# 0012 — Game-neutral GBA code lives in `:gba:*`

**Status.** Accepted 2026-11-04 (owner request 2026-10-27, M4-06).

**Context.** ADR 0010 split `gbafe.*` (game-neutral engine) from `fe8.*`, but much of `gbafe.*` is not
Fire Emblem at all: it models GBA hardware or decodes GBA formats. Other GBA ports can reuse that without
the FE engine.

**Decision.** Code that knows no Fire Emblem lives in `:gba:*` modules, package `gba.*`:
- `:gba:vfs` layered VFS, mod manifest, texture overrides.
- `:gba:data` LZ77, tiles/`IndexedImage`/`Bgr555`, `Decompress`, `JsonReader`.
- `:gba:core` `VerifiesPort`, `FixedStepClock`, `GameLoop`/`Simulation`, `EventChannel`/`CoreEvents`.
- `:gba:render` PPU model: `RenderState`, `SoftwareRenderer`, `GpuFrame`, `PackedCompositor`,
  `SpriteRasterizer`, `TileSheets`, `FrameBuffer`, `ColorMath`, `BgAffine`, `HBlankLines`.
- `:gba:input` `GameAction`, `InputFrame`, `VanillaButtonMap`, `TouchButtons`, `UiElements`.
- `:gba:audio` m4a driver, `PcmStream`, `AudioSink`.
- `:gba:extract` `Json`, `Symbols`, `DecompEnums`, `IndexedPng`, `ExtractOutput`, `RomImporter`.

**Rules.** `gba` imports neither `gbafe.*` nor `fe8.*` (`tools/tests/test_layering.py`). Each `gbafe`
engine module `api`-depends on its `gba` counterpart, so downstream code sees both.

**Stays FE-side (`gbafe.*`, `fe8.*`).** Procs, RNG, `BattleRolls`, `GameClock`, `Interpolate`, flags;
`KeyStatus`/`KeyComboReset`; `Tsa`/`TrigTable`; `HardwareScene` and the ported BG/OAM/LCD/`ColorFade`
code; `ScreenExtractor`/`ExtractSources`; text; scripts; everything in `fe8.*`.

**Remaining M4-06 work.**
- Testkit: `ReferenceData`, `RamSymbols`, `AllocationMeter` out of `:testing`.
- Split `ExtractSources` so the interface and `RecordingSources` can move (the bundled-manifest resource
  path stays FE-side).
- A doc on reusing the mGBA harness for another game.

**Consequences.** Import paths changed (`gbafe.X` -> `gba.X`) with no behaviour change; `Ported from` KDoc
and `@VerifiesPort` tests are untouched. Progress counts are unaffected.
