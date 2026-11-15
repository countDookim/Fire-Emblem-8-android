package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_0020
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory

/**
 * The world map text commands `EventC6_WmDisplayText` and `EventC7_` of `src/eventscr_gmap.c`
 * (`EV_CMD_WM_TEXT` 0xC6 and `EV_CMD_WM_TEXTSTART` 0xC7; the commands the Prologue's opening
 * script runs). Register them one by one with `engine.register`; `EventC8_` (`ShowWmText(0)`,
 * top of the screen) is not ported here.
 *
 * @param memory script words (the command arguments)
 * @param text the world map text box
 */
public class WorldMapTextEvents(
    private val memory: ScriptMemory,
    private val text: WorldMapText,
) {
    /**
     * Ported from src/eventscr_gmap.c: EventC6_WmDisplayText
     *
     * Prints a world map text box message ([WorldMapText.startWmTextMsg]): the message id is
     * `a | b` in the decomp, the halfwords at `+4` and `+6` (`firstHalf` and `secondHalf` here).
     * They are ORed without shifting one, so the id is effectively 16 bits.
     * Yields so the script waits for the text; does nothing while skipping. The `EV_STATE_0020`
     * branch (`SetTalkFlag(TALK_FLAG_NOSKIP)`, a flag `StartWmTextMsg` already sets) is not run by
     * any capture and fails loudly.
     */
    public val eventC6WmDisplayText: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        val firstHalf = memory.u16(proc.pEventCurrent + 4)
        val secondHalf = memory.u16(proc.pEventCurrent + 6)
        text.startWmTextMsg(firstHalf or secondHalf)
        check(proc.evStateBits and EV_STATE_0020 == 0) { "not verified: EV_STATE_0020 at C6" }
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr_gmap.c: EventC7_
     *
     * Shows the text box at the bottom of the screen (`ShowWmText(1)`; `EventC8_` is the same
     * at the top) and yields; does nothing while skipping.
     */
    public val eventC7: EventHandler = EventHandler { proc: EventEngineProc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        text.showWmText(1)
        EVC_ADVANCE_YIELD
    }
}
