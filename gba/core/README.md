# gba:core

Game-neutral GBA core (package `gba.core`), nothing platform-specific, nothing allocates in per-tick paths.
- `time`: `FixedStepClock` (exact 2^24/280896 Hz, catch-up cap), `GameLoop`, `Simulation`.
- `event`: `EventChannel`, `CoreEvents` (ADR 0004).
- `VerifiesPort`: read by `tools/progress.py`.
