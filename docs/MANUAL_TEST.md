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

## Newly testable
- Touch layout (#3): in Play, landscape, every button is fully visible (none under the camera notch, status bar
  or gesture bar) with a small margin; the D-pad is a cross with arrows (no "+" square). Pressing the D-pad does
  nothing visible until gameplay (boot and title ignore directions).
- PSG sound (new, approximate): the opening theme now has its square-wave (PSG channel 1) voice.
  Report if it sounds wrong (harsh, too loud/quiet vs the music, stuck notes), ideally
  compared with an emulator.

## Known not working (don't report)
Everything after the title: no gameplay or saves; title key presses do nothing yet.
