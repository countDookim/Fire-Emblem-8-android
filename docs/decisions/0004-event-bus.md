# 0004 — Typed, allocation-free event bus

**Context.** Mods hook into gameplay (onStep, onMapEnter, onBattleStart, onItemUsed, ...) without editing
core code; dispatch must not allocate because some events fire every tick.

**Decision.** `fe8.core.event.EventChannel<L>`: one channel per event, typed by a `fun interface`
listener whose method takes primitives/ids (`fun onStep(unit: Int, x: Int, y: Int)`).
- Dispatch is `inline fun dispatch(call: (L) -> Unit)` over an array snapshot: no event objects, no
  iterators, no lambda allocation.
- Copy-on-write subscribe/unsubscribe (allocation only at registration), safe during dispatch.
- Listeners ordered by integer priority (vanilla systems use 0; mods choose before/after).
- Channels live next to the system that fires them (`BattleEvents.onBattleStart`), are listed in
  `MODDING.md`, and are named `<system>.<event>` for the debug menu.
- Events are notifications; to *change* behaviour mods replace strategy interfaces (formulas, AI), which
  keeps vanilla logic linear and testable. Cancellable events, where needed, return a Boolean.

**Consequences.** Slightly more boilerplate (one listener interface per event) in exchange for type
safety and zero per-dispatch cost. Verified by `AllocationTest`.
