# TStar's FangFinder

Client-side Fabric mod for Minecraft **26.1.2** that reads the *exact* bearing
of every evoker fang line attack and triangulates the tracked player.
Companion to the desktop Evoker Fang Triangulator (Python) - the F9 copy
output pastes straight into it.

## Why this beats sighting the fangs manually

The server computes a single angle
`f = Mth.atan2(targetZ - evokerZ, targetX - evokerX)` and spawns the 16 fangs
of a line attack at `evoker + (cos f, sin f) * 1.25 * (i + 1)`.

Two facts make positions the right thing to measure:

1. Fang **spawn packets carry X/Y/Z as full 64-bit doubles.** The direction
   between any two fangs of a volley reproduces the server's aim vector to
   ~1e-6 degrees (verified by simulation against a replica of the game's
   sin table).
2. The fang entity's **rotation is quantized to one byte** (1.40625 degree
   steps) in the spawn packet, so reading the entity's yaw on the client -
   or eyeballing the fang models, which render from that same byte - can be
   more than a degree off.

The remaining error in a triangulated fix is Minecraft's own aim
quantization (the sin lookup table has ~0.0055 degree steps and Mth.atan2 is
a float approximation): about 1-2 blocks at 2.8 km with 3 trackers, and it
shrinks with distance and with more volleys. No measurement method can do
better, because the fangs themselves only point that accurately.

## What it does

- Detects every evoker fang volley from spawn packets (fully passive -
  nothing is sent to the server).
- For line volleys, computes the exact yaw (Minecraft F3 convention,
  printed to 10 decimals), the ray anchor, and - when all 16 fangs
  spawned - the caster's exact X/Z.
- Handles volleys with **missing fangs** (a fang that finds no valid ground
  is skipped by the server); gaps are detected via the 1.25-block spacing
  and don't affect the angle.
- Ignores close-range **ring volleys** (target within 3 blocks of the
  evoker) with a chat notice - those carry no single direction.
- Built-in solver: every volley is added as a ray; with 2+ rays the
  least-squares intersection (the fix) plus per-ray miss distance is shown
  on the HUD and in chat.
- Chat lines have `[copy]` buttons; **F9** copies all measurements as
  `anchorX anchorZ yaw` lines - the exact format the desktop triangulator
  expects (X / Z / Yaw per tracker row).

## Keys (rebindable in Options > Controls)

| Key | Action |
|-----|--------|
| N   | Open the full-screen map / OSC screen |
| F6  | Toggle the whole mod on/off |
| F7  | Print the tracker placement guide |
| F8  | Toggle the HUD overlay |
| F9  | Copy all measurements (click the chat button) |
| F10 | Clear measurements and the fix |

## Moving targets & multiple machines

Each MACHINE contributes only its newest ray to the fix. Volleys anchored
within `machineRadius` blocks (default 32, configurable) of an existing
measurement are recognized as the same machine: an unchanged bearing just
bumps its repeat counter, a changed bearing (the target moved) replaces
the machine's ray and updates the fix. Keep separate machines further
apart than the radius. The HUD shows each ray's age; optionally rays can
go stale after a configurable time and drop out of the fix.

## Branding

The mod ships its own icon (shown in ModMenu/mod lists) and title logo
(shown in the map/OSC screen header and, optionally, above the in-game
HUD overlay). Both are real GUI sprites drawn via the game's own sprite
atlas, not text.

## Quality-of-life features

- **Fix-lock sound** - a rising ping plays the moment two rays first
  cross into a fix, so you know you have a lock without watching the HUD.
  Toggle in config (General).
- **Targeted alert** - if a volley is aimed at YOU (you are the loaded
  player on the ray), you get a red chat warning plus a low urgent tone.
  Toggle in config (Tracking).
- **Live cursor coordinates** - hovering the map shows the world X/Z
  under the cursor in the top-right, plus its distance from the current
  fix. Handy for measuring and eyeballing placements.
- **TP command button** - copies a ready `/tp @s X ~ Z` to the fix
  (creative/admin worlds) as a clickable chat line.
- **Button tooltips & disabled states** - complex buttons show a hover
  tooltip explaining what they do. Buttons that can't be used right now
  (e.g. "Use fix" with no fix, "Copy rays" with no rays) are greyed out
  and their tooltip explains why.
- **On-screen feedback** - status messages (e.g. "No rays to share yet",
  "Plan ready", "Added manual ray") now appear as a banner inside the
  FangFinder screen, instead of only in chat where the GUI hides them.
- **Smarter placement planner** - the Plan tool now searches ring,
  ring-plus-center, and split-ring layouts, then runs a coordinate-
  descent refinement pass to squeeze the worst-case error down further.
  It reports a coverage percentage (share of the area within 3 blocks)
  and marks when refinement improved the result.
- **Commands**: `/fangfinder help` lists everything, `/fangfinder status`
  prints the current fix, machine count, worst miss, geometry quality,
  and (if in-world) the distance and F3 bearing from you to the fix.
  `/fangfinder clear` wipes state. Bare `/fangfinder` prints help.

## Config

