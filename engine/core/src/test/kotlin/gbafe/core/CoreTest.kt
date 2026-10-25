package gbafe.core

import gbafe.core.event.EventChannel
import gbafe.core.event.TickListener
import gbafe.core.flags.FeatureFlags
import gbafe.core.rng.Fe8Rng
import gbafe.core.time.FixedStepClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedStepClockTest {
    @Test
    fun `produces exactly the GBA rate over one simulated hour`() {
        val clock = FixedStepClock()
        clock.advance(0)
        var t = 0L
        val displayStep = 1_000_000_000L / 60
        val end = 3_600_000_000_000L
        while (t < end) {
            t += displayStep
            clock.advance(t)
        }
        val expected = end.toDouble() * FixedStepClock.TICK_HZ / 1e9
        assertTrue(kotlin.math.abs(clock.ticks - expected) < 2.0, "ticks=${clock.ticks} expected≈$expected")
    }

    @Test
    fun `caps catch-up after a stall`() {
        val clock = FixedStepClock(maxStepsPerAdvance = 4)
        clock.advance(0)
        assertEquals(4, clock.advance(10_000_000_000L))
        assertEquals(0, clock.advance(10_000_000_001L))
    }
}

class EventChannelTest {
    @Test
    fun `dispatches in priority order and survives unsubscribe during dispatch`() {
        val ch = EventChannel<TickListener>("test")
        val log = ArrayList<String>()
        lateinit var b: TickListener
        ch.subscribe(TickListener { log += "late" }, priority = 10)
        b = ch.subscribe(TickListener { log += "b"; ch.unsubscribe(b) })
        ch.subscribe(TickListener { log += "early" }, priority = -5)
        ch.dispatch { it.onTick(0) }
        ch.dispatch { it.onTick(1) }
        assertEquals(listOf("early", "b", "late", "early", "late"), log)
    }
}

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
