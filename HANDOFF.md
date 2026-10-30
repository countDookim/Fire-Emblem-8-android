# HANDOFF

_Updated: 2026-10-30 (scheduled run). Owner decisions live in docs/PROJECT_BRIEF.md (amendments merged in)._

## NEEDS USER
_(nothing blocked)_

## Status
- Docs condensed this run (owner request): TASKS 21→7 KB, brief 18→9 KB, HANDOFF, CLAUDE, USAGE header.
- M4-11: H&S, logos, op anim and title match mGBA on the device path (`Fe8BootTest`: 27 lag frames f401..3600,
  239 frames f1110..3600 every 10; gaps below). Overrun = missed VBlanks per load (`GameLoop.overran`,
  `OpAnim/TitleScreen.missedVBlanks`, measured from `gBmSt.main_loop_ended`). `Fe8Boot.render` runs the active HBlank
  handler; per-line BLDCNT via `LineTable.setBlendControl`.
- Verified ports: 208 / 8352. Stale merged remote branches (delete refused, 403): `wip/M4-09`, `wip/M3-10`,
  `wip/M4-10`, `wip/M4-10-affine`, `wip/M4-11`.
- Rules: interleave verifiable ports with infrastructure (a `@VerifiesPort` every run or two); keep new
  engine/game code free of `java.*`/`javax.*` (KMP coming, M4-07); battle rolls only via `mechanics.battleRolls`.

## Next task: M3-15 touch buttons (owner 2026-10-30), then darken gap, manual-test APK, M3-07
- **M3-15** (TASKS.md): on-screen GBA button overlay `ActionSource` in `:app`, multi-touch, on by default in debug
  APKs. `:app` can't compile in sessions: mirror `fe8.app.HardwareKeySource`, keep logic in a JVM-testable class.
- **Darken gap**: `Fe8BootTest.SPLIT_FRAMES` f1110..1140 (limit <= 80 px) differ only at x125..139, y137..151, below
  `gOpAnimSt.unk0A` where `OpAnimHBlank2` sets BLDCNT 0xCCF (darken); same one-step-too-bright gap as f1170..1210,
  f2200..2230 (`FRAME_GAPS` in ProcEngineDifferentialTest/Fe8BootTest; also from the dump's own state in
  FrameCompareTest). Sample f1110 (127,149): ours 16,14,12 vs mGBA 16,15,12; BLDY 8, BLDALPHA 0x0808, where every
  darken formula gives ceil(c/2): suspect OBJ semi-transparency/target handling, not the formula (all
  `MgbaColorMath.darken` variants tried). Dump both channel values for one pixel first; read mGBA 0.10.5 `_darken`
  / `software-private.h`. When fixed: limit 0, drop those gaps, add `@VerifiesPort("OpAnimHBlank1")`/`2`.
- Other gaps: f1180 BG VRAM 0xD800..0xE800 (sync timing); f3000 (+f3010 device path) title sprites one step off.
- Then: manual-test APK (M4-11 acceptance), title key paths (need a key trace), M3-07 GPU backend (draw
  `RenderState` with shaders; the app shows a CPU frame via GL today).
- Unverified (no trace yet): `Title_RestartProc`, `SkipTitleFx_Loop_KeyListener`, `Title_EndAllProcChildren` path,
  `FadeCore.darkenEnd`, `ColorFadeSetupFromColorToBlack/White`, `ColorFadeSetupFromWhite`, `OpAnimHBlank1/2`,
  `sub_80C85FC`/`sub_80C8684`, `GameControl_PostIntro` (partial), `sub_8009B64`.
- Follow-ups (not tasks yet): units/`.fe8s` beyond the prologue need the event converter to follow `LOAD1/LOAD2`
  of each `ChapterEventGroup`; keysplit/drum sub-voicegroups not written as `voicegroups/*.json`; renderer: OBJ
  mosaic, mosaic on affine BGs.

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
