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
- **KMP rebuild (no new feature, 2026-11-04):** the game libraries are now Kotlin Multiplatform and the APK uses
  their Android builds. Check nothing regressed: launch, Play boots to the title with music and sound effects,
  touch buttons and gamepad work, and ROM import (if you use it) still completes and Play boots from it.

## Known not working (don't report)
Everything after the title: no gameplay or saves; title key presses do nothing yet.
