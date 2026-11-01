# engine:input

- `GameAction`, per-tick `InputFrame`, `ActionSource`s (keys, gamepad, on-screen buttons, replays), 1:1
  `VanillaButtonMap` (same inputs to mGBA and the port).
- `KeyStatus`: port of `KeyStatusBuffer` + `gKeyStatusIgnoredSt` (`src/hardware.c`: `_UpdateKeyStatus`,
  `UpdateKeyStatus`, `ResetKeyStatus`); byte-identical to mGBA per frame (`KeyStatusDifferentialTest`).
- `TouchButtons`: on-screen GBA buttons layout + multi-touch, laid out inside system bar / cutout insets
  (drawn by `fe8.app.TouchOverlayView`).
- `UiElements`: element bounds for mod touch hit-testing.
