package dev.fangfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Data sharing over chat. Rays and named ray sets are encoded into short
 * chat messages; any player running FangFinder parses incoming chat and
 * imports them automatically.
 *
 * <p>Wire format (chat-safe, chunked under the 256-char chat limit):
 * <pre>
 *   ff|ray|x,z,yaw                        one ray (co-op fast tracking)
 *   ff|set|name|i/n|x,z,yaw;x,z,yaw;...   chunk i of n of a named set
 * </pre>
 *
 * <p>Name collisions prompt the receiver with clickable choices
 * (overwrite / keep both / discard) backed by the /fangfinder client
 * command.
 */
public final class Sharing {
	private static final int MAX_SETS = 8;

	/** Imported sets by name. */
	static final Map<String, RaySet> sets = new LinkedHashMap<>();

	// chunk assembly: key = sender|name
	private static final Map<String, String[]> partial = new HashMap<>();
	// pending name collision
	private static RaySet pendingSet;

	private Sharing() {
	}

	// ------------------------------------------------------------------
	//  Sending
	// ------------------------------------------------------------------

	static void shareLastRay() {
		if (FangFinderClient.machines.isEmpty()) {
			FangFinderClient.feedback("No rays to share yet");
			return;
		}
		Measurement m = FangFinderClient.machines
				.get(FangFinderClient.machines.size() - 1);
		copyToClipboard("ff|ray|" + encodeRay(m));
		FangFinderClient.feedback("Ray copied to clipboard");
	}

	static void shareSet(String rawName) {
		if (FangFinderClient.machines.isEmpty()) {
			FangFinderClient.feedback("No rays to share yet");
			return;
		}
		String name = sanitize(rawName);
		List<Measurement> rays = FangFinderClient.machines;
		StringBuilder payload = new StringBuilder();
		for (Measurement m : rays) {
			if (payload.length() > 0) {
				payload.append(';');
			}
			payload.append(encodeRay(m));
		}
		copyToClipboard("ff|set|" + name + "|1/1|" + payload);
		FangFinderClient.feedback("Set '" + name + "' (" + rays.size()
				+ " rays) copied to clipboard");
	}

	private static String encodeRay(Measurement m) {
		return String.format(Locale.ROOT, "%.2f,%.2f,%.7f", m.anchorX(),
				m.anchorZ(), m.yaw());
	}

	/**
	 * Payloads are no longer broadcast to chat (that only auto-imported
	 * for other players live in the same session); copying to clipboard
	 * lets the same wire format be pasted anywhere - Discord, a manually
	 * typed chat message, etc. - and still be recognized by a receiving
	 * client's {@link #onChat} if it ends up in chat at all.
	 */
	private static void copyToClipboard(String line) {
		Minecraft.getInstance().keyboardHandler.setClipboard(line);
	}

	// ------------------------------------------------------------------
	//  Receiving (wired to ClientReceiveMessageEvents CHAT + GAME)
	// ------------------------------------------------------------------

	/** Scans any incoming chat line for share payloads. */
	static void onChat(String rawText, String senderOrNull) {
		int idx = rawText.indexOf("ff|");
		if (idx < 0) {
			return;
		}
		String payload = rawText.substring(idx).trim();
		String self = localName();
		if (senderOrNull != null && senderOrNull.equals(self)) {
			return;
		}
		String sender = senderOrNull != null ? senderOrNull : "unknown";
		try {
			if (payload.startsWith("ff|ray|")) {
				receiveRay(payload.substring(7), sender);
			} else if (payload.startsWith("ff|set|")) {
				receiveSetChunk(payload.substring(7), sender);
			}
		} catch (RuntimeException e) {
			FangFinderClient.chat(err("malformed share from " + sender));
		}
	}

	private static void receiveRay(String body, String sender) {
		double[] r = parseRay(body);
		FangFinderClient.addManualRay(r[0], r[1], r[2]);
		FangFinderClient.chat(info("ray from " + sender
				+ " added to live tracking"
				+ (FangFinderClient.fix != null
						? String.format(Locale.ROOT,
								" - fix X %.1f Z %.1f",
								FangFinderClient.fix[0],
								FangFinderClient.fix[1])
						: "")));
	}

