package dev.fangfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * A named set of rays imported through data sharing. Each set solves its
 * own fix and draws on the map in its own color, independently of the
 * live machine measurements.
 */
public final class RaySet {
	private static final int[] PALETTE = {
			0xFF4FD7FF, 0xFFB07CFF, 0xFFFF8ACD, 0xFF7CFF9B,
			0xFFFFB86B, 0xFF8AB4FF, 0xFFFFF06B, 0xFF6BFFE0,
	};

	public final String name;
	public final String from;
	public final List<Measurement> rays = new ArrayList<>();
	public final int color;
	public double[] fix;
	public double worstMiss;

	public RaySet(String name, String from, int index) {
		this.name = name;
		this.from = from;
		this.color = PALETTE[Math.floorMod(index, PALETTE.length)];
	}

	public void addRay(double x, double z, double yaw) {
		if (rays.size() >= 12) {
			return;
		}
		double dx = -Math.sin(Math.toRadians(yaw));
		double dz = Math.cos(Math.toRadians(yaw));
		rays.add(new Measurement(rays.size() + 1, x, z, dx, dz, yaw, 0, 0,
				null, null, true, 0, null));
	}

	public void solve() {
		fix = Solver.intersect(rays);
		worstMiss = 0.0;
		if (fix != null) {
			for (Measurement m : rays) {
				worstMiss = Math.max(worstMiss, m.missDistance(fix));
			}
		}
	}
}
