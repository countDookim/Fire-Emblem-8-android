# 0009 — The product is a 1:1 copy; modding is groundwork only

**Context.** The owner clarified (2026-10-25) that the finished app must play exactly like the original.
Moddability means it is *easy to add* changes (touch controls, 1-RN hit, widescreen, QoL...), not that
the app ships any of them.

**Decision.**
- The shipped game contains no non-original gameplay or presentation option: no built-in QoL feature
  flags, no alternate formulas, no widescreen, no touch gameplay UI. `FeatureFlags` stays as the
  mechanism for mods; the base game registers no flags.
- Every system exposes extension points instead: data/assets through the VFS (0002, 0003), hooks through
  the event bus (0004), and behaviour through strategy interfaces collected in `gbafe.core.mechanics.
  Mechanics`, each defaulting to a vanilla implementation verified against the original. Example:
  `BattleRolls` (hit = 2 RN in vanilla; a mod can make it 1 RN by replacing one method).
- Input stays action-based (`ActionSource`, `GameAction`), and UI elements expose bounds/actions
  (task M3-11), so touch gameplay can be added by a mod without game-logic changes.
- Only exception, for playability on phones without a controller: an **on-screen GBA button overlay**
  (D-pad, A, B, L, R, Start, Select) that produces exactly the GBA buttons. It changes no behaviour.
- Alternative implementations of extension points may exist only in tests (to prove the swap works).
- Debug tools (debug menu) exist only in debug builds.

**Consequences.** Brief milestone 7 becomes "mod loader, debug menu (debug builds), on-screen GBA
buttons"; no QoL flags or touch gameplay are planned. Every newly ported system must add its strategy
interfaces and events, with a test showing a swap works, but ships only the vanilla implementation.
