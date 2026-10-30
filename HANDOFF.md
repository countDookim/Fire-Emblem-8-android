# HANDOFF

_Updated: 2026-10-30 07:15 UTC (scheduled run, started ~06:52)._

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
- This run: **M4-11 op anim + title now match mGBA on the device path** (`Fe8BootTest`: from power-on with the
  ram_trace_logo_skip inputs, all 27 lag frames f401..3600 and 239 frames f1110..3600 every 10 match, gaps below).
  - Cause of the divergence: `overran = { hw.modifiedBgs != 0 }` fired on every BG-sync step (op anim ran at half
    speed). Now `GameLoop.overran` returns missed VBlanks (multi-frame), `OpAnim.missedVBlanks` /
    `TitleScreen.missedVBlanks` are set by the loads that overrun on hardware (measured from `gBmSt.main_loop_ended`:
    SetupOpAnimWorldMapfx 3; Ephraim prepare stages 0,1,5; Eirika 1,2; sub_80C8278 0..2; Joshua scroll 2; cast scenes
    timer 1 (+2 for scenes 3,4); sparkles 1..3; Title_SetupMainGraphics 0..2, SetupSpecialEffectGraphics 2,
    LoadDemonKing). The BG-sync heuristic is kept only while the early start UI proc exists.
  - `Fe8Boot.render` runs the active HBlank handler (`HBlankLines.capture`, now allocation-free overload); new per-line
    BLDCNT override (`LineTable.setBlendControl`, renderer uses it). `GameLoop.overrunning` (public).
- Verified ports: 208 / 8352 (unchanged: wiring/timing run).
- Stale remote branches (merged; deleting refused with 403): `wip/M4-09`, `wip/M3-10`, `wip/M4-10`, `wip/M4-10-affine`, `wip/M4-11`.

## Run rules (owner, 2026-10-26)
- Budget **~30 min** (owner, 2026-10-27; a little over is fine to finish a nearly done task). Work until ~minute 23,
  never end early (`tools/run_clock.sh check` must pass before wrap-up; the 14:53 run wrongly stopped at ~17 min): when a task finishes, start the next (on `wip/<task-id>` if it may not finish). Wrap up by ~30.
- Before the final push, append this session's row to `docs/USAGE.md`.
- Run `tools/setup.sh` in the background with a log and poll it. Never fetch full CI job logs (step conclusions
  first, then `tail_lines` <= 80 of the failing job).
- Interleave verifiable ports with infrastructure: a `@VerifiesPort` lands every run or two.

## Next task: the darken gap (now also f1110..1140), then M3-07, M3-15
- `Fe8BootTest.SPLIT_FRAMES` f1110..1140 (asserted <= 80 px) differ only at x125..139, y137..151, below line
  `gOpAnimSt.unk0A` (0x78..0x84) where `OpAnimHBlank2` sets BLDCNT 0xCCF (darken): the same one-step-too-bright
  darken gap as f1170..1210 / f2200..2230 (see Renderer gap below; the split `unk08` is 0xA0, so BG2's map block never
  switches). Fixing darken should zero all of them: then set the limit to 0, drop those FRAME_GAPS and add
  `@VerifiesPort("OpAnimHBlank1")`/`OpAnimHBlank2` in Fe8BootTest.
- Darken lead: only some pixels in those areas differ, so look at which layer/target
  pairs differ (OBJ semi-transparent pixels? BG0 not a target in 0xCCF's top bits 0xF? backdrop). Dump our and mGBA's
  channel values for one differing pixel before trying formulas again. First sample: f1110 (127,149) ours 5-bit
  RGB 16,14,12 vs mGBA 16,15,12 (ours darker there, unlike f1170).
  BLDY 8, BLDALPHA 0x0808 there: every darken formula gives the same result for evy 8 (ceil(c/2)), so the source
  colour or the effect differs (alpha 8/8 vs darken: suspect OBJ semi-transparency / target handling, not the formula).
- Then: **M3-15 touch buttons** (owner 2026-10-30: debugging without a gamepad; before the manual-test APK), manual-test
  APK (M4-11 acceptance), title key paths, M3-07 GPU backend.
- Renderer gap (`FRAME_GAPS` in ProcEngineDifferentialTest and Fe8BootTest): frames with BLDCNT effect 3 (darken, e.g. f1170 BLDY 6,
  f1190 BLDY 10; f2200..2230) come out one 5-bit step brighter than mGBA even from the dump's own state (checked via
  FrameCompareTest). `MgbaColorMath.darken` widened-8-bit, 5-bit and unwidened-8-bit variants all tried: none fits;
  read mGBA 0.10.5 `_darken`/`software-private.h` (maybe darken applies to the blended/OBJ-semi path differently).
  f1180: BG VRAM 0xD800..0xE800 differs (sync timing); f3000 (and f3010 on the device path): title's first frames, sprites one step off.
- Key-press title paths (skip listener -> label 3, A/Start -> `sub_8013F40` fade out) need a key trace on the title.
- Unverified: `Title_RestartProc`, `SkipTitleFx_Loop_KeyListener`, `Title_EndAllProcChildren` path, `FadeCore.darkenEnd`,
  `ColorFadeSetupFromColorToBlack/White`, `ColorFadeSetupFromWhite` (no trace yet).
- M3-07 GPU backend: the app already shows a CPU-rendered frame via GL; v1 = draw `RenderState` with shaders.
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
