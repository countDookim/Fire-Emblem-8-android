package gbafe.extract.fe8.events

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Unit tests for [EventScriptConverter] symbolic formatting of FE8 event commands. */
class EventScriptConverterTest {
    private fun le16(vararg words: Int): ByteArray = ByteArray(words.size * 2) { i ->
        val w = words[i / 2]
        if (i % 2 == 0) (w and 0xFF).toByte() else ((w ushr 8) and 0xFF).toByte()
    }

    @Test
    fun `override_weapon_menu uses menu2 WEAPON1-5 bits and override_unit_menu uses menu1`() {
        val bytes = le16(
            0x3D20, 0x0002, // override_unit_menu: bit 1 = STAFF
            0x3D20, 0x6000, // override_unit_menu: bits 13|14 = OPTIONS|END
            0x3D20, 0x8000, // override_unit_menu: unmapped bit 15 stays hex
            0x3D21, 0x0002, // override_weapon_menu: bit 1 = WEAPON2 (not STAFF)
            0x3D21, 0x0005, // override_weapon_menu: bits 0|2 = WEAPON1|WEAPON3
            0x3D21, 0x001F, // override_weapon_menu: WEAPON1..5
            0x3D21, 0x0000, // override_weapon_menu: zero
            0x3D21, 0x0020, // override_weapon_menu: unmapped bit 5 stays numeric
        )
        val result = EventScriptConverter.convert(bytes, 0, bytes.size)
        assertEquals(
            listOf(
                "override_unit_menu EVENT_MENUOVERRIDE_STAFF",
                "override_unit_menu EVENT_MENUOVERRIDE_OPTIONS|EVENT_MENUOVERRIDE_END",
                "override_unit_menu 0x8000",
                "override_weapon_menu EVENT_MENUOVERRIDE_WEAPON2",
                "override_weapon_menu EVENT_MENUOVERRIDE_WEAPON1|EVENT_MENUOVERRIDE_WEAPON3",
                "override_weapon_menu EVENT_MENUOVERRIDE_WEAPON1|EVENT_MENUOVERRIDE_WEAPON2|" +
                    "EVENT_MENUOVERRIDE_WEAPON3|EVENT_MENUOVERRIDE_WEAPON4|" +
                    "EVENT_MENUOVERRIDE_WEAPON5",
                "override_weapon_menu 0",
                "override_weapon_menu 32",
            ),
            result.lines,
        )
    }

    @Test
    fun `change_class types jid as class and swap_class types jid as character`() {
        val charKind = Fe8ScriptConstants.KIND_CHARACTER
        val eirika = Fe8ScriptConstants.valueOf(charKind, "CHARACTER_EIRIKA")
        val seth = Fe8ScriptConstants.valueOf(charKind, "CHARACTER_SETH")
        val moulder = Fe8ScriptConstants.valueOf(charKind, "CHARACTER_MOULDER")
        val cavalier = Fe8ScriptConstants.valueOf(Fe8ScriptConstants.KIND_CLASS, "CLASS_CAVALIER")
        assertEquals(cavalier, moulder) // same numeric id (5): verifies field kind is distinct
        val bytes = le16(
            0x3540, eirika, cavalier, seth, // change_class: unit, class, character
            0x3541, eirika, moulder, 0, // swap_class: unit, character, unused (0 -> CHARACTER_NONE)
        )
        val result = EventScriptConverter.convert(bytes, 0, bytes.size)
        assertEquals(
            listOf(
                "change_class CHARACTER_EIRIKA CLASS_CAVALIER CHARACTER_SETH",
                "swap_class CHARACTER_EIRIKA CHARACTER_MOULDER CHARACTER_NONE",
            ),
            result.lines,
        )
    }
}
