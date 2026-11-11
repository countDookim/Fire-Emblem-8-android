# Manual testing

Latest owner test: **published 0.1.571 (`a1978d9`), 2026-11-11** (Rapier popup).
Castle stutter fix published in **0.1.497 (`42dc059`)**; no additional castle re-test is needed.
Popup fixes are published in **0.1.528 (`e0187ea`)**.

- **D-07 resolved on this build:** the owner heard neither castle stutter; the retest trace had zero
  repeated PCM frames. The movement-map frontier replaced repeated full-map scans.
- **D-12 player banner confirmed (owner, 2026-11-11, 0.1.488):** the Player Phase banner is correct.
  Enemy/green banners are not ready (D12-2, with M6-D).
- **D-09 resolved (owner, 2026-11-11, 0.1.571):** the Rapier popup is correct.

## Test now

Delete save (APP-1): on the launcher, tap **Delete save** (enabled only when a save exists), then
**Delete** in the "Delete save data?" dialog. The button should disable. Tap **Play**: the game
should start from the title with no resume and no filled save slots. The imported ROM must still be
there (no re-import needed; the launcher still shows the game data file count). **Cancel** must keep
the save. Limits: this deletes all saves and the suspend at once, with no per-slot delete.


## Known differences (owner reports)

- Map cursor over a player unit: original FE8 widens the cursor to frame the unit; the APK keeps the normal
  cursor (owner, 2026-11-11, mGBA comparison). Fix planned as PP-0a.

## Currently ignore

These are known gaps; re-test them only when a relevant change is announced:

- D-11: missing cinematic between New Game and the castle scene (testable after M6-A); subtitle, Start-skip
  and world-map portrait ports remain standalone and unwired. No new device check for these yet.
- Missing unit selection, actions, menus and inventory (M6-B, M6-H), combat (M6-C), enemy turns (M6-D), save/difficulty
  menus (M6-E) and the Seth/Valter battle animation (M6-G). Combat will resolve instantly until M6-G.
  Cursor movement works; New Game currently uses Normal difficulty.

Previously confirmed: castle audio, dialogue/portraits/blinking/mouths, backgrounds, walking and rider exits;
boot, ROM import, controls, GPU rendering and suspend/resume. Report regressions if noticed.

Install `fe8-debug.apk` from the [latest release](../../../releases/tag/latest); every build requires your ROM.
Implementation status: [HANDOFF](../HANDOFF.md).
