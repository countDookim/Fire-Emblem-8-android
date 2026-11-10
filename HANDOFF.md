# HANDOFF

_Updated 2026-11-10. Read GPT.md / CLAUDE.md / GEMINI.md for session rules.
Decisions: [PROJECT_BRIEF](docs/PROJECT_BRIEF.md). Backlog: [TASKS](docs/TASKS.md).
Reference details: [M5](docs/plans/M5-notes.md), [M6](docs/plans/M6-notes.md)._

## NEEDS USER

Nothing blocks development. Emulator comparison of D-12 is pending; see [MANUAL_TEST](docs/MANUAL_TEST.md).

## In progress

- D09-wired-check: owner PC, until ~12:06Z — full popup integration/reference regression and fixes.
- D11-boundary: owner PC, until ~12:06Z — original callback-phase captures at scene setup (2355–2356).
- READ-event: owner PC, until ~12:06Z — event package readability review.

## Current state

- Latest owner test: **local 0.1.488 (`0a73f11`)**: neither reported castle audio stutter reproduced.
  Original save restored afterward; ROM import preserved. Published 0.1.488 (`8c5b7c9`) banner comparison remains partial.
- Published replacement: **0.1.497 (`42dc059`)**, CI **706 tests, zero failures/skips**. Local full
  `jvmTest test` also passed 706; both APK content guards, game/audio Dokka, 27 Python tests and instruction sync passed.
- **~650/8352 functions verified** (643 + 7 popup ports 11-10 + 2 GotItem; CI updates the count), `ported-without-tag: 0`; 897 EventScr scripts reproduce ROM bytes at runtime.
  Local full `jvmTest test` 723 tests green (11-09 late, popup ports). Most of the count recovery credits existing scheduled ports after their test helpers were moved below tagged methods.
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

1. **D-11:** standalone `OpSubtitle` parent/flare scripts are transcribed (`c9b49b9`), not wired.
   Generic fade-in is now real: `gbafe.render.PaletteFade.archiveCurrentPalettes`/`writeFadedPaletteFromArchive`
   (`8b8c5b0`, r01230/r01260 full palettes) and `ArchivedPaletteFade` (`NewFadeIn` proc, `2e0e4ff`, 64-frame timer
   sequence). The 1214–2354 replay uses it (`36bfd2c`) and matches 510/512 palette entries at r01230/r01260; entries
   273/417 (0x0842) come from unmodelled code. White/partial-mask branches and `FadeInOut_DisableGfx` unverified.
   Next: phase-aware captures at the 2355–2356 scene-setup boundary; see
   [M6 notes](docs/plans/M6-notes.md#d-11-remaining-subtitle-verification-2026-11-09). Full later lifecycle,
   normal termination and original Start-skip captures remain before wiring. Monologue steps 1213–4897;
   the world-map wrapper at 4898–9313 is separate work.
2. **D-12:** owner compares the banner with an emulator; D12-2 adds enemy-phase and original scanline captures.
3. **D-09:** `D09-1b` (`b25217b` / `e09709d`) and `D09-2` (`075fabb`: `DrawUiFrame2`, `LoadUiFrameGraphics`, `LoadIconPalette`,
   `LoadIconObjectGraphics` verified vs `ram_trace_item_popup` `f18630`; `GetIconGfxTileIndex` transcribed) are done.
   `D09-3a` (`cdf44d3`): `fe8.game.ui.Popup` with verified `PopupProc_Init`/`PopupProc_WaitForPress`
   (item_popup per-step ewram; positive clock only); `NewPopupCore`/`NewPopup_Simple` transcribed.
   11-09 late: `ParsePopupInstAndGetLen`, `GetItemName`/`GetItemNameWithArticle`/`GetItemIconId` (`f748e13`),
   `GeneratePopupText` (`d7ce616`), `PopupProc_GfxDraw` (`5c738f2`, ResetText's special-char reset not modelled) and
   `PopupProc_GfxClear` (`3638c8f`; width+1 column from TmFillRect asm, not distinguished by the capture) verified vs
   item_popup. OP_NUM/unit-name/wtype paths unexercised. 11-10: `PopupProc_PrepareGfx`, `ResetIconGraphics`, `PopupProc_Maybe{Set,Reset}Volume` (`f8ca29f`; window bits/icon
   tables seeded, blend real), `fe8.game.ui.PopupScript` hosting `ProcScr_Popup` on `ProcEngine` with verified
   `NewPopupCore`/`NewPopup_Simple` (`7a2c7e9`, per-step scrCur/lifetime) and `PopupIconUpdateProc_Loop` (`6acd49c`,
   OAM every 10th frame). `PlaySound` transcribed; BGM-volume child is a test stand-in; negative-clock key path
   untested. 11-10: D09-4 done: `GotItemPopup` (`ProcScr_GotItem`/`NewPopup_ItemGot`, transcribed, fake-driven test) and
   Event37 GIVEITEMTO now starts it blocking from Fe8Boot (lazy `PopupScript`). Stand-ins: `unitNameTextId`=0 (gpPopupUnit
   untracked), `loadIconPalette` slot→`ItemIcon` mapping not trace-checked, full inventory still throws, gold popup not wired.
   D09-5: `ItemGot_DisplayLePopup`/`ItemGot_GotLeItem` verified vs item_popup (`GotItemPopupDifferentialTest`, steps
   18589–18731, break-checked); `NewPopup_ItemGot` stays transcribed (birth frame outside capture, red/root paths unexercised).
   Next: owner device check of the Rapier popup (MANUAL_TEST).

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
