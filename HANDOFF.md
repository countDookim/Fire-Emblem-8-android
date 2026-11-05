# HANDOFF

_Updated 2026-11-05 (owner-PC session, night). Owner decisions: docs/PROJECT_BRIEF.md. History: git log._

## NEEDS USER
- GitHub Actions: every code-repo run since 2026-11-05 00:03Z (f50da92) is `startup_failure` ("workflow file
  issue", also on a manual dispatch) although ci.yml is unchanged and valid; billing (owner): compute $2.67/$12,
  storage 0.4/0.5 GB, resets ~2026-12-01: likely the storage quota (not confirmed). Until fixed, run `jvmTest`, Dokka and `:app:assembleDebug`
  locally before pushing; the README progress bar is not updated by CI. 2026-11-05: 766 old artifacts (4.2 GB,
  90-day default retention) deleted with the owner's OK; ci.yml now keeps artifacts 3-7 days, Dokka on master only.

## Next task: M5-16 (all 765 non-WM EventScr_* → .fe8s, round-trip byte-exact with the M5-15 assembler)
Done 2026-11-05 (owner PC): M5-09..15, 18..24, 28, 29, 33..36, 38, 41 (see TASKS). Open and unblocked:
prologue.fe8s is v1 (symbolic, exact nm lengths; ROM repo `extracted/` updated, but its release is not re-published
while Actions is blocked: refresh `.assets/extracted` from the ROM repo checkout); M5-25/26/30..32 (saves),
M5-37/39/40 (map graphics/render), M5-41b (ROM run on a map larger than the screen: the prologue map is 15×10,
so the camera never scrolls in ram_trace_first_phase).
Pinned frames (ROM repo `harness/gen_scripts.py`, NEWGAME comment): InitChapterMap f9314..9319 (ch 0), event's
load_map ch 16 settles ~f9513, StdEventEngine f4907..9292 (WM) and f9508..19312, BattleEventEngine
f14479..14631, PhaseIntro f19316 (= suspend info[4] commit), PlayerPhase f19393. New-game save write
f1130..1132 + 1 byte (0x7224) f1214. Title START now enters GameControl's save-menu stand-in (holds) until M5-58.

## State
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Sound: m4a sequencer and DirectSound mixer byte-exact vs per-frame RAM traces (`M4aNoteTest`, 3145 frames);
  `CgbSound` verified; `PsgSynth` (not a port, GBATEK/mGBA behaviour) RMS 1249 vs mGBA 1200.
- Owner device tests passed: boot to title, touch buttons, gamepad (2026-10-30); GPU backend, audio
  (2026-11-01); ROM import ~4 s, boots from it, PSG sound correct by ear (2026-11-04).
- Verified ports: 310 / 8352 (`tools/progress.py`, 2026-11-05).
- Layout: game-neutral `:gba:*` (ADR 0012), FE-shared `gbafe.*` in `:engine:*`, FE8-only `fe8.*` in `:game`
  and `:app`. KMP (ADR 0013): all of them except `:gba:testkit`, `:testing`, `:tools:extract` (plain JVM);
  android target only in CI (`com.android.kotlin.multiplatform.library`; CI log shows `compileAndroidMain`).
- Owner to smoke-test the first APK built on the KMP android variants (MANUAL_TEST "Newly testable").

## Open leads (low priority unless the owner reports a problem)
- m4a tests skip the M5 runs (`M4aRuns.kt`): our sequencer diverges on `ram_trace_newgame` r00975 channel 12
  (new-game menu songs). Investigate before claiming m4a coverage there.
- Prologue map fits the screen: M5-07 holds move the cursor (14,9) but never scroll BG3/camera; a scroll
  trace needs a bigger map (later chapter).
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
- Harness (ROM repo): `watch NAME ADDR LEN` (≤16 watches, LEN ≤ 0x10000, hex LEN needs `0x`), `dumpsram`,
  `--sram FILE`; `python3 -I harness/scan_trace.py <run dir>` names procs/gPlaySt/SRAM transitions;
  `gen_scripts.py --scratch-newgame OUT END RAM_EVERY [FROM]`. loadstate does not restore SRAM.
- Scheduled runs and owner-PC sessions both push to `master`: before starting a task, `git fetch` and skip it if a
  `wip/<id>` branch exists or TASKS marks it `wip`; push `wip/<id>` early for long tasks. M5-02 needs cmake, ninja
  and network (mGBA clone); without them take a ROM-only task.
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
