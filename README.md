# Austrian Painter

A client-side Fabric mod for Minecraft **26.1.2** that repaints blocks with another block's
textures while keeping their original shape, collision and behaviour.

The repaint happens at model-bake time. Nothing is ever placed, broken or sent to the server — the
mod is invisible to it. It was built for Hypixel Skyblock's Catacombs, where paint made inside a
room is stored relative to that room so it survives the layout being re-randomised each run.

---

## What it can do

Three kinds of rule, each stored in its own preset folder:

| Rule | What it does | Scope |
|---|---|---|
| **Positional paint** | This exact coordinate borrows that block's textures | Per dimension, or per dungeon room |
| **Block-type paint** | *Every* block of type X renders as Y | Global |
| **Donor palette** | A weighted bag of donors an area fill draws from | Authoring only, shared by every world |

A palette is never consulted while rendering: an area fill rolls it once and writes the concrete
result into the positional preset, so it behaves exactly like a very large brush stroke.

Painted blocks also take the donor's step sound, break sound and destroy particles (toggleable). A
donor whose model draws nothing — barrier, structure void, light — renders the block invisible while
its collision and selection outline stay put.

---

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.150.0+26.1.2 |
| fabric-language-kotlin | 1.13.13+kotlin.2.4.10 |
| **YetAnotherConfigLib (YACL)** | 3.9.6+26.1-fabric — **required**, the settings screen is built on it |
| ModMenu | Optional. Adds the config button to the mod list |

Client-side only. Drop the jar and its dependencies in `mods/`.

---

## Keybinds

All under the **Austrian Painter** category in Controls.

| Default | Action |
|---|---|
| `P` | Open the paint menu |
| `G` | Brush: paint at the crosshair |
| `H` | Brush: erase at the crosshair |
| `O` | Open the paint menu on the Area tab |
| `[` | Set area corner 1 |
| `]` | Set area corner 2 |
| `\` | Clear the area selection |
| `Z` | Undo the last paint change |

**Hold the paint key and scroll** to resize the brush. This is the one gesture with no button
behind it; the HUD says so while the brush is on (turn that off under Settings → Show usage hints).

Every hint the mod prints reads the *live* binding, so rebinding a key does not leave the UI naming
the old one.

---

## `/paintbrush`

A client command. Nothing here reaches the server.

| Subcommand | Effect |
|---|---|
| *(none)* | Brush status: on/off, donor, radius |
| `on` / `off` / `toggle` | Arm or disarm the brush |
| `donor <block>` | Set the donor block, and arm the brush |
| `radius <1-5>` | Brush size; a radius of `r` paints a cube of side `2r-1` |
| `undo` | Same as the undo key |
| `room` | Dungeon diagnostics: known room cores, floor, scope key, origin, rotation, painted count |
| `cull` / `cull reset` | Face-culling counters, for diagnosing a hole in a wall |
| `dungeon <off\|F1-F7\|M1-M7> [boss]` | Pretend to be on that floor. Session only — see below |
| `sound <true\|false>` | Painted sounds on or off |

---

## The paint menu

One screen, four tabs, reached with `P`.

**Brush** — choose a donor, arm the brush, set the size and the mode, then `Apply` for the block
you are looking at. The list underneath is everything painted in the current scope, grouped by
donor; click a row to delete that whole group. Removing a *single* position stays a world action:
look at it and press the erase key.

Mode is either **Position** (this one block) or **Block type** (every block of that type,
everywhere). Both read whatever you were looking at when the menu opened — the crosshair freezes as
soon as a screen is up, so it is captured once on open.

**Area** — set two corners, with the keys, the X/Y/Z boxes, or the "Use looked-at" buttons. The list
shows what is actually inside the box, most common first, plus an "Everything" row. Click one to
choose what gets replaced, then either:

- **Replace** — one donor over the whole selection, or
- **Replace random** — a weighted draw from the active palette, one roll per position.
- **Re-roll** redraws the last apply over the same positions with a fresh roll.

The selection is drawn in-world as a coloured box (colours are configurable). The ceiling is
**2,000,000 blocks**; past that everything is refused rather than stalling the client.

**Palette** — add and remove donors, and scroll a row to change its weight (hold shift for ±10).
Weights are relative, so each row shows the share it works out to.

**Presets** — one folder manager for all three kinds. New, Duplicate, Rename, Delete, Activate.
Every refusal says why rather than doing nothing.

Results appear on the status line above the footer, not in chat — chat is unreadable while a screen
is open. Actions triggered by a keybind, where no screen is up, still report to chat.

---

## Undo

The last **20** changes can be undone, with the `Z` key, the footer button, or `/paintbrush undo`.

Two deliberate limits:

- A single change over **400,000 positions** is not recorded at all, and recording it would be a
  lie about everything older — so the whole history is cleared and the status line says so. A
  full-size area apply is intentionally outside what can be undone.
- The history is cleared whenever the scope changes: joining a world, leaving one, switching a
  preset, or walking through a Catacombs doorway. Recorded coordinates belong to one slice's
  coordinate space and cannot be replayed into another.

There is no redo.

---

## Dungeons

A Catacombs room prefab is placed at a different grid cell and a different rotation every run, so
paint made inside one is stored **relative to the room's marker corner with its rotation taken
out**, and re-projected when you walk back in.

Rooms are identified by hashing a column of blocks through the tile centre and looking that hash up
in a room list served by NoammAddons, and oriented by finding the single blue terracotta block
Hypixel leaves on one roof corner. Boss rooms sit at fixed coordinates and need no scanning; they
are keyed `B1`–`B7`, and master mode shares them with the normal floor.

The HUD shows the room in scope. `/paintbrush room` shows the rest. If a room is not recognised, use
**Settings → Dungeons → Re-scan dungeon**.

To author boss-room paint off Hypixel — on a test server that sends no sidebar — use
`/paintbrush dungeon F7 boss`. It is session-only and never persisted, and the HUD turns orange
while it is on so it cannot be mistaken for real detection.

---

## Config layout

Everything lives under `config/ap` (`run/config/ap` in the dev client):

```
config/ap/
  settings.json                  world -> preset bindings, room -> type bindings, colours, brush size
  block-config/<name>.json       positional paint: { "dimensions": {...}, "rooms": {...} }
  block-type-config/<name>.json  flat  { "minecraft:oak_stairs": "minecraft:diamond_block" }
  palette-config/<name>.json     flat  { "minecraft:stone": 70 }
  data/rooms.json                Catacombs room list, fetched from api.noamm.org and cached
  data/version.txt
