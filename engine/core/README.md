# engine:core

Pure Kotlin, no dependencies; nothing allocates in per-tick paths.
- `time`: `FixedStepClock` (exact 2^24/280896 Hz, catch-up cap), `GameLoop`, `Simulation`.
- `rng`: `Fe8Rng`, `LcgRng` (`src/rng.c`, verified), `RngService`, `RandomSource`.
- `gbafe.proc`: proc scheduler (`src/proc.c`).
- `event`: `EventChannel`, `CoreEvents` (ADR 0004). `mechanics`: `Mechanics`/`BattleRolls`, vanilla only (0009).
- `flags`: `FeatureFlag(s)`, vanilla override, mods only. `VerifiesPort`: read by `tools/progress.py`.
