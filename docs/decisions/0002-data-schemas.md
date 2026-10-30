# 0002 — Data: versioned JSON + JSON Schema, compiled to primitive tables

**Status.** Accepted; format names per 0010.

**Context.** All game data must be moddable, schema-validated, versioned JSON, with allocation-free access.

**Decision.**
- `data/<table>.json`:
  `{ "format": "gbafe.items", "game": "fe8", "version": 1, "entries": [{ "id": "SWORD_IRON", ... }] }`.
  Ids = decomp constants minus prefix; indices come from position, never hand-written.
- `schemas/<table>.schema.json` (JSON Schema 2020-12) is the public contract; tests validate data against it.
- Load: kotlinx.serialization (strict) → DTOs → `TableCompiler` → primitive arrays behind typed wrappers
  (`items.might(item: ItemId)`, `ItemId` value class); DTOs discarded.
- Mods replace files (0003) or patch with `data/<table>.merge.json` (RFC 7386 on `entries` by `id`) in load
  order; new ids index after vanilla ones.
- A `version` bump needs a loader migration for older mod files.

**Consequences.** Vanilla JSON is extracted into the ROM repo, never committed here; schemas are committed.
