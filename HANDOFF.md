# HANDOFF

_Updated: 2026-10-29 07:20 UTC (scheduled run, started ~06:50)._

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
- This run: **M4-10 (a)..(d) done**: whole `gProcScr_TitleScreen` ported in `fe8.game.title.TitleScreen` (intro fx, logo zoom,
  light explosion, `DrawTitleSprites`, `Title_IDLE`, real skip listener, `Title_RestartProc`, fades) and verified by
  `TitleScreenDifferentialTest` (mode 1 to the idle title, f3600; mode 0 fade-in on new trace `ram_trace_title_fade`,
  f690..f800, which also checks `gFadeComponents`). New `gbafe.render.ColorFade` + `FadeCore` (`StartFadeCore`,
  `ColorFadeTick`, `sub_80141B0` = `darkenEnd`). `blendPalette` (sub_80C69B0) is now top-level in `fe8.game.opanim`.
  `GameControl` takes an optional `titleScreen` factory and starts the real title (stand-in only when null); the main
  `ProcEngineDifferentialTest` builds it via `titleScreenFor(...)` (helper at the end of TitleScreenDifferentialTest.kt).
- ROM repo: `ram_trace_title_fade` added (release rebuild by its workflow; CI needs it). No dumps f700..f702 there
  (START held): the test syncs the proc from the first settled dump.
- Verified ports: 179 -> **204 / 8352** (local `progress.py` after a full `jvmTest`). `Title_IDLE` stays unverified until a key trace
  covers its A/Start branch. Local full `jvmTest` + Dokka green before the final push.
- Stale remote branches (merged; deleting refused with 403): `wip/M4-09`, `wip/M3-10`, `wip/M4-10`, `wip/M4-10-affine`.

## Run rules (owner, 2026-10-26)
- Budget **~30 min** (owner, 2026-10-27; a little over is fine to finish a nearly done task). Work until ~minute 23,
  never end early (`tools/run_clock.sh check` must pass before wrap-up; the 14:53 run wrongly stopped at ~17 min): when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~30.
- Before the final push, append this session's row to `docs/USAGE.md`.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: M4-10 (e) title frame-compare, then M3-07 GPU backend, M3-15, M4-11
- M4-10 (e): frame-compare the idle title vs `boot_to_title` (f900/f1200 full dumps) or `ram_trace_title1` f3600 VRAM/frame:
  needs BG map sync to VRAM (TitleScreen writes map buffers only; check how OpAnim tests sync) and OBJ rendering of the
  title sprites. Key paths (skip listener -> label 3, A/Start -> `sub_8013F40` fade out) need a key trace on the title.
- Unverified: `Title_RestartProc`, `SkipTitleFx_Loop_KeyListener`, `Title_EndAllProcChildren` path, `FadeCore.darkenEnd`,
  `ColorFadeSetupFromColorToBlack/White`, `ColorFadeSetupFromWhite` (no trace yet).
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
