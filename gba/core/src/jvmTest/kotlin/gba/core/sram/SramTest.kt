package gba.core.sram

import gba.testkit.AllocationMeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SramBitsTest {
    @Test
    fun `little endian u16 and u32`() {
        val b = ByteArray(8)
        SramBits.putU32(b, 1, 0x89ABCDEF.toInt())
        assertEquals(0xEF, b[1].toInt() and 0xFF)
        assertEquals(0x89, b[4].toInt() and 0xFF)
        assertEquals(0x89ABCDEF.toInt(), SramBits.getU32(b, 1))
        SramBits.putU16(b, 6, 0x1FFFF)
        assertEquals(0xFFFF, SramBits.getU16(b, 6))
        assertEquals(0x89AB, SramBits.getU16(b, 3))
    }

    @Test
    fun `bitfields are lsb first within a byte`() {
        val b = ByteArray(4)
        SramBits.putBits(b, 0, 0, 3, 0b101)
        SramBits.putBits(b, 0, 3, 5, 0b11010)
        assertEquals(0b11010101, b[0].toInt() and 0xFF)
        assertEquals(0b101, SramBits.getBits(b, 0, 0, 3))
        assertEquals(0b11010, SramBits.getBits(b, 0, 3, 5))
    }

    @Test
    fun `bitfield across a byte boundary`() {
        val b = ByteArray(4)
        SramBits.putBits(b, 0, 6, 6, 0b101101)
        assertEquals(0b01000000, b[0].toInt() and 0xFF)
        assertEquals(0b00001011, b[1].toInt() and 0xFF)
        assertEquals(0b101101, SramBits.getBits(b, 0, 6, 6))
    }

    @Test
    fun `bitfield across a u32 boundary`() {
        val b = ByteArray(8)
        SramBits.putBits(b, 0, 28, 8, 0xA5)
        assertEquals(0x5, SramBits.getU32(b, 0) ushr 28)
        assertEquals(0xA, SramBits.getU32(b, 4) and 0xF)
        assertEquals(0xA5, SramBits.getBits(b, 0, 28, 8))
    }

    @Test
    fun `put keeps neighbouring bits and masks the value`() {
        val b = ByteArray(4) { 0xFF.toByte() }
        SramBits.putBits(b, 0, 5, 7, 0)
        assertEquals(0b00011111, b[0].toInt() and 0xFF)
        assertEquals(0b11110000, b[1].toInt() and 0xFF)
        assertEquals(0xFF, b[2].toInt() and 0xFF)
        SramBits.putBits(b, 0, 5, 7, -1) // only the low 7 bits are stored
        assertEquals(0xFF, b[0].toInt() and 0xFF)
        assertEquals(0xFF, b[1].toInt() and 0xFF)
    }

    @Test
    fun `random round trips for every width and offset`() {
        val rnd = Random(1234)
        val b = ByteArray(16)
        for (width in 1..32) {
            for (off in 0..40) {
                rnd.nextBytes(b)
                val before = b.copyOf()
                val v = rnd.nextInt()
                SramBits.putBits(b, 2, off, width, v)
                val mask = if (width == 32) -1 else (1 shl width) - 1
                assertEquals(v and mask, SramBits.getBits(b, 2, off, width) and mask)
                // bits outside the field are untouched
                for (k in 0 until 14 * 8) {
                    if (k in off until off + width) continue
                    val bi = 2 + k / 8
                    val before1 = (before[bi].toInt() shr (k % 8)) and 1
                    val after1 = (b[bi].toInt() shr (k % 8)) and 1
                    assertEquals(before1, after1, "bit $k width=$width off=$off")
                }
            }
        }
    }

    @Test
    fun `signed fields sign extend`() {
        val b = ByteArray(2)
        SramBits.putBits(b, 0, 4, 4, 0xF)
        assertEquals(15, SramBits.getBits(b, 0, 4, 4))
        assertEquals(-1, SramBits.getBitsSigned(b, 0, 4, 4))
        SramBits.putBits(b, 0, 4, 4, 0x7)
        assertEquals(7, SramBits.getBitsSigned(b, 0, 4, 4))
    }

    @Test
    fun `bit helpers allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val b = ByteArray(16)
        var sum = 0
        val bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 10_000) {
            SramBits.putBits(b, 0, 13, 17, sum)
            sum += SramBits.getBits(b, 0, 13, 17) + SramBits.getBitsSigned(b, 0, 3, 5)
            SramBits.putU32(b, 4, sum)
            sum += SramBits.getU32(b, 4) + SramBits.getU16(b, 5)
        }
        assertEquals(0L, bytes)
    }
}

class CartSramTest {
    @Test
    fun `blank sram reads ff and starts clean`() {
        val s = CartSram()
        assertEquals(0x8000, CartSram.SIZE)
        assertEquals(0xFF, s.readByte(0))
        assertEquals(0xFF, s.readByte(CartSram.SIZE - 1))
        assertFalse(s.dirty)
    }

    @Test
    fun `write then read round trips and marks dirty`() {
        val s = CartSram()
        val src = byteArrayOf(1, 2, 3, 4, 5)
        writeSramFast(src, 1, s, 0x100, 3)
        assertTrue(s.dirty)
        assertEquals(2, s.readByte(0x100))
        assertEquals(4, s.readByte(0x102))
        assertEquals(0xFF, s.readByte(0x103))
        val out = ByteArray(5)
        readSramFast(s, 0x100, out, 2, 3)
        assertTrue(byteArrayOf(0, 0, 2, 3, 4).contentEquals(out))
        s.clearDirty()
        assertFalse(s.dirty)
        writeSramFast(src, 0, s, 0, 0) // size 0: no-op like `while (--size != -1)`
        assertFalse(s.dirty)
    }

    @Test
    fun `verify returns zero or the bus address of the first mismatch`() {
        val s = CartSram()
        val src = ByteArray(8) { it.toByte() }
        writeSramFast(src, 0, s, 0x20, 8)
        assertEquals(0, verifySramFast(src, 0, s, 0x20, 8))
        s.writeByte(0x23, 0x99)
        s.writeByte(0x25, 0x99)
        assertEquals(CartSram.BASE_ADDRESS + 0x23, verifySramFast(src, 0, s, 0x20, 8))
        assertEquals(0, verifySramFast(src, 0, s, 0x20, 3)) // mismatch lies beyond the range
        assertEquals(0, verifySramFast(src, 0, s, 0, 0))
    }

    @Test
    fun `write and verify returns zero and leaves the data in place`() {
        val s = CartSram()
        val src = ByteArray(100) { (it * 7).toByte() }
        assertEquals(0, writeAndVerifySramFast(src, 0, s, 0, 100))
        val out = ByteArray(100)
        readSramFast(s, 0, out, 0, 100)
        assertTrue(src.contentEquals(out))
        assertEquals(3, SRAM_WRITE_ATTEMPTS)
    }

    @Test
    fun `out of range access throws`() {
        val s = CartSram()
        val b = ByteArray(4)
        assertThrows(IllegalArgumentException::class.java) {
            writeSramFast(b, 0, s, CartSram.SIZE - 3, 4)
        }
        assertThrows(IllegalArgumentException::class.java) { readSramFast(s, -1, b, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { s.readByte(CartSram.SIZE) }
        writeSramFast(b, 0, s, CartSram.SIZE - 4, 4) // exactly the last bytes is fine
    }

    @Test
    fun `image copy in and out`() {
        val s = CartSram()
        val img = ByteArray(CartSram.SIZE) { it.toByte() }
        s.copyIn(img)
        assertFalse(s.dirty)
        assertEquals(1, s.readByte(1))
        val out = ByteArray(CartSram.SIZE)
        s.copyOut(out)
        assertTrue(img.contentEquals(out))
        s.erase()
        assertTrue(s.dirty)
        assertEquals(0xFF, s.readByte(1))
    }

    @Test
    fun `read write verify allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val s = CartSram()
        val src = ByteArray(256) { it.toByte() }
        val dst = ByteArray(256)
        var bad = 0
        val bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 10_000) {
            writeSramFast(src, 0, s, 0x40, 256)
            readSramFast(s, 0x40, dst, 0, 256)
            bad += verifySramFast(src, 0, s, 0x40, 256)
            bad += writeAndVerifySramFast(src, 0, s, 0x200, 128)
            s.writeByte(3, bad)
        }
        assertEquals(0L, bytes)
        assertEquals(0, bad)
    }
}
