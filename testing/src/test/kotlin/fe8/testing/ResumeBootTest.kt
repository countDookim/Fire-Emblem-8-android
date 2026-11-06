package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.core.sram.InMemorySaveStorage
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcFunc
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Booting with a suspend save in the save storage resumes the battle map (M6-12); booting with an
 * empty storage starts a new game and stores the image once the suspend save is written.
 *
 * The unit tests compare all [UnitTable.TOTAL_SIZE] bytes of our unit table at the resumed idle
 * phase with the idle dump r01299 of `ram_trace_ch2_scroll` / `ram_trace_prologue_resume`.
 * Masked: +0x3C..0x3F of every unit (`pMapSpriteHandle`), a runtime pointer into the map sprite
 * handle pool, allocated by the unit sprite loader and not a property of the saved unit.
 */
class ResumeBootTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    private class CountingStorage(private val initial: ByteArray?) : SaveStorage {
        var stores = 0
        var image: ByteArray? = initial

        override fun load(dest: ByteArray): Boolean {
            val saved = image ?: return false
            saved.copyInto(dest)
            return true
        }

        override fun store(src: ByteArray) {
            stores++
            image = src.copyOf()
        }
    }

    /** Boots on [storage], presses A on the title and runs to the idle player phase. */
    private fun bootToPhase(
        storage: SaveStorage,
        afterResume: (Fe8Boot, Proc) -> Unit = { _, _ -> },
    ): Pair<Fe8Boot, Boolean> {
        var frame = 0
        val keySource: () -> Any = { if (frame % 20 in 5..8) 1 else 0 }
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            (keySource as () -> Int),
            RngService(),
            saveStorage = storage,
        )
        val orig = boot.control.hooks.startResumedGame
        boot.control.hooks.startResumedGame = ProcFunc {
            orig.invoke(it)
            afterResume(boot, boot.engine.find(boot.bmapMain.script)!!)
        }
        while (frame < 3000 && boot.engine.find(boot.playerPhaseScript) == null) {
            boot.frame()
            frame++
        }
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 6000) boot.frame()
        val reached = !boot.eventEngine.eventEngineExists() &&
            boot.engine.find(boot.playerPhaseScript) != null
        return boot to reached
    }

    private fun needRom() = assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")

    @Test
    fun `boot with the chapter2 suspend save resumes chapter 2`() {
        needRom()
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present")
        val pre = ref.run("sram_chapter2_boot")
        assumeTrue(pre.hasSram, "sram_chapter2_boot has no SRAM dumps")
        val (boot, reached) = bootToPhase(CountingStorage(pre.sram("s00001")))
        assertTrue(reached, "player phase of the resumed map not reached")
        assertEquals(2, boot.control.playSt.chapterIndex)
    }

    /**
     * Ported from src/bmio.c: MapMain_ResumeFromPhaseIdle
     *
     * Ground truth: BMapMain (`gProc_BMapMain` at 0x0859A1F0) in the proc array of the dump r01149
     * of `ram_trace_prologue_resume` (and r01150 of `ram_trace_ch2_scroll`) is at command 40, which
     * is `PROC_LABEL(4)`; ours sits on label 4 right after `startResumedGame`. The BG0..3 / OBJ
     * enable bits of `gLCDControlBuffer.dispcnt` (IWRAM 0x03003080) of that dump equal ours. The
     * two refreshes (entity maps, unit sprites) are not compared.
     */
    @Test
    @VerifiesPort("MapMain_ResumeFromPhaseIdle")
    fun `resume from phase idle jumps BMapMain to label 4 like the dumps`() {
        needRom()
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present")
        val pre = ref.run("sram_chapter2_boot")
        assumeTrue(pre.hasSram, "sram_chapter2_boot has no SRAM dumps")
        val r = ref.run("ram_trace_ch2_scroll")
        val want = ProcArrayDump.ofEwram(r.region("r01150", "ewram")).find(0x0859A1F0)!!.scrCur
        assertEquals(40, want)
        val iwram = r.region("r01150", "iwram")
        val wantLayers = (iwram[0x3080].toInt() and 0xFF or (iwram[0x3081].toInt() and 0xFF shl 8)) and
            0x1F00
        var type = -1
        var got = -1
        var layers = -1
        bootToPhase(CountingStorage(pre.sram("s00001"))) { boot, proc ->
            type = boot.battleMap.hooks.suspendPointType()
            got = proc.scrCur
            layers = boot.ui.env.hardware.lcd.dispcnt and 0x1F00
        }
        assertEquals(0, type, "SUSPEND_POINT_PLAYERIDLE, the type chapter2.sav was suspended at")
        assertEquals(want, got)
        assertEquals(wantLayers, layers, "BG0..3 / OBJ enable bits of gLCDControlBuffer.dispcnt")
    }

    @Test
    fun `boot with the prologue suspend save resumes the prologue`() {
        needRom()
        val sav = listOf(
            File(assets, "prologue.sav"),
            File("/home/claude/fire-emblem-8-rom/reference/fixtures/prologue.sav"),
        ).firstOrNull { it.isFile && it.length() == CartSram.SIZE.toLong() }
        assumeTrue(sav != null, "prologue.sav fixture not present")
        val (boot, reached) = bootToPhase(CountingStorage(sav!!.readBytes()))
        assertTrue(reached, "player phase of the resumed map not reached")
        assertEquals(0, boot.control.playSt.chapterIndex)
    }

    private fun unitDiff(run: String, dump: String, got: ByteArray): List<String> {
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present")
        val want = ref.run(run).region(dump, "ewram").copyOfRange(
            UnitTable.BLUE_ADDRESS - UnitTable.EWRAM_BASE,
            UnitTable.BLUE_ADDRESS - UnitTable.EWRAM_BASE + UnitTable.TOTAL_SIZE,
        )
        val mine = got.copyOf()
        for (u in 0 until UnitTable.TOTAL_SIZE / UnitTable.UNIT_SIZE) {
            for (i in 0x3C until 0x40) {
                want[u * UnitTable.UNIT_SIZE + i] = 0
                mine[u * UnitTable.UNIT_SIZE + i] = 0
            }
        }
        return (0 until UnitTable.TOTAL_SIZE).filter { want[it] != mine[it] }.take(20).map {
            "slot ${it / UnitTable.UNIT_SIZE} +0x%X ours %02X dump %02X".format(
                it % UnitTable.UNIT_SIZE, mine[it], want[it],
            )
        }
    }

    @Test
    fun `chapter2 units at the resumed idle phase equal the reference`() {
        needRom()
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present")
        val (boot, reached) = bootToPhase(CountingStorage(ref.run("sram_chapter2_boot").sram("s00001")))
        assertTrue(reached)
        assertEquals(emptyList<String>(), unitDiff("ram_trace_ch2_scroll", "r01299", boot.units.bytes))
    }

    @Test
    fun `prologue units at the resumed idle phase equal the reference`() {
        needRom()
        val sav = File("/home/claude/fire-emblem-8-rom/reference/fixtures/prologue.sav")
        assumeTrue(sav.isFile, "prologue.sav fixture not present")
        val (boot, reached) = bootToPhase(CountingStorage(sav.readBytes()))
        assertTrue(reached)
        assertEquals(emptyList<String>(), unitDiff("ram_trace_prologue_resume", PRO_DUMP, boot.units.bytes))
    }

    private companion object {
        const val PRO_DUMP = "r01299"
    }

    @Test
    fun `new game boot stores the image once the suspend save is written`() {
        needRom()
        val storage = CountingStorage(null)
        val (_, reached) = bootToPhase(storage)
        assertTrue(reached)
        assertTrue(storage.stores >= 2, "new game save + suspend save stored: ${storage.stores}")
        val fresh = InMemorySaveStorage()
        fresh.store(storage.image!!)
        assertTrue(fresh.load(ByteArray(CartSram.SIZE)))
    }
}
