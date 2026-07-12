package dev.fangfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Firing solution math for cubicmetre's Orbital Strike Cannon, ported 1:1
 * from the official fire-control app (github.com/cubicmetre/osc-fire-control,
 * src/utils/calculations.ts + validation.ts + validPasscodes.ts).
 *
 * <p>Supports the OSC Mark 6 / 6.1 and the Mark 6 MS variant (the MS uses a
 * different X/Z payload offset and subtracts 3.51 from DY).
 */
public final class OscMath {
	private OscMath() {
	}

	// Constants from the Excel formulas (verbatim)
	private static final double DV_PLUS_X = 8.755553287151535;
	private static final double DV_PLUS_Y = 8.816216069168268;
	private static final double DV_PLUS_Z = 8.755553287151535;
	private static final double DV_MINUS_X = -8.664591055789826;
	private static final double DV_MINUS_Z = -8.664591055789826;
	private static final double DV_YX = -0.596968396919667;
	private static final double DY_MS = 3.51;
	private static final double DY_STAB = 106.92 + 44.4;
	private static final double DY_NUKE = 140;
	private static final double DX = 22.5000000095367;
	private static final double DZ = 0.9999999904633 - 1;
	private static final double DXZ_MS = 8.43750000953673;
	private static final double SLOWDOWN_XZ = 0.900000035762786;
	private static final double SLOWDOWN_Y = 1.5;

	/** 222 valid passcodes that never misfired over >10,000 cycles. */
	public static final int[] VALID_PASSCODES = {
			63, 430, 635, 751, 859, 950, 95, 437, 636, 753, 862, 951,
			111, 438, 637, 754, 863, 953, 123, 439, 639, 755, 867, 956,
			125, 445, 655, 756, 869, 957, 126, 446, 671, 758, 871, 958,
			175, 447, 679, 761, 873, 973, 189, 459, 683, 763, 875, 974,
			190, 474, 685, 764, 876, 979, 191, 475, 686, 765, 877, 982,
			223, 479, 687, 766, 879, 986, 235, 483, 691, 767, 881, 987,
			238, 489, 693, 783, 882, 988, 239, 490, 694, 791, 884, 990,
			243, 491, 695, 797, 885, 991, 250, 494, 697, 799, 886, 994,
			251, 495, 699, 807, 887, 995, 254, 499, 701, 811, 892, 997,
			287, 503, 702, 813, 893, 998, 303, 505, 703, 814, 894, 999,
			311, 511, 711, 815, 895, 1001, 315, 559, 717, 821, 909, 1002,
			318, 567, 718, 822, 917, 1003, 335, 573, 719, 823, 921, 1005,
			351, 575, 727, 825, 923, 1006, 359, 591, 730, 827, 931, 1007,
			365, 599, 733, 828, 933, 1009, 367, 605, 735, 829, 934, 1011,
			373, 607, 739, 830, 935, 1013, 377, 619, 742, 831, 937, 1014,
			378, 621, 743, 839, 938, 1017, 380, 622, 745, 843, 939, 1018,
			382, 623, 746, 846, 940, 1019, 413, 629, 747, 847, 941, 1020,
			415, 630, 748, 854, 942, 1021, 423, 631, 749, 855, 943, 1022,
			429, 633, 750, 857, 949, 1023,
	};

	public static boolean isValidPasscode(int passcode) {
		for (int p : VALID_PASSCODES) {
			if (p == passcode) {
				return true;
			}
		}
		return false;
	}

	/** Full firing solution. Arrays are indexed x=0, y=1, z=2. */
	public static final class Result {
		public final int[] coarse = new int[3];
		public final int[] fine = new int[3];
		public final double[] count = new double[3];
		public final double[] diff = new double[3];
		public final double[] exactPos = new double[3];
		public int[][] grid = new int[5][13];
		public String timeTotal = "";
		public String timePayload = "";
		public String timeAccel = "";
		public final List<String> errors = new ArrayList<>();

		public boolean valid() {
			return errors.isEmpty();
		}
	}

