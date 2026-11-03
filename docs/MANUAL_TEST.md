# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Passed (owner)
- 2026-10-30: install and launch, boot to the title (H&S, logos, op anim, title) with the original timing,
  gamepad/keyboard, on-screen touch buttons (D-pad diagonals, multi-touch).
- 2026-11-01: quick app open, GPU renderer (same as plain Play), test tone, tone button label (#2), test music;
  single Play (GPU) boot with sound in sync through the title and a restarted op anim, pause/resume.
- 2026-11-01: touch layout inside the safe area, cross D-pad (#3); PSG (CGB) sound in the op theme.

## Newly testable
- PSG (square/wave) sound after the 2026-11-03 fixes: the op theme's PSG parts sound like mGBA/hardware (no
  clicks or missing note restarts); compare with an emulator if you can.

- ROM import (M4-04): launcher "Import ROM", pick your FE8 (U) `.gba`: progress bar, then "Game data imported:
  N files"; a wrong file shows a clear SHA-1 message. Note the time (logcat tag `Fe8Import`, target < ~30 s).
  Play then boots from the imported data (also after reinstalling the debug APK).

## Known not working (don't report)
Everything after the title: no gameplay or saves; title key presses do nothing yet.
