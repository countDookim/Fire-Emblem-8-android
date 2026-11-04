package gbafe.extract

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestResourceTest {
    @Test
    fun `the bundled FE8 manifest loads and answers lookups`() {
        val m = ManifestSources.bundled("fe8")
        assertNotNull(m)
        m!!
        assertEquals(0x08000000, m["gItemData"].address and 0xFF000000.toInt())
        assertTrue(m.sizeOrSpan("gItemData") > 0)
    }

    @Test
    fun `the library sources use no files or desktop APIs`() {
        val banned = listOf("import java.io.File", "java.nio.file", "java.awt", "javax.")
        val roots = listOf("src/commonMain/kotlin", "src/jvmMain/kotlin")
            .map { File(System.getProperty("user.dir"), it) }
        roots.forEach { assertTrue(it.isDirectory, "source dir not found: $it") }
        val files = roots.flatMap { it.walkTopDown().toList() }.filter { it.extension == "kt" }
        val offenders = files.flatMap { f ->
            f.readLines().filter { l -> !isComment(l) && banned.any { it in l } }.map { "${f.name}: ${it.trim()}" }
        }.toList()
        assertTrue(
            offenders.isEmpty(),
            "forbidden APIs in library sources:\n" + offenders.joinToString("\n"),
        )
    }

    private fun isComment(line: String): Boolean =
        line.trim().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
}
