# engine:core

Pure Kotlin; depends on `:gba:core`; nothing allocates in per-tick paths.
- `time`: `GameClock`. Moved to `:gba:core`: `FixedStepClock`, `GameLoop`, `Simulation`, `event`, `VerifiesPort`.
- `rng`: `Fe8Rng`, `LcgRng` (`src/rng.c`, verified), `RngService`, `RandomSource`.
- `gbafe.proc`: proc scheduler (`src/proc.c`).
- `mechanics`: `Mechanics`/`BattleRolls`, vanilla only (0009).
- `flags`: `FeatureFlag(s)`, vanilla override, mods only.
