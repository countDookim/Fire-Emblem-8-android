# testing

Verification module.
- `ReferenceData` / `GbaMem` — read mGBA reference dumps from `$FE8_ASSETS/reference/out`. The only place GBA
  addresses may appear.
- Differential tests (`src/test`) — e.g. `RngDifferentialTest` (boot RNG state vs mGBA IWRAM).
- `AllocationMeter` + `AllocationTest` — zero bytes per frame, measured with HotSpot's per-thread counter.
- `FrameTimeBenchmark` — `./gradlew :testing:benchmark`, JSON report, loose regression budgets.

Tests needing reference data are *skipped* (never passed) when it is missing.
