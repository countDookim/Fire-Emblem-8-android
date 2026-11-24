package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, PC-7i) of the lord-death event, game-over screen and the way
 * back to the title in `ram_trace_prologue_gameover` (PC-cap2). Proc rows are built with
 * [EnemyPhaseInventoryTest.rows] from the `procs` watch (1-based step index, matching `f`/`r`
 * dumps). Windows: lord death `f5831..5833`, `ProcScr_BmGameOver` `f5833..5953`,
 * `ProcScr_GameOverScreen` `f5953..6293` (fade in until `f6123`, idle from `f6124`, A at `f6260`,
 * fade out `f6261..6293`), cleanup `f6294..6295`, title `f6297`. The function lists of the PORT
 * rows PC-7a/b, PC-8a/b/h are in docs/TASKS.md.
 */
class GameOverInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        assertEquals(
            expected.sorted(),
            inventory.rows(RUN, first, last).sorted(),
            "$RUN f$first..f$last",
        )
    }

    private fun run(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("displayio"), "$RUN watches missing")
        return run
    }

    @Test
    fun `death, lord-death event start and music fade match the procs watch f5784 to f5840`() {
        check(DEATH_ROWS, 5784, 5840)
    }

    @Test
    fun `fade to black and game over screen start match the procs watch f5841 to f5960`() {
        check(SCREEN_START_ROWS, 5841, 5960)
    }

    @Test
    fun `fade out, screen end and title start match the procs watch f6255 to f6330`() {
        check(SCREEN_END_ROWS, 6255, 6330)
    }

    @Test
    fun `lord death writes Eirika back, starts the game over event and fades the song`() {
        val run = run()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val blue = run.watch("blue")
        val playSt = run.watch("gplayst")
        val sound = run.watch("soundst")
        val procs = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)

        assertEquals(7, blue.u8(5830, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(0, blue.u32(5830, UNIT_SIZE + UNIT_STATE))
        assertEquals(0, blue.u8(5831, UNIT_SIZE + UNIT_CUR_HP)) // unit written back dead
        assertEquals(5, blue.u32(5831, UNIT_SIZE + UNIT_STATE))
        assertNotNull(dump(5831).find("sProcScr_CombatAction"))
        assertNull(dump(5832).find("sProcScr_CombatAction"))
        assertNull(dump(5832).find("ProcScr_StdEventEngine"))
        assertNotNull(dump(5833).find("ProcScr_StdEventEngine"))
        assertNotNull(dump(5833).find("ProcScr_BmGameOver"))

        // sub_80837B0: config.disableBgm 0 -> 1 at f5833 (cleared again by the title at f6297)
        assertEquals(0, playSt.u8(5832, PLAYST_CONFIG) and 1)
        assertEquals(1, playSt.u8(5833, PLAYST_CONFIG) and 1)
        assertEquals(1, playSt.u8(6296, PLAYST_CONFIG) and 1)
        assertEquals(0, playSt.u8(6297, PLAYST_CONFIG) and 1)

        // quote song 0x3F fades (gMusicProc3Script f5833..5880); game over song 0x3E from f5881
        assertEquals(SONG_IN_SORROWS_SHROUD, sound.u16(5880, SOUND_SONG))
        assertEquals(SONG_GAME_OVER, sound.u16(5881, SOUND_SONG))
        assertEquals(SONG_GAME_OVER, sound.u16(6296, SOUND_SONG))
        assertEquals(SONG_MAIN_THEME, sound.u16(6297, SOUND_SONG))
    }

    @Test
    fun `game over screen proc counters follow fade in, idle and fade out`() {
        val run = run()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        fun screen(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
            .find("ProcScr_GameOverScreen")!!
        fun c1(step: Int) = screen(step).u16(COUNTER1).toShort().toInt()
        fun c2(step: Int) = screen(step).u16(COUNTER2).toShort().toInt()

        assertEquals(21, (5954..5970).maxOf { c1(it) }) // Init sets counter1 = 21
        assertEquals(1, c1(6107))
        assertEquals(0, c1(6115))
        assertEquals(-1, c1(6123)) // LoopFadeIn breaks
        assertEquals(1499, c2(6124)) // BeginIdle: counter2 = 1500, first LoopIdle tick
        assertEquals(1499 - (6260 - 6124), c2(6260))
        assertEquals(0, c1(6261)) // A at f6260: BeginFadeOut, LoopFadeOut counts counter1
        assertEquals(0x1F, c1(6292))
        assertNull(ProcArrayDump.ofWatch(procs, 6294, names).find("ProcScr_GameOverScreen"))
    }

    @Test
    fun `background scroll, blend and display state match the displayio watch`() {
        val run = run()
        val io = run.watch("displayio")
        // GameOverScreen_RandomScroll_Loop: BG2/BG3 HOFS/VOFS = -pos >> 8, pos += per-frame diff
        for (step in 5955..6293) {
            val k = step - 5954
            fun reg(start: Int, diff: Int) = (-(start + diff * k) shr 8) and 0x1FF
            assertEquals(reg(1234, 46), io.u16(step, IO_BG2HOFS), "bg2 x f$step")
            assertEquals(reg(5678, -90), io.u16(step, IO_BG2HOFS + 2), "bg2 y f$step")
            assertEquals(reg(6346, -16), io.u16(step, IO_BG3HOFS), "bg3 x f$step")
            assertEquals(reg(8536, -53), io.u16(step, IO_BG3HOFS + 2), "bg3 y f$step")
        }
        assertEquals(io.u16(6293, IO_BG2HOFS), io.u16(6295, IO_BG2HOFS)) // scroll child gone
        // Init configures BLDCNT (f5954 -> f5955 here) and the end disables the display (f6295)
        assertEquals(0x2864, io.u16(5955, IO_BLDCNT))
        assertEquals(0x1F00, io.u16(6294, IO_DISPCNT))
        assertEquals(0x0000, io.u16(6295, IO_DISPCNT))
        assertEquals(0x1F00, io.u16(5953, IO_DISPCNT))
    }

    @Test
    fun `GameControl returns to the title after cleanup`() {
        val run = run()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        assertNotNull(dump(6294).find("gProc_BMapMain"))
        val d6295 = dump(6295)
        assertNull(d6295.find("gProc_BMapMain"))
        assertNull(d6295.find("ProcScr_StdEventEngine"))
        assertEquals(0, d6295.find("gProcScr_GameControl")!!.u8(GC_NEXT_ACTION))
        assertNull(dump(6296).find("gProcScr_TitleScreen"))
        assertNotNull(dump(6297).find("gProcScr_TitleScreen"))
    }

    private companion object {
        const val RUN = "ram_trace_prologue_gameover"
        const val UNIT_SIZE = 0x48
        const val UNIT_STATE = 0x0C
        const val UNIT_CUR_HP = 0x13
        const val PLAYST_CONFIG = 0x41
        const val SOUND_SONG = 4
        const val SONG_IN_SORROWS_SHROUD = 0x3F
        const val SONG_GAME_OVER = 0x3E
        const val SONG_MAIN_THEME = 0x43
        const val COUNTER1 = 0x4C
        const val COUNTER2 = 0x4E
        const val GC_NEXT_ACTION = 0x29
        const val IO_DISPCNT = 0x00
        const val IO_BG2HOFS = 0x18
        const val IO_BG3HOFS = 0x1C
        const val IO_BLDCNT = 0x50

        val DEATH_ROWS = listOf(
            "ProcScr_BmGameOver <- ProcScr_StdEventEngine 5833-5840",
            "ProcScr_MapAnimBattle <- tree 3 5784-5829",
            "ProcScr_Mu <- tree 5 5784-5814",
            "ProcScr_Mu <- tree 5 5784-5840",
            "ProcScr_MuDeathFade <- ProcScr_Mu 5784-5814",
            "ProcScr_MuHitFlash <- ProcScr_Mu 5784-5800",
            "ProcScr_PalFade <- ProcScr_Mu 5784-5804",
            "ProcScr_StdEventEngine <- tree 3 5833-5840",
            "gMusicProc3Script <- tree 3 5833-5840",
            "gProcScr_CpDecide <- gProcScr_CpOrder 5784-5840",
            "gProcScr_CpOrder <- gProcScr_CpPhase 5784-5840",
            "gProcScr_CpPerform <- gProcScr_CpDecide 5784-5840",
            "gProcScr_CpPhase <- gProc_BMapMain 5784-5840",
            "gProcScr_GameControl <- tree 3 5784-5840",
            "gProc_BMapMain <- tree 2 5784-5840",
            "gProc_MapTask <- tree 4 5784-5840",
            "sProcScr_CombatAction <- gProcScr_CpPerform 5784-5831",
            "sProc_BMVSync <- tree 0 5784-5840",
        )

        val SCREEN_START_ROWS = listOf(
            "ProcScr_BmGameOver <- ProcScr_StdEventEngine 5841-5960",
            "ProcScr_FadeToBlack <- tree 3 5873-5937",
            "ProcScr_GameOverScreen <- ProcScr_BmGameOver 5953-5960",
            "ProcScr_GameOverScreen_RandomScroll <- ProcScr_GameOverScreen 5954-5960",
            "ProcScr_Mu <- tree 5 5841-5952",
            "ProcScr_StdEventEngine <- tree 3 5841-5960",
            "gMusicProc3Script <- tree 3 5841-5880",
            "gProcScr_CpDecide <- gProcScr_CpOrder 5841-5960",
            "gProcScr_CpOrder <- gProcScr_CpPhase 5841-5960",
            "gProcScr_CpPerform <- gProcScr_CpDecide 5841-5960",
            "gProcScr_CpPhase <- gProc_BMapMain 5841-5960",
            "gProcScr_GameControl <- tree 3 5841-5960",
            "gProc_BMapMain <- tree 2 5841-5960",
            "gProc_MapTask <- tree 4 5841-5960",
            "sProc_BMVSync <- tree 0 5841-5960",
        )

        val SCREEN_END_ROWS = listOf(
            "ProcScr_BmGameOver <- ProcScr_StdEventEngine 6255-6294",
            "ProcScr_FadeCore <- gProcScr_TitleScreen 6304-6319",
            "ProcScr_GameOverScreen <- ProcScr_BmGameOver 6255-6293",
            "ProcScr_GameOverScreen_RandomScroll <- ProcScr_GameOverScreen 6255-6293",
            "ProcScr_StdEventEngine <- tree 3 6255-6294",
            "gProcScr_CpDecide <- gProcScr_CpOrder 6255-6294",
            "gProcScr_CpOrder <- gProcScr_CpPhase 6255-6294",
            "gProcScr_CpPerform <- gProcScr_CpDecide 6255-6294",
            "gProcScr_CpPhase <- gProc_BMapMain 6255-6294",
            "gProcScr_DrawTitleSprites <- gProcScr_TitleScreen 6304-6330",
            "gProcScr_GameControl <- tree 3 6255-6330",
            "gProcScr_TitleScreen <- gProcScr_GameControl 6297-6330",
            "gProc_BMapMain <- tree 2 6255-6294",
            "gProc_MapTask <- tree 4 6255-6294",
            "sProc_BMVSync <- tree 0 6255-6294",
        )
    }
}
