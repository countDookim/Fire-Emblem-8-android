package fe8.core.event

/** Listener for per-tick hooks. [tick] is the logic frame counter. */
public fun interface TickListener {
    /** Called once per logic tick. Must not allocate. */
    public fun onTick(tick: Long)
}

/**
 * Engine-level channels. Game systems define their own channel holders (e.g. `MapEvents.onStep`,
 * `BattleEvents.onBattleStart`) next to the system that fires them, so the bus stays typed and
 * discoverable; `MODDING.md` lists every channel.
 */
public class CoreEvents {
    /** Fired at the start of every logic tick, before any game system runs. */
    public val onTickStart: EventChannel<TickListener> = EventChannel("core.onTickStart")

    /** Fired at the end of every logic tick, after all game systems ran. */
    public val onTickEnd: EventChannel<TickListener> = EventChannel("core.onTickEnd")
}
