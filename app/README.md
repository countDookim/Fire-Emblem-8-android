# app

Thin Android shell (minSdk 26, target/compile 36). `MainActivity` (Compose launcher/settings only) and
`GameSurfaceView` (OpenGL ES 3.0: 240×160 texture, aspect-correct nearest scaling, `GameLoop` on the GL
thread). Signed with the committed `debug.keystore` so CI APKs update in place. Only built where an
Android SDK exists (CI). Planned: AudioTrack sink, gamepad/touch `ActionSource`s, settings (vanilla toggle).
