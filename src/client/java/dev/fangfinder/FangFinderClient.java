package dev.fangfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.world.entity.EntityType;

import net.fabricmc.api.ClientModInitializer;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/**
 * FangFinder - client-side evoker fang bearing reader.
 *
 * <p>Fang spawn packets carry positions as full doubles while entity
 * rotation is byte-quantized, so the aim angle is recovered from fang
 * positions (exact) rather than rotations (~1.4 degree error).
 *
 * <p>Because the tracked player moves, each machine contributes only its
 * NEWEST ray to the fix. Machines are recognized by their volleys being
 * anchored near each other (config: machineRadius).
 */
public final class FangFinderClient implements ClientModInitializer {
	public static final String MOD_ID = "fangfinder";

	/** Spacing between consecutive fangs of a line volley, from vanilla. */
	private static final double FANG_SPACING = 1.25;
	/** Max perpendicular scatter (blocks) for points to count as one line. */
	private static final double COLLINEAR_EPS = 1.0e-6;
	/** Ticks of silence after the last fang before a volley is finalized. */
	private static final int SETTLE_TICKS = 2;
	/** Rolling cap on tracked machines. */
	private static final int MAX_MACHINES = 12;

	// volley collection
	private static final List<double[]> pending = new ArrayList<>();
	private static long tick = 0;
	private static long lastFangTick = Long.MIN_VALUE;

	// results
	static final List<Measurement> machines = new ArrayList<>();
	static double[] fix = null;          // {x, z} or null
	static double worstMiss = 0.0;
	static double bestPairSepDeg = 0.0;  // widest angle between active rays
	static boolean hudVisible = true;
	private static int nextId = 1;

	private KeyMapping mapKey;
	private KeyMapping toggleModKey;
	private KeyMapping guideKey;
	private KeyMapping toggleHudKey;
	private KeyMapping copyKey;
	private KeyMapping clearKey;

