# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Confirmed on device (owner)
Install and launch; boot to the title (H&S, logos, op anim, title) with original timing and sound incl. PSG;
GPU renderer; gamepad/keyboard; on-screen touch buttons (cross D-pad, diagonals, multi-touch, safe-area
layout); pause/resume; ROM import (~4 s) and Play from the imported data.
2026-11-07, APK 5e213f1: KMP rebuild (no regressions), New Game fades in on the Prologue map with the event song
(issue #4), close/reopen resumes turn 1 (M6-12).

## Newly testable
_(nothing new; next device test: after M6-37 wires dialogue into the app)_

## Known not working (don't report)
After the title: no save menu (New Game is chosen automatically), no dialogue, unit or cursor sprites, map music,
fades or player actions yet; extra cutscene units may sit on the map (known gap). Without an imported ROM (debug
assets only) the game stops after the title.
