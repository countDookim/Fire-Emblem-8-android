# HANDOFF

_Updated: 2026-10-25 15:20 UTC (scheduled run, started 14:51)._

## Owner decisions 2026-10-25 (read docs/decisions/0009)
- The app is a 1:1 copy. Modding = extension points only; ship no touch gameplay, QoL flags or alternate
  formulas. Only touch input shipped: on-screen GBA buttons (task M3-15).
- Every ported system adds strategy interfaces to `gbafe.core.mechanics.Mechanics` (vanilla impl only,
  alternatives only in tests). Battle must use `mechanics.battleRolls` for hit/crit/skill rolls, never
  `rng.roll1/roll2` directly.

- Reuse by future FE6/FE7 ports (docs/decisions/0010): packages are now `gbafe.*` (engine, extract) vs
  `fe8.*` (game, app, testing, `gbafe.extract.fe8`); data formats `gbafe.<table>` + `"game": "fe8"`.
  New shared systems go in `gbafe.<system>` modules; FE8-only ones (world map, skirmishes...) in `fe8`.
  Known gap: `ExtractCli` calls the FE8 extractors directly; add `--game` dispatch when touching it.

## NEEDS USER
_(nothing blocked)_

## Status
- This run: M2-09 `MessageCodec` (engine:text; bytes <-> `{Tag}` text, lossless, unknown bytes `{0xNN}`;
  all 3404 messages round-trip), M2-10 fonts (`FontExtractor`, 3 atlases), M2-11 portraits
  (`PortraitsExtractor`, unassembled tile sheets + chibi/mouth/card), M2-12 `data/chapters.json`
  (every struct byte; full cross-check vs decomp `src/data/chapter_settings.json` = 0 mismatches),
  M2-13/14 maps (`MapsExtractor`: 79 `maps/chapter_NN.tmj` + 24 tilesets, map changes as hidden layers
  `change_<id>`). Schemas: `text`, `font`, `portraits`, `chapters` (+ Tiled structure test for `.tmj`).
  All extracted output is committed in the ROM repo (`extracted/`, last 1a23a8b).
- Last CI before this run: ✅ master (de5c6a3). Not yet seen: CI on this run's commits (145e4d9..c469e04).
- Session setup: 94 s. Branch: `master`. No `wip/` branches.
- Progress: 5 / 8352 verified (unchanged: this run was extraction, no game-code ports).

## Next task: M2-17 — map sprites (standing + moving) → PNG + anim JSON
- Standing: `unit_icon_wait_table` 0x088AF880 (0x358 bytes; decomp `src/unit_icon_wait_data.c`,
  `include/unit_icon_data.h` for `UnitIconWait` = {u16 ?, u16 size (UNIT_ICON_SIZE_16x16/16x32/32x32),
  sheet ptr}; check the struct before coding). Sheets are likely LZ77 4bpp, 3 frames stacked; palette is
  the unit faction palette (find it via `src/bmudisp.c` / `SMS_DisplayOne`, e.g. `gPal_MapSprite`).
- Moving: `unit_icon_move_table` 0x089A2E00 (0x3F8 bytes, `struct MuInfo {img, anim}`, `include/mu.h`);
  `anim` is an AP animation script (`src/ap.c`) — export frames + the raw script as JSON for now.
- Files: new `tools/extract/.../fe8/mapsprites/MapSpritesExtractor.kt`, CLI step `mapsprites/`,
  `testing/.../MapSpritesRomTest.kt`, schema + `tools/tests/test_schemas.py` case (pattern of
  `PortraitsExtractor`/`PortraitsRomTest`).
- Done when: counts match the tables (and the class table's SMS ids, `GetClassSMSId`), Eirika/Ephraim
  standing frames checked by eye, schema test passes.
- Decomp source checkout for names/cross-checks: see caveat below (add `data` and `include` to the
  sparse set for these tables).

## Known bugs / caveats
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
