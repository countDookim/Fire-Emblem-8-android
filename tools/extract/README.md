# :tools:extract

JVM CLI: ROM → JSON tables, indexed PNGs, Tiled maps, MIDI + voicegroups, WAV, `.fe8s`, text. Never shipped
(the app extracts on device, ADR 0011).

```
./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out ../fire-emblem-8-rom/extracted \
  --symbols ../fire-emblem-8-rom/decomp/fireemblem8.nm.txt --map <fireemblem8.map> --decomp <fireemblem8u>"
./gradlew :tools:extract:run --args=--help
```

- **Never writes into this repo:** `--out` inside it (canonical paths) exits with code 2.
- The extraction itself is the library `:engine:extract` (`gbafe.extract.fe8.Fe8Steps`), also used by `:app` for
  ROM import; this module adds the CLI and the file/decomp sides (`DecompSources`, `FileOutput`).
- Values come from the ROM; `--symbols`/`--map` give addresses, `--decomp` only names/layout. Steps ask them
  through `ExtractSources`: `DecompSources` (decomp), `RecordingSources` (`--write-manifest <file>`: records the
  answers used) and `ManifestSources` (`--manifest <file>`: no decomp, as on device, ADR 0011).
- The committed manifest is `engine/extract/src/main/resources/gbafe/extract/manifest/fe8.json`;
  `tools/ci_check_extraction.sh` fails if it is stale or if manifest-only output differs. Regenerate it after
  changing what a step looks up.
- Generic helpers live in `:gba:extract`: `IndexedPng` (palette PNGs via JDK zlib, no ImageIO, which reorders
  palettes; reads all filters), `Json` (deterministic writer), `Symbols` (reads `nm`), `DecompEnums` (reads
  `NAME = literal,` constants). Decoders (LZ77, tiles, Huffman) live in `gba/*` and `engine/*`.
- No CLI library (no plugin portal).
- `tools/tests/test_schemas.py` checks `schemas/` against what ROM tests extract into `testing/build/extracted/`.
