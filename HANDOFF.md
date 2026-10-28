# HANDOFF

_Updated: 2026-10-28 07:15 UTC (scheduled run, started 06:51)._

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

## Owner decision 2026-10-27 (read docs/decisions/0011)
- The player supplies the ROM: the APK ships **no game content**; first launch imports the player's `.gba`, checks
  its SHA1, extracts on device into the VFS `base` layer. Tasks M4-03 (extraction library + offsets manifest, no
  decomp at runtime), M4-04 (import UI), M4-05 (CI guard). Do these before the first manual-test APK that shows
  real game screens (M3-07 may use test-only assets until then, never bundled in release builds).

- Owner request 2026-10-27: a game-neutral **GBA port library** (`:gba:*`, below `gbafe`) so future GBA ports start
  with tools, rendering, input, audio, VFS and the test kit ready. Task M4-06 (plan and module list there); do it
  before or with M4-03. Not started (a first attempt was reverted at the owner's request: to-do only for now).
- Owner request 2026-10-27: build with **Kotlin Multiplatform** (task M4-07), targets **jvm + android only** for now
  (owner, 2026-10-27; other platforms later on request). Until it lands, keep new engine/game code free of `java.*`/`javax.*` (only `engine/vfs` uses `java.io` today).

## NEEDS USER
_(nothing blocked)_

## Status
- This run (M4-08 op anim, f843..f1100): `OpAnim` now runs script idx 10..23: `OpAnimPreparefxEphraim`, `NewProc08AA6D04`
  (tree 0), Ephraim fly-in + `ProcScr_OpAnimMergeBG`, split screen `sub_80C7900` (+ `OpAnimHBlank1/2`, unverified: no
  frame trace), split line, merge shadow, name, exit, `OpAnimPreparefxEirika`, Eirika fly-in, `sub_80C8100/8184/8214`;
  `sub_80C8278` + `OpAnim1_UpdateScrollOneLine` ported but unverified (trace ends at its step 28 of 0x38; VRAM not dumped).
  New game-neutral pieces: `gbafe.core.math.Interpolate`, `gbafe.render.SpriteLayers` (ctc.c `ClearSprites/PutSprite/
  PutSpriteExt/PushSpriteLayerObjects`; OAM push not ported), `LcdControl` window macros (`setWinEnable`, `setWin0Box`,
  `setWin0/1Layers`, `setWOutLayers`, `setWincntBit`). `GameLoop.onMain` now clears/pushes `sprites` (push = no-op).
  - `ProcEngineDifferentialTest`: lag rule is now `gBmSt.main_loop_ended == 0` in the previous dump (skip the step) and
    no compare on a dump with it 0 (mid-step); runs tree 0 every frame; checks `gGenericBuffer`, BG0..3 map buffers,
    `gOpAnimSt`, unk30..36 (after the step that clears them: slot leftovers), merge BG proc, `sHBlankHandler1`,
    wincnt/window boxes, sprite layer chains (`sSpriteLayers` heads 0 and 13).
- Verified ports: 79 -> ~**105 / 8352** (CI's progress commit has the exact count).
- CI: this run's pushes all superseded/cancelled except the last (not checked at wrap-up). Local full `jvmTest` + Dokka
  green before the final push. Session setup: ~120 s.
- Owner note: the session's auto-mode refused `git push --delete` of `wip/m4-08-opanim` (merged into master now; delete
  it by hand or leave it).

## Run rules (owner, 2026-10-26)
- Budget **~30 min** (owner, 2026-10-27; a little over is fine to finish a nearly done task). Work until ~minute 23,
  never end early (`tools/run_clock.sh check` must pass before wrap-up; the 14:53 run wrongly stopped at ~17 min): when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~30.
- Before the final push, append this session's row to `docs/USAGE.md`.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: M4-08 op anim (continue: needs a longer trace) or M3-07 GPU backend
- `ram_trace_opanim` ends at f1100 inside `sub_80C8278`: add a reference run f1090..~f1600 (see "New reference runs"
  below; same inputs) to verify `sub_80C8278` and continue with `sub_80C835C` onwards (data/opanim.s lists the script).
  A VRAM dump (or a frame compare) would also cover `OpAnim1_UpdateScrollOneLine` and the HBlank handlers.
- Test ROM addresses for op anim graphics are inline in `ProcEngineDifferentialTest` (from fireemblem8.nm.txt).
- Boot driver: `GameLoop` + `GameControl` exist (OpAnim's `sprites` must be the loop's when wired); still missing AgbMain init pieces, `SoftResetIfKeyComboPressed` hookup,
  `IsSoftwareReset` / `SetSoftwareResetFlag`. Assets for `EarlyStartUi.Gfx`/`OpAnim.Gfx` on device: from the ROM import (M4-03).
- Proc helpers `Proc_ForAll/ForEach/*Marked`, `SetMark/SetEndCb/SetRepeatCb` exist but are unverified (no trace uses them yet).
- M3-07 GPU backend (`:app`, manual test): can now show H&S and both logos from `ui/*` (see `IntroLogosScreenTest`).
- `gSinLookup` loader exists (`TrigTable`), but `SaveDraw` still takes a `ShortArray` and the app does not load it yet.
- Re-extracting after an extractor change: run the CLI (see Known bugs), compare with `python3 tools/compare_extraction.py
  <rom repo>/extracted <out>`, copy only changed/new files into the ROM repo's `extracted/`, push there; CI fails until you do.
- New reference runs: add to `harness/gen_scripts.py`, run it + `harness/make_reference.sh` (seconds), diff old `out/` vs a
  fresh untar of the old `dist/reference.tar.xz` (only PROVENANCE.sha1 may differ), `tar -C reference -cJf
  dist/reference.tar.xz out`, update the sha1 in `dist/MANIFEST.sha1`, push. Also untar into `.assets/reference` locally.
  Fetch the ROM repo before committing there (the session checkout can be behind origin).
- M3-10 Android ActionSources, M3-12..15 audio/overlay. Open renderer items: OBJ mosaic, mosaic on affine BGs.
- Follow-ups noted, not tasks yet: unit placement / `.fe8s` for chapters other than the prologue need the event
  converter to follow `LOAD1/LOAD2` targets from each chapter's `ChapterEventGroup` (`include/chapterdata.h`);
  keysplit/drum sub-voicegroups are scanned for samples but not written as their own `voicegroups/*.json`.

## Known bugs / caveats
- Frame compares: `FrameCompareTest.applyHBlank` only knows `HBlank_BgVerticalScroll` and fails on any other handler;
  dumped RAM is one game step ahead of the dumped frame (the menu already flipped its scroll buffer: the test un-flips).
- `RenderStateTest.filling a frame allocates nothing` flaked (200 bytes once in 1000 frames, locally and in CI run
  73): now uses `AllocationMeter.measureMin` (min over 3 rounds; a real per-frame allocation still fails). Use it
  for other allocation tests if they flake.
- `tools/progress.py` attributes a `@VerifiesPort` test to the LAST `class` declared before it in the file: keep
  helper classes below the test class (or top-level after it), or the port is not counted.
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
