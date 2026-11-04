# HANDOFF

_Updated 2026-11-04 (run 17:4x, on the owner's Windows PC). Owner decisions: docs/PROJECT_BRIEF.md._

## NEEDS USER
_(nothing blocked)_

## NEXT RUN, FIRST: check `wip/M4-07` CI (slices 3-5; slice 3 changes `:app`: VFS `read()`, Dokka on KMP modules); if green, merge to master
- Done 2026-11-04 (17:4x run), M4-07: slice 1 merged (CI 315 green); slice 2 on master (65b33a4, master CI
  317 green before it): `:gba:core/input/render/audio` KMP `jvm()` (`copyInto`, `kotlin.concurrent.Volatile`);
  ADR 0013 (targets jvm + android; android target needs AGP = CI-only slice). Full jvmTest green, 0 skipped.
- WIP `wip/M4-07` (pushed): slice 3 (055a166) `:gba:vfs` KMP: `VfsLayer.open(): InputStream` ->
  `read(): ByteArray`, `VfsFileNotFoundException`, `DirectoryLayer` in jvmMain, pure `gba.data.Crc32`
  (`Crc32Test` vs JDK); `:app` `AssetLayer`/`MainActivity` follow (CI-only compile). Slice 4 (333624a)
  `:gba:extract` KMP: `gba.data.Sha1` (`Sha1Test` vs MessageDigest + ROM), `internal expect fun
  zlibDeflate/zlibInflate` (JDK loops in jvmMain, PNG bytes unchanged), `ByteSink`; truncated PNG now
  `IllegalArgumentException`. jvmTest green after each slice. Slice 5 (wip): `:engine:core/data/input/render/save/
  script/text` + `:game` KMP (only `String.format` -> `padStart` in MessageCodec/RomView); jvmTest green.
  Master fix this run: `tools/progress.py` + `test_layering.py` scanned only `src/main`/`src/test`, so moved
  KMP sources were missed (slice 2 dropped m4a ports on master); now also commonMain/jvmMain/jvmTest
  (count 260 again).
  Then: `android` target (AGP in CI: `androidTarget()` in the KMP convention only when the SDK exists, like
  settings.gradle.kts does for `:app`), then `:engine:*`/`:game` (grep `java.` per module first).
- Local Windows runs (owner PC): no `gh` (REST via `git credential fill` token + curl), JDK 17 only (add
  `-Pfe8.jdk=17`), ROM repo at `../FE8Rom` has CRLF text: build `.assets` from it with `git -c
  core.autocrlf=false archive HEAD extracted` (else `RomImporterTest` fails), Python tests need `PYTHONUTF8=1`.
- Owner 2026-11-04: M4-04 device test passed (ROM import ~4 s on phone, boots from it); PSG sound sounds
  correct by ear. M4-04 done.
- Done 2026-11-04 (06:5x run), M4-06 slices 2-7 (ADR 0012 lists what moved / stays): `:gba:data` (Lz77, Decompress,
  Tiles/IndexedImage/Bgr555, JsonReader), `:gba:core` (VerifiesPort, EventChannel/CoreEvents, FixedStepClock,
  GameLoop/Simulation), `:gba:render` (RenderState, SoftwareRenderer, PackedCompositor, HBlankLines, GpuFrame, ...;
  HardwareScene stays: uses BgHardware/LcdControl/OamBuffer), `:gba:input` (GameAction, TouchButtons, UiElements),
  `:gba:audio` (whole former `:engine:audio`), `:gba:extract` (ExtractOutput, RomImporter, IndexedPng, Json, Symbols,
  DecompEnums). Engine modules `api`-depend on their gba twin. jvmTest + Dokka (`-Pfe8.docs=true`) green locally,
  progress 260 unchanged. Left for M4-06: testkit (ReferenceData, RamSymbols, AllocationMeter out of `:testing`),
  split `ExtractSources.kt` (interface generic; bundled-manifest path stays gbafe) so it + ScreenExtractor deps can
  move, mGBA harness reuse doc; then M4-07 (KMP).
- Done 2026-11-03 (22:5x run), M4-03: library `:engine:extract` (core, extractors, `ManifestSources.bundled("fe8")`
  = resource `engine/extract/src/main/resources/gbafe/extract/manifest/fe8.json`; no `java.io.File`, checked by
  `ManifestResourceTest`); `:tools:extract` = CLI (`DecompSources`, `FileOutput`, `Fe8StepsCli`). gbafe code
  must not name `fe8.` even in strings/paths (`test_layering.py`). Master CI failed on Dokka (cross-module
  overrides need their own KDoc): fixed, re-check.
- Done 2026-11-03 M4-04 code (merged): `RomImporter`/`Fe8Import` + app import UI (`RomImport.kt`, `Launcher`);
  CI compiled `:app` + APKs. Then owner device test (MANUAL_TEST entry to add): import,
  logcat `Fe8Import` time, game boots from `filesDir/base`. PNG bytes differ from extracted/ for 228 files
  (zlib build), pixels equal (test compares decoded pixels).
- Done 2026-11-03, M4-06 slice 1: `:gba:vfs` (package `gba.vfs`). Next: move `gbafe.data` etc. one module per
  brief (git mv + sed of package/imports + settings/build lists); `:app` imports must follow (grep app/).
- Done 2026-11-03 (14:5x run), M4-03 manifest: steps ask the decomp via `gbafe.extract.ExtractSources`
  (`DecompSources`; `RecordingSources` = CLI `--write-manifest`; `ManifestSources` = CLI `--manifest`, no decomp).
  Committed `tools/extract/manifest/fe8.json` (94 KB: 189 symbols, 126 spans, 1234 ROM-name lookups, enums,
  faces). `tools/ci_check_extraction.sh` now also fails on a stale manifest and on manifest-only output != decomp
  output (run locally with symlinked map/nm: 1830 files OK). Also done: core `runFe8Steps(rom: ByteArray,
  ExtractSources, ExtractOutput)` (no `File`; CLI wrapper uses `FileOutput`). Next: new module (e.g.
  `:engine:extract` or `:gba:extract` per M4-06) holding the core + extractors + `ManifestSources` + manifest as a
  resource, `:tools:extract` keeps CLI/`DecompSources`/`Symbols`/`DecompEnums`; check no `java.*` in the moved
  code (`MessagesExtractor.readFaces(File)` stays in the CLI); then M4-04. Local CI check: symlink baserom,
  extracted, ROM-repo `decomp/fireemblem8.{map,nm.txt}` into a dir and run with `FE8_ASSETS=<dir>`.
- Done 2026-11-03 (14:5x run), M3-14: `PsgSynth` NRx2 writes without trigger as mGBA `_writeEnvelope` (GBA
  style): DAC off (vol 0 + decrease) stops the channel, envelope `dead` state (held at 0/15, period 0), a dead
  envelope revived by a write reloads its timer (`M4aPsg.envelopeWrites`; `PsgSynthTest`). No change in the 6
  quarter-frame misses (`M4aNoteTest` now prints them). Analysis: they are envelope steps landing within ~2k
  cycles of a quarter boundary, ours late at f953/f981 and early at f991: not one constant phase offset.
  Deriving mGBA's grid from `psg_writes.txt` abs cycles (frame event every 32768 from reset, NR52 enable at
  f13 -> step 7 at events = 2 mod 8) gives offset 43290 (or +k*32768): 43290 -> 30 misses, 76058 -> 12; so the
  log's abs cycle or my chunk mapping is off. Low value; leave unless the owner hears a problem.
