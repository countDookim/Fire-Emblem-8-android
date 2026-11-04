# 0009 — The product is a 1:1 copy; modding is extension points only

**Status.** Accepted (owner, 2026-10-25).

**Context.** The app must play exactly like the original; changes (touch gameplay, 1-RN hit, widescreen, QoL)
must be easy for mods to add, not shipped.

**Decision.**
- Ship no non-original option: no QoL flags, alternate formulas, widescreen or touch gameplay. `FeatureFlags` is
  for mods; the base game registers none.
- Extension points: VFS data/assets (0002, 0003), event bus (0004), strategies in
  `gbafe.core.mechanics.Mechanics` defaulting to verified vanilla (e.g. `BattleRolls`).
- Action-based input (`ActionSource`, `GameAction`) and `UiElements` bounds let a mod add touch gameplay.
- Only shipped touch input: **on-screen GBA buttons** (`gba.input.TouchButtons` + `fe8.app.TouchOverlayView`),
  emitting exactly GBA keys.
- Alternative implementations only in tests; debug tools only in debug builds.

**Consequences.** Milestone 7 = mod loader, debug menu, on-screen buttons. Each ported system adds strategies and
events with a swap test, shipping only vanilla.
