package fe8.testing

import fe8.game.RomView
import fe8.game.mapanim.ApProcs
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawLifecycle
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveDrawSlots
import fe8.game.savemenu.SaveDrawSlotsParent
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Only `BitfileToIndex` carries `@VerifiesPort`; `sub_80AA7EC`, `sub_80AADE0`, `sub_80AB05C`,
 * `SaveDraw_Loop_Main`, `sub_80AAE90` and `sub_80AAF6C` have unexercised branches (see the
 * coverage asserts at the end) and stay `Transcribed`.
 *
 * Differential test of [SaveDrawSlots] (`PC-4c1`: slot boxes, banner and play-time sprites of
 * `src/savedraw.c`) against `ram_trace_prologue_end` (`PC-cap1`, `f9459..9862`).
 *
 * For every frame whose `sSpritePool` the capture holds, the save menu fields are loaded from the
 * captured `gProcScr_SaveMenuPostChapter` proc, `SaveDraw_Loop_Main` runs on the fog-less loop
 * (the `PC-4c2` steps are no-ops), and the sprites it queued are compared with the captured pool
 * entries whose object is one of this task's sprites (position, attr2, object).
 */
class SaveDrawSlotsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class MenuView : SaveDrawSlotsParent {
        override var susSlot = 0
        override var susSlotCur = 0
        override var mainSelBitfile = 0
        override var extraSelBitfile = 0
        override var approc: Proc? = null
        override var jumpLabel = 0
        override var unk2f = 0
        override var mainSelect = 0
        override var mainOptions = 0
        override var unk31 = 0
        override var cursorSlot = 0
        override var cursorConfig = 0
        override var unk44 = 0
        override var unk46 = 0
        val played = IntArray(3)
        override var totalTime = 0

