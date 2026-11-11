# HANDOFF

_Updated 2026-11-11 10:40Z (owner PC: 4–6 agent run; test speed-up left on wip/test-speedup). Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Plan: [ROADMAP](docs/plans/ROADMAP.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. APP-1 (Delete save) owner-confirmed on 0.1.590 (11-11). New owner report D-13 below.
Modding decisions D2–D9 in [M7-modding](docs/plans/M7-modding.md) are open (D1 decided: option A, ADR 0015; M6 is not blocked).

## In progress

- D11-wm3e: owner PC, until ~15:30Z
- D11-ci2: owner PC, until ~15:30Z
- PP-3: owner PC, until ~15:30Z
- PP-14: owner PC, until ~15:30Z

## Current state

- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published **0.1.551 (`1c30d0b`)**: CI **769 passed, zero failures/skips**. The pinned decomp
  source is now provisioned for node-table tests; missing source fails clearly and invalidates
  stale test-cache results. Rapier popup icon/cleanup fixes remain included (first in 0.1.528).
- Current source: **817 verified of 7,732 in scope (10.5%)**; 620 link/debug functions are out of scope
  (8,352 total). `ported-without-tag: 0`, `unknown-verifies-names: 0`. Full local `jvmTest test` (owner PC 11-11
  10:15Z): **806 passed, zero failures/skips**. CI 587–589 failed only on tests that scan every capture (fixed:
  `M4A_UNCOVERED_RUNS`, `M4aTrackTest` skips FLG_START tracks).
- New Game reaches the Prologue opening and turn-1 map; suspend/resume and cursor movement work.
  Player actions, battle animations and the real save/difficulty menus remain unimplemented.
- Layout: game-neutral `:gba:*`, `gbafe.*` in `:engine:*`, FE8 `fe8.*` in `:game`/`:app` (ADRs 0010–0013).
  All APKs require player ROM import (`base/rom/baserom.gba`); no game content is bundled.

## Device status

Close an issue only after reference verification and owner confirmation; preserve stated comparison limits.

| Issue | Latest evidence / status |
|---|---|
| D-07 audio | **Resolved for both reported castle events:** owner heard neither hitch on local 0.1.488 (`0a73f11`); normal trace PCM repetitions fell 4495→0 after the movement frontier change. Earlier cache/reserve changes alone failed. |
| D-12 phase banner | **Player banner confirmed correct by the owner on 0.1.488 (11-11)**; reference: `PhaseIntroDifferentialTest`. Enemy/green banners open (D12-2, with M6-D). |
| D-11 cinematic | Open: the subtitle monologue and following world-map opening are unwired. |
| D-09 Rapier popup | **Resolved:** owner confirmed the popup correct on 0.1.571 (11-11); reference: `WiredItemPopupTest`/`GotItemPopupDifferentialTest`. Other popup branches stay D09-3 (low priority). |
| D-13 Start in cutscene | **Open (owner, 0.1.590, 11-11):** Start during the Prologue in-game cutscene fast-forwards visibly instead of skipping. Triage: `EventEngine_StartSkip` sets the skip bit but its effects (`StartLockingFadeToBlack(0x40)`, `sub_800D488`, `Proc_BlockEachMarked(5)`) sit behind the no-op `startSkipEffects` hook. Rows D13-cap1, D13-1. |
| D-05 interactions | Expected missing gameplay; re-test when player actions are ported. |

D-01–04, D-06, D-08, D-10 and normal portrait mouths are owner-confirmed solved.
Earlier device checks cover boot/import/controls/GPU/suspend-resume. Do not request routine repeats of solved checks.

## Next work

Plan re-done 2026-11-11 (owner-approved): [ROADMAP](docs/plans/ROADMAP.md) has the milestones and task rules;
[TASKS](docs/TASKS.md) has the open rows. Tasks are capture-first: CAP → INV → PORT (≤ ~8 functions the capture
window runs) → WIRE. Two lanes run in parallel; take the first `todo` whose deps are done in either.

1. **Lane A, opening wired (M6-A, D-11).** Done 11-11: wm3b–d, ci0, ci1, ci4, cap2 (`ram_trace_newgame_intro`,
   intro main path). Next: `D11-wm3e`, `D11-wm3f`, `D11-ci2`/`ci3` (use the new capture), `D11-wire-a`, `D11-cap1`.
   Status: [D11-worldmap §8–9a](docs/plans/D11-worldmap.md). Ports stay standalone until `D11-wire-b`/`D11-wm9`/`ci5`.
2. **Lane B, player phase (M6-B).** Done 11-11: PP-cap1..3, PP-0, PP-0a (hover cursor; device check pending), PP-1, PP-2, PP-9. Next: PP-3, PP-10, PP-14..16. Notes: [M6B-player-phase](docs/plans/M6B-player-phase.md).
   PP-cap3 has battle animations on; M6-C needs an animations-off variant.
3. **Readability (rule 11, changed 11-11):** comments must be meaningful and checked against the decomp
   ([READABILITY](docs/READABILITY.md)); every older `done` row carries `audit todo`. Use Sonnet, not Haiku.
   Audit next: fe8.game.ui, OpAnim Eirika functions, bm sprites (Haiku passes of 11-11).
4. **Alignment done 2026-11-11:** the progress bar counts in-scope functions only; generated lists
   [UNVERIFIED](docs/plans/UNVERIFIED.md) (written but unverified code) and [STANDINS](docs/plans/STANDINS.md)
   (placeholders by milestone) are checked in CI: regenerate both with `--write` after changing such comments.
5. **Agent notes (11-11):** brief agents to keep scripts in their own scratchpad subdirectory (one agent ran
   another's `edit1.py`). `WalkingAllocationTest` failed intermittently under heavy load once (PP-1 run); it passed
   3/3 afterwards. Re-run it if it fails.
6. **TEST-1 done 11-11:** `:testing` runs in 3 forks; AllocationMeter classes run in `serialTest` (auto-detected).
   Full run ~3.3 min (was 4.5). Revert notes: [TEST-SPEEDUP](docs/plans/TEST-SPEEDUP.md).
7. **Owner:** decide modding questions D2–D9 when M7 nears.

`@VerifiesPort` takes bare function names; `"src/x.c: F"` silently counts nothing.
Node table checks use pinned runtime source: `tools/setup_decomp.sh` or `FE8U_DECOMP` (read-only).

## Verification limits worth retaining

- D-07: before/after Android traces, profiling limits and ADB setup are in [D07-tracing](docs/plans/D07-tracing.md).
  The frontier preserves weighted movement-pool results and passes existing original movement tests.
  Other long ticks remain; neither trace isolates per-function CPU cost or precisely timestamps the two reported events.
- D-12: `PhaseIntroDifferentialTest` checks chapter-6 player-banner state/buffers/graphics plus complete OBJ
  attributes at six captures f1700..1750 (`a95588a`). `aefe6d8` corrects VCount-to-HBlank line translation with a
  source-derived render-line test; original per-line blending, sound hooks and enemy/green branches remain unverified.
  The replacement uses the primary HBlank slot; coexistence with another handler is not established.
- M4a tests exclude divergent runs in `M4aRuns.kt`; exclusions are not verified coverage. M6-13 needs an autolevel
  trace; M6-14 needs a colliding unit-load trace. Generic palette fades and non-normal portrait modes remain partial.

## Working notes

- Claim before coding; skip live claims. Use `git fetch` + `git rebase --rebase-merges origin/master`.
  CI may add a progress commit, so fetch/rebase again if a push races it. Commit completed steps; unfinished work uses `wip/`.
- Owner PC: `source .assets.env`, export `ANDROID_HOME=$HOME/android-sdk`; use `$HOME/.cache/fe8-tmp` for logs/temp.
  Maven mirror: `tools/maven_mirror.py` on port 8765 (`/google` before `/central`).
  Serialize Gradle with `.gradle/agent-checks.lock`; use two workers / 2 GiB heap and run Dokka per module
  (`:game:dokkaGenerate -Pfe8.docs=true`; fails on undocumented public members, incl. companions).
- Full checks: `./gradlew jvmTest test`. Focused verification uses `:testing:test --tests <class>` (no `:testing:jvmTest`).
  Filtered runs replace result XML: regenerate progress only after the full suite. Put helper classes/enums below
  tagged tests: the progress parser uses the last preceding class declaration. CI normally commits README/port map;
  regenerate/commit them locally when needed. Keep `ported-without-tag: 0` and mutation-check every new port tag.
- Refreshing an asset archive/manifest alone does not install new runs: unpack/copy them into
  `FE8_ASSETS/reference/out` too; otherwise newly added tests fail on missing files.
- ROM bytes/text/glyphs/dumps belong only in the private ROM repo; tests read FE8_ASSETS. New captures must preserve
  old runs, update packed assets and manifest SHA1, then publish/refresh assets; see the private repo README and harness.
  An overrun dump may be mid-frame; account for RAM/OAM sync timing. GPU/shader changes also need shader parity checks.
- Keep assistant instructions synchronized through `docs/AGENT_INSTRUCTIONS.template.md` and
  `tools/sync_agent_instructions.py`. Continue [READABILITY](docs/READABILITY.md) reviews on owner-PC sessions.
- Completed history and older investigation details: `git log`; pre-compression notes: `git show 8c5b7c9:HANDOFF.md`.
