# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Passed (owner)
- 2026-10-30: install and launch, boot to the title (H&S, logos, op anim, title) with the original timing,
  gamepad/keyboard, on-screen touch buttons (D-pad diagonals, multi-touch).
- 2026-11-01: quick app open, GPU renderer (same as plain Play), test tone, tone button label (#2).

## Newly testable
- [ ] (debug APK) **Test music (opening)** (launcher, scroll down if needed): the opening theme plays as in
      the original (tempo, instruments; Game Boy square-wave/noise voices, if any, are still missing), without
      crackles; stops on **Stop test music**, when you switch apps and when you press Play. Report anything
      that sounds wrong.

## Known not working (don't report)
Everything after the title: no gameplay, sound or saves; title key presses do nothing yet.
