# gba:data

Game-neutral GBA load/extract-time decoders (package `gba.data`; Kotlin Multiplatform, ADR 0013; never per frame):
- `Lz77` (BIOS `LZ77UnCompWram`/`Vram`), `Decompress`, `JsonReader`.
- `Tiles` + `IndexedImage`: 4bpp/8bpp 8×8 tiles ⇄ palette-index image (4bpp: low nibble = left pixel); indices
  kept exactly so palette swaps and colour-0 transparency behave as vanilla.
- `Bgr555`: GBA colour ⇄ ARGB8888 (`c << 3`, exact inverse on expanded colours).
