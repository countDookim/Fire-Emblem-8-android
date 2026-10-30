# :tools:extract

JVM CLI: ROM → JSON tables, indexed PNGs, Tiled maps, MIDI + voicegroups, WAV, `.fe8s`, text. Never shipped
(on-device extraction: ADR 0011).

```
./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out ../fire-emblem-8-rom/extracted \
  --symbols ../fire-emblem-8-rom/decomp/fireemblem8.nm.txt --map <fireemblem8.map> --decomp <fireemblem8u>"
./gradlew :tools:extract:run --args=--help
```

- **Never writes into this repo:** `--out` inside it (canonical paths) exits with code 2.
- Steps: `gbafe.extract.fe8.Fe8Steps`. Values come from the ROM; `--symbols`/`--map` give addresses, `--decomp`
  only names/layout.
- Decoders the game also needs (LZ77, tiles, Huffman) live in `engine/*`. No CLI library (no plugin portal).
- `IndexedPng`: palette PNGs via JDK zlib, no ImageIO (it reorders palettes); reads all filters.
- `Json`: deterministic writer; `Symbols` reads `nm`; `DecompEnums` reads `NAME = literal,` constants.
- `tools/tests/test_schemas.py` checks `schemas/` against what ROM tests extract into `testing/build/extracted/`.
