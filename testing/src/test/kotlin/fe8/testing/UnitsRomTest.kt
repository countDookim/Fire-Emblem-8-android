package fe8.testing

import gbafe.extract.fe8.units.UnitsExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-16: prologue unit placement. `UnitDef_Event_PrologueAlly` 0x088B3C14 (0x3C bytes) and
 * `UnitDef_Event_PrologueEnemy` 0x088B3C50 (0x50) in decomp/fireemblem8.nm.txt against
 * `src/events/prologue-eventudefs.h` (pinned 04d1edb).
 */
class UnitsRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun rom(): ByteArray {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return romFile.readBytes()
    }

    @Test
    fun `prologue allies match prologue-eventudefs_h`() {
        val allies = UnitsExtractor.readGroup(rom(), 0x8B3C14, 0x3C / UnitsExtractor.ENTRY_SIZE)
        assertEquals(2, allies.size)
        val (seth, eirika) = allies
        // Seth: CLASS_PALADIN, FACTION_ID_BLUE, level 1, (13, 9), Steel Sword / Silver Lance / Vulnerary, 6 REDAs.
        assertEquals(listOf(0x02, 0x07, 0, 1, 13, 9), listOf("charIndex", "classIndex", "allegiance", "level", "xPosition", "yPosition").map { seth[it] })
        assertEquals(listOf(0x03, 0x17, 0x6C, 0), seth["items"])
        @Suppress("UNCHECKED_CAST") val redas = seth["redas"] as List<Map<String, Int>>
        assertEquals(listOf(13 to 8, 10 to 8, 10 to 7, 9 to 7, 9 to 5, 8 to 5), redas.map { it.getValue("x") to it.getValue("y") })
        assertEquals(mapOf("x" to 13, "y" to 8, "flags" to 0x18, "a" to 0, "b" to 0xFFFF, "delayFrames" to 0), redas[0])
        // Eirika: CLASS_EIRIKA_LORD at (8, 5), one REDA {9, 5, flags 0x10, a 2}.
        assertEquals(listOf(0x01, 0x02, 8, 5), listOf("charIndex", "classIndex", "xPosition", "yPosition").map { eirika[it] })
        assertEquals(mapOf("x" to 9, "y" to 5, "flags" to 0x10, "a" to 2, "b" to 0xFFFF, "delayFrames" to 0), (eirika["redas"] as List<*>).single())
    }

    @Test
    fun `prologue enemies match prologue-eventudefs_h`() {
        val enemies = UnitsExtractor.readGroup(rom(), 0x8B3C50, 0x50 / UnitsExtractor.ENTRY_SIZE)
        assertEquals(3, enemies.size)
        // O'Neill: CLASS_FIGHTER, FACTION_ID_RED, level 4, (14, 8), Iron Axe, ai {6, 3, 0, 0}, REDA (10, 8).
        val oneill = enemies[0]
        assertEquals(listOf(0x68, 0x3F, 2, 4, 14, 8), listOf("charIndex", "classIndex", "allegiance", "level", "xPosition", "yPosition").map { oneill[it] })
        assertEquals(listOf(0x1F, 0, 0, 0), oneill["items"])
        assertEquals(listOf(6, 3, 0, 0), oneill["ai"])
        @Suppress("UNCHECKED_CAST") val r = (oneill["redas"] as List<Map<String, Int>>).single()
        assertEquals(10 to 8, r.getValue("x") to r.getValue("y"))
    }
}
