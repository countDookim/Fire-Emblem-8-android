package gba.core.event

/** Listener for per-tick hooks. [tick] is the logic frame counter (`FixedStepClock.ticks`). */
public fun interface TickListener {
    /** Called once per logic tick with the current tick count. Must not allocate. */
    public fun onTick(tick: Long)
}

/**
 * Engine-level channels that exist for every game. Game systems define their own channel holders
 * (e.g. `MapEvents.onStep`, `BattleEvents.onBattleStart`) next to the system that fires them, so
 * the bus stays typed and discoverable; `MODDING.md` lists the channels that exist now and the
 * planned ones.
 */
public class CoreEvents {
    /**
     * Fired at the start of every logic tick, before any game system runs (the host game loop
     * dispatches it).
     */
    public val onTickStart: EventChannel<TickListener> = EventChannel("core.onTickStart")

    /**
     * Fired at the end of every logic tick, after all game systems ran (the host game loop
     * dispatches it).
     */
    public val onTickEnd: EventChannel<TickListener> = EventChannel("core.onTickEnd")
}
