package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventHandler

/**
 * The small world map commands the Prologue's beginning event runs that no other class owns:
 * 0xC1 (`WM_SKIPWM`) and 0xC9 (`WM_REMOVETEXT`).
 *
 * @param gmData `gGMData`
 * @param hideWmText `HideWmText`
 */
public class WorldMapMiscEvents(
    private val gmData: GmData,
    private val hideWmText: () -> Unit,
) {
    /** Registers 0xC1 and 0xC9 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0xC1, eventC1SkipWm)
        engine.register(0xC9, eventC9RemoveWmText)
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventC1_SKIPWM (not verified: only the resulting
     * `gGMData` state bit at the exit is checked, by WorldMapExitTest)
     *
     * Sets `gGMData.state.bits.skip_wm` (`GM_STATE_SKIPPED`), which makes the world map exit
     * straight to the chapter intro event (`WorldMap_PostBeginningEvent`).
     */
    public val eventC1SkipWm: EventHandler = EventHandler { _ ->
        gmData.stateRaw = gmData.stateRaw or SKIP_WM
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventC9_RemoveWmText (not verified: no test checks
     * the handler against a trace)
     *
     * Hides the text box ([WorldMapText.hideWmText]), then yields unless the event is being
     * skipped (`EventC9_RemoveWmText` hides in both branches).
     */
    public val eventC9RemoveWmText: EventHandler = EventHandler { proc ->
        hideWmText()
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) EVC_ADVANCE_CONTINUE else EVC_ADVANCE_YIELD
    }

    private companion object {
        /** `GMStateBits.skip_wm` / `GM_STATE_SKIPPED` (include/worldmap.h:446). */
        const val SKIP_WM = 0x40
    }
}
