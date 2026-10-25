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
  M2-17 standing map sprites, M2-13/14 maps (`MapsExtractor`: 79 `maps/chapter_NN.tmj` + 24 tilesets, map changes as hidden layers
  `change_<id>`). Schemas: `text`, `font`, `portraits`, `chapters` (+ Tiled structure test for `.tmj`).
  All extracted output is committed in the ROM repo (`extracted/`, last 1a23a8b).
- CI: ✅ through 2e56261 (M2-10); later runs were cancelled by newer pushes — check the run for the final commit first.
- Session setup: 94 s. Branch: `master`. No `wip/` branches.
- Progress: 5 / 8352 verified (unchanged: this run was extraction, no game-code ports).

## Next task: M2-17 (rest) — moving map sprites → PNG + anim JSON
- Standing sprites are done this run (`MapSpritesExtractor`, `mapsprites/standing/NNN.png` + `mapsprites.json`,
  107 entries; 4bpp LZ77, 3 frames stacked, 2 tiles wide for 16x16/16x32, 4 for 32x32; palette
  `gPal_MapSprite` 0x0859EE20, 4 factions × 16 colors).
- Moving: `unit_icon_move_table` 0x089A2E00 (0x3F8 bytes, `struct MuInfo {img, anim}`, decomp
  `include/mu.h`; names in `include/unit_icon_pointer.h`). `img` is a sheet (check compression/size like
  the standing ones), `anim` an AP animation script (`src/ap.c`: frame data + per-mode scripts). Export
  the sheet PNG + the raw script decoded to JSON (frames list, per-direction scripts) under
  `mapsprites/moving/`; extend `MapSpritesExtractor.Output`/`mapsprites.schema.json` with a `moving` array.
- Done when: `MapSpritesRomTest` checks moving count (0x3F8/8 = 127 = class count) and the SMS ids used
  by the class table (`classes.json` field holding `SMSId`) are all < 107; Eirika's walk frames checked by
  eye; schema test passes. Then mark M2-17 done in `docs/TASKS.md`.
- Decomp source checkout for names/cross-checks: see caveat below.

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
