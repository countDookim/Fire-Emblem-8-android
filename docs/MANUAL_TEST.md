# Manual testing

Latest owner test: **local 0.1.488 (`0a73f11`), 2026-11-09**.
Castle stutter fix published in **0.1.497 (`42dc059`)**; no additional castle re-test is needed.
Popup candidate: **0.1.528 (`e0187ea`), CI pending**. Wait for this build; published 0.1.526
does not include its icon/cleanup fixes.

- **D-07 resolved on this build:** the owner heard neither castle stutter; the retest trace had zero
  repeated PCM frames. The movement-map frontier replaced repeated full-map scans.
- **D-12 partially confirmed on published 0.1.488 (`8c5b7c9`):** the Player Phase banner appears
  and looks correct; emulator comparison is pending.

## Test now

Compare the **Player Phase banner** with original FE8 on an emulator, using Normal difficulty:

1. Start a fresh New Game. If a suspend auto-resumes, clear app data and re-import your ROM.
2. Play through the opening to the banner after the final dialogue.
3. Compare its position, entrance/exit timing, squares and translucency, then check that it clears
   cleanly for map music and cursor control. Report differences with the launcher build string.

D-09 (low priority; candidate above): when Seth gives Eirika the Rapier before the first battle,
check that the popup shows its text and item icon, plays the jingle, then closes automatically
without leaving the frame or icon behind. Report layout/colour/timing differences and the launcher
build string. The wired path now has reference checks; device confirmation is still pending.

D-12 stays pending until this comparison; enemy/green banners are not ready for verification.

## Currently ignore

These are known gaps; re-test them only when a relevant change is announced:

- D-11: missing cinematic between New Game and the castle scene; subtitle code is not wired yet.
- Missing Seth/Valter battle animation, unit selection/actions/menus/inventory, and main/save/difficulty menus.
  Cursor movement works; New Game currently uses Normal difficulty.

Previously confirmed: castle audio, dialogue/portraits/blinking/mouths, backgrounds, walking and rider exits;
boot, ROM import, controls, GPU rendering and suspend/resume. Report regressions if noticed.

Install `fe8-debug.apk` from the [latest release](../../../releases/tag/latest); every build requires your ROM.
Implementation status: [HANDOFF](../HANDOFF.md).
