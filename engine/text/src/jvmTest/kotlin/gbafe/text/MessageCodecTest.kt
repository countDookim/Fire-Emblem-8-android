package gbafe.text

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MessageCodecTest {
    private val codec = MessageCodec(faces = mapOf("FID_Eirika" to 0x0102, "FID_Active" to GbaFeTextCodes.FACE_ACTIVE))

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun roundTrip(raw: ByteArray, text: String) {
        assertEquals(text, codec.decode(raw))
        assertArrayEquals(raw, codec.encode(text))
    }

    @Test
    fun `plain text and single-byte codes`() =
        roundTrip(bytes('H'.code, 'i'.code, 1, 3, 0x1F, 0), "Hi{NL}{A}{.}")

    @Test
    fun `load face with named and unnamed arguments`() {
        roundTrip(bytes(0x0A, 0x10, 0x02, 0x01, 0x10, 0xFF, 0xFF, 0), "{OpenLeft}{LoadFace FID_Eirika}{LoadFace FID_Active}")
        roundTrip(bytes(0x10, 0x05, 0x01, 0), "{LoadFace 0x05 0x01}")
    }

    @Test
    fun `two-byte codes, tab and unknown bytes survive`() {
        roundTrip(bytes(0x80, 0x05, 0x81, 0x40, 0x80, 0x99, 0x13, 0x85, 0), "{G}{TAB}{0x80 0x99}{0x13}{0x85}")
    }

    @Test
    fun `braces and latin-1`() {
        roundTrip(bytes('{'.code, '}'.code, 0xE9, 0x93, 0x7F, 0), "{0x7B}{0x7D}é{LQuote}{DashedLine}")
    }

    @Test
    fun `aliases and hex encode`() {
        assertArrayEquals(bytes(1, 2, 0x80, 0x04, 0x23, 0), codec.encode("{LF}{CR}{BreakTalk}{HASH}"))
        assertEquals("{NL}{NL2}{LoadOverworldFaces}#", codec.decode(bytes(1, 2, 0x80, 0x04, 0x23, 0)))
        assertArrayEquals(bytes(0x10, 2, 1, 0), codec.encode("{LoadFace 0x02 0x01}"))
    }

    @Test
    fun `truncated multi-byte codes at the end decode as hex`() {
        roundTrip(bytes(0x10, 0x41, 0), "{LoadFace}A")
        roundTrip(bytes(0x80, 0), "{0x80}")
    }

    @Test
    fun `bad input is rejected`() {
        assertThrows<IllegalArgumentException> { codec.encode("{Nope}") }
        assertThrows<IllegalArgumentException> { codec.encode("{NL") }
        assertThrows<IllegalArgumentException> { codec.encode("a}b") }
        assertThrows<IllegalArgumentException> { codec.encode("—") }
        assertThrows<IllegalArgumentException> { codec.decode(bytes('a'.code)) }
    }
}
