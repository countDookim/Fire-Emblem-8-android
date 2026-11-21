package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, PC-4i) of `gProcScr_SaveMenuPostChapter` (`f9444..9862`) and its
 * exit back to `gProcScr_GameControl` (`f9863`) in `ram_trace_prologue_end` (`PC-cap1`). Proc rows
 * are built with [EnemyPhaseInventoryTest.rows] from the `procs` watch (1-based step index).
 * Exact subwindows, SRAM write bracketing, and corrected `PC-4a..4g` function lists are
 * documented in docs/plans/M6C-J-breakdown.md ("PC-4i inventory").
 */
class SaveMenuInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun endRun(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")
        return run
    }

    @Test
    fun `post chapter save menu proc rows match the procs watch f9444 to f9863`() {
        assertEquals(
            SAVE_MENU_ROWS.sorted(),
            inventory.rows(RUN, 9444, 9863).sorted(),
            "$RUN f9444..f9863",
        )
    }

    @Test
    fun `entry, confirm prompt, slot 0 SRAM write, flip scroll, and exit match PC-cap1`() {
        val run = endRun()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        val playSt = run.watch("gplayst")

        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)

        // Entry (f9444..9461): SaveMenuInit + SetLcdChapterIdx at f9444, InitScreen f9445..9458.
        val d9444 = dump(9444)
        val gc9444 = d9444.find("gProcScr_GameControl")!!
        assertEquals(76, gc9444.scrCur)
        assertEquals(1, gc9444.u8(PROC_LOCK_CNT))
        val sm9444 = d9444.find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(5, sm9444.scrCur)
        assertEquals(0, sm9444.u8(SM_SUS_SLOT))
        assertEquals(5, sm9444.u8(SM_JUMP_LABEL))
        assertEquals(0xDC, sm9444.u8(SM_MENU_X_OFFSET))
        assertEquals(MAIN_MENU_OPTION_INVALID, sm9444.u16(SM_MAIN_SEL_BITFILE))

        val sm9458 = dump(9458).find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(6, sm9458.scrCur)
        assertEquals(listOf(0, 7, 6), (0..2).map { sm9458.u8(SM_CHAPTER_IDX + it) })
        assertEquals(listOf(1, 3, 3), (0..2).map { sm9458.u8(SM_SLOT_FLAGS + it) })
        assertEquals(listOf(0, 268746, 148852), (0..2).map { sm9458.u32(SM_PLAYED_TIME + it * 4) })
        assertEquals(0, sm9458.u8(SM_SUS_SLOT_CUR))
        assertEquals(0x100, sm9458.u16(SM_SUS_ICON_SCALE))
        assertEquals(0xFF, sm9458.u8(SM_CURSOR_SLOT))

        // f9459 starts savedraw, savedrawcursor, approc, and HBlank_BgVerticalScroll.
        val d9459 = dump(9459)
        val sm9459 = d9459.find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(7, sm9459.scrCur)
        assertEquals(d9459.find("ProcScr_savedraw")!!.address, sm9459.u32(SM_SAVEDRAW))
        assertEquals(d9459.find("ProcScr_ApProc")!!.address, sm9459.u32(SM_APPROCPTR))
        assertNotNull(d9459.find("gProcScr_SaveDrawCursor"))
        assertEquals(
            0,
            u32(run.region("r09458", "iwram"), ADDR_HBLANK1 - IWRAM_BASE),
        )
        assertEquals(
            ADDR_HBLANK_BG_VERTICAL_SCROLL,
            u32(run.region("r09459", "iwram"), ADDR_HBLANK1 - IWRAM_BASE),
        )

        // Direct slot-select branch starts BmFadeIN (f9461..9492) and reaches select loop f9494.
        val d9461 = dump(9461)
        assertEquals(13, d9461.find("gProcScr_SaveMenuPostChapter")!!.scrCur)
        assertNotNull(d9461.find("ProcScr_BmFadeIN"))
        val d9494 = dump(9494)
        assertNull(d9494.find("ProcScr_BmFadeIN"))
        assertEquals(16, d9494.find("gProcScr_SaveMenuPostChapter")!!.scrCur)

        // Only A at f9600 and A at f9800 are pressed; sus_slot stays 0 (no slot movement).
        for (step in 9444..9862) {
            val expectedKey = if (step == 9600 || step == 9800) KEY_A else 0
            assertEquals(expectedKey, run.inputs[step - 1].toInt() and 0xFFFF, "input at f$step")
            assertEquals(0, dump(step).find("gProcScr_SaveMenuPostChapter")!!.u8(SM_SUS_SLOT))
        }

        // First A (f9600): confirmation prompt opens (cursor_config 0 -> 1, BG1 row 17 written).
        assertEquals(0, dump(9599).find("gProcScr_SaveMenuPostChapter")!!.u8(SM_CURSOR_CONFIG))
        val sm9600 = dump(9600).find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(16, sm9600.scrCur)
        assertEquals(1, sm9600.u8(SM_CURSOR_CONFIG))
        val bg1Before = bg1Row17(run.region("r09599", "ewram"))
        val bg1Confirm = bg1Row17(run.region("f09600", "ewram"))
        assertTrue(bg1Before.all { it == 0 })
        assertTrue(bg1Confirm.take(10).all { it != 0 })

        // SRAM is unchanged before f9800; s09799 has valid game 0..2 and suspend 3..4.
        val s9350 = run.sram("s09350")
        val s9799 = run.sram("s09799")
        assertArrayEquals(s9350, s9799)
        assertEquals(SAVEBLOCK_KIND_GAME, s9799[blockInfo(0) + INFO_KIND].toInt() and 0xFF)
        assertEquals(SAVEBLOCK_KIND_SUSPEND, s9799[blockInfo(3) + INFO_KIND].toInt() and 0xFF)
        assertEquals(SAVEBLOCK_KIND_SUSPEND, s9799[blockInfo(4) + INFO_KIND].toInt() and 0xFF)
        assertEquals(0, s9799[GAME0_OFFSET + PLAYST_CHAPTER_INDEX].toInt() and 0xFF)
        assertEquals(0, u32(s9799, GAME0_OFFSET + PLAYST_TIME_SAVED))
        assertEquals(5140, playSt.u32(9799, PLAYST_TIME_SAVED))

        // Second A (f9800..9802): WriteGameSave(0) spans 3 frames, clears prompt at f9802.
        val s9800 = run.sram("s09800")
        val s9801 = run.sram("s09801")
        val s9802 = run.sram("s09802")
        assertEquals(10669, playSt.u32(9800, PLAYST_TIME_SAVED))
        assertEquals(SAVEBLOCK_KIND_INVALID, s9800[blockInfo(3) + INFO_KIND].toInt() and 0xFF)
        assertEquals(SAVEBLOCK_KIND_INVALID, s9800[blockInfo(4) + INFO_KIND].toInt() and 0xFF)
        assertEquals(1, s9800[GAME0_OFFSET + PLAYST_CHAPTER_INDEX].toInt() and 0xFF)
        assertEquals(10669, u32(s9800, GAME0_OFFSET + PLAYST_TIME_SAVED))
        assertNotEquals(s9800.toList(), s9801.toList())
        assertEquals(0x0EF3180F, u32(s9801, blockInfo(0) + INFO_CHECKSUM32))
        assertEquals(0x5652A716, u32(s9802, blockInfo(0) + INFO_CHECKSUM32))
        assertArrayEquals(s9802, run.sram("s10100"))

        val sm9802 = dump(9802).find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(17, sm9802.scrCur)
        assertEquals(0, sm9802.u8(SM_CURSOR_CONFIG))
        assertTrue(bg1Row17(run.region("f09802", "ewram")).all { it == 0 })

        // Post-write slot box scroll (f9804..9844) & slot 0 metadata refresh (f9814).
        val sm9804 = dump(9804).find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(21, sm9804.scrCur)
        assertEquals(6, sm9804.u8(SM_JUMP_LABEL))
        val sm9814 = dump(9814).find("gProcScr_SaveMenuPostChapter")!!
        assertEquals(22, sm9814.scrCur)
        assertEquals(8, sm9814.u8(SM_SCROLL_CNT))
        assertEquals(1, sm9814.u8(SM_CHAPTER_IDX))
        assertEquals(10669, sm9814.u32(SM_PLAYED_TIME))
        val d9815 = dump(9815)
        assertNull(d9815.find("ProcScr_ApProc"))
        assertEquals(0, d9815.find("gProcScr_SaveMenuPostChapter")!!.u32(SM_APPROCPTR))
        assertEquals(0xE0, d9815.find("gProcScr_SaveMenuPostChapter")!!.u16(SM_SUS_ICON_SCALE))
        assertEquals(0xFF, dump(9830).find("gProcScr_SaveMenuPostChapter")!!.u8(SM_SUS_SLOT_CUR))

        // Exit fade (f9844..9860) and PostSaveMenuHandler (f9862 -> f9863).
        assertEquals(27, dump(9844).find("gProcScr_SaveMenuPostChapter")!!.scrCur)
        val d9845 = dump(9845)
        assertEquals(29, d9845.find("gProcScr_SaveMenuPostChapter")!!.scrCur)
        assertNotNull(d9845.find("ProcScr_BmFadeOUT"))
        assertEquals(32, dump(9862).find("gProcScr_SaveMenuPostChapter")!!.scrCur)

        val d9863 = dump(9863)
        assertNull(d9863.find("gProcScr_SaveMenuPostChapter"))
        assertNull(d9863.find("ProcScr_savedraw"))
        assertNull(d9863.find("gProcScr_SaveDrawCursor"))
        assertEquals(
            0,
            u32(run.region("f09863", "iwram"), ADDR_HBLANK1 - IWRAM_BASE),
        )
        val gc9863 = d9863.find("gProcScr_GameControl")!!
        assertEquals(76, gc9863.scrCur)
        assertEquals(0, gc9863.u8(PROC_LOCK_CNT))
        assertEquals(2, gc9863.u8(GC_NEXT_ACTION)) // GAME_ACTION_USR_SKIPPED
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val IWRAM_BASE = 0x03000000
        const val ADDR_BG1_TILEMAP = 0x020234A8
        const val ADDR_HBLANK1 = 0x03003134
        const val ADDR_HBLANK_BG_VERTICAL_SCROLL = 0x08014CC5

        const val KEY_A = 0x0001
        const val MAIN_MENU_OPTION_INVALID = 0x0040

        const val PROC_LOCK_CNT = 0x28
        const val GC_NEXT_ACTION = 0x29

        const val SM_SCROLL_CNT = 0x29
        const val SM_SUS_SLOT = 0x2C
        const val SM_CURSOR_SLOT = 0x2D
        const val SM_JUMP_LABEL = 0x2E
        const val SM_MENU_X_OFFSET = 0x2F
        const val SM_CURSOR_CONFIG = 0x36
        const val SM_CHAPTER_IDX = 0x37
        const val SM_SLOT_FLAGS = 0x3A
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_MAIN_SEL_BITFILE = 0x42
        const val SM_SUS_ICON_SCALE = 0x44
        const val SM_PLAYED_TIME = 0x48
        const val SM_SAVEDRAW = 0x58
        const val SM_APPROCPTR = 0x60

        const val PLAYST_TIME_SAVED = 0x00
        const val PLAYST_CHAPTER_INDEX = 0x0E

        const val BLOCK_INFO_OFFSET = 0x64
        const val BLOCK_INFO_SIZE = 0x10
        const val INFO_KIND = 0x06
        const val INFO_CHECKSUM32 = 0x0C
        const val SAVEBLOCK_KIND_GAME = 0
        const val SAVEBLOCK_KIND_SUSPEND = 1
        const val SAVEBLOCK_KIND_INVALID = 0xFF
        const val GAME0_OFFSET = 0x3FC4

        fun blockInfo(slot: Int): Int = BLOCK_INFO_OFFSET + slot * BLOCK_INFO_SIZE

        fun u16(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

        fun u32(buf: ByteArray, off: Int): Int =
            u16(buf, off) or (u16(buf, off + 2) shl 16)

        fun bg1Row17(ewram: ByteArray): List<Int> {
            val start = ADDR_BG1_TILEMAP - EWRAM_BASE + (17 * 32 + 7) * 2
            return (0 until 11).map { i -> u16(ewram, start + i * 2) }
        }

        val SAVE_MENU_ROWS = listOf(
            "gProcScr_GameControl <- tree 3 9444-9863",
            "gProcScr_SaveMenuPostChapter <- gProcScr_GameControl 9444-9862",
            "ProcScr_savedraw <- gProcScr_SaveMenuPostChapter 9459-9862",
            "ProcScr_ApProc <- tree 3 9459-9814",
            "gProcScr_SaveDrawCursor <- ProcScr_savedraw 9459-9862",
            "ProcScr_BmFadeIN <- tree 4 9461-9492",
            "ProcScr_BmFadeOUT <- tree 4 9845-9860",
        )
    }
}
