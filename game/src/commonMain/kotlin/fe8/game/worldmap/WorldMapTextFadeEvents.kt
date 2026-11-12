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

/**
 * The unported callees of the world map text-box and fade commands
 * ([WorldMapTextFadeEvents]).
 */
public class WmTextFadeHooks(
    /**
     * `sub_80BA008(delay)` (src/worldmap_main.c:1734; followed by the empty `nullsub_5`): sets the
     * world map main proc's `timer` to the argument and jumps it to label 0.
     */
    public val sub80BA008: (Int) -> Unit = {},
    /**
     * `EndWM(proc)` (src/worldmap_main.c:1744): ends the world map: its fade-in and main procs,
     * the dialogue screen and the AP sprite procs, and sets up the normal BGs.
     */
    public val endWm: (EventEngineProc) -> Unit = {},
    /**
     * `GmMuEntryStartShow(speed, blend)` (src/worldmap_entry.c:687): starts the show animation
     * of the `GmapMuEntry` proc, [speed] frames long; `blend` is the exponent passed to
     * `_DivArm1` (0 = linear); no-op if one is running.
     */
    public val gmMuEntryStartShow: (Int, Int) -> Unit = { _, _ -> },
    /**
     * `GmMuEntryStartHide(a, b)` (src/worldmap_entry.c:710): the reverse animation; `a` is the
     * frames, `b` the `_DivArm1` exponent, as in [gmMuEntryStartShow].
     */
    public val gmMuEntryStartHide: (Int, Int) -> Unit = { _, _ -> },
    /** `EndGmMuEntry()`: ends the `GmapMuEntry` proc. */
    public val endGmMuEntry: () -> Unit = {},
    /** `GetGmMuEntryFlag()`: true while a show or hide animation is running. */
    public val gmMuEntryFlag: () -> Boolean = { false },
    /**
     * `StartGmapLineFade(arg, NULL)` (src/worldmap_line_fade.c:163): starts the scanline-wise
     * "fancy fade"; `arg` 2 gives a 100-frame fade, anything else 50 frames
     * (`GmapLineFade_Init`).
     */
    public val startGmapLineFade: (Int) -> Unit = {},
    /** `IsGmapLineFadeActive()`: whether the fancy fade is running. */
    public val isGmapLineFadeActive: () -> Boolean = { false },
)

/**
 * The world map event commands `Event80..82` (skip/fade out/end the world map) and
 * `EventAF..B3` (the animated box show/hide and the fancy fade, with their waits) of
 * `src/eventscr_gmap.c`. `EventAF`,
 * `EventB1`, `EventB2` and `EventB3` are verified through the event VM in
 * WorldMapEventHostTest (Prologue steps 4907-5100); the others are transcribed (not run). The
 * Prologue test registers them one by one; [registerOn] adds the whole range.
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

    /** `EVENT_IS_SKIPPING(proc)`: the player is skipping the event (`EV_STATE_SKIPPING`). */
    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    /**
     * Halfword [index] of the running command (0 = the opcode); the decomp's
     * `EVT_CMD_ARGV(cmd)[n]` is `arg16(proc, n + 1)`.
     */
    private fun arg16(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 * index)

    /**
     * Transcribed from src/eventscr_gmap.c: Event80_WmSkip_Unsure (not verified: no @VerifiesPort
     * test names it).
     *
     * `Event80`: passes its 32-bit argument (`EVT_CMD_ARG32_LE`) to the world map main proc
     * through [WmTextFadeHooks.sub80BA008] and yields. What the decomp's "skip, unsure" name
     * stands for is not documented.
     */
    public val event80WmSkipUnsure: EventHandler = EventHandler { proc ->
        hooks.sub80BA008(arg16(proc, 2) or (arg16(proc, 3) shl 16))
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr_gmap.c: Event81_WmFadeOut (not verified: no @VerifiesPort test
     * names it): while skipping all layers go off at once, else a slow (speed 4) locking fade to
     * black (`StartSlowLockingFadeToBlack` = `StartLockingFadeToBlack(0x04)`, src/bmlib.c:1096)
     * that the event waits for.
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
     *
     * `Event82`: ends the world map ([WmTextFadeHooks.endWm]).
     */
    public val event82WmEnd: EventHandler = EventHandler { proc ->
        hooks.endWm(proc)
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventAF_WmShowTextBox
     *
     * Starts the show animation of the world map `GmapMuEntry` proc (the decomp calls the
     * command "show text box"), unless skipping. The speed (frames) and blend (easing exponent)
     * are the halfwords at `+4` and `+6`.
     */
    public val eventAFWmShowTextBox: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.gmMuEntryStartShow(arg16(proc, 2), arg16(proc, 3))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB0_WmHideTextBox_Bugged (not verified: no
     * @VerifiesPort test names it).
     *
     * `EventB0`: starts the hide animation of the `GmapMuEntry` proc, unless skipping. What the
     * decomp's "Bugged" refers to is not documented.
     */
    public val eventB0WmHideTextBoxBugged: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.gmMuEntryStartHide(arg16(proc, 2), arg16(proc, 3))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventB1_WmTextBoxWait
     *
     * Waits for the show/hide animation of `EventAF` / `EventB0` to finish (the command stays
     * until [WmTextFadeHooks.gmMuEntryFlag] clears); when skipping it ends the animation instead.
     * The skipping branch (`EndGmMuEntry`) is not run by the capture; it is kept as printed.
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
     * Ported from src/eventscr_gmap.c: EventB2_WmFancyFade
     *
     * Starts the scanline-wise fancy fade with the command's 32-bit argument and yields.
     */
    public val eventB2WmFancyFade: EventHandler = EventHandler { proc ->
        hooks.startGmapLineFade(arg16(proc, 2) or (arg16(proc, 3) shl 16))
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr_gmap.c: EventB3_WmFancyFadeWait
     *
     * Holds the event until the fancy fade of `EventB2` has finished.
     */
    public val eventB3WmFancyFadeWait: EventHandler = EventHandler { _ ->
        if (!hooks.isGmapLineFadeActive()) EVC_ADVANCE_YIELD else EVC_STOP_YIELD
    }
}
