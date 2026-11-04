# gba:render

ADR 0008; package `gba.render` (Kotlin Multiplatform, ADR 0013). GBA PPU model, no FE ports.
- `RenderState` (tile layers, sprite batches, palettes, effects, `LineTable`), `TileSheets`.
- `SoftwareRenderer`: CPU reference. `SpriteRasterizer` (OBJ rules shared by both backends).
- `GpuFrame` (per-line packing for the GLES shader) + `PackedCompositor` (its Kotlin spec).
- `FrameBuffer` (ARGB), `Camera`, `ColorMath`, `BgAffine`, `HBlankLines`.
