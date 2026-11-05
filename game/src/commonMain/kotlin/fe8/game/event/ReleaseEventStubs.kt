package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE

/**
 * Stand-in: not ports; no-op event commands that let a script run past commands whose handlers
 * are not ported yet (text, fades, unit loads, ASMC and so on), as the skip mode of the
 * beginning events does in the release build. Each stub advances past its command (the engine
 * reads the length from the header word) without any effect.
 */
public object ReleaseEventStubs {
    /**
     * Registers a no-op handler on [engine] for every command code that has no handler yet, so
     * handlers registered before (for example by [EventScrCore.registerAll]) win.
     */
    public fun registerMissing(engine: EventEngine) {
        val noop = EventHandler { EVC_ADVANCE_CONTINUE }
        for (i in engine.lo.indices) {
            if (engine.isUnregistered(i)) engine.register(i, noop)
            if (engine.isUnregistered(i + 0x80)) engine.register(i + 0x80, noop)
        }
    }
}
