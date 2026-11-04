package gba.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TextureOverridesTest {
    /** Minimal PNG head: signature + IHDR with the given colour type (enough for kind detection). */
    private fun png(file: File, colourType: Int) {
        file.parentFile.mkdirs()
        val b = ByteArray(33)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10).copyInto(b)
        b[11] = 13; "IHDR".toByteArray().copyInto(b, 12)
        b[24] = 8; b[25] = colourType.toByte()
        file.writeBytes(b)
    }

    private fun vfs(tmp: File, vararg layers: String) = VirtualFileSystem().also { v ->
        for (l in layers) v.mount(DirectoryLayer(File(tmp, l).also { it.mkdirs() }, l))
    }

    @Test
    fun `no mods means no override`(@TempDir tmp: File) {
        png(File(tmp, "base/gfx/ui/cursors.png"), 3)
        assertNull(TextureOverrides(vfs(tmp, "base")).resolve("gfx/ui/cursors.png"))
    }

    @Test
    fun `indexed hi-res replacement with its scale`(@TempDir tmp: File) {
        png(File(tmp, "base/gfx/ui/cursors.png"), 3)
        png(File(tmp, "mod/gfx/ui/cursors@2x.png"), 3)
        val o = TextureOverrides(vfs(tmp, "base", "mod")).resolve("gfx/ui/cursors.png")!!
        assertEquals("gfx/ui/cursors@2x.png", o.path)
        assertEquals(2, o.scale)
        assertTrue(o.indexed)
        assertTrue(o.paletteVariants.isEmpty())
    }

    @Test
    fun `higher layer wins over a larger scale in a lower layer`(@TempDir tmp: File) {
        png(File(tmp, "a/gfx/x@4x.png"), 3)
        png(File(tmp, "b/gfx/x@2x.png"), 6)
        val o = TextureOverrides(vfs(tmp, "base", "a", "b")).resolve("gfx/x.png")!!
        assertEquals(2, o.scale)
        assertFalse(o.indexed)
        png(File(tmp, "b/gfx/x@3x.png"), 6) // same layer: larger scale wins
        assertEquals(3, TextureOverrides(vfs(tmp, "base", "a", "b")).resolve("gfx/x.png")!!.scale)
    }

    @Test
    fun `true-colour palette variants are selected by palette hash`(@TempDir tmp: File) {
        val pal = ShortArray(16) { (it * 0x421).toShort() }
        val hash = TextureOverrides.paletteHash(pal)
        assertTrue(Regex("[0-9a-f]{8}").matches(hash))
        png(File(tmp, "mod/gfx/face@2x.png"), 6)
        png(File(tmp, "mod/gfx/face@2x.$hash.png"), 6)
        png(File(tmp, "mod/gfx/face@2x.notahash.png"), 6)
        png(File(tmp, "mod/gfx/sub/face@2x.0000abcd.png"), 6) // other directory: ignored
        val o = TextureOverrides(vfs(tmp, "base", "mod")).resolve("gfx/face.png")!!
        assertEquals(mapOf(hash to "gfx/face@2x.$hash.png"), o.paletteVariants)
        assertEquals("gfx/face@2x.$hash.png", o.imageFor(hash))
        pal[3] = 0x7FFF
        assertEquals("gfx/face@2x.png", o.imageFor(TextureOverrides.paletteHash(pal)))
    }
}