`config/fangfinder.json`, or in-game via ModMenu (optional dependency -
a themed config screen, matching the map screen's look, appears on
FangFinder's entry when ModMenu is installed). Options, grouped:

- **General** - mod on/off, chat announcements, HUD logo on/off
- **Tracking** - same-machine radius, ray staleness, default map zoom
- **Appearance** - panel opacity, credit name (shown bottom-left of
  both the map screen and the config screen)

## Building

Requires **JDK 25** (Minecraft 26.1 needs Java 25 for the Gradle JVM).

```
./gradlew build        # gradlew.bat build on Windows
```

The jar lands in `build/libs/fangfinder-1.0.0.jar`.

## Installing

1. Install Fabric Loader 0.19.3+ for Minecraft 26.1.2.
2. Drop these into your `mods` folder:
   - `fangfinder-1.0.0.jar`
   - Fabric API `0.154.0+26.1.2` (or any 26.1.2 build)

## Using it with the tracking machine

1. Trigger the machine so the damaged evoker fires its line attack at the
   wind-charge owner (they must be more than 3 blocks away, or you get the
   ring attack).
2. The volley appears in chat and on the HUD with its exact yaw.
3. Repeat from a second (ideally well-separated) tracker - the fix appears
   automatically. More volleys = tighter fix; watch the "worst miss" line.
4. Or press F9 and paste into the desktop triangulator for the map view.

Note: the ray anchor is the first received fang, not the evoker - both lie
on the same line, so triangulation is unaffected. The caster position is
also printed whenever a full 16/16 volley makes it exact.

Purely observational, but on servers, whether coordinate-deriving mods are
allowed is up to the server's rules - check before using it in multiplayer.


## Project layout note

The zip now extracts with the project at the ROOT (build.gradle next to
this README), so `gradlew.bat build` runs from wherever you extracted.
If you are updating over an old copy, delete the old `.gradle` and
`build` folders first - they are generated caches from previous versions
and can hold stale state. Everything you need to keep is in `src/`,
`gradle/`, and the top-level gradle files.

## Map screen (N)

A Xaero's-style full-screen view with two tabs.

**Tracker** - a pannable, zoomable world map (drag to pan, scroll to
zoom at the cursor, +/- buttons too)
showing every fang ray (dashed red), tracker machines (gold, with target
names when identified), the least-squares fix (green crosshair with
uncertainty ring), and your own position. Manual rays can be typed in at
the bottom (X / Z / Yaw) exactly like the desktop calculator, and "Copy
rays" exports everything in the desktop tool's format.

Every loaded player is drawn live (cyan, with name; you are white), so
a target near one of your evokers moves on the map in real time. Rays
are clipped mathematically to the viewport, so they render cleanly no
matter how far away the machines are or how deep you zoom. "Xa. wp"
shares the current fix in Xaero's waypoint chat format (see below).
"Clear" resets everything including the T-numbering.

## Xaero's World Map

The build script includes Xaero's maven and an optional (compile-only)
dependency on `xaero.map:xaeroworldmap-fabric-26.1.2:1.42.0` per their
developer docs, so the project is ready for deeper hooks. The mod runs
fine with or without Xaero installed; the shipped integration is the
waypoint share above, which needs no API at all. The waypoint share uses the documented chat schema: Xaero's
WaypointSharingHandler intercepts chat lines starting with
"xaero-waypoint:" (verified against its published stack traces - it
hooks chat message addition, so locally printed lines count) and shows
an add-waypoint prompt. The dimension parameter is omitted, which
Xaero's schema documents as "default to the player's current
dimension". Deeper integration (drawing rays directly onto Xaero's own
map) would require compiling against their closed-source internals and
is deliberately not attempted blind.

**Orbital Strike Cannon** - a full port of cubicmetre's fire-control app
(github.com/cubicmetre/osc-fire-control) for the OSC Mark 6 / 6.1 and the
Mark 6 MS variant. Enter cannon origin (chunk-aligned: each coordinate
divisible by 16), target, passcode (222 valid codes, default 940), fire
mode (nuke 1-31 / stab 1-275), and magazine slot; the 5x13 binary LED
grid renders live with the same color-coded frame as the control panel,
plus coarse/fine counters, exact payload landing position, firing time
estimate, and every validation rule from the original. "Use tracker fix
as target" pipes the triangulated position straight into the cannon. The
math was verified bit-for-bit against the reference implementation on 500
randomized firing solutions.


## Data sharing (co-op tracking)

Rays and whole ray SETS travel between mod users over ordinary chat:

- `/fangfinder shareray` - broadcast your newest ray. Anyone nearby with
  the mod auto-adds it to their live tracking, so two players sitting at
  two different machines both get the fix instantly (share angles both
  ways and both clients solve the same intersection).
- `/fangfinder share <name>` or the map's "Share" button - broadcast all
  current rays as a named set, chunked under the chat length limit.
  Receivers import it as a separate colored layer on the map, complete
  with its own fix. If a set with that name already exists, the receiver
  is prompted in chat with clickable [overwrite] [keep both] [discard]
  choices (keep-both renames to name-2).
- `/fangfinder sets` lists imports, `/fangfinder remove <name>` deletes.

Wire format is plain text (`ff|ray|x,z,yaw` / `ff|set|name|i/n|...`), so
it works on any server and is human-readable for players without the mod.

## Optimal placing (Plan button, Tracker tab)

Click **Plan** on the Tracker tab to open a labelled panel down the left
side. Each field has a heading above it explaining what to enter:

- **Area centre (X, Z)** - the middle of the region you want to cover
  (e.g. spawn, or the middle of your world border).
- **World border size** - the border width in blocks.
- **Number of trackers (2-8)** - how many machines you'll build.
- **Focus radius** - leave 0 to plan for the whole area, or set a radius
  to concentrate accuracy near one spot.

**Fill from world** sets the centre to your current position so you only
have to type the border size and tracker count. **Compute placement**
then works out the best spots and draws them on the map as purple
markers P1..Pn, and the panel shows a plain-English result: worst-case
and average fix error, and what percentage of the area is covered well.
**Copy coordinates** exports the P1..Pn positions.

Under the hood it searches ring, ring-plus-centre and split-ring layouts
and then refines them, using the game's own aim quantization as the
error model - but you don't need to know any of that to use it: describe
the area, pick a tracker count, Compute, and build a tracker at each
purple marker.

