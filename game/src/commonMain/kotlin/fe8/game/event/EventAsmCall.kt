package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gbafe.core.flags.FlagStore

/**
 * The ASMC command of `src/eventscr.c` (`EV_CMD_ASMC` 0x0D, `Event0D_AsmCall`): the script gives
 * the address of a function in the game code, which is called with the event proc. The port runs
 * a Kotlin implementation registered for that address instead.
 *
 * @param memory script words (the command arguments).
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
     * Ported from src/eventscr.c: Event0D_AsmCall
     *
     * Calls the function at the command's address with the event proc and yields. Subcommand 1
     * (`EvtAsmCallEnableSkip`) does not call it while the cutscene is being skipped. Port
     * addition: an address with no implementation calls [onUnknown] and continues, where the C
     * would call the ROM function.
     */
    public val event0DAsmCall: EventHandler = EventHandler { proc ->
        val currentCmd = proc.pEventCurrent
        val functionAddress = memory.u16(currentCmd + 4) or (memory.u16(currentCmd + 6) shl 16)
        if (memory.u16(currentCmd) and 0xF == 1 && proc.evStateBits and EV_STATE_SKIPPING != 0) {
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        // The script stores a Thumb address (bit 0 set); the map is keyed by the plain address.
        val impl = functions[functionAddress and 1.inv()]
        if (impl == null) {
            onUnknown(functionAddress)
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        impl(proc)
        EVC_ADVANCE_YIELD
    }
}

/**
 * Address of `gGuideTable` (`struct GuideEnt[]`, 12 bytes each, the entries of the guide menu) in
 * the US ROM. Not checkable against the decomp, which has no ROM addresses for data; the
 * differential test reads the real table.
 */
private const val GUIDE_TABLE = 0x08B19E0C

/**
 * Ported from src/bmguide.c: BmGuideTextSetAllGreen
 *
 * Sets the display flag (`displayFlag`, the u16 at offset 6) of every guide entry, up to the entry
 * whose `title` is 12 (the list terminator); the title is the low byte of the entry's first word.
 * It is meant to run from an ASMC command.
 */
public fun bmGuideTextSetAllGreen(memory: ScriptMemory, flags: FlagStore) {
    var guideEntryAddress = GUIDE_TABLE
    while (memory.u16(guideEntryAddress) and 0xFF != 12) {
        flags.setFlag(memory.u16(guideEntryAddress + 6))
        guideEntryAddress += 12
    }
}
