# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Passed (owner)
- 2026-10-30: install and launch, boot to the title (H&S, logos, op anim, title) with the original timing,
  gamepad/keyboard, on-screen touch buttons (D-pad diagonals, multi-touch).
- 2026-11-01: quick app open, GPU renderer (same as plain Play), test tone, tone button label (#2), test music.

## Newly testable
- [ ] The launcher has a single **Play** button (GPU renderer). The boot now has sound, as on the GBA:
      silent health & safety and logos, the op anim music starts with the world map, the title theme
      takes over as the red and blue orbs split, a sound effect plays when the main title appears and when
      you press A/Start. Report missing or wrong sounds, crackles, or music running ahead of / behind the
      picture. Known gap: Game Boy square-wave/noise voices are not played yet (some sound effects, e.g.
      the A/Start one, may sound thin or partly missing).
- [ ] Switching apps mid-boot stops the sound; coming back resumes picture and sound together.

## Known not working (don't report)
Everything after the title: no gameplay or saves; title key presses do nothing yet.
