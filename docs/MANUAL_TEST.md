# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Passed (owner, 2026-10-30)
Install and launch, boot to the title (H&S, logos, op anim, title) with the original timing, gamepad/keyboard,
on-screen touch buttons (D-pad diagonals, multi-touch).

## Newly testable
- [ ] The app opens quickly: the launcher appears at once and the bundled-asset count fills in after
      ("counting…" first). Before, opening took a while.
- [ ] **Play (GPU renderer, experimental)** on the launcher: the boot screens and title look exactly like
      with plain **Play** (same colours, fades, scrolling menu background), and run as smoothly. Report any
      difference, a black/garbled screen or a crash, with the phone model.

## Known not working (don't report)
Everything after the title: no gameplay, sound or saves; title key presses do nothing yet.
