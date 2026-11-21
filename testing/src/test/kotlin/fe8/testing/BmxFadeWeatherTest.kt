package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.BmxFade
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `bmxfade_init` calls `InitBmBgLayers`, which puts BG3 level with BG2 (priority 2 instead of 3)
 * only for `WEATHER_CLOUDS` (7, include/types.h); weather 4 is `WEATHER_RAIN`.
 */
class BmxFadeWeatherTest {
    /** BG3's priority (BG3CNT bits 0..1) after `bmxfadeInit` in [weather]. */
    private fun bg3Priority(weather: Int): Int {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val playSt = PlaySt().also { it.chapterWeatherId = weather }
        val fade = BmxFade(
            engine, BmSt(), UnitTable(RomView.ofRom(ByteArray(0x100))), hardware, playSt,
        )
        val proc = engine.start(fade.script, 3)
        hardware.lcd.bgCnt[3] = 0
        fade.bmxfadeInit(proc)
        return hardware.lcd.bgCnt[3] and 3
    }

    @Test
    fun `the fade init levels BG3 with BG2 only in the clouds weather`() {
        assertEquals(2, bg3Priority(7), "WEATHER_CLOUDS")
        assertEquals(3, bg3Priority(4), "WEATHER_RAIN")
        assertEquals(3, bg3Priority(0), "WEATHER_FINE")
    }
}