- Done 2026-11-03 (06:5x run): **port bug fixed**: `MPlayMain` (m4a_1.s `_081DD9F6`) sets CGB `mo |= 1` on a
  track volume change and `mo |= 2` on pitch change; `M4aSequencer.updateChannels` didn't, so `CgbSound` missed
  retriggers (ch2 notes at r906/r932 = the "first-step misses"). `PsgSynth` frame timing: after `startAtFrame`
  each render is one frame and the register file is latched `WRITE_CYCLES` (17408) after VBlank (old state
  plays first; all reads go through the latched copy). Quarter frames off 41 -> 6 of 857 (asserted < 1/50),
  RMS 1249/1200. `M4aNoteTest` asserts per-frame PSG trigger sets = mGBA (f901..f1100).
- ROM repo 5b4fbe3: harness `--psg-writes` (build.sh patches libmgba `GBAIOWrite*` with `gbaHarnessIoHook`)
  logs sound register writes with cycle (`psg_writes.txt`; `ReferenceData.Run.psgWrites`); `audio_menu` has it.
  mGBA write times vary 4.6k..31k cycles after VBlank. Tried feeding the measured time per frame to the solo
  synths: still 6 misses (other ones), so timing doesn't explain them. Left: ch1 f953 q0 (ours 1545 vs 216:
  mGBA's note already silent; note triggered step 949) and f991 q1, wave f980 q1..2 (ours ~250 vs 2), ch1 f1009.
  f953: by the grid (step 7 at 68198 + k*262144 from boot) NR12 0x32 written in step 949 reaches volume 0 at
  step 954 cycle ~4.6k whatever the write time, so ours is one envelope step late on this note only: check
  `PsgSynth.trigger`/`sequencerStep` vs mGBA `_resetEnvelope` + case 7 for this phase (scratch: print our
  envelope steps for ch1 f949..f954). Measured: our ch1 volume per eighth frame r948 3 (trigger), r949 3->2,
  r951 2->1, r953 0 from its first sample; mGBA's f953 chunk is already flat, so mGBA reached 0 earlier: the
  grid estimate above is off somewhere; compare our `seqStep` at each step-7 with mGBA's (log
  `audio->frame` in the harness hook if needed). `SEQ_OFFSET_CYCLES` sweep (misses): 60000 25, 67092 6,
  68198 6, 69304 9, 76000 12: the phase is right; the misses are note-specific.
  f991 is the opposite case (ours silent, mGBA vol 1): NR12 0x12 triggered step 991 @10.6k, NR12=8 (no trigger)
  step 993; mGBA's envelope code (`_writeEnvelope`, `_updateEnvelope`, case 7) matches ours as far as read. Wave f980: NR30 DAC off in step 981 (mGBA flat after, ours DC tail).
  Stopped channels now hold their last level (mGBA `GBAudioSamplePSG` adds `chN.sample` regardless; no change
  in the test metrics).
