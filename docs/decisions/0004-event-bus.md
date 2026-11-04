# 0004 — Typed, allocation-free event bus

**Status.** Accepted.

**Context.** Mods hook gameplay without editing core code; some events fire every tick.

**Decision.** `gba.core.event.EventChannel<L>`, one per event, typed by a `fun interface` listener taking
primitives/ids (`fun onStep(unit: Int, x: Int, y: Int)`).
- `inline fun dispatch(call: (L) -> Unit)` over an array snapshot: no event objects, iterators or lambdas.
- Copy-on-write subscribe (allocates only then), safe during dispatch; integer priority (vanilla 0).
- Channels live with their system (`BattleEvents.onBattleStart`), named `<system>.<event>`, listed in `MODDING.md`.
- Events notify; behaviour changes use strategies (0009). Cancellable events return a Boolean.

**Consequences.** One listener interface per event; zero dispatch cost (`AllocationTest`).
