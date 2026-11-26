package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterTitle
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawEffects
import fe8.game.savemenu.SaveDrawLifecycle
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveDrawSlots
import fe8.game.savemenu.SaveMenuSelectLoop
import fe8.game.savemenu.SaveMenuState
import fe8.game.savemenu.SaveSlotData
import fe8.game.text.Fe8Text
import fe8.game.ui.UiHand
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `PC-11h`: slot-select and confirm draw of the title menu against `ram_trace_title_continue`
 * (`f1050..1213`) and `ram_trace_title_resume` (`f1018..1099`).
 *
 * - `sub_80AB05C` (slot boxes, bookmark, confirm box) and `sub_80AA7EC` (play time) are compared
 *   through the queued `sSpritePool` entries (x, y, attr2, object) per captured frame.
 * - `sub_80AACBC` runs on the previous frame's menu fields and is compared with the captured
 *   `unk_44` / `sus_slot_cur` / `unk_2a` and OAM affine matrix 3.
 * - `SaveMenuDrawSubSelBox(Ext)` is seeded from a graphic dump before the draw and compared with
 *   the next graphic dump (BG1 tilemap rows 17..18 and the sub-box text tiles in VRAM).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class TitleSlotSelectDrawDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private var rom: RomView? = null
    private var romBytes: ByteArray? = null

    @AfterAll
    fun releaseReferenceData() {
        rom = null
        romBytes = null
    }

    private data class Entry(val x: Int, val y: Int, val oam2: Int, val obj: Int)

    private fun romFile() = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile().isFile, "ROM not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        if (romBytes == null) romBytes = romFile().readBytes()
        if (rom == null) rom = RomView.ofRom(romBytes!!)
    }

    private fun dumpName(run: ReferenceData.Run, step: Int): String? =
        listOf("r%05d", "f%05d").map { it.format(step) }.firstOrNull { run.dumps.containsKey(it) }

    private fun procAt(run: ReferenceData.Run, step: Int, name: String): ProcArrayDump.Proc? =
        ProcArrayDump.ofEwram(run.region(dumpName(run, step)!!, "ewram"), ProcArrayDump.romNames())
            .find(name)

    private fun menuAt(run: ReferenceData.Run, step: Int) = procAt(run, step, "ProcScr_SaveMenu")

    private fun syncMenu(menu: SaveMenuState, sm: ProcArrayDump.Proc) {
        menu.mainSelect = sm.u8(0x2B)
        menu.susSlot = sm.u8(0x2C)
        menu.cursorSlot = sm.u8(0x2D)
        menu.jumpLabel = sm.u8(0x2E)
        menu.unk2f = sm.u8(0x2F)
        menu.mainOptions = sm.u8(0x30)
        menu.unk31 = sm.u8(0x31)
        menu.extraSelBitfile = sm.u8(0x35)
        menu.cursorConfig = sm.u8(0x36)
        menu.susSlotCur = sm.u8(0x3F)
        menu.mainSelBitfile = sm.u16(0x42)
        menu.unk44 = sm.u16(0x44)
        menu.unk46 = sm.u16(0x46)
        for (i in 0 until 3) menu.slots.playedTime[i] = sm.u32(0x48 + 4 * i)
        menu.slots.totalTime = sm.u32(0x54)
    }

    /**
     * Rewinds the fields `sub_80AACBC` writes (`sus_slot_cur`, `unk_44`) to their value when the
     * frame's draw ran: the captured step is taken after the effect step of the same frame. The
     * menu proc writes `unk_44` (to `0xF0` at the start of the spin) before the effect step.
     */
    private fun preEffect(menu: SaveMenuState, prev: ProcArrayDump.Proc, sm: ProcArrayDump.Proc) {
        menu.susSlotCur = prev.u8(0x3F)
        val after = sm.u16(0x44)
        if (menu.susSlotCur != 0xFF && after != 0x100) menu.unk44 = (after + 16) and 0xFFFF
    }

    private fun trackedObjects(view: RomView): Set<Int> {
        val out = HashSet<Int>()
        for (i in 0 until 11) {
            out += view.u32(0x08A209B8 + 4 * i) // option labels
            out += view.u32(0x08A2067C + 4 * i) // large digits
            out += view.u32(0x08A20650 + 4 * i) // small digits
        }
        for (i in 0 until 3) {
            out += view.u32(0x08A209F0 + 4 * i) // slot boxes
            out += view.u32(0x08A209E4 + 4 * i) // slot labels
        }
        out += listOf(0x08A2051C, 0x08A20590, 0x08A20588, 0x08A20536)
        return out
    }

    private data class Coverage(
        var compared: Int = 0,
        var totalTimeFrames: Int = 0,
        var playedTimeFrames: Int = 0,
        var confirmFrames: Int = 0,
        var bookmarkFrames: Int = 0,
        var slotBoxFrames: Int = 0,
    )

    private fun compareSprites(name: String, first: Int, last: Int): Coverage {
        available()
        val view = rom!!
        val run = ref.run(name)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val cursor = SaveDrawCursor(engine, layers, view)
        val apProcs = ApProcs(
            engine,
            SpriteAnimPlayer({ view.u16(it) }, layers, WorldMapApHost(view, hardware, oam)),
        )
        val lifecycle = SaveDrawLifecycle(
            engine, hardware, BgVerticalScroll(), oam, cursor, apProcs, view,
        )
        var clock = 0
        val slots = SaveDrawSlots(layers, view, cursor, UiHand(view, layers), apProcs) { clock }
        val menuProc = engine.start(
            ProcScript("ProcScr_SaveMenu", listOf(block())) { SaveMenuState() }, 3,
        )
        val menu = menuProc.state as SaveMenuState
        syncMenu(menu, menuAt(run, first)!!)
        val sdProc = lifecycle.startSaveDraw(menuProc)
        val tracked = trackedObjects(view)
        val cov = Coverage()

        for (step in first..last) {
            val dn = dumpName(run, step) ?: continue
            val sm = menuAt(run, step) ?: continue
            if (procAt(run, step, "ProcScr_savedraw") == null) continue // menu already ended
            syncMenu(menu, sm)
            val prevSm = if (dumpName(run, step - 1) != null) menuAt(run, step - 1) else null
            if (prevSm != null) preEffect(menu, prevSm, sm)
            if (prevSm != null && prevSm.u8(0x36) != sm.u8(0x36)) {
                println("$name f$step cursor_config ${prevSm.u8(0x36)} -> ${sm.u8(0x36)}")
            }
            val ewram = run.region(dn, "ewram")
            val iwram = run.region(dn, "iwram")
            clock = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_GAME_CLOCK)
            (sdProc.state as SaveDrawProc).unk29 = 1
            val captured = readPool(ewram, iwram).filter { it.obj in tracked }

            layers.clear()
            slots.loopMain(sdProc, {}, {}, {})
            val own = (0 until layers.allocIt).map {
                val e = layers.pool[it]
                Entry(e.oam1, e.oam0, e.oam2, e.obj!!.id)
            }.filter { it.obj in tracked }
            assertEquals(captured.sortedBy { key(it) }, own.sortedBy { key(it) }, "$name f$step")

            cov.compared++
            if (menu.unk2f != 0) {
                cov.slotBoxFrames++
                if (menu.mainSelBitfile == 1) cov.totalTimeFrames++ else cov.playedTimeFrames++
                if (menu.susSlotCur != 0xFF) cov.bookmarkFrames++
            }
            if (menu.cursorConfig != 0) cov.confirmFrames++
        }
        return cov
    }

    private fun key(e: Entry): Long =
        (e.obj.toLong() shl 32) xor (e.y.toLong() shl 20) xor (e.x.toLong() shl 8) xor e.oam2.toLong()

    @Test
    @VerifiesPort("sub_80AB05C", "sub_80AA7EC")
    fun `slot boxes and play time match ram_trace_title_continue f1050 to f1213`() {
        val cov = compareSprites("ram_trace_title_continue", 1050, 1213)
        println("continue sprites: $cov")
        assertTrue(cov.compared > 100, "compared ${cov.compared}")
        assertTrue(cov.slotBoxFrames > 100, "slot box frames ${cov.slotBoxFrames}")
        assertTrue(cov.confirmFrames > 0, "confirm box frames")
    }

    @Test
    @VerifiesPort("sub_80AB05C", "sub_80AA7EC")
    fun `slot boxes and play time match ram_trace_title_resume f1018 to f1099`() {
        val cov = compareSprites("ram_trace_title_resume", 1018, 1099)
        println("resume sprites: $cov")
        assertTrue(cov.compared > 50, "compared ${cov.compared}")
        assertTrue(cov.slotBoxFrames > 30, "slot box frames ${cov.slotBoxFrames}")
    }

    private fun oamAffine(iwram: ByteArray, matrix: Int): List<Int> = (0 until 4).map {
        s16(iwram, ADDR_OAM - GbaMem.IWRAM + (matrix * 4 + it) * 8 + 6)
    }

    private fun oamAffine(oam: OamBuffer, matrix: Int): List<Int> = listOf(
        oam.buffer[matrix * 16 + 3].toInt(),
        oam.buffer[matrix * 16 + 7].toInt(),
        oam.buffer[matrix * 16 + 11].toInt(),
        oam.buffer[matrix * 16 + 15].toInt(),
    )

    private fun compareEffects(name: String, first: Int, last: Int): List<Int> {
        available()
        val view = rom!!
        val run = ref.run(name)
        val engine = ProcEngine()
        val hardware = BgHardware()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val cursor = SaveDrawCursor(engine, layers, view)
        val apProcs = ApProcs(
            engine,
            SpriteAnimPlayer({ view.u16(it) }, layers, WorldMapApHost(view, hardware, oam)),
        )
        val lifecycle = SaveDrawLifecycle(
            engine, hardware, BgVerticalScroll(), oam, cursor, apProcs, view,
        )
        val rng = RngService()
        val chapterTitle = ChapterTitle(
            view, hardware, ChapterData(view),
            getPlayChapterId = { error("not run") },
            getNextUnclearedNode = { error("not run") },
        )
        val lib = BmSaveLib(CartSram()).also { it.sramInit() }
        val slotData = SaveSlotData(lib, chapterTitle, hardware, view)
        val effects = SaveDrawEffects(
            hardware, oam, layers, slotData, rng.lcg, lifecycle.particles, view,
        )
        val menuProc = engine.start(
            ProcScript("ProcScr_SaveMenu", listOf(block())) { SaveMenuState() }, 3,
        )
        val menu = menuProc.state as SaveMenuState
        val sdProc = lifecycle.startSaveDraw(menuProc)
        val state = sdProc.state as SaveDrawProc
        var spinFrames = 0
        var hideFrames = 0
        var affineFrames = 0

        for (step in first..last) {
            if (dumpName(run, step) == null || dumpName(run, step - 1) == null) continue
            val prev = menuAt(run, step - 1) ?: continue
            val next = menuAt(run, step) ?: continue
            val sdNext = procAt(run, step, "ProcScr_savedraw") ?: continue
            syncMenu(menu, next)
            preEffect(menu, prev, next)
            val prevIw = run.region(dumpName(run, step - 1)!!, "iwram")
            val iw = run.region(dumpName(run, step)!!, "iwram")
            for (m in 0 until 4) {
                for (k in 0 until 4) {
                    oam.buffer[m * 16 + 4 * k + 3] =
                        s16(prevIw, ADDR_OAM - GbaMem.IWRAM + (m * 4 + k) * 8 + 6).toShort()
                }
            }
            state.unk2a = sdNext.u16(0x2A) - 1
            state.unk3c = menu.susSlot // the slot-change branch is not part of this check

            val wasSpin = menu.susSlotCur != 0xFF && menu.unk44 != 0x100
            val wasHide = wasSpin && menu.unk44 < 0x10
            effects.sub80AACBC(sdProc)

            assertEquals(next.u8(0x3F), menu.susSlotCur, "sus_slot_cur f$step")
            assertEquals(next.u16(0x44), menu.unk44, "unk_44 f$step")
            assertEquals(sdNext.u16(0x2A), state.unk2a, "unk_2a f$step")
            assertEquals(oamAffine(iw, 3), oamAffine(oam, 3), "OAM affine 3 f$step")
            if (wasSpin) spinFrames++
            if (wasHide) hideFrames++
            if (wasSpin && !wasHide) affineFrames++
        }
        println("$name spin frames $spinFrames hide $hideFrames affine $affineFrames")
        return listOf(spinFrames, hideFrames, affineFrames)
    }

    @Test
    @VerifiesPort("sub_80AACBC")
    fun `unk_44 spin and hide match ram_trace_title_continue f1050 to f1213`() {
        val (spin, hide, affine) = compareEffects("ram_trace_title_continue", 1050, 1213)
        assertTrue(spin > 10, "spin frames $spin")
        assertTrue(hide > 0, "hide frames $hide")
        assertTrue(affine > 5, "affine frames $affine")
    }

    private class SubBox(romBytes: ByteArray) {
        val hardware = BgHardware()
        val host = object : TextHost {
            override fun language(): Int = 1

            override fun applyTextPalette(talk: Boolean, palid: Int) {
                hardware.enablePaletteSync()
            }

            override fun readVram32(addr: Int): Int {
                val o = addr - VRAM_BASE
                var v = 0
                for (k in 0..3) v = v or ((hardware.vram[o + k].toInt() and 0xFF) shl (8 * k))
                return v
            }

            override fun writeVram32(addr: Int, value: Int) {
                val o = addr - VRAM_BASE
                for (k in 0..3) hardware.vram[o + k] = (value shr (8 * k)).toByte()
            }

            override fun clearVram(dest: Int, byteCount: Int) {
                hardware.vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
            }
        }
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        val messages = MsgStrings(
            romBytes,
            MsgHuffman(
                romBytes,
                0x0815A72C - 0x08000000,
                u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        val font = Font()
        val subText = Text()
        val loop = SaveMenuSelectLoop(
            ProcEngine(), hardware, text, messages, font, subText,
            bitfileToIndex = { bits -> (0 until 8).firstOrNull { (bits shr it) and 1 != 0 } ?: -1 },
        )

        fun seed(run: ReferenceData.Run, dump: String) {
            val ew = run.region(dump, "ewram")
            val tm = hardware.mapBuffer(1)
            for (i in tm.indices) tm[i] = u16(ew, BG1_TILEMAP - GbaMem.EWRAM + i * 2).toShort()
            text.resetText()
            text.initTextFont(font, VRAM_BASE + 0x80 * 0x20, 0x80, 4)
            text.initText(subText, 10)
            run.region(dump, "vram").copyInto(hardware.vram)
        }
    }

    private fun subBoxCase(
        name: String,
        seedDump: String,
        targetDump: String,
        stateStep: Int,
        draw: Boolean,
        requireChange: Boolean = true,
    ) {
        available()
        val run = ref.run(name)
        val box = SubBox(romBytes!!)
        box.seed(run, seedDump)
        val before = box.hardware.mapBuffer(1).copyOf()
        val state = SaveMenuState()
        state.mainSelBitfile = menuAt(run, stateStep)!!.u16(0x42)
        state.cursorConfig = 2
        box.loop.saveMenuDrawSubSelBox(state, draw)
        assertTrue(box.hardware.modifiedBgs and 2 != 0, "BG1 sync requested")
        if (!draw) assertEquals(0, state.cursorConfig, "cursor_config reset")

        val ew = run.region(targetDump, "ewram")
        val tm = box.hardware.mapBuffer(1)
        for (row in 17..18) {
            for (col in 7 until 7 + 12) {
                val i = row * 32 + col
                assertEquals(
                    u16(ew, BG1_TILEMAP - GbaMem.EWRAM + i * 2), tm[i].toInt() and 0xFFFF,
                    "$seedDump->$targetDump BG1 [$row][$col]",
                )
            }
        }
        assertArrayEquals(
            run.region(targetDump, "vram").copyOfRange(0x1000, 0x1280),
            box.hardware.vram.copyOfRange(0x1000, 0x1280),
            "$seedDump->$targetDump sub-box text VRAM",
        )
        val changed = (17 * 32 until 19 * 32).any { before[it] != tm[it] }
        if (requireChange) assertTrue(changed, "the call changed the sub-box tilemap")
    }

    @Test
    @VerifiesPort("SaveMenuDrawSubSelBox", "SaveMenuDrawSubSelBoxExt")
    fun `Restart confirm opens the sub-box like ram_trace_title_continue f1093 to f1154`() {
        subBoxCase("ram_trace_title_continue", "f01093", "f01154", 1094, true)
    }

    @Test
    @VerifiesPort("SaveMenuDrawSubSelBox", "SaveMenuDrawSubSelBoxExt")
    fun `Restart clears the sub-box like ram_trace_title_continue f1175 to f1176`() {
        subBoxCase("ram_trace_title_continue", "f01175", "f01176", 1175, false)
    }

    @Test
    @VerifiesPort("SaveMenuDrawSubSelBox", "SaveMenuDrawSubSelBoxExt")
    fun `Resume keeps the sub-box clear like ram_trace_title_resume f1031 to f1062`() {
        subBoxCase("ram_trace_title_resume", "f01031", "f01062", 1031, false, requireChange = false)
    }

    private fun readPool(ewram: ByteArray, iwram: ByteArray): List<Entry> {
        val allocIt = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        val count = (allocIt - ADDR_S_SPRITE_POOL) / SPRITE_ENTRY_SIZE
        return (0 until count).map { i ->
            val base = (ADDR_S_SPRITE_POOL - GbaMem.EWRAM) + i * SPRITE_ENTRY_SIZE
            Entry(
                s16(ewram, base + 0x04), s16(ewram, base + 0x06), u16(ewram, base + 0x08),
                u32(ewram, base + 0x0C),
            )
        }
    }

    private companion object {
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970
        const val ADDR_GAME_CLOCK = 0x03000014
        const val ADDR_OAM = 0x03003140
        const val BG1_TILEMAP = 0x020234A8
        const val VRAM_BASE = 0x06000000
        const val SPRITE_ENTRY_SIZE = 16

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
