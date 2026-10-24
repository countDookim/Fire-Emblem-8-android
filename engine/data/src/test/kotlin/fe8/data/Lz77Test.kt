package fe8.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Lz77Test {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `literals only`() {
        val r = Lz77.decode(bytes(0x10, 3, 0, 0, 0x00, 'a'.code, 'b'.code, 'c'.code))
        assertArrayEquals("abc".toByteArray(), r.data)
        assertEquals(8, r.consumed)
    }

    @Test
    fun `overlapping back-reference repeats a run`() {
        // "ab" then ref(len=3+3=6, dist=2) -> "abababab"; flags 0b0010_0000.
        val r = Lz77.decode(bytes(0x10, 8, 0, 0, 0x20, 'a'.code, 'b'.code, 0x30, 0x01))
        assertArrayEquals("abababab".toByteArray(), r.data)
        assertEquals(9, r.consumed)
    }

    @Test
    fun `stops at header size mid-reference`() {
        val r = Lz77.decode(bytes(0x10, 4, 0, 0, 0x40, 'x'.code, 0xF0, 0x00))
        assertArrayEquals("xxxx".toByteArray(), r.data)
    }

    @Test
    fun `rejects bad streams`() {
        assertThrows<IllegalArgumentException> { Lz77.decode(bytes(0x11, 1, 0, 0, 0)) }
        assertThrows<IllegalArgumentException> { Lz77.decode(bytes(0x10, 4, 0, 0, 0x80, 0x00, 0x00)) }
        assertThrows<IllegalArgumentException> { Lz77.decode(bytes(0x10, 4, 0, 0, 0x00, 1)) }
    }
}
