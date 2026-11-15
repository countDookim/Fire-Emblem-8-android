# 0016 — Faithful vanilla behavior and extensive executable mods

**Status.** Accepted direction (owner, 2026-11-15). Clarifies 0009 and extends 0015;
runtime, API and storage choices remain subject to the open decisions in [M7](../plans/M7-modding.md).

**Context.** The owner wants the base game as close to the original as possible, with extensive,
easy modding down to enemy AI, camera behavior and entirely new control schemes. Data replacement,
button remapping and event notifications alone do not meet that goal.

**Decision.**
1. Vanilla remains the default: original rules, AI decisions, RNG order, timing, GBA input behavior,
   camera framing and 240×160 presentation, verified against the original. No alternative behavior
   is enabled merely by installing the app. ROM import/distribution and port-credit rules are unchanged.
2. Mods may replace logic as well as data: targeting/movement/scoring and whole AI decision routines;
   combat formulas, skills, actions and turn rules; camera tracking, zoom and viewport; semantic
   controls including direct touch and custom actions; maps, scripts, text, graphics and audio.
   The original-only constraint applies to the unmodded experience, not to what a loaded mod can do.
3. Provide executable mod logic without rebuilding/forking the APK. Keep `.fe8s` as the event-script
   format (0001); it is not assumed to be a general AI/camera/input runtime. Choose the executable
   runtime and its lifecycle in a separate design review; no language or plugin format is selected here.
4. Notifications and replacement contracts are distinct. Mods need typed observations and controlled
   ways to supply decisions/actions. Use stable semantic IDs and state views rather than requiring
   authors to know FE8 ROM addresses, proc internals or synthetic GBA key sequences. Define API
   versions, composition/conflicts, deterministic RNG/clock access and lifecycle before implementation.
5. Establish replaceable boundaries alongside each system's M6 work; retain the verified vanilla
   adapter. Record gaps as bounded tasks. This does not authorize speculative unobserved ports,
   broad refactors, a runtime choice, or interruption of another agent's claim.
6. Keep original differential tests on the vanilla path and test mod behavior separately. Test that
   disabling mods and starting from the same clean baseline restores original results; do not promise
   that removing a mod converts a modded save. Preserve the vanilla allocation/frame-time budget and
   measure the execution cost of loaded mods explicitly. Mod tests do not earn FE8 port credit.
7. Deliver in stages, but retain the entire scope, including audio, AI, camera and controls. M7 cannot
   close with only a texture/stat/script example. Document author APIs and examples, and plan versioned
   mod save state and compatibility. The first release cut and unresolved implementation choices remain open.

**Consequences.** M7 gains contract/runtime design tasks and separate demonstrations of AI, camera and
control replacement without an app rebuild. 0009's “alternatives only in tests” means alternatives are
not built-in vanilla options; it does not prohibit player-loaded mods. Wider viewports are a mod capability,
not a changed vanilla renderer. [M7](../plans/M7-modding.md) tracks what is accepted, open and unimplemented.
