# engine:input

Abstract `GameAction`s (Confirm, Cancel, Move(dir), SelectItem(n), ...), per-tick `InputFrame` bitmasks,
interchangeable `ActionSource`s (buttons, gamepad, the on-screen GBA button overlay, replays; touch
gameplay UIs are left to mods, see docs/decisions/0009) and the 1:1 `VanillaButtonMap` used to
feed identical inputs to mGBA and the port.

`KeyStatus` is the port of the game's `struct KeyStatusBuffer` + `gKeyStatusIgnoredSt`
(`src/hardware.c`: `_UpdateKeyStatus`, `UpdateKeyStatus`, `ResetKeyStatus`): new presses, 12/4-frame
auto-repeat, A/B/L/R release detection, Start/Select idle counter. Byte-identical to mGBA RAM per frame
(`KeyStatusDifferentialTest`, reference run `key_status_title`). `UiElements`: UI element bounds for touch
hit-testing (mod groundwork). `TouchButtons`: the on-screen GBA button overlay's layout and multi-touch state
(M3-15; drawn by `fe8.app.TouchOverlayView`).
