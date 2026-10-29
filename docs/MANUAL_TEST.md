# Manual test checklist

Install `fe8-debug.apk` from the [`latest` release](../../../releases/tag/latest) (allow "install unknown
apps" for your browser once). Each new APK installs over the previous one (same signing key).
Report anything wrong as a GitHub Issue with label **`user-report`** (include the build string shown
on the launcher screen).

## Newly testable — build 0.1.x (milestone 1)
Nothing game-related yet. This APK only proves the Android shell, GL path and loop work on your phone.

- [ ] App installs and launches; the launcher shows *"Fire Emblem: The Sacred Stones — Kotlin port"*,
      a build string `0.1.<run> (<sha>)` and `59.7275 Hz logic`.
- [ ] Tap **Play** (release build or no bundled assets): a green/blue vertical gradient fills a centred 3:2 area with black
      bars at the sides (landscape), and slowly scrolls. No stretching, no flicker.
- [ ] Rotate the phone / lock and unlock: the app does not crash and the pattern resumes.
- [ ] Battery/heat after ~2 minutes on the pattern screen feels normal.
- [ ] (M4-09) The launcher shows *"Bundled test assets (debug builds only): N files"* with N in the
      thousands (the extracted game data travels in this test APK).
- [ ] (M3-10) With a Bluetooth/USB gamepad or keyboard: pressing keys on the test-pattern screen does
      not crash and is not swallowed by Android (Back on a gamepad's B does not leave the app). Input has
      no visible effect yet (no game screen consumes it).

- [ ] (M4-11, partial) Tap **Play**: instead of the test pattern, the boot runs like the original: health &
      safety screen (press **A** on a gamepad/keyboard to continue, or wait), Nintendo logo, Intelligent
      Systems logo with their fades. The op anim and title then run too, but are known to differ from the
      original (timing/graphics; being fixed): only report crashes there.

## Known not working (don't report)
Everything gameplay-related: no title screen, sound, input or saves yet.
