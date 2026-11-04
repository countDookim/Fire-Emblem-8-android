# engine:render

ADR 0008. FE ports on top of `:gba:render` (PPU model, `gba.render`): `LcdControl` (`gLCDControlBuffer` + `FlushLCDControl`;
`applyTo(RenderState)` decodes like the PPU), `HBlankHandlers`, `BgHardware`, `OamBuffer`, `HardwareScene`, fades.
