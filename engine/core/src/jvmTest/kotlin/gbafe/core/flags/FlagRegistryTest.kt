package gbafe.core.flags

import gba.testkit.AllocationMeter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class FlagRegistryTest {
    private val layout = EventFlagLayout(
        permanentSaveBytes = 0x19,
        chapterSaveBytes = 5,
        permanentStorageBytes = 32,
        chapterStorageBytes = 16,
        chapterFlagLimit = 100,
        permanentBaseId = 100,
        bitMask = byteArrayOf(1, 2, 4, 8, 16, 32, 64, -128, 0, 0, 0, 0, 0),
    )

    private val vanillaNames = mapOf("chapter_won" to 3, "met_eirika" to 101, "secret" to 250)

    private fun registry() = FlagRegistry(EventFlags(layout), vanillaNames)

    private fun bytesOf(f: EventFlags): ByteArray = ByteArray(f.size).also { f.copyOut(it) }

    @Test
    fun `vanilla names resolve to flag ids and drive EventFlags`() {
        val r = registry()
        assertEquals(3, r.resolve("chapter_won"))
        assertEquals(101, r.resolve("met_eirika"))
        r.set(r.resolve("chapter_won"))
        r.set(r.resolve("met_eirika"))
        assertTrue(r.vanilla.checkFlag(3))
        assertTrue(r.check(101))
        r.clear(3)
        assertFalse(r.vanilla.checkFlag(3))
    }

    @Test
    fun `mod names get handles at or above MOD_BASE and resolve after vanilla`() {
        val r = registry()
        val h = r.register("modA", "dragon_slain")
        assertEquals(MOD_BASE, h)
        assertEquals(h, r.resolve("dragon_slain"))
        assertEquals(h, r.register("modA", "dragon_slain"))
        assertEquals(MOD_BASE + 1, r.register("modA", "second"))
        assertEquals(UNRESOLVED, r.resolve("nope"))
        assertThrows(NoSuchElementException::class.java) { r.resolveOrThrow("nope") }
        assertThrows(IllegalArgumentException::class.java) { r.register("modA", "secret") }
        assertThrows(IllegalArgumentException::class.java) { r.register("modB", "second") }
        assertFalse(r.check(h))
        r.set(h)
        assertTrue(r.check(h))
        r.clear(h)
        assertFalse(r.check(h))
    }

    @Test
    fun `mod flag survives serialization into a registry with reordered registration`() {
        val a = registry()
        val x = a.register("modA", "x")
        val y = a.register("modA", "y")
        val z = a.register("modA", "z")
        a.set(x)
        a.set(z)
        val other = a.register("modB", "other")
        a.set(other)
        val blob = a.serializeMod("modA")

        val b = registry()
        val otherB = b.register("modB", "other")
        val zB = b.register("modA", "z")
        val yB = b.register("modA", "y")
        val xB = b.register("modA", "x")
        assertTrue(x != xB && z != zB)
        assertEquals(3, b.deserializeMod("modA", blob))
        assertTrue(b.check(xB))
        assertFalse(b.check(yB))
        assertTrue(b.check(zB))
        assertFalse(b.check(otherB))
        assertEquals(y - MOD_BASE, 1)
    }

    @Test
    fun `deserialize clears absent flags and ignores unknown names`() {
        val a = registry()
        a.set(a.register("modA", "kept"))
        a.register("modA", "gone")
        val blob = a.serializeMod("modA")

        val b = registry()
        val kept = b.register("modA", "kept")
        val fresh = b.register("modA", "fresh")
        b.set(fresh)
        assertEquals(1, b.deserializeMod("modA", blob))
        assertTrue(b.check(kept))
        assertFalse(b.check(fresh))
        assertThrows(IllegalArgumentException::class.java) {
            b.deserializeMod("modA", blob.copyOf(blob.size - 1))
        }
    }

    @Test
    fun `non-ascii names round trip`() {
        val a = registry()
        a.set(a.register("m", "용사_처치"))
        val b = registry()
        val h = b.register("m", "용사_처치")
        b.deserializeMod("m", a.serializeMod("m"))
        assertTrue(b.check(h))
    }

    @Test
    fun `mod operations leave the vanilla bytes untouched`() {
        val r = registry()
        r.set(5)
        r.set(150)
        val before = bytesOf(r.vanilla)
        for (i in 0 until 100) r.set(r.register("m", "f$i"))
        r.clear(r.resolve("f7"))
        r.deserializeMod("m", r.serializeMod("m"))
        assertArrayEquals(before, bytesOf(r.vanilla))
    }

    @Test
    fun `handle space ends at u16 and is enforced`() {
        val r = registry()
        val slots = MAX_HANDLE - MOD_BASE + 1
        var last = 0
        for (i in 0 until slots) last = r.register("m", "f$i")
        assertEquals(MAX_HANDLE, last)
        assertThrows(IllegalStateException::class.java) { r.register("m", "overflow") }
        assertEquals(MAX_HANDLE, r.register("m", "f${slots - 1}"))
        r.set(MAX_HANDLE)
        assertTrue(r.check(MAX_HANDLE))
        assertThrows(IllegalArgumentException::class.java) { r.check(MAX_HANDLE + 1) }
    }

    @Test
    fun `unregistered mod handles are rejected`() {
        val r = registry()
        r.register("m", "a")
        assertThrows(IllegalArgumentException::class.java) { r.check(MOD_BASE + 1) }
        assertThrows(IllegalArgumentException::class.java) { r.set(MOD_BASE + 1) }
    }

    @Test
    fun `entries list vanilla then mod flags with values`() {
        val r = registry()
        val h = r.register("m", "f")
        r.set(h)
        r.set(3)
        val e = r.entries()
        assertEquals(vanillaNames.size + 1, e.size)
        val mod = e.last()
        assertEquals("f", mod.name)
        assertEquals(h, mod.handle)
        assertEquals("m", mod.modId)
        assertTrue(mod.value)
        val van = e.first { it.name == "chapter_won" }
        assertEquals(3, van.handle)
        assertEquals(null, van.modId)
        assertTrue(van.value)
    }

    @Test
    fun `check set and clear allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val r = registry()
        val handles = IntArray(64) { r.register("m", "f$it") } + intArrayOf(3, 101, 250)
        var hits = 0
        var n = 0
        val bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 10_000) {
            val h = handles[n % handles.size]
            n++
            r.set(h)
            if (r.check(h)) hits++
            r.clear(h)
        }
        assertEquals(0L, bytes, "bytes allocated over 10k calls")
        assertTrue(hits > 0)
    }
}
