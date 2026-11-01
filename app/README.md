# app

Android shell (minSdk 26, target/compile 36); built only with an Android SDK (CI). Signed with the committed
`debug.keystore` so CI APKs update in place.
- `MainActivity` (Compose launcher, one **Play** button) → `GameSurfaceView` (GL ES 3.0, `GameLoop` on the GL
  thread; `GpuBackend` composites 240×160 in a shader, then nearest scaling) + `TouchOverlayView` (on-screen GBA
  buttons inside the system bar / cutout insets, cross D-pad).
- Sound: `Fe8Sound` (m4a, ROM sound windows) mixed in the game's VBlank → `PcmStream` → `AudioThread` →
  `AudioTrackSink` (low-latency `AudioTrack`, 48 kHz).
- `HardwareKeySource`: keys/gamepad. `AssetLayer`: VFS `base` over APK assets (debug: ROM-repo extracts incl.
  `sound/`; empty in release, ADR 0011).
- Planned: ROM import (M4-04), CGB/PSG sound (M3-13).
