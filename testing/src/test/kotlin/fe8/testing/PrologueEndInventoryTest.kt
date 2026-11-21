package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, PC-0) of the Prologue ending event, GameControl chapter switch,
 * and Chapter 1 start windows in `ram_trace_prologue_end` (PC-cap1). Excludes save-menu
 * internals (`f9444..9862`, covered by PC-4i). Proc rows are built with
 * [EnemyPhaseInventoryTest.rows] from the `procs` watch (1-based step index, matching `f`/`r`
 * dumps). Function lists and PORT row boundaries derived from these observations are in
 * docs/plans/M6C-J-breakdown.md ("PC-0 inventory").
 */
class PrologueEndInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        assertEquals(
            expected.sorted(),
            inventory.rows(RUN, first, last).sorted(),
            "$RUN f$first..f$last",
        )
    }

    private fun endRun(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")
        return run
    }

    @Test
    fun `ending scene start matches the procs watch f4894 to f5000`() {
        check(ENDING_START_ROWS, 4894, 5000)
    }

    @Test
    fun `ending scene finish and post chapter switch match the procs watch f9406 to f9444`() {
        check(ENDING_FINISH_AND_SWITCH_ROWS, 9406, 9444)
    }

    @Test
    fun `chapter 1 start and chapter intro match the procs watch f9863 to f10298`() {
        check(CHAPTER1_START_ROWS, 9863, 10298)
    }

    @Test
    fun `ending event keeps StdEventEngine alive until f9440 without save menu procs`() {
        val run = endRun()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = run.watch("procs")
        for (step in 4894..9440) {
            val procs = ProcArrayDump.ofWatch(trace, step, names)
            assertNotNull(procs.find("ProcScr_StdEventEngine"), "missing event at f$step")
            assertNull(procs.find("gProcScr_SaveMenuPostChapter"), "early save menu at f$step")
        }
    }

    @Test
    fun `win condition and ending event fields match PC-cap1 watches`() {
        val run = endRun()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        val flags = run.watch("flags")
        val playSt = run.watch("gplayst")

        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)

        // PC-1: f4893 -> f4894 starts EventScr_Prologue_EndingScene via RunPotentialWaitEvents
        val d4893 = dump(4893)
        assertEquals(33, d4893.find("gProcScr_PlayerPhase")!!.scrCur)
        assertNull(d4893.find("ProcScr_StdEventEngine"))
        assertEquals(0x00, flags.u8(4893, 3))
        assertEquals(0xC3, flags.u8(4893, 32))

        val d4894 = dump(4894)
        assertEquals(35, d4894.find("gProcScr_PlayerPhase")!!.scrCur)
        val ev4894 = d4894.find("ProcScr_StdEventEngine")!!
        assertEquals(5, ev4894.scrCur)
        assertEquals(EVENT_PROLOGUE_ENDING_SCENE, ev4894.u32(EV_CURRENT))
        assertEquals(0x0002, ev4894.u16(EV_STATE_BITS))
        assertNotNull(d4894.find("gMusicProc3Script"))
        assertEquals(0x80, flags.u8(4894, 3)) // EVFLAG_HIDE_BLINKING_ICON (0x84)
        assertEquals(0xE7, flags.u8(4894, 32)) // adds EVFLAG_WIN (3) and EVFLAG_DEFEAT_ALL (6)

        // PC-2a: f4911 -> f4912 locks map units (Event23_DisaleMapDisp -> LockMus)
        val d4911 = dump(4911)
        assertEquals(0, d4911.find("ProcScr_Mu")!!.u8(PROC_LOCK_CNT))
        assertEquals(0x0102, d4911.find("ProcScr_StdEventEngine")!!.u16(EV_STATE_BITS))

        val d4912 = dump(4912)
        assertEquals(1, d4912.find("ProcScr_Mu")!!.u8(PROC_LOCK_CNT))
        assertEquals(0x1102, d4912.find("ProcScr_StdEventEngine")!!.u16(EV_STATE_BITS))

        // PC-2 tail: f9423 -> f9424 runs REMA, ENUTx11, MNC2(1), EventScr_08592170
        val d9423 = dump(9423)
        assertEquals(1, d9423.find("ProcScr_Mu")!!.u8(PROC_LOCK_CNT))
        assertEquals(0x00, playSt.u8(9423, PLAYST_SAVE_MENU_BYTE))
        assertEquals(4, d9423.find("gProcScr_GameControl")!!.u8(GC_NEXT_ACTION))

        val d9424 = dump(9424)
        assertEquals(0, d9424.find("ProcScr_Mu")!!.u8(PROC_LOCK_CNT))
        assertEquals(0x04, playSt.u8(9424, PLAYST_SAVE_MENU_BYTE)) // save_menu_type = 2
        val gc9424 = d9424.find("gProcScr_GameControl")!!
        assertEquals(2, gc9424.u8(GC_NEXT_ACTION)) // GAME_ACTION_USR_SKIPPED
        assertEquals(1, gc9424.u8(GC_NEXT_CHAPTER))
        val ev9424 = d9424.find("ProcScr_StdEventEngine")!!
        assertEquals(EVENT_SCR_08592170, ev9424.u32(EV_CURRENT))
        assertEquals(0x0D12, ev9424.u16(EV_STATE_BITS))

        // EventScr_08592114 + EventEngine_OnEnd + EndBMapMain teardown (f9438..f9441)
        assertEquals(
            EVENT_SCR_08592114,
            dump(9438).find("ProcScr_StdEventEngine")!!.u32(EV_CURRENT),
        )
        assertEquals(6, dump(9439).find("ProcScr_StdEventEngine")!!.scrCur)
        val d9440 = dump(9440)
        assertNull(d9440.find("sProc_BMVSync"))
        assertNull(d9440.find("gProc_MapTask"))
        val d9441 = dump(9441)
        assertNull(d9441.find("ProcScr_StdEventEngine"))
        assertNull(d9441.find("gProc_BMapMain"))
        assertNull(d9441.find("gProcScr_PlayerPhase"))
        assertEquals(0, d9441.find("gProcScr_GameControl")!!.u8(GC_LOCK))
    }

    @Test
    fun `chapter switch and chapter 1 start update RNG, rankings, stats, and units`() {
        val run = endRun()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        val playSt = run.watch("gplayst")
        val rng = run.watch("rng")
        val blue = run.watch("blue")
        val flags = run.watch("flags")

        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        fun rngWords(step: Int) = listOf(rng.u16(step, 0), rng.u16(step, 2), rng.u16(step, 4))

        // PC-3: GameControl post-chapter switch f9441..f9444
        assertEquals(81, dump(9441).find("gProcScr_GameControl")!!.scrCur)
        assertEquals(69, dump(9442).find("gProcScr_GameControl")!!.scrCur)
        assertEquals(71, dump(9443).find("gProcScr_GameControl")!!.scrCur)
        assertEquals(0, playSt.u8(9443, PLAYST_CHAPTER_INDEX))
        assertEquals(listOf(60569, 20783, 3607), rngWords(9443))
        assertEquals(13, blue.u8(9443, UNIT_CUR_HP))
        assertEquals(1, blue.u32(9443, UNIT_STATE))
        assertEquals(7, blue.u8(9443, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(0, blue.u32(9443, UNIT_SIZE + UNIT_STATE))

        val d9444 = dump(9444)
        val gc9444 = d9444.find("gProcScr_GameControl")!!
        assertEquals(76, gc9444.scrCur)
        assertEquals(1, gc9444.u8(PROC_LOCK_CNT))
        assertNotNull(d9444.find("gProcScr_SaveMenuPostChapter"))
        assertEquals(1, playSt.u8(9444, PLAYST_CHAPTER_INDEX))
        val expectedRng = listOf(35448, 26949, 25418)
        assertEquals(expectedRng, rngWords(9444))
        assertEquals(30, blue.u8(9444, UNIT_CUR_HP))
        assertEquals(9, blue.u32(9444, UNIT_STATE)) // US_HIDDEN | US_NOT_DEPLOYED
        assertEquals(16, blue.u8(9444, UNIT_SIZE + UNIT_CUR_HP))
        assertEquals(9, blue.u32(9444, UNIT_SIZE + UNIT_STATE))

        // Verify gChapterStats[0], gGmMonsterRnState, and ranking fields at f09444
        val ew9444 = run.region("f09444", "ewram")
        val iw9444 = run.region("f09444", "iwram")
        assertEquals(0x00360180, u32(ew9444, ADDR_CHAPTER_STATS - EWRAM_BASE))
        val monsterRn = (0..2).map { i -> u16(iw9444, ADDR_MONSTER_RN - IWRAM_BASE + i * 2) }
        assertEquals(expectedRng, monsterRn)
        assertEquals(0x50, playSt.u32(9444, PLAYST_OVERALL_RANK))
        assertTrue(playSt.u32(9444, PLAYST_RANK_BLOCK) != 0)

        // PC-5: GameControl resumes at f9863, branches to EXEC_BM_EXT at f9864 (skips WM)
        val d9863 = dump(9863)
        assertNull(d9863.find("gProcScr_SaveMenuPostChapter"))
        assertEquals(0, d9863.find("gProcScr_GameControl")!!.u8(PROC_LOCK_CNT))

        val d9864 = dump(9864)
        assertEquals(52, d9864.find("gProcScr_GameControl")!!.scrCur)
        assertNull(d9864.find("ProcScr_WorldMapWrapper"))

        // StartBattleMap spans f9865..f9869 (scrCur 53), starts BMapMain at f9870 (scrCur 54)
        assertEquals(53, dump(9865).find("gProcScr_GameControl")!!.scrCur)
        assertEquals(0x40, playSt.u8(9865, PLAYST_FACTION))
        assertEquals(0, playSt.u16(9865, PLAYST_TURN))
        assertEquals(0x00, flags.u8(9865, 32))

        val d9870 = dump(9870)
        assertEquals(54, d9870.find("gProcScr_GameControl")!!.scrCur)
        assertEquals(1, d9870.find("gProc_BMapMain")!!.scrCur)
        assertEquals(10739, playSt.u32(9870, PLAYST_CHAPTER_STARTED))
        assertEquals(1, blue.u32(9870, UNIT_STATE)) // US_NOT_DEPLOYED cleared
        assertEquals(1, blue.u32(9870, UNIT_SIZE + UNIT_STATE))

        // BmMain_StartIntroFx starts gProcScr_ChapterIntro at f9872; Ch1 events start f10298
        assertEquals(7, dump(9872).find("gProc_BMapMain")!!.scrCur)
        assertEquals(2, dump(9872).find("gProcScr_ChapterIntro")!!.scrCur)
        assertNull(dump(10297).find("gProcScr_ChapterIntro"))
        assertEquals(8, dump(10297).find("gProc_BMapMain")!!.scrCur)
        assertEquals(12, dump(10298).find("gProc_BMapMain")!!.scrCur)
        assertNotNull(dump(10298).find("ProcScr_StdEventEngine"))
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val IWRAM_BASE = 0x03000000
        const val ADDR_CHAPTER_STATS = 0x0203ECF4
        const val ADDR_MONSTER_RN = 0x0300534E

        const val EVENT_PROLOGUE_ENDING_SCENE = 0x089EF164
        const val EVENT_SCR_08592114 = 0x08592114
        const val EVENT_SCR_08592170 = 0x08592170

        const val PROC_LOCK_CNT = 0x28
        const val GC_LOCK = 0x28
        const val GC_NEXT_ACTION = 0x29
        const val GC_NEXT_CHAPTER = 0x2A
        const val EV_CURRENT = 0x34
        const val EV_STATE_BITS = 0x3C

        const val PLAYST_CHAPTER_STARTED = 0x04
        const val PLAYST_CHAPTER_INDEX = 0x0E
        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TURN = 0x10
        const val PLAYST_OVERALL_RANK = 0x2C
        const val PLAYST_RANK_BLOCK = 0x30
        const val PLAYST_SAVE_MENU_BYTE = 0x4A

        const val UNIT_SIZE = 0x48
        const val UNIT_STATE = 0x0C
        const val UNIT_CUR_HP = 0x13

        fun u16(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

        fun u32(buf: ByteArray, off: Int): Int =
            u16(buf, off) or (u16(buf, off + 2) shl 16)

        val ENDING_START_ROWS = listOf(
            "ProcScr_FadeFromBlack <- ProcScr_StdEventEngine 4916-4931",
            "ProcScr_FadeToBlack <- ProcScr_StdEventEngine 4895-4910",
            "ProcScr_Mu <- tree 5 4894-5000",
            "ProcScr_PalFade <- gProcScr_E_FACE 4939-4951",
            "ProcScr_PalFade <- gProcScr_E_FACE 4939-4951",
            "ProcScr_StdEventEngine <- tree 3 4894-5000",
            "ProcScr_TemporaryLock <- gProcScr_Talk 4939-4947",
            "ProcScr_TemporaryLock <- gProcScr_Talk 4939-4947",
            "gMusicProc3Script <- tree 3 4894-4944",
            "gProcScr_0859124C <- gProcScr_E_FACE 4939-5000",
            "gProcScr_0859124C <- gProcScr_E_FACE 4939-5000",
            "gProcScr_E_FACE <- tree 5 4939-5000",
            "gProcScr_E_FACE <- tree 5 4939-5000",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4939-5000",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 4939-5000",
            "gProcScr_GameControl <- tree 3 4894-5000",
            "gProcScr_PlayerPhase <- gProc_BMapMain 4894-5000",
            "gProcScr_Talk <- tree 3 4933-5000",
            "gProcScr_TalkBubbleOpen <- tree 3 4949-4960",
            "gProcScr_TalkOpen <- gProcScr_Talk 4949-4961",
            "gProcScr_TalkSkipListener <- tree 3 4935-5000",
            "gProc_BMapMain <- tree 2 4894-5000",
            "gProc_MapTask <- tree 4 4894-5000",
            "sProc_BMVSync <- tree 0 4894-5000",
        )

        val ENDING_FINISH_AND_SWITCH_ROWS = listOf(
            "ProcScr_FadeToBlack <- ProcScr_StdEventEngine 9406-9421",
            "ProcScr_Mu <- tree 5 9406-9439",
            "ProcScr_StdEventEngine <- tree 3 9406-9440",
            "gProcScr_0859124C <- gProcScr_E_FACE 9406-9422",
            "gProcScr_0859124C <- gProcScr_E_FACE 9406-9422",
            "gProcScr_E_FACE <- tree 5 9406-9422",
            "gProcScr_E_FACE <- tree 5 9406-9422",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 9406-9422",
            "gProcScr_FaceBlink <- gProcScr_E_FACE 9406-9422",
            "gProcScr_GameControl <- tree 3 9406-9444",
            "gProcScr_PlayerPhase <- gProc_BMapMain 9406-9440",
            "gProcScr_SaveMenuPostChapter <- gProcScr_GameControl 9444-9444",
            "gProc_BMapMain <- tree 2 9406-9440",
            "gProc_MapTask <- tree 4 9406-9430",
            "gProc_MapTask <- tree 4 9431-9439",
            "sProc_BMVSync <- tree 0 9406-9439",
        )

        val CHAPTER1_START_ROWS = listOf(
            "ProcScr_ChapterIntro_Bg1And3Scroll <- gProcScr_ChapterIntro 9875-10280",
            "ProcScr_ChapterIntro_Bg2Scroll <- gProcScr_ChapterIntro 10076-10280",
            "ProcScr_ChapterIntro_KeyListen <- gProcScr_ChapterIntro 9935-10199",
            "ProcScr_ChapterIntro_LightBurst <- gProcScr_ChapterIntro 10005-10051",
            "ProcScr_ChapterIntro_PutDecalSprite <- gProcScr_ChapterIntro 10076-10280",
            "ProcScr_ChapterIntro_RevealDecalSprite <- " +
                "ProcScr_ChapterIntro_PutDecalSprite 10106-10146",
            "ProcScr_StdEventEngine <- tree 3 10298-10298",
            "gProcScr_ChapterIntro <- gProc_BMapMain 9872-10296",
            "gProcScr_GameControl <- tree 3 9863-10298",
            "gProc_BMapMain <- tree 2 9870-10298",
            "gProc_MapTask <- tree 4 9870-10298",
            "sProc_BMVSync <- tree 0 9870-10298",
        )
    }
}
