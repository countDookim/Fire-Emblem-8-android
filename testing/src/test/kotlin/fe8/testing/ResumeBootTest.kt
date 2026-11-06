package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import gba.core.sram.CartSram
import gba.core.sram.InMemorySaveStorage
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Booting with a suspend save in the save storage resumes the battle map (M6-12); booting with an
 * empty storage starts a new game and stores the image once the suspend save is written.
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
    private fun bootToPhase(storage: SaveStorage): Pair<Fe8Boot, Boolean> {
        var frame = 0
        val keySource: () -> Any = { if (frame % 20 in 5..8) 1 else 0 }
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            (keySource as () -> Int),
            RngService(),
            saveStorage = storage,
        )
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
