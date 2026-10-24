# 0008 — Render model: typed tile layers + sprite batches (proposed)

**Context.** Vanilla frames must match mGBA bit-exactly, which requires reproducing the original's
composition rules (4 BG layers, priorities, OBJ, alpha blend, brightness fades, windows, mosaic,
per-scanline effects). The brief forbids GBA memory-layout mimicry and wants a GPU path plus texture
packs.

**Proposal.** A *semantic* render state, filled by game systems each tick, consumed by two backends:
- `TileLayer` (map: `ShortArray` of tile+palette+flip; scroll; priority; per-line scroll table),
  `SpriteBatch` (fixed-capacity arrays: x, y, frame id, palette, priority, flip, affine), `PaletteBank`
  (BGR555 `ShortArray`, with fade/blend state), `Effects` (blend mode/coeffs, windows, mosaic).
- Software backend (JVM/tests) composes per scanline exactly like the hardware rules → frame compare.
- GPU backend (`:app`) draws the same state with shaders; texture packs replace tiles/sprites by asset
  path and scale; vanilla keeps indexed-palette behaviour.
No VRAM/OAM byte arrays or register mirrors are exposed to game code.

**Open questions.** Scanline (HBlank) effects on the GPU path; how texture packs interact with
palette animation. To be decided in milestone 3 (task M3-01) before acceptance.
