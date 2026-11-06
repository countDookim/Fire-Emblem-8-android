# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Confirmed on device (owner)
Install and launch; boot to the title (H&S, logos, op anim, title) with original timing and sound incl. PSG;
GPU renderer; gamepad/keyboard; on-screen touch buttons (cross D-pad, diagonals, multi-touch, safe-area
layout); pause/resume; ROM import (~4 s) and Play from the imported data.

## Newly testable
- **New Game shows the Prologue map (issue #4 fix, 2026-11-06):** APK from 1b207b8+ (fades, map palette) and the
  next commit (music). Steps: Play, START on the title. Expect: within ~1 s the screen fades in on the Prologue map,
  the title theme fades out and the opening event song plays. Known: the opening event keeps running with stubbed
  text/camera/unit commands, so the screen may fade out and in again repeatedly; no dialogue or sprites yet.
- **KMP rebuild (no new feature, 2026-11-04):** the game libraries are now Kotlin Multiplatform and the APK uses
  their Android builds. Check nothing regressed: launch, Play boots to the title with music and sound effects,
  touch buttons and gamepad work, and ROM import (if you use it) still completes and Play boots from it.

## Known not working (don't report)
After the title: no save menu (New Game is chosen automatically), no dialogue, unit or cursor sprites, map music,
fades or player actions yet; extra cutscene units may sit on the map (known gap). Without an imported ROM (debug
assets only) the game stops after the title.