	@Override
	public void onInitializeClient() {
		FangFinderConfig.get(); // load early

		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		mapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fangfinder.map", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_N, category));
		toggleModKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fangfinder.toggle_mod", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F6, category));
		guideKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fangfinder.guide", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F7, category));
		toggleHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fangfinder.toggle_hud", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F8, category));
		copyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fangfinder.copy", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F9, category));
		clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.fangfinder.clear", InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F10, category));

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (FangFinderConfig.get().enabled
					&& entity.getType() == EntityType.EVOKER_FANGS) {
				synchronized (pending) {
					pending.add(new double[]{entity.getX(), entity.getZ()});
					lastFangTick = tick;
				}
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tick++;
			synchronized (pending) {
				if (!pending.isEmpty() && tick - lastFangTick >= SETTLE_TICKS) {
					if (FangFinderConfig.get().enabled) {
						processVolley(new ArrayList<>(pending));
					}
					pending.clear();
				}
			}
			// stale rays dropping out of the age window change the fix
			if (FangFinderConfig.get().enabled
					&& FangFinderConfig.get().maxRayAgeSeconds > 0
					&& tick % 20 == 0 && !machines.isEmpty()) {
				recomputeFix();
			}
			while (mapKey.consumeClick()) {
				client.setScreen(new MapScreen());
			}
			while (toggleModKey.consumeClick()) {
				FangFinderConfig cfg = FangFinderConfig.get();
				cfg.enabled = !cfg.enabled;
				cfg.save();
				chat(Component.literal("[FangFinder] "
						+ (cfg.enabled ? "enabled" : "disabled"))
						.withStyle(cfg.enabled ? ChatFormatting.GREEN
								: ChatFormatting.RED));
			}
			while (guideKey.consumeClick()) {
				printPlacementGuide();
			}
			while (toggleHudKey.consumeClick()) {
				hudVisible = !hudVisible;
			}
			while (copyKey.consumeClick()) {
				sendCopyAll();
			}
			while (clearKey.consumeClick()) {
				clearAllData();
				chat(Component.literal("[FangFinder] measurements cleared")
						.withStyle(ChatFormatting.GRAY));
			}
		});

		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
				FangFinderClient::renderHud);

		// ---- data sharing: parse incoming chat for share payloads ----
		ClientReceiveMessageEvents.CHAT.register(
				(message, signed, sender, boundType, timestamp) -> {
					if (FangFinderConfig.get().enabled) {
						Sharing.onChat(message.getString(),
								profileName(sender));
					}
				});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay && FangFinderConfig.get().enabled) {
				Sharing.onChat(message.getString(), null);
			}
		});

		// ---- /fangfinder client commands ----
		ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess) -> dispatcher.register(
						ClientCommands.literal("fangfinder")
								.then(ClientCommands.literal("share")
										.then(ClientCommands.argument("name",
												StringArgumentType.word())
												.executes(ctx -> {
													Sharing.shareSet(
															StringArgumentType
																	.getString(ctx,
																			"name"));
													return Command.SINGLE_SUCCESS;
												})))
								.then(ClientCommands.literal("shareray")
										.executes(ctx -> {
											Sharing.shareLastRay();
											return Command.SINGLE_SUCCESS;
										}))
								.then(ClientCommands.literal("sets")
										.executes(ctx -> {
											Sharing.listSets();
											return Command.SINGLE_SUCCESS;
										}))
								.then(ClientCommands.literal("remove")
										.then(ClientCommands.argument("name",
												StringArgumentType.word())
												.executes(ctx -> {
													Sharing.removeSet(
															StringArgumentType
																	.getString(ctx,
																			"name"));
													return Command.SINGLE_SUCCESS;
												})))
								.then(ClientCommands.literal("resolve")
										.then(ClientCommands.argument("choice",
												StringArgumentType.word())
												.executes(ctx -> {
													Sharing.resolvePending(
															StringArgumentType
																	.getString(ctx,
																			"choice"));
													return Command.SINGLE_SUCCESS;
												})))
								.then(ClientCommands.literal("help")
										.executes(ctx -> {
											printHelp();
											return Command.SINGLE_SUCCESS;
										}))
								.then(ClientCommands.literal("status")
										.executes(ctx -> {
											printStatus();
											return Command.SINGLE_SUCCESS;
										}))
								.then(ClientCommands.literal("clear")
										.executes(ctx -> {
											clearAllData();
											chat(Component.literal(
													"[FangFinder] cleared")
													.withStyle(
															ChatFormatting.GRAY));
											return Command.SINGLE_SUCCESS;
										}))
								.executes(ctx -> {
									printHelp();
									return Command.SINGLE_SUCCESS;
								})));
	}

	/** Lists commands and current keybinds in chat. */
	private static void printHelp() {
		chat(Component.literal("[FangFinder] commands")
				.withStyle(ChatFormatting.RED));
		String[] rows = {
				"/fangfinder help - this list",
				"/fangfinder status - fix, machines, geometry",
				"/fangfinder share <name> - copy a named ray set "
						+ "to clipboard",
				"/fangfinder shareray - copy your newest ray "
						+ "to clipboard",
				"/fangfinder sets / remove <name> - manage imports",
				"/fangfinder clear - wipe all rays and the fix",
				"keys: N map · F6 mod · F7 guide · F8 hud · "
						+ "F9 copy · F10 clear",
		};
		for (String r : rows) {
			chat(Component.literal("  " + r)
					.withStyle(ChatFormatting.GOLD));
		}
	}

	/** One-line summary of the current tracking state. */
	private static void printStatus() {
		MutableComponent m = Component.literal("[FangFinder] ")
				.withStyle(ChatFormatting.RED);
		if (fix == null) {
			m.append(Component.literal(machines.size()
					+ " machine(s), no fix yet")
					.withStyle(ChatFormatting.GOLD));
			chat(m);
			return;
		}
		String s = String.format(Locale.ROOT,
				"%d machine(s) · fix X %.1f Z %.1f · worst miss %.2f · "
						+ "geometry %.1f deg%s", machines.size(), fix[0],
				fix[1], worstMiss, bestPairSepDeg,
				bestPairSepDeg < 5.0 ? " (WEAK)" : "");
		m.append(Component.literal(s).withStyle(
				bestPairSepDeg < 5.0 ? ChatFormatting.GOLD
						: ChatFormatting.GREEN));
		chat(m);
		if (Minecraft.getInstance().player != null) {
			net.minecraft.client.player.LocalPlayer p =
					Minecraft.getInstance().player;
			double dx = fix[0] - p.getX();
			double dz = fix[1] - p.getZ();
			double dist = Math.hypot(dx, dz);
			double bearing = normalizeYawDeg(
					Math.toDegrees(Math.atan2(-dx, dz)));
			chat(Component.literal(String.format(Locale.ROOT,
					"  from you: %.0f blocks away, bearing %.1f "
							+ "(point your F3 Facing at this yaw)",
					dist, bearing)).withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * Reads a display name from a Mojang GameProfile without hard-coding
	 * the accessor. Mojang has flipped GameProfile between a {@code
	 * getName()} getter and a {@code name()} record accessor across
	 * versions, so we try both by reflection and never fail the build on
	 * whichever one 26.1 happens to ship.
	 */
	private static String profileName(Object profile) {
		if (profile == null) {
			return null;
		}
		for (String method : new String[]{"name", "getName"}) {
			try {
				Object v = profile.getClass().getMethod(method).invoke(profile);
				if (v != null) {
					return v.toString();
				}
			} catch (ReflectiveOperationException ignored) {
				// try the next accessor
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	//  Volley geometry
	// ------------------------------------------------------------------

	private static void processVolley(List<double[]> fangs) {
		int n = fangs.size();
		if (n < 2) {
			return; // a single stray fang carries no direction
		}

		// Fangs are spawned (and therefore received) in order of increasing
		// distance from the evoker, so first->last points toward the target.
		double[] first = fangs.get(0);
		double[] last = fangs.get(n - 1);
		double spanX = last[0] - first[0];
		double spanZ = last[1] - first[1];
		double span = Math.hypot(spanX, spanZ);
		if (span < 1.0e-9) {
			return;
		}
		double dirX = spanX / span;
		double dirZ = spanZ / span;

		// Collinearity: the close-range attack spawns two RINGS of fangs in
		// the same tick; those fail this check and are reported, not used.
		double maxPerp = 0.0;
		for (double[] p : fangs) {
			double perp = Math.abs((p[0] - first[0]) * dirZ
					- (p[1] - first[1]) * dirX);
			maxPerp = Math.max(maxPerp, perp);
		}
		if (maxPerp > COLLINEAR_EPS) {
			info(Component.literal(String.format(Locale.ROOT,
					"[FangFinder] ignored close-range ring volley (%d fangs, "
							+ "target was within 3 blocks of the evoker)", n))
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		// Spacing sanity: consecutive gaps must be whole multiples of 1.25
		// (gaps appear when a fang found no valid ground and was skipped).
		boolean spacingOk = true;
		for (int i = 1; i < fangs.size(); i++) {
			double gap = (fangs.get(i)[0] - fangs.get(i - 1)[0]) * dirX
					+ (fangs.get(i)[1] - fangs.get(i - 1)[1]) * dirZ;
			double steps = gap / FANG_SPACING;
			if (Math.abs(steps - Math.round(steps)) > 0.02
					|| Math.round(steps) < 1) {
				spacingOk = false;
				break;
			}
		}

		int totalSteps = (int) Math.round(span / FANG_SPACING);
		boolean fullVolley = n == 16 && totalSteps == 15;

		// Minecraft yaw convention: dir = (-sin yaw, cos yaw)
		double yaw = Math.toDegrees(Math.atan2(-dirX, dirZ));

		Double evokerX = fullVolley ? first[0] - dirX * FANG_SPACING : null;
		Double evokerZ = fullVolley ? first[1] - dirZ * FANG_SPACING : null;

		String targetName = findLoadedTargetOnRay(first[0], first[1],
				dirX, dirZ);

		// Same machine? Keep only its NEWEST ray - the target moves, so an
		// older bearing from this machine points at an old position.
		double radius = Math.max(1, FangFinderConfig.get().machineRadius);
		for (Measurement m : machines) {
			if (m.sameMachine(first[0], first[1], radius)) {
				boolean moved = m.update(first[0], first[1], dirX, dirZ,
						yaw, n, totalSteps + 1, evokerX, evokerZ,
						spacingOk, tick, targetName);
				recomputeFix();
				if (moved) {
					announce(m, true); // target moved: worth a chat line
				}
				return;
			}
		}

		Measurement m = new Measurement(nextId++, first[0], first[1],
				dirX, dirZ, yaw, n, totalSteps + 1, evokerX, evokerZ,
				spacingOk, tick, targetName);
		machines.add(m);
		while (machines.size() > MAX_MACHINES) {
			machines.remove(0);
		}
		recomputeFix();
		announce(m, false);
	}

	/**
	 * If the tracked player is inside client tracking range, exactly one
	 * loaded player entity stands on the fang ray - identify them. The
	 * server never syncs mob targets, so beyond render distance this
	 * legitimately cannot name anyone and returns null.
	 */
	private static String findLoadedTargetOnRay(double ax, double az,
			double dirX, double dirZ) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		String best = null;
		double bestPerp = 1.5; // blocks; fang aim used their exact position
		boolean ambiguous = false;
		for (net.minecraft.world.entity.Entity p : mc.level.players()) {
			double vx = p.getX() - ax;
			double vz = p.getZ() - az;
			double along = vx * dirX + vz * dirZ;
			if (along < -FANG_SPACING) {
				continue; // behind the volley
			}
			double perp = Math.hypot(vx - along * dirX, vz - along * dirZ);
			if (perp < bestPerp) {
				if (best != null) {
					ambiguous = true;
				}
				best = p.getName().getString();
				bestPerp = perp;
			}
		}
		if (best != null && mc.player != null
				&& best.equals(mc.player.getName().getString())
				&& FangFinderConfig.get().selfAlert) {
			chat(Component.literal(
					"[FangFinder] WARNING: that volley is aimed at YOU")
					.withStyle(ChatFormatting.RED));
			playPing(0.6F); // low, urgent
		}
		return ambiguous ? best + "?" : best;
	}

	// ------------------------------------------------------------------
	//  Fix
	// ------------------------------------------------------------------

	/** Rays currently allowed into the solver (fresh enough). */
	private static List<Measurement> activeRays() {
		int maxAge = FangFinderConfig.get().maxRayAgeSeconds;
		if (maxAge <= 0) {
			return machines;
		}
		List<Measurement> active = new ArrayList<>();
		for (Measurement m : machines) {
			if (tick - m.seenTick() <= maxAge * 20L) {
				active.add(m);
			}
		}
		return active;
	}

	/** Adds a manually-entered ray from the map screen (fangCount = 0). */
	static void addManualRay(double x, double z, double yaw) {
		double dx = -Math.sin(Math.toRadians(yaw));
		double dz = Math.cos(Math.toRadians(yaw));
		machines.add(new Measurement(nextId++, x, z, dx, dz,
				normalizeYawDeg(yaw), 0, 0, null, null, true, tick, null));
		while (machines.size() > MAX_MACHINES) {
			machines.remove(0);
		}
		recomputeFix();
	}

	private static double normalizeYawDeg(double yaw) {
		yaw = yaw % 360.0;
		if (yaw > 180.0) {
			yaw -= 360.0;
		} else if (yaw < -180.0) {
			yaw += 360.0;
		}
		return yaw;
	}

	/** Clears everything (map screen "Clear" and F10 share this). */
	static void clearAllData() {
		machines.clear();
		fix = null;
		worstMiss = 0.0;
		bestPairSepDeg = 0.0;
		nextId = 1; // full reset: numbering starts from T1 again
	}

	/** Posts a clickable [copy] chat line (used by the map screen). */
	static void chatWithCopy(String label, String payload) {
		chat(Component.literal("[FangFinder] ")
				.withStyle(ChatFormatting.RED)
				.append(Component.literal(label)
						.withStyle(ChatFormatting.GOLD))
				.append(copyButton(payload)));
	}

	/** Plays a short UI sound, respecting the config toggle. */
	static void playPing(float pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSoundManager() != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(
					SoundEvents.EXPERIENCE_ORB_PICKUP, pitch, 0.6F));
		}
	}

	static void recomputeFix() {
		boolean hadFix = fix != null;
		List<Measurement> active = activeRays();
		fix = Solver.intersect(active);
		if (fix != null && !hadFix && FangFinderConfig.get().fixSound) {
			playPing(1.4F); // fresh target lock
		}
		worstMiss = 0.0;
		bestPairSepDeg = 0.0;
		if (fix != null) {
			for (Measurement m : active) {
				worstMiss = Math.max(worstMiss, m.missDistance(fix));
			}
		}
		for (int i = 0; i < active.size(); i++) {
			for (int j = i + 1; j < active.size(); j++) {
				double cross = Math.abs(active.get(i).dirX()
						* active.get(j).dirZ()
						- active.get(i).dirZ() * active.get(j).dirX());
				bestPairSepDeg = Math.max(bestPairSepDeg,
						Math.toDegrees(Math.asin(Math.min(1.0, cross))));
			}
		}
	}

	// ------------------------------------------------------------------
	//  Placement guide (F7)
	// ------------------------------------------------------------------

	private static void printPlacementGuide() {
		double range = 0.0;
		if (fix != null && !machines.isEmpty()) {
			Measurement m = machines.get(machines.size() - 1);
			range = Math.hypot(fix[0] - m.anchorX(), fix[1] - m.anchorZ());
		}
		chat(Component.literal("[FangFinder] tracker placement guide")
				.withStyle(ChatFormatting.RED));
		chat(guideLine("Only the ANGLE between bearings, seen from the "
				+ "target, matters. Distance between trackers by itself "
				+ "does nothing."));
		chat(guideLine("Moving a tracker along its own fang line changes "
				+ "nothing. Separate trackers PERPENDICULAR to the "
				+ "measured bearing."));
		chat(guideLine("Ideal is bearings ~90 deg apart (a quarter-circle "
				+ "around the target). Avoid ~180 deg too - opposite rays "
				+ "are parallel again."));
		if (range > 1.0) {
			chat(guideLine(String.format(Locale.ROOT,
					"Estimated range %.0f blocks. Perpendicular offset "
							+ "for the next tracker:", range)));
			printGuideTier(range, 5.0, "weak but usable");
			printGuideTier(range, 20.0, "good");
			printGuideTier(range, 45.0, "great");
		} else {
			chat(guideLine("No fix yet, so per 1000 blocks of range: "
					+ "5 deg = 87 blocks sideways, 20 deg = 364, "
					+ "45 deg = 1000."));
			chat(guideLine("Rule of thumb: sideways offset of about a "
					+ "third of the range gives a solid fix."));
		}
		chat(guideLine(String.format(Locale.ROOT,
				"Machines within %d blocks of each other count as ONE "
						+ "tracker (configurable).",
				FangFinderConfig.get().machineRadius)));
	}

	private static void printGuideTier(double range, double sepDeg,
			String label) {
		double rad = Math.toRadians(sepDeg);
		double offset = range * Math.tan(rad);
		double err = range * 5.0e-5 / Math.sin(rad);
		chat(guideLine(String.format(Locale.ROOT,
				"  %2.0f deg (%s): %.0f blocks sideways -> fix error "
						+ "~%.1f blocks", sepDeg, label, offset,
				Math.max(err, 0.1))));
	}

	private static Component guideLine(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GOLD);
	}

	// ------------------------------------------------------------------
	//  Chat output
	// ------------------------------------------------------------------

	private static void announce(Measurement m, boolean updated) {
		String yawStr = String.format(Locale.ROOT, "%.10f", m.yaw());
		MutableComponent msg = Component.literal("[FangFinder] ")
				.withStyle(ChatFormatting.RED)
				.append(Component.literal(String.format(Locale.ROOT,
						"T%d %s yaw %s  (%d/%d fangs)%s ", m.id(),
						updated ? "moved:" : "new:", yawStr,
						m.fangCount(), m.expectedCount(),
						m.spacingOk() ? "" : " [odd spacing!]"))
						.withStyle(ChatFormatting.GOLD))
				.append(copyButton(m.copyLine()));
		if (m.evokerX() != null) {
			msg.append(Component.literal(String.format(Locale.ROOT,
					"  caster (%.4f, %.4f)", m.evokerX(), m.evokerZ()))
					.withStyle(ChatFormatting.GRAY));
		}
		if (m.targetName() != null) {
			msg.append(Component.literal("  -> " + m.targetName())
					.withStyle(ChatFormatting.AQUA));
		}
		info(msg);

		List<Measurement> active = activeRays();
		if (fix != null && active.size() >= 2) {
			String fixLine = String.format(Locale.ROOT,
					"Fix: X %.3f  Z %.3f  (block %d, %d · worst miss %.3f) ",
					fix[0], fix[1], (int) Math.floor(fix[0]),
					(int) Math.floor(fix[1]), worstMiss);
			info(Component.literal("[FangFinder] ")
					.withStyle(ChatFormatting.RED)
					.append(Component.literal(fixLine)
							.withStyle(ChatFormatting.GREEN))
					.append(copyButton(String.format(Locale.ROOT,
							"%.3f %.3f", fix[0], fix[1]))));
		}
	}

	private static void sendCopyAll() {
		if (machines.isEmpty()) {
			chat(Component.literal("[FangFinder] nothing to copy yet")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (Measurement m : machines) {
			sb.append(m.copyLine()).append('\n');
		}
		if (fix != null) {
			sb.append(String.format(Locale.ROOT,
					"# fix %.3f %.3f (worst miss %.3f)%n",
					fix[0], fix[1], worstMiss));
		}
		chat(Component.literal("[FangFinder] ")
				.withStyle(ChatFormatting.RED)
				.append(Component.literal(machines.size()
						+ " measurement(s) ready - ")
						.withStyle(ChatFormatting.GOLD))
				.append(copyButton(sb.toString()))
				.append(Component.literal(
						" (each line: anchorX anchorZ yaw)")
						.withStyle(ChatFormatting.GRAY)));
	}

	private static Component copyButton(String payload) {
		return Component.literal("[copy]")
				.withStyle(style -> style
						.withColor(ChatFormatting.AQUA)
						.withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(payload)));
	}

	/** Announcement that respects the chatMessages config switch. */
	private static void info(Component message) {
		if (FangFinderConfig.get().chatMessages) {
			chat(message);
		}
	}

	/** Always-on chat (warnings, direct key feedback). */
	static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(message);
		}
	}

	/**
	 * Short status feedback that shows as an on-screen toast when a
	 * FangFinder screen is open (chat is hidden behind the GUI there),
	 * and falls back to chat otherwise.
	 */
	static void feedback(String plainText) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof MapScreen) {
			MapScreen.toast(plainText);
		} else {
			chat(Component.literal("[FangFinder] " + plainText)
					.withStyle(ChatFormatting.GOLD));
		}
	}

	// ------------------------------------------------------------------
	//  HUD
	// ------------------------------------------------------------------

	private static void renderHud(
			net.minecraft.client.gui.GuiGraphicsExtractor graphics,
			net.minecraft.client.DeltaTracker deltaTracker) {
		if (!hudVisible) {
			return;
		}
		if (!FangFinderConfig.get().enabled) {
			// Always show something when the master switch is off, even
			// with no active tracking - otherwise toggling it off looks
			// like it did nothing when there was no HUD to hide anyway.
			Font f = Minecraft.getInstance().font;
			String msg = "FangFinder OFF (F6)";
			int w = f.width(msg);
			graphics.fill(4, 4, 4 + w + 8, 4 + f.lineHeight + 6,
					FangFinderConfig.applyPanelOpacity(0xFF101418));
			graphics.text(f, msg, 8, 8, 0xFFE5484D);
			return;
		}
		if (machines.isEmpty()) {
			return;
		}
		int maxAge = FangFinderConfig.get().maxRayAgeSeconds;
		Font font = Minecraft.getInstance().font;
		List<String> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		if (!FangFinderConfig.get().showHudLogo) {
			lines.add("FangFinder");
			colors.add(0xFFE5484D);
		}
		for (Measurement m : machines) {
			long ageS = (tick - m.seenTick()) / 20L;
			boolean stale = maxAge > 0 && ageS > maxAge;
			lines.add(String.format(Locale.ROOT,
					"T%d  yaw %.6f  (%s)%s%s  %ds%s", m.id(), m.yaw(),
					m.fangCount() == 0 ? "manual"
							: m.fangCount() + "/" + m.expectedCount(),
					m.repeats() > 1 ? "  x" + m.repeats() : "",
					m.targetName() != null ? "  -> " + m.targetName() : "",
					ageS, stale ? " STALE" : ""));
			colors.add(stale ? 0xFF5B6875
					: m.spacingOk() ? 0xFFE2B93B : 0xFFB0682F);
		}
		List<Measurement> active = activeRays();
		if (fix != null && active.size() >= 2) {
			lines.add(String.format(Locale.ROOT, "fix X %.2f  Z %.2f",
					fix[0], fix[1]));
			colors.add(0xFF3ECF8E);
			lines.add(String.format(Locale.ROOT, "worst miss %.3f blocks",
					worstMiss));
			colors.add(0xFF8B98A5);
			if (bestPairSepDeg < 5.0) {
				lines.add(String.format(Locale.ROOT,
						"weak geometry! rays only %.2f deg apart",
						bestPairSepDeg));
				colors.add(0xFFE5484D);
				Measurement lastM = machines.get(machines.size() - 1);
				double range = Math.hypot(fix[0] - lastM.anchorX(),
						fix[1] - lastM.anchorZ());
				lines.add(String.format(Locale.ROOT,
						"move a tracker %.0f+ blocks SIDEWAYS (F7 = guide)",
						Math.max(range * 0.0875, 10.0)));
				colors.add(0xFFE5484D);
			}
		} else {
			lines.add("need 2+ machines with fresh rays for a fix");
			colors.add(0xFF8B98A5);
		}
		lines.add("N map · F6 mod · F7 guide · F8 hide · F9 copy · F10 clear");
		colors.add(0xFF5B6875);

		int pad = 4;
		int lineH = font.lineHeight + 1;
		int width = 0;
		for (String s : lines) {
			width = Math.max(width, font.width(s));
		}
		boolean showLogo = FangFinderConfig.get().showHudLogo;
		int logoH = showLogo ? 16 : 0;
		int logoW = showLogo ? (int) Math.round(logoH * 1024.0 / 159.0) : 0;
		width = Math.max(width, logoW);
		int pad2 = 4;
		int x = 4;
		int y = 4;
		graphics.fill(x, y, x + width + pad * 2,
				y + logoH + lines.size() * lineH + pad2 * 2 - 1,
				FangFinderConfig.applyPanelOpacity(0xFF101418));
		int textY = y + pad2;
		if (showLogo) {
			graphics.blitSprite(
					net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					MapScreen.LOGO, x + pad, textY, logoW, logoH);
			textY += logoH + 2;
		}
		for (int i = 0; i < lines.size(); i++) {
			graphics.text(font, lines.get(i), x + pad,
					textY + i * lineH, colors.get(i));
		}
	}
}
