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
- [ ] **Test tone (440 Hz)** on the launcher: a steady beep starts at once (no crackles or gaps, also while
      you tap around), stops at once on **Stop test tone**, and stops when you switch apps.
- [ ] (#2) Start the tone, switch apps and come back: the button reads **Test tone (440 Hz)** again and one
      press starts the beep.
- [ ] (debug APK) **Test music (opening)**: the opening theme plays as in the original (tempo, instruments;
      square-wave/noise parts are still missing), without crackles; stops on **Stop test music**, when you
      switch apps and when you press Play. Report anything that sounds wrong.

## Known not working (don't report)
Everything after the title: no gameplay, sound or saves; title key presses do nothing yet.
