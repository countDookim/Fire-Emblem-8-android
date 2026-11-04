# testing

Verification; GBA addresses appear only in tests. Tests lacking reference data are *skipped*, never passed.
- Reference access (`ReferenceData`, `GbaMem`, `RamSymbols`, `AllocationMeter`, `MgbaColorMath`) lives in
  `:gba:testkit`; this module adds FE8-specific helpers (`HardwareScene` frame-compare loader, `M4aRam`).
- Tests: `*DifferentialTest`/`Fe8BootTest` (vs mGBA, dumps from `$FE8_ASSETS/reference/out`), `*RomTest` (ROM
  golden values), `AllocationTest` (zero bytes per frame).
- `FrameTimeBenchmark`: `./gradlew :testing:benchmark`, JSON report.
