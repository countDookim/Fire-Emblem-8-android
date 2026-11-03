package gbafe.extract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecordingSourcesTest {
    private val symbols = Symbols(
        listOf(
            "08000100 00000010 R gTableA",
            "08000110 R gTableB",
            "08000200 R gOther",
        ),
    )

    @Test
    fun `records exactly the answers the run used`() {
        val rec = RecordingSources(DecompSources(symbols, null))
        assertEquals(0x08000100, rec["gTableA"].address)
        assertEquals(0xF0, rec.sizeOrSpan("gTableB"))
        assertEquals(listOf("gTableA", "gTableB"), rec.namesWithPrefix("gTable"))
        val names = rec.romNamesByAddress()
        assertEquals("gOther", names[0x08000200])
        assertEquals(null, names[0x08000300])
        assertEquals(emptyMap<Int, String>(), rec.enumNames("include/constants/items.h", "ITEM_"))
        val m = rec.manifest()
        assertEquals(setOf("gTableA"), (m["symbols"] as Map<*, *>).keys)
        assertEquals(mapOf("gTableB" to 0xF0), m["spans"])
        assertEquals(mapOf("0x08000200" to "gOther", "0x08000300" to null), m["romNames"])
        assertEquals(mapOf("include/constants/items.h ITEM_" to emptyMap<String, String>()), m["enums"])
    }

    @Test
    fun `a written manifest answers the same as the decomp and refuses other queries`() {
        val rec = RecordingSources(DecompSources(symbols, null))
        rec["gTableA"]
        rec.sizeOrSpan("gTableB")
        rec.namesWithPrefix("gTable")
        rec.romNamesByAddress().let { it[0x08000200]; it[0x08000300] }
        rec.enumNames("include/constants/items.h", "ITEM_")
        rec.faces()
        val m = ManifestSources(Json.write(rec.manifest()))
        assertEquals(symbols["gTableA"], m["gTableA"])
        assertEquals(0xF0, m.sizeOrSpan("gTableB"))
        assertEquals(listOf("gTableA", "gTableB"), m.namesWithPrefix("gTable"))
        assertEquals("gOther", m.romNamesByAddress()[0x08000200])
        assertEquals(null, m.romNamesByAddress()[0x08000300])
        assertEquals(emptyMap<Int, String>(), m.enumNames("include/constants/items.h", "ITEM_"))
        assertEquals(emptyMap<String, Int>(), m.faces())
        assertThrows<IllegalArgumentException> { m["gOther"] }
        assertThrows<IllegalArgumentException> { m.romNamesByAddress()[0x08000100] }
    }
}
