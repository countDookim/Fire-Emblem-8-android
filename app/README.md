# app

Android shell (minSdk 26, target/compile 36); built only with an Android SDK (CI). Signed with the committed
`debug.keystore` so CI APKs update in place.
- `MainActivity` (Compose launcher) → `GameSurfaceView` (GL ES 3.0, 240×160 texture, nearest scaling, `GameLoop`
  on the GL thread) + `TouchOverlayView` (on-screen GBA buttons).
- `HardwareKeySource`: keys/gamepad. `AssetLayer`: VFS `base` over APK assets (empty in release, ADR 0011).
- Planned: GPU backend (M3-07), `AudioTrack` sink, ROM import (M4-04).