        override fun playedTime(slot: Int): Int = played[slot]
    }

    private class Entry(val x: Int, val y: Int, val oam2: Int, val obj: Int) {
        override fun toString() = "(%d,%d,%04X,%08X)".format(x, y, oam2, obj)
        override fun equals(other: Any?) =
            other is Entry && x == other.x && y == other.y && oam2 == other.oam2 &&
                obj == other.obj
        override fun hashCode() = obj
    }

    @Test
    @VerifiesPort("BitfileToIndex")
    fun `slot, banner and play time sprites match PC-cap1`() {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")
        val rom = RomView.ofRom(romFile.readBytes())
        val procsTrace = run.watch("procs")

        val engine = ProcEngine()
        val hardware = BgHardware()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val cursor = SaveDrawCursor(engine, layers, rom)
        val apPlayer = SpriteAnimPlayer(
            { rom.u16(it) }, layers, WorldMapApHost(rom, hardware, oam),
        )
        val apProcs = ApProcs(engine, apPlayer)
        val lifecycle = SaveDrawLifecycle(
            engine, hardware, BgVerticalScroll(), oam, cursor, apProcs, rom,
        )
        var clock = 0
        val slots = SaveDrawSlots(layers, rom, cursor, UiHand(rom, layers), apProcs) { clock }

        val menuProc = engine.start(
            ProcScript("gProcScr_SaveMenuPostChapter", listOf(block())) { MenuView() }, 3,
        )
        val menu = menuProc.state as MenuView
        val sdProc = lifecycle.startSaveDraw(menuProc)

        val tracked = trackedObjects(rom)
        var compared = 0
        var sawConfirm = false
        var sawBookmarkRest = false
        var sawBookmarkSpin = false
        var sawSlotSelected = false
        var sawTotalTime = false
        var sawExtras = false
        var sawStaticCursor = false
        var maxHours = 0
        val bitfiles = HashSet<Int>()
        for (step in 9459..9862) {
            if (step in OVERRUN_STEPS) continue
            val sm = ProcArrayDump.ofWatch(procsTrace, step, names)
                .find("gProcScr_SaveMenuPostChapter") ?: continue
            val smPrev = ProcArrayDump.ofWatch(procsTrace, step - 1, names)
                .find("gProcScr_SaveMenuPostChapter") ?: sm
            // The slot-0 play time is refreshed (f9814 metadata refresh) by the menu proc after
            // the step's dump and before the draw, so it is read from the next step's dump.
            val smNext = ProcArrayDump.ofWatch(procsTrace, step + 1, names)
                .find("gProcScr_SaveMenuPostChapter") ?: sm
            val prefix = if (step in GRAPHIC_DUMP_STEPS) "f" else "r"
            val ewram = run.region("%s%05d".format(prefix, step), "ewram")
            val iwram = run.region("%s%05d".format(prefix, step), "iwram")
            menu.susSlot = sm.u8(0x2C)
            menu.cursorSlot = sm.u8(0x2D)
            menu.jumpLabel = sm.u8(0x2E)
            menu.unk2f = sm.u8(0x2F)
            menu.cursorConfig = sm.u8(0x36)
            menu.susSlotCur = smPrev.u8(0x3F)
            menu.mainSelBitfile = sm.u16(0x42)
            menu.extraSelBitfile = sm.u8(0x35)
            menu.unk44 = sm.u16(0x44)
            menu.unk46 = sm.u16(0x46)
            for (i in 0 until 3) menu.played[i] = smNext.u32(0x48 + 4 * i)
            menu.totalTime = smNext.u32(0x54)
            clock = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_GAME_CLOCK)
            (sdProc.state as SaveDrawProc).unk29 = 1

            layers.clear()
            slots.loopMain(sdProc, {}, {}, {})
            val own = ArrayList<Entry>()
            for (i in 0 until layers.allocIt) {
                val e = layers.pool[i]
                own += Entry(e.oam1, e.oam0, e.oam2, e.obj!!.id)
            }
            val captured = readPool(ewram, iwram).filter { it.obj in tracked }
            assertEquals(captured, own, "slot sprites at f$step")
            sawConfirm = sawConfirm || menu.cursorConfig != 0
            sawBookmarkRest = sawBookmarkRest || menu.unk44 == 0x100
            sawBookmarkSpin = sawBookmarkSpin || menu.unk44 != 0x100
            sawSlotSelected = sawSlotSelected || menu.jumpLabel == 6
            sawTotalTime = sawTotalTime || menu.mainSelBitfile == 1
            sawExtras = sawExtras || menu.unk46 != 0
            sawStaticCursor = sawStaticCursor || menu.cursorSlot != 0xFF
            maxHours = maxOf(maxHours, menu.played[menu.susSlot] / 216000)
            bitfiles += menu.mainSelBitfile
            compared++
        }
        // What the capture exercises; everything else in SaveDrawSlots stays Transcribed.
        assertEquals(395, compared, "frames compared")
        assertTrue(sawConfirm && sawBookmarkRest && sawBookmarkSpin && sawSlotSelected)
        assertTrue(!sawTotalTime && !sawExtras && !sawStaticCursor && maxHours == 0)
        assertEquals(setOf(0x40), bitfiles, "chosen main option bitfiles")
    }

    private fun trackedObjects(rom: RomView): Set<Int> {
        val out = HashSet<Int>()
        for ((table, n) in listOf(0x08A20650 to 11, 0x08A2067C to 11, 0x08A209B8 to 11,
            0x08A209F0 to 3, 0x08A209E4 to 3)) {
            for (i in 0 until n) out += rom.u32(table + 4 * i)
        }
        out += listOf(0x08A20590, 0x08A2051C, 0x08A20588, 0x08A20536, 0x085B6490)
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
        const val RUN = "ram_trace_prologue_end"
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970
        const val ADDR_GAME_CLOCK = 0x03000014
        const val SPRITE_ENTRY_SIZE = 16
        // VBlank overruns: the main step did not run, the pool is empty.
        val OVERRUN_STEPS = setOf(9800, 9801, 9813, 9814, 9839, 9840, 9841, 9842, 9843)
        val GRAPHIC_DUMP_STEPS = setOf(9444, 9494, 9500, 9600, 9700, 9799, 9800, 9802, 9863)

        fun u8(buf: ByteArray, off: Int): Int = buf[off].toInt() and 0xFF
        fun u16(buf: ByteArray, off: Int): Int = u8(buf, off) or (u8(buf, off + 1) shl 8)
        fun s16(buf: ByteArray, off: Int): Int = u16(buf, off).toShort().toInt()
        fun u32(buf: ByteArray, off: Int): Int = u16(buf, off) or (u16(buf, off + 2) shl 16)
    }
}