	/**
	 * @param ms true for the OSC Mark 6 MS variant, false for Mark 6 / 6.1
	 */
	public static Result calculate(int originX, int originY, int originZ,
			int targetX, int targetY, int targetZ, boolean nuke,
			int nukeSize, int stabDepth, int magazineSlot, int passcode,
			boolean ms) {
		Result r = new Result();

		// Step 1: firing direction for X and Z
		double dvX = targetX > originX ? DV_PLUS_X : DV_MINUS_X;
		double dvZ = targetZ > originZ ? DV_PLUS_Z : DV_MINUS_Z;

		// Step 2: DY from fire mode; MS variant subtracts DY_MS
		double baseDY = nuke ? DY_NUKE : DY_STAB;
		double dY = ms ? baseDY - DY_MS : baseDY;

		// Initial payload position (MS uses DXZ_MS for both X and Z)
		double dX = ms ? DXZ_MS : DX;
		double dZ = ms ? DXZ_MS : DZ;
		double iX = originX + dX + magazineSlot;
		double iY = originY + 2.1975;
		double iZ = originZ + dZ;

		// Step 3: diffY, clamped to >= 50
		double diffY = (dY + targetY) - iY;
		if (diffY < 50) {
			diffY = 50;
		}

		// Steps 4-5: Y count -> coarse + fine
		double countY = diffY / (SLOWDOWN_Y * DV_PLUS_Y);
		int coarseY = (int) Math.floor(countY);
		int fineY = (int) Math.round((countY - coarseY) * 10);

		// Steps 6-7: X drift compensation and Z difference
		double diffX = targetX - (iX + (coarseY + fineY / 10.0) * DV_YX);
		double diffZ = targetZ - iZ;

		// Step 8: X/Z counts
		double countX = diffX / (SLOWDOWN_XZ * dvX);
		double countZ = diffZ / (SLOWDOWN_XZ * dvZ);

		int coarseX = (int) Math.floor(Math.abs(countX));
		int fineX = (int) Math.round((Math.abs(countX) - coarseX) * 10);
		int coarseZ = (int) Math.floor(Math.abs(countZ));
		int fineZ = (int) Math.round((Math.abs(countZ) - coarseZ) * 10);

		// Exact motion and final payload position
		double vX = dvX * (coarseX + fineX / 10.0)
				+ DV_YX * (coarseY + fineY / 10.0);
		double vY = DV_PLUS_Y * (coarseY + fineY / 10.0);
		double vZ = dvZ * (coarseZ + fineZ / 10.0);
		r.exactPos[0] = iX + SLOWDOWN_XZ * vX;
		r.exactPos[1] = iY + SLOWDOWN_Y * vY;
		r.exactPos[2] = iZ + SLOWDOWN_XZ * vZ;

		r.coarse[0] = coarseX;
		r.coarse[1] = coarseY;
		r.coarse[2] = coarseZ;
		r.fine[0] = fineX;
		r.fine[1] = fineY;
		r.fine[2] = fineZ;
		r.count[0] = countX;
		r.count[1] = countY;
		r.count[2] = countZ;
		r.diff[0] = diffX;
		r.diff[1] = diffY;
		r.diff[2] = diffZ;

		// Payload size: nuke -> nukeSize; stab -> CEILING(depth / (9*0.99))
		int payloadSize = nuke ? nukeSize
				: (int) Math.ceil(stabDepth / (9 * 0.99));

		r.grid = generateBinaryGrid(coarseX, coarseY, coarseZ, fineX, fineY,
				fineZ, passcode, diffX, diffZ, nuke, payloadSize);

		// Time estimate
		double payloadTicks = nuke
				? 6 * ((nukeSize * (nukeSize + 1)) / 2.0 + nukeSize * 2.4375)
						+ 930
				: 6 * (2 * Math.round(stabDepth / (9 * 0.99))) + 1300;
		double accelTicks = 6 * (Math.max(coarseX, coarseZ) + coarseY + 2);
		r.timePayload = fmtTime((long) Math.ceil(payloadTicks / 20));
		r.timeAccel = fmtTime((long) Math.ceil(accelTicks / 20));
		r.timeTotal = fmtTime((long) Math.ceil((payloadTicks + accelTicks) / 20));

		// ---- validation (ported from validation.ts) ----
		if (originX % 16 != 0) {
			r.errors.add("Origin X (" + originX + ") must be divisible by 16");
		}
		if (originY % 16 != 0) {
			r.errors.add("Origin Y (" + originY + ") must be divisible by 16");
		}
		if (originZ % 16 != 0) {
			r.errors.add("Origin Z (" + originZ + ") must be divisible by 16");
		}
		if (nuke) {
			if (nukeSize < 1) {
				r.errors.add("Nuke size must be at least 1");
			}
			if (nukeSize > 31) {
				r.errors.add("Nuke size must be 31 or less");
			}
		} else {
			if (stabDepth < 1) {
				r.errors.add("Stab depth must be at least 1");
			}
			if (Math.ceil(stabDepth / (9 * 0.99)) > 31) {
				r.errors.add("Stab depth too large (max ~275)");
			}
		}
		double dxo = targetX - originX;
		double dzo = targetZ - originZ;
		double dist = Math.sqrt(dxo * dxo + dzo * dzo);
		if (dist < 64) {
			r.errors.add(String.format(Locale.ROOT,
					"Distance (%.1f) must be at least 64 blocks", dist));
		}
		if (!isValidPasscode(passcode)) {
			r.errors.add("Passcode " + passcode + " is not in the valid list");
		}
		if (Math.abs(countX) >= 32767.999) {
			r.errors.add("X distance too large (count overflow)");
		}
		if (Math.abs(countZ) >= 32767.999) {
			r.errors.add("Z distance too large (count overflow)");
		}
		if (Math.abs(countY) >= 31.999) {
			r.errors.add("Y distance too large (count overflow)");
		}
		if (Math.abs(countX) < 1 && Math.abs(countZ) < 1) {
			r.errors.add("Target too close (both X and Z counts < 1)");
		}
		return r;
	}

