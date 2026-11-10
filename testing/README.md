# testing

Verification; GBA addresses appear only in tests. Tests lacking reference data are *skipped*, never passed.
- Reference access (`ReferenceData`, `GbaMem`, `RamSymbols`, `AllocationMeter`, `MgbaColorMath`) lives in
  `:gba:testkit`; this module adds FE8-specific helpers (`HardwareScene` frame-compare loader, `M4aRam`).
- Tests: `*DifferentialTest`/`Fe8BootTest` (vs mGBA, dumps from `$FE8_ASSETS/reference/out`), `*RomTest` (ROM
  golden values), `AllocationTest` (zero bytes per frame).
- `FrameTimeBenchmark`: `./gradlew :testing:benchmark`, JSON report.

Node-table tests also read the pinned original decomp source. CI and `tools/setup.sh` provision it
with `tools/setup_decomp.sh`; owner-PC runs can set `FE8U_DECOMP` to the read-only sibling checkout.
With a ROM present, missing source fails rather than silently skipping. These source files are
Gradle test inputs, so a prior missing-source result cannot be reused after provisioning.
