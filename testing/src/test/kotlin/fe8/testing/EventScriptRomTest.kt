package fe8.testing

import gbafe.extract.fe8.events.EventScriptConverter
import gbafe.extract.fe8.events.Fe8EventCommands
import gbafe.extract.fe8.events.Fe8EventCommands.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-15: event converter v0 on the prologue. `EventScr_Prologue_BeginningScene` 0x089EEBE8 (0xD4
 * bytes) and `EventScr_Prologue_RenaisThroneCutscene` 0x089EECBC (0x3A4) in
 * decomp/fireemblem8.nm.txt, checked against `src/events/prologue-eventscript.h` (pinned 04d1edb).
 * Dumps `testing/build/extracted/scripts/prologue.fe8s`.
 */
class EventScriptRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private val symbols = mapOf(
        0x089EECBC to "EventScr_Prologue_RenaisThroneCutscene",
        0x088B3C14 to "UnitDef_Event_PrologueAlly",
        0x088B3D18 to "UnitDef_Event_PrologueThroneRoomUnits",
        0x088B3DCC to "UnitDef_Event_PrologueMessager",
    )

    private fun convert(address: Int): EventScriptConverter.Result {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return EventScriptConverter.convert(romFile.readBytes(), address - 0x08000000, symbols)
    }

    @Test
    fun `command table has the core commands`() {
        assertEquals("return", Fe8EventCommands.byHeader.getValue(0x0120).name)
        assertEquals("text_show", Fe8EventCommands.byHeader.getValue(0x1B20).name)
        assertTrue(Fe8EventCommands.byHeader.size > 150)
    }

    private fun layoutOf(header: Int) = Fe8EventCommands.byHeader.getValue(header).layout
        .map { listOf(it.name, it.kind, it.word, it.shift) }

    @Test
    fun `command layouts follow the macro bodies`() {
        // move_unit: _EvtArg0(.., speed), _EvtParams2(pid, _EvtSubParam16u8(x, y))
        assertEquals(
            listOf(
                listOf("speed", FieldKind.U16, 1, 0), listOf("pid", FieldKind.U16, 2, 0),
                listOf("x", FieldKind.U8, 3, 0), listOf("y", FieldKind.U8, 3, 8),
            ),
            layoutOf(0x2F40),
        )
        // call: _EvtAutoCmdLen4 has an implicit zero arg0 word, then a u32 pointer.
        assertEquals(
            listOf(listOf(null, FieldKind.U16, 1, 0), listOf("scr", FieldKind.POINTER, 2, 0)),
            layoutOf(0x0A40),
        )
        assertEquals(
            listOf(
                listOf("pid", FieldKind.U16, 1, 0), listOf("x", FieldKind.U8, 2, 0),
                listOf("y", FieldKind.U8, 2, 8), listOf("w", FieldKind.U8, 3, 0),
                listOf("h", FieldKind.U8, 3, 8),
            ),
            layoutOf(0x3640),
        )
        assertEquals(
            listOf(
                listOf("to", FieldKind.U4, 1, 0), listOf("a", FieldKind.U4, 1, 4),
                listOf("b", FieldKind.U4, 1, 8),
            ),
            layoutOf(0x0620),
        )
        // Commands without a decomp macro, from the handlers in src/eventscr.c.
        for (h in listOf(0x1F20, 0x2020, 0x2320, 0x2420, 0x2820, 0x3540)) {
            assertTrue(Fe8EventCommands.byHeader.containsKey(h), "0x%X".format(h))
        }
    }

    @Test
    fun `field read decodes bytes nibbles and pointers`() {
        // 0x3640 check_in_area: pid=0x0102, x=3, y=4, w=5, h=6
        val rom = byteArrayOf(0x40, 0x36, 0x02, 0x01, 3, 4, 5, 6)
        val got = Fe8EventCommands.byHeader.getValue(0x3640).layout.map { it.read(rom, 0) }
        assertEquals(listOf(0x0102, 3, 4, 5, 6), got)
        val call = byteArrayOf(0x40, 0x0A, 0, 0, 0x78, 0x56, 0x34, 0x08)
        assertEquals(0x08345678, Fe8EventCommands.byHeader.getValue(0x0A40).layout[1].read(call, 0))
    }

    @Test
    fun `every command of the prologue world map script has a known header`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val start = 0x08A39768 - 0x08000000
        val end = start + 0x598
        var p = start
        val unknown = ArrayList<String>()
        while (p < end) {
            val h = (rom[p].toInt() and 0xFF) or ((rom[p + 1].toInt() and 0xFF) shl 8)
            val len = (h shr 4) and 0xF
            assertTrue(len > 0, "zero length at 0x%X".format(p))
            val c = Fe8EventCommands.find(h)
            if (c == null) {
                unknown += "0x%04X@0x%X".format(h, p)
            } else {
                val words = 1 + c.layout.maxOf { it.word + if (it.kind.bits == 32) 1 else 0 }
                assertEquals(len, words, c.name)
            }
            p += 2 * len
        }
        assertEquals(end, p, "script must end exactly at the symbol size")
        assertTrue(unknown.isEmpty(), unknown.toString())
    }

    @Test
    fun `prologue beginning scene matches prologue-eventscript_h`() {
        val r = convert(0x089EEBE8)
        assertEquals(0xD4, r.byteLength, r.lines.joinToString("\n"))
        assertTrue(r.lines.none { it.startsWith("raw ") }, r.lines.joinToString("\n"))
        // CALL(EventScr_Prologue_RenaisThroneCutscene) first; LOAD1(1, UnitDef_Event_PrologueAlly)
        // after LABEL(0); ends ENDA.
        assertTrue(
            r.lines.first().endsWith(" EventScr_Prologue_RenaisThroneCutscene"),
            r.lines.first(),
        )
        val label = r.lines.indexOf("label 0")
        assertTrue(label > 0)
        assertTrue(
            r.lines[label + 2].endsWith(" 1 UnitDef_Event_PrologueAlly"),
            r.lines[label + 2],
        )
        assertEquals("return", r.lines.last())
    }

    /**
     * Every `EventScr_Prologue*` (address to nm size): conversion must consume exactly the symbol,
     * no raw commands.
     */
    private val prologueScripts = listOf(
        0x89EEBE8 to 0x00000D4, // EventScr_Prologue_BeginningScene
        0x89EECBC to 0x00003A4, // EventScr_Prologue_RenaisThroneCutscene
        0x89EF060 to 0x0000044, // EventScr_Prologue_GiveRapier
        0x89EF0A4 to 0x0000034, // EventScr_Prologue_ONeillSpawn
        0x89EF0D8 to 0x0000054, // EventScr_Prologue_OneEnemyLeft
        0x89EF12C to 0x0000038, // EventScr_Prologue_ONeillAttack
        0x89EF164 to 0x0000058, // EventScr_Prologue_EndingScene
        0x89EF1BC to 0x0000058, // EventScr_Prologue_EirikaAttacked
        0x89EF214 to 0x0000028, // EventScr_Prologue_Turn1
        0x89EF23C to 0x0000018, // EventScr_Prologue_Turn2
        0x89EF254 to 0x0000028, // EventScr_Prologue_Turn3
        0x89EF27C to 0x0000034, // EventScr_Prologue_ExecTut
        0x89EF2B0 to 0x0000078, // EventScr_Prologue_Tutorial0
        0x89EF328 to 0x0000060, // EventScr_Prologue_Tutorial1
        0x89EF388 to 0x0000010, // EventScr_Prologue_Tutorial2
        0x89EF398 to 0x000001C, // EventScr_Prologue_Tutorial3
        0x89EF3B4 to 0x0000024, // EventScr_Prologue_TutMessageTurn1
        0x89EF3D8 to 0x0000094, // EventScr_Prologue_TutMessageTurn2
        0x89EF46C to 0x0000080, // EventScr_Prologue_Tutorial4
        0x89EF4EC to 0x0000020, // EventScr_Prologue_Tutorial5
        0x89EF50C to 0x0000030, // EventScr_Prologue_Tutorial6
        0x89EF53C to 0x0000030, // EventScr_Prologue_Tutorial7
        0x89EF56C to 0x000003C, // EventScr_Prologue_Tutorial8
        0x89EF5A8 to 0x0000068, // EventScr_Prologue_Tutorial9
        0x89EF610 to 0x0000060, // EventScr_Prologue_OneillSethBattle
        0x89EF670 to 0x0000070, // EventScr_Prologue_TutEirikaAttack
        0x89EF6E0 to 0x0000078, // EventScr_Prologue_TutorialA
        0x89EF758 to 0x0000060, // EventScr_Prologue_TutorialB
        0x89EF7B8 to 0x0000010, // EventScr_Prologue_TutorialC
        0x89EF7C8 to 0x0000034, // EventScr_Prologue_TutorialD
        0x89EF7FC to 0x000002C, // EventScr_Prologue_TutorialE
        0x89EF828 to 0x0000020, // EventScr_Prologue_9EF828
    )

    @Test
    fun `every prologue script converts to its exact symbol size`() {
        for ((address, size) in prologueScripts) {
            val r = convert(address)
            assertEquals(size, r.byteLength, "0x%X".format(address))
            assertTrue(
                r.lines.none { it.startsWith("raw ") },
                "0x%X: %s".format(address, r.lines.filter { it.startsWith("raw ") }),
            )
        }
    }

    @Test
    fun `dump prologue fe8s`() {
        val scripts = listOf(
            "EventScr_Prologue_BeginningScene" to convert(0x089EEBE8),
            "EventScr_Prologue_RenaisThroneCutscene" to convert(0x089EECBC),
        )
        assertEquals(0x3A4, scripts[1].second.byteLength)
        val out = File(
            System.getProperty("fe8.extractedOut") ?: "build/extracted",
            "scripts/prologue.fe8s",
        )
        out.parentFile.mkdirs()
        val text = EventScriptConverter.render(scripts)
        // Grammar draft (docs/decisions/0001): comments, `event <name>`, 4-space-indented
        // `<command> <arg>*` with args = decimal, 0x-hex or identifier.
        val arg = "(?:\\d+|0x[0-9A-F]+|[A-Za-z_][A-Za-z0-9_]*)"
        val grammar = Regex("^(?:|#.*|event [A-Za-z_][A-Za-z0-9_]*|    [a-z][a-z0-9_]*(?: $arg)*)$")
        assertTrue(
            text.lines().all { grammar.matches(it) },
            text.lines().firstOrNull { !grammar.matches(it) },
        )
        out.writeText(text)
    }
}
