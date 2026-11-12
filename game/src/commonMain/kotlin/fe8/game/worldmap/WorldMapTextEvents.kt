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
 * (`WM_TEXT` and `WM_TEXTSTART`; the commands the Prologue's opening script runs). Register them
 * one by one.
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
     * Starts the text of message `a | b` (the halfwords at `+4` and `+6`). The `EV_STATE_0020`
     * branch (`SetTalkFlag(TALK_FLAG_NOSKIP)`, a flag `StartWmTextMsg` already sets) is not run by
     * any capture and fails loudly.
     */
    public val eventC6WmDisplayText: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        val a = memory.u16(proc.pEventCurrent + 4)
        val b = memory.u16(proc.pEventCurrent + 6)
        text.startWmTextMsg(a or b)
        check(proc.evStateBits and EV_STATE_0020 == 0) { "not verified: EV_STATE_0020 at C6" }
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr_gmap.c: EventC7_
     *
     * Shows the text box at the bottom of the screen.
     */
    public val eventC7: EventHandler = EventHandler { proc: EventEngineProc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        text.showWmText(1)
        EVC_ADVANCE_YIELD
    }
}
