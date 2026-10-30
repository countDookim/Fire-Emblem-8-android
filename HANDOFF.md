# HANDOFF

_Updated: 2026-10-30 14:35 UTC (scheduled run, started 14:12). Owner decisions live in docs/PROJECT_BRIEF.md (amendments merged in)._

## NEEDS USER
_(nothing blocked)_

## Status
- This run: docs condensed (owner request: TASKS 21→7 KB, brief 18→9 KB with amendments merged, HANDOFF, CLAUDE,
  USAGE header). **M3-15 done** (manual test pending): `gbafe.input.TouchButtons` (layout, D-pad diagonals,
  multi-touch; `TouchButtonsTest`) + `fe8.app.TouchOverlayView` over `GameSurfaceView`, always on (product feature).
- **Darken gap fixed:** mGBA's `_darken` on 8-bit widened channels rounds red `c - c*evy/16` but green/blue
  `c*(16-evy)/16` (packed-colour subtraction); fit 100% of darkened pixels. `MgbaColorMath.darken` updated; all
  op anim/title frames now pixel-exact except f1180 (`Fe8BootTest` 249 frames, `@VerifiesPort("OpAnimHBlank2")`).
- M4-11: H&S, logos, op anim, title match mGBA on the device path (27 lag frames f401..3600). Overrun = missed
  VBlanks per load (`GameLoop.overran`, `OpAnim/TitleScreen.missedVBlanks`).
- Verified ports: 209 / 8352. Stale merged remote branches (delete refused, 403): `wip/M4-09`, `wip/M3-10`,
  `wip/M4-10`, `wip/M4-10-affine`, `wip/M4-11`.
- Rules: interleave verifiable ports with infrastructure (a `@VerifiesPort` every run or two); keep new
  engine/game code free of `java.*`/`javax.*` (KMP coming, M4-07); battle rolls only via `mechanics.battleRolls`.

## Next task: manual-test APK (M4-11 acceptance), then title key paths, M3-07
- CI run 179 (M3-15): every step green incl. APK build (`:app` compiles), only "Rolling `latest` release" failed
  (likely a race with the next push; log tail uninformative). If it fails again, read that step's log only.
- **APK:** check the last CI run built `fe8-debug.apk` (`:app` compiles only in CI: `TouchOverlayView`,
  `GameSurfaceView(ctx, game, touch)`, `MainActivity` FrameLayout are new). If green, mark M4-11 ready for manual
  test in MANUAL_TEST.md/TASKS.
- **f1180** (last frame gap): BG VRAM 0xD800..0xE800 differs from the dump (sync timing of a BG map copy).
- Title key paths (skip listener -> label 3, A/Start -> `sub_8013F40` fade out) need a key trace on the title
  (new reference run, see How-to). Then M3-07 GPU backend (draw `RenderState` with shaders).
- Unverified (no trace yet): `OpAnimHBlank1` (BG2 map block switch never happens: `unk08` 0xA0),
  `Title_RestartProc`, `SkipTitleFx_Loop_KeyListener`, `Title_EndAllProcChildren` path, `FadeCore.darkenEnd`,
  `ColorFadeSetupFromColorToBlack/White`, `ColorFadeSetupFromWhite`, `sub_80C85FC`/`sub_80C8684`,
  `GameControl_PostIntro` (partial), `sub_8009B64`.
- Follow-ups (not tasks yet): units/`.fe8s` beyond the prologue need the event converter to follow `LOAD1/LOAD2`
  of each `ChapterEventGroup`; keysplit/drum sub-voicegroups not written as `voicegroups/*.json`; renderer: OBJ
  mosaic, mosaic on affine BGs. `MgbaColorMath.alpha` may have the same packed-rounding quirk (check if an alpha
  frame ever differs by one step).

## How-to
- Re-extract: run the CLI (below), `python3 tools/compare_extraction.py <rom repo>/extracted <out>`, copy only
  changed/new files into the ROM repo's `extracted/`, push there (CI fails until you do).
- New reference runs: add to `harness/gen_scripts.py`, run it + `harness/make_reference.sh`; diff old `out/` vs a
  fresh untar of old `dist/reference.tar.xz` (only PROVENANCE.sha1 may differ); `tar -C reference -cJf
  dist/reference.tar.xz out`; update `dist/MANIFEST.sha1`; push; copy into `.assets/reference`. Fetch the ROM
  repo first (checkout can be behind).
- Extractor CLI: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo>
  --decomp <fireemblem8u> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`.
- Decomp source isn't in the assets: `git clone --filter=blob:none --sparse
  https://github.com/FireEmblemUniverse/fireemblem8u`, checkout `04d1edb…`, `git sparse-checkout set --no-cone
  include src texts asm data sound`. Tests hard-code the names/addresses they need.
- Test ROM addresses for op anim/title are inline in the tests (from fireemblem8.nm.txt). ROM data symbols mostly
  lack sizes: use the next symbol's address (as `Lz77RomTest`).

## Known bugs / caveats
- Fresh session: local `master` can be an old unrelated history and `origin/master` stale: `git fetch` first, then
  `git branch -m master stale-master && git checkout -B master origin/master`.
- Pushes race CI's `ci: update progress [skip ci]` commit: `git fetch && git rebase origin/master`, push again.
- CI's Dokka fails on ANY warning (every public declaration incl. constructor `val`s needs KDoc): run
  `./gradlew dokkaGenerate -Pfe8.docs=true` before pushing public API (in the background: can exceed 2 min cold).
- Always `source .assets.env` before Gradle (else Maven Central 429s; `tools/maven_mirror.py` retries 404/429).
- `tools/progress.py` credits a `@VerifiesPort` test to the LAST `class` declared before it: keep helper classes
  below the test class.
- Allocation tests can flake (200 B once in 1000 frames): use `AllocationMeter.measureMin`.
- `FrameCompareTest.applyHBlank` knows only `HBlank_BgVerticalScroll`; dumped RAM is one game step ahead of the
  dumped frame (the test un-flips the menu scroll buffer).
- `gh release view/download <tag>` (GraphQL) is refused; use `gh api repos/.../releases/...` (REST).
- `cursor_hand.png` colours are a stand-in (`paletteSource: caller`). `effectiveAgainst` in items.json holds class
  numbers; classes inline shared terrain tables (decide with the data loader).
- `.fe8s` v0 prints args as raw numbers; symbolic constants and a parser are M5.
- `RamSymbols[name]` throws on ambiguous static names. `GameSurfaceView` is device-untested.
- Dokka's Kotlin types are only on the docs classpath: keep `configureDokkaLint()` using Groovy property access.
