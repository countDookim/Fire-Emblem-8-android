package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.scene.BrownTextBox

/**
 * The popup command of `src/eventscr.c` (0x3A).
 *
 * @param state the event slots (`gEventSlots[2]` is the default message).
 * @param memory script memory.
 * @param brownTextBox the brown text box of subcommand 1.
 */
internal class EventPopup(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val brownTextBox: BrownTextBox,
) {
    /** Registers 0x3A on [engine]. */
    fun registerOn(engine: EventEngine) {
        engine.register(0x3A, event3ADisplayPopup)
    }

    /**
     * Transcribed from src/eventscr.c: Event3A_DisplayPopup
     * (not verified: no reference test)
     *
     * Subcommand 0 (`NewPopup_VerySimple`) is not ported and throws [NotImplementedError].
     */
    val event3ADisplayPopup: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        val cur = proc.pEventCurrent
        val subcmd = memory.u16(cur) and 0xF
        var textId = memory.u16(cur + 2).toShort().toInt()
        if (textId < 0) textId = state.slots[2]
        when (subcmd) {
            0 -> throw NotImplementedError("NewPopup_VerySimple (event 0x3A sub 0) is not ported")
            1 -> {
                val arg = memory.u16(cur + 6)
                val x = arg.toByte().toInt()
                val y = (arg shr 8).toByte().toInt()
                brownTextBox.startBrownTextBox(textId, x, y, proc.proc!!)
            }
        }
        EVC_ADVANCE_YIELD
    }
}
