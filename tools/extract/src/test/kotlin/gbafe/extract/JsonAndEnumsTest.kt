package gbafe.extract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonAndEnumsTest {
    @Test
    fun `json writer is deterministic and escapes strings`() {
        val doc = linkedMapOf("a" to 1, "b" to listOf(1, 2), "c" to linkedMapOf("x" to "q\"\n"), "d" to null, "e" to emptyMap<String, Int>())
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": [1, 2],\n  \"c\": { \"x\": \"q\\\"\\n\" },\n  \"d\": null,\n  \"e\": {}\n}\n",
            Json.write(doc),
        )
    }

    @Test
    fun `enum parser reads hex and decimal and skips comments`() {
        val h = """
            enum {
                ITEM_NONE = 0x00,
                // ITEM_FAKE = 0x05,
                ITEM_SWORD_IRON = 0x01, /* ITEM_X = 9, */
                CLASS_OTHER = 3,
                ITEM_TEN = 10,
            };
        """
        assertEquals(mapOf(0 to "ITEM_NONE", 1 to "ITEM_SWORD_IRON", 10 to "ITEM_TEN"), DecompEnums.parse(h, "ITEM_"))
    }
}

class SymbolsTest {
    private val s = Symbols(
        listOf("08000000 00000010 T a", "08000010 T b", "08000020 00000004 T c", "08000020 00000004 T c", "0800002c T d", "0800002c T d2"),
    )

    @Test
    fun `size from nm or span to next symbol`() {
        assertEquals(0x10, s.sizeOrSpan("a"))
        assertEquals(0x10, s.sizeOrSpan("b"))
        assertEquals(0x08000020, s["c"].address)
    }
}
