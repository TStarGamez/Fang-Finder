package dev.fangfinder;

import java.util.Locale;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * TStar's FangFinder full-screen UI (default key: M).
 *
 * <p>Two tabs, drawn as real tabs: TRACKER (triangulation map with drag
 * pan / scroll zoom and manual ray inputs) and ORBITAL STRIKE CANNON
 * (cubicmetre's fire control, Mark 6 / 6.1 / MS, with the binary LED
 * output).
 *
 * <p>Render order matters in 26.1: background fills and panels are drawn
 * BEFORE super.extractRenderState (so vanilla widgets composite on top),
 * and all dynamic content and text is drawn AFTER it.
 */
public final class MapScreen extends Screen {
	private static final int TAB_TRACKER = 0;
	private static final int TAB_OSC = 1;

	// theme
	private static final int C_BG = 0xFF0D1117;
	private static final int C_HEADER = 0xFF161B22;
	private static final int C_PANEL = 0xFF10151C;
	private static final int C_TAB_ON = 0xFF1D2733;
	private static final int C_TAB_OFF = 0xFF0A0E13;
	private static final int C_MAP = 0xFF07090C;
	private static final int C_EDGE = 0xFF2C3947;
	private static final int C_TEXT = 0xFFE8ECEF;
	private static final int C_DIM = 0xFF8B98A5;
	private static final int C_RED = 0xFFE5484D;
	private static final int C_GREEN = 0xFF3ECF8E;
	private static final int C_GOLD = 0xFFE2B93B;

	static final Identifier LOGO = Identifier.fromNamespaceAndPath(
			FangFinderClient.MOD_ID, "logo");
	private static final double LOGO_ASPECT = 1024.0 / 159.0;

	private static final int HEADER_H = 54;
	private static final int TAB_W = 170;
	private static final int TAB_H = 20;

	// LED frame colors, matching the fire-control app's column groups
	private static final int[] COLUMN_COLORS = {
			0xFF3C44AA, 0xFF3C44AA, 0xFF3C44AA, 0xFF3AAFD9, 0xFF70B919,
			0xFF70B919, 0xFFF8C627, 0xFFF07613, 0xFFF07613, 0xFFF07613,
			0xFFA12722, 0xFF835432, 0xFF835432
	};

	// persisted across screen instances
	private static int activeTab = TAB_TRACKER;
	private static boolean planOpen = false;
	private static Planner.Result lastPlan;
	private static int shareCounter = 1;
	private static EditBox pCx;
	private static EditBox pCz;
	private static EditBox pSize;
	private static EditBox pN;
	private static EditBox pFocus;
	private static double centerX;
	private static double centerZ;
	private static double scale = 1.0;
	private static boolean centered = false;

	// EditBoxes passed as "previous" so their text survives re-init
	private static EditBox pRayX;
	private static EditBox pRayZ;
	private static EditBox pRayYaw;
	private static EditBox pOx;
	private static EditBox pOy;
	private static EditBox pOz;
	private static EditBox pTx;
	private static EditBox pTy;
	private static EditBox pTz;
	private static EditBox pPass;
	private static EditBox pNuke;
	private static EditBox pStab;
	private static EditBox pSlot;

	private int hoverX = -1;
	private int hoverY = -1;

	// transient on-screen feedback (replaces chat lines the GUI hides)
	private static String toastText = "";
	private static long toastUntilMs = 0;

	/** Shows a short message on whichever FangFinder screen is open. */
	static void toast(String text) {
		toastText = text;
		toastUntilMs = System.currentTimeMillis() + 4000;
	}

	public MapScreen() {
		super(Component.literal("TStar's FangFinder"));
	}

	// ------------------------------------------------------------------
	//  Layout helpers
	// ------------------------------------------------------------------

	/** Width of the left-side planner panel when the Plan tool is open. */
	private static final int PLAN_PANEL_W = 208;

	private static final String PLAN_DESC = "Suggests where to build "
			+ "trackers so any target in the area can be pinned "
			+ "accurately. Fill in the area, pick how many trackers, "
			+ "then Compute.";

	private static final int PLAN_ROW_SPACING = 40;

	/**
	 * Y of the first Optimal-Placing field row, positioned below the
	 * wrapped description text so a long description can never grow down
	 * into the field labels - shared by the widget layout and the render
	 * pass so they can't drift out of sync with each other.
	 */
	private int planFieldRow0() {
		return HEADER_H + 26 + wrappedHeight(PLAN_DESC, PLAN_PANEL_W - 16)
				+ 8;
	}

	/** Y of the Fill from world / Compute placement / Copy coordinates
	 *  button block, right below the 4 field rows - shared for the same
	 *  reason as {@link #planFieldRow0}. */
	private int planButtonsY() {
		return planFieldRow0() + PLAN_ROW_SPACING * 3 + 28;
	}

	private int mapX0() {
		return planOpen ? PLAN_PANEL_W + 14 : 8;
	}

	private int mapY0() {
		return HEADER_H + 6;
	}

	private int mapX1() {
		return this.width - 74;
	}

	private int mapY1() {
		return this.height - 36;
	}

	private int tabLeftX(int tab) {
		return this.width / 2 - TAB_W - 3 + tab * (TAB_W + 6);
	}

	private int tabTopY() {
		return HEADER_H - TAB_H;
	}

