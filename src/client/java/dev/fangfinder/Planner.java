package dev.fangfinder;

import java.util.Locale;

/**
 * Optimal tracker placement.
 *
 * <p>Inputs: world-border center and size (diameter), the number of
 * trackers, and an optional hunting-focus radius (0 = plan for the whole
 * bordered area). The planner searches ring layouts (a regular N-gon of
 * trackers around the center, plus the variant with one tracker AT the
 * center) over a sweep of ring radii and rotations, and picks the layout
 * minimizing the WORST-CASE fix error over a grid of possible target
 * positions.
 *
 * <p>Error model: per-ray angular error is about half a sin-table step
 * (~5e-5 rad, the game's own aim quantization). A target's fix error is
 * approximated by its best tracker pair: err = eps * sqrt(d1^2 + d2^2) /
 * |sin(separation)| - which is why spreading trackers AROUND the hunting
 * area beats clustering them, and why bearings near 0 or 180 degrees are
 * useless.
 */
public final class Planner {
	private static final double EPS = 5.0e-5;

	private Planner() {
	}

	public static final class Result {
		public double[][] positions; // [n][2] = x, z
		public double ringRadius;
		public boolean centerTracker;
		public double worstErr;
		public double avgErr;
		public double coveragePct;   // % of sampled targets under 3 blocks
		public boolean refined;      // whether local search improved it

		public String summary() {
			String worst = worstErr >= 1.0e6
					? "blind spots exist (2 trackers can't resolve the "
							+ "line through them - use 3+)"
					: String.format(Locale.ROOT,
							"worst ~%.2f · avg ~%.2f blocks · %.0f%% good"
									+ " coverage", worstErr, avgErr,
							coveragePct);
			return String.format(Locale.ROOT,
					"%d trackers · ring r=%.0f%s%s · %s",
					positions.length, ringRadius,
					centerTracker ? " + center" : "",
					refined ? " (refined)" : "", worst);
		}

		public String copyText() {
			StringBuilder sb = new StringBuilder(summary()).append('\n');
			for (int i = 0; i < positions.length; i++) {
				sb.append(String.format(Locale.ROOT, "P%d  %.0f %.0f%n",
						i + 1, positions[i][0], positions[i][1]));
			}
			return sb.toString();
		}
	}

	public static Result plan(double centerX, double centerZ, double size,
			int n, double focusRadius) {
		n = Math.max(2, Math.min(8, n));
		double borderR = Math.max(64, size / 2.0);
		double huntR = focusRadius > 0
				? Math.min(focusRadius, borderR) : borderR;

		// target grid: 15x15 across the (square) hunting area
		int grid = 15;
		double[][] targets = new double[grid * grid][2];
		int t = 0;
		for (int i = 0; i < grid; i++) {
			for (int j = 0; j < grid; j++) {
				targets[t][0] = centerX + (i / (grid - 1.0) * 2 - 1) * huntR;
				targets[t][1] = centerZ + (j / (grid - 1.0) * 2 - 1) * huntR;
				t++;
			}
		}

		Result best = null;
		// Candidate families: plain ring, ring+center, and a two-radius
		// split ring (half the trackers on an inner ring, half on outer)
		// which covers both near and far targets better for larger n.
		for (int layout = 0; layout <= 2; layout++) {
			for (double frac = 0.12; frac <= 0.98; frac += 0.02) {
				double r = Math.min(frac * huntR * 1.4, borderR * 0.98);
				for (int rotStep = 0; rotStep < 3; rotStep++) {
					double[][] pos = candidate(layout, centerX, centerZ, r,
							n, rotStep, borderR);
					if (pos == null) {
						continue;
					}
					best = consider(best, pos, targets, r,
							layout == 1);
				}
			}
		}
		if (best == null) {
			return null;
		}

		// Local refinement: coordinate descent nudging each tracker to
		// cut the worst-case error the ring layout couldn't reach.
		Result refined = refine(best, targets, borderR, centerX, centerZ);
		if (refined.worstErr < best.worstErr * 0.999) {
			refined.refined = true;
			best = refined;
		}
		best.coveragePct = coverage(best.positions, targets);
		return best;
	}

	private static double[][] candidate(int layout, double cx, double cz,
			double r, int n, int rotStep, double borderR) {
		double rot = rotStep * Math.PI / Math.max(1, n) * 0.66;
		if (layout == 0) {
			return layoutPositions(cx, cz, r, n, rot, false);
		}
		if (layout == 1) {
			if (n < 3) {
				return null;
			}
			return layoutPositions(cx, cz, r, n - 1, rot, true);
		}
		// split ring: inner half at 0.55r, outer half at r
		if (n < 4) {
			return null;
		}
		int outer = (n + 1) / 2;
		int inner = n - outer;
		double[][] pos = new double[n][2];
		for (int i = 0; i < outer; i++) {
			double a = rot + 2 * Math.PI * i / outer;
			pos[i][0] = clamp(cx + r * Math.cos(a), cx, borderR);
			pos[i][1] = clamp(cz + r * Math.sin(a), cz, borderR);
		}
		for (int i = 0; i < inner; i++) {
			double a = rot + Math.PI / inner + 2 * Math.PI * i / inner;
			pos[outer + i][0] = clamp(cx + 0.55 * r * Math.cos(a), cx,
					borderR);
			pos[outer + i][1] = clamp(cz + 0.55 * r * Math.sin(a), cz,
					borderR);
		}
		return pos;
	}

