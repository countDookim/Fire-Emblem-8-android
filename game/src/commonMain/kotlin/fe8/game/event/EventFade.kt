package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gbafe.render.ScreenFade

/**
 * The fade command of `src/eventscr.c` (0x17 FADU, FADI, FAWU, FAWI): fades the screen in or
 * out through black or white during a cutscene, on the blend fades of [fade].
 *
 * @param memory script words (the command arguments).
 * @param fade the screen fade service (blend fades and the display layer switches).
 */
public class EventFade(
    private val memory: ScriptMemory,
    private val fade: ScreenFade,
) {
    /** Registers the fade command 0x17 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x17, event17Fade)
    }

    /**
     * Ported from src/eventscr.c: Event17_Fade
     *
     * Verified: subcodes 0 (FADU) and 1 (FADI) by Chapter1ScriptCoreDifferentialTest on PC-cap1
     * (`f10497..f20231`: `evStateBits`, cursor, engine lock count, fade proc speed and level).
     * Subcodes 2 (FAWU) and 3 (FAWI) are Transcribed (not verified: no capture runs them); the
     * skipping branch and the `SetDispEnable` call are not compared there (the LCD registers
     * exist only in the sparse dumps; CutsceneStartSkipDifferentialTest compares FADU's).
     *
     * Not while skipping. Subcode 0 (FADU) and 2 (FAWU) fade in from black / white, clear
     * `EV_STATE_FADEDIN` and enable all layers; 1 (FADI) and 3 (FAWI) fade out to black / white
     * and set it. The fade blocks the event proc (the command yields).
     */
    public val event17Fade: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) return@EventHandler EVC_ADVANCE_CONTINUE
        val currentCmd = proc.pEventCurrent
        val fadeSpeed = memory.u16(currentCmd + 2)
        val parent = proc.proc
        when (memory.u16(currentCmd) and 0xF) {
            0 -> {
                if (parent != null) fade.startLockingFadeFromBlack(fadeSpeed, parent)
                proc.evStateBits = proc.evStateBits and EV_STATE_FADEDIN.inv()
                fade.lcd.setDispEnable(1, 1, 1, 1, 1)
            }
            1 -> {
                if (parent != null) fade.startLockingFadeToBlack(fadeSpeed, parent)
                proc.evStateBits = proc.evStateBits or EV_STATE_FADEDIN
            }
            2 -> {
                if (parent != null) fade.startLockingFadeFromWhite(fadeSpeed, parent)
                proc.evStateBits = proc.evStateBits and EV_STATE_FADEDIN.inv()
                fade.lcd.setDispEnable(1, 1, 1, 1, 1)
            }
            3 -> {
                if (parent != null) fade.startLockingFadeToWhite(fadeSpeed, parent)
                proc.evStateBits = proc.evStateBits or EV_STATE_FADEDIN
            }
            else -> return@EventHandler EVC_ERROR
        }
        EVC_ADVANCE_YIELD
    }
}