	private static void receiveSetChunk(String body, String sender) {
		// body = name|i/n|payload
		int p1 = body.indexOf('|');
		int p2 = body.indexOf('|', p1 + 1);
		String name = sanitize(body.substring(0, p1));
		String[] frac = body.substring(p1 + 1, p2).split("/");
		int i = Integer.parseInt(frac[0]);
		int n = Integer.parseInt(frac[1]);
		String key = sender + "|" + name + "|" + n;
		String[] parts = partial.computeIfAbsent(key, k -> new String[n]);
		if (i < 1 || i > n) {
			return;
		}
		parts[i - 1] = body.substring(p2 + 1);
		for (String part : parts) {
			if (part == null) {
				return; // still assembling
			}
		}
		partial.remove(key);

		RaySet set = new RaySet(name, sender, sets.size());
		for (String part : parts) {
			for (String ray : part.split(";")) {
				double[] r = parseRay(ray);
				set.addRay(r[0], r[1], r[2]);
			}
		}
		set.solve();

		if (sets.containsKey(name)) {
			pendingSet = set;
			MutableComponent msg = Component.literal("[FangFinder] ")
					.withStyle(ChatFormatting.RED)
					.append(Component.literal("set '" + name
							+ "' already exists: ")
							.withStyle(ChatFormatting.GOLD))
					.append(choice("[overwrite]", "overwrite"))
					.append(Component.literal(" "))
					.append(choice("[keep both]", "rename"))
					.append(Component.literal(" "))
					.append(choice("[discard]", "discard"));
			FangFinderClient.chat(msg);
		} else {
			importSet(set);
		}
	}

	private static Component choice(String label, String cmd) {
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(
						"/fangfinder resolve " + cmd)));
	}

	private static void importSet(RaySet set) {
		while (sets.size() >= MAX_SETS) {
			sets.remove(sets.keySet().iterator().next());
		}
		sets.put(set.name, set);
		String fixTxt = set.fix != null
				? String.format(Locale.ROOT, " - fix X %.1f Z %.1f",
						set.fix[0], set.fix[1])
				: "";
		FangFinderClient.chat(info("imported set '" + set.name + "' from "
				+ set.from + " (" + set.rays.size() + " rays)" + fixTxt));
	}

	/** Handles /fangfinder resolve &lt;overwrite|rename|discard&gt;. */
	static void resolvePending(String choice) {
		if (pendingSet == null) {
			FangFinderClient.chat(err("nothing pending"));
			return;
		}
		RaySet set = pendingSet;
		pendingSet = null;
		switch (choice) {
			case "overwrite" -> {
				sets.remove(set.name);
				importSet(set);
			}
			case "rename" -> {
				String base = set.name;
				int k = 2;
				while (sets.containsKey(base + "-" + k)) {
					k++;
				}
				RaySet renamed = new RaySet(base + "-" + k, set.from,
						sets.size());
				for (Measurement m : set.rays) {
					renamed.addRay(m.anchorX(), m.anchorZ(), m.yaw());
				}
				renamed.solve();
				importSet(renamed);
			}
			case "discard" ->
					FangFinderClient.chat(info("discarded incoming set"));
			default -> FangFinderClient.chat(
					err("unknown choice: " + choice));
		}
	}

	static void removeSet(String name) {
		if (sets.remove(sanitize(name)) != null) {
			FangFinderClient.chat(info("removed set '" + name + "'"));
		} else {
			FangFinderClient.chat(err("no set named '" + name + "'"));
		}
	}

	static void listSets() {
		if (sets.isEmpty()) {
			FangFinderClient.chat(info("no imported sets"));
			return;
		}
		for (RaySet s : sets.values()) {
			String fix = s.fix != null
					? String.format(Locale.ROOT, "fix X %.1f Z %.1f",
							s.fix[0], s.fix[1])
					: "no fix";
			FangFinderClient.chat(info("set '" + s.name + "' from "
					+ s.from + ": " + s.rays.size() + " rays, " + fix));
		}
	}

	// ------------------------------------------------------------------

	private static double[] parseRay(String s) {
		String[] p = s.split(",");
		return new double[]{Double.parseDouble(p[0]),
				Double.parseDouble(p[1]), Double.parseDouble(p[2])};
	}

	private static String sanitize(String name) {
		String out = name.replaceAll("[^A-Za-z0-9_-]", "");
		if (out.isEmpty()) {
			out = "set";
		}
		return out.length() > 16 ? out.substring(0, 16) : out;
	}

	private static String localName() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null ? mc.player.getName().getString() : "";
	}

	private static Component info(String text) {
		return Component.literal("[FangFinder] ")
				.withStyle(ChatFormatting.RED)
				.append(Component.literal(text)
						.withStyle(ChatFormatting.GOLD));
	}

	private static Component err(String text) {
		return Component.literal("[FangFinder] " + text)
				.withStyle(ChatFormatting.GRAY);
	}
}
