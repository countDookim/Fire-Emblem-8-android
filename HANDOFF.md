# HANDOFF

_Updated 2026-11-09. Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Emulator comparison of D-12 is pending; see [MANUAL_TEST](docs/MANUAL_TEST.md).

## In progress

_(none)_

## Current state

- Published and owner-tested: **0.1.488 (`8c5b7c9`)**. Its CI passed: **679 tests, zero failures/skips**;
  both APKs passed the no-game-content guard. Local full `jvmTest test`, per-module Dokka and 27 Python tests passed.
- **598/8352 functions verified**, `ported-without-tag: 0`; 897 EventScr scripts reproduce ROM bytes at runtime.
  README/port map are generated; do not infer verified progress from implementation alone.
- New Game reaches the Prologue opening and turn-1 map; suspend/resume and cursor movement work.
  Player actions, battle animations and the real save/difficulty menus remain unimplemented.
- Layout: game-neutral `:gba:*`, `gbafe.*` in `:engine:*`, FE8 `fe8.*` in `:game`/`:app` (ADRs 0010–0013).
  All APKs require player ROM import (`base/rom/baserom.gba`); no game content is bundled.

## Device status

Close an issue only after reference verification and owner confirmation; preserve stated comparison limits.

| Issue | Latest evidence / status |
|---|---|
| D-07 audio | **Open, unchanged on 0.1.488:** two castle stutters remain. Primitive sprite-cache lookup, zero-allocation steady state and startup PCM reserve did not resolve the reported issue. |
| D-12 phase banner | **Appears correct on 0.1.488; emulator comparison pending.** Presence is confirmed, exact fidelity is not. |
| D-11 cinematic | Open: the subtitle monologue and following world-map opening are unwired. |
| D-09 Rapier popup | Open, owner low priority. Headless inventory grant is checked; device inventory is unknown. |
| D-05 interactions | Expected missing gameplay; re-test when player actions are ported. |

D-01–04, D-06, D-08, D-10 and normal portrait mouths are owner-confirmed solved.
Earlier device checks cover boot/import/controls/GPU/suspend-resume. Do not request routine repeats of solved checks.

## Next work

1. **D-07:** obtain an Android trace correlating the two castle hitches with game-thread stalls,
   PCM starvation and native AudioTrack underruns. Investigation is owner-authorized. Root cause remains unknown;
   do not present the existing reserve (`63e638e`) as a successful device fix or repeat unchanged-build tests.
2. **D-11:** next coding slice is `D11-proc` in TASKS. Done standalone (not wired): slides/fades
   (`014f3ed`, `d3c4112`), `OpSubtitleSetup` init + BG helpers (`a5aec60`), `OpSubtitleFlare` + `gbafe.render.BgAffin`
   (`c2f0be5`). Remaining: proc script, audio, verified Start skip, then wiring. Original monologue runs at steps 1213–4897; the separate world-map wrapper follows at 4898–9313.
3. **D-12:** owner compares the banner with an emulator; D12-2 adds enemy-phase and original scanline captures.
4. **D-09:** private ROM commit `f7a442a` has the popup capture generator; local `reference/out/ram_trace_item_popup`
   contains 181 RAM and 10 graphics captures, not packed/published into FE8_ASSETS. D09-1b must justify its sound-test
   alias from matching provenance, preserve old runs and publish assets before UI/icon/popup implementation.

## Verification limits worth retaining

- D-07: sheet decompression measured **0 B** on HotSpot; the earlier ~11 KB attribution to it was unsupported.
  A cold JVM walk-start frame (zero-based 784) allocated 54,280 B in tick, none in GPU present; exact cause unknown.
  These are not Android timings. Logs: `$HOME/.cache/fe8-tmp/d07-spikes-{baseline,attribution,split,trees}.log`.
  The reserve passes a simulated delayed-callback test but failed the owner's actual stutter re-test.
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
  Serialize Gradle with `.gradle/agent-checks.lock`; use two workers / 2 GiB heap and run Dokka per module.
- Full checks: `./gradlew jvmTest test`. Focused verification uses `:testing:test --tests <class>` (no `:testing:jvmTest`).
  Filtered runs replace result XML: regenerate progress only after the full suite. Put helper classes/enums below
  tagged tests: the progress parser uses the last preceding class declaration. CI normally commits README/port map;
  regenerate/commit them locally when needed. Keep `ported-without-tag: 0` and mutation-check every new port tag.
- ROM bytes/text/glyphs/dumps belong only in the private ROM repo; tests read FE8_ASSETS. New captures must preserve
  old runs, update packed assets and manifest SHA1, then publish/refresh assets; see the private repo README and harness.
  An overrun dump may be mid-frame; account for RAM/OAM sync timing. GPU/shader changes also need shader parity checks.
- Keep assistant instructions synchronized through `docs/AGENT_INSTRUCTIONS.template.md` and
  `tools/sync_agent_instructions.py`. Continue [READABILITY](docs/READABILITY.md) reviews on owner-PC sessions.
- Completed history and older investigation details: `git log`; pre-compression notes: `git show 8c5b7c9:HANDOFF.md`.
