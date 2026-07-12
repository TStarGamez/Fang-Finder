package dev.fangfinder;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * FangFinder's ModMenu config screen. Uses the same dark theme, logo
 * sprite, and panel chrome as {@link MapScreen} so both feel like one
 * mod, and keeps entirely to verified 26.1 Screen/Button/EditBox APIs.
 */
public final class FangFinderConfigScreen extends Screen {
	private static final int[] RADII = {8, 16, 32, 64, 128};
	private static final int[] AGES = {0, 30, 60, 120, 300};
	private static final int[] OPACITIES = {40, 60, 75, 90, 100};
	private static final int[] ZOOMS = {250, 500, 1000, 2000, 5000, 10000};

	private static final int C_BG = 0xFF0D1117;
	private static final int C_HEADER = 0xFF161B22;
	private static final int C_PANEL = 0xFF10151C;
	private static final int C_EDGE = 0xFF2C3947;
	private static final int C_TEXT = 0xFFE8ECEF;
	private static final int C_DIM = 0xFF8B98A5;
	private static final int C_GOLD = 0xFFE2B93B;

	// Single source of truth for the row layout, shared by init() (widget
	// placement) and extractRenderState() (panel backgrounds/headers) so
	// the two can never drift out of sync with each other again - that
	// drift (two independently hardcoded y0 values) was why "APPEARANCE"
	// used to render on top of the Panel opacity button.
	private static final int ROW_Y0 = 78;
	private static final int ROW_H = 26;
	private static final int HEADER_GAP = 18;
	// Bottom of the last General/Tracking row (4 rows of height 20).
	private static final int ROW4_BOTTOM = ROW_Y0 + ROW_H * 3 + 20;
	// The Appearance header needs HEADER_GAP of clearance below that row
	// (same clearance GENERAL/TRACKING get above the panel top) plus 10px
	// between the header text and its own button below it.
	private static final int APPEAR_Y = ROW4_BOTTOM + HEADER_GAP + 10;

	private final Screen parent;

