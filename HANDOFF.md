# HANDOFF

_Updated 2026-11-04 (owner-PC WSL session, night). Owner decisions: docs/PROJECT_BRIEF.md. History: git log._

## NEEDS USER
_(nothing blocked)_

## Next task: M5-02 (ROM repo harness: `dumpsram`, `--sram`)
M5 is planned in docs/TASKS.md (M5-01..59; facts, addresses, frames: docs/plans/M5-notes.md); M5-01 (ADR 0001
amendment) is done. M5-02..08 (ROM repo: harness commands, NEWGAME reference runs, publish) unlock the
trace-verified ports. If the harness rebuild is blocked, do ROM-only tasks instead: M5-10..15, M5-18, M5-21,
M5-22, M5-28. New code goes in `commonMain` (no `java.*`, `System.*`, `String.format`).

## State
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Sound: m4a sequencer and DirectSound mixer byte-exact vs per-frame RAM traces (`M4aNoteTest`, 3145 frames);
  `CgbSound` verified; `PsgSynth` (not a port, GBATEK/mGBA behaviour) RMS 1249 vs mGBA 1200.
- Owner device tests passed: boot to title, touch buttons, gamepad (2026-10-30); GPU backend, audio
  (2026-11-01); ROM import ~4 s, boots from it, PSG sound correct by ear (2026-11-04).
- Verified ports: 260 / 8352 (`tools/progress.py`).
- Layout: game-neutral `:gba:*` (ADR 0012), FE-shared `gbafe.*` in `:engine:*`, FE8-only `fe8.*` in `:game`
  and `:app`. KMP (ADR 0013): all of them except `:gba:testkit`, `:testing`, `:tools:extract` (plain JVM);
  android target only in CI (`com.android.kotlin.multiplatform.library`; CI log shows `compileAndroidMain`).
- Owner to smoke-test the first APK built on the KMP android variants (MANUAL_TEST "Newly testable").

## Open leads (low priority unless the owner reports a problem)
- Slow app open (owner 2026-10-30): likely fixed (assets counted on `Dispatchers.IO`), not confirmed. If it is
  reported again, measure `MainActivity.onCreate`/Compose init and the first Play frame (`bundledBootRom`).
- PSG: 6 of 857 quarter frames differ from mGBA (`M4aNoteTest` prints them: ch1 f953/f991/f1009, wave
  f980). These are note-specific envelope steps near a quarter boundary, not a global phase error
  (`SEQ_OFFSET_CYCLES` sweep: 68198 is best).
- f1180: BG VRAM 0xD800..0xE800 differs from the dump (sync timing of a BG map copy).
- Unverified (no trace): `OpAnimHBlank1`, `ColorFadeSetupFrom{ColorToWhite,White}`, `sub_80C85FC`,
  `sub_80C8684`, `GameControl_PostIntro` (partial), `sub_8009B64`; m4a `ply_xcmd`, fade-in/`TEMPORARY_FADE`.
  `m4aSongNumStartOrChange` is never called by FE8: leave it unclaimed.
- Follow-ups: units/`.fe8s` beyond the prologue need the `LOAD1/LOAD2` targets of each `ChapterEventGroup`;
  keysplit/drum sub-voicegroups are not written as `voicegroups/*.json`; OBJ mosaic; mosaic on affine BGs;
  GPU sprites are still rasterised on the CPU (fine for v1).

## How-to
- Shader parity after any shader/`PackedCompositor` change: `FE8_SHADER_DUMP=<dir> ./gradlew :testing:test
  --tests '*FrameCompareTest'`, then `node tools/shader_parity.mjs <dir>` (headless Chromium WebGL2).
- Re-extract: run the CLI (below), `python3 tools/compare_extraction.py <rom repo>/extracted <out>`, copy only
  changed/new files into the ROM repo's `extracted/`, push there (CI fails until you do).
- Extractor CLI: `./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out <dir outside repo>
  --decomp <fireemblem8u> --map <rom repo>/decomp/fireemblem8.map --symbols <rom repo>/decomp/fireemblem8.nm.txt"`.
- New reference runs (ROM repo; fetch it first): add to `harness/gen_scripts.py`, run it and
  `harness/make_reference.sh`; diff old `out/` vs a fresh untar of `dist/reference.tar.xz` (only
  PROVENANCE.sha1 may differ); `tar -C reference -cJf dist/reference.tar.xz out`; update `dist/MANIFEST.sha1`
  (else publish fails); push; copy into `.assets/reference`. PSG solo runs: harness `--audio --psg-mask N`;
  ours: `FE8_PSG_MASK` (with `--rerun`: env is not a Gradle input).
- Decomp source: `git clone --filter=blob:none --sparse https://github.com/FireEmblemUniverse/fireemblem8u`,
  checkout `04d1edb…`, `git sparse-checkout set --no-cone include src texts asm data sound`. Tests hard-code
  the names/addresses they need; ROM data symbols mostly lack sizes: use the next symbol's address.
- Trace timing: a frame that overruns its VBlank is dumped mid-frame; step only on dumps whose successor's key
  struct differs (see `titleKeyPath`).

## Rules and caveats
- A `@VerifiesPort` every run or two. No `java.*`/`javax.*`/`System.*` in `commonMain` (KMP); battle rolls
  only via `mechanics.battleRolls`.
- Fresh session: `git fetch` first; if local `master` is an old unrelated history: `git branch -m master
  stale-master && git checkout -B master origin/master`.
- Pushes race CI's `ci: update progress [skip ci]` commit: `git fetch && git rebase origin/master`, push again.
- CI's Dokka fails on ANY warning (every public declaration incl. constructor `val`s needs KDoc): run
  `./gradlew dokkaGenerate -Pfe8.docs=true` before pushing public API (background: can exceed 2 min cold).
  Keep `configureDokkaLint()` using Groovy property access (Dokka types are only on the docs classpath).
- Always `source .assets.env` before Gradle (else Maven Central 429s).
- `tools/progress.py` scans `main`/`commonMain`/`jvmMain` and `test`/`jvmTest`; it credits a `@VerifiesPort`
  test to the LAST `class` declared before it: keep helper classes below the test class.
- Allocation tests can flake (200 B once in 1000 frames): use `AllocationMeter.measureMin`.
- `FrameCompareTest.applyHBlank` knows only `HBlank_BgVerticalScroll`; dumped RAM is one game step ahead of
  the dumped frame (the test un-flips the menu scroll buffer).
- `gh release view/download <tag>` (GraphQL) is refused; use `gh api repos/.../releases/...` (REST).
- Old merged `wip/*` branches were deleted 2026-11-04 (owner PC); delete a `wip/` branch once merged.
- Data: `cursor_hand.png` colours `paletteSource: caller`; `effectiveAgainst` in items.json holds class
  numbers; classes inline shared terrain tables (decide with the data loader); `.fe8s` v0 prints args as raw
  numbers (symbolic constants and a parser are M5). `RamSymbols[name]` throws on ambiguous static names.
- Owner PC = WSL2 Ubuntu (2026-11-04): checkouts in `~/FE8/{Fire-Emblem-8-android,Fire-Emblem-8-rom,fireemblem8u}`;
  Android SDK in `~/android-sdk` (`:app` builds locally); Playwright in `~/node_modules`; cmake/ninja for the harness.
