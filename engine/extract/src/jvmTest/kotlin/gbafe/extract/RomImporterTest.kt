package gbafe.extract

import gba.extract.ExtractOutput
import gba.extract.RomCheck
import gba.extract.RomImporter
import gbafe.extract.fe8.Fe8Import
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RomImporterTest {
    private class MemoryOutput : ExtractOutput {
        val files = LinkedHashMap<String, ByteArray>()

        override fun write(path: String, bytes: ByteArray) {
            files[path] = bytes
        }
    }

    private val abc = "abc".encodeToByteArray()
    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"

    private fun importer(version: Int = 1) = RomImporter(abcSha1, version) { _, out ->
        out.write("a.bin", byteArrayOf(1))
        out.writeText("dir/b.json", "{}")
        listOf("a.bin", "dir/b.json")
    }

    @Test
    fun `check reports the SHA-1 of a wrong ROM`() {
        assertEquals(RomCheck.Ok, importer().check(abc))
        assertEquals(RomCheck.WrongRom("da39a3ee5e6b4b0d3255bfef95601890afd80709"), importer().check(ByteArray(0)))
    }

    @Test
    fun `import writes the stamp last and reports progress`() {
        val out = MemoryOutput()
        val seen = ArrayList<Pair<String, Int>>()
        val written = importer().import(abc, out) { p, n -> seen += p to n }
        assertEquals(listOf("a.bin", "dir/b.json"), written)
        assertEquals(listOf("a.bin" to 1, "dir/b.json" to 2), seen)
        assertEquals(listOf("a.bin", "dir/b.json", RomImporter.STAMP_PATH), out.files.keys.toList())
        val stamp = out.files.getValue(RomImporter.STAMP_PATH).decodeToString()
        assertEquals("$abcSha1 1\n", stamp)
        assertTrue(importer().isCurrent(stamp))
        assertFalse(importer(2).isCurrent(stamp))
        assertFalse(importer().isCurrent(null))
    }

    @Test
    fun `a wrong ROM throws and writes nothing`() {
        val out = MemoryOutput()
        assertThrows<IllegalArgumentException> { importer().import(byteArrayOf(1, 2), out) }
        assertTrue(out.files.isEmpty())
    }

    private fun samePixels(a: ByteArray, b: ByteArray): Boolean {
        val x = javax.imageio.ImageIO.read(a.inputStream()) ?: return false
        val y = javax.imageio.ImageIO.read(b.inputStream()) ?: return false
        if (x.width != y.width || x.height != y.height) return false
        for (j in 0 until x.height) for (i in 0 until x.width) {
            if (x.getRGB(i, j) != y.getRGB(i, j)) return false
        }
        return true
    }

    @Test
    fun `FE8 import reproduces the extracted files byte for byte`() {
        val assets = System.getenv("FE8_ASSETS")
        assumeTrue(assets != null && File(assets, "baserom.gba").isFile, "FE8_ASSETS not set")
        val root = File(assets, "extracted")
        val out = MemoryOutput()
        val start = System.nanoTime()
        val written = Fe8Import.importer().import(File(assets, "baserom.gba").readBytes(), out)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("import: ${written.size} files in $ms ms")
        val onDisk = root.walkTopDown().filter { it.isFile && it.name != ".release" }.count()
        println("extracted/ files (excluding .release): $onDisk")
        val diffs = written.filter {
            !File(root, it).readBytes().contentEquals(out.files.getValue(it))
        }
        println("import: ${diffs.size} differing files; dirs: ${diffs.groupingBy { it.substringBeforeLast('/') }.eachCount()}; non-png: ${diffs.filter { !it.endsWith(".png") }.take(5)}")
        // PNG bytes depend on the zlib build that compressed them; compare decoded pixels instead.
        val pixelDiffs = diffs.filter { !samePixels(File(root, it).readBytes(), out.files.getValue(it)) }
        assertTrue(pixelDiffs.isEmpty(), "files differ from extracted/: ${pixelDiffs.take(8)}")
        assertEquals(written.size + 1, out.files.size)
        assertEquals(onDisk, written.size)
    }
}
