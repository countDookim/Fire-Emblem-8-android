# 0008 — Render model: typed tile layers + sprite batches

**Status.** Accepted 2026-10-26.

**Context.** Frames must match mGBA bit-exactly (4 BGs, priorities, OBJ, blending, brightness, windows, mosaic,
per-line effects) without GBA memory mimicry, with a GPU path. Texture packs are mod-only (0009).

**Decision.** A *semantic* render state filled each tick, two backends:
- `TileLayer` (`ShortArray` tile+palette+flip map, scroll, priority, size, text/affine), `SpriteBatch`
  (fixed-capacity primitive arrays), `PaletteBank` (BG + OBJ BGR555, 2 × 256), `Effects` (blend, brightness,
  windows 0/1/OBJ, mosaic). Tile graphics addressed by sheet + index; no VRAM/OAM/register mirrors.
- Software backend (`gbafe.render`) composes per scanline by hardware rules: the **reference** for mGBA frame
  tests. GPU backend (`:app`, shaders) must match it pixel for pixel on vanilla content.
- **Per-line effects:** ported HBlank handlers fill an optional `LineTable` (160 lines: BG scroll/affine origin,
  window 0/1 x bounds, blend/brightness, a few palette writes). Software applies it per line; GPU uploads it as
  a 160-row data texture (+ 160 × N palette texture). A handler that doesn't fit extends `LineTable`; never a
  raster callback.
- **Texture packs:** vanilla GPU textures are indexed (R8) with palette lookup, so palette animation and fades
  work. Replacements (`TextureOverrides`): *indexed* (any scale, animate like vanilla; recommended) or
  *true-colour* (own colours; optional per-palette-hash variants, else static; blending/fades/windows/mosaic
  still apply). Only the GPU backend uses them.

**Consequences.** Done: types, software rules incl. `LineTable`, override resolver. GPU backend (M3-07, `app/GpuBackend` + `PackedCompositor` spec) done 2026-11-01. Open: drawing texture overrides (boot screens read ROM data).
