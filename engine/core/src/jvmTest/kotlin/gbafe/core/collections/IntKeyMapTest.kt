package gbafe.core.collections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntKeyMapTest {
    @Test
    fun `missing key is null and not contained`() {
        val map = IntKeyMap<String>()
        assertNull(map[5])
        assertFalse(map.containsKey(5))
        assertFalse(5 in map)
        assertEquals(0, map.size)
        assertThrows(NoSuchElementException::class.java) {
            map.getValue(5)
        }
    }

    @Test
    fun `insert get and overwrite`() {
        val map = IntKeyMap<String>()
        map[0] = "zero"
        map[-1] = "minus"
        map[0x0801F5A1] = "addr"
        assertEquals("zero", map[0])
        assertEquals("minus", map[-1])
        assertEquals("addr", map[0x0801F5A1])
        map[0] = "again"
        assertEquals("again", map[0])
        assertEquals("again", map.getValue(0))
        assertEquals(3, map.size)
        assertTrue(0 in map)
    }

    @Test
    fun `growth keeps every entry`() {
        val map = IntKeyMap<Int>()
        for (i in 0 until 5000) map[i * 4 + 0x08000001] = i
        assertEquals(5000, map.size)
        for (i in 0 until 5000) assertEquals(i, map[i * 4 + 0x08000001])
        assertNull(map[0x08000002])
    }

    @Test
    fun `colliding keys stay distinct`() {
        val map = IntKeyMap<Int>()
        // Multiples of a large power of two share their low bits, the worst case for a mask.
        for (i in 0 until 200) map[i shl 16] = i
        for (i in 0 until 200) assertEquals(i, map[i shl 16])
        assertNull(map[201 shl 16])
        assertEquals(200, map.size)
    }
}
