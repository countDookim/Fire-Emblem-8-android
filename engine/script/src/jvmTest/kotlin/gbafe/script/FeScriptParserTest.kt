package gbafe.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeScriptParserTest {
    private val sleep = CommandSpec("sleep", 1, listOf(ArgKind.INT))
    private val specs = listOf(
        CommandSpec("nop", 0),
        sleep,
        CommandSpec("move", 3),
        CommandSpec("call", 2, listOf(ArgKind.INT, ArgKind.IDENT)),
    )
    private val commands = CommandSet { name ->
        if (name == "wait") sleep else specs.firstOrNull { it.name == name }
    }
    private val constants = setOf("CHAR_A", "CHAR_B")
    private val parser = FeScriptParser(commands, { it in constants })

    private fun errors(text: String): List<String> =
        parser.parse("test.fe8s", text).errors.map { it.toString() }

    @Test
    fun `parses blocks labels commands and comments`() {
        val text = """
            # header comment

            event First
                nop
                label 0
                move CHAR_A -3 0x404   # trailing comment
                call 0 Second
            event Second
                label 7
                wait 60
        """.trimIndent()
        val r = parser.parse("test.fe8s", text)
        assertTrue(r.ok)
        val expected = listOf(
            ScriptLine.EventStart("First", 3),
            ScriptLine.Command(specs[0], emptyList(), 4),
            ScriptLine.Label(0, 5),
            ScriptLine.Command(
                specs[2],
                listOf(Arg.IdentArg("CHAR_A"), Arg.IntArg(-3), Arg.IntArg(0x404)),
                6,
            ),
            ScriptLine.Command(specs[3], listOf(Arg.IntArg(0), Arg.IdentArg("Second")), 7),
            ScriptLine.EventStart("Second", 8),
            ScriptLine.Label(7, 9),
            ScriptLine.Command(sleep, listOf(Arg.IntArg(60)), 10),
        )
        assertEquals(expected, r.lines)
    }

    @Test
    fun `alias resolves to the canonical spec`() {
        val lines = parser.parseOrThrow("test.fe8s", "event A\n  wait 1\n")
        assertEquals("sleep", (lines[1] as ScriptLine.Command).spec.name)
    }

    @Test
    fun `large hex wraps to a negative int and CRLF is accepted`() {
        val lines = parser.parseOrThrow("t", "event A\r\n  sleep 0xFFFFFFFF\r\n")
        assertEquals(listOf<Arg>(Arg.IntArg(-1)), (lines[1] as ScriptLine.Command).args)
    }

    @Test
    fun `unknown command`() {
        assertEquals(listOf("test.fe8s:2: unknown command 'jump'"), errors("event A\n  jump 1\n"))
    }

    @Test
    fun `wrong argument count`() {
        assertEquals(
            listOf(
                "test.fe8s:2: wrong argument count for 'move': expected 3, got 2",
                "test.fe8s:3: wrong argument count for 'nop': expected 0, got 1",
            ),
            errors("event A\n  move 1 2\n  nop 5\n"),
        )
    }

    @Test
    fun `unknown identifier`() {
        assertEquals(
            listOf("test.fe8s:2: unknown identifier 'CHAR_Z'"),
            errors("event A\n  move CHAR_Z 1 2\n"),
        )
    }

    @Test
    fun `malformed number`() {
        assertEquals(
            listOf(
                "test.fe8s:2: malformed number '12ab'",
                "test.fe8s:3: malformed number '0x'",
                "test.fe8s:4: malformed number '99999999999'",
                "test.fe8s:5: malformed number '0xZZ'",
            ),
            errors("event A\n  sleep 12ab\n  sleep 0x\n  sleep 99999999999\n  sleep 0xZZ\n"),
        )
    }

    @Test
    fun `command outside an event block`() {
        assertEquals(
            listOf("test.fe8s:1: command 'nop' outside an event block"),
            errors("nop\nevent A\n  nop\n"),
        )
    }

    @Test
    fun `label outside an event block and duplicates`() {
        assertEquals(
            listOf(
                "test.fe8s:1: label outside an event block",
                "test.fe8s:4: duplicate label 1",
                "test.fe8s:5: duplicate event 'A'",
            ),
            errors("label 1\nevent A\n label 1\n label 1\nevent A\n"),
        )
    }

    @Test
    fun `labels may repeat across events`() {
        assertTrue(parser.parse("t", "event A\n label 1\nevent B\n label 1\n").ok)
    }

    @Test
    fun `argument kinds are checked`() {
        assertEquals(
            listOf(
                "test.fe8s:2: argument 1 of 'sleep' must be a number, got 'CHAR_A'",
                "test.fe8s:3: argument 2 of 'call' must be an identifier, got '5'",
            ),
            errors("event A\n  sleep CHAR_A\n  call 1 5\n"),
        )
    }

    @Test
    fun `malformed headers and identifiers`() {
        assertEquals(
            listOf(
                "test.fe8s:1: malformed event header, expected 'event <Name>'",
                "test.fe8s:3: malformed identifier '\$x'",
            ),
            errors("event\nevent A\n  call 1 \$x\n"),
        )
    }

    @Test
    fun `parseOrThrow reports all errors in file line format`() {
        val e = assertThrows(ScriptParseException::class.java) {
            parser.parseOrThrow("test.fe8s", "event A\n  jump\n  sleep x\n")
        }
        assertEquals(2, e.errors.size)
        assertEquals(
            "test.fe8s:2: unknown command 'jump'\n" +
                "test.fe8s:3: argument 1 of 'sleep' must be a number, got 'x'",
            e.message,
        )
        assertFalse(parser.parse("t", "event A\n jump\n").ok)
    }
}
