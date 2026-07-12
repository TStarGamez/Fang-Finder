# Changelog

## [3.7.3] - 2026-07-12
### Fixed
- `fabric.mod.json` declared compatibility with only `~26.1.2`, when the
  code (and the pinned Fabric API build) actually works fine on the
  whole 26.1.x line. Widened to `>=26.1.0 <26.2.0` so the mod doesn't
  falsely refuse to load on 26.1 or 26.1.1.

## [3.7.2] - 2026-07-12
### Fixed
- Panel opacity had no visible effect on the map screen or config
  screen. Root cause: both draw a fully opaque full-screen backdrop
  (`C_BG`) before anything else, and `C_BG` is almost the same dark
  color as the panels (`C_PANEL`) - so blending a translucent panel
  against an opaque, nearly-identical-colored backdrop was
  imperceptible regardless of the setting. The backdrop and header
  band on both screens now respect panelOpacity too, so lowering it
  actually reveals the game world behind the GUI, same as vanilla's
  own Options screens.

## [3.7.1] - 2026-07-12
### Fixed
- The on-screen feedback banner (toast) on the map screen used a
  hardcoded background alpha instead of the panel opacity setting -
  the one remaining panel background that didn't respect it. Audited
  every panel fill across the HUD, map screen, and config screen;
  this was the last gap. Screen backdrops (the solid full-screen
  background behind each GUI) and the tracker map's own viewport are
  intentionally left fully opaque, same as before.

## [3.7.0] - 2026-07-12
### Changed
- `/fangfinder share`/`shareray` (and the map's "Share" button) no
  longer broadcast a live network chat message for other players'
  clients to auto-import. They now copy the payload straight to your
  clipboard instead, for you to paste anywhere - a receiving client
  still recognizes the format if it ends up in chat, so pasting it
  into chat yourself still auto-imports for anyone nearby with the
  mod. This trades away automatic, no-effort co-op sharing for not
  spamming public/server chat with tracking data.
- Named-set sharing no longer chunks the payload under the old chat
  length limit; the whole set is copied as a single payload.

## [3.6.6] - 2026-07-12
### Fixed
- The mod's keybind category showed as the raw untranslated string
  "key.category.fangfinder.main" in Controls, instead of "FangFinder".
  The lang file had two incorrect guesses at the key
  ("key.categories.fangfinder.main" and "key_category.fangfinder.main")
  and neither matched what Minecraft actually looks up.
- "Toggle mod on/off" (F6) only ever stopped new fang detection and
  hid the HUD. Two other passive background behaviors kept running
  while disabled: the periodic stale-ray recompute (which could still
  update the fix and trigger the fix-lock sound) and incoming chat-based
  ray/set imports from other FangFinder users. Both now also respect
  the master switch, so disabling genuinely halts everything passive
  until re-enabled. Explicit user actions (commands, the map screen,
  manual ray entry) are intentionally left available either way.

## [3.6.5] - 2026-07-12
### Fixed
- Panel opacity setting only ever affected two background fills on the
  map screen. The HUD overlay (hardcoded to a fixed alpha), the
  Optimal Placing panel, and the entire config screen's own panels
  all ignored it completely. Centralized into one
  `FangFinderConfig.applyPanelOpacity()` helper and wired it into
  every panel background across all three screens.
- "Mod enabled" toggle: disabling the mod produced no visible change
  if nothing was actively being tracked yet (the HUD only rendered
  when there were active machines, so there was nothing to hide).
  The HUD now shows a persistent "FangFinder OFF (F6)" indicator
  whenever the mod is disabled, regardless of tracking state, and the
  Options screen's toggle now also posts the same chat confirmation
  the F6 keybind already gave.

## [3.6.4] - 2026-07-12
### Fixed
- Planner panel: "Copy coordinates" stayed permanently greyed out even
  after computing a plan, because its disabled state was only ever set
  once at screen-init time and never refreshed. "Compute placement"
  now re-enables it directly the moment a plan is ready.
- Planner panel: the Fill from world / Compute placement / Copy
  coordinates button block sat too far below the input fields; moved
  it up, and moved the results readout up to match.
- Config screen: widened the gap above the Appearance section so its
  header no longer overlaps the last General/Tracking row.

## [3.6.3] - 2026-07-12
### Fixed
- Optimal Placing panel: its background fill was drawn *after* the
  field EditBoxes and buttons had already rendered, painting over
  them so they were invisible (and looked non-functional even though
  their hitboxes still worked). The fill now happens before the
  widgets render, same as every other panel.
### Removed
- The "Credit name" config option and its "by <name>" display in the
  corner of the map screen and config screen.

## [3.6.2] - 2026-07-12
### Fixed
- Optimal Placing panel: the description text could wrap onto more lines
  than the layout assumed, drawing over the "Area centre" field label.
  The field row now starts below the description's actual wrapped
  height instead of a fixed guess.
- Tracker map: marker labels (fix, plan markers, tracker anchors, player
  names) could overflow past the map's right edge and overlap the
  button column when the marker was near the edge. Labels now flip to
  the marker's left side instead of overflowing.
- Config screen: the "APPEARANCE" section header and panel background
  were computed from a different Y origin than the actual widgets,
  causing the header to render on top of the Panel opacity button.
  Both now derive from the same layout constants.
### Added
- Hover tooltips on every remaining input field and button across the
  map screen and config screen that didn't already have one.
