package fe8.testing

import fe8.game.bm.BmMap
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The map fade after an enemy's action (EP-9c, src/bmxfade.c) on the `CpPerformSim` set-up of
 * [CpPerformActionDifferentialTest]: `AiRefreshMap` draws the map onto BG2 ([fe8.game.bm.BmMapRender.renderBmMapOnBg2]),
 * refreshes the entity maps and starts `sProcScr_BMXFADE`. Windows: the boss f3832..3848
 * (`ram_trace_prologue_clear`), Soldier 1 f2018..2034 and Soldier 2 f2723..2739
 * (`ram_trace_prologue_enemy_phase`). The post-action event of the original (EP-11) is not run,
 * so the frame the fade starts in is the sync point: engine step k after it is compared with
 * capture step F0 + k. Per step: the proc (existence by `DoesBMXFADEExist`, script position,
 * `counter`, `game_lock`), `gBmSt.lock`, BLDCNT, the blend coefficients, BG2CNT, the whole BG2
 * tilemap buffer and the whole palette buffer. At F0 also the unit, fog and hidden maps. The
 * display state before the fade (LCD buffer, palette, BG2) is loaded from capture step F0 - 1.
 */
class BmxFadeDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun compare(s: CpPerformSim, f: Int, at: String) {
        val names = ProcArrayDump.romNames()
        val dump = ProcArrayDump.ofWatch(s.data.watch("procs"), f, names)
        val proc = dump.find(PROC_SCR_BMXFADE)
        val ours = s.engine.find(s.fade.script)
        assertEquals(proc != null, s.fade.doesBmxFadeExist(), "sProcScr_BMXFADE exists $at")
        if (proc != null) {
            assertEquals(proc.scrCur, ours!!.scrCur, "sProcScr_BMXFADE script position $at")
            val state = ours.state as fe8.game.bm.BmxFadeState
            assertEquals(proc.u16(0x4C).toShort().toInt(), state.counter, "BMXFADE counter $at")
            assertEquals(proc.u16(0x4E), state.gameLock, "BMXFADE game_lock $at")
        }
        val e = s.ewram(f)
        val i = s.iwram(f)
        val lcd = s.hardware.lcd
        assertEquals(e[BM_ST - GbaMem.EWRAM + 1].toInt(), s.bm.lock, "gBmSt.lock $at")
        assertEquals(GbaMem.u16(i, GbaMem.IWRAM, LCD + 0x3C), lcd.bldcnt, "BLDCNT $at")
        assertEquals(i[LCD - GbaMem.IWRAM + 0x44].toInt() and 0xFF, lcd.blendCoeffA, "EVA $at")
        assertEquals(i[LCD - GbaMem.IWRAM + 0x45].toInt() and 0xFF, lcd.blendCoeffB, "EVB $at")
        assertEquals(i[LCD - GbaMem.IWRAM + 0x46].toInt() and 0xFF, lcd.blendY, "EVY $at")
        assertEquals(GbaMem.u16(i, GbaMem.IWRAM, LCD + 0x14), lcd.bgCnt[2], "BG2CNT $at")
        val bg2 = s.hardware.mapBuffer(2)
        for (t in bg2.indices) {
            assertEquals(
                GbaMem.u16(e, GbaMem.EWRAM, BG2_BUFFER + 2 * t), bg2[t].toInt() and 0xFFFF,
                "gBG2TilemapBuffer[$t] $at",
            )
        }
        for (c in s.hardware.palette.indices) {
            assertEquals(
                GbaMem.u16(e, GbaMem.EWRAM, PALETTE_BUFFER + 2 * c),
                s.hardware.palette[c].toInt() and 0xFFFF,
                "gPaletteBuffer[$c] $at",
            )
        }
    }

    /** Pool [pointer] (`gBmMapUnit`, ...) of capture frame [f] against [pool]. */
    private fun pool(s: CpPerformSim, f: Int, pointer: Int, pool: BmMap.Pool, what: String) {
        val e = s.ewram(f)
        fun byte(a: Int) = e[a - GbaMem.EWRAM].toInt() and 0xFF
        fun word(a: Int) = (0..3).sumOf { byte(a + it) shl (8 * it) }
        val rows = word(pointer)
        for (y in 0 until s.map.sizeY) {
            val row = word(rows + 4 * y)
            for (x in 0 until s.map.sizeX) {
                assertEquals(byte(row + x), pool[y, x] and 0xFF, "$what ($x, $y) f$f")
            }
        }
    }

    private fun pools(s: CpPerformSim, f: Int) {
        pool(s, f, GBM_MAP_UNIT, s.map.unit, "gBmMapUnit")
        pool(s, f, GBM_MAP_FOG, s.map.fog, "gBmMapFog")
        pool(s, f, GBM_MAP_HIDDEN, s.map.hidden, "gBmMapHidden")
    }

    /** Runs [s] to the frame the fade starts in, then compares [first]..[first] + 16. */
    private fun fadeWindow(s: CpPerformSim, first: Int) {
        var guard = 0
        // The display state before the fade (BLDCNT, palette: the map battle and the sprites, M6-H,
        // change them in the original) is the capture's input, not ported.
        while (!s.fade.doesBmxFadeExist() && guard++ < 600) {
            s.loadDisplay(first - 1)
            s.frame()
        }
        assertTrue(s.fade.doesBmxFadeExist(), "the fade starts")
        pools(s, first)
        for (k in 0..16) {
            compare(s, first + k, "f${first + k}")
            s.frame()
        }
    }

    @Test
    @VerifiesPort(
        "NewBMXFADE", "bmxfade_init", "bmxfade_loop", "Destruct6CBMXFADE", "DoesBMXFADEExist",
        "RenderBmMapOnBg2", "RefreshEntityBmMaps",
    )
    fun `the fade after the boss moves as the capture f3832 to f3848`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(ref, romFile, "ram_trace_prologue_clear", 3803, 0x81)
        fadeWindow(s, 3832)
    }

    @Test
    @VerifiesPort(
        "NewBMXFADE", "bmxfade_init", "bmxfade_loop", "Destruct6CBMXFADE", "DoesBMXFADEExist",
        "RenderBmMapOnBg2", "RefreshEntityBmMaps",
    )
    fun `the fade after Soldier 1 dies as the capture f2018 to f2034`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(
            ref, romFile, "ram_trace_prologue_enemy_phase", 1613, 0x82, 1614,
        )
        fadeWindow(s, 2018)
    }

    @Test
    @VerifiesPort(
        "NewBMXFADE", "bmxfade_init", "bmxfade_loop", "Destruct6CBMXFADE", "DoesBMXFADEExist",
        "RenderBmMapOnBg2", "RefreshEntityBmMaps",
    )
    fun `the fade after Soldier 2 dies as the capture f2723 to f2739`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(
            ref, romFile, "ram_trace_prologue_enemy_phase", 2038, 0x83, 2038,
        )
        fadeWindow(s, 2723)
    }

    @Test
    @VerifiesPort("RefreshEntityBmMaps")
    fun `the decision loop's refresh leaves the maps of the capture f3805`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(ref, romFile, "ram_trace_prologue_clear", 3803, 0x81)
        // The decision's refresh (f3803 in the capture, boss on the map) and the perform proc's
        // `UnitBeginAction` (which takes the boss off the unit map, f3804) both run in the first
        // engine frame, so the pools equal the capture's f3805.
        s.frame()
        pools(s, 3805)
    }

    private companion object {
        const val PROC_SCR_BMXFADE = 0x0859ADC8
        const val BM_ST = 0x0202BCB0
        const val LCD = 0x03003080
        const val PALETTE_BUFFER = 0x020228A8
        const val BG2_BUFFER = 0x02023CA8
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_FOG = 0x0202E4E8
        const val GBM_MAP_HIDDEN = 0x0202E4EC
    }
}
