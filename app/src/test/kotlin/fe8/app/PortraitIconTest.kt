package fe8.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Structural checks of [PortraitIcon.build] against the real ROM (runtime `FE8_ASSETS`, skipped
 * when absent). No expected pixels are stored (CLAUDE.md rule 3).
 */
class PortraitIconTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun icon(): IntArray {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return PortraitIcon.build(romFile.readBytes())
    }

    @Test
    fun `Eirika's portrait id is 2`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assertEquals(2, PortraitIcon.portraitId(romFile.readBytes()))
    }

    @Test
    fun `icon has recents size, an opaque centre and transparent palette-0 corners`() {
        val px = icon()
        val n = PortraitIcon.SIZE
        assertEquals(192, n)
        assertEquals(n * n, px.size)
        assertEquals(0xFF, px[(n / 2) * n + n / 2] ushr 24, "centre must be opaque")
        val transparentCorners = listOf(0, n - 1, (n - 1) * n, n * n - 1).count { px[it] == 0 }
        assertTrue(transparentCorners >= 1, "palette index 0 must stay transparent at a corner")
    }

    @Test
    fun `every pixel is fully opaque or fully transparent`() {
        assertTrue(icon().all { it ushr 24 == 0xFF || it == 0 })
    }
}
