package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.SaveMenuChoice
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuSlotFlip
import fe8.game.savemenu.SaveMenuState
import fe8.game.savemenu.SaveSlotData
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test for `PC-4e2` against `ram_trace_prologue_end` (`PC-cap1`): the slot box flip
 * after the slot 0 write (`SaveMenuRegisterSlotSelected` at f9804, `SaveMenuWaitSlotBoxScrolling`
 * f9805..9844: affine matrix 0 of `gOam`, the slot 0 refresh at scroll frame 8, the option scan
 * at scroll frame 0x20 and the jump to `PL_SAVEMENU_EXIT_FADE`) and the `NewFadeOut(8)` that
 * follows (f9845..9861).
 */
class SaveMenuSlotFlipDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(val run: ReferenceData.Run) {
        val romBytes = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        ).readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val oam = OamBuffer()
        val saveLib = BmSaveLib(CartSram().also { it.copyIn(run.sram("s09813")) }).also {
            it.sramInit()
        }
        val chapterTitle = ChapterTitle(
            rom,
            hardware,
            ChapterData(rom),
            getPlayChapterId = { error("not run") },
            getNextUnclearedNode = { error("not run") },
        )
        val slotData = SaveSlotData(saveLib, chapterTitle, hardware, rom)
        val archivedFade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val menu = SaveMenuPostChapter(
            engine, hardware, PlaySt(), BmSt(), GmData(ByteArray(0x8000), RngService()),
            saveLib, archivedFade,
        )
        val choice = SaveMenuChoice()
        val sounds = ArrayList<Int>()
        val flip = SaveMenuSlotFlip(engine, rom, oam, chapterTitle, slotData, choice).also {
            it.hooks.playSoundEffect = { id -> sounds += id }
        }

        init {
            menu.wireSlotFlip(flip)
        }

        fun dump(step: Int): ProcArrayDump.Proc =
            ProcArrayDump.ofWatch(run.watch("procs"), step, ProcArrayDump.romNames())
                .find("gProcScr_SaveMenuPostChapter")!!

        /** The real menu proc with the `SaveMenuProc` fields of capture step [step]. */
        fun procAt(step: Int): Proc {
            val parent = engine.start(
                menu.script,
                3,
            )
            val s = parent.state as SaveMenuState
            val sm = dump(step)
            s.scrollCnt = sm.u8(SM_SCROLL_CNT)
            s.susSlot = sm.u8(SM_SUS_SLOT)
            s.cursorSlot = sm.u8(SM_CURSOR_SLOT)
            s.jumpLabel = sm.u8(SM_JUMP_LABEL)
            s.mainOptions = sm.u8(SM_MAIN_OPTIONS)
            s.unk31 = sm.u8(SM_UNK_31)
            s.mainSelBitfile = sm.u16(SM_MAIN_SEL_BITFILE)
            for (i in 0 until 3) {
                s.slots.chapterIdx[i] = sm.u8(SM_CHAPTER_IDX + i)
                s.slots.unk3a[i] = sm.u8(SM_UNK_3A + i)
                s.slots.playedTime[i] = sm.u32(SM_PLAYED_TIME + i * 4)
            }
            s.slots.totalTime = sm.u32(SM_TOTAL_TIME)
            s.susSlotCur = sm.u8(SM_SUS_SLOT_CUR)
            s.slots.unk44 = sm.u16(SM_UNK_44)
            return parent
        }

        /** Loads the slot tables and palette of `r09811` (after the write, before the refresh). */
        fun seedTables(frame: String) {
            val e = run.region(frame, "ewram")
            for (i in 0 until 4) {
                slotData.playStChapterBits[i] = u8(e, BITS + i)
                slotData.playStChapterMode[i] = u8(e, MODE + i)
                e.copyInto(slotData.playStOptionBits[i], 0, OPTS + i * 8, OPTS + i * 8 + 8)
            }
            for (c in 0 until 512) hardware.palette[c] = u16(e, PALETTE + c * 2).toShort()
        }
    }

    private fun harness(): Harness {
        assumeTrue(ref.available && romFile.isFile, "mGBA reference data / ROM not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN lacks procs watch")
        return Harness(run)
    }

    @Test
    @VerifiesPort(
        "SaveMenuRegisterSlotSelected",
        "InitSaveMenuChoice",
        "AddMainMenuOption",
        "AddExtraMenuOption",
    )
    fun `slot flip, option scan and exit jump match PC-cap1 f9804 to f9844`() {
        val h = harness()
        h.seedTables("r09811")
        h.run.region("f09800", "vram").copyInto(h.hardware.vram)
        // The refresh must rewrite the slot 0 title image, so start from a blank region.
        h.hardware.vram.fill(0, TITLE_GFX_0, TITLE_GFX_0 + 0x800)
        val proc = h.procAt(9803)
        val s = proc.state as SaveMenuState
        h.choice.hooks.linkArena = { s.slots.unk3a.any { it and 1 != 0 } }
        h.choice.hooks.freeMap = { s.slots.unk3a.any { it and 4 != 0 } }

        h.flip.saveMenuRegisterSlotSelected(proc)
        val registered = h.dump(9804)
        assertEquals(registered.u8(SM_JUMP_LABEL), s.jumpLabel, "jump_label")
        assertEquals(registered.u8(SM_SCROLL_CNT), s.scrollCnt, "scroll_cnt")

        // The frame at which each scroll_cnt value first shows in the capture.
        val firstFrame = HashMap<Int, Int>()
        for (f in 9805..9844) firstFrame.putIfAbsent(h.dump(f).u8(SM_SCROLL_CNT), f)
        // The refresh at scroll_cnt 8 spans f9812..9815 (frame overrun): restore the tables.
        while (s.scrollCnt < 0x21) {
            val cnt = s.scrollCnt
            h.flip.saveMenuWaitSlotBoxScrolling(proc)
            val frame = firstFrame.getValue(cnt + 1)
            assertEquals(h.dump(frame).u8(SM_SCROLL_CNT), s.scrollCnt, "scroll_cnt f$frame")
            val iwram = h.run.region("r%05d".format(frame), "iwram")
            for (k in 0 until 4) {
                assertEquals(
                    u16(iwram, GOAM - IWRAM + 6 + 8 * k),
                    h.oam.buffer[3 + 4 * k].toInt() and 0xFFFF,
                    "affine 0 [$k] at scroll_cnt $cnt (f$frame)",
                )
            }
            if (cnt == 8) {
                val after = h.dump(9815)
                assertEquals(
                    (0 until 3).map { after.u8(SM_CHAPTER_IDX + it) },
                    s.slots.chapterIdx.toList(),
                )
                assertEquals(
                    (0 until 3).map { after.u32(SM_PLAYED_TIME + it * 4) },
                    s.slots.playedTime.toList(),
                )
                // Slot 0 title image, rewritten by PutChapterTitleGfx.
                val vram = h.run.region("f09863", "vram")
                assertArrayEquals(
                    vram.copyOfRange(TITLE_GFX_0, TITLE_GFX_0 + 0x800),
                    h.hardware.vram.copyOfRange(TITLE_GFX_0, TITLE_GFX_0 + 0x800),
                    "slot 0 title gfx",
                )
            }
        }

        val done = h.dump(9844)
        assertEquals(done.u8(SM_MAIN_OPTIONS), s.mainOptions, "main_options")
        assertEquals(done.u8(SM_UNK_31), s.unk31, "unk_31")
        assertEquals(done.u8(SM_EXTRA_OPTIONS), s.extraOptions, "extra_options")
        assertEquals(done.u8(SM_MAX_CHOICE), s.maxChoice, "max_choice")
        assertEquals(done.u8(SM_JUMP_LABEL), s.jumpLabel, "jump_label")
        assertEquals(done.u16(SM_MAIN_SEL_BITFILE), s.mainSelBitfile, "main_sel_bitfile")
        assertEquals(done.u8(SM_SUS_SLOT), s.susSlot, "sus_slot")
        assertEquals(done.scrCur, proc.scrCur, "script cursor (PL_SAVEMENU_EXIT_FADE)")
        assertEquals(33, s.scrollCnt)
    }

    @Test
    @VerifiesPort("NewFadeOut", "FadeOutExists", "FadeOut_Loop")
    fun `NewFadeOut(8) timer, palette and display match PC-cap1 f9845 to f9861`() {
        val h = harness()
        val trace = h.run.watch("procs")
        val names = ProcArrayDump.romNames()
        val frames = (9840..9870).filter {
            ProcArrayDump.ofWatch(trace, it, names).find(FADE_OUT_SCRIPT) != null
        }
        assertTrue(frames.isNotEmpty(), "fade-out never observed")
        assertEquals(frames.size, frames.last() - frames.first() + 1, "contiguous lifetime")
        val first = ProcArrayDump.ofWatch(trace, frames.first(), names).find(FADE_OUT_SCRIPT)!!
        assertEquals(8, first.u32(0x30), "speed")
        assertEquals(-1, first.u32(0x34), "mask")
        assertEquals(0, first.u8(0x29), "white_out")
        val timers = frames.map {
            ProcArrayDump.ofWatch(trace, it, names).find(FADE_OUT_SCRIPT)!!.u32(0x2C)
        }

        val before = h.run.region("r%05d".format(frames.first() - 1), "ewram")
        for (i in 0 until 512) h.hardware.palette[i] = u16(before, PALETTE + i * 2).toShort()
        val iwramBefore = h.run.region("r%05d".format(frames.first() - 1), "iwram")
        h.hardware.lcd.dispcnt = u16(iwramBefore, LCD_CONTROL)
        val fade = h.archivedFade
        val proc = fade.newFadeOut(8, null)
        assertTrue(fade.fadeOutExists())
        val mine = ArrayList<Int>()
        val palettes = ArrayList<ShortArray>()
        while (fade.fadeOutExists()) {
            h.engine.run(h.engine.root(4))
            if (fade.fadeOutExists()) {
                mine += fade.stateOf(proc).timer
                palettes += h.hardware.palette.copyOf()
            }
            assertTrue(mine.size < 500, "fade never ended")
        }
        assertEquals(timers.filter { it != 0 }, mine, "timer sequence")
        // The first observed frame may be before the first loop step (timer 0).
        val offset = frames.size - mine.size
        for ((i, palette) in palettes.withIndex()) {
            val frame = frames[i + offset]
            val e = h.run.region("r%05d".format(frame), "ewram")
            // Colors 273 and 417 are rewritten by the palette cycle of SaveDraw_Loop_Main (PC-4c2).
            for (c in 0 until 512) {
                if (c == 273 || c == 417) continue
                assertEquals(
                    u16(e, PALETTE + 2 * c), palette[c].toInt() and 0xFFFF,
                    "palette color $c at f$frame (timer ${mine[i]})",
                )
            }
        }
        val end = h.run.region("f09863", "iwram")
        assertEquals(u16(end, LCD_CONTROL) and 0x1F00, h.hardware.lcd.dispcnt and 0x1F00, "display")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BITS = 0x02000940 - EWRAM
        const val MODE = 0x02000944 - EWRAM
        const val OPTS = 0x02000948 - EWRAM
        const val PALETTE = 0x020228A8 - EWRAM
        const val GOAM = 0x03003140
        const val LCD_CONTROL = 0x3080
        const val FADE_OUT_SCRIPT = 0x08A20DCC
        const val TITLE_GFX_0 = 0x16800

        const val SM_SCROLL_CNT = 0x29
        const val SM_SUS_SLOT = 0x2C
        const val SM_CURSOR_SLOT = 0x2D
        const val SM_JUMP_LABEL = 0x2E
        const val SM_MAIN_OPTIONS = 0x30
        const val SM_UNK_31 = 0x31
        const val SM_EXTRA_OPTIONS = 0x32
        const val SM_MAX_CHOICE = 0x33
        const val SM_CHAPTER_IDX = 0x37
        const val SM_UNK_3A = 0x3A
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_MAIN_SEL_BITFILE = 0x42
        const val SM_UNK_44 = 0x44
        const val SM_PLAYED_TIME = 0x48
        const val SM_TOTAL_TIME = 0x54

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    }
}
