# engine:data

`DataTable` contract for schema-validated JSON compiled to primitive arrays (ADR 0002; loader not yet built),
plus load/extract-time decoders (never per frame):
- `Lz77` (BIOS `LZ77UnCompWram`/`Vram`), `Decompress`, `Tsa`, `TrigTable`, `JsonReader`.
- `Tiles` + `IndexedImage`: 4bpp/8bpp 8×8 tiles ⇄ palette-index image (4bpp: low nibble = left pixel); indices
  kept exactly so palette swaps and colour-0 transparency behave as vanilla.
- `Bgr555`: GBA colour ⇄ ARGB8888 (`c << 3`, exact inverse on expanded colours).