	public FangFinderConfigScreen(Screen parent) {
		super(Component.literal("FangFinder Options"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		FangFinderConfig cfg = FangFinderConfig.get();
		int colL = this.width / 2 - 210;
		int colR = this.width / 2 + 10;

		// ---- General ----
		this.addRenderableWidget(Button.builder(enabledText(cfg), b -> {
			cfg.enabled = !cfg.enabled;
			b.setMessage(enabledText(cfg));
			FangFinderClient.chat(Component.literal("[FangFinder] "
					+ (cfg.enabled ? "enabled" : "disabled")));
		}).pos(colL, ROW_Y0).size(200, 20).tooltip(Tooltip.create(
				Component.literal("Turn the whole mod on or off.")))
				.build());
		this.addRenderableWidget(Button.builder(chatText(cfg), b -> {
			cfg.chatMessages = !cfg.chatMessages;
			b.setMessage(chatText(cfg));
		}).pos(colL, ROW_Y0 + ROW_H).size(200, 20).tooltip(Tooltip.create(
				Component.literal("Show volley and fix messages in chat "
						+ "as well as the HUD."))).build());
		this.addRenderableWidget(Button.builder(hudLogoText(cfg), b -> {
			cfg.showHudLogo = !cfg.showHudLogo;
			b.setMessage(hudLogoText(cfg));
		}).pos(colL, ROW_Y0 + ROW_H * 2).size(200, 20).tooltip(
				Tooltip.create(Component.literal("Show the FangFinder "
						+ "logo above the in-game HUD overlay.")))
				.build());
		this.addRenderableWidget(Button.builder(fixSoundText(cfg), b -> {
			cfg.fixSound = !cfg.fixSound;
			b.setMessage(fixSoundText(cfg));
		}).pos(colL, ROW_Y0 + ROW_H * 3).size(200, 20).tooltip(
				Tooltip.create(Component.literal("Play a rising ping the "
						+ "moment two rays first cross into a fix.")))
				.build());

		// ---- Tracking ----
		this.addRenderableWidget(Button.builder(radiusText(cfg), b -> {
			cfg.machineRadius = cycle(RADII, cfg.machineRadius);
			b.setMessage(radiusText(cfg));
		}).pos(colR, ROW_Y0).size(200, 20).tooltip(Tooltip.create(
				Component.literal("Volleys anchored within this many "
						+ "blocks of an existing ray are treated as the "
						+ "same tracker machine."))).build());
		this.addRenderableWidget(Button.builder(ageText(cfg), b -> {
			cfg.maxRayAgeSeconds = cycle(AGES, cfg.maxRayAgeSeconds);
			b.setMessage(ageText(cfg));
		}).pos(colR, ROW_Y0 + ROW_H).size(200, 20).tooltip(Tooltip.create(
				Component.literal("Rays older than this drop out of the "
						+ "fix. \"never\" keeps every ray forever.")))
				.build());
		this.addRenderableWidget(Button.builder(zoomText(cfg), b -> {
			cfg.defaultZoomBlocks = cycle(ZOOMS, cfg.defaultZoomBlocks);
			b.setMessage(zoomText(cfg));
		}).pos(colR, ROW_Y0 + ROW_H * 2).size(200, 20).tooltip(
				Tooltip.create(Component.literal("How far from centre "
						+ "the map view is zoomed to when you first open "
						+ "it.")))
				.build());
		this.addRenderableWidget(Button.builder(selfAlertText(cfg), b -> {
			cfg.selfAlert = !cfg.selfAlert;
			b.setMessage(selfAlertText(cfg));
		}).pos(colR, ROW_Y0 + ROW_H * 3).size(200, 20).tooltip(
				Tooltip.create(Component.literal("Warn you in chat (with "
						+ "an urgent tone) if a detected volley is aimed "
						+ "at you.")))
				.build());

		// ---- Appearance ----
		this.addRenderableWidget(Button.builder(opacityText(cfg), b -> {
			cfg.panelOpacity = cycle(OPACITIES, cfg.panelOpacity);
			b.setMessage(opacityText(cfg));
		}).pos(this.width / 2 - 100, APPEAR_Y).size(200, 20).tooltip(
				Tooltip.create(Component.literal("How see-through the "
						+ "FangFinder panels and HUD background are.")))
				.build());

		this.addRenderableWidget(Button.builder(
				Component.literal("Done"), b -> this.onClose()
		).pos(this.width / 2 - 60, this.height - 32).size(120, 20)
				.tooltip(Tooltip.create(Component.literal(
						"Save changes and close.")))
				.build());
	}

	private static Component enabledText(FangFinderConfig cfg) {
		return Component.literal("Mod enabled: "
				+ (cfg.enabled ? "ON" : "OFF"));
	}

	private static Component chatText(FangFinderConfig cfg) {
		return Component.literal("Chat announcements: "
				+ (cfg.chatMessages ? "ON" : "OFF"));
	}

	private static Component hudLogoText(FangFinderConfig cfg) {
		return Component.literal("HUD logo: "
				+ (cfg.showHudLogo ? "ON" : "OFF"));
	}

	private static Component fixSoundText(FangFinderConfig cfg) {
		return Component.literal("Fix-lock sound: "
				+ (cfg.fixSound ? "ON" : "OFF"));
	}

	private static Component selfAlertText(FangFinderConfig cfg) {
		return Component.literal("Alert when targeted: "
				+ (cfg.selfAlert ? "ON" : "OFF"));
	}

	private static Component radiusText(FangFinderConfig cfg) {
		return Component.literal("Same-machine radius: "
				+ cfg.machineRadius + " blocks");
	}

	private static Component ageText(FangFinderConfig cfg) {
		return Component.literal("Rays go stale after: "
				+ (cfg.maxRayAgeSeconds == 0 ? "never"
						: cfg.maxRayAgeSeconds + "s"));
	}

	private static Component zoomText(FangFinderConfig cfg) {
		return Component.literal("Map opens showing: \u00b1"
				+ cfg.defaultZoomBlocks + " blocks");
	}

	private static Component opacityText(FangFinderConfig cfg) {
		return Component.literal("Panel opacity: "
				+ cfg.panelOpacity + "%");
	}

	private static int cycle(int[] values, int current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) {
				return values[(i + 1) % values.length];
			}
		}
		return values[0];
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX,
			int mouseY, float delta) {
		// Backdrop and header must respect panelOpacity too, not just
		// the smaller panel boxes - see the comment in MapScreen's
		// drawChrome for why an opaque backdrop made the setting
		// invisible no matter what it was set to.
		g.fill(0, 0, this.width, this.height,
				FangFinderConfig.applyPanelOpacity(C_BG));
		g.fill(0, 0, this.width, 56,
				FangFinderConfig.applyPanelOpacity(C_HEADER));
		g.fill(0, 56, this.width, 57, C_EDGE);

		int panel = FangFinderConfig.applyPanelOpacity(C_PANEL);
		int colL = this.width / 2 - 212;
		int colR = this.width / 2 + 8;
		int panelTop = ROW_Y0 - HEADER_GAP;
		int panelBottom = ROW_Y0 + ROW_H * 3 + 20 + 8;
		g.fill(colL, panelTop, colL + 204, panelBottom, panel);
		g.fill(colR, panelTop, colR + 204, panelBottom, panel);
		int appearTop = APPEAR_Y - HEADER_GAP;
		int appearBottom = APPEAR_Y + 20 + 8;
		g.fill(this.width / 2 - 106, appearTop, this.width / 2 + 106,
				appearBottom, panel);

		super.extractRenderState(g, mouseX, mouseY, delta);

		int logoH = 30;
		int logoW = (int) Math.round(logoH * 1024.0 / 159.0);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, MapScreen.LOGO,
				this.width / 2 - logoW / 2, 4, logoW, logoH);
		g.text(this.font, "OPTIONS", this.width / 2 - 22, 40, C_GOLD);

		g.text(this.font, "GENERAL", colL + 4, ROW_Y0 - 10, C_GOLD);
		g.text(this.font, "TRACKING", colR + 4, ROW_Y0 - 10, C_GOLD);
		String appearLabel = "APPEARANCE";
		g.text(this.font, appearLabel,
				this.width / 2 - this.font.width(appearLabel) / 2,
				APPEAR_Y - 10, C_GOLD);
	}

	@Override
	public void onClose() {
		FangFinderConfig.get().save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
