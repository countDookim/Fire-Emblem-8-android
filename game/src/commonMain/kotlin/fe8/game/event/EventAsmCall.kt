package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gbafe.core.flags.FlagStore

/**
 * The ASMC command of `src/eventscr.c` (0x0D): calls a native function with the event proc.
 *
 * @param functions Kotlin implementations by ROM function address (thumb bit ignored).
 * @param onUnknown called with the address when no implementation is known; the command is
 *   then a no-op that continues.
 */
public class EventAsmCall(
    private val memory: ScriptMemory,
    private val functions: Map<Int, (EventEngineProc) -> Unit> = emptyMap(),
    private val onUnknown: (address: Int) -> Unit = {},
) {
    /** Registers 0x0D on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x0D, event0DAsmCall)
    }

    /**
     * Transcribed from src/eventscr.c: Event0D_AsmCall
     * (not verified: callback dispatch and skip behavior not trace-compared)
     */
    public val event0DAsmCall: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val func = memory.u16(cur + 4) or (memory.u16(cur + 6) shl 16)
        if (memory.u16(cur) and 0xF == 1 && proc.evStateBits and EV_STATE_SKIPPING != 0) {
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        val impl = functions[func and 1.inv()]
        if (impl == null) {
            onUnknown(func)
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        impl(proc)
        EVC_ADVANCE_YIELD
    }
}

/** Address of `gGuideTable` (`GuideEnt[]`, 12 bytes each) in the US ROM. */
private const val GUIDE_TABLE = 0x08B19E0C

/**
 * Transcribed from src/bmguide.c: BmGuideTextSetAllGreen
 * (not verified: guide display-flag bytes not trace-compared)
 *
 * Sets the display flag of every guide entry, up to the entry whose title is 12.
 */
public fun bmGuideTextSetAllGreen(memory: ScriptMemory, flags: FlagStore) {
    var it = GUIDE_TABLE
    while (memory.u16(it) and 0xFF != 12) {
        flags.setFlag(memory.u16(it + 6))
        it += 12
    }
}
