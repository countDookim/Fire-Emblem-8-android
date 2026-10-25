# engine:data

Compiles versioned, schema-validated JSON tables from the VFS into primitive arrays with typed accessors.
Currently only the `DataTable` contract. Design: `docs/decisions/0002-data-schemas.md`. Tables arrive in M2.

Graphics decoding (load/extract time, never per frame):
- `Lz77` — BIOS LZ77 decompressor (`LZ77UnCompWram`/`Vram`).
- `Tiles` + `IndexedImage` — 4bpp/8bpp 8×8 tiles ⇄ an image of palette indices (4bpp: low nibble = left
  pixel). Indices are kept exactly so palette swaps and colour 0 transparency behave as in vanilla.
- `Bgr555` — GBA colours ⇄ ARGB8888 (`c << 3`, exact inverse on expanded colours).
