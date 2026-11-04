# engine:input

FE ports on top of `:gba:input` (`gba.input`: actions, `InputFrame`, `TouchButtons`, `UiElements`).
- `KeyStatus`: port of `KeyStatusBuffer` + `gKeyStatusIgnoredSt` (`src/hardware.c`: `_UpdateKeyStatus`,
  `UpdateKeyStatus`, `ResetKeyStatus`); byte-identical to mGBA per frame (`KeyStatusDifferentialTest`).
- `KeyComboReset`: soft-reset key combo.