- Done 2026-11-02 (22:5x run): frame sequencer locked to the CPU clock as in mGBA: `PsgSynth.startAtFrame`
  (step 7 at cycle 68198 + k*262144; writes land 17408 cycles into a frame, applied at render start, so the
  grid is shifted by that). `M4aDriver` calls it with its frame count. `M4aNoteTest` now runs ch1/ch2/wave
  solo synths and compares peak-to-peak per quarter frame with `audio_psg1/2/4` (f900..f1099): 41 of 856 off
  (was 133 unaligned; asserted < 1/15). Per channel (scratch analysis): ch1 70 -> 4, ch2 18, wave 4.
  Mix RMS unchanged 1246/1200.
- Resolved 2026-11-03 (missing MPlayMain `mo` flags, not timing): ch2 notes at dumps 906 and 932 step one 64 Hz event later in mGBA than ours (others match; 947 with
  a similar trigger phase matches). mGBA 0.10.5 `src/gb/audio.c` logic = ours (checked: `_resetEnvelope`,
  case 7, `_updateEnvelopeDead` reloads `nextStep` on an NRx2 write only when dead). Likely the in-frame write
  time varies (CgbSound runs after the DirectSound mix: work varies per frame); a constant `TRIGGER_CYCLES`
  can't model it. Measure write times by logging mGBA's cycle at NR24 writes (harness).
