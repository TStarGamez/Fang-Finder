package dev.fangfinder;

import java.util.List;

/**
 * Least-squares intersection of N rays on the X/Z plane.
 *
 * <p>Minimizes sum_i || (I - d_i d_i^T)(p - P_i) ||^2, giving the 2x2 linear
 * system A p = b with A = sum(I - d d^T) and b = sum((I - d d^T) P). For two
 * non-parallel rays this is their exact crossing point.
 */
public final class Solver {
	private Solver() {
	}

	/** @return best-fit {x, z}, or null if under-determined / parallel. */
	public static double[] intersect(List<Measurement> rays) {
		if (rays.size() < 2) {
			return null;
		}
		double a11 = 0.0;
		double a12 = 0.0;
		double a22 = 0.0;
		double b1 = 0.0;
		double b2 = 0.0;
		for (Measurement m : rays) {
			double dx = m.dirX();
			double dz = m.dirZ();
			double m11 = 1.0 - dx * dx;
			double m12 = -dx * dz;
			double m22 = 1.0 - dz * dz;
			a11 += m11;
			a12 += m12;
			a22 += m22;
			b1 += m11 * m.anchorX() + m12 * m.anchorZ();
			b2 += m12 * m.anchorX() + m22 * m.anchorZ();
		}
		double det = a11 * a22 - a12 * a12;
		if (Math.abs(det) < 1.0e-9) {
			return null;
		}
		return new double[]{
				(b1 * a22 - b2 * a12) / det,
				(a11 * b2 - a12 * b1) / det
		};
	}
}
