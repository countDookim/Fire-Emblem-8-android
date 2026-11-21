package fe8.game.event

import gbafe.script.ScriptAssemblyException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Unit tests for [Fe8ConstantSymbols], [Fe8CommandSet], and [Fe8CommandEncoder]. */
class Fe8ScriptSymbolsTest {
    private val tools = Fe8ScriptTools(NmSymbolIndex(""))

    private fun assembleWords(body: String): IntArray {
        val text = "event Test\n$body\n"
        val image = tools.assembler.assemble(tools.parser.parseOrThrow("test.fe8s", text))
        val entry = image.find("Test")!!
        val count = image.bytesOf(entry).size / 2
        return IntArray(count) { i -> image.words[i] and 0xFFFF }
    }

    @Test
    fun `override_weapon_menu and override_unit_menu resolve only their own menu bit tables`() {
        assertArrayEquals(
            intArrayOf(
                0x3D20, 0x0002,
                0x3D21, 0x0002,
                0x3D21, 0x0005,
            ),
            assembleWords(
                """
                    override_unit_menu EVENT_MENUOVERRIDE_STAFF
                    override_weapon_menu EVENT_MENUOVERRIDE_WEAPON2
                    override_weapon_menu EVENT_MENUOVERRIDE_WEAPON1|EVENT_MENUOVERRIDE_WEAPON3
                """.trimIndent(),
            ),
        )
        assertThrows(ScriptAssemblyException::class.java) {
            assembleWords("    override_weapon_menu EVENT_MENUOVERRIDE_STAFF")
        }
        assertThrows(ScriptAssemblyException::class.java) {
            assembleWords("    override_unit_menu EVENT_MENUOVERRIDE_WEAPON2")
        }
    }

    @Test
    fun `change_class and swap_class type jid according to Event35_UnitClassChanging`() {
        assertArrayEquals(
            intArrayOf(
                0x3540, 1, 5, 2,
                0x3541, 1, 5, 0,
            ),
            assembleWords(
                """
                    change_class CHARACTER_EIRIKA CLASS_CAVALIER CHARACTER_SETH
                    swap_class CHARACTER_EIRIKA CHARACTER_MOULDER CHARACTER_NONE
                """.trimIndent(),
            ),
        )
        assertThrows(ScriptAssemblyException::class.java) {
            assembleWords("    swap_class CHARACTER_EIRIKA CLASS_CAVALIER CHARACTER_NONE")
        }
        assertThrows(ScriptAssemblyException::class.java) {
            assembleWords("    change_class CHARACTER_EIRIKA CHARACTER_MOULDER CHARACTER_SETH")
        }
    }
}
