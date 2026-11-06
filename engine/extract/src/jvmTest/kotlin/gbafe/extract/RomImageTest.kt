package gbafe.extract

import gba.extract.RomCheck
import gbafe.extract.fe8.Fe8Import
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Same temp-file + rename sink as the app's `FileRomSink`. */
private class TestFileSink(private val target: File) : RomSink {
    private val tmp = File(target.parentFile, target.name + ".tmp")
    private val out = run {
        target.parentFile.mkdirs()
        FileOutputStream(tmp)
    }

    override fun write(bytes: ByteArray, off: Int, len: Int) = out.write(bytes, off, len)

    override fun commit() {
        out.fd.sync()
        out.close()
        check(tmp.renameTo(target))
    }

    override fun abort() {
        runCatching { out.close() }
        tmp.delete()
    }
}

class RomImageTest {
    private fun tempDir(): File {
        val root = File(System.getProperty("user.home"), ".cache/fe8-tmp").apply { mkdirs() }
        return Files.createTempDirectory(root.toPath(), "romimage").toFile()
    }

    private fun sha1(b: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun realRom(): ByteArray? {
        val dir = System.getenv("FE8_ASSETS") ?: return null
        return File(dir, "baserom.gba").takeIf { it.isFile }?.readBytes()
    }

    @Test
    fun `keep stores the verified image`() {
        val rom = realRom()
        assumeTrue(rom != null, "FE8_ASSETS/baserom.gba not available")
        val base = tempDir()
        try {
            val kept = File(base, RomImage.PATH)
            val check = RomImage.keep(Fe8Import.importer(), rom!!) { TestFileSink(kept) }
            assertEquals(RomCheck.Ok, check)
            assertTrue(kept.isFile)
            assertEquals(Fe8Import.ROM_SHA1, sha1(kept.readBytes()))
            assertArrayEquals(rom, kept.readBytes())
            assertEquals(listOf("baserom.gba"), kept.parentFile.list()!!.toList())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `keep refuses a wrong file and leaves nothing`() {
        val rom = realRom()
        assumeTrue(rom != null, "FE8_ASSETS/baserom.gba not available")
        val wrong = rom!!.copyOf().also { it[0x100] = (it[0x100] + 1).toByte() }
        val base = tempDir()
        try {
            val kept = File(base, RomImage.PATH)
            val r = RomImage.keep(Fe8Import.importer(), wrong) { TestFileSink(kept) }
            assertTrue(r is RomCheck.WrongRom)
            assertFalse(kept.exists())
            assertEquals(0, base.walkTopDown().count { it.isFile })
        } finally {
            base.deleteRecursively()
        }
    }
}
