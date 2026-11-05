# gba:core

Game-neutral GBA core (package `gba.core`), nothing platform-specific, nothing allocates in per-tick paths.
- `time`: `FixedStepClock` (exact 2^24/280896 Hz, catch-up cap), `GameLoop`, `Simulation`.
- `event`: `EventChannel`, `CoreEvents` (ADR 0004).
- `sram`: `CartSram` (32 KiB cart SRAM), `SramFast` (agb_sram.c ports), `SramBits` (LE/bitfield helpers).
- `VerifiesPort`: read by `tools/progress.py`.
