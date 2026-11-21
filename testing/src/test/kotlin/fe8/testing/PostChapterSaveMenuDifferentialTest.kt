package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.SaveSlotData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Runs the save slot metadata and palette functions of `PC-4f` on the SRAM of
 * `ram_trace_prologue_end` (`PC-cap1`) and compares the `SaveMenuProc` slot fields, the
 * `gPlayStChapterBits` / `gPlayStChapterMode` / `gPlayStOptionBits` tables and the slot rows of
 * `gPaletteBuffer` with the capture, at f9450..9458 (`SaveMenu_InitScreen`) and f9812..9814
 * (`SaveMenuWaitSlotBoxScrolling`, after the slot 0 write).
 */
class PostChapterSaveMenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Fixture(
        val lib: BmSaveLib,
        val data: SaveSlotData,
        val hardware: BgHardware,
    )

    private fun fixture(sram: ByteArray): Fixture {
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        val title = ChapterTitle(
            rom, hardware, ChapterData(rom),
            getPlayChapterId = { error("not run") },
            getNextUnclearedNode = { error("not run") },
        )
        val lib = BmSaveLib(CartSram().also { it.copyIn(sram) })
        lib.sramInit() // the boot probe that sets gBoolSramWorking
        return Fixture(lib, SaveSlotData(lib, title, hardware, rom), hardware)
    }

    private fun proc(run: ReferenceData.Run, step: Int): ProcArrayDump.Proc =
        ProcArrayDump.ofWatch(run.watch("procs"), step, ProcArrayDump.romNames())
            .find("gProcScr_SaveMenuPostChapter")!!

    private fun compareTables(data: SaveSlotData, ewram: ByteArray, what: String) {
        for (i in 0 until 4) {
            assertEquals(u8(ewram, BITS + i), data.playStChapterBits[i], "$what bits[$i]")
            assertEquals(u8(ewram, MODE + i), data.playStChapterMode[i], "$what mode[$i]")
            assertEquals(
                ewram.copyOfRange(OPTS + i * 8, OPTS + i * 8 + 8).toList(),
                data.playStOptionBits[i].toList(),
                "$what option bits[$i]",
            )
        }
    }

    /** Compares the slot rows, except the colors in [skip] (animated by `SaveDraw_Loop_Main`). */
    private fun comparePalette(
        hardware: BgHardware,
        ewram: ByteArray,
        what: String,
        skip: Set<Int> = emptySet(),
    ) {
        for (c in 0x1A * 16 until 0x20 * 16) {
            if (c in skip) continue
            val expected = u16(ewram, PALETTE + c * 2)
            assertEquals(expected, hardware.palette[c].toInt() and 0xFFFF, "$what color $c")
        }
    }

    private fun comparePalettePreloaded(f: Fixture, ewram: ByteArray) {
        // Rows other than the slot rows are not touched by the port; start from the capture's.
        for (c in 0 until 512) f.hardware.palette[c] = u16(ewram, PALETTE + c * 2).toShort()
    }

    @Test
    @VerifiesPort(
        "SaveMenuInitSaveSlotData",
        "SaveMenuInitSlotPalette",
        "GetChapterTitleExtra",
        "LoadSavedEid8A",
        "ReadGameSavePlaySt",
        "ReadSuspendSavePlaySt",
        "sub_80895B4",
    )
    fun `slot metadata and palettes at f9450 to f9458 match PC-cap1`() {
        assumeTrue(ref.available && romFile.isFile, "mGBA reference data / ROM not present")
        val run = ref.run(RUN)
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val f = fixture(run.sram("s09449"))
        val slots = SaveSlotData.Slots()
        slots.susSlotCur = 0xFF
        slots.unk44 = 0x100
        for (i in 0 until 4) f.data.saveMenuInitSaveSlotData(i, slots)
        f.data.saveMenuInitSlotPalette(0)

        val sm = proc(run, 9458)
        assertEquals((0 until 3).map { sm.u8(SM_CHAPTER_IDX + it) }, slots.chapterIdx.toList())
        assertEquals((0 until 3).map { sm.u8(SM_UNK_3A + it) }, slots.unk3a.toList())
        assertEquals((0 until 3).map { sm.u32(SM_PLAYED_TIME + it * 4) }, slots.playedTime.toList())
        assertEquals(sm.u32(SM_TOTAL_TIME), slots.totalTime)
        assertEquals(sm.u8(SM_SUS_SLOT_CUR), slots.susSlotCur)
        assertEquals(sm.u16(SM_UNK_44), slots.unk44)
        val ewram = run.region("r09458", "ewram")
        compareTables(f.data, ewram, "f9458")
        comparePalette(f.hardware, run.region("r09459", "ewram"), "f9459")
    }

    @Test
    @VerifiesPort("SaveMenuInitSaveSlotData", "SaveMenuInitSlotPalette")
    fun `slot 0 refresh after the write at f9812 to f9814 matches PC-cap1`() {
        assumeTrue(ref.available && romFile.isFile, "mGBA reference data / ROM not present")
        val run = ref.run(RUN)
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val f = fixture(run.sram("s09813"))
        val before = proc(run, 9811)
        val slots = SaveSlotData.Slots()
        for (i in 0 until 3) {
            slots.chapterIdx[i] = before.u8(SM_CHAPTER_IDX + i)
            slots.unk3a[i] = before.u8(SM_UNK_3A + i)
            slots.playedTime[i] = before.u32(SM_PLAYED_TIME + i * 4)
        }
        slots.susSlotCur = before.u8(SM_SUS_SLOT_CUR)
        slots.unk44 = before.u16(SM_UNK_44)
        val e0 = run.region("r09811", "ewram")
        for (i in 0 until 4) {
            f.data.playStChapterBits[i] = u8(e0, BITS + i)
            f.data.playStChapterMode[i] = u8(e0, MODE + i)
            e0.copyInto(f.data.playStOptionBits[i], 0, OPTS + i * 8, OPTS + i * 8 + 8)
        }
        comparePalettePreloaded(f, e0)

        f.data.saveMenuInitSaveSlotData(0, slots)
        f.data.saveMenuInitSaveSlotData(4, slots)
        f.data.saveMenuInitSlotPalette(0)

        val after = proc(run, 9814)
        assertEquals((0 until 3).map { after.u8(SM_CHAPTER_IDX + it) }, slots.chapterIdx.toList())
        assertEquals((0 until 3).map { after.u8(SM_UNK_3A + it) }, slots.unk3a.toList())
        val times = (0 until 3).map { after.u32(SM_PLAYED_TIME + it * 4) }
        assertEquals(times, slots.playedTime.toList())
        assertEquals(after.u8(SM_SUS_SLOT_CUR), slots.susSlotCur)
        // Slot 4 runs in f9815; the same frame's sub_80AACBC already steps unk_44 by 0x10.
        assertEquals(0xF0, slots.unk44)
        assertEquals(proc(run, 9815).u16(SM_UNK_44) + 0x10, slots.unk44)
        // The call spans the f9814 / f9815 frame boundary: the option bits land in f9815.
        val e1 = run.region("r09815", "ewram")
        compareTables(f.data, e1, "f9815")
        // Color 1 of bank 0x1A is rewritten by the palette cycle in the same frame (PC-4c2).
        comparePalette(f.hardware, run.region("r09815", "ewram"), "f9815", setOf(0x1A * 16 + 1))
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val BITS = 0x02000940 - EWRAM
        const val MODE = 0x02000944 - EWRAM
        const val OPTS = 0x02000948 - EWRAM
        const val PALETTE = 0x020228A8 - EWRAM

        const val SM_CHAPTER_IDX = 0x37
        const val SM_UNK_3A = 0x3A
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_UNK_44 = 0x44
        const val SM_PLAYED_TIME = 0x48
        const val SM_TOTAL_TIME = 0x54

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    }
}
