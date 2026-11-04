package gbafe.core

import gbafe.core.flags.FeatureFlags
import gbafe.core.rng.Fe8Rng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureFlagsTest {
    @Test
    fun `vanilla forces every flag off`() {
        val flags = FeatureFlags()
        val f = flags.register("mod.test.flag", "test", defaultEnabled = true)
        assertFalse(flags.isEnabled(f))
        flags.vanilla = false
        assertTrue(flags.isEnabled(f))
        assertEquals(f, flags.register("mod.test.flag", "again"))
    }
}

class Fe8RngTest {
    /** Load/store round-trip; the bit-exactness proof is the mGBA differential test in :testing. */
    @Test
    fun `load and store round-trip`() {
        val r = Fe8Rng()
        r.load(0x1234, 0xFFFF, 0x10001)
        val out = IntArray(3)
        r.store(out)
        assertEquals(listOf(0x1234, 0xFFFF, 0x0001), out.toList())
    }
}
