package gbafe.extract.fe8.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Fe8ScriptFilesTest {
    private fun base(): MutableMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        var a = Fe8ScriptFiles.DATA_START
        for ((_, name) in Fe8ScriptFiles.FIRST_SCRIPTS) {
            m[name] = a
            a += 0x10
        }
        return m
    }

    @Test
    fun `scripts go to the last header starting at or before them`() {
        val m = base()
        val pro = m.getValue("EventScr_Prologue_BeginningScene")
        m["EventScr_Prologue_Mid"] = pro + 4
        m["EventScr_Outside"] = 0x08123456
        m["EventScr_AfterEnd"] = Fe8ScriptFiles.DATA_END
        val g = Fe8ScriptFiles.group(m)
        assertEquals(listOf("common", "prologue"), g.keys.take(2).toList())
        assertEquals(
            listOf("EventScr_Prologue_BeginningScene", "EventScr_Prologue_Mid"),
            g.getValue("prologue").take(2),
        )
        assertEquals(
            listOf("EventScr_Outside", "EventScr_9EEA58", "EventScr_AfterEnd"),
            g.getValue("common"),
        )
    }

    @Test
    fun `missing or unordered first script fails`() {
        val m = base()
        m.remove("EventScr_Ch2_BeginningScene")
        assertThrows(IllegalStateException::class.java) { Fe8ScriptFiles.group(m) }
        val n = base()
        n["EventScr_Ch2_BeginningScene"] = 0x089EEA58
        assertThrows(IllegalStateException::class.java) { Fe8ScriptFiles.group(n) }
    }
}