- Done 2026-11-02 (15:3x run), both from mGBA 0.10.5 `src/gb/audio.c`: levels unipolar
  (`_squareChannelDuty * currentVolume`; square/noise 0..2*vol, wave 0..2*s; note on/off DC steps
  now give mGBA's DC-blocker transients) and square duty position kept across triggers (NR14/NR24
  restart never resets it). PSG RMS f900..f1100: ours 1248 vs mGBA 1200; solo ch1 777/786, ch2
  260/277, wave 686/683 (13379 Hz; rendering at 32768 Hz adds only ~2.5%).
- Done 2026-11-02 (15:0x run): the "wave 3.2x" was a test artefact: `M4aNoteTest` replayed frames in
  directory order (now sorted) and never saw the wave RAM load (state comes from each dump; now loads it from
  the dumped ch3 `currentPointer`). Fixes in `PsgSynth`: wave 2 units per 4-bit step (per-level pp now = mGBA
  at 100/75/50%), DC blocker (`highPass`, as mGBA blip_buf bass_shift 9 @ 32768 Hz; tests turn it off),
  square duty patterns in hardware phase (`DUTY_SHIFT`; ch1 and wave were anti-correlated vs mGBA).
- Now (AC RMS f900..f1100, mGBA int16): ch1 669/786, ch2 239/277, wave 679/683, ch1+2+3 967/1200 (0.81x).
  `ram_trace_menu_cgb` had no dumps on input frames (f902, f1000: SE note starts missed); ROM repo now splits
  its holds (same input, dumps r00902/r01000 added; other dumps byte-identical). With them: ch1 741/786,
  all 1033/1200. `M4aNoteTest` now asserts PSG RMS / mGBA (sum of `audio_psg1/2/4`) in 0.75..1.2 when those
  runs exist (skipped otherwise). Remaining gap: per-frame compare as in this run (ours vol-1 square pp
  788 vs mGBA 997: envelope/trigger timing?).
- ROM repo: harness `--audio --psg-mask N` (DirectSound off, PSG channels by bit) + reference runs
  `audio_psg1/2/4` (release `latest` rebuilt OK at 43f9476; note: changing harness/scripts needs `harness/make_reference.sh`, then
  `tar -C reference -cJf dist/reference.tar.xz out` and a new `dist/MANIFEST.sha1`, else publish fails).
  `FE8_PSG_MASK` solos ours (use `--rerun`: env is not a Gradle input).
- `PsgSynth` (not a port, GBATEK behaviour): squares, wave, noise, NR51 pan, sweep, length, NR50/SOUNDCNT_H;
  box-filtered; `PSG_GAIN` 512 derived from mGBA's mixer.
- Open: square gap (above), owner listen test (box filter, DC blocker, duty phase).
- Songs using CGB channels (600-frame scan, 74 songs): 67 (op theme, ch1), 96..126 (system SEs), 179, 184, ...

## Status
- Owner tested the APK 2026-10-30: boot to title, touch buttons and gamepad work; **only issue: slow app open**.
  Likely cause fixed (unconfirmed on device): the launcher counted thousands of bundled assets on the main thread
  (`AssetLayer.list` also did a per-file `exists()`, quadratic); now counted on `Dispatchers.IO`, no `exists()`.
- Boot (H&S, logos, op anim, title) matches mGBA: 27 lag frames, 249 frames pixel-exact, gap f1180 only.
- Run 2026-11-01 15:0x: `MPlayExtender` = `M4aSound.mPlayExtender` (M4aSoundTest: CGB types/pan masks);
  `RomView.SOUND_WINDOWS` (0x08207200..0x08587700, ROM repo `extracted/sound/`; `SoundWindowTest`: all 1000
  songs mix from it), `fe8.game.Fe8Sound`, `M4aPullSource`, launcher "Test music (opening)" (debug; MANUAL_TEST).
  `m4aSongNumStartOrChange`: FE8 never calls it (unverifiable, leave unclaimed).
