package gbafe.script

import gbafe.core.flags.EventFlags
import gbafe.core.flags.EventFlagLayout
import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.MOD_BASE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    private val flagSpec = CommandSpec("flag", 2)
    private val setSlot = CommandSpec("set_slot", 2)
    private val expander = ModFlagSlotExpander(symbols, setSlot, 2) { listOf(0, 1) }

    private fun cmd(vararg a: Arg) = ScriptLine.Command(flagSpec, a.toList(), 7)

    @Test
    fun `a mod flag gets a set_slot before the command`() {
        val c = cmd(Arg.IdentArg("MyMod.Seen"), Arg.IntArg(0))
        val out = expander.expand(c)!!
        assertEquals(
            listOf(ScriptLine.Command(setSlot, listOf(Arg.IntArg(2), Arg.IntArg(handle)), 7), c),
            out,
        )
    }

    @Test
    fun `commands without a mod flag are unchanged`() {
        assertEquals(null, expander.expand(cmd(Arg.IdentArg("EVFLAG_8"), Arg.IntArg(3))))
    }

    @Test
    fun `two mod flags in one command are rejected`() {
        val e = assertThrows(ScriptAssemblyException::class.java) {
            expander.expand(cmd(Arg.IdentArg("MyMod.Seen"), Arg.IdentArg("MyMod.Seen")))
        }
        assertEquals(7, e.line)
    }

    @Test
    fun `assembled size grows only by the set_slot words`() {
        val enc = object : CommandEncoder {
            override val labelWordCount = 2
            override fun wordCount(spec: CommandSpec) = if (spec.name == "set_slot") 3 else 2
            override fun encode(
                spec: CommandSpec,
                args: List<Arg>,
                symbols: SymbolResolver,
                out: IntArray,
                pos: Int,
            ) = Unit

            override fun encodeLabel(number: Int, out: IntArray, pos: Int) = Unit
        }
        val lines = listOf(
            ScriptLine.EventStart("E", 1),
            cmd(Arg.IdentArg("MyMod.Seen"), Arg.IntArg(0)),
            cmd(Arg.IntArg(1), Arg.IntArg(0)),
        )
        val plain = ScriptAssembler(enc).assemble(lines).find("E")!!.wordCount
        val expanded = ScriptAssembler(enc, expander = expander).assemble(lines).find("E")!!
        assertEquals(4, plain)
        assertEquals(7, expanded.wordCount)
    }
}
