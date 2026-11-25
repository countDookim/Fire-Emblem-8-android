package fe8.testing

import fe8.game.RomView
import fe8.game.mapanim.ApProcs
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawCursorProc
import fe8.game.savemenu.SaveDrawLifecycle
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveDrawSlots
import fe8.game.savemenu.SaveMenuState
import fe8.game.ui.UiHand
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `PC-11g`: the title main option list (`sub_80AAE90`, `sub_80AABC4`) and the sprites of
 * `sub_80AADE0` and `SaveDrawCursor_Loop` it shares the pool with, against
 * `ram_trace_title_menu_items` (`f944..1320`, cursor walk) and `ram_trace_title_continue`
 * (`f944..1093`, scroll to the slot select).
 *
 * Per step the `SaveMenuProc` fields come from the captured proc, `SaveDraw_Loop_Main` and the
 * cursor proc run on a fresh layer pool (the cursor proc is seeded from the previous step's capture), and the queued sprites of the list objects (option boxes, labels, banner and
 * cursor) are compared with the captured `sSpritePool` entries (x, y, attr2, object).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class TitleMainListDrawDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private var rom: RomView? = null

    @AfterAll
    fun releaseReferenceData() {
        rom = null
    }

    private data class Entry(val x: Int, val y: Int, val oam2: Int, val obj: Int)

    private fun compare(name: String, first: Int, last: Int, minListFrames: Int) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(name)
        val view = rom ?: RomView.ofRom(romFile.readBytes()).also { rom = it }

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
        val sdProc = lifecycle.startSaveDraw(menuProc)
        val cursorProc = checkNotNull((sdProc.state as SaveDrawProc).drawCursorProc)
        val tracked = trackedObjects(view)

        fun dumpName(step: Int): String? =
            listOf("r%05d", "f%05d").map { it.format(step) }.firstOrNull { run.dumps.containsKey(it) }

        fun seedCursor(step: Int, counterStep: Int) {
            val c = ProcArrayDump.ofEwram(run.region(dumpName(step)!!, "ewram"), names)
                .find("gProcScr_SaveDrawCursor")!!
            val s = cursorProc.state as SaveDrawCursorProc
            s.unk2a = ProcArrayDump.ofEwram(run.region(dumpName(counterStep)!!, "ewram"), names)
                .find("gProcScr_SaveDrawCursor")!!.u16(0x2A) - 1
            s.susSlot = c.u8(0x2C)
            s.unk2e = c.u16(0x2E).toShort().toInt()
            s.mainOptions = c.u16(0x30).toShort().toInt()
            s.unk32 = c.u16(0x32).toShort().toInt()
            s.unk34 = c.u16(0x34).toShort().toInt()
            s.cursorEn = c.u8(0x36)
            s.unk37 = c.u8(0x37)
            s.unk38 = c.u8(0x38)
            s.unk39 = c.u8(0x39)
            s.unk3a = c.u8(0x3A)
        }

        var compared = 0
        var listFrames = 0
        var maxOffset = 0
        val labels = HashSet<Int>()
        for (step in first..last) {
            val dn = dumpName(step) ?: continue
            if (dumpName(step - 1) == null) continue
            seedCursor(step - 1, step)
            val ewram = run.region(dn, "ewram")
            val iwram = run.region(dn, "iwram")
            val captured = readPool(ewram, iwram).filter { it.obj in tracked }
            val sm = ProcArrayDump.ofEwram(ewram, names).find("ProcScr_SaveMenu") ?: continue
            menu.mainSelect = sm.u8(0x2B)
            menu.susSlot = sm.u8(0x2C)
            menu.cursorSlot = sm.u8(0x2D)
            menu.jumpLabel = sm.u8(0x2E)
            menu.unk2f = sm.u8(0x2F)
            menu.mainOptions = sm.u8(0x30)
            menu.unk31 = sm.u8(0x31)
            menu.cursorConfig = sm.u8(0x36)
            menu.susSlotCur = sm.u8(0x3F)
            menu.mainSelBitfile = sm.u16(0x42)
            menu.extraSelBitfile = sm.u8(0x35)
            menu.unk44 = sm.u16(0x44)
            menu.unk46 = sm.u16(0x46)
            clock = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_GAME_CLOCK)
            (sdProc.state as SaveDrawProc).unk29 = 1

            layers.clear()
            slots.loopMain(sdProc, {}, {}, {})
            cursor.saveDrawCursorLoop(cursorProc)
            val own = (0 until layers.allocIt).map {
                val e = layers.pool[it]
                Entry(e.oam1, e.oam0, e.oam2, e.obj!!.id)
            }.filter { it.obj in tracked }
            assertEquals(captured.sortedBy { key(it) }, own.sortedBy { key(it) }, "$name f$step")
            compared++
            if (menu.unk2f + menu.unk46 < 220 && menu.unk31 > 0) {
                listFrames++
                maxOffset = maxOf(maxOffset, menu.unk2f)
            }
            labels += menu.mainSelect
        }
        assertTrue(compared > 0)
        assertTrue(listFrames >= minListFrames, "list frames $listFrames")
        assertTrue(labels.size >= 1)
    }

    private fun key(e: Entry): Long =
        (e.obj.toLong() shl 32) xor (e.y.toLong() shl 20) xor (e.x.toLong() shl 8) xor e.oam2.toLong()

    @Test
    @VerifiesPort("sub_80AAE90", "sub_80AABC4")
    fun `cursor walk main list matches ram_trace_title_menu_items f944 to f1320`() {
        compare("ram_trace_title_menu_items", 944, 1320, 300)
    }

    @Test
    @VerifiesPort("sub_80AAE90", "sub_80AABC4")
    fun `main list matches ram_trace_title_continue f944 to f1001`() {
        compare("ram_trace_title_continue", 944, 1001, 50)
    }

    private fun trackedObjects(rom: RomView): Set<Int> {
        val out = HashSet<Int>()
        for (i in 0 until 11) out += rom.u32(0x08A209B8 + 4 * i)
        out += listOf(0x08A2051C, SaveDrawCursor.SPRITE_CURSOR_ADDRESS)
        return out
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
        const val SPRITE_ENTRY_SIZE = 16

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
