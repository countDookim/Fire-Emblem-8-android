package gbafe.extract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExtractCliTest {
    @Test
    fun `help returns null`() {
        assertNull(parseArgs(arrayOf("--help")))
        assertNull(parseArgs(arrayOf("--rom", "x", "-h")))
    }

    @Test
    fun `parses all flags`() {
        val a = parseArgs(arrayOf("--rom", "r.gba", "--out", "o", "--map", "m.map", "--decomp", "d"))!!
        assertEquals(ExtractArgs(File("r.gba"), File("d"), File("m.map"), File("o")), a)
        assertEquals("fe8", a.game)
        assertEquals("fe8", parseArgs(arrayOf("--rom", "r", "--out", "o", "--game", "fe8"))!!.game)
    }

    @Test
    fun `rejects bad command lines`() {
        assertThrows<UsageException> { parseArgs(arrayOf("--out", "o")) }
        assertThrows<UsageException> { parseArgs(arrayOf("--rom", "r", "--out")) }
        assertThrows<UsageException> { parseArgs(arrayOf("--rom", "r", "--out", "o", "--bogus", "1")) }
        assertThrows<UsageException> { parseArgs(arrayOf("--rom", "r", "--out", "o", "--game", "fe9")) }
    }

    @Test
    fun `refuses output inside the code repo`(@TempDir tmp: File) {
        val repo = File(tmp, "code").apply { mkdirs() }
        assertThrows<UsageException> { checkOutsideCodeRepo(repo, repo) }
        assertThrows<UsageException> { checkOutsideCodeRepo(File(repo, "build/extracted"), repo) }
        assertThrows<UsageException> { checkOutsideCodeRepo(File(tmp, "rom/../code/x"), repo) }
        assertDoesNotThrow { checkOutsideCodeRepo(File(tmp, "rom/extracted"), repo) }
        assertDoesNotThrow { checkOutsideCodeRepo(File(tmp, "code-sibling"), repo) }
    }
}