```

All of it is meant to be opened and edited by hand, so parsing is forgiving: a bad coordinate, an
unknown block or a junk key is logged and skipped rather than failing the load. Positional presets
are written by hand rather than through Gson so the coordinate arrays stay on one line — Gson's
pretty printer would triple the file length of a large preset for no benefit.

A pre-preset `config/austrianpainter` folder is migrated once, on first run. The originals are never
deleted.

---

## Building

```sh
./gradlew build      # jar in build/libs
./gradlew test       # pure-logic tests, no Minecraft bootstrap
./gradlew runClient  # dev client
```

Minecraft 26.x needs **Java 25**, and Loom checks the JVM Gradle itself runs on rather than the Java
toolchain. `gradle.properties` pins `org.gradle.java.home` to one machine's JDK — if yours is
elsewhere, **do not edit that line**; put your own `org.gradle.java.home` in
`~/.gradle/gradle.properties`, which takes precedence over the project file.

Every build prints `The mappings (net.fabricmc:yarn:1.21.11+build.6) were not built for Minecraft
version 26.1.2`. That is expected: Minecraft ships unobfuscated from 26.x on, so nothing is actually
remapped, but Loom refuses to configure without a mappings entry. The yarn version is a placeholder
and nothing more.

`runClient` logs in through DevLogin's device-code flow so it can join online-mode servers. The
first launch prints a `microsoft.com/link` code to the console; credentials are cached in
`~/.devlogin/accounts.json`, never in this repo.

Hot swapping needs the JetBrains Runtime, which carries DCEVM. The workflow that actually benefits
is IntelliJ's generated "Minecraft Client" config run in **debug** mode with its JRE set to that
runtime — then Ctrl+Shift+F9 to push a change in.

---

## Source layout

```
src/main/java/com/maxisch/mixin/client/   three mixins: step sounds, break sound/particles, scroll
src/main/kotlin/com/maxisch/
  client/          entrypoint, keybinds, /paintbrush, key hints
  client/gui/      the paint screen, block picker, YACL settings
  client/gui/tab/  the four tabs
  client/gui/widget/  shared row list and text line
  client/render/   model wrapper, sprite borrowing, face culling, HUD, selection box
  paint/           presets, storage, index, codec, undo, settings, paths
  paint/session/   transient authoring state: brush, area, selection, area scan
  dungeon/         Catacombs scope: scoreboard read, room scan, room data, coordinate transform
```

`PaintStorage` is the facade everything paints through; it delegates to `PaintSession` (what the
rules apply to and when they are written) and `PaintRules` (the rules themselves). `PaintIndex` is
the flattened, immutable snapshot the chunk-build worker threads read — it is swapped whole rather
than mutated, which is why edits are batched per stroke rather than per block.

---

## Credits

The dungeon scanner is a trimmed port of [NoammAddons](https://github.com/Noamm9/NoammAddons)'
`DungeonScanner`/`UniqueRoom` — no map rendering, no secrets, no score tracking, only what
identifying and orienting a room needs. The Catacombs room list is served by NoammAddons.

MIT licensed; see `LICENSE.txt`.