	/** Excel: MOD(FLOOR(value/divisor,1),2) */
	private static int bit(long value, long divisor) {
		return (int) ((Math.abs(value) / divisor) % 2);
	}

	private static int inv(long value, long divisor) {
		return 1 - bit(value, divisor);
	}

	/** The 5x13 LED grid, row by row, exactly as the fire-control app. */
	private static int[][] generateBinaryGrid(int cx, int cy, int cz,
			int fx, int fy, int fz, int pass, double diffX, double diffZ,
			boolean nuke, int payload) {
		return new int[][]{
				{bit(cz, 1), bit(cz, 32), bit(cz, 1024), inv(fz, 8),
						inv(fy, 8), bit(cy, 8), inv(fx, 8), bit(cx, 8192),
						bit(cx, 256), bit(cx, 8), bit(payload, 2),
						bit(pass, 512), bit(pass, 16)},
				{bit(cz, 2), bit(cz, 64), bit(cz, 2048), inv(fz, 4),
						inv(fy, 4), bit(cy, 4), inv(fx, 4), bit(cx, 4096),
						bit(cx, 128), bit(cx, 4), bit(payload, 4),
						bit(pass, 256), bit(pass, 8)},
				{bit(cz, 4), bit(cz, 128), bit(cz, 4096), inv(fz, 2),
						inv(fy, 2), bit(cy, 2), inv(fx, 2), bit(cx, 2048),
						bit(cx, 64), bit(cx, 2), bit(payload, 8),
						bit(pass, 128), bit(pass, 4)},
				{bit(cz, 8), bit(cz, 256), bit(cz, 8192), inv(fz, 1),
						inv(fy, 1), bit(cy, 1), inv(fx, 1), bit(cx, 1024),
						bit(cx, 32), bit(cx, 1), bit(payload, 16),
						bit(pass, 64), bit(pass, 2)},
				{bit(cz, 16), bit(cz, 512), bit(cz, 16384),
						diffZ < 0 ? 1 : 0, bit(cy, 16), diffX < 0 ? 1 : 0,
						bit(cx, 16384), bit(cx, 512), bit(cx, 16),
						bit(payload, 1), nuke ? 1 : 0, bit(pass, 32),
						bit(pass, 1)},
		};
	}

	private static String fmtTime(long seconds) {
		return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
				(seconds % 3600) / 60, seconds % 60);
	}
}
