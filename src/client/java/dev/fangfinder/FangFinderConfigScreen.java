package dev.fangfinder;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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

	private final Screen parent;
	private EditBox creditBox;

	public FangFinderConfigScreen(Screen parent) {
		super(Component.literal("FangFinder Options"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		FangFinderConfig cfg = FangFinderConfig.get();
		int colL = this.width / 2 - 210;
		int colR = this.width / 2 + 10;
		int y0 = 78;
		int rowH = 26;

		// ---- General ----
		this.addRenderableWidget(Button.builder(enabledText(cfg), b -> {
			cfg.enabled = !cfg.enabled;
			b.setMessage(enabledText(cfg));
		}).pos(colL, y0).size(200, 20).build());
		this.addRenderableWidget(Button.builder(chatText(cfg), b -> {
			cfg.chatMessages = !cfg.chatMessages;
			b.setMessage(chatText(cfg));
		}).pos(colL, y0 + rowH).size(200, 20).build());
		this.addRenderableWidget(Button.builder(hudLogoText(cfg), b -> {
			cfg.showHudLogo = !cfg.showHudLogo;
			b.setMessage(hudLogoText(cfg));
		}).pos(colL, y0 + rowH * 2).size(200, 20).build());
		this.addRenderableWidget(Button.builder(fixSoundText(cfg), b -> {
			cfg.fixSound = !cfg.fixSound;
			b.setMessage(fixSoundText(cfg));
		}).pos(colL, y0 + rowH * 3).size(200, 20).build());

		// ---- Tracking ----
		this.addRenderableWidget(Button.builder(radiusText(cfg), b -> {
			cfg.machineRadius = cycle(RADII, cfg.machineRadius);
			b.setMessage(radiusText(cfg));
		}).pos(colR, y0).size(200, 20).build());
		this.addRenderableWidget(Button.builder(ageText(cfg), b -> {
			cfg.maxRayAgeSeconds = cycle(AGES, cfg.maxRayAgeSeconds);
			b.setMessage(ageText(cfg));
		}).pos(colR, y0 + rowH).size(200, 20).build());
		this.addRenderableWidget(Button.builder(zoomText(cfg), b -> {
			cfg.defaultZoomBlocks = cycle(ZOOMS, cfg.defaultZoomBlocks);
			b.setMessage(zoomText(cfg));
		}).pos(colR, y0 + rowH * 2).size(200, 20).build());
		this.addRenderableWidget(Button.builder(selfAlertText(cfg), b -> {
			cfg.selfAlert = !cfg.selfAlert;
			b.setMessage(selfAlertText(cfg));
		}).pos(colR, y0 + rowH * 3).size(200, 20).build());

		// ---- Appearance ----
		int y1 = y0 + rowH * 3 + 22;
		this.addRenderableWidget(Button.builder(opacityText(cfg), b -> {
			cfg.panelOpacity = cycle(OPACITIES, cfg.panelOpacity);
			b.setMessage(opacityText(cfg));
		}).pos(colL, y1).size(200, 20).build());

		creditBox = this.addRenderableWidget(new EditBox(this.font,
				colR, y1, 200, 20, creditBox,
				Component.literal("Credit name")));
		creditBox.setValue(cfg.creditName);

		this.addRenderableWidget(Button.builder(
				Component.literal("Done"), b -> this.onClose()
		).pos(this.width / 2 - 60, this.height - 32).size(120, 20)
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
		g.fill(0, 0, this.width, this.height, C_BG);
		g.fill(0, 0, this.width, 56, C_HEADER);
		g.fill(0, 56, this.width, 57, C_EDGE);

		int colL = this.width / 2 - 212;
		int colR = this.width / 2 + 8;
		int y0 = 60;
		int panelBottom = y0 + 26 * 4 + 20;
		g.fill(colL, y0, colL + 204, panelBottom, C_PANEL);
		g.fill(colR, y0, colR + 204, panelBottom, C_PANEL);
		int y1 = panelBottom + 22;
		g.fill(colL, y1 - 4, colR + 204, y1 + 24, C_PANEL);

		super.extractRenderState(g, mouseX, mouseY, delta);

		int logoH = 30;
		int logoW = (int) Math.round(logoH * 1024.0 / 159.0);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, MapScreen.LOGO,
				this.width / 2 - logoW / 2, 4, logoW, logoH);
		g.text(this.font, "OPTIONS", this.width / 2 - 22, 40, C_GOLD);

		g.text(this.font, "GENERAL", colL + 4, y0 - 10, C_GOLD);
		g.text(this.font, "TRACKING", colR + 4, y0 - 10, C_GOLD);
		g.text(this.font, "APPEARANCE", colL + 4, y1 - 14, C_GOLD);

		g.text(this.font, "by " + FangFinderConfig.get().creditName, 6,
				this.height - 10, C_DIM);
	}

	@Override
	public void onClose() {
		FangFinderConfig cfg = FangFinderConfig.get();
		if (creditBox != null) {
			String name = creditBox.getValue().trim();
			cfg.creditName = name.isEmpty() ? "TStar" : name;
		}
		cfg.save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}
}
