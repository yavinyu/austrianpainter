# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Austrian Painter — a client-side Fabric mod for Minecraft **26.1.2** that repaints blocks with
another block's textures at model-bake time, while keeping the original block's shape, collision
and behaviour. Nothing is placed, broken, or sent to the server. Built for Hypixel Skyblock's
Catacombs dungeon, where paint applied inside a room is stored relative to that room's marker
corner (rotation removed) so it survives the layout being re-randomised each run.

Language: Kotlin (`src/main/kotlin`), with three Java mixins (`src/main/java/com/maxisch/mixin/client`).
Build: Gradle (Kotlin DSL) via fabric-loom.

Read `README.md` before making user-facing changes — it documents every keybind, `/paintbrush`
subcommand, paint-menu tab, and the dungeon/boss-room scoping rules in detail. Treat it as the
source of truth for intended behavior, not just documentation.

## Build, test, run

```sh
./gradlew build       # jar in build/libs
./gradlew test        # pure-logic JUnit 5 tests only, no Minecraft bootstrap (src/test/kotlin)
./gradlew runClient   # dev client
./gradlew check       # build + test + checkLwjglVersion (fails if lwjgl_version drifts from what Minecraft ships)
```

Run a single test class/method the normal Gradle way, e.g.
`./gradlew test --tests "com.maxisch.paint.PaintIndexTest"`.

**Java 25 required.** Loom checks the JVM Gradle itself runs on, not just the toolchain.
`gradle.properties` pins `org.gradle.java.home` to one machine's JDK path — **do not edit that
line**; put a personal override in `~/.gradle/gradle.properties` instead, which takes precedence.

Every build prints a "mappings not built for this version" warning — expected. Minecraft ships
unobfuscated from 26.x on, so nothing is actually remapped; the yarn mapping entry is only a
placeholder Loom requires to configure at all.

`runClient` logs in via DevLogin's device-code flow (needed to join online-mode servers like
Hypixel). First launch prints a `microsoft.com/link` code to the console; credentials cache in
`~/.devlogin/accounts.json`, never in this repo. Hot swapping needs the JetBrains Runtime (DCEVM);
the practical workflow is IntelliJ's generated "Minecraft Client" run config in **debug** mode with
its JRE set to that runtime, then Ctrl+Shift+F9 to push a change.

CI (`.github/workflows/build.yml`) runs `./gradlew build` on `ubuntu-latest` with Java 25 on push/PR
to `main`, and uploads test reports on failure. No checkstyle/spotless/ktlint configured.

## Architecture

**Everything paints through `PaintStorage`** (`paint/`), the facade that delegates to:
- `PaintSession` (`paint/session/`) — transient authoring state: brush, area selection, scan. Decides *what* the rules apply to and *when* they're written.
- `PaintRules` (`paint/rule/`) — the rules themselves: `AreaRule`, `BossZones`, `DeviceColumns`, `DoorZones`, `ColumnRules`.
- `PaintIndex` / `PaintIndexBuilder` — the flattened, **immutable** snapshot that chunk-build worker threads read. Swapped whole, never mutated in place — this is why edits are batched per stroke rather than per block, and why chunk-bake reads never need locking.

Repainting itself happens at model-bake time via `ModelLoadingPlugin`/`PaintModelPlugin`
(`client/render/`) — a painted block renders as its donor's model/textures but keeps its real
block's collision, sound, and behavior. No world edit ever occurs.

**Presets** (`paint/preset/`: `Presets`, `PresetStore`, `PresetCodec`) are hand-written JSON, not
Gson — this keeps coordinate arrays on one line instead of Gson's pretty-printer exploding a large
preset across many lines. Preserve this format when touching preset serialization. Config lives
under `config/ap/` (`run/config/ap` in the dev client); see the "Config layout" section of
README.md for the exact file layout and ruleset key syntax (`*all`, `*unpainted`, `@` paint-state
suffixes, `palette:` prefix).

**Dungeon scoping** (`dungeon/`): rooms are identified by hashing a column of blocks and looking it
up in a room list served by NoammAddons (`detect/RoomScanner`, `DoorScanner`, `WorldProbe`), then
oriented by finding a marker block Hypixel leaves on one roof corner. Paint is stored relative to
the room's un-rotated marker corner (`room/RoomTransform`, `RoomDataStore`) and re-projected onto
every scanned room in render distance — not just the one the player stands in — though edits always
write to the room the player is currently in. Boss rooms (`B1`–`B7`) sit at fixed coordinates, need
no scanning, and their paint is loaded/unloaded on arena entry/exit rather than carried dungeon-wide
(loading it dungeon-wide would cost a lookup per block on every chunk bake for no visible benefit).
The dungeon scanner and the F7 device-column feature are trimmed ports of NoammAddons'
`DungeonScanner`/`UniqueRoom` and `IHateDiorite` — see README.md's Credits section before modifying
that logic, to understand what was intentionally left out (map rendering, secrets, score tracking).

**GUI** (`client/gui/`, `client/render/render2d/`): the paint menu is drawn through a NanoVG
renderer ported from the `rsm` project (picture-in-picture bridge into Minecraft's GUI pass, not a
mod-menu framework — this mod's tabs are workflows, not a list of toggles). NanoVG is bundled via
jar-in-jar (`include(...)` in `build.gradle.kts`) since Minecraft doesn't ship it; its version must
stay compatible with whatever LWJGL core version Minecraft bundles, which `checkLwjglVersion`
enforces at `check` time.

**Undo/redo** (`paint/PaintHistory`): a 20-entry stack. A single change over 400,000 positions is
never recorded (and clears the whole history rather than lying about continuity). History is
cleared on any scope change: world join/leave, preset switch, or walking through a Catacombs
doorway — recorded coordinates belong to one slice's coordinate space and can't replay into another.

## Source layout

```
src/main/java/com/maxisch/mixin/client/   step sounds, break sound/particles, scroll-to-resize
src/main/kotlin/com/maxisch/
  client/            entrypoint, keybinds, /paintbrush command, key hints
  client/gui/        paint screen, block picker, YACL settings integration
  client/gui/tab/    the five paint-menu tabs (Brush, Area, Palette, Presets, History)
  client/gui/widget/ shared row list and text line widgets
  client/render/     model wrapper, sprite/texture borrowing, face culling, HUD, selection box
  client/render/render2d/  NanoVG bridge
  paint/             PaintStorage facade, PaintIndex, preset codec, history, settings, paths
  paint/session/     transient authoring state: brush, area, selection, area scan
  paint/rule/        AreaRule, BossZones, DeviceColumns, DoorZones, ColumnRules
  dungeon/           room/door scanning, room data store, coordinate transform
```
