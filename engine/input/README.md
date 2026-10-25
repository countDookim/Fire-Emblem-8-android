# engine:input

Abstract `GameAction`s (Confirm, Cancel, Move(dir), SelectItem(n), ...), per-tick `InputFrame` bitmasks,
interchangeable `ActionSource`s (buttons, gamepad, the on-screen GBA button overlay, replays; touch
gameplay UIs are left to mods, see docs/decisions/0009) and the 1:1 `VanillaButtonMap` used to
feed identical inputs to mGBA and the port. Planned (M3): key-repeat semantics ported from the decomp,
UI element bounds for touch hit-testing (mod groundwork).
