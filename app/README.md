# app

Android shell (minSdk 26, target/compile 36); built only where an Android SDK is found (CI, owner PC). Signed with the committed
`debug.keystore` so CI APKs update in place.
- `MainActivity` (Compose launcher: ROM import, **Play**) → `GameSurfaceView` (GL ES 3.0, `GameLoop` on the GL
  thread; `GpuBackend` composites 240×160 in a shader, then nearest scaling) + `TouchOverlayView` (on-screen GBA
  buttons inside the system bar / cutout insets, cross D-pad).
- `RomImport`: document-picker ROM → SHA-1 check → `:engine:extract` into `filesDir/base/` (ADR 0011).
- Sound: `Fe8Sound` (m4a, ROM sound windows) mixed in the game's VBlank → `PcmStream` → `AudioThread` →
  `AudioTrackSink` (low-latency `AudioTrack`, 48 kHz).
- `HardwareKeySource`: keys/gamepad. `AssetLayer`: VFS `base` over APK assets. No build bundles game data (ADR 0011;
  `tools/check_no_game_content.py` in CI), so it is empty; the imported `filesDir/base/` is used when current.
