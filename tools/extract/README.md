# :tools:extract

JVM command-line tool that converts the original ROM into the moddable formats the game loads
(JSON tables, indexed PNGs, Tiled maps, MIDI + voicegroups, `.fe8s` scripts). Milestone 2.

```
./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out ../fire-emblem-8-rom/extracted \
  --symbols ../fire-emblem-8-rom/decomp/fireemblem8.nm.txt --decomp <fireemblem8u checkout>"
./gradlew :tools:extract:run --args=--help
```

- **Output never goes into this repository.** `--out` is checked against the code repo root (canonical
  paths) and the tool exits with code 2 if it points inside it. ROM-derived data lives in the ROM repo.
- Decoders that the game also needs at load time (LZ77, tiles, Huffman text) live in `engine/*`; this
  module only orchestrates them and writes files.
- No CLI library: sessions cannot reach the Gradle plugin portal and the tool needs nothing fancy.
- `IndexedPng` writes/reads palette PNGs (colour type 3, 8-bit) with JDK zlib only — no ImageIO, which
  may reorder palettes. Reading supports all scanline filters, so PNGs re-saved by editors still load.
- Steps: `data/items.json` (`items/ItemsExtractor`, needs `--symbols`; `--decomp` supplies ids from
  `include/constants/items.h`). Values always come from the ROM; the decomp only provides names and layout.
- `Json` is a small deterministic writer (stable key order, diffable output); `Symbols` reads `nm` output;
  `DecompEnums` reads `NAME = literal,` constants from decomp headers.
- Schemas live in `schemas/` (committed, not ROM-derived) and are checked by `tools/tests/test_schemas.py`
  against what `ItemsRomTest` extracts into `testing/build/extracted/`.
