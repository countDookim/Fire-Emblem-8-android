# HANDOFF

_Updated: 2026-10-28 23:15 UTC (scheduled run, started 22:51)._

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

## Owner decision 2026-10-28: no ROM needed before the public release
- Debug/test APKs may bundle the ROM repo's extracted assets (amendment; CLAUDE.md rule 4; 0011 amended). Goal now:
  **first manual-test APK** booting to the title. Order: M4-08, M3-07, M4-09, M3-10, M3-15, M4-10, M4-11 (TASKS.md).
  M4-03/04/06/07 (ROM import, GBA library, KMP) come after that APK.

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
- This run: **M4-08 done**: `OpAnim` runs the whole `ProcScr_OpAnim` (face screens 5..12 via `LATER_FACES` +
  `faces4In/6In/7In/ColumnIn/10In/12In`, `fadeRingOut`, `fadeToWhite`, `endCutscene`; `ProcEngine.endAllChildren`,
  `LcdControl.setWin1Box`); `ProcEngineDifferentialTest` uses run5 (`ram_trace_opanim4`) and runs to f3000.
  `GameControl` script mirrored through `POST_TITLE_IDLE` (indices match C; `postIntro` partial, `postClassReel`;
  a title **stand-in** proc is started at label 24).
- **M4-10 started**: `fe8.game.title.TitleScreen` (start, `Title_Init`, `SetupMainGraphics`, `SetupSpecialEffectGraphics`,
  `sub_80C5A44`, `Title_Loop_DrawRotatingBlueLights`; skip listener and the rest are stand-ins) verified by
  `TitleScreenDifferentialTest` (state from f2909, mid-step dumps skipped like the main test). `gbafe.render.BgAffine.set`
  = mGBA's float `BgAffineSet`, matches the trace exactly. New reference `ram_trace_title1` (f2990..3600) pushed to the
  ROM repo (release rebuilt by its workflow; not used by tests yet).
- **M4-09 done** (CI 137 green): debug APK bundles `assets/extracted/`, `fe8.app.AssetLayer`, guard on the release APK.
  **M3-10 done** (CI 138 green, manual test pending): `fe8.app.HardwareKeySource`. MANUAL_TEST.md updated.
- Verified ports: 157 (README) -> **179 / 8352** (local `progress.py` after a full `jvmTest`; it only counts
  tests whose results exist). Local full `jvmTest` + Dokka green before the final push.
- Stale remote branches (merged; deleting refused with 403): `wip/M4-09`, `wip/M3-10`, `wip/M4-10`, `wip/M4-10-affine`.

## Run rules (owner, 2026-10-26)
- Budget **~30 min** (owner, 2026-10-27; a little over is fine to finish a nearly done task). Work until ~minute 23,
  never end early (`tools/run_clock.sh check` must pass before wrap-up; the 14:53 run wrongly stopped at ~17 min): when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~30.
- Before the final push, append this session's row to `docs/USAGE.md`.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: M4-10 title screen (then M3-07 GPU backend, M3-15, M4-11)
- Continue `gProcScr_TitleScreen` from idx 16 (`sub_80C5BD4`: BG2 fill, mode 0, blend, `CpuFastFill` BG pal 0xE;
  then `Title_Loop_DrawRedBlueOrbs`, dragon flash, demon king BG2, logo zoom, light explosion, `sub_80C62DC`, `Title_IDLE`,
  `gProcScr_DrawTitleSprites`). Extend `TitleScreenDifferentialTest` to `ram_trace_title1` (f2990..3600; add its frames
  past f3000) and raise its break index. Port the real `gProcScr_SkipTitleFxKeyListener` (needs `KeyStatus`).
- Then wire `TitleScreen` into `GameControl.startTitleScreen` (replace the stand-in) and give the main test the title gfx.
- M3-07 GPU backend: the app already shows a CPU-rendered frame via GL; v1 = draw `RenderState` with shaders.
- M4-11 boot wiring needs `Fe8Game` to build OpAnim/Title/EarlyStartUi from VFS assets (`AssetLayer`).
- Unverified leftovers: `OpAnimHBlank1/2`, `sub_80C85FC`/`sub_80C8684`, `GameControl_PostIntro` (partial), `sub_8009B64`.
- Test ROM addresses for op anim / title graphics are inline in the tests (from fireemblem8.nm.txt).
- Re-extracting after an extractor change: run the CLI (see Known bugs), compare with `python3 tools/compare_extraction.py
  <rom repo>/extracted <out>`, copy only changed/new files into the ROM repo's `extracted/`, push there; CI fails until you do.
- New reference runs: add to `harness/gen_scripts.py`, run it + `harness/make_reference.sh` (seconds), diff old `out/` vs a
  fresh untar of the old `dist/reference.tar.xz` (only PROVENANCE.sha1 may differ), `tar -C reference -cJf
  dist/reference.tar.xz out` (~15 s, now ~12 MB), update the sha1 in `dist/MANIFEST.sha1`, push. Also copy into `.assets/reference` locally.
  Fetch the ROM repo before committing there (the session checkout can be behind origin).
- M3-12..15 audio/overlay. Open renderer items: OBJ mosaic, mosaic on affine BGs.
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
- Pushes to master often race CI's `ci: update progress [skip ci]` commit: `git fetch && git rebase origin/master`, push again.
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