- Verified ports: 260 / 8352 (earlier: `m4aSoundInit` = `M4aSound.init` (gMPlayTable open, `unk_B`;
  `M4aSoundTest`, also a song started by number plays); `m4aMPlayStop` = `M4aCommands.mPlayStop` + `TrackStop`
  (`M4aStopTest`); `FadeOutBody` (fade-out to stop only; fade-in/`TEMPORARY_FADE` untraced) + `MPlayMain` claimed
  (`M4aFadeTest`, `M4aNoteTest`); `m4aMPlayVolumeControl` = `M4aCommands.volumeControl` (`M4aVolumeTest`).
  New ROM-repo reference runs `ram_trace_menu_bgm` (title START, A every 120 f; BGM1 volume ramp + stop f1301,
  next song f1323) and `ram_trace_menu_fade` (BGM1 fade speed 4, f4908..f4972); `M4aSequencerTest` replays them
  with the game's volume control/stop (also `M4aNoteTest`: now replays fades too, 0 skipped, 3145 frames PCM byte-exact). M4-10 title key paths done. Stale merged remote branches (delete refused, 403): `wip/M4-09`, `wip/M3-10`,
  `wip/M4-10`, `wip/M4-10-affine`, `wip/M4-11`, `wip/M3-13-seq`, `wip/M3-13-note`, `wip/M3-13-mix`, `wip/M3-13-mix2`, `wip/M3-13-start`, `wip/M3-13-cgb` (merged).
- Rules: a `@VerifiesPort` every run or two; no `java.*`/`javax.*` in new engine/game code (KMP, M4-07); battle
  rolls only via `mechanics.battleRolls`.

## Next task: owner manual tests of M3-07/M3-12, meanwhile M3-13 m4a (sound ROM data for the app, CgbSound)
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
- M3-12 (merged; CI built the APK): `gba.audio.AudioSource`/`ToneSource`,
  `app/AudioTrackSink` (`PERFORMANCE_MODE_LOW_LATENCY`, 48 kHz, chunk = half the device minimum buffer) +
  `AudioThread` (URGENT_AUDIO, one pre-allocated buffer), launcher "Test tone" button, MANUAL_TEST step.
- M3-13 (in progress, on master): `gba.audio` `M4aPitch`, `M4aTrack`/`M4aChannel`/`M4aPlayer`/
  `M4aSoundInfo` state, `M4aCommands` (all ply_* incl. note/fine/endtie/xcmd; fade, TrackStop),
  `M4aSequencer` (`MPlayMain`: ticks, gate countdown, channel vol/pitch updates; needs an `M4aSoundInfo`
  for notes), `M4aMixer` (`SoundMainRAM`: reverb, envelope, fixed + interpolated mixing).
  `M4aNoteTest`: per-frame IWRAM dumps, all players in `gSoundInfo.intp` chain order on shared channels
  (`M4aRam.sound`), DMA counter taken from the next dump (VSync runs first): 3011 frames, track state,
  channel chains/fields and the **whole PCM buffer byte-exact** (0 skipped).
  Not modelled: `PORT`, `MEMACC` (no trace uses them; unsupported), `maxLines` cut-off (FE8: 0).
  Unverified (no trace): `FadeOutBody`, `TrackStop`, `ply_xcmd` (needs a run with a BGM fade / song stop).
  `PcmStream` (not a port): SPSC ring, game thread `push(info)` after `M4aMixer.frame`, audio thread
  `render` resamples 13379 Hz (`pcmFreq`, 224 samples/frame) to the device rate.
  Song starts (`M4aSequencer.start` = `MPlayStart`, `immInit`): the game calls them after SoundMain in
  the frame; the test detects `m4aMPlayImmInit` from the next dump (clock 0, track 0 flags 0x80).
  `M4aSequencer.songNumStart` (`M4aSongTable`; players = `gMPlayTable` order = `M4aRam.PLAYERS`).
  `M4aDriver` (not a port): `vSync` -> sequencers in `intp` order -> `M4aMixer.frame` -> `PcmStream.push`
  (`M4aDriverTest`; `M4aRam.sound` links `intp`, so `info.chain()` gives the traced order). Done: sound window, boot sound, `CgbSound`, `PsgSynth` v1 (top of file).
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
