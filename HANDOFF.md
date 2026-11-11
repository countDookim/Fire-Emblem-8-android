# HANDOFF

_Updated 2026-11-11 08:57Z (owner PC: plan review and alignment). Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Plan: [ROADMAP](docs/plans/ROADMAP.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Emulator comparison of D-12 is pending; see [MANUAL_TEST](docs/MANUAL_TEST.md).
Modding decisions D2–D9 in [M7-modding](docs/plans/M7-modding.md) are open (D1 decided: option A, ADR 0015; M6 is not blocked).

## In progress

- READ-bm-battle (READABILITY bm battle files): owner PC, until ~12:30Z
- D11-wm3c: owner PC, until ~12:30Z
- D11-ci1: owner PC, until ~11:45Z
- PP-cap2: owner PC, until ~12:00Z

## Current state

- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published **0.1.551 (`1c30d0b`)**: CI **769 passed, zero failures/skips**. The pinned decomp
  source is now provisioned for node-table tests; missing source fails clearly and invalidates
  stale test-cache results. Rapier popup icon/cleanup fixes remain included (first in 0.1.528).
- Current source: **750 verified of 7,732 in scope (9.6%)**; 620 link/debug functions are out of scope
  (8,352 total). `ported-without-tag: 0`, `unknown-verifies-names: 0`; 897 EventScr scripts reproduce ROM bytes at
  runtime. Full local `jvmTest test` (owner PC 11-11 08:55Z, after the alignment commits): **787 passed, zero
  failures/skips**; Dokka (game, engine:render, engine:core, engine:extract) and the app compile passed.
- New Game reaches the Prologue opening and turn-1 map; suspend/resume and cursor movement work.
  Player actions, battle animations and the real save/difficulty menus remain unimplemented.
- Layout: game-neutral `:gba:*`, `gbafe.*` in `:engine:*`, FE8 `fe8.*` in `:game`/`:app` (ADRs 0010–0013).
  All APKs require player ROM import (`base/rom/baserom.gba`); no game content is bundled.

## Device status

Close an issue only after reference verification and owner confirmation; preserve stated comparison limits.

| Issue | Latest evidence / status |
|---|---|
| D-07 audio | **Resolved for both reported castle events:** owner heard neither hitch on local 0.1.488 (`0a73f11`); normal trace PCM repetitions fell 4495→0 after the movement frontier change. Earlier cache/reserve changes alone failed. |
| D-12 phase banner | **Appears correct on 0.1.488; emulator comparison pending.** Presence is confirmed, exact fidelity is not. |
| D-11 cinematic | Open: the subtitle monologue and following world-map opening are unwired. |
| D-09 Rapier popup | Open, owner low priority. Headless inventory grant is checked; device inventory is unknown. |
| D-05 interactions | Expected missing gameplay; re-test when player actions are ported. |

D-01–04, D-06, D-08, D-10 and normal portrait mouths are owner-confirmed solved.
Earlier device checks cover boot/import/controls/GPU/suspend-resume. Do not request routine repeats of solved checks.

## Next work

Plan re-done 2026-11-11 (owner-approved): [ROADMAP](docs/plans/ROADMAP.md) has the milestones and task rules;
[TASKS](docs/TASKS.md) has the open rows. Tasks are capture-first: CAP → INV → PORT (≤ ~8 functions the capture
window runs) → WIRE. Two lanes run in parallel; take the first `todo` whose deps are done in either.

1. **Lane A, opening wired (M6-A, D-11).** Start: `D11-wire-a` (skip audio), `D11-wm3b..e` (remaining
   `WorldMap_Init` callees), `D11-cap1`, `D11-ci0`. Status so far: [D11-worldmap §8](docs/plans/D11-worldmap.md),
   [M6 notes](docs/plans/M6-notes.md). All monologue/world-map ports are standalone until `D11-wire-b`/`D11-wm9`.
   Restore the default face VRAM/palette configuration at the world-map exit (`D11-wm8f`).
2. **Lane B, player phase (M6-B, issue #5 item 5).** Start: `PP-cap1..3` (ROM repo captures from `prologue.sav`),
   then `PP-0` (inventory), which fixes the function lists of `PP-1..16`.
3. **Soon (owner request):** `APP-1`, a Delete save button in the launcher (keeps the imported ROM).
4. **Alignment done 2026-11-11:** the progress bar counts in-scope functions only; generated lists
   [UNVERIFIED](docs/plans/UNVERIFIED.md) (written but unverified code) and [STANDINS](docs/plans/STANDINS.md)
   (placeholders by milestone) are checked in CI: regenerate both with `--write` after changing such comments.
5. **Owner:** compare the D-12 banner with an emulator; check the Rapier popup (D-09, low priority); decide
   modding questions D2–D9 when M7 nears.

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
