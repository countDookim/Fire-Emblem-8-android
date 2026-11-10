# HANDOFF

_Updated 2026-11-10 (owner-PC wrap-up). Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Emulator comparison of D-12 is pending; see [MANUAL_TEST](docs/MANUAL_TEST.md).

## In progress

None. `wip/D11-wm6` is merged into master (`b027750`); the remote branch can be deleted.

## Current state

- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published **0.1.551 (`1c30d0b`)**: CI **769 passed, zero failures/skips**. The pinned decomp
  source is now provisioned for node-table tests; missing source fails clearly and invalidates
  stale test-cache results. Rapier popup icon/cleanup fixes remain included (first in 0.1.528).
- Current source: **728/8352 verified**, `ported-without-tag: 0`; 897 EventScr scripts reproduce
  ROM bytes at runtime. Full local `jvmTest test` (scheduled, after wm6/wm4c): **784 passed, zero failures/skips**; earlier both APK
  builds/content guards, game Dokka, 27 Python tests and instruction sync passed. New source CI pending.
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

1. **D-11 monologue:** standalone replay now follows the original parent 1214–4897 (`9e20898`):
   long scene-setup callbacks at 2355–2356, 2425–2427, 3228 are compared at completion; palette and
   BG0/BG1 maps every 30 steps match (entries 273/417 unmodelled). Closing slow fade-to-black is the real
   `ScreenFade` and verified (`c40863d`). Start skips at 1505/2375/4405 now match original parent/child cleanup and real fast-fade
   state through termination (`797ee45`, private `48f35a1`); skip audio and the GameControl
   bridge still need checks before wiring (D11-wire). See [M6 notes](docs/plans/M6-notes.md).
2. **D11 world map** (steps 4898–9313, [plan](docs/plans/D11-worldmap.md)), all standalone/unwired:
   - wm3: `WorldMap_Init` spans 4899–4901 as one multi-frame callback (no yield; `b9ba3b6`); `sub_80B8FEC`
     verified; `WorldMapBeginning` gives the real Prologue hooks (event lookup, ResetGmStoryNode,
     CallEvent) and matches the `StdEventEngine` at 4907 (`c7ed068`). Stand-ins: `sub_80BA008`,
     `StartGmapMuEntry`; the optional portrait controller bridge is available. Boot wiring remains.
   - wm4 (`a3214a6`): GmapRM BG procs, BG1 scroll/tilemaps and Event85 camera verified; Prologue uses no
     other camera opcode. Remaining: opcodes B4–B7, blend paths, proc end.
   - wm4a/b (`5e9722e`, `7a6f911`): `GmapRmBaPalAnim1` transcribed; `GmapRmBorder1` proc logic (80C28C4/2964/29F8/2A1C)
     verified per step on the procs watch (nation 7, 5528–5670; 18 capped 1-frame resyncs asserted). Blend, OAM,
     palette 0x19 and VRAM effects, MergeIn/Out and Start/Show are unverified: no dump falls inside the runs
     (needs a per-step lcd/OAM watch capture). Unwired; nation-highlight event opcode not registered.
   - wm5 (`807369a`): text handler verified; GmPalFade and opcodes 80–82/AF–B3 transcribed, unregistered.
   - wm7 (`90811ce`): 11 portrait functions verified/mutation-checked against original proc, BG,
     palette, VRAM and OAM state. Init writes remain transcribed; observed state-3 path only.
     Event C4/C5 and full VM timing remain unverified/unregistered.
   - wm6 (scheduled 2026-11-10, `b027750`): `WorldMapUnitTest` verifies 13 GmapUnit/container functions
     (fields per step, 4890–9310); u16/s8/u8 narrowings untested. Loop/OAM, fades, palette ids, scripts and
     opcodes 9E–AC remain transcribed/unregistered.
   - wm4c (`c2025c8`): events B4–BA (big map, nation highlight) transcribed with unit tests; unverified, unregistered.
   - wm8 (end-to-end + registration) follows. Restore default face
     VRAM/palette configuration at the world-map exit before sharing TalkFace with battle dialogue.
   `@VerifiesPort` takes bare function names; `"src/x.c: F"` silently counts nothing (fixed in wm4b/wm6 tests).
   Node table checks use pinned runtime source: `tools/setup_decomp.sh` or `FE8U_DECOMP` (read-only).
3. **D-12:** owner compares the banner with an emulator; D12-2 adds enemy-phase and original scanline captures.
4. **D-09:** item popup is wired through Event37, GotItemPopup and PopupScript. `539016e` fixes the
   omitted icon OAM callback and missing BG sync on close. The real boot-path test checks visible
   icon/palette/glyph/text state, blocking volume children, cleanup and final inventory against
   `ram_trace_item_popup`; silent audio and state alignment do not establish full-scene/audio timing.
   Next: owner checks the popup (low priority). Red/root/full-inventory and negative-clock paths,
   unit-name/number/wtype instructions and gold popups remain unverified or unwired.

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
