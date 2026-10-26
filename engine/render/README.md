# engine:render

`FrameBuffer` (offscreen ARGB frame, BGR555 conversion identical to mGBA dumps), `Camera` (viewport not
tied to 240×160), `Renderer` contract, the semantic `RenderState` (tile layers, sprite batches, palettes,
effects, per-line `LineTable`), `TileSheets` (sheet id → GBA-format tile bytes) and `SoftwareRenderer`, the
CPU reference compositor (text BGs, sprites, windows, blending, BG mosaic; affine BGs and OBJ mosaic open). Design: `docs/decisions/0008-render-model.md`.
