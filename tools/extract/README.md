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
  only names/layout. Steps ask them through `ExtractSources`: `DecompSources` (decomp), `RecordingSources`
  (`--write-manifest <file>`: records the answers used) and `ManifestSources` (`--manifest <file>`: no decomp,
  as on device, ADR 0011). Committed manifest: `engine/extract/src/jvmMain/resources/gbafe/extract/manifest/fe8.json`; `tools/ci_check_extraction.sh` fails if it
  is stale or if manifest-only output differs. Regenerate after changing what a step looks up.
- Decoders the game also needs (LZ77, tiles, Huffman) live in `engine/*`. No CLI library (no plugin portal).
- `IndexedPng`: palette PNGs via JDK zlib, no ImageIO (it reorders palettes); reads all filters.
- `Json`: deterministic writer; `Symbols` reads `nm`; `DecompEnums` reads `NAME = literal,` constants.
- `tools/tests/test_schemas.py` checks `schemas/` against what ROM tests extract into `testing/build/extracted/`.
