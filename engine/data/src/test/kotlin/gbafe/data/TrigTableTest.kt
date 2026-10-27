package gbafe.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TrigTableTest {
    @Test
    fun `parses gbafe trig and indexes sin and cos`() {
        val sin = (0 until 320).joinToString(",") { (it * 3 - 100).toString() }
        val t = TrigTable.parse("""{ "format": "gbafe.trig", "game": "fe8", "version": 1, "fractionBits": 12, "sin": [$sin] }""")
        assertEquals(12, t.fractionBits)
        assertEquals(-100, t.sin(0))
        assertEquals(-100 + 3 * 5, t.sin(0x105))
        assertEquals(-100 + 3 * 0x40, t.cos(0))
        assertEquals(-100 + 3 * 0x13F, t.cos(0xFF))
    }

    @Test
    fun `json reader handles nesting, strings and numbers`() {
        val v = JsonReader.parse(""" {"a": [1, -2.5, true, null, {"b": "x\"A"}], "c": {}} """) as Map<*, *>
        assertEquals(listOf(1L, -2.5, true, null, mapOf("b" to "x\"A")), v["a"])
        assertEquals(emptyMap<String, Any?>(), v["c"])
        assertThrows(IllegalArgumentException::class.java) { JsonReader.parse("[1,]x") }
    }
}
