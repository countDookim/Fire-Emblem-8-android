package gba.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.zip.CRC32
import kotlin.random.Random

class Crc32Test {
    private fun jdk(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): Long =
        CRC32().also { it.update(bytes, off, len) }.value

    @Test
    fun emptyInput() {
        assertEquals(0L, Crc32().value)
        assertEquals(jdk(ByteArray(0)), Crc32().value)
    }

    @Test
    fun checkValue() {
        val crc = Crc32()
        crc.update("123456789".encodeToByteArray())
        assertEquals(0xCBF43926L, crc.value)
    }

    @Test
    fun byteByByteMatchesArray() {
        val data = Random(1).nextBytes(500)
        val a = Crc32()
        for (b in data) a.update(b.toInt())
        val b = Crc32().also { it.update(data) }
        assertEquals(b.value, a.value)
        assertEquals(jdk(data), a.value)
    }

    @Test
    fun updateUsesLow8Bits() {
        val a = Crc32().also { it.update(0x1AB) }
        assertEquals(CRC32().also { it.update(0xAB) }.value, a.value)
    }

    @Test
    fun slices() {
        val data = Random(2).nextBytes(300)
        val crc = Crc32().also { it.update(data, 17, 100) }
        assertEquals(jdk(data, 17, 100), crc.value)
        val tail = Crc32().also { it.update(data, 250) }
        assertEquals(jdk(data, 250), tail.value)
    }

    @Test
    fun randomArraysAndReset() {
        val crc = Crc32()
        for (seed in 10..15) {
            val data = Random(seed).nextBytes(Random(seed).nextInt(0, 4096))
            crc.reset()
            crc.update(data)
            assertEquals(jdk(data), crc.value)
        }
    }
}
