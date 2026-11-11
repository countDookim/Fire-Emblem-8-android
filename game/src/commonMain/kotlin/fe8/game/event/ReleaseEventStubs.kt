package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE

/**
 * Stand-in until M6-E: not ports; no-op event commands that let a script run past commands
 * whose handlers are not ported yet (text, fades, unit loads, ASMC and so on), as the skip mode
 * of the beginning events does in the release build. Each stub advances past its command (the
 * engine
 * reads the length from the header word) without any effect.
 */
public object ReleaseEventStubs {
    /**
     * Registers a no-op handler on [engine] for every command code that has no handler yet, so
     * handlers registered before (for example by [EventScrCore.registerAll]) win. With [onStub],
     * each stub first reports its command code (the registered index) when it runs, so tests
     * can measure which commands still hit a stub; null: no reporting.
     */
    public fun registerMissing(engine: EventEngine, onStub: ((code: Int) -> Unit)? = null) {
        for (i in engine.lo.indices) {
            if (engine.isUnregistered(i)) engine.register(i, stub(i, onStub))
            if (engine.isUnregistered(i + 0x80)) engine.register(i + 0x80, stub(i + 0x80, onStub))
        }
    }

    /** Builds a handler that reports the missing command and advances without effects. */
    private fun stub(code: Int, onStub: ((code: Int) -> Unit)?): EventHandler = EventHandler {
        onStub?.invoke(code)
        EVC_ADVANCE_CONTINUE
    }
}
