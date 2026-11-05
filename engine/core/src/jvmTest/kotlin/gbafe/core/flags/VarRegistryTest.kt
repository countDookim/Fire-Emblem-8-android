package gbafe.core.flags

import gba.testkit.AllocationMeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class VarRegistryTest {
    private val vanillaNames = mapOf("slot1" to 1, "counter" to 20)

    private fun registry() = VarRegistry(IntArrayVarStore(32), vanillaNames)

    @Test
    fun `vanilla names resolve to indices and use the vanilla store`() {
        val r = registry()
        assertEquals(20, r.resolve("counter"))
        r.set(20, 77)
        assertEquals(77, r.get(r.resolve("counter")))
        assertEquals(77, r.vanilla.get(20))
    }

    @Test
    fun `mod variables register resolve and hold ints`() {
        val r = registry()
        val h = r.register("m", "gold_bonus")
        assertEquals(MOD_BASE, h)
        assertEquals(h, r.resolve("gold_bonus"))
        assertEquals(h, r.register("m", "gold_bonus"))
        assertEquals(0, r.get(h))
        r.set(h, -123456)
        assertEquals(-123456, r.get(h))
        assertEquals(UNRESOLVED, r.resolve("nope"))
        assertThrows(NoSuchElementException::class.java) { r.resolveOrThrow("nope") }
        assertThrows(IllegalArgumentException::class.java) { r.register("m", "counter") }
        assertThrows(IllegalArgumentException::class.java) { r.register("other", "gold_bonus") }
        assertThrows(IllegalArgumentException::class.java) { r.get(h + 1) }
    }

    @Test
    fun `mod variable survives serialization into a reordered registry`() {
        val a = registry()
        val x = a.register("modA", "x")
        val y = a.register("modA", "y")
        a.set(x, 42)
        a.set(y, Int.MIN_VALUE)
        a.set(a.register("modB", "other"), 9)
        val blob = a.serializeMod("modA")

        val b = registry()
        val otherB = b.register("modB", "other")
        val yB = b.register("modA", "y")
        val xB = b.register("modA", "x")
        assertEquals(2, b.deserializeMod("modA", blob))
        assertEquals(42, b.get(xB))
        assertEquals(Int.MIN_VALUE, b.get(yB))
        assertEquals(0, b.get(otherB))
        assertThrows(IllegalArgumentException::class.java) {
            b.deserializeMod("modA", blob.copyOf(3))
        }
    }

    @Test
    fun `mod operations leave vanilla variables untouched and entries list both`() {
        val r = registry()
        r.set(1, 5)
        for (i in 0 until 50) r.set(r.register("m", "v$i"), i + 1)
        assertEquals(5, r.get(1))
        assertEquals(0, r.get(2))
        val e = r.entries()
        assertEquals(vanillaNames.size + 50, e.size)
        assertEquals(50, e.last().value)
        assertEquals(MOD_BASE + 49, e.last().handle)
    }

    @Test
    fun `handle space ends at u16 and is enforced`() {
        val r = registry()
        val slots = MAX_HANDLE - MOD_BASE + 1
        var last = 0
        for (i in 0 until slots) last = r.register("m", "v$i")
        assertEquals(MAX_HANDLE, last)
        assertThrows(IllegalStateException::class.java) { r.register("m", "overflow") }
        r.set(MAX_HANDLE, 1)
        assertEquals(1, r.get(MAX_HANDLE))
    }

    @Test
    fun `get and set allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val r = registry()
        val handles = IntArray(64) { r.register("m", "v$it") } + intArrayOf(1, 20)
        var sum = 0
        var n = 0
        val bytes = AllocationMeter.measureMin(warmup = 20_000, iterations = 10_000) {
            val h = handles[n % handles.size]
            n++
            r.set(h, n)
            sum += r.get(h)
        }
        assertEquals(0L, bytes, "bytes allocated over 10k calls")
        assertTrue(sum != 0)
    }
}
