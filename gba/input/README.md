# gba:input

Package `gba.input`; GBA keys and touch overlay, no FE ports.
- `GameAction`, per-tick `InputFrame`, `ActionSource`s (keys, gamepad, on-screen buttons, replays), 1:1
  `VanillaButtonMap` (same inputs to mGBA and the port).
- `TouchButtons`: on-screen GBA buttons layout + multi-touch, laid out inside system bar / cutout insets
  (drawn by `fe8.app.TouchOverlayView`).
- `UiElements`: element bounds for mod touch hit-testing.
