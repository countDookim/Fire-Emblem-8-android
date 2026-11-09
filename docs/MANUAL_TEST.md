# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest). Check the launcher build
string when reporting results; an older APK may not contain the latest changes. Every build requires ROM import.
For a fresh New Game test, clear app data and re-import the ROM so an existing suspend does not auto-resume.

## Latest owner test — 2026-11-09

Build **0.1.467 (`060478a`)**:

- Walking animation itself looks correct. Off-screen exits still fail (D-06), and a rare brief stutter remains (D-07).
- Character portraits display correctly during dialogue. Blinking is missing (D-10).

Earlier device checks also confirmed boot to title, original sound, GPU rendering, touch buttons,
gamepad/keyboard, pause/resume, ROM import and close/reopen resuming turn 1.

## Open device issues

| ID | Priority | Observed behavior / re-test target |
|---|---|---|
| D-08 | High | After Seth, Eirika and Franz leave the castle, the background becomes corrupted when Seth and Franz start talking. It worsens afterward and persists for the rest of the run. |
| D-06 | Normal | Franz and Seth should ride off-screen but still stop/remain at the screen edge. Implementing walking animation did not fix this behavior. |
| D-07 | Normal | Units occasionally pause/stutter briefly while walking during the opening, then continue normally. |
| D-10 | Normal | Portraits do not blink. Blinking is expected in FE8: the original Prologue reference contains active blink processes. |
| D-09 | Low (owner) | The Rapier-received popup never appears before the first battle. Eirika's inventory has **not** been checked on device because player-phase interactions are unavailable; do not infer that the item is missing. |

See [HANDOFF.md](../HANDOFF.md) for findings and verification status. Re-test an affected row when a fix
and its APK build are announced; include the launcher build string with the result.

## Known incomplete gameplay

The save menu/difficulty selector and player-phase actions (including inventory access) are not ported yet;
New Game is chosen automatically when there is no valid suspend. Portrait mouth animation is also incomplete.
