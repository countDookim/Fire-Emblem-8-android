package gba.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ByteSinkTest {
    @Test
    fun emptySink() {
        val sink = ByteSink()
        assertEquals(0, sink.size)
        assertArrayEquals(ByteArray(0), sink.toByteArray())
    }

    @Test
    fun writeIntKeepsLow8Bits() {
        val sink = ByteSink()
        val jdk = ByteArrayOutputStream()
        for (v in intArrayOf(0, 1, 0x7F, 0x80, 0xFF, 0x100, 0x1234, -1, -129, Int.MIN_VALUE, Int.MAX_VALUE)) {
            sink.write(v)
            jdk.write(v)
        }
        assertArrayEquals(jdk.toByteArray(), sink.toByteArray())
    }

    @Test
    fun writeRangeHonoursOffsetAndLength() {
        val src = ByteArray(10) { (it + 1).toByte() }
        val sink = ByteSink()
        sink.write(src, 3, 4)
        sink.write(src, 8)
        sink.write(src)
        sink.write(src, 5, 0)
        val jdk = ByteArrayOutputStream()
        jdk.write(src, 3, 4)
        jdk.write(src, 8, 2)
        jdk.write(src, 0, src.size)
        assertArrayEquals(jdk.toByteArray(), sink.toByteArray())
        assertEquals(jdk.size(), sink.size)
    }

    @Test
    fun writeIntIsBigEndian() {
        val sink = ByteSink()
        sink.writeInt(0x01020304)
        sink.writeInt(-2)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte()),
            sink.toByteArray(),
        )
    }

    @Test
    fun toByteArrayIsACopy() {
        val sink = ByteSink()
        sink.write(7)
        val first = sink.toByteArray()
        first[0] = 0
        sink.write(8)
        assertArrayEquals(byteArrayOf(7, 8), sink.toByteArray())
    }

    @Test
    fun mixedSequencesMatchByteArrayOutputStreamAcrossGrowth() {
        for (capacity in intArrayOf(0, 1, 16, 17, 256)) {
            val rnd = Random(capacity)
            val sink = ByteSink(capacity)
            val jdk = ByteArrayOutputStream()
            repeat(2000) {
                when (rnd.nextInt(3)) {
                    0 -> {
                        val v = rnd.nextInt()
                        sink.write(v)
                        jdk.write(v)
                    }
                    1 -> {
                        // Chunks up to several times the capacity force the "need > 2 * size" growth branch.
                        val bytes = rnd.nextBytes(rnd.nextInt(0, 700))
                        val off = if (bytes.isEmpty()) 0 else rnd.nextInt(bytes.size)
                        val len = rnd.nextInt(0, bytes.size - off + 1)
                        sink.write(bytes, off, len)
                        jdk.write(bytes, off, len)
                    }
                    else -> {
                        val v = rnd.nextInt()
                        sink.writeInt(v)
                        jdk.write(v ushr 24)
                        jdk.write(v ushr 16)
                        jdk.write(v ushr 8)
                        jdk.write(v)
                    }
                }
                assertEquals(jdk.size(), sink.size)
            }
            assertArrayEquals(jdk.toByteArray(), sink.toByteArray(), "capacity $capacity")
        }
    }
}
