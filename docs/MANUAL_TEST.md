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
Prologue opening after M6-29: tested by the owner 2026-11-07 (APK 3380240 vs an emulator). No extra cutscene
units on the map any more; four problems found, tracked as D-01..D-04 in HANDOFF "Device issues" (re-test each when
it is marked `fix merged, unverified`). Clear the app's data before a re-test so an old suspend save is not resumed.
Re-test by the owner 2026-11-08 (APK after d01eb4c): D-01 dialogue, D-02 cutscene units, D-03 Seth/Eirika talk +
Seth's move, D-04 enemy colours all fixed as described. New: D-06, in the cutscene outside the castle Franz (and later
Seth) ride off the map but teleport to the map edge and wait a while before disappearing (walk animation not ported).

2026-11-09, next APK: clear app data, import the ROM, choose Play and start New Game. The first dialogue should
show both portrait sprites fading in. Portrait OAM and the fade-in palette have reference tests; a device check is
still needed. The opening's brown text box should also appear with its text. For D-06, re-check Franz and Seth
riding off the castle map; the input-aligned headless run now matches the original movement and one MU OAM dump,
but this does not establish that the reported device behavior is fixed.

## Known not working (don't report)
After the title: no save menu (New Game is chosen automatically); portrait blink/mouth animation and fade-out
are incomplete; player-phase actions are not ported yet. D-06 still needs device confirmation. Every build
requires an imported ROM.
