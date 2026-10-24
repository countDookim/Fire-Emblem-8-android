# :tools:extract

JVM command-line tool that converts the original ROM into the moddable formats the game loads
(JSON tables, indexed PNGs, Tiled maps, MIDI + voicegroups, `.fe8s` scripts). Milestone 2.

```
./gradlew :tools:extract:run --args="--rom $FE8_ASSETS/baserom.gba --out ../fire-emblem-8-rom/extracted"
./gradlew :tools:extract:run --args=--help
```

- **Output never goes into this repository.** `--out` is checked against the code repo root (canonical
  paths) and the tool exits with code 2 if it points inside it. ROM-derived data lives in the ROM repo.
- Decoders that the game also needs at load time (LZ77, tiles, Huffman text) live in `engine/*`; this
  module only orchestrates them and writes files.
- No CLI library: sessions cannot reach the Gradle plugin portal and the tool needs nothing fancy.
