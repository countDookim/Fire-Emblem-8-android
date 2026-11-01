# engine:render

ADR 0008. `RenderState` (tile layers, sprite batches, palettes, effects, `LineTable`), `TileSheets`.
- `SoftwareRenderer`: CPU reference (text/affine BGs, sprites, windows, blending, BG mosaic; OBJ and affine-BG
  mosaic open).
- `LcdControl`: port of `gLCDControlBuffer` + `FlushLCDControl`; `applyTo(RenderState)` decodes like the PPU.
  `HBlankLines`/`HBlankHandlers`: per-line effects.
- `GpuFrame` (per-line packing for the GLES shader) + `PackedCompositor` (its Kotlin spec; parity with
  `SoftwareRenderer`), `SpriteRasterizer` (OBJ rules shared by both).
- `FrameBuffer` (ARGB; BGR555 conversion as in mGBA dumps), `Camera` (not tied to 240×160).
