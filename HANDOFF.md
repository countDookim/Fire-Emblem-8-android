# HANDOFF

_Updated 2026-11-13 20:08Z (owner PC: M6-C done except MC-17; M6-D started: EP-cap1, EP-0, EP-1, EP-2a; 922 tests green locally). Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Plan: [ROADMAP](docs/plans/ROADMAP.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Owner device test 0.1.622 (11-12): D-11 and PP-0a confirmed; new reports D-14, D-15 below.
Modding decisions D2–D9 in [M7-modding](docs/plans/M7-modding.md) are open (D1 decided: option A, ADR 0015; M6 is not blocked).

## In progress

- EP-3a: owner PC, until ~22:15Z
- Readability OpAnim lines 742-1381: owner PC, until ~21:20Z

## Current state

- 2026-11-13 owner PC (17:56–20:08Z): **M6-C done except MC-17; M6-D started.** Merged MC-8b (hit checks verified),
  MC-8c (triangle attack transcribed; was always false), MC-w1/MC-w2 (headless replays of the attack and kill captures
  through Fe8Boot match every unit byte and the RNG; attack flow 0 B/frame), TEST-2/TEST-4 (MU walk and waiting event
  loads no longer allocate; TEST-5 menu callback boxing and TEST-6 one-off walk allocations open). EP-cap1 published
  (ROM `assets-2500bf5`, `ram_trace_prologue_enemy_phase`: both Soldiers attack Eirika and die, boss idle, no
  level-up). EP-0 inventory (traced; EP rows rewritten, 2/3/6 split). EP-1 (phase start/end) and EP-2a (AiPhaseInit,
  CpOrder; Fe8Boot runs them, CpDecide a stand-in until EP-3a). Next: EP-2b (claim released), EP-3a.. ; MC-17 needs
  EP-cap2. Readability: fe8.game.ui all rows; fe8.game.bm PhaseIntro/MapAnimations, sprite group, state group, camera
  group, BmMap group, ChapterData/ChapterMapGfx; save BmGameSave group and BmSaveLib 1-520 audited. Left: BmSaveLib
  521-1080, bm BMapMain/BattleMapKind, bm UnitTable, bm 3863 (never reviewed), opanim, fe8.game, extract events.
  Merge scripts: `|| exit 1` on every step (set -e did not stop them). Device check pending: PP-17, D-14, D-15, M6-C
  attack flow (MANUAL_TEST step 3, APK 0.1.667+).

- 2026-11-13 owner PC (to ~10:00Z): **M6-C almost complete.** Merged MC-3a, 3b, 4a, 4b, 5, 6, 9, 10, 11, 12a, 12b,
  13, 14, 15, 16 (with MC-1/2/7/8a from 11-12): Attack -> weapon list (rows, panel, face) -> target cursor -> forecast
  (slides in, arrows, x2) -> battle resolves at once (no map anim, M6-H) -> exp, write-back, dead unit removed.
  Left in M6-C: MC-8b (hit checks), MC-17 (level-up; needs EP-cap2), MC-w (WIRE + headless replay). Stand-ins:
  level-up bookkeeping only (MC-17), dying rescuer no-op (C14-1). New bug rows: BUG-gold, BUG-changemap.
  Readability: all fe8.game.worldmap, fe8.game.event, fe8.app, gba.* (core, input, vfs, data, extract, testkit),
  gbafe.script, small engine/extract rows, savemenu, fe8.testing, title, OpSubtitle, save suspend group audited.
  Left: gbafe.extract.fe8.events, fe8.game, fe8.game.bm (3 rows), opanim (OpAnim/Hs/EarlyStartUi), save (BmSaveLib;
  BmGameSave group), fe8.game.ui. Local full suite is now `./gradlew jvmTest --continue` in the main checkout
  (~6 min; TEST-SPEEDUP Change 4). Device check pending: PP-17, D-14, D-15, M6-C attack flow (MANUAL_TEST step 3).

- 2026-11-12/13 owner PC (17:30–23:10Z): **M6-C started.** MC-1, MC-2, MC-7, MC-8a, MC-9 and MC-3a merged (attack
  target list, battle rounds and hits, forecast simulation, Attack opens the weapon list; 896 tests green, Dokka
  clean). Next: MC-3b, MC-4, MC-5, MC-6, MC-8b, MC-10. Readability: gba.render, gbafe.render and 4 of 8 worldmap rows
  audited; WorldMapLineFade darken constant fixed. Next readability: worldmap Scroll, Screen, Unit, Mu (one file each,
  owner 11-13: small tasks). Device check pending: PP-17, D-14, D-15, MC-3a (MANUAL_TEST).

- 2026-11-12 owner PC (evening): **M6-B code-complete.** PP-17 merged (player phase wired in Fe8Boot: select, range,
  arrow, move, unit menu, Wait, cancels, map menu End, side windows, Burst; enemy AI an M6-D stand-in that ends at once,
  End turn reaches turn 2); crash fixes for a windowed ROM and a unit-less slide-in; cursor-move allocations removed.
  Earlier today: PP-11, CAP-status1 (ROM `assets-5c99df4`), PP-18. Device check pending (MANUAL_TEST PP-17).
  Readability pass (rule 11) not done (owner: Lane B only).

- 2026-11-12 owner PC: **Lane A (M6-A) done.** New Game → monologue (Start skips) → world map (event script, text, camera, highlights, portraits, units, gradient) → "Prologue" title card → castle → player phase, all wired through Fe8Boot; 872 tests green, Dokka clean, 1034/7732 verified (13.3%). Device check of the whole opening pending (MANUAL_TEST D11-wire-b / D11-wm9 entries). Lane A rows in TASKS are empty; next lane-A-like work comes from the device check.

- Owner PC 11-11 (15:23–16:50Z): PP-5..8, PP-10, PP-12, PP-13, PP-16, D11-ci3c, D13-cap1, D13-1, D13-2 merged; 4 readability audits;
  854 tests, 1 local failure (TEST-2, passes in CI); 944/7732 verified. WIP: `wip/PP-11`; D11-wm8a analysis in D11-worldmap §8a.
- ROM repo has no Actions minutes until ~2026-12-01 (owner, 11-11): publish new captures locally with
  `gh release create assets-<sha> --target <full sha> --latest` and publish.yml's file list (done for `assets-bd66e15`, `assets-8a14ce3`). Every ROM-repo commit message
  must contain `[skip ci]`: a push touching `dist/` otherwise starts publish.yml (owner 11-12: no ROM Actions).
  Both ROM workflows (publish-assets, decomp-map) are disabled since 11-12; re-enable with `gh workflow enable` after ~12-01.
- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published **0.1.598 (`8605040`)**: CI **830 passed, zero failures/skips**; debug/release APK content
  guards and benchmark passed. CI retains only this build's results/Dokka artifacts and rolling APK release.
  Rapier popup icon/cleanup fixes remain included (first in 0.1.528).
- Current source: CI 598 reports **895 verified of 7,732 in scope (11.5%)**; 620 link/debug functions
  are out of scope (8,352 total). `ported-without-tag: 0`, `unknown-verifies-names: 0`.
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
| D-11 cinematic | **Confirmed by the owner on 0.1.622 (11-12):** monologue, Magvel world-map exposition and Start in the monologue (skip to the exposition with a fade-in) are correct. Follow-up D-14. |
| D-14 WM skip fade | **Fixed, device check pending (`d349bd9`):** Start during the world-map exposition while a portrait was shown left a stale face proc that threw, freezing the game dark; the skip now also cuts to black at once as the original. `WorldMapSkipBootTest`. |
| D-15 class reel | **Stand-in, device check pending (`d349bd9`):** title idle no longer hangs on black; the class reel (needs battle animations, M6-G) is skipped and the opening animation follows, as after a played reel. `TitleIdleBootTest`. |
| D-09 Rapier popup | **Resolved:** owner confirmed the popup correct on 0.1.571 (11-11); reference: `WiredItemPopupTest`/`GotItemPopupDifferentialTest`. Other popup branches stay D09-3 (low priority). |
| D-13 Start in cutscene | **Open (owner, 0.1.590, 11-11):** Start during the Prologue in-game cutscene fast-forwards visibly instead of skipping. Triage: `EventEngine_StartSkip` sets the skip bit but its effects (`StartLockingFadeToBlack(0x40)`, `sub_800D488`, `Proc_BlockEachMarked(5)`) sit behind the no-op `startSkipEffects` hook. Rows D13-cap1, D13-1. |
| PP-0a hover cursor | **Confirmed by the owner on 0.1.622 (11-12):** the cursor grows/shrinks over units and player units play their hover animation. |
| D-05 interactions | Expected missing gameplay; re-test when player actions are ported. |

D-01–04, D-06, D-08, D-10 and normal portrait mouths are owner-confirmed solved.
Earlier device checks cover boot/import/controls/GPU/suspend-resume. Do not request routine repeats of solved checks.

## Next work

Plan re-done 2026-11-11 (owner-approved): [ROADMAP](docs/plans/ROADMAP.md) has the milestones and task rules;
[TASKS](docs/TASKS.md) has the open rows. Tasks are capture-first: CAP → INV → PORT (≤ ~8 functions the capture
window runs) → WIRE. Two lanes run in parallel; take the first `todo` whose deps are done in either.

1. **Lane A, main line (M6-C → M6-D → M6-E), broken into rows 11-12.** M6-C `MC-*` (map combat), M6-D `EP-*`
   (enemy phase), M6-E `PC-*` (Prologue completable). First: `MC-cap1`, `MC-0`, `EP-0` (no deps). Call chains and
   capture plans: [M6C-J-breakdown](docs/plans/M6C-J-breakdown.md). Lane A's opening is done (device check pending).
2. **Lane B, player phase (M6-B): code-complete 11-12** (PP-11, CAP-status1, PP-18, PP-17); owner device check pending
   (MANUAL_TEST PP-17). Then M6-F info
   screens (`IS-*`). Notes: [M6B-player-phase](docs/plans/M6B-player-phase.md). M6-G to M6-J have starter rows only.
   Owner-approved 11-12: this lane split, and the Easy-mode tutorial (PC-cap3, PC-10) as the end of M6-E.
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

- CI storage: keep only the latest successful master build (rule 15); `ci.yml` replaces older Actions
  artifacts after results, Dokka and the rolling APK release are available. Branch builds upload no artifacts.
  Initial cleanup: 727→14.9 MiB, two artifacts for CI 598; local Python checks: 53 passed.
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
