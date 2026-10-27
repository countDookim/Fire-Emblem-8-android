package fe8.testing

import gbafe.data.TrigTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The extracted `data/trig.json` loads through [TrigTable] to exactly the ROM's `gSinLookup` (0x080D751C, 320 s16). */
class TrigAssetTest {
    private val assets = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets")

    @Test
    fun `extracted trig table equals gSinLookup`() {
        val json = File(assets, "extracted/data/trig.json")
        val rom = File(assets, "baserom.gba")
        assumeTrue(json.isFile && rom.isFile, "extracted assets / ROM not present (run tools/setup.sh)")
        val t = TrigTable.parse(json.readText())
        val r = rom.readBytes()
        val at = 0x0D751C
        for (i in 0 until 320) {
            assertEquals(((r[at + 2 * i].toInt() and 0xFF) or (r[at + 2 * i + 1].toInt() shl 8)).toShort(), t.table[i], "entry $i")
        }
        assertEquals(12, t.fractionBits)
        assertEquals(1 shl 12, t.sin(0x40))
        assertEquals(1 shl 12, t.cos(0))
    }
}
