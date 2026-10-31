# HANDOFF

_Updated 2026-10-31 (scheduled run 14:51). Owner decisions: docs/PROJECT_BRIEF.md._

## NEEDS USER
_(nothing blocked)_

## Status
- Owner tested the APK 2026-10-30: boot to title, touch buttons and gamepad work; **only issue: slow app open**.
  Likely cause fixed (unconfirmed on device): the launcher counted thousands of bundled assets on the main thread
  (`AssetLayer.list` also did a per-file `exists()`, quadratic); now counted on `Dispatchers.IO`, no `exists()`.
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Verified ports: 231 / 8352 (this run, m4a: ply_note, RealClearChain, ply_fine, ply_endtie, SoundMainRAM). M4-10 title key paths done. Stale merged remote branches (delete refused, 403): `wip/M4-09`, `wip/M3-10`,
  `wip/M4-10`, `wip/M4-10-affine`, `wip/M4-11`, `wip/M3-13-seq`, `wip/M3-13-note`, `wip/M3-13-mix`, `wip/M3-13-mix2` (merged).
- Rules: a `@VerifiesPort` every run or two; no `java.*`/`javax.*` in new engine/game code (KMP, M4-07); battle
  rolls only via `mechanics.battleRolls`.

## Next task: owner manual tests of M3-07/M3-12, meanwhile M3-13 m4a (CgbSound, then AudioSource)
- Done (merged to master; CI built the APK): `SpriteRasterizer` (shared OBJ rules), `GpuFrame` (per-line
  packing: layer origins, windows, blend, per-line palettes, CPU OBJ plane), `PackedCompositor` (Kotlin spec of
  the shader; parity with `SoftwareRenderer` in every `SoftwareRendererTest` scene via `@AfterEach` and on 15 mGBA
  frames), `app/GpuBackend` (GLES 3.0, composites 240×160 into an FBO, then scales), launcher button
  "Play (GPU renderer, experimental)", MANUAL_TEST step.
- Shader check: `FE8_SHADER_DUMP=<dir> ./gradlew :testing:test --tests '*FrameCompareTest'` then
  `node tools/shader_parity.mjs <dir>` (headless Chromium WebGL2): all 15 frames pixel-exact. Re-run after any
  shader/`PackedCompositor` change; `glslangValidator` (apt `glslang-tools`) checks syntax.
- Open: owner manual test (then mark M3-07 done); maps/sheets upload only when changed (palette, lines, OBJ plane every frame); sprites
  are still rasterised on CPU (OK for v1); GPU colour math is hardware (no mGBA variant).
- M3-12 (merged; CI built the APK): `gbafe.audio.AudioSource`/`ToneSource`,
  `app/AudioTrackSink` (`PERFORMANCE_MODE_LOW_LATENCY`, 48 kHz, chunk = half the device minimum buffer) +
  `AudioThread` (URGENT_AUDIO, one pre-allocated buffer), launcher "Test tone" button, MANUAL_TEST step.
- M3-13 (in progress, on master): `gbafe.audio` `M4aPitch`, `M4aTrack`/`M4aChannel`/`M4aPlayer`/
  `M4aSoundInfo` state, `M4aCommands` (all ply_* incl. note/fine/endtie/xcmd; fade, TrackStop),
  `M4aSequencer` (`MPlayMain`: ticks, gate countdown, channel vol/pitch updates; needs an `M4aSoundInfo`
  for notes), `M4aMixer` (`SoundMainRAM`: reverb, envelope, fixed + interpolated mixing).
  `M4aNoteTest`: per-frame IWRAM dumps, all players in `gSoundInfo.intp` chain order on shared channels
  (`M4aRam.sound`), DMA counter taken from the next dump (VSync runs first): 3011 frames, track state,
  channel chains/fields and the **whole PCM buffer byte-exact** (6 skipped: song changes).
  Not modelled: `PORT`, `MEMACC` (no trace uses them; unsupported), `maxLines` cut-off (FE8: 0).
  Unverified (no trace): `FadeOutBody`, `TrackStop`, `ply_xcmd` (needs a run with a BGM fade / song stop).
  `PcmStream` (not a port): SPSC ring, game thread `push(info)` after `M4aMixer.frame`, audio thread
  `render` resamples 13379 Hz (`pcmFreq`, 224 samples/frame) to the device rate.
  Next: wire a per-frame driver (sequencers in `intp` order -> `M4aMixer.frame` -> `PcmStream.push`) into
  the app's `AudioThread` once song start (`m4aSongNumStart`/`MPlayStart`) is ported; `CgbSound` (CGB
  channels 1-4 -> PSG; needs a PSG model); claim `MPlayMain` once fades are verified.
  Sources (decomp sparse-checkout `src/m4a* include/m4a* include/gba`): `src/m4a.c`, `src/m4a_1.s`,
  `src/m4a_tables.c`, offsets in `include/m4a.inc` / `include/gba/m4a_internal.h`.
- If the owner still reports slow startup: measure what runs before the launcher draws (`Application`/
  `MainActivity.onCreate`, Compose init) or the first Play frame (`bundledBootRom`, `Fe8Game` setup).
- Trace timing: a game frame that overruns its VBlank (e.g. the fade setup) is dumped mid-frame and the next dump
  shows no key update; step only on dumps whose successor's key struct differs (see `titleKeyPath`).
- f1180: BG VRAM 0xD800..0xE800 differs from the dump (sync timing of a BG map copy).
- Unverified (no trace): `OpAnimHBlank1` (never active at compared frames f1110+),
  `ColorFadeSetupFrom{ColorToWhite,White}`, `sub_80C85FC`/`sub_80C8684`,
  `GameControl_PostIntro` (partial), `sub_8009B64`.
- Follow-ups: units/`.fe8s` beyond the prologue need `LOAD1/LOAD2` targets of each `ChapterEventGroup`;
  keysplit/drum sub-voicegroups not written as `voicegroups/*.json`; OBJ mosaic, mosaic on affine BGs.

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
- `cursor_hand.png` colours: `paletteSource: caller`. `effectiveAgainst` in items.json holds class
  numbers; classes inline shared terrain tables (decide with the data loader).
- `.fe8s` v0 prints args as raw numbers; symbolic constants and a parser are M5.
- `RamSymbols[name]` throws on ambiguous static names.
- Dokka's Kotlin types are only on the docs classpath: keep `configureDokkaLint()` using Groovy property access.
