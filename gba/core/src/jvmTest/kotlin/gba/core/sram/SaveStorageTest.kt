package gba.core.sram

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SaveStorageTest {
    @TempDir
    lateinit var dir: File

    private fun pattern(seed: Int) = ByteArray(CartSram.SIZE) { (it * 31 + 7 + seed).toByte() }

    private fun sramWith(image: ByteArray) = CartSram().also {
        it.write(0, image, 0, image.size)
    }

    @Test
    fun `file round trip is byte exact`() {
        val file = File(dir, "sub/fe8.sav")
        val image = pattern(0)
        val sram = sramWith(image)
        assertTrue(sram.flushTo(FileSaveStorage(file)))
        assertFalse(sram.dirty)
        assertFalse(sram.flushTo(FileSaveStorage(file)))

        val restored = CartSram()
        assertTrue(restored.loadFrom(FileSaveStorage(file)))
        assertFalse(restored.dirty)
        val out = ByteArray(CartSram.SIZE)
        restored.copyOut(out)
        assertArrayEquals(image, out)
    }

    @Test
    fun `crash before rename keeps previous save and stays dirty`() {
        val file = File(dir, "fe8.sav")
        val a = pattern(0)
        sramWith(a).flushTo(FileSaveStorage(file))

        val failing = FileSaveStorage(file) { throw RuntimeException("crash") }
        val sram = sramWith(pattern(5))
        val e = assertThrows(RuntimeException::class.java) { sram.flushTo(failing) }
        assertEquals("crash", e.message)
        assertTrue(sram.dirty)
        assertArrayEquals(a, file.readBytes())

        val reopened = CartSram()
        assertTrue(reopened.loadFrom(FileSaveStorage(file)))
        val out = ByteArray(CartSram.SIZE)
        reopened.copyOut(out)
        assertArrayEquals(a, out)

        // The stale .tmp is overwritten by the next successful store.
        assertTrue(sram.flushTo(FileSaveStorage(file)))
        assertArrayEquals(pattern(5), file.readBytes())
    }

    @Test
    fun `missing or wrong length file does not load`() {
        val file = File(dir, "fe8.sav")
        val sram = CartSram()
        assertFalse(sram.loadFrom(FileSaveStorage(file)))
        assertEquals(0xFF, sram.readByte(0))
        assertEquals(0xFF, sram.readByte(CartSram.SIZE - 1))

        file.writeBytes(ByteArray(100))
        assertFalse(sram.loadFrom(FileSaveStorage(file)))
        file.writeBytes(ByteArray(CartSram.SIZE + 1))
        assertFalse(sram.loadFrom(FileSaveStorage(file)))
        assertEquals(0xFF, sram.readByte(0))
    }

    @Test
    fun `in memory round trip`() {
        val storage = InMemorySaveStorage()
        assertFalse(CartSram().loadFrom(storage))
        val image = pattern(3)
        assertTrue(sramWith(image).flushTo(storage))
        val restored = CartSram()
        assertTrue(restored.loadFrom(storage))
        val out = ByteArray(CartSram.SIZE)
        restored.copyOut(out)
        assertArrayEquals(image, out)
    }
}
