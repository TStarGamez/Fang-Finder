package dev.fangfinder;

import java.util.Locale;

/**
 * One tracked MACHINE: the newest fang ray it has produced.
 *
 * <p>Because the target moves, older rays from the same machine point at
 * old positions - only the freshest bearing per machine enters the solver.
 * A machine is recognized by its volleys being anchored near each other
 * (see {@link FangFinderConfig#machineRadius}).
 */
public final class Measurement {
	private final int id;
	private double anchorX;
	private double anchorZ;
	private double dirX;
	private double dirZ;
	private double yaw;
	private int fangCount;
	private int expectedCount;
	private Double evokerX;
	private Double evokerZ;
	private boolean spacingOk;
	private int repeats;
	private long seenTick;
	private String targetName;

	public Measurement(int id, double anchorX, double anchorZ, double dirX,
			double dirZ, double yaw, int fangCount, int expectedCount,
			Double evokerX, Double evokerZ, boolean spacingOk,
			long seenTick, String targetName) {
		this.id = id;
		this.anchorX = anchorX;
		this.anchorZ = anchorZ;
		this.dirX = dirX;
		this.dirZ = dirZ;
		this.yaw = yaw;
		this.fangCount = fangCount;
		this.expectedCount = expectedCount;
		this.evokerX = evokerX;
		this.evokerZ = evokerZ;
		this.spacingOk = spacingOk;
		this.repeats = 1;
		this.seenTick = seenTick;
		this.targetName = targetName;
	}

	public int id() {
		return id;
	}

	public double anchorX() {
		return anchorX;
	}

	public double anchorZ() {
		return anchorZ;
	}

	public double dirX() {
		return dirX;
	}

	public double dirZ() {
		return dirZ;
	}

	public double yaw() {
		return yaw;
	}

	public int fangCount() {
		return fangCount;
	}

	public int expectedCount() {
		return expectedCount;
	}

	public Double evokerX() {
		return evokerX;
	}

	public Double evokerZ() {
		return evokerZ;
	}

	public boolean spacingOk() {
		return spacingOk;
	}

	public int repeats() {
		return repeats;
	}

	public long seenTick() {
		return seenTick;
	}

	public String targetName() {
		return targetName;
	}

	/** True if the new volley's anchor belongs to this machine. */
	public boolean sameMachine(double ax, double az, double radius) {
		return Math.hypot(ax - anchorX, az - anchorZ) <= radius;
	}

	/**
	 * True if a bearing falls in the same sin-table bucket (~0.0055 degree
	 * steps) as the current ray - i.e. the target has not visibly moved.
	 */
	public boolean sameBearing(double yawDeg) {
		double d = Math.abs(yawDeg - yaw) % 360.0;
		if (d > 180.0) {
			d = 360.0 - d;
		}
		return d < 0.002;
	}

	/**
	 * Replace this machine's ray with its newest volley.
	 *
	 * @return true if the bearing actually changed (the target moved)
	 */
	public boolean update(double ax, double az, double ndx, double ndz,
			double nyaw, int nFangs, int nExpected, Double ex, Double ez,
			boolean ok, long tick, String name) {
		boolean moved = !sameBearing(nyaw);
		this.anchorX = ax;
		this.anchorZ = az;
		this.dirX = ndx;
		this.dirZ = ndz;
		this.yaw = nyaw;
		this.fangCount = nFangs;
		this.expectedCount = nExpected;
		this.evokerX = ex;
		this.evokerZ = ez;
		this.spacingOk = ok;
		this.seenTick = tick;
		if (name != null) {
			this.targetName = name;
		}
		this.repeats = moved ? 1 : this.repeats + 1;
		return moved;
	}

	/** Perpendicular distance from a point to this ray's carrier line. */
	public double missDistance(double[] point) {
		double vx = point[0] - anchorX;
		double vz = point[1] - anchorZ;
		double along = vx * dirX + vz * dirZ;
		double px = vx - along * dirX;
		double pz = vz - along * dirZ;
		return Math.hypot(px, pz);
	}

	/** Line format shared with the desktop triangulator: "x z yaw". */
	public String copyLine() {
		return String.format(Locale.ROOT, "%.6f %.6f %.10f",
				anchorX, anchorZ, yaw);
	}
}
