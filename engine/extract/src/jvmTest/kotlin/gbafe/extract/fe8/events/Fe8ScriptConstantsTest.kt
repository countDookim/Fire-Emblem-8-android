package gbafe.extract.fe8.events

import gbafe.script.ConstantOrigin
import gbafe.script.MutableScriptConstants
import gbafe.script.ScriptConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class Fe8ScriptConstantsTest {
    private val c = Fe8ScriptConstants

    @Test
    fun `names resolve to the decomp values and back`() {
        assertEquals(2, c.valueOf(Fe8ScriptConstants.KIND_CHARACTER, "CHARACTER_SETH"))
        assertEquals(38, c.valueOf(Fe8ScriptConstants.KIND_SONG, "SONG_RAID"))
        assertEquals(12, c.valueOf(Fe8ScriptConstants.KIND_SLOT, "EVT_SLOT_C"))
        assertEquals(3, c.valueOf(Fe8ScriptConstants.KIND_EVENT_FLAG, "EVFLAG_WIN"))
        assertEquals(101, c.valueOf(Fe8ScriptConstants.KIND_EVENT_FLAG, "EVFLAG_GAMEOVER"))
        assertEquals(16, c.valueOf(Fe8ScriptConstants.KIND_CHAPTER, "CHAPTER_E_16"))
        assertEquals(
            1 shl 14,
            c.valueOf(Fe8ScriptConstants.KIND_MENU_OVERRIDE, "EVENT_MENUOVERRIDE_END"),
        )
        assertEquals("CHARACTER_SETH", c.nameOf(Fe8ScriptConstants.KIND_CHARACTER, 2))
        assertEquals("SONG_RAID", c.nameOf(Fe8ScriptConstants.KIND_SONG, 38))
        assertEquals("EVT_SLOT_C", c.nameOf(Fe8ScriptConstants.KIND_SLOT, 12))
        assertEquals("EVFLAG_WIN", c.nameOf(Fe8ScriptConstants.KIND_EVENT_FLAG, 3))
        assertEquals("EVFLAG_GAMEOVER", c.nameOf(Fe8ScriptConstants.KIND_EVENT_FLAG, 101))
        assertEquals("CHAPTER_E_16", c.nameOf(Fe8ScriptConstants.KIND_CHAPTER, 16))
        assertEquals(ScriptConstants.NONE, c.valueOf(Fe8ScriptConstants.KIND_SONG, "SONG_NOPE"))
        assertEquals(null, c.nameOf(Fe8ScriptConstants.KIND_SONG, 0x3000))
        assertEquals(-1, c.valueOf(Fe8ScriptConstants.KIND_CHARACTER, "CHARACTER_EVT_ACTIVE"))
    }

    @Test
    fun `event flags have filler and derived names`() {
        val k = Fe8ScriptConstants.KIND_EVENT_FLAG
        assertEquals(41, c.valueOf(k, "EVFLAG_41"))
        assertEquals("EVFLAG_41", c.nameOf(k, 41))
        assertEquals(ConstantOrigin.FILLER, c.originOf(k, "EVFLAG_41"))
        assertEquals(103 + 9, c.valueOf(k, "EVFLAG_EXTRA_UNIT_9"))
        assertEquals(ConstantOrigin.DERIVED, c.originOf(k, "EVFLAG_EXTRA_UNIT_9"))
        assertEquals(ConstantOrigin.DECOMP, c.originOf(k, "EVFLAG_WIN"))
        // The decomp name wins the reverse lookup over the derived alias of the same id.
        assertEquals("EVFLAG_EXTRA_UNIT_BASE", c.nameOf(k, 103))
        // Every id up to the highest named flag has a name.
        for (id in 0..136) assertNotNull(c.nameOf(k, id), "flag $id")
    }

    @Test
    fun `mods layer over the base`() {
        val m = MutableScriptConstants(Fe8ScriptConstants)
        m.define(Fe8ScriptConstants.KIND_SONG, "SONG_MY_MOD", 0x200)
        assertEquals(0x200, m.valueOf(Fe8ScriptConstants.KIND_SONG, "SONG_MY_MOD"))
        assertEquals("SONG_MY_MOD", m.nameOf(Fe8ScriptConstants.KIND_SONG, 0x200))
        assertEquals(38, m.valueOf(Fe8ScriptConstants.KIND_SONG, "SONG_RAID"))
    }
}
