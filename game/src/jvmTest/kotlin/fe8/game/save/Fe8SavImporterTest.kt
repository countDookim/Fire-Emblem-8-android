package fe8.game.save

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Error paths of [Fe8SavImporter] on synthetic bytes. */
class Fe8SavImporterTest {
    @Test
    fun `too short input raises TooShort`() {
        val e = assertThrows(Fe8SavException.TooShort::class.java) {
            Fe8SavImporter.import(ByteArray(0x7FFF))
        }
        assertEquals(0x7FFF, e.size)
    }

    @Test
    fun `old magic raises UnsupportedMagic`() {
        val b = ByteArray(0x8000) { 0xFF.toByte() }
        b[8] = 0x03
        b[9] = 0x06
        b[10] = 0x04
        b[11] = 0x00
        val e = assertThrows(Fe8SavException.UnsupportedMagic::class.java) {
            Fe8SavImporter.import(b)
        }
        assertEquals(0x40603, e.magic)
    }

    @Test
    fun `blank sram raises CorruptedHeader`() {
        assertThrows(Fe8SavException.CorruptedHeader::class.java) {
            Fe8SavImporter.import(ByteArray(0x8000) { 0xFF.toByte() })
        }
    }
}
