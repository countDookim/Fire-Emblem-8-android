package gbafe.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Tests of [ScriptImage], [ScriptAssembler] and the `A|B` mask syntax with a toy encoder. */
class ScriptAssemblerTest {
    private val ops = CommandSet {
        when (it) {
            "op" -> CommandSpec("op", 1)
            "jump" -> CommandSpec("jump", 1)
            else -> null
        }
    }

    /** `op v` is two words (0x0120, v), `jump target` is three words (0x0A40, 0, address). */
    private val encoder = object : CommandEncoder {
        override val labelWordCount = 2

        override fun wordCount(spec: CommandSpec) = if (spec.name == "op") 2 else 3

        override fun encode(
            spec: CommandSpec,
            args: List<Arg>,
            symbols: SymbolResolver,
            out: IntArray,
            pos: Int,
        ) {
            val a = args[0]
            val v = if (a is Arg.IntArg) {
                a.value.toLong()
            } else {
                symbols.resolveMask((a as Arg.IdentArg).name, "k")
            }
            require(v != SymbolResolver.UNRESOLVED) { "unresolved" }
            if (spec.name == "op") {
                out[pos] = 0x0120
                out[pos + 1] = v.toInt() and 0xFFFF
            } else {
                out[pos] = 0x0A40
                out[pos + 2] = v.toInt() and 0xFFFF
            }
        }

        override fun encodeLabel(number: Int, out: IntArray, pos: Int) {
            out[pos] = 0x0820
            out[pos + 1] = number
        }
    }

    private val constants = SymbolResolver { name, kind ->
        when {
            kind != "k" -> SymbolResolver.UNRESOLVED
            name == "A" -> 1L
            name == "B" -> 4L
            else -> SymbolResolver.UNRESOLVED
        }
    }

    private fun parse(text: String) =
        FeScriptParser(ops, { it == "A" || it == "B" }).parseOrThrow("t", text)

    @Test
    fun `masks assemble to the OR of their names`() {
        val image = ScriptAssembler(encoder, constants)
            .assemble(parse("event One\n    op A|B\n    label 3\n"))
        val e = image.find("One")!!
        assertEquals(listOf(0x0120, 5, 0x0820, 3), image.words.toList().subList(0, 4))
        assertEquals(4, e.wordCount)
    }

    @Test
    fun `scripts resolve each other and get fixed or synthetic addresses`() {
        val text = "event Second\n    op 7\n\nevent First\n    jump Second\n"
        val image = ScriptAssembler(encoder, constants).assemble(parse(text)) {
            if (it == "First") 0x08000100 else ScriptBases.NONE
        }
        val second = image.find("Second")!!
        assertEquals(ScriptAssembler.SYNTHETIC_BASE, second.baseAddress)
        val first = image.find("First")!!
        assertEquals(second.baseAddress and 0xFFFF, image.words[first.wordOffset + 2])
        assertEquals(2, image.entryCount)
        assertEquals(first, image.entryContaining(0x08000104))
        assertNull(image.entryContaining(0x08000100 + first.byteCount))
        assertEquals(first.wordOffset + 2, image.wordIndexOfAddress(0x08000104))
        assertEquals(-1, image.wordIndexOfAddress(0x08000105))
        assertNull(image.find("Nope"))
    }

    @Test
    fun `an unknown symbol is reported with its line`() {
        val e = assertThrows<ScriptAssemblyException> {
            ScriptAssembler(encoder).assemble(parse("event X\n    op A\n"))
        }
        assertEquals(2, e.line)
    }
}