	private static Result consider(Result best, double[][] pos,
			double[][] targets, double r, boolean center) {
		double worst = 0;
		double sum = 0;
		for (double[] target : targets) {
			double e = targetError(target, pos);
			worst = Math.max(worst, e);
			sum += Math.min(e, 1.0e6);
		}
		if (best == null || worst < best.worstErr) {
			Result res = new Result();
			res.positions = pos;
			res.ringRadius = r;
			res.centerTracker = center;
			res.worstErr = worst;
			res.avgErr = sum / targets.length;
			return res;
		}
		return best;
	}

	/** Coordinate descent: nudge each tracker to lower worst-case error. */
	private static Result refine(Result seed, double[][] targets,
			double borderR, double cx, double cz) {
		double[][] pos = new double[seed.positions.length][2];
		for (int i = 0; i < pos.length; i++) {
			pos[i][0] = seed.positions[i][0];
			pos[i][1] = seed.positions[i][1];
		}
		double step = borderR * 0.08;
		double curWorst = worstError(pos, targets);
		for (int pass = 0; pass < 40 && step > 1.0; pass++) {
			boolean improved = false;
			for (double[] p : pos) {
				double ox = p[0];
				double oz = p[1];
				double bestLocal = curWorst;
				double bx = ox;
				double bz = oz;
				int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
						{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
				for (int[] d : dirs) {
					p[0] = clamp(ox + d[0] * step, cx, borderR);
					p[1] = clamp(oz + d[1] * step, cz, borderR);
					double w = worstError(pos, targets);
					if (w < bestLocal) {
						bestLocal = w;
						bx = p[0];
						bz = p[1];
					}
				}
				p[0] = bx;
				p[1] = bz;
				if (bestLocal < curWorst - 1e-9) {
					curWorst = bestLocal;
					improved = true;
				}
			}
			if (!improved) {
				step *= 0.5;
			}
		}
		double sum = 0;
		for (double[] target : targets) {
			sum += Math.min(targetError(target, pos), 1.0e6);
		}
		Result res = new Result();
		res.positions = pos;
		res.ringRadius = seed.ringRadius;
		res.centerTracker = seed.centerTracker;
		res.worstErr = curWorst;
		res.avgErr = sum / targets.length;
		return res;
	}

	private static double worstError(double[][] pos, double[][] targets) {
		double worst = 0;
		for (double[] target : targets) {
			worst = Math.max(worst, targetError(target, pos));
		}
		return worst;
	}

	private static double coverage(double[][] pos, double[][] targets) {
		int good = 0;
		for (double[] target : targets) {
			if (targetError(target, pos) < 3.0) {
				good++;
			}
		}
		return 100.0 * good / targets.length;
	}

	private static double clamp(double v, double center, double borderR) {
		double lo = center - borderR * 0.98;
		double hi = center + borderR * 0.98;
		return Math.max(lo, Math.min(hi, v));
	}

	private static double[][] layoutPositions(double cx, double cz,
			double r, int ringCount, double rot, boolean center) {
		int n = ringCount + (center ? 1 : 0);
		double[][] pos = new double[n][2];
		int idx = 0;
		if (center) {
			pos[idx][0] = cx;
			pos[idx][1] = cz;
			idx++;
		}
		for (int i = 0; i < ringCount; i++) {
			double a = rot + 2 * Math.PI * i / ringCount;
			pos[idx][0] = cx + r * Math.cos(a);
			pos[idx][1] = cz + r * Math.sin(a);
			idx++;
		}
		return pos;
	}

	/** Best-pair fix error estimate for one target position. */
	private static double targetError(double[] target, double[][] pos) {
		double best = 1.0e9;
		for (int i = 0; i < pos.length; i++) {
			double vix = target[0] - pos[i][0];
			double viz = target[1] - pos[i][1];
			double di = Math.hypot(vix, viz);
			if (di < 8) {
				continue; // ring attack range / on top of the machine
			}
			for (int j = i + 1; j < pos.length; j++) {
				double vjx = target[0] - pos[j][0];
				double vjz = target[1] - pos[j][1];
				double dj = Math.hypot(vjx, vjz);
				if (dj < 8) {
					continue;
				}
				double sin = Math.abs(vix * vjz - viz * vjx) / (di * dj);
				if (sin < 1.0e-6) {
					continue; // collinear pair: no information
				}
				best = Math.min(best,
						EPS * Math.hypot(di, dj) / sin);
			}
		}
		return best;
	}
}
