package dev.fangfinder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Persisted settings (config/fangfinder.json). */
public final class FangFinderConfig {
	/** Master switch - when off, no fangs are collected and no HUD shows. */
	public boolean enabled = true;
	/** Chat announcements for new/updated bearings (warnings always show). */
	public boolean chatMessages = true;
	/**
	 * Volleys anchored within this many blocks of an existing measurement
	 * are treated as the SAME machine, and only its newest ray is kept.
	 * Keep separate machines further apart than this.
	 */
	public int machineRadius = 32;
	/**
	 * Rays older than this many seconds are excluded from the fix
	 * (shown gray on the HUD). 0 disables expiry.
	 */
	public int maxRayAgeSeconds = 0;

	// ---- Orbital Strike Cannon fire control (map screen, OSC tab) ----
	public int oscOriginX = 0;
	public int oscOriginY = 0;
	public int oscOriginZ = 0;
	public int oscTargetX = 0;
	public int oscTargetY = 64;
	public int oscTargetZ = 0;
	public int oscPasscode = 940;
	public int oscMagazineSlot = 0;
	public int oscNukeSize = 10;
	public int oscStabDepth = 10;
	public boolean oscNuke = true;
	public boolean oscMs = false;

	// ---- UI / misc ----
	/** Map screen HUD/panel background opacity, percent. */
	public int panelOpacity = 90;
	/** Half-width (blocks) the map screen opens zoomed to show. */
	public int defaultZoomBlocks = 2000;
	/** Show the mini logo in the in-game HUD overlay, not just the screen. */
	public boolean showHudLogo = true;

	/** Play a ping when a brand-new fix is computed (a fresh target lock). */
	public boolean fixSound = true;
	/** Warn (chat + sound) when a volley is aimed at the local player. */
	public boolean selfAlert = true;

	private static FangFinderConfig instance;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
			.create();

	public static FangFinderConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("fangfinder.json");
	}

	private static FangFinderConfig load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				FangFinderConfig c = GSON.fromJson(Files.readString(p),
						FangFinderConfig.class);
				if (c != null) {
					return c;
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// fall through to defaults
		}
		FangFinderConfig c = new FangFinderConfig();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException ignored) {
			// non-fatal
		}
	}

	/**
	 * Applies the panelOpacity setting to an opaque ARGB color. Every
	 * panel background across the HUD, map screen, and config screen
	 * should call this rather than drawing with a fixed alpha, so the
	 * opacity setting actually affects everything it's supposed to.
	 */
	public static int applyPanelOpacity(int argb) {
		int op = Math.max(20, Math.min(100, get().panelOpacity));
		int alpha = (op * 255 / 100) << 24;
		return (argb & 0x00FFFFFF) | alpha;
	}
}
