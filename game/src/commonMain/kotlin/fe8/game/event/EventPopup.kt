package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.scene.BrownTextBox

/**
 * The popup command of `src/eventscr.c` (0x3A): a short message box shown during a script.
 * Only the brown text box variant is ported.
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
    /** Registers the popup command 0x3A on [engine]. */
    fun registerOn(engine: EventEngine) {
        engine.register(0x3A, event3ADisplayPopup)
    }

    /**
     * Ported from src/eventscr.c: Event3A_DisplayPopup
     *
     * Subcommand 0 (`NewPopup_VerySimple`) is not ported and throws [NotImplementedError].
     * Subcommand 1 displays a brown text box with the message of the command argument (slot 2
     * when negative) at the position packed in the last argument word (two signed bytes; the
     * decomp does not say whether it is in tiles or pixels). The box blocks the event proc, and
     * the command does nothing while the event is being skipped.
     */
    val event3ADisplayPopup: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        val currentCmd = proc.pEventCurrent
        val subcommand = memory.u16(currentCmd) and 0xF
        var textId = memory.u16(currentCmd + 2).toShort().toInt()
        if (textId < 0) textId = state.slots[2]
        when (subcommand) {
            0 -> throw NotImplementedError("NewPopup_VerySimple (event 0x3A sub 0) is not ported")
            1 -> {
                val position = memory.u16(currentCmd + 6)
                val x = position.toByte().toInt()
                val y = (position shr 8).toByte().toInt()
                brownTextBox.startBrownTextBox(textId, x, y, proc.proc!!)
            }
        }
        EVC_ADVANCE_YIELD
    }
}
