# 0008 — Render model: typed tile layers + sprite batches (accepted 2026-10-26, M3-01)

**Context.** Vanilla frames must match mGBA bit-exactly, which requires reproducing the original's
composition rules (4 BG layers, priorities, OBJ, alpha blend, brightness fades, windows, mosaic,
per-scanline effects). The brief forbids GBA memory-layout mimicry and wants a GPU path plus texture
packs (texture packs are mod extension points only, 0009; the product ships the original graphics).

**Decision.** A *semantic* render state, filled by game systems each tick, consumed by two backends:
- `TileLayer` (map: `ShortArray` of tile+palette+flip; scroll; priority; size; text or affine mode),
  `SpriteBatch` (fixed-capacity primitive arrays: x, y, shape/size, tile base, palette, priority, flip,
  affine index, mode), `PaletteBank` (BG + OBJ BGR555 `ShortArray`s, 2 × 256 colours), `Effects` (blend
  mode/targets/coeffs, brightness, windows 0/1/OBJ + masks, mosaic).
- Software backend (JVM, `gbafe.render`) composes per scanline exactly like the hardware rules. It is the
  **reference**: frame-compare tests run on it against mGBA dumps (M3-06).
- GPU backend (`:app`) draws the same state with shaders and must produce the same pixels as the software
  backend for vanilla content (checked by an instrumented readback test once a device test exists).
No VRAM/OAM byte arrays or register mirrors are exposed to game code; tile *graphics* live in GPU-side or
array-backed tile sheets addressed by sheet + tile index, never by VRAM address.

**Scanline (HBlank) effects.** The original changes state mid-frame from HBlank handlers (window shapes for
transitions/spotlights, wavy scroll, per-line blend/brightness, gradient palette entries). Ported HBlank
handlers do not run as interrupts; instead they fill an optional `LineTable` in the render state before the
frame is composed: per line (160 rows) optional overrides for each BG's scroll x/y (and affine reference
point), window 0/1 horizontal bounds, blend coeffs / brightness, and a small list of palette-entry writes.
- Software backend: applies the line's overrides before composing that line (exact hardware behaviour).
- GPU backend: uploads the `LineTable` as a 160-row data texture each frame (only the columns in use) and
  the fragment shader indexes it by output scanline. Palette writes per line become a 160 × N palette
  texture (N = entries touched; the vanilla game touches few). Cost is one small texture upload per frame
  and zero when no table is active.
- A ported handler that cannot be expressed as table data is a bug in this ADR: extend `LineTable`, never
  add a raster callback into game code.

**Texture packs vs palette animation.** Vanilla GPU textures are *indexed* (R8 colour index per pixel) and
the shader looks colours up in the `PaletteBank` texture, so palette animation, fades and per-line palette
writes work unchanged. Replacement textures (asset override layer, M3-08) come in two kinds:
1. *Indexed* replacements (any scale, still palette indices): behave exactly like vanilla, incl. palette
   animation. Recommended for packs.
2. *True-colour* (RGBA) replacements: drawn with their own colours. A pack may give `paletteVariants`
   (palette content hash → image) for assets whose palette changes; without them the replacement stays
   static while the original would have animated (documented limitation in MODDING.md). Brightness fades,
   alpha blending, windows and mosaic still apply, because they operate on composed colours.
The software backend ignores replacements (it is the vanilla reference); only the GPU backend uses them.

**Consequences.** M3-02 defines the types above (+ `LineTable`), allocation-free per frame. M3-03..05
implement the software rules incl. `LineTable`; M3-07 the GPU path; M3-08 the override resolver with the
two replacement kinds.
