package gbafe.save

import gbafe.core.flags.EventFlagLayout
import gbafe.core.flags.EventFlags
import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.IntArrayVarStore
import gbafe.core.flags.VarRegistry
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModSaveSectionsTest {
    private val layout = EventFlagLayout(
        permanentSaveBytes = 0x19,
        chapterSaveBytes = 5,
        permanentStorageBytes = 32,
        chapterStorageBytes = 16,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )
    private val sram = ByteArray(SaveFormat.CORE_SIZE) { (it * 5 + 1).toByte() }
    private val base = SaveContainer.fromSram("fe8", sram)

    private class Regs(layout: EventFlagLayout) {
        val flags = FlagRegistry(EventFlags(layout), mapOf("vanilla" to 1))
        val vars = VarRegistry(IntArrayVarStore(4), mapOf("v" to 0))
        val seen = flags.register("m", "M.Seen")
        val unseen = flags.register("m", "M.Unseen")
        val count = vars.register("m", "M.Count")
    }

    @Test
    fun `flags and variables round trip through an encoded container`() {
        val a = Regs(layout)
        a.flags.set(a.seen)
        a.vars.set(a.count, 1234)
        val bytes = ModSaveSections.write(base, "m", a.flags, a.vars).toByteArray()

        val b = Regs(layout)
        ModSaveSections.read(SaveContainer.read(bytes), "m", b.flags, b.vars)
        assertTrue(b.flags.check(b.seen))
        assertFalse(b.flags.check(b.unseen))
        assertEquals(1234, b.vars.get(b.count))
    }

    @Test
    fun `core section is untouched`() {
        val a = Regs(layout)
        a.flags.set(a.seen)
        val c = ModSaveSections.write(base, "m", a.flags, null)
        assertArrayEquals(sram, c.core)
        assertNotNull(c.section(SaveFormat.modSectionId("m")))
    }

    @Test
    fun `missing section resets flags and variables`() {
        val a = Regs(layout)
        a.flags.set(a.seen)
        a.vars.set(a.count, 9)
        ModSaveSections.read(base, "m", a.flags, a.vars)
        assertFalse(a.flags.check(a.seen))
        assertEquals(0, a.vars.get(a.count))
    }

    @Test
    fun `newer section version throws NewerVersion`() {
        val id = SaveFormat.modSectionId("m")
        val section = SaveSection(id, ModSaveSections.VERSION + 1, ByteArray(8))
        val a = Regs(layout)
        assertThrows<SaveException.NewerVersion> {
            ModSaveSections.read(base.withSection(section), "m", a.flags, a.vars)
        }
    }

    @Test
    fun `truncated payload throws Corrupt`() {
        val a = Regs(layout)
        a.flags.set(a.seen)
        val full = ModSaveSections.write(base, "m", a.flags, a.vars)
        val data = full.section(SaveFormat.modSectionId("m"))!!.data
        for (cut in intArrayOf(0, 2, 5, data.size - 1)) {
            val bad = SaveSection(
                SaveFormat.modSectionId("m"),
                ModSaveSections.VERSION,
                data.copyOf(cut),
            )
            assertThrows<SaveException.Corrupt>("cut $cut") {
                ModSaveSections.read(base.withSection(bad), "m", a.flags, a.vars)
            }
        }
    }
}
