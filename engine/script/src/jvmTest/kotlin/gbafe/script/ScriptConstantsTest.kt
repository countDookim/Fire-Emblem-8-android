package gbafe.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScriptConstantsTest {
    private val base = TableScriptConstants(
        mapOf(
            "song" to ConstantTable(arrayOf("SONG_A", "SONG_B", "SONG_C"), intArrayOf(5, 1, 5)),
        ),
    )

    @Test
    fun `table resolves both directions and the first duplicate is primary`() {
        assertEquals(1, base.valueOf("song", "SONG_B"))
        assertEquals(5, base.valueOf("song", "SONG_C"))
        assertEquals("SONG_A", base.nameOf("song", 5))
        assertEquals("SONG_B", base.nameOf("song", 1))
        assertNull(base.nameOf("song", 2))
        assertEquals(ScriptConstants.NONE, base.valueOf("song", "NOPE"))
        assertEquals(ScriptConstants.NONE, base.valueOf("item", "SONG_A"))
        assertNull(base.findValue("song", "NOPE"))
        assertEquals(5, base.findValue("song", "SONG_A"))
    }

    @Test
    fun `mutable layer adds entries and overrides the base`() {
        val m = MutableScriptConstants(base)
        m.define("song", "SONG_MOD", 99)
        m.define("song", "SONG_B", 7)
        m.define("modkind", "X", 1)
        assertEquals(99, m.valueOf("song", "SONG_MOD"))
        assertEquals("SONG_MOD", m.nameOf("song", 99))
        assertEquals(7, m.valueOf("song", "SONG_B"))
        assertEquals("SONG_B", m.nameOf("song", 7))
        assertEquals(5, m.valueOf("song", "SONG_A"))
        assertEquals("SONG_A", m.nameOf("song", 5))
        assertEquals("X", m.nameOf("modkind", 1))
        m.define("song", "SONG_MOD", 100)
        assertEquals(100, m.valueOf("song", "SONG_MOD"))
        assertEquals("SONG_MOD", m.nameOf("song", 100))
        assertEquals(ScriptConstants.NONE, MutableScriptConstants().valueOf("song", "SONG_A"))
    }
}
