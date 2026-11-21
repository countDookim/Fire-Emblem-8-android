package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.WorldMapApHost
import gba.testkit.AllocationMeter
import gbafe.render.ApHost
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The ROM sprite cache must not allocate on repeated AP display. */
class WorldMapApHostAllocationTest {
    @Test
    fun `cached sprite object lookup preserves identity and allocates nothing`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(AllocationMeter.supported && romFile.isFile)
        val names = ProcArrayDump.romNames()
        assertEquals("gSprite_Face64x96", names[FACE_SPRITE])
        assertEquals("gSprite_Face64x96_Flipped", names[FACE_SPRITE_FLIPPED])
        val host: ApHost = WorldMapApHost(
            RomView.ofRom(romFile.readBytes()), BgHardware(), OamBuffer(),
        )
        val expected = host.objectAt(FACE_SPRITE)
        val flipped = host.objectAt(FACE_SPRITE_FLIPPED)
        var last = expected
        var index = 0
        val bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 10_000) {
            last = host.objectAt(if (index++ and 1 == 0) FACE_SPRITE else FACE_SPRITE_FLIPPED)
        }
        assertSame(flipped, last)
        assertSame(expected, host.objectAt(FACE_SPRITE))
        assertEquals(0L, bytes, "cached object lookup")
    }

    private companion object {
        /** `gSprite_Face64x96`: a sprite object in the ROM. */
        const val FACE_SPRITE = 0x0859100C
        /** `gSprite_Face64x96_Flipped`. */
        const val FACE_SPRITE_FLIPPED = 0x08591026
    }
}
