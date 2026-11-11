# Test suite speed-up

Date: 2026-11-11

Owner request: "make the full test significantly shorter without losing meaningful testing; write
what changed so it's possible to go back".

## Before (last full run, `./gradlew jvmTest test`, 4.5 min wall)

- `:testing` is 260 s of 284 s summed test time and ran serially in one JVM fork.
- Top classes: TextDrawRomTest 80 s (one test, `replayed talk lines reproduce the traced glyph tiles`:
  a DFS search per full dump of `ram_trace_prologue_talk`), M4aChannelTest 28 s, M4aPitchTest 14 s,
  M4aTrackTest / M4aNoteTest (scan every iwram dump of every reference run, about 7,000 new dumps
  added on 2026-11-11), WorldMapFaceTest 23 s, AppRomPathTest 21 s.

No test or assertion was removed or weakened by any change below. Verified in full test run on 2026-11-11.

## Change 1: parallel forks for `:testing` (testing/build.gradle.kts)

Commit: `2103612`

What changed
- `test` now has `maxParallelForks = 3` (heap unchanged: default per fork).
- `serialTestClasses`: every class under `testing/src/test/kotlin` whose source mentions
  `AllocationMeter` (11 classes: AllocationTest, WalkingAllocationTest, BmMapDifferentialTest,
  BmMapScrollDifferentialTest, KeyStatusDifferentialTest, ModScriptEndToEndTest, PrologueMapTest,
  RenderStateTest, SoftwareRendererTest, ToneSourceTest, WiredItemPopupTest). Detected from the
  sources, so a new class using the meter goes to `serialTest` automatically.
- `test` excludes those classes (and their nested classes); new task `serialTest` (`Test`,
  `maxParallelForks = 1`, same classes dirs, classpath and `fe8.decomp` / `fe8.extractedOut`
  properties) includes exactly them. `test` is `finalizedBy("serialTest")`, so every `test` run
  (CI: `./gradlew jvmTest test`) runs serialTest afterwards, even if `test` failed.
- Targeted runs (any task request containing `--tests`, decided at configuration time from
  `gradle.startParameter.taskRequests`) keep the old behaviour: one fork, no exclude, no
  serialTest, so `:testing:test --tests fe8.testing.X` works for every class.
- Result XML: `testing/build/test-results/serialTest/`. `tools/ci_summary.py`, `tools/progress.py`
  and `tools/unverified.py` glob `**/build/test-results/**/*.xml`, and the CI upload step uses the
  same glob, so no reader needed a change and the summary count still includes every test.
- The frame benchmark (`FrameTimeBenchmark`) is a `JavaExec` task in `src/main`, not a test; untouched.

Why no test is lost
- The two sets are complementary patterns over the same test classes; each class runs exactly once.

Risk
- Allocation counters are per thread and forks are separate JVMs, but a loaded machine slows JIT/OSR,
  which is why those classes run alone, after `test`.
- Shared-state review (nothing needed fixing): tests write only to `build/extracted/...` (each class
  its own file names; `ui/` is shared by IconsRomTest, IntroLogosScreenTest, HealthSafetyScreenTest
  with distinct file names), `build/frame-diff/<run>_<dump>.png` (FrameCompareTest, unique names) and
  a `Files.createTempFile` (ModScriptEndToEndTest). `mkdirs()` is race-safe. No test sets system
  properties at runtime; no test reads another test's output.
- Peak memory is up to 3 forks of default heap (a quarter of RAM each by default); on the 10 GB
  machine lower `maxParallelForks` if it swaps.

How to revert
- `git revert 2103612`; or delete the `targeted` val, the `if (!targeted)` block in `test`, the
  `serialTestClasses` block and the `serialTest` task.

## Change 2: TextDrawRomTest memoizes identical searches (TextDrawRomTest.kt)

Commit: `a0d69c4`

What changed
- In `replayed talk lines reproduce the traced glyph tiles`, the DFS result is cached per exact key
  (strip index, pen x, strip bytes as an ISO-8859-1 string). A repeated key reuses the outcome;
  `checked` and `matched` are still incremented for every occurrence and MISS is still printed.

Why no assertion is lost
- `Search.run()` depends only on its inputs: it refills VRAM with the sentinel, creates a new `Font`
  and `Text`, and re-runs `initTextFont` / `initTalkTextFont` / `initText`, which reset the active
  font, glyph table, palette call and `chrCounter`; the host language and config are constant. Nothing
  left by a previous search reaches the next. `checked == matched` and `matched >= 40` keep their
  meaning; the inner assertions run on the first occurrence of each key.

Risk: none known (a future stateful `TextSystem` field not reset by those init calls would need to join the key).

How to revert
- `git revert a0d69c4`; or replace the `outcomes.getOrPut(key) { ... }` line with
  `Search(ts, host, rom, i, x, strip).run()` and drop the `outcomes` map.

## Change 3: scan-every-dump audio tests memoize identical cases

Commit: `407a17f`

Files: M4aChannelTest, M4aPitchTest, M4aTrackTest.

- M4aChannelTest: `volSet` is a pure function of (rhythmPan, velocity, volMR, volML) on a fresh
  channel. Key: those four inputs plus the two wanted volumes. Duplicates still `direct++` / `cgb++`.
- M4aPitchTest (DirectSound and CGB tests): `midiKeyToFreq(waveFreq, key, fine)` and
  `midiKeyToCgbFreq(chan, key, fine)` are pure (constant tables only). Key: all inputs plus the wanted
  value. `checked` (and `keys` in the DirectSound test) still see every case.
- M4aTrackTest: `volPitSet` is pure in the dumped bytes (flags, outputs 0x08..0x11, inputs 0x12..0x18)
  on a fresh track with both set flags. Key: all 17 bytes the case reads. Duplicates still add their
  result to `distinct` and count in `checked`, so `checked > 10_000` and `distinct.size > 100` are unchanged.
- M4aNoteTest: NOT changed. It steps the sequencer/mixer frame to frame and carries PSG synth and
  wave RAM state across frames, so a frame is not a function of its own bytes.
- The scanned runs, dumps and exclusion lists are unchanged.

Why no assertion is lost: the first occurrence of each distinct case is asserted exactly as before;
a duplicate has identical inputs and expected values, so it would produce the same result.

Risk: none known beyond key collisions, which are excluded by exact packing of every byte read.

How to revert: `git revert 407a17f`; or remove the `seen` / `results` sets and run the body
unconditionally.

## Results (measured 2026-11-11)

- Wall time (`./gradlew jvmTest test --max-workers=2`):
  - Before: 4.5 min (270 s)
  - After: 3m 19s (200 s), saving ~70 s (~26% speed-up).
- Total tests: 806 (across 250 XML files, including `testing/build/test-results/serialTest/`),
  0 failures, 0 skips (identical to master).
- Verified functions (`tools/progress.py`): 817 (10.5%), 0 ported-without-tag.
- Commit sequence on `wip/test-speedup-run`:
  1. `2103612`: test(testing): parallel forks and serialTest for allocation-metering classes
  2. `a0d69c4`: test(testing): memoize identical search outcomes in TextDrawRomTest
  3. `407a17f`: test(testing): memoize pure test cases in M4aChannel, M4aPitch and M4aTrack

## Deferred

- Profiling WorldMapFaceTest (23 s) and AppRomPathTest's whole-ROM case (17 s) needs test runs,
  which the owner deferred.
