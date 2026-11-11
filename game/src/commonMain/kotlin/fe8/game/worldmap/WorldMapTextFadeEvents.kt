package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory
import gbafe.render.ScreenFade

/** The unported callees of the world map text-box and fade commands. */
public class WmTextFadeHooks(
    /** `sub_80BA008(delay)` (followed by the no-op `nullsub_5`). */
    public val sub80BA008: (Int) -> Unit = {},
    /** `EndWM(proc)`. */
    public val endWm: (EventEngineProc) -> Unit = {},
    /** `GmMuEntryStartShow(speed, blend)`. */
    public val gmMuEntryStartShow: (Int, Int) -> Unit = { _, _ -> },
    /** `GmMuEntryStartHide(a, b)`. */
    public val gmMuEntryStartHide: (Int, Int) -> Unit = { _, _ -> },
    /** `EndGmMuEntry()`. */
    public val endGmMuEntry: () -> Unit = {},
    /** `GetGmMuEntryFlag()`. */
    public val gmMuEntryFlag: () -> Boolean = { false },
    /** `StartGmapLineFade(arg, NULL)`. */
    public val startGmapLineFade: (Int) -> Unit = {},
    /** `IsGmapLineFadeActive()`. */
    public val isGmapLineFadeActive: () -> Boolean = { false },
)

/**
 * The world map commands `Event80..82` and `EventAF..B3` of `src/eventscr_gmap.c`. All handlers
 * are transcribed (not verified: no trace step runs them through the event engine; the opening
 * event VM comparison is D11-wm8a..f). They are not registered anywhere yet; [registerOn] does it.
 *
 * @param memory script words (the command arguments)
 * @param fade the screen fade service
 * @param hooks the unported callees
 */
public class WorldMapTextFadeEvents(
    private val memory: ScriptMemory,
    private val fade: ScreenFade,
    private val hooks: WmTextFadeHooks,
) {
    /** Registers the handlers under their opcodes (0x80-0x82, 0xAF-0xB3) on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x80, event80WmSkipUnsure)
        engine.register(0x81, event81WmFadeOut)
        engine.register(0x82, event82WmEnd)
        engine.register(0xAF, eventAFWmShowTextBox)
        engine.register(0xB0, eventB0WmHideTextBoxBugged)
        engine.register(0xB1, eventB1WmTextBoxWait)
        engine.register(0xB2, eventB2WmFancyFade)
        engine.register(0xB3, eventB3WmFancyFadeWait)
    }

    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    private fun arg16(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 * index)

    /**
     * Transcribed from src/eventscr_gmap.c: Event80_WmSkip_Unsure (not verified: no @VerifiesPort
     * test names it).
     */
    public val event80WmSkipUnsure: EventHandler = EventHandler { proc ->
        hooks.sub80BA008(arg16(proc, 2) or (arg16(proc, 3) shl 16))
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr_gmap.c: Event81_WmFadeOut (not verified: no @VerifiesPort test
     * names it): while skipping all layers go off at once, else a slow (speed 4) locking fade to
     * black.
     */
    public val event81WmFadeOut: EventHandler = EventHandler { proc ->
        if (skipping(proc)) {
            fade.lcd.setDispEnable(0, 0, 0, 0, 0)
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        proc.proc?.let { fade.startLockingFadeToBlack(4, it) }
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr_gmap.c: Event82_WmEnd (not verified: no @VerifiesPort test
     * names it).
     */
    public val event82WmEnd: EventHandler = EventHandler { proc ->
        hooks.endWm(proc)
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventAF_WmShowTextBox (not verified: no @VerifiesPort
     * test names it).
     */
    public val eventAFWmShowTextBox: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.gmMuEntryStartShow(arg16(proc, 1), arg16(proc, 2))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB0_WmHideTextBox_Bugged (not verified: no
     * @VerifiesPort test names it).
     */
    public val eventB0WmHideTextBoxBugged: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.gmMuEntryStartHide(arg16(proc, 1), arg16(proc, 2))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB1_WmTextBoxWait (not verified: no @VerifiesPort
     * test names it).
     */
    public val eventB1WmTextBoxWait: EventHandler = EventHandler { proc ->
        if (skipping(proc)) {
            hooks.endGmMuEntry()
            EVC_ADVANCE_CONTINUE
        } else if (!hooks.gmMuEntryFlag()) {
            EVC_ADVANCE_YIELD
        } else {
            EVC_STOP_YIELD
        }
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB2_WmFancyFade (not verified: no @VerifiesPort
     * test names it).
     */
    public val eventB2WmFancyFade: EventHandler = EventHandler { proc ->
        hooks.startGmapLineFade(arg16(proc, 2) or (arg16(proc, 3) shl 16))
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB3_WmFancyFadeWait (not verified: no @VerifiesPort
     * test names it).
     */
    public val eventB3WmFancyFadeWait: EventHandler = EventHandler { _ ->
        if (!hooks.isGmapLineFadeActive()) EVC_ADVANCE_YIELD else EVC_STOP_YIELD
    }
}
