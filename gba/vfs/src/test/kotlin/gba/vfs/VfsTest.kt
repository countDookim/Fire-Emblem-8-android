package gba.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VfsTest {
    @Test
    fun `later layers override earlier ones`(@TempDir tmp: File) {
        val base = File(tmp, "base").apply { mkdirs() }
        val mod = File(tmp, "mod").apply { mkdirs() }
        File(base, "data").mkdirs(); File(mod, "data").mkdirs()
        File(base, "data/items.json").writeText("base")
        File(base, "data/classes.json").writeText("base")
        File(mod, "data/items.json").writeText("mod")
        val vfs = VirtualFileSystem()
        vfs.mount(DirectoryLayer(base, "base"))
        vfs.mount(DirectoryLayer(mod, "mod:x"))
        assertEquals("mod", vfs.readText("data/items.json"))
        assertEquals("base", vfs.readText("/data//classes.json"))
        assertEquals(listOf("data/classes.json", "data/items.json"), vfs.list("data"))
    }

    @Test
    fun `paths cannot escape the root`() {
        assertThrows<IllegalArgumentException> { VirtualFileSystem.normalize("../secret") }
        assertEquals("a/c", VirtualFileSystem.normalize("a/b/../c"))
    }
}
