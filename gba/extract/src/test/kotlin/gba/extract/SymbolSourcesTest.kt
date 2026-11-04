package gba.extract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SymbolSourcesTest {
    private val fake = object : SymbolSources {
        override fun get(name: String) = Symbols.Symbol(name, 0x08001000, 16)
        override fun sizeOrSpan(name: String) = 32
        override fun namesWithPrefix(prefix: String) = listOf("${prefix}A", "${prefix}B")
        override fun romNamesByAddress() = mapOf(0x08002000 to "gFoo")
        override fun enumNames(relPath: String, prefix: String) = mapOf(1 to "X_ONE")
    }

    @Test
    fun `recorded manifest round-trips through json`() {
        val rec = RecordingSymbolSources(fake)
        rec["gSym"]
        rec.sizeOrSpan("gSym")
        rec.namesWithPrefix("gP")
        rec.romNamesByAddress()[0x08002000]
        rec.enumNames("include/x.h", "X_")
        val m = ManifestSymbolSources(Json.write(rec.manifest()))
        assertEquals(fake["gSym"], m["gSym"])
        assertEquals(32, m.sizeOrSpan("gSym"))
        assertEquals(listOf("gPA", "gPB"), m.namesWithPrefix("gP"))
        assertEquals("gFoo", m.romNamesByAddress()[0x08002000])
        assertEquals(mapOf(1 to "X_ONE"), m.enumNames("include/x.h", "X_"))
        assertThrows(IllegalArgumentException::class.java) { m["other"] }
    }
}