	// ------------------------------------------------------------------
	//  Widgets
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		FangFinderConfig cfg = FangFinderConfig.get();
		if (!centered) {
			centered = true;
			if (FangFinderClient.fix != null) {
				centerX = FangFinderClient.fix[0];
				centerZ = FangFinderClient.fix[1];
			} else if (this.minecraft != null
					&& this.minecraft.player != null) {
				centerX = this.minecraft.player.getX();
				centerZ = this.minecraft.player.getZ();
			}
			int half = Math.max(50, cfg.defaultZoomBlocks);
			scale = Math.max(0.02, Math.min(16.0,
					(mapX1() - mapX0()) / (2.0 * half)));
		}
		if (activeTab == TAB_TRACKER) {
			initTracker();
		} else {
			initOsc(cfg);
		}
	}

	private void initTracker() {
		int bx = this.width - 66;
		int by = mapY0() + 2;
		this.addRenderableWidget(Button.builder(Component.literal("+"),
				b -> scale = Math.min(16.0, scale * 1.5)
		).pos(bx, by).size(28, 20).tooltip(Tooltip.create(
				Component.literal("Zoom in"))).build());
		this.addRenderableWidget(Button.builder(Component.literal("-"),
				b -> scale = Math.max(0.02, scale / 1.5)
		).pos(bx + 30, by).size(28, 20).tooltip(Tooltip.create(
				Component.literal("Zoom out"))).build());
		boolean haveFix = FangFinderClient.fix != null;
		boolean haveRays = !FangFinderClient.machines.isEmpty();
		boolean havePlayer = this.minecraft != null
				&& this.minecraft.player != null;
		tipButton(Component.literal("Fix"), bx, by + 22, 58, 20, b -> {
			if (FangFinderClient.fix != null) {
				centerX = FangFinderClient.fix[0];
				centerZ = FangFinderClient.fix[1];
			}
		}, "Centre the map on the current triangulated fix.", haveFix,
				"No fix yet - add 2+ rays that cross.");
		tipButton(Component.literal("Me"), bx, by + 44, 58, 20, b -> {
			if (this.minecraft != null && this.minecraft.player != null) {
				centerX = this.minecraft.player.getX();
				centerZ = this.minecraft.player.getZ();
			}
		}, "Centre the map on your player.", havePlayer,
				"No player loaded.");
		tipButton(Component.literal("Clear"), bx, by + 66, 58, 20, b -> {
			FangFinderClient.clearAllData();
			lastPlan = null;
			FangFinderClient.feedback("Cleared all rays and the fix");
		}, "Delete all rays, the fix, and any placement plan.", haveRays,
				"Nothing to clear yet.");
		tipButton(Component.literal("Share"), bx, by + 88, 58, 20,
				b -> Sharing.shareSet(shareAutoName()),
				"Copy all current rays, as a named set, to the "
						+ "clipboard - paste it anywhere for another "
						+ "FangFinder user to import.", haveRays,
				"No rays to share yet.");
		this.addRenderableWidget(Button.builder(
				Component.literal(planOpen ? "Rays" : "Plan"), b -> {
					planOpen = !planOpen;
					if (this.minecraft != null) {
						this.minecraft.setScreen(new MapScreen());
					}
				}
		).pos(bx, by + 110).size(58, 20).tooltip(Tooltip.create(
				Component.literal(planOpen
						? "Return to manual ray entry."
						: "Optimal placing: work out where to build "
								+ "trackers for the best coverage."))).build());
		tipButton(Component.literal("Xa. wp"), bx, by + 132, 58, 20,
				b -> sendXaeroWaypoint(),
				"Share the fix as a Xaero's Map waypoint (via chat).",
				haveFix, "No fix yet to make a waypoint from.");
		tipButton(Component.literal("TP cmd"), bx, by + 154, 58, 20,
				b -> copyTpCommand(),
				"Copy a /tp command to the fix (needs cheats/op).",
				haveFix, "No fix yet to teleport to.");

		int y = this.height - 27;
		if (!planOpen) {
			pRayX = this.addRenderableWidget(new EditBox(this.font, 30, y,
					66, 18, pRayX, Component.literal("X")));
			pRayZ = this.addRenderableWidget(new EditBox(this.font, 118, y,
					66, 18, pRayZ, Component.literal("Z")));
			pRayYaw = this.addRenderableWidget(new EditBox(this.font, 224,
					y, 86, 18, pRayYaw, Component.literal("Yaw")));
			tipEditBox(pRayX, "The evoker's X coordinate for a manually "
					+ "entered ray.");
			tipEditBox(pRayZ, "The evoker's Z coordinate for a manually "
					+ "entered ray.");
			tipEditBox(pRayYaw, "The yaw (F3 bearing) of the fang line, "
					+ "in degrees.");
			this.addRenderableWidget(Button.builder(
					Component.literal("Add ray"), b -> {
						Double x = parseD(pRayX.getValue());
						Double z = parseD(pRayZ.getValue());
						Double yaw = parseD(pRayYaw.getValue());
						if (x != null && z != null && yaw != null) {
							FangFinderClient.addManualRay(x, z, yaw);
							FangFinderClient.feedback("Added manual ray");
						} else {
							FangFinderClient.feedback(
									"Enter numbers in X, Z and Yaw first");
						}
					}
			).pos(316, y - 1).size(62, 20).tooltip(Tooltip.create(
					Component.literal("Add a ray by hand: the evoker's X "
							+ "and Z, and the yaw of the fangs. Joins the "
							+ "solver like a detected volley."))).build());
			tipButton(Component.literal("Copy rays"), 384, y - 1, 72, 20,
					b -> copyTrackerData(),
					"Copy all rays (and the fix) for the desktop tool.",
					!FangFinderClient.machines.isEmpty(),
					"No rays to copy yet.");
		} else {
			// OPTIMAL PLACING panel, laid out down the left side with a
			// label above each field so it reads top-to-bottom.
			int px = 14;
			int fieldX = px + 4;
			boolean first = pCx == null;
			int r0 = planFieldRow0();   // first field row
			int rs = 40;                // row spacing (label + box)
			pCx = this.addRenderableWidget(new EditBox(this.font, fieldX,
					r0, 88, 18, pCx, Component.literal("Center X")));
			pCz = this.addRenderableWidget(new EditBox(this.font,
					fieldX + 96, r0, 88, 18, pCz,
					Component.literal("Center Z")));
			pSize = this.addRenderableWidget(new EditBox(this.font, fieldX,
					r0 + rs, 184, 18, pSize,
					Component.literal("Border size")));
			pN = this.addRenderableWidget(new EditBox(this.font, fieldX,
					r0 + rs * 2, 184, 18, pN,
					Component.literal("Trackers")));
			pFocus = this.addRenderableWidget(new EditBox(this.font, fieldX,
					r0 + rs * 3, 184, 18, pFocus,
					Component.literal("Focus radius")));
			tipEditBox(pCx, "X coordinate of the area's centre to plan "
					+ "trackers around.");
			tipEditBox(pCz, "Z coordinate of the area's centre to plan "
					+ "trackers around.");
			tipEditBox(pSize, "Width of the area to cover, in blocks "
					+ "(e.g. your world border size).");
			tipEditBox(pN, "How many tracker machines you plan to build "
					+ "(2-8).");
			tipEditBox(pFocus, "Concentrate accuracy within this radius "
					+ "of the centre; 0 plans for the whole area evenly.");
			if (first) {
				pCx.setValue("0");
				pCz.setValue("0");
				pSize.setValue("10000");
				pN.setValue("3");
				pFocus.setValue("0");
			}

			int rowBtns = planButtonsY();
			tipButton(Component.literal("Fill from world"), fieldX, rowBtns,
					184, 20, b -> fillPlannerFromWorld(),
					"Set Center to your position and guess the border "
							+ "from the world border, so you only need to "
							+ "pick a tracker count.",
					this.minecraft != null && this.minecraft.player != null,
					"No player loaded.");

			// Built before "Compute placement" so that button's onPress
			// can flip this one from disabled to enabled once a plan
			// exists - tipButton() only sets the disabled state once, at
			// build time, so without this the button stayed greyed out
			// forever even after a plan was computed.
			Button copyCoordsBtn = tipButton(
					Component.literal("Copy coordinates"), fieldX,
					rowBtns + 48, 184, 20, b -> {
						if (lastPlan != null) {
							if (this.minecraft != null) {
								this.minecraft.setScreen(null);
							}
							FangFinderClient.chatWithCopy(
									"tracker placement plan - ",
									lastPlan.copyText());
						}
					}, "Copy the suggested tracker coordinates to "
							+ "clipboard.", lastPlan != null,
					"Compute a placement first.");

			this.addRenderableWidget(Button.builder(
					Component.literal("Compute placement"), b -> {
						Double cx = parseD(pCx.getValue());
						Double cz = parseD(pCz.getValue());
						Double size = parseD(pSize.getValue());
						Double n = parseD(pN.getValue());
						Double focus = parseD(pFocus.getValue());
						if (cx != null && cz != null && size != null
								&& n != null && focus != null) {
							lastPlan = Planner.plan(cx, cz, size,
									(int) Math.round(n), focus);
							centerX = cx;
							centerZ = cz;
							scale = Math.max(0.02, Math.min(16.0,
									(mapX1() - mapX0() - 40)
											/ Math.max(size, 64)));
							if (lastPlan != null) {
								copyCoordsBtn.active = true;
								copyCoordsBtn.setTooltip(Tooltip.create(
										Component.literal("Copy the "
												+ "suggested tracker "
												+ "coordinates to "
												+ "clipboard.")));
							}
							FangFinderClient.feedback(lastPlan != null
									? "Plan ready: " + lastPlan.summary()
									: "Could not compute a plan");
						} else {
							FangFinderClient.feedback(
									"Fill all planner fields with numbers");
						}
					}
			).pos(fieldX, rowBtns + 24).size(184, 20).tooltip(
					Tooltip.create(Component.literal("Work out the best "
							+ "spots to build trackers for the area you "
							+ "described, and draw them on the map."))
			).build());
		}
	}

	/** Auto-fills the planner from the player position and world border. */
	private void fillPlannerFromWorld() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return;
		}
		pCx.setValue(Integer.toString(
				(int) Math.round(this.minecraft.player.getX())));
		pCz.setValue(Integer.toString(
				(int) Math.round(this.minecraft.player.getZ())));
		FangFinderClient.feedback("Center set to your position. Set the "
				+ "border size to your world's, then Compute.");
	}

	private static String shareAutoName() {
		return "set" + (shareCounter++);
	}

	private void initOsc(FangFinderConfig cfg) {
		int x = 18;
		boolean firstTime = pOx == null;
		pOx = this.addRenderableWidget(new EditBox(this.font, x, 70, 62, 18,
				pOx, Component.literal("Origin X")));
		pOy = this.addRenderableWidget(new EditBox(this.font, x + 68, 70,
				62, 18, pOy, Component.literal("Origin Y")));
		pOz = this.addRenderableWidget(new EditBox(this.font, x + 136, 70,
				62, 18, pOz, Component.literal("Origin Z")));
		pTx = this.addRenderableWidget(new EditBox(this.font, x, 108, 62,
				18, pTx, Component.literal("Target X")));
		pTy = this.addRenderableWidget(new EditBox(this.font, x + 68, 108,
				62, 18, pTy, Component.literal("Target Y")));
		pTz = this.addRenderableWidget(new EditBox(this.font, x + 136, 108,
				62, 18, pTz, Component.literal("Target Z")));
		pPass = this.addRenderableWidget(new EditBox(this.font, x, 146, 62,
				18, pPass, Component.literal("Passcode")));
		pSlot = this.addRenderableWidget(new EditBox(this.font, x + 68,
				146, 62, 18, pSlot, Component.literal("Magazine slot")));
		pNuke = this.addRenderableWidget(new EditBox(this.font, x, 184, 62,
				18, pNuke, Component.literal("Nuke size")));
		pStab = this.addRenderableWidget(new EditBox(this.font, x + 68,
				184, 62, 18, pStab, Component.literal("Stab depth")));
		tipEditBox(pOx, "Cannon origin X - chunk aligned (divisible by "
				+ "16).");
		tipEditBox(pOy, "Cannon origin Y - chunk aligned (divisible by "
				+ "16).");
		tipEditBox(pOz, "Cannon origin Z - chunk aligned (divisible by "
				+ "16).");
		tipEditBox(pTx, "Target X to hit with the cannon.");
		tipEditBox(pTy, "Target Y to hit with the cannon.");
		tipEditBox(pTz, "Target Z to hit with the cannon.");
		tipEditBox(pPass, "One of 222 valid OSC passcodes (default 940).");
		tipEditBox(pSlot, "Which magazine slot to fire from.");
		tipEditBox(pNuke, "Nuke payload size, 1-31 (used when "
				+ "Mode = Nuke).");
		tipEditBox(pStab, "Stab payload depth, 1-275 (used when "
				+ "Mode = Stab).");
		if (firstTime) {
			pOx.setValue(Integer.toString(cfg.oscOriginX));
			pOy.setValue(Integer.toString(cfg.oscOriginY));
			pOz.setValue(Integer.toString(cfg.oscOriginZ));
			pTx.setValue(Integer.toString(cfg.oscTargetX));
			pTy.setValue(Integer.toString(cfg.oscTargetY));
			pTz.setValue(Integer.toString(cfg.oscTargetZ));
			pPass.setValue(Integer.toString(cfg.oscPasscode));
			pSlot.setValue(Integer.toString(cfg.oscMagazineSlot));
			pNuke.setValue(Integer.toString(cfg.oscNukeSize));
			pStab.setValue(Integer.toString(cfg.oscStabDepth));
		}

		this.addRenderableWidget(Button.builder(fireModeText(cfg), b -> {
			cfg.oscNuke = !cfg.oscNuke;
			b.setMessage(fireModeText(cfg));
		}).pos(x, 214).size(100, 20).tooltip(Tooltip.create(
				Component.literal("Switch between Nuke and Stab payloads. "
						+ "Nuke uses Nuke Size; Stab uses Stab Depth.")))
				.build());
		this.addRenderableWidget(Button.builder(variantText(cfg), b -> {
			cfg.oscMs = !cfg.oscMs;
			b.setMessage(variantText(cfg));
		}).pos(x + 106, 214).size(100, 20).tooltip(Tooltip.create(
				Component.literal("Cannon variant. Mk6 MS uses a different "
						+ "payload offset than Mk6/6.1.")))
				.build());
		boolean haveFix = FangFinderClient.fix != null;
		tipButton(Component.literal("Use fix"), x, 240, 100, 20, b -> {
			if (FangFinderClient.fix != null) {
				pTx.setValue(Integer.toString((int) Math
						.floor(FangFinderClient.fix[0])));
				pTz.setValue(Integer.toString((int) Math
						.floor(FangFinderClient.fix[1])));
			}
		}, "Fill Target X/Z from the current tracker fix.", haveFix,
				"No tracker fix yet - triangulate a target first "
						+ "(Tracker tab).");
		tipButton(Component.literal("Copy"), x + 106, 240, 100, 20,
				b -> copyOscData(),
				"Copy the full firing solution (grid + counts) to "
						+ "clipboard via a chat button.");
	}

	private static Component fireModeText(FangFinderConfig cfg) {
		return Component.literal("Mode: " + (cfg.oscNuke ? "Nuke" : "Stab"));
	}

	private static Component variantText(FangFinderConfig cfg) {
		return Component.literal(cfg.oscMs ? "OSC Mk6 MS" : "OSC Mk6/6.1");
	}

	// ------------------------------------------------------------------
	//  Input
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
		for (int tab = 0; tab <= 1; tab++) {
			int tx = tabLeftX(tab);
			if (click.x() >= tx && click.x() <= tx + TAB_W
					&& click.y() >= tabTopY() && click.y() <= HEADER_H) {
				if (tab != activeTab && this.minecraft != null) {
					saveOscInputs();
					activeTab = tab;
					this.minecraft.setScreen(new MapScreen());
				}
				return true;
			}
		}
		return super.mouseClicked(click, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX,
			double deltaY) {
		if (activeTab == TAB_TRACKER && inMapArea(event.x(), event.y())
				&& scale > 0) {
			centerX -= deltaX / scale;
			centerZ -= deltaY / scale;
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
			double horizontalAmount, double verticalAmount) {
		if (activeTab == TAB_TRACKER && inMapArea(mouseX, mouseY)
				&& verticalAmount != 0) {
			int midX = (mapX0() + mapX1()) / 2;
			int midY = (mapY0() + mapY1()) / 2;
			double wx = centerX + (mouseX - midX) / scale;
			double wz = centerZ + (mouseY - midY) / scale;
			scale = Math.max(0.02, Math.min(16.0,
					scale * Math.pow(1.2, verticalAmount)));
			centerX = wx - (mouseX - midX) / scale;
			centerZ = wz - (mouseY - midY) / scale;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
				verticalAmount);
	}

	private boolean inMapArea(double x, double y) {
		return x >= mapX0() && x <= mapX1() && y >= mapY0() && y <= mapY1();
	}

	@Override
	public void onClose() {
		saveOscInputs();
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
	}

	private void saveOscInputs() {
		FangFinderConfig cfg = FangFinderConfig.get();
		cfg.oscOriginX = parseI(pOx, cfg.oscOriginX);
		cfg.oscOriginY = parseI(pOy, cfg.oscOriginY);
		cfg.oscOriginZ = parseI(pOz, cfg.oscOriginZ);
		cfg.oscTargetX = parseI(pTx, cfg.oscTargetX);
		cfg.oscTargetY = parseI(pTy, cfg.oscTargetY);
		cfg.oscTargetZ = parseI(pTz, cfg.oscTargetZ);
		cfg.oscPasscode = parseI(pPass, cfg.oscPasscode);
		cfg.oscMagazineSlot = parseI(pSlot, cfg.oscMagazineSlot);
		cfg.oscNukeSize = parseI(pNuke, cfg.oscNukeSize);
		cfg.oscStabDepth = parseI(pStab, cfg.oscStabDepth);
		cfg.save();
	}

	// ------------------------------------------------------------------
	//  Rendering
	// ------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX,
			int mouseY, float delta) {
		this.hoverX = mouseX;
		this.hoverY = mouseY;
		// backgrounds and panels first, so widgets composite on top
		drawChrome(g);
		super.extractRenderState(g, mouseX, mouseY, delta);
		// dynamic content and all text on top
		drawTitleAndTabs(g);
		if (activeTab == TAB_TRACKER) {
			renderTrackerMap(g);
			if (planOpen) {
				renderPlanPanel(g);
			}
		} else {
			renderOsc(g);
		}
		drawToast(g);
	}

	/**
	 * Text/labels for the Optimal-Placing panel. The panel's background
	 * fill itself is drawn earlier, in {@link #drawChrome}, so it lands
	 * behind (not on top of) the field widgets - see the comment there.
	 */
	private void renderPlanPanel(GuiGraphicsExtractor g) {
		int px = 6;
		int w = PLAN_PANEL_W;
		int tx = px + 8;
		g.text(this.font, "OPTIMAL PLACING", tx, HEADER_H + 12, C_RED);
		drawWrapped(g, PLAN_DESC, tx, HEADER_H + 26, w - 16, C_DIM);

		int r0 = planFieldRow0();
		int rs = 40;
		label(g, "Area centre (X, Z)", tx, r0 - 10);
		label(g, "World border size (width in blocks)", tx, r0 + rs - 10);
		label(g, "Number of trackers (2-8)", tx, r0 + rs * 2 - 10);
		label(g, "Focus radius (0 = whole area)", tx, r0 + rs * 3 - 10);

		// results readout, below the button block
		int ry = planButtonsY() + 48 + 20 + 14;
		if (lastPlan == null) {
			drawWrapped(g, "No plan yet. Purple markers will appear on the "
					+ "map, numbered P1..Pn - build a tracker at each.",
					tx, ry, w - 16, C_DIM);
		} else {
			g.text(this.font, "RESULT", tx, ry, C_GOLD);
			drawWrapped(g, lastPlan.summary(), tx, ry + 12, w - 16, C_TEXT);
			int py = ry + 12 + wrappedHeight(lastPlan.summary(), w - 16)
					+ 4;
			for (int i = 0; i < lastPlan.positions.length
					&& py < this.height - 16; i++) {
				double[] pp = lastPlan.positions[i];
				g.text(this.font, String.format(Locale.ROOT,
						"P%d  X %.0f  Z %.0f", i + 1, pp[0], pp[1]),
						tx, py, 0xFFB07CFF);
				py += 11;
			}
		}
	}

	private void label(GuiGraphicsExtractor g, String text, int x, int y) {
		g.text(this.font, text, x, y, C_GOLD);
	}

	/**
	 * Draws a map-marker label offset to the right of (sx, y) by default,
	 * flipping to the marker's left instead when the label would
	 * otherwise overflow past the map's right edge (x1) into the fixed
	 * button column, then clamps into [x0, x1] either way.
	 */
	private void markerLabel(GuiGraphicsExtractor g, String text, int sx,
			int y, int rightOffset, int x0, int x1, int color) {
		int w = this.font.width(text);
		int lx = sx + rightOffset;
		if (lx + w > x1) {
			lx = sx - rightOffset - w;
		}
		lx = Math.max(x0, Math.min(lx, x1 - w));
		g.text(this.font, text, lx, y, color);
	}

	/** Word-wraps text to a pixel width, drawing line by line. */
	private void drawWrapped(GuiGraphicsExtractor g, String text, int x,
			int y, int maxW, int color) {
		StringBuilder line = new StringBuilder();
		int ly = y;
		for (String word : text.split(" ")) {
			String trial = line.length() == 0 ? word : line + " " + word;
			if (this.font.width(trial) > maxW && line.length() > 0) {
				g.text(this.font, line.toString(), x, ly, color);
				ly += 10;
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(trial);
			}
		}
		if (line.length() > 0) {
			g.text(this.font, line.toString(), x, ly, color);
		}
	}

	private int wrappedHeight(String text, int maxW) {
		int lines = 1;
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String trial = line.length() == 0 ? word : line + " " + word;
			if (this.font.width(trial) > maxW && line.length() > 0) {
				lines++;
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(trial);
			}
		}
		return lines * 10;
	}

	/** Draws the transient feedback banner near the bottom-centre. */
	private void drawToast(GuiGraphicsExtractor g) {
		if (toastText.isEmpty()
				|| System.currentTimeMillis() > toastUntilMs) {
			return;
		}
		int w = this.font.width(toastText);
		int cx = this.width / 2;
		int y = this.height - 52;
		g.fill(cx - w / 2 - 8, y - 4, cx + w / 2 + 8, y + 14,
				FangFinderConfig.applyPanelOpacity(0xFF000000));
		g.fill(cx - w / 2 - 8, y - 4, cx + w / 2 + 8, y - 3, C_RED);
		g.text(this.font, toastText, cx - w / 2, y + 1, 0xFFFFE0E0);
	}

	/** Backdrop, header band, tab bodies, content panels. */
	private void drawChrome(GuiGraphicsExtractor g) {
		int panel = FangFinderConfig.applyPanelOpacity(C_PANEL);

		// The backdrop and header must also respect panelOpacity, not
		// just the smaller panel boxes - C_BG and C_PANEL are nearly
		// the same dark color, so blending panels against an opaque
		// C_BG backdrop was visually imperceptible no matter what the
		// setting was. Making the whole screen translucent together
		// (revealing the game world behind, same as vanilla's own
		// Options screens) is what actually makes it visible.
		g.fill(0, 0, this.width, this.height,
				FangFinderConfig.applyPanelOpacity(C_BG));
		g.fill(0, 0, this.width, HEADER_H,
				FangFinderConfig.applyPanelOpacity(C_HEADER));
		g.fill(0, HEADER_H, this.width, HEADER_H + 1, C_EDGE);
		for (int tab = 0; tab <= 1; tab++) {
			int tx = tabLeftX(tab);
			boolean on = tab == activeTab;
			g.fill(tx, tabTopY(), tx + TAB_W, HEADER_H,
					on ? C_TAB_ON : C_TAB_OFF);
			if (on) {
				g.fill(tx, tabTopY(), tx + TAB_W, tabTopY() + 2, C_RED);
			}
		}
		if (activeTab == TAB_TRACKER) {
			// map frame, right control strip, bottom input strip
			g.fill(mapX0() - 2, mapY0() - 2, mapX1() + 2, mapY1() + 2,
					C_EDGE);
			g.fill(mapX0(), mapY0(), mapX1(), mapY1(), C_MAP);
			g.fill(this.width - 70, mapY0() - 2, this.width - 4,
					mapY0() + 174, panel);
			g.fill(mapX0() - 2, this.height - 32, mapX1() + 2,
					this.height - 4, panel);
			// Optimal Placing panel background - must be drawn here,
			// before super.extractRenderState() renders the widgets,
			// or its opaque fill paints over the field EditBoxes and
			// buttons and makes them invisible (and un-clickable at a
			// glance, even though their hitboxes still work).
			if (planOpen) {
				int px = 6;
				int pw = PLAN_PANEL_W;
				g.fill(px, HEADER_H + 4, px + pw, this.height - 6,
						panel);
				g.fill(px, HEADER_H + 4, px + pw, HEADER_H + 5, C_RED);
			}
		} else {
			g.fill(12, HEADER_H + 6, 232, 268, panel);
			int panelX = Math.max(244, this.width / 2 - 10);
			g.fill(panelX, HEADER_H + 6, this.width - 8,
					this.height - 8, panel);
		}
	}

	private void drawTitleAndTabs(GuiGraphicsExtractor g) {
		int logoH = 30;
		int logoW = (int) Math.round(logoH * LOGO_ASPECT);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, LOGO,
				this.width / 2 - logoW / 2, 3, logoW, logoH);
		for (int tab = 0; tab <= 1; tab++) {
			int tx = tabLeftX(tab);
			boolean on = tab == activeTab;
			g.centeredText(this.font, Component.literal(
					tab == TAB_TRACKER ? "TRACKER"
							: "ORBITAL STRIKE CANNON"),
					tx + TAB_W / 2, tabTopY() + 6,
					on ? C_TEXT : C_DIM);
		}
	}

	// ---- Tracker map ---------------------------------------------------

	private void renderTrackerMap(GuiGraphicsExtractor g) {
		int x0 = mapX0();
		int y0 = mapY0();
		int x1 = mapX1();
		int y1 = mapY1();
		int midX = (x0 + x1) / 2;
		int midY = (y0 + y1) / 2;

		// grid + labels
		double step = gridStep();
		double wLeft = centerX + (x0 - midX) / scale;
		double wRight = centerX + (x1 - midX) / scale;
		double wTop = centerZ + (y0 - midY) / scale;
		double wBot = centerZ + (y1 - midY) / scale;
		for (double wx = Math.floor(wLeft / step) * step; wx <= wRight;
				wx += step) {
			int sx = midX + (int) Math.round((wx - centerX) * scale);
			if (sx >= x0 && sx <= x1) {
				g.fill(sx, y0, sx + 1, y1, 0xFF161D26);
				g.text(this.font, String.format(Locale.ROOT, "%.0f", wx),
						sx + 3, y1 - 11, C_DIM);
			}
		}
		for (double wz = Math.floor(wTop / step) * step; wz <= wBot;
				wz += step) {
			int sy = midY + (int) Math.round((wz - centerZ) * scale);
			if (sy >= y0 && sy <= y1) {
				g.fill(x0, sy, x1, sy + 1, 0xFF161D26);
				g.text(this.font, String.format(Locale.ROOT, "%.0f", wz),
						x0 + 4, sy + 3, C_DIM);
			}
		}

		// dashed rays, mathematically clipped to the map rectangle so a
		// ray is always drawn no matter how far off-screen its anchor is
		for (Measurement m : FangFinderClient.machines) {
			drawClippedRay(g, m, x0, y0, x1, y1, midX, midY, C_RED);
		}

		// imported sets: rays, anchors, and their own fixes in set colors
		for (RaySet set : Sharing.sets.values()) {
			for (Measurement m : set.rays) {
				drawClippedRay(g, m, x0, y0, x1, y1, midX, midY,
						set.color);
			}
			for (Measurement m : set.rays) {
				int sx = midX + (int) Math.round(
						(m.anchorX() - centerX) * scale);
				int sy = midY + (int) Math.round(
						(m.anchorZ() - centerZ) * scale);
				if (inMap(sx, sy, x0, y0, x1, y1)) {
					g.fill(sx - 2, sy - 2, sx + 3, sy + 3, set.color);
				}
			}
			if (set.fix != null) {
				int sx = midX + (int) Math.round(
						(set.fix[0] - centerX) * scale);
				int sy = midY + (int) Math.round(
						(set.fix[1] - centerZ) * scale);
				if (inMap(sx, sy, x0, y0, x1, y1)) {
					g.fill(sx - 6, sy, sx + 7, sy + 1, set.color);
					g.fill(sx, sy - 6, sx + 1, sy + 7, set.color);
					markerLabel(g, set.name + String.format(
							Locale.ROOT, " %.0f/%.0f", set.fix[0],
							set.fix[1]), sx, sy + 4, 8, x0, x1, set.color);
				}
			}
		}

		// planned tracker positions (optimal placing) - only while the
		// Plan tool is open, so old plans don't clutter normal tracking
		if (lastPlan != null && planOpen) {
			for (int i = 0; i < lastPlan.positions.length; i++) {
				double[] pp = lastPlan.positions[i];
				int sx = midX + (int) Math.round((pp[0] - centerX) * scale);
				int sy = midY + (int) Math.round((pp[1] - centerZ) * scale);
				if (inMap(sx, sy, x0, y0, x1, y1)) {
					g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFFB07CFF);
					markerLabel(g, "P" + (i + 1) + String.format(
							Locale.ROOT, " %.0f/%.0f", pp[0], pp[1]),
							sx, sy - 9, 6, x0, x1, 0xFFB07CFF);
				}
			}
		}

		// machine anchors
		for (Measurement m : FangFinderClient.machines) {
			int sx = midX + (int) Math.round((m.anchorX() - centerX) * scale);
			int sy = midY + (int) Math.round((m.anchorZ() - centerZ) * scale);
			if (inMap(sx, sy, x0, y0, x1, y1)) {
				g.fill(sx - 3, sy - 3, sx + 4, sy + 4, C_GOLD);
				String label = "T" + m.id()
						+ (m.fangCount() == 0 ? " (manual)" : "")
						+ (m.targetName() != null
								? " -> " + m.targetName() : "");
				markerLabel(g, label, sx, sy - 9, 6, x0, x1, C_GOLD);
			}
		}

		// fix + uncertainty ring
		if (FangFinderClient.fix != null) {
			double[] fix = FangFinderClient.fix;
			int sx = midX + (int) Math.round((fix[0] - centerX) * scale);
			int sy = midY + (int) Math.round((fix[1] - centerZ) * scale);
			if (inMap(sx, sy, x0, y0, x1, y1)) {
				double rPx = FangFinderClient.worstMiss * scale;
				if (rPx > 3) {
					for (int a = 0; a < 72; a++) {
						double ang = a * Math.PI / 36;
						int cx = sx + (int) Math.round(Math.cos(ang) * rPx);
						int cy = sy + (int) Math.round(Math.sin(ang) * rPx);
						if (inMap(cx, cy, x0, y0, x1, y1)) {
							g.fill(cx, cy, cx + 1, cy + 1, C_GREEN);
						}
					}
				}
				g.fill(sx - 8, sy, sx + 9, sy + 1, C_GREEN);
				g.fill(sx, sy - 8, sx + 1, sy + 9, C_GREEN);
				markerLabel(g, String.format(Locale.ROOT,
						"FIX %.1f / %.1f", fix[0], fix[1]), sx, sy + 6, 8,
						x0, x1, C_GREEN);
			}
		}

		// live positions of every loaded player
		if (this.minecraft != null && this.minecraft.level != null) {
			String self = this.minecraft.player != null
					? this.minecraft.player.getName().getString() : null;
			for (net.minecraft.world.entity.Entity p
					: this.minecraft.level.players()) {
				String name = p.getName().getString();
				boolean isSelf = name.equals(self);
				int sx = midX
						+ (int) Math.round((p.getX() - centerX) * scale);
				int sy = midY
						+ (int) Math.round((p.getZ() - centerZ) * scale);
				if (inMap(sx, sy, x0, y0, x1, y1)) {
					int color = isSelf ? 0xFFFFFFFF : 0xFF4FD7FF;
					g.fill(sx - 2, sy - 2, sx + 3, sy + 3, color);
					markerLabel(g, isSelf ? "you" : name, sx, sy - 4, 5,
							x0, x1, color);
				}
			}
		}

		g.text(this.font, String.format(Locale.ROOT,
				"center %.0f / %.0f · %.2f px/block · drag to pan · "
						+ "scroll to zoom · N up = -Z",
				centerX, centerZ, scale), x0 + 4, y0 + 4, C_DIM);

		// live cursor coordinate readout (top-right of the map)
		if (hoverX >= x0 && hoverX <= x1 && hoverY >= y0 && hoverY <= y1
				&& scale > 0) {
			double wx = centerX + (hoverX - midX) / scale;
			double wz = centerZ + (hoverY - midY) / scale;
			String cursor = String.format(Locale.ROOT, "X %.0f  Z %.0f",
					wx, wz);
			if (FangFinderClient.fix != null) {
				double dd = Math.hypot(wx - FangFinderClient.fix[0],
						wz - FangFinderClient.fix[1]);
				cursor += String.format(Locale.ROOT,
						"   (%.0f from fix)", dd);
			}
			g.text(this.font, cursor,
					x1 - 4 - this.font.width(cursor), y0 + 4, C_TEXT);
		}
		int ty = this.height - 22;
		if (!planOpen) {
			g.text(this.font, "X", 20, ty, C_TEXT);
			g.text(this.font, "Z", 108, ty, C_TEXT);
			g.text(this.font, "Yaw", 196, ty, C_TEXT);
		}
	}

	/**
	 * Liang-Barsky-clips the ray to the map rectangle in screen space,
	 * then draws even dashes along the visible segment only.
	 */
	private void drawClippedRay(GuiGraphicsExtractor g, Measurement m,
			int x0, int y0, int x1, int y1, int midX, int midY,
			int color) {
		double ax = midX + (m.anchorX() - centerX) * scale;
		double ay = midY + (m.anchorZ() - centerZ) * scale;
		double dx = m.dirX();
		double dy = m.dirZ();

		double t0 = 0;
		double t1 = 1.0e12;
		// X slabs
		if (Math.abs(dx) < 1.0e-12) {
			if (ax < x0 + 1 || ax > x1 - 2) {
				return;
			}
		} else {
			double ta = (x0 + 1 - ax) / dx;
			double tb = (x1 - 2 - ax) / dx;
			t0 = Math.max(t0, Math.min(ta, tb));
			t1 = Math.min(t1, Math.max(ta, tb));
		}
		// Y slabs
		if (Math.abs(dy) < 1.0e-12) {
			if (ay < y0 + 1 || ay > y1 - 2) {
				return;
			}
		} else {
			double ta = (y0 + 1 - ay) / dy;
			double tb = (y1 - 2 - ay) / dy;
			t0 = Math.max(t0, Math.min(ta, tb));
			t1 = Math.min(t1, Math.max(ta, tb));
		}
		if (t1 <= t0) {
			return;
		}

		// dash phase anchored to absolute t: 9 px on, 5 px off, 2 px wide
		for (double t = Math.ceil(t0); t <= t1; t += 1.0) {
			if (((long) t) % 14 < 9) {
				int px = (int) Math.round(ax + t * dx);
				int py = (int) Math.round(ay + t * dy);
				g.fill(px, py, px + 2, py + 2, color);
			}
		}
	}

	private static boolean inMap(int x, int y, int x0, int y0, int x1,
			int y1) {
		return x >= x0 && x <= x1 - 2 && y >= y0 && y <= y1 - 2;
	}

	private double gridStep() {
		double[] ladder = {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000,
				5000, 10000, 20000, 50000, 100000};
		for (double s : ladder) {
			if (s * scale >= 45) {
				return s;
			}
		}
		return 100000;
	}

	// ---- OSC panel -----------------------------------------------------

	private void renderOsc(GuiGraphicsExtractor g) {
		FangFinderConfig cfg = FangFinderConfig.get();
		int x = 18;
		g.text(this.font, "CANNON ORIGIN X / Y / Z  (each % 16 = 0)", x, 60,
				C_GOLD);
		g.text(this.font, "TARGET X / Y / Z", x, 98, C_GOLD);
		g.text(this.font, "PASSCODE / MAGAZINE SLOT", x, 136, C_GOLD);
		g.text(this.font, "NUKE SIZE (1-31) / STAB DEPTH (1-275)", x, 174,
				C_GOLD);

		OscMath.Result r = OscMath.calculate(
				parseI(pOx, cfg.oscOriginX), parseI(pOy, cfg.oscOriginY),
				parseI(pOz, cfg.oscOriginZ), parseI(pTx, cfg.oscTargetX),
				parseI(pTy, cfg.oscTargetY), parseI(pTz, cfg.oscTargetZ),
				cfg.oscNuke, parseI(pNuke, cfg.oscNukeSize),
				parseI(pStab, cfg.oscStabDepth),
				parseI(pSlot, cfg.oscMagazineSlot),
				parseI(pPass, cfg.oscPasscode), cfg.oscMs);

		int panelX = Math.max(244, this.width / 2 - 10);
		int cell = Math.max(8,
				Math.min(16, (this.width - panelX - 28) / 15));
		int gridX = panelX + 10;
		int gridY = HEADER_H + 28;
		g.text(this.font, "BINARY OUTPUT — "
				+ (cfg.oscMs ? "OSC Mk6 MS" : "OSC Mk6/6.1"), gridX,
				HEADER_H + 12, C_RED);
		if (!r.valid()) {
			g.text(this.font, "INVALID", gridX + 200, HEADER_H + 12,
					0xFFFF5555);
		}

		for (int c = 0; c < 13; c++) {
			int fx = gridX + (c + 1) * cell;
			g.fill(fx, gridY, fx + cell - 1, gridY + cell - 1,
					COLUMN_COLORS[c]);
			g.fill(fx, gridY + 6 * cell, fx + cell - 1,
					gridY + 7 * cell - 1, COLUMN_COLORS[c]);
		}
		for (int row = 0; row < 5; row++) {
			int shade = 0xFF000000 | (0x2A + row * 0x12) << 16
					| (0x30 + row * 0x12) << 8 | (0x38 + row * 0x12);
			int fy = gridY + (row + 1) * cell;
			g.fill(gridX, fy, gridX + cell - 1, fy + cell - 1, shade);
			g.fill(gridX + 14 * cell, fy, gridX + 15 * cell - 1,
					fy + cell - 1, shade);
		}
		for (int row = 0; row < 5; row++) {
			for (int c = 0; c < 13; c++) {
				int lx = gridX + (c + 1) * cell;
				int ly = gridY + (row + 1) * cell;
				g.fill(lx, ly, lx + cell - 1, ly + cell - 1, 0xFF15181C);
				boolean on = r.grid[row][c] == 1;
				int inset = Math.max(2, cell / 4);
				g.fill(lx + inset, ly + inset, lx + cell - 1 - inset,
						ly + cell - 1 - inset,
						on ? 0xFF35E635 : 0xFF262B31);
				if (on) {
					g.fill(lx + inset + 1, ly + inset + 1,
							lx + inset + 3, ly + inset + 3, 0xFFB6FFB6);
				}
			}
		}

		int ty = gridY + 7 * cell + 10;
		if (r.valid()) {
			g.text(this.font, String.format(Locale.ROOT,
					"coarse  X %d  Y %d  Z %d", r.coarse[0], r.coarse[1],
					r.coarse[2]), gridX, ty, C_TEXT);
			g.text(this.font, String.format(Locale.ROOT,
					"fine    X %d  Y %d  Z %d", r.fine[0], r.fine[1],
					r.fine[2]), gridX, ty + 11, C_TEXT);
			g.text(this.font, String.format(Locale.ROOT,
					"payload lands  %.2f / %.2f / %.2f", r.exactPos[0],
					r.exactPos[1], r.exactPos[2]), gridX, ty + 22, C_TEXT);
			g.text(this.font, "time " + r.timeTotal + "  (payload "
					+ r.timePayload + " + accel " + r.timeAccel + ")",
					gridX, ty + 33, C_DIM);
			g.text(this.font, "sign bits: row5 col4 = Z-, row5 col6 = X-",
					gridX, ty + 44, C_DIM);
		} else {
			int ey = ty;
			for (int i = 0; i < Math.min(6, r.errors.size()); i++) {
				g.text(this.font, "! " + r.errors.get(i), gridX, ey,
						0xFFFF5555);
				ey += 11;
			}
		}
	}

	// ------------------------------------------------------------------
	//  Actions
	// ------------------------------------------------------------------

	/**
	 * Shares the current fix in Xaero's waypoint chat format. Xaero's
	 * Minimap intercepts chat lines beginning with "xaero-waypoint:" (its
	 * WaypointSharingHandler hooks chat message addition, including local
	 * system messages) and turns them into an add-waypoint prompt. The
	 * dimension parameter is omitted, which Xaero documents as "use the
	 * player's current dimension".
	 */
	private void sendXaeroWaypoint() {
		if (FangFinderClient.fix == null) {
			return;
		}
		int x = (int) Math.floor(FangFinderClient.fix[0]);
		int z = (int) Math.floor(FangFinderClient.fix[1]);
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
		FangFinderClient.chat(Component.literal(
				"xaero-waypoint:FangFix:F:" + x + ":64:" + z
						+ ":11:false:0"));
		FangFinderClient.chatWithCopy(
				"fix " + x + " / " + z + " shared as Xaero waypoint - ",
				x + " " + z);
	}

	/** Copies a ready-to-paste /tp to the fix (a common admin/creative use). */
	private void copyTpCommand() {
		if (FangFinderClient.fix == null) {
			return;
		}
		int x = (int) Math.floor(FangFinderClient.fix[0]);
		int z = (int) Math.floor(FangFinderClient.fix[1]);
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
		FangFinderClient.chatWithCopy("teleport command - ",
				"/tp @s " + x + " ~ " + z);
	}

	private void copyTrackerData() {
		StringBuilder sb = new StringBuilder();
		for (Measurement m : FangFinderClient.machines) {
			sb.append(m.copyLine()).append('\n');
		}
		if (FangFinderClient.fix != null) {
			sb.append(String.format(Locale.ROOT, "# fix %.3f %.3f%n",
					FangFinderClient.fix[0], FangFinderClient.fix[1]));
		}
		if (sb.length() == 0) {
			return;
		}
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
		FangFinderClient.chatWithCopy("tracker data ready - ",
				sb.toString());
	}

	private void copyOscData() {
		FangFinderConfig cfg = FangFinderConfig.get();
		saveOscInputs();
		OscMath.Result r = OscMath.calculate(cfg.oscOriginX, cfg.oscOriginY,
				cfg.oscOriginZ, cfg.oscTargetX, cfg.oscTargetY,
				cfg.oscTargetZ, cfg.oscNuke, cfg.oscNukeSize,
				cfg.oscStabDepth, cfg.oscMagazineSlot, cfg.oscPasscode,
				cfg.oscMs);
		StringBuilder sb = new StringBuilder();
		sb.append("OSC ").append(cfg.oscMs ? "Mk6 MS" : "Mk6/6.1")
				.append("  target ").append(cfg.oscTargetX).append(' ')
				.append(cfg.oscTargetY).append(' ').append(cfg.oscTargetZ)
				.append("  mode ").append(cfg.oscNuke ? "nuke" : "stab")
				.append('\n');
		for (int[] row : r.grid) {
			for (int v : row) {
				sb.append(v);
			}
			sb.append('\n');
		}
		sb.append("coarse ").append(r.coarse[0]).append('/')
				.append(r.coarse[1]).append('/').append(r.coarse[2])
				.append("  fine ").append(r.fine[0]).append('/')
				.append(r.fine[1]).append('/').append(r.fine[2])
				.append("  time ").append(r.timeTotal);
		if (!r.valid()) {
			sb.append("\nINVALID: ").append(String.join("; ", r.errors));
		}
		if (this.minecraft != null) {
			this.minecraft.setScreen(null);
		}
		FangFinderClient.chatWithCopy("firing solution ready - ",
				sb.toString());
	}

	// ------------------------------------------------------------------

	/**
	 * Builds a button with a hover tooltip. If {@code enabled} is false the
	 * button greys out and its tooltip explains why (using {@code
	 * disabledReason}) instead of the normal help text.
	 */
	private Button tipButton(Component label, int x, int y, int w, int h,
			Button.OnPress action, String help, boolean enabled,
			String disabledReason) {
		Button b = Button.builder(label, action).pos(x, y).size(w, h)
				.tooltip(Tooltip.create(Component.literal(
						enabled ? help : disabledReason)))
				.build();
		b.active = enabled;
		return this.addRenderableWidget(b);
	}

	private Button tipButton(Component label, int x, int y, int w, int h,
			Button.OnPress action, String help) {
		return tipButton(label, x, y, w, h, action, help, true, "");
	}

	/** Adds a hover tooltip to an already-added EditBox. */
	private static void tipEditBox(EditBox box, String help) {
		box.setTooltip(Tooltip.create(Component.literal(help)));
	}

	private static Double parseD(String s) {
		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException | NullPointerException e) {
			return null;
		}
	}

	private static int parseI(EditBox box, int fallback) {
		if (box == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(box.getValue().trim());
		} catch (NumberFormatException | NullPointerException e) {
			return fallback;
		}
	}
}
