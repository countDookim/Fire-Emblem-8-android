# engine:core

Foundation shared by every module. Pure Kotlin/JVM, no dependencies.

- `time` — `FixedStepClock` (exact 2^24/280896 Hz ticks, catch-up cap), `GameLoop`, `Simulation`.
- `rng` — `Fe8Rng`, `LcgRng` (ported from `src/rng.c`, verified vs mGBA), `RngService`, `RandomSource`.
- `event` — `EventChannel` (typed, allocation-free, priority-ordered), `CoreEvents`.
- `mechanics` — `Mechanics`: swappable gameplay strategies, vanilla by default (`BattleRolls`: hit/crit/skill
  rolls). Only vanilla implementations ship (docs/decisions/0009).
- `flags` — `FeatureFlag`/`FeatureFlags` with vanilla override (for mods; the base game registers none).
- `VerifiesPort` — test annotation read by `tools/progress.py`.

Rules: nothing here may allocate in per-tick paths; see `docs/decisions/0004-event-bus.md`.
