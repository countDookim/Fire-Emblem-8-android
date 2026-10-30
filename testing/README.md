# testing

Verification; the only place GBA addresses may appear. Tests lacking reference data are *skipped*, never passed.
- `ReferenceData`, `GbaMem`, `RamSymbols`: mGBA dumps from `$FE8_ASSETS/reference/out`, state by decomp symbol.
- `MgbaColorMath`: mGBA blend/fade arithmetic for frame comparison.
- Tests: `*DifferentialTest`/`Fe8BootTest` (vs mGBA), `*RomTest` (ROM golden values), `AllocationTest`
  (`AllocationMeter`, zero bytes per frame).
- `FrameTimeBenchmark`: `./gradlew :testing:benchmark`, JSON report.
