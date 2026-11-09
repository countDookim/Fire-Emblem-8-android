# Manual testing

Latest owner test: **0.1.488 (`8c5b7c9`), 2026-11-09**.

- **D-07 remains open:** both castle audio stutters are unchanged. The PCM startup reserve did not fix them.
- **D-12 partially confirmed:** the Player Phase banner appears and looks correct; emulator comparison is pending.

## Test now

Compare the **Player Phase banner** with original FE8 on an emulator, using Normal difficulty:

1. Start a fresh New Game. If a suspend auto-resumes, clear app data and re-import your ROM.
2. Play through the opening to the banner after the final dialogue.
3. Compare its position, entrance/exit timing, squares and translucency, then check that it clears
   cleanly for map music and cursor control. Report differences with the launcher build string.

D-12 stays pending until this comparison; enemy/green banners are not ready for verification.

## Currently ignore

These are known gaps; re-test them only when a relevant change is announced:

- D-07's two audio stutters: already reproduced on 0.1.488; no repeat test needed on this build.
- D-11: missing cinematic between New Game and the castle scene; subtitle code is not wired yet.
- D-09: missing Rapier-received popup (low priority). Device inventory remains unverified.
- Missing Seth/Valter battle animation, unit selection/actions/menus/inventory, and main/save/difficulty menus.
  Cursor movement works; New Game currently uses Normal difficulty.

Previously confirmed: dialogue/portraits/blinking/mouths, backgrounds, walking and rider exits;
boot, ROM import, controls, GPU rendering and suspend/resume. Report regressions if noticed.

Install `fe8-debug.apk` from the [latest release](../../../releases/tag/latest); every build requires your ROM.
Implementation status: [HANDOFF](../HANDOFF.md).
