# HANDOFF

_Updated: 2026-10-26 07:15 UTC (scheduled run, started 06:51)._

## Owner decisions 2026-10-25 (read docs/decisions/0009)
- The app is a 1:1 copy. Modding = extension points only; ship no touch gameplay, QoL flags or alternate
  formulas. Only touch input shipped: on-screen GBA buttons (task M3-15).
- Every ported system adds strategy interfaces to `gbafe.core.mechanics.Mechanics` (vanilla impl only,
  alternatives only in tests). Battle must use `mechanics.battleRolls` for hit/crit/skill rolls, never
  `rng.roll1/roll2` directly.

- Reuse by future FE6/FE7 ports (docs/decisions/0010): packages are now `gbafe.*` (engine, extract) vs
  `fe8.*` (game, app, testing, `gbafe.extract.fe8`); data formats `gbafe.<table>` + `"game": "fe8"`.
  New shared systems go in `gbafe.<system>` modules; FE8-only ones (world map, skirmishes...) in `fe8`.
  `ExtractCli` now dispatches on `--game` (default fe8) to `gbafe.extract.fe8.runFe8Steps` (Fe8Steps.kt).

## NEEDS USER
_(nothing blocked)_

## Status
- This run: M2-20 done (cursors: `CursorsExtractor` → `ui/cursors.png` 18×4-tile misc OBJ UI sheet, `cursor_hand.png`,
  `cursors.json` with both palettes + map-cursor OAM lists), M2-21 done (ROM-repo `publish-assets` packs `extracted/`
  as `extracted.tar.xz`; `setup.sh` fetches it into `$FE8_ASSETS/extracted` via REST; code CI step
  `tools/ci_check_extraction.sh` re-runs the extractor and fails on drift), M3-01 done (ADR 0008 accepted:
  `LineTable` for HBlank effects; indexed vs RGBA texture replacements), M3-02 done (`gbafe.render.RenderState`).
  **Milestone 2 is complete.** ROM repo `extracted/` last 6b6e885; release `assets-9db5d11`.
- CI: green on ce469f7 (incl. the new extraction step, 23 s; its log was not read, so "ran" vs "skipped" is unconfirmed).
- Session setup: ~6 s (assets cached). Branch: `master`. No `wip/` branches.
- Progress: 5 / 8352 verified (unchanged: no game-code ports this run).

## Next task: M3-03 — software compositor, text-mode BG layers + priorities
- New `gbafe.render.SoftwareRenderer(state: RenderState, sheets: TileSheets, fb: FrameBuffer)` (`Renderer`). Needs a
  tile-sheet store first: `TileSheets` = sheet id → 4bpp/8bpp tile bytes (`ByteArray`, GBA tile format, decode with
  `gbafe.data.Tiles` semantics) — keep it in `engine:render` with no `fe8` dependency.
- Per scanline (apply `state.lines` overrides when `lines.active`): for each enabled text-mode layer compute the
  map entry at ((x+scrollX) mod W, (y+scrollY) mod H) with 32/64-tile wrap, flips, 4bpp palette bank; colour 0 =
  transparent. Pick the front-most opaque pixel by (priority, BG number); fall back to backdrop `palettes.bg[0]`.
  Output via `FrameBuffer.bgr555ToArgb`. Must not allocate (add to `RenderStateTest`-style allocation test).
- Tests: synthetic scenes (two layers, priority swap, scroll wrap at 512 px, flips) with hand-computed pixels.
  Real-frame compare against mGBA is M3-06.
- Then M3-04 (sprites), M3-05 (blend/windows/mosaic + LineTable effects).
- Follow-ups noted, not tasks yet: unit placement / `.fe8s` for chapters other than the prologue need the event
  converter to follow `LOAD1/LOAD2` targets from each chapter's `ChapterEventGroup` (`include/chapterdata.h`);
  keysplit/drum sub-voicegroups are scanned for samples but not written as their own `voicegroups/*.json`.
- Re-extracting after an extractor change: run the CLI (see the M2-21 lines in Known bugs), copy the output into the
  ROM repo's `extracted/`, commit + push there (publish-assets makes a new release); CI fails until you do.

## Known bugs / caveats
- Extractor CLI: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo> --decomp
  <fireemblem8u checkout> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`.
- `gh release view/download <tag>` (GraphQL) is refused in Claude sessions; use `gh api repos/.../releases/...`
  (REST), as `setup.sh` 3b does. `gh release download` without a tag still works.
- Hand cursor colours in `cursor_hand.png` are a stand-in: the game draws it with whatever OBJ palette 0 the calling
  screen loaded (`paletteSource: caller` in cursors.json).
- CI's Dokka step fails on ANY warning (every public declaration, incl. constructor `val`s, needs KDoc). Before
  pushing new public API run `./gradlew dokkaGenerate -Pfe8.docs=true` (after `tools/setup.sh` + `source .assets.env`).
- Decomp checkout for this work: sparse set must include `texts` (face names in messages.json) and `sound`
  (song/sample ground truth): `git sparse-checkout set --no-cone include src texts asm data sound`.
- `.fe8s` v0 prints args as raw numbers (u16, or u32 for ROM pointers/`set_slot`); symbolic constants and a
  real parser are M5. `move_unit_modify` etc. = the macro's 1-bit `modify` parameter baked into the sub-command.
- The decomp **source** is not in the session assets (only `decomp/fireemblem8.{map,nm.txt}` in the ROM
  repo). Extraction with names needs a checkout: `git clone --filter=blob:none --sparse
  https://github.com/FireEmblemUniverse/fireemblem8u` then `git checkout 04d1edb…` + `git sparse-checkout
  set --no-cone include src texts asm data` (~10 s, works through the session proxy). Tests hard-code the few
  names/addresses they need, so CI does not need it.
- Classes inline copies of shared terrain tables; terrains.json also lists them by symbol. Decide with the
  data loader (later M2) whether classes should reference tables by name.
- `effectiveAgainst` in items.json holds class numbers until items and classes are cross-linked.
- Always `source .assets.env` in the same shell before Gradle: without `FE8_MAVEN_MIRROR` set, Dokka
  resolution goes straight to Maven Central and fails with 429s.
- Long Gradle runs (Dokka with `-Pfe8.docs=true`) can exceed the 2-min tool timeout on a cold daemon: run
  them in the background, or repeat (second run is warm).
- Maven Central through the session proxy returns spurious 404/429; `tools/setup.sh` starts
  `tools/maven_mirror.py` (retries). CI is unaffected.
- `GameSurfaceView` is untested on a real device (no gameplay yet; manual test only).
- Static C names can repeat across files; `RamSymbols[name]` throws if ambiguous — use unique names.
- ROM data symbols in `decomp/fireemblem8.nm.txt` (ROM repo) mostly have no size (asm labels): use the
  next symbol's address as the span, as `Lz77RomTest` does.
- Dokka's Kotlin types are not on the build classpath outside docs builds; `configureDokkaLint()` in
  `build.gradle.kts` configures it via Groovy property access — keep it that way.
