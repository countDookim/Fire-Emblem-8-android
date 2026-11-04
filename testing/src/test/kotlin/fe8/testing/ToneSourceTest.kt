package fe8.testing

import gba.audio.ToneSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToneSourceTest {
    @Test
    fun `tone has the requested number of cycles per second and does not allocate`() {
        val tone = ToneSource(sampleRate = 48_000, frequency = 440)
        val buf = ShortArray(2 * 48_000)
        tone.render(buf, 48_000)
        var rising = 0
        for (i in 1 until 48_000) if (buf[2 * i] > 0 && buf[2 * i - 2] < 0) rising++
        // The 440th low-to-high edge lands a fraction of a sample after the first second.
        assertTrue(rising in 439..440, "rising edges in one second: $rising")
        assertEquals(buf[0], buf[1], "both channels")
        assertEquals(0L, AllocationMeter.measureMin(warmup = 50, iterations = 50) { tone.render(buf, 256) })
    }
}
