# engine:save

`gbafe.save` binary save container v1 (layout in `SaveFormat` KDoc): magic `GBFS`, container version, game id,
section table (id, section version, length, crc32), then payloads.

- `core` = the raw 32 KiB cartridge SRAM image; `mod:<id>` sections are opaque and preserved verbatim on rewrite.
- `SaveContainer.read` parses + upgrades via `MigrationRegistry` (keyed by `(sectionId, fromVersion)`, chained
  one step at a time); `writeTo(dst, offset)` writes into a caller buffer with no allocation;
  `readSectionInto` copies one payload into a caller buffer.
- Errors are `SaveException`: `Corrupt`, `NewerVersion` (container or section newer than supported),
  `MissingMigration`.
- `Crc32` is pure Kotlin (no `java.util.zip`). `SaveChecksums`: the FE SRAM checksums (Checksum16, Checksum32). The original `.sav`
  importer is `fe8.game.save.Fe8SavImporter`.
