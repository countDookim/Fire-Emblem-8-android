package gba.core.sram

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileSaveStorageTest {
    @TempDir
    lateinit var dir: File

    private val image = ByteArray(CartSram.SIZE) { (it * 7 + 3).toByte() }

    @Test
    fun `delete removes the save and keeps the rom`() {
        val rom = File(dir, "base/rom/baserom.gba").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val storage = FileSaveStorage(File(dir, "fe8.sav"))
        storage.store(image)
        val dest = ByteArray(CartSram.SIZE)
        assertTrue(storage.load(dest))
        assertArrayEquals(image, dest)

        assertTrue(storage.delete())

        assertFalse(storage.file.exists())
        assertFalse(storage.load(ByteArray(CartSram.SIZE)))
        assertTrue(rom.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), rom.readBytes())
    }

    @Test
    fun `delete without a save is a no-op`() {
        val storage = FileSaveStorage(File(dir, "fe8.sav"))
        assertFalse(storage.delete())
        assertFalse(storage.file.exists())
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `delete also removes a stale tmp file`() {
        val storage = FileSaveStorage(File(dir, "fe8.sav"))
        val tmp = File(dir, "fe8.sav.tmp").apply { writeBytes(byteArrayOf(9)) }
        assertFalse(storage.delete())
        assertFalse(tmp.exists())
    }
}
