package fe8.testing

import fe8.game.RomView
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * D-08: while a convo background owns BG3 the map tile animation must not overwrite its
 * characters (VRAM 0xA000..0xAFFF): `Event23_DisaleMapDisp` suspends the VBlank proc.
 */
class ConvoBackgroundVramTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun expected(rom: RomView): ByteArray {
        val bg = ByteArray(0x20000)
        rom.decompressInto(rom.u32(CONVO_BACKGROUND_DATA + 12 * BACKGROUND), bg, 0)
        return bg.copyOfRange(0x2000, 0x3000)
    }

    @Test
    fun `reference dumps keep the background characters`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val want = expected(RomView.ofRom(romFile.readBytes()))
        val run = ref.run("ram_trace_prologue")
        for (f in listOf(13200, 13500, 13800, 14100)) {
            val vram = run.region("f%05d".format(f), "vram")
            assertTrue(want.contentEquals(vram.copyOfRange(0xA000, 0xB000)), "f$f BG3 chars")
        }
    }

    @Test
    fun `map tile animation does not overwrite the background while the talk scene runs`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val want = expected(RomView.ofRom(romFile.readBytes()))
        val pb = PrologueBoot(romFile)
        var loaded = 0
        var checked = 0
        var bad = 0
        pb.onFrame = { frame ->
            val hw = pb.boot.ui.env.hardware
            val equal = want.contentEquals(hw.vram.copyOfRange(0xA000, 0xB000))
            if (loaded == 0 && equal) loaded = frame
            val running = pb.boot.eventEngine.eventEngineExists()
            if (loaded != 0 && running && frame < loaded + WINDOW) {
                checked++
                if (!equal) bad++
            }
        }
        assertTrue(pb.reachIdle(), "Prologue idle not reached")
        assertTrue(loaded > 0, "background characters were never loaded")
        assertTrue(checked >= 300, "frames checked: $checked")
        assertEquals(0, bad, "overwritten frames (of $checked, from frame $loaded)")
    }

    private companion object {
        const val CONVO_BACKGROUND_DATA = 0x0895DD1C
        const val BACKGROUND = 29
        /** Frames of the talk scene (the map is resumed 331 frames after the load). */
        const val WINDOW = 330
    }
}
