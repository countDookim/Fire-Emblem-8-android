package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MapAnimations
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The Prologue map tile animation (tiles 0x140.. of BG VRAM) matches the reference. */
class MapAnimationsTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("BMapVSync_UpdateMapImgAnimations")
    fun `animated map tiles match the first phase reference`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val run = ref.run("ram_trace_first_phase")
        val startProc = ProcArrayDump.ofEwram(run.region("f19430", "ewram")).find(BM_VSYNC)
        assertNotNull(startProc, "BMVSync proc in the f19430 dump")
        val hw = BgHardware()
        val rom = RomView.ofRom(romFile.readBytes())
        val anims = MapAnimations(rom, ChapterData(rom), hw, PlaySt())
        anims.seedTileAnimation(
            startProc!!.u32(0x2C),
            startProc.u32(0x30),
            startProc.u16(0x34).toShort().toInt(),
        )
        run.region("f19430", "vram").copyInto(hw.vram)
        var changed = false
        var prev = run.region("f19430", "vram")
        for (f in 19431..19520) {
            anims.updateMapImgAnimations()
            val prefix = if (f % 10 == 0) "f" else "r"
            val proc = ProcArrayDump.ofEwram(run.region("$prefix$f", "ewram")).find(BM_VSYNC)
            assertNotNull(proc, "BMVSync proc in $prefix$f dump")
            assertEquals(proc!!.u16(0x34).toShort().toInt(), anims.gfxAnimClock, "frame $f clock")
            assertEquals(proc.u32(0x30), anims.gfxAnimCurrent, "frame $f current")
            if (f % 10 != 0) continue
            val want = run.region("f$f", "vram")
            for (i in TILE_ANIM until TILE_ANIM + COMPARED) {
                assertEquals(want[i], hw.vram[i], "f$f vram byte $i")
            }
            if (!want.copyOfRange(TILE_ANIM, TILE_ANIM + COMPARED)
                    .contentEquals(prev.copyOfRange(TILE_ANIM, TILE_ANIM + COMPARED))
            ) changed = true
            prev = want
        }
        assertTrue(changed, "the reference animation never changed")
    }

    @Test
    @VerifiesPort("BMapVSync_InitMapAnimations")
    fun `initMapAnimations initializes animation fields matching reference traces`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val chapterData = ChapterData(rom)
        val hw = BgHardware()
        val playSt = PlaySt()
        val anims = MapAnimations(rom, chapterData, hw, playSt)

        // 1. Prologue resume: r01149 (chapter 0)
        val runPrologue = ref.run("ram_trace_prologue_resume")
        val ew1149 = runPrologue.region("r01149", "ewram")
        val dump1149 = ProcArrayDump.ofEwram(ew1149).find(BM_VSYNC)
        assertNotNull(dump1149, "BMVSync proc in r01149")

        anims.seedTileAnimation(0x1111, 0x2222, 33)
        anims.seedPaletteAnimation(0x4444, 0x5555, 66)
        playSt.decode(ew1149, GPLAYST_OFFSET)

        anims.initMapAnimations()

        assertEquals(dump1149!!.u32(0x2C), anims.gfxAnimStart, "r01149 gfxAnimStart")
        assertEquals(dump1149.u32(0x30), anims.gfxAnimCurrent, "r01149 gfxAnimCurrent")
        assertEquals(
            dump1149.u16(0x34).toShort().toInt(),
            anims.gfxAnimClock,
            "r01149 gfxAnimClock",
        )
        assertEquals(dump1149.u32(0x38), anims.palAnimStart, "r01149 palAnimStart")
        assertEquals(dump1149.u32(0x3C), anims.palAnimCurrent, "r01149 palAnimCurrent")
        assertEquals(
            dump1149.u16(0x36).toShort().toInt(),
            anims.palAnimClock,
            "r01149 palAnimClock",
        )

        for (f in 1150..1154) {
            anims.updateMapImgAnimations()
            anims.updateMapPalAnimations()
            val dump = ProcArrayDump.ofEwram(
                runPrologue.region("r%05d".format(f), "ewram"),
            ).find(BM_VSYNC)
            assertNotNull(dump, "BMVSync proc in r%05d".format(f))
            assertEquals(dump!!.u32(0x2C), anims.gfxAnimStart, "r%05d gfxAnimStart".format(f))
            assertEquals(dump.u32(0x30), anims.gfxAnimCurrent, "r%05d gfxAnimCurrent".format(f))
            assertEquals(
                dump.u16(0x34).toShort().toInt(),
                anims.gfxAnimClock,
                "r%05d gfxAnimClock".format(f),
            )
            assertEquals(dump.u32(0x38), anims.palAnimStart, "r%05d palAnimStart".format(f))
            assertEquals(dump.u32(0x3C), anims.palAnimCurrent, "r%05d palAnimCurrent".format(f))
            assertEquals(
                dump.u16(0x36).toShort().toInt(),
                anims.palAnimClock,
                "r%05d palAnimClock".format(f),
            )
        }

        // 2. New game bm: r09513 (chapter 0x10 = 16)
        val runNewGame = ref.run("ram_trace_newgame_bm")
        val ew9513 = runNewGame.region("r09513", "ewram")
        val dump9513 = ProcArrayDump.ofEwram(ew9513).find(BM_VSYNC)
        assertNotNull(dump9513, "BMVSync proc in r09513")

        anims.seedTileAnimation(0x9999, 0x8888, 77)
        anims.seedPaletteAnimation(0x7777, 0x6666, 55)
        playSt.decode(ew9513, GPLAYST_OFFSET)

        anims.initMapAnimations()

        assertEquals(dump9513!!.u32(0x2C), anims.gfxAnimStart, "r09513 gfxAnimStart")
        assertEquals(dump9513.u32(0x30), anims.gfxAnimCurrent, "r09513 gfxAnimCurrent")
        assertEquals(
            dump9513.u16(0x34).toShort().toInt(),
            anims.gfxAnimClock,
            "r09513 gfxAnimClock",
        )
        assertEquals(dump9513.u32(0x38), anims.palAnimStart, "r09513 palAnimStart")
        assertEquals(dump9513.u32(0x3C), anims.palAnimCurrent, "r09513 palAnimCurrent")
        assertEquals(
            dump9513.u16(0x36).toShort().toInt(),
            anims.palAnimClock,
            "r09513 palAnimClock",
        )

        for (f in 9514..9515) {
            anims.updateMapImgAnimations()
            anims.updateMapPalAnimations()
            val dump = ProcArrayDump.ofEwram(
                runNewGame.region("r%05d".format(f), "ewram"),
            ).find(BM_VSYNC)
            assertNotNull(dump, "BMVSync proc in r%05d".format(f))
            assertEquals(dump!!.u32(0x2C), anims.gfxAnimStart, "r%05d gfxAnimStart".format(f))
            assertEquals(dump.u32(0x30), anims.gfxAnimCurrent, "r%05d gfxAnimCurrent".format(f))
            assertEquals(
                dump.u16(0x34).toShort().toInt(),
                anims.gfxAnimClock,
                "r%05d gfxAnimClock".format(f),
            )
            assertEquals(dump.u32(0x38), anims.palAnimStart, "r%05d palAnimStart".format(f))
            assertEquals(dump.u32(0x3C), anims.palAnimCurrent, "r%05d palAnimCurrent".format(f))
            assertEquals(
                dump.u16(0x36).toShort().toInt(),
                anims.palAnimClock,
                "r%05d palAnimClock".format(f),
            )
        }
    }

    @Test
    @VerifiesPort("BMapVSync_Start", "BMapVSync_OnLoop")
    fun `VSync proc lifecycle and loop match prologue resume reference trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val run = ref.run("ram_trace_prologue_resume")
        val ew1149 = run.region("r01149", "ewram")
        val dump1149 = ProcArrayDump.ofEwram(ew1149).find(BM_VSYNC)
        assertNotNull(dump1149, "BMVSync proc in r01149")

        val rom = RomView.ofRom(romFile.readBytes())
        val chapterData = ChapterData(rom)
        val hw = BgHardware()
        val playSt = PlaySt().also { it.decode(ew1149, GPLAYST_OFFSET) }
        val bmSt = BmSt().also { it.gameGfxSemaphore = 99 }
        val engine = ProcEngine()

        var wfxInitCalled = false
        var syncUnitSpriteSheetCalls = 0
        var wfxVSyncCalls = 0
        val anims = MapAnimations(
            rom = rom,
            chapterData = chapterData,
            hardware = hw,
            playSt = playSt,
            engine = engine,
            bmSt = bmSt,
        )
        anims.hooks.wfxInit = { wfxInitCalled = true }
        anims.hooks.syncUnitSpriteSheet = { syncUnitSpriteSheetCalls++ }
        anims.hooks.wfxVSync = { wfxVSyncCalls++ }

        val proc = anims.start()
        assertNotNull(proc, "BMapVSync_Start proc")
        assertEquals(0, proc!!.rootIndex, "proc root tree is PROC_TREE_VSYNC (0)")
        assertEquals(MapAnimations.PROC_MARK_DISP, proc.mark, "proc mark is PROC_MARK_DISP (1)")
        assertEquals(0, bmSt.gameGfxSemaphore, "bmSt.gameGfxSemaphore cleared to 0")
        assertTrue(wfxInitCalled, "wfxInit was called")

        assertEquals(dump1149!!.u32(0x2C), anims.gfxAnimStart, "r01149 gfxAnimStart")
        assertEquals(dump1149.u32(0x30), anims.gfxAnimCurrent, "r01149 gfxAnimCurrent")
        assertEquals(
            dump1149.u16(0x34).toShort().toInt(),
            anims.gfxAnimClock,
            "r01149 gfxAnimClock",
        )
        assertEquals(dump1149.u32(0x38), anims.palAnimStart, "r01149 palAnimStart")
        assertEquals(dump1149.u32(0x3C), anims.palAnimCurrent, "r01149 palAnimCurrent")
        assertEquals(
            dump1149.u16(0x36).toShort().toInt(),
            anims.palAnimClock,
            "r01149 palAnimClock",
        )

        for (f in 1150..1154) {
            engine.run(engine.root(0))
            val dump = ProcArrayDump.ofEwram(
                run.region("r%05d".format(f), "ewram"),
            ).find(BM_VSYNC)
            assertNotNull(dump, "BMVSync proc in r%05d".format(f))
            assertEquals(dump!!.u32(0x2C), anims.gfxAnimStart, "r%05d gfxAnimStart".format(f))
            assertEquals(dump.u32(0x30), anims.gfxAnimCurrent, "r%05d gfxAnimCurrent".format(f))
            assertEquals(
                dump.u16(0x34).toShort().toInt(),
                anims.gfxAnimClock,
                "r%05d gfxAnimClock".format(f),
            )
            assertEquals(dump.u32(0x38), anims.palAnimStart, "r%05d palAnimStart".format(f))
            assertEquals(dump.u32(0x3C), anims.palAnimCurrent, "r%05d palAnimCurrent".format(f))
            assertEquals(
                dump.u16(0x36).toShort().toInt(),
                anims.palAnimClock,
                "r%05d palAnimClock".format(f),
            )
            assertEquals(
                f - 1149,
                syncUnitSpriteSheetCalls,
                "syncUnitSpriteSheet called each frame",
            )
            assertEquals(f - 1149, wfxVSyncCalls, "wfxVSync called each frame")
            assertEquals(0, proc.rootIndex, "proc is still alive on tree 0 (not ended)")
        }
    }

    @Test
    fun `palette animations disable reset and proc end behave as transcribed`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val chapterData = ChapterData(rom)
        val hw = BgHardware()
        val playSt = PlaySt().apply { chapterIndex = 0 }
        val engine = ProcEngine()
        var endCalled = false
        val anims = MapAnimations(rom, chapterData, hw, playSt, engine)
        anims.hooks.onEnd = { endCalled = true }

        val proc = anims.start()
        assertNotNull(proc)
        anims.disableMapPaletteAnimations()
        assertEquals(0, anims.palAnimStart, "palette animations disabled")

        anims.resetMapPaletteAnimations()
        assertEquals(0, anims.palAnimStart, "chapter 0 has no palette animation")

        anims.end()
        assertNull(engine.find(anims.procScrBmVSync), "proc ended")
        assertTrue(endCalled, "onEnd hook called on proc termination")
    }

    private companion object {
        const val BM_VSYNC = 0x0859D8B8
        const val TILE_ANIM = 0xA000
        const val COMPARED = 0x1000
        const val GPLAYST_OFFSET = 0x0202BCF0 - 0x02000000
    }
}
