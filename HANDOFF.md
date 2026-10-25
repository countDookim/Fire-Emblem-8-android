# HANDOFF

_Updated: 2026-10-25 23:20 UTC (scheduled run, started 22:51)._

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
- This run: M2-17 done (moving map sprites: `moving/NNN.png` 32-px-wide sheets + AP frames/anims JSON),
  M2-16 done (`UnitsExtractor`: prologue's 9 `UnitDef_Event_Prologue*` arrays → hidden object layers in
  `chapter_00.tmj`), M2-15 done (`.fe8s` converter v0: `tools/gen_fe8_event_commands.py` → `Fe8EventCommands`,
  `EventScriptConverter`; `scripts/prologue.fe8s`, all 32 scripts exact size, 0 `raw`), M2-18 + M2-19 done
  (`gbafe.extract.m4a.M4aSong`: 1000 song entries → 594 MIDI identical to all 589 decomp `.mid`; 84 voicegroups;
  439 WAV identical to the decomp `.aif`), M2-20 partial (icons + window frames), `--game` CLI dispatch.
  New schemas: `songs`, `voicegroup`, `samples` (+ `moving` in `mapsprites`). ROM repo `extracted/` last ff16883.
- CI: ✅ through 2c702f7 (start of run); this run's pushes not yet checked — check the run for the final commit first.
- Session setup: ~6 s (assets cached). Branch: `master`. No `wip/` branches.
- Progress: 5 / 8352 verified (unchanged: extraction only, no game-code ports).

## Next task: M2-20 (rest) — UI cursors → PNG + JSON, then M2-21
- Map cursor + misc OBJ UI: `LoadObjUIGfx` (decomp `src/bm.c:570`): `gGfx_MiscUiGraphics` LZ77 → copied as
  a 2D block 0x12 tiles wide × 4 rows (`Copy2dChr`), palettes `gPal_MiscUiGraphics` (2 × 16, OBJ pal 0x10).
  Decode as a 18×4-tile sheet. Frames: `sMapCursorSpriteLut` (`src/bm.c` `PutMapCursor`) = OAM data lists;
  export them raw like the AP frames in `MapSpritesExtractor` (u16 count + attrs).
- Hand cursor: `gImg_UiCursorHandTop`/`Bottom` (LZ77, `src/cursor_hand.c:171`, OBJ VRAM 0x060100C0 / 0x060104C0).
- Output `ui/cursors.png` (+ hand) and `ui/cursors.json`; add to `IconsRomTest` (rename to `UiRomTest` if it
  grows) with symbol addresses from `decomp/fireemblem8.nm.txt`; check by eye; mark M2-20 done in TASKS.
- Then M2-21 (extraction in ROM-repo CI + release tarball fetched by `setup.sh`).
- Follow-ups noted, not tasks yet: unit placement / `.fe8s` for chapters other than the prologue need the event
  converter to follow `LOAD1/LOAD2` targets from each chapter's `ChapterEventGroup` (`include/chapterdata.h`);
  keysplit/drum sub-voicegroups are scanned for samples but not written as their own `voicegroups/*.json`.

## Known bugs / caveats
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
