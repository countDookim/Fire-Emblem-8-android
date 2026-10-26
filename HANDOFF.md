# HANDOFF

_Updated: 2026-10-26 15:25 UTC (scheduled run, started 14:51)._

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
- This run: **M3-09** done (`gbafe.input.KeyStatus`: `_UpdateKeyStatus`, `UpdateKeyStatus`, `ResetKeyStatus`, per-frame
  byte-identical vs mGBA; new ROM-repo reference run `key_status_title`), **M3-03/04/05** done
  (`gbafe.render.SoftwareRenderer` + `TileSheets`: text + affine BGs, sprites incl. affine/1D/2D, windows, blending,
  semi-transparent OBJ, BG mosaic, LineTable), **M3-06** part (`fe8.testing.HardwareScene`: mGBA VRAM/PAL/OAM/IO
  dump → RenderState; `FrameCompareTest`: health & safety, logos, intro, title frames pixel-exact vs mGBA),
  **M3-16** (new) done (`gbafe.render.LcdControl` = `gLCDControlBuffer` + `FlushLCDControl`, `applyTo(RenderState)`),
  **M3-08** done (`gbafe.vfs.TextureOverrides`), **M3-11** done (`gbafe.input.UiElements`).
- ROM repo: `2ae5423` (key_status_title), `65d04f4` (menu_screens: main menu / mode select / save-slot select, full
  dumps). Release `assets-65d04f4` should be published by its workflow (check; setup.sh takes the latest).
- CI: green through f929282 (progress commit after it shows 9 / 8352 verified); later pushes not checked yet.
- Session setup: ~153 s cold (Gradle warm-up). Branch: `master`. No `wip/` branches.
- Progress: 9 / 8352 verified (+4 this run: 3 key-status functions, `FlushLCDControl`).

## Run rules (owner, 2026-10-26)
- Budget **~45 min** (guideline; a little over is fine to finish a nearly done task). Work until ~minute 38, never end
  early: when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~45.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: M3-06 rest (menu frames + HBlank), then a verifiable port, then M3-07
- `menu_screens` frames (m0962/m1024/m1086/m1087) do NOT match yet (12k–24k px): UI windows/text match, the rune
  background (BG2 over BG3, BLDCNT 0x0844, EVA 6/EVB 16) differs everywhere, not a scroll offset (probed ±2 px).
  Probes done (end of run, code not kept): not dump timing (swapping VRAM/PAL/IO between consecutive dumps m1086/m1087
  changes nothing), not BG0/BG1 (disabling them changes nothing), the 128 OAM entries matter a lot (no OBJ: 35k
  diffs), disabling BG2, BG3 or the blend each gives 16.7k. Ours is darker in the background (e.g. expected 5-bit
  (13,14,13) vs ours (11,12,12)). Suspects: OBJ/BG priority or semi-transparent OBJ blending rules (save menu draws
  sparkle sprites, `src/savedraw.c` `sub_80AB56C`), or an HBlank effect. Compare rows of the diff PNG, then check
  GBATEK/mGBA rules for OBJ-vs-BG blending (`build/frame-diff/*.png` after adding the frames to the test list). Dump timing note: `io.bin` holds the
  registers used to draw the dumped frame (the VBlank flush of the new values has not run yet; RAM is one step
  ahead), see `LcdControlDifferentialTest`.
- Then M3-06's remaining acceptance: build the health & safety screen from **extracted assets** (not VRAM) and
  frame-compare it.
- Verifiable port candidates (interleave rule): `hardware.c` palette helpers (`ApplyColorAddition_ClampMax/Min`,
  `ColorFadeSetup*`, `EnablePaletteSync`), `BG_SetPosition`/`BG_SetPriority`-style setters on `LcdControl`, verified
  via RAM traces. A gameplay reference run (new game → prologue map) would also exercise scroll/window registers
  (LcdControl test notes they are all zero in current dumps).
- M3-07 GPU backend (`:app`, manual test), M3-10 Android ActionSources, M3-12..15 audio/overlay.
- Open renderer items: OBJ mosaic, mosaic on affine BGs (no reference frame yet).
- Follow-ups noted, not tasks yet: unit placement / `.fe8s` for chapters other than the prologue need the event
  converter to follow `LOAD1/LOAD2` targets from each chapter's `ChapterEventGroup` (`include/chapterdata.h`);
  keysplit/drum sub-voicegroups are scanned for samples but not written as their own `voicegroups/*.json`.
- Re-extracting after an extractor change: run the CLI (see the M2-21 lines in Known bugs), copy the output into the
  ROM repo's `extracted/`, commit + push there (publish-assets makes a new release); CI fails until you do.
- New reference runs: add to the ROM repo's `harness/gen_scripts.py`, run it + `harness/make_reference.sh` (seconds;
  `bin/mgba-harness` is committed), check old runs are byte-identical, `tar -C reference -cJf dist/reference.tar.xz
  out`, update the sha1 in `dist/MANIFEST.sha1`, push (publish workflow releases it). Quick screen exploration: run
  the harness on a scratch script and convert `*.frame.rgb` (240×160 RGB888) to PNG to look at it.

## Known bugs / caveats
- Local `master` in a fresh session can be an old unrelated history (seen this run): the checkout came up detached
  at origin/master; use `git checkout -B master origin/master` style recovery (rename the stale branch first).
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
