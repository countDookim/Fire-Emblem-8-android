package gbafe.core.flags

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlagStoreTest {
    private val layout = EventFlagLayout(
        permanentSaveBytes = 0x19,
        chapterSaveBytes = 5,
        permanentStorageBytes = 32,
        chapterStorageBytes = 16,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )
    private val vanilla = EventFlags(layout)
    private val registry = FlagRegistry(vanilla, mapOf("chapter_won" to 3, "met_eirika" to 101))
    private val store = RoutedFlagStore(registry)

    private fun bytesOf(f: EventFlags): ByteArray = ByteArray(f.size).also { f.copyOut(it) }

    @Test
    fun `mod handle as positive int and as sign-extended short routes to the registry`() {
        val h = registry.register("m", "MyMod.Seen")
        val h2 = registry.register("m", "MyMod.Other")
        val before = bytesOf(vanilla)

        store.setFlag(h)
        assertTrue(registry.check(h))
        assertTrue(store.checkFlag(h))
        assertTrue(store.checkFlag(h.toShort().toInt()))
        assertFalse(registry.check(h2))

        store.clearFlag(h.toShort().toInt())
        assertFalse(registry.check(h))
        store.setFlag(h2.toShort().toInt())
        assertTrue(registry.check(h2))
        assertTrue(store.checkFlag(h2))
        assertArrayEquals(before, bytesOf(vanilla))
    }

    @Test
    fun `vanilla ids go to EventFlags and leave mod flags alone`() {
        val h = registry.register("m", "MyMod.Seen")
        store.setFlag(3)
        store.setFlag(101)
        assertTrue(vanilla.checkFlag(3))
        assertTrue(vanilla.checkFlag(101))
        assertTrue(store.checkFlag(3))
        assertFalse(store.checkFlag(4))
        assertFalse(registry.check(h))
        store.clearFlag(3)
        assertFalse(vanilla.checkFlag(3))
        assertTrue(vanilla.checkFlag(101))
    }

    @Test
    fun `vanilla bytes are identical to plain EventFlags use`() {
        val plain = EventFlags(layout)
        registry.register("m", "MyMod.Seen")
        for (id in intArrayOf(0, 3, 7, 99, 100, 101, 150, 200)) {
            store.setFlag(id)
            plain.setFlag(id)
        }
        store.clearFlag(101)
        plain.clearFlag(101)
        store.setFlag(registry.resolve("MyMod.Seen"))
        assertArrayEquals(bytesOf(plain), bytesOf(vanilla))
        assertEquals(plain.checkFlag(7), store.checkFlag(7))
    }
}
