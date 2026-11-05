package gbafe.script

import gbafe.core.flags.EventFlags
import gbafe.core.flags.EventFlagLayout
import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.MOD_BASE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModFlagSymbolsTest {
    private val layout = EventFlagLayout(
        permanentSaveBytes = 0x19,
        chapterSaveBytes = 5,
        permanentStorageBytes = 32,
        chapterStorageBytes = 16,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )
    private val registry = FlagRegistry(EventFlags(layout), mapOf("EVFLAG_8" to 8))
    private val handle = registry.register("m", "MyMod.Seen")
    private val symbols = ModFlagSymbols(registry)

    @Test
    fun `mod flag resolves only for the event_flag kind`() {
        assertEquals(handle.toLong(), symbols.resolve("MyMod.Seen", "event_flag"))
        assertTrue(handle >= MOD_BASE)
        assertEquals(SymbolResolver.UNRESOLVED, symbols.resolve("MyMod.Seen", "character"))
        assertEquals(SymbolResolver.UNRESOLVED, symbols.resolve("MyMod.Seen", null))
    }

    @Test
    fun `unknown and vanilla names stay unresolved`() {
        assertEquals(SymbolResolver.UNRESOLVED, symbols.resolve("MyMod.Nope", "event_flag"))
        assertEquals(SymbolResolver.UNRESOLVED, symbols.resolve("EVFLAG_8", "event_flag"))
    }

    @Test
    fun `identifier check knows mod names only`() {
        assertTrue(symbols.isKnown("MyMod.Seen"))
        assertFalse(symbols.isKnown("MyMod.Nope"))
        assertFalse(symbols.isKnown("EVFLAG_8"))
    }
}
