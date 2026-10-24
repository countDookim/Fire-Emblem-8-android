# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Newly testable — build 0.1.x (milestone 1)
Nothing game-related yet. This APK only proves the Android shell, GL path and loop work on your phone.

- [ ] App installs and launches; the launcher shows *"Fire Emblem: The Sacred Stones — Kotlin port"*,
      a build string `0.1.<run> (<sha>)` and `59.7275 Hz logic`.
- [ ] Tap **Play (test pattern)**: a green/blue vertical gradient fills a centred 3:2 area with black
      bars at the sides (landscape), and slowly scrolls. No stretching, no flicker.
- [ ] Rotate the phone / lock and unlock: the app does not crash and the pattern resumes.
- [ ] Battery/heat after ~2 minutes on the pattern screen feels normal.

## Known not working (don't report)
Everything gameplay-related: no title screen, sound, input or saves yet.
