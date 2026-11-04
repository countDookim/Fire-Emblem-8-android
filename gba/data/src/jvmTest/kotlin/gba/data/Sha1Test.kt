package gba.data

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class Sha1Test {
    private fun jdk(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun emptyAndAbc() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex(Sha1.digest(ByteArray(0))))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(Sha1.digest("abc".encodeToByteArray())))
        assertArrayEquals(jdk("abc".encodeToByteArray()), Sha1.digest("abc".encodeToByteArray()))
    }

    @Test
    fun fipsVectors() {
        val s56 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", hex(Sha1.digest(s56.encodeToByteArray())))
        val million = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", hex(Sha1.digest(million)))
        assertArrayEquals(jdk(million), Sha1.digest(million))
    }

    @Test
    fun lengths0to130() {
        val rnd = Random(7)
        for (n in 0..130) {
            val data = rnd.nextBytes(n)
            assertArrayEquals(jdk(data), Sha1.digest(data), "length $n")
        }
    }

    @Test
    fun randomArrays() {
        for (seed in 1..8) {
            val rnd = Random(seed)
            val data = rnd.nextBytes(rnd.nextInt(0, 200_000))
            assertArrayEquals(jdk(data), Sha1.digest(data), "seed $seed")
        }
    }

    @Test
    fun baseRom() {
        val dir = System.getenv("FE8_ASSETS")
        assumeTrue(dir != null)
        val rom = File(dir, "baserom.gba")
        assumeTrue(rom.isFile)
        val data = rom.readBytes()
        assertArrayEquals(jdk(data), Sha1.digest(data))
    }
}
