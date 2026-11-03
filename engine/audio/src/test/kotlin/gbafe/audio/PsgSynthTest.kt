package gbafe.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PsgSynthTest {
    private val rate = 48000

    /** Upward crossings of [mid]: levels are unipolar (0..2 * volume), as in mGBA. */
    private fun crossings(out: FloatArray, frames: Int, right: Boolean, mid: Float): Int {
        var n = 0
        val o = if (right) 1 else 0
        for (i in 1 until frames) if (out[2 * i - 2 + o] < mid && out[2 * i + o] >= mid) n++
        return n
    }

    @Test
    fun `frame timing applies writes 17408 cycles into the frame and holds a stopped level`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, 13379)
        synth.highPass = false
        synth.startAtFrame(1000)
        psg.write(M4aPsg.NR51, 0x22)
        psg.write(0x04000068, 0xC0) // 75% duty
        psg.write(0x04000069, 0xF0)
        psg.write(0x0400006D, 0x87) // freq 0x700: 2048 Hz
        val out = FloatArray(2 * 224)
        synth.render(out, 224, 1f)
        val w = (17408L * 13379 / 16777216).toInt() // 13 samples
        assertTrue((0 until w).all { out[2 * it] == 0f }, "silent before the write point")
        assertTrue((w until 224).any { out[2 * it] > 0f }, "playing after it")
        psg.write(0x04000069, 0x00) // DAC off: the channel stops
        psg.write(0x0400006D, 0x87)
        out.fill(0f)
        synth.render(out, 224, 1f)
        val last = out[2 * (w - 1)]
        assertTrue(last > 0f, "previous level until the write point")
        assertTrue((w until 224).all { out[2 * it] == out[2 * w] }, "stopped: last level held")
    }

    @Test
    fun `square 2 plays its register frequency and duty, panned by NR51`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        psg.write(M4aPsg.NR51, 0x02) // ch2 right only
        psg.write(0x04000068, 0x80) // 50% duty
        psg.write(0x04000069, 0xF0) // volume 15, no envelope
        val fr = 2048 - 298 // 131072 / 298 = 439.8 Hz
        psg.write(0x0400006C, fr and 0xFF)
        psg.write(0x0400006D, 0x80 or (fr ushr 8))
        val out = FloatArray(2 * rate)
        synth.render(out, rate, 1f)
        val n = crossings(out, rate, right = true, mid = 15f)
        assertTrue(n in 438..441, "crossings $n")
        assertTrue((0 until rate).all { out[2 * it] == 0f }, "left silent")
        assertEquals(30f, out.maxOrNull())
        assertTrue(synth.isOn(2))
    }

    @Test
    fun `envelope decays and length stops the channel`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        psg.write(M4aPsg.NR51, 0x11)
        psg.write(0x04000062, 0x80 or 0x30) // length 64 - 48 = 16 ticks of 256 Hz = 62.5 ms
        psg.write(0x04000063, 0xF1) // volume 15, decrease every 1/64 s
        psg.write(0x04000064, 0)
        psg.write(0x04000065, 0xC4) // trigger, length enable
        val out = FloatArray(2 * rate)
        synth.render(out, rate / 100, 1f) // 10 ms
        assertTrue(synth.isOn(1) && synth.volumeOf(1) == 15)
        synth.render(out, rate / 20, 1f) // +50 ms: 3 envelope steps
        assertTrue(synth.volumeOf(1) in 11..13, "volume ${synth.volumeOf(1)}")
        synth.render(out, rate / 20, 1f) // past 62.5 ms
        assertFalse(synth.isOn(1))
    }

    @Test
    fun `CgbSound note start triggers the synth`() {
        val psg = M4aPsg()
        psg.write(0x04000079, 0xA0)
        psg.write(0x0400007D, 0x80)
        assertEquals(8, psg.takeTriggers())
        assertEquals(0, psg.takeTriggers())
    }

    @Test
    fun `sweep raises square 1 and writes the frequency back`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        psg.write(M4aPsg.NR51, 0x11)
        psg.write(0x04000060, 0x11) // period 1 (128 Hz), increase, shift 1
        psg.write(0x04000062, 0x80)
        psg.write(0x04000063, 0xF0)
        psg.write(0x04000064, 0x00)
        psg.write(0x04000065, 0x81) // 0x100, trigger
        val out = FloatArray(2 * rate)
        synth.render(out, rate / 100, 1f) // 10 ms: one or two sweep clocks
        val f = psg.read(0x04000064) or ((psg.read(0x04000065) and 7) shl 8)
        assertTrue(f == 0x180 || f == 0x240, "freq 0x%X".format(f))
        synth.render(out, rate / 10, 1f) // keeps rising past 2047: channel off
        assertFalse(synth.isOn(1))
    }

    @Test
    fun `NR50 and SOUNDCNT_H scale the mix`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        psg.write(M4aPsg.NR51, 0x22)
        psg.write(M4aPsg.NR50, 0x37) // left 4/8, right 8/8
        psg.write(M4aPsg.SOUNDCNT_H, 0x01) // 50%
        psg.write(0x04000068, 0x80)
        psg.write(0x04000069, 0xF0)
        psg.write(0x0400006D, 0x80)
        val out = FloatArray(2 * 1000)
        synth.render(out, 1000, 1f)
        assertEquals(7.5f, (0 until 1000).maxOf { out[2 * it] })
        assertEquals(15f, (0 until 1000).maxOf { out[2 * it + 1] })
    }

    @Test
    fun `NRx2 writes without trigger follow mGBA's envelope rules`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        // Volume 1, decreasing every 1/64 s: reaches 0 and stops stepping (dead).
        psg.write(M4aPsg.NR51, 0x22)
        psg.write(0x04000069, 0x11)
        psg.write(0x0400006D, 0x87)
        val out = FloatArray(2 * rate)
        synth.render(out, rate / 10, 1f)
        assertTrue(synth.isOn(2) && synth.volumeOf(2) == 0)
        // Period 0: the volume holds (GBA style: no zombie-mode change).
        psg.write(0x04000069, 0x31)
        psg.write(0x0400006D, 0x87)
        synth.render(out, 1, 1f)
        psg.write(0x04000069, 0x08)
        synth.render(out, rate / 10, 1f)
        assertTrue(synth.isOn(2) && synth.volumeOf(2) == 3, "volume ${synth.volumeOf(2)}")
        // Volume 0 and decreasing: the DAC is off, the channel stops without a trigger.
        psg.write(0x04000069, 0x00)
        synth.render(out, 1, 1f)
        assertFalse(synth.isOn(2))
    }

    private fun square2(psg: M4aPsg, duty: Int, fr: Int) {
        psg.write(M4aPsg.NR51, 0x22)
        psg.write(0x04000068, duty shl 6)
        psg.write(0x04000069, 0xF0)
        psg.write(0x0400006C, fr and 0xFF)
        psg.write(0x0400006D, 0x80 or (fr ushr 8))
    }

    @Test
    fun `squares above the output Nyquist rate average out instead of aliasing`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        square2(psg, duty = 2, fr = 2048 - 4) // 32768 Hz, 50% duty
        val out = FloatArray(2 * rate)
        synth.render(out, rate, 1f)
        val peak = (0 until rate).maxOf { kotlin.math.abs(out[2 * it] - 15f) }
        assertTrue(peak < 15f * 0.75f, "peak $peak") // unfiltered: 15 +-15 at a beat frequency
    }

    @Test
    fun `box filter keeps the duty-cycle mean`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        square2(psg, duty = 0, fr = 2048 - 37) // 3542 Hz, 12.5%: edges fall between samples
        val out = FloatArray(2 * rate)
        synth.render(out, rate, 1f)
        val mean = (0 until rate).sumOf { out[2 * it].toDouble() } / rate
        assertEquals(30 * 0.125, mean, 0.05)
    }

    @Test
    fun `wave channel is box-filtered with the right mean`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        psg.write(M4aPsg.NR51, 0x44)
        psg.write(0x04000070, 0x80)
        psg.write(0x04000073, 0x20) // 100%
        for (i in 0 until 16) psg.write(M4aPsg.WAVE_RAM + i, if (i < 8) 0xFF else 0x00)
        val fr = 2048 - 1 // 65536 Hz: 44 wave steps per output sample
        psg.write(0x04000074, fr and 0xFF)
        psg.write(0x04000075, 0x80 or (fr ushr 8))
        val out = FloatArray(2 * rate)
        synth.render(out, rate, 1f)
        val peak = (0 until rate).maxOf { kotlin.math.abs(out[2 * it] - 15f) }
        val mean = (0 until rate).sumOf { out[2 * it].toDouble() } / rate
        assertTrue(peak < 15f, "peak $peak")
        assertEquals(15.0, mean, 0.05)
    }

    @Test
    fun `NR32 bit 7 plays the wave at 75 percent`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        synth.highPass = false
        psg.write(M4aPsg.NR51, 0x44)
        psg.write(0x04000070, 0x80)
        psg.write(0x04000073, 0x80) // CgbSound's CGB3_VOL 0x80
        for (i in 0 until 16) psg.write(M4aPsg.WAVE_RAM + i, if (i < 8) 0xFF else 0x00)
        val fr = 2048 - 1024 // 64 Hz: whole samples
        psg.write(0x04000074, fr and 0xFF)
        psg.write(0x04000075, 0x80 or (fr ushr 8))
        val out = FloatArray(2 * 1000)
        synth.render(out, 1000, 1f)
        assertEquals(22f, (0 until 1000).maxOf { out[2 * it] }) // (15 * 3 >> 2) * 2
    }

    @Test
    fun `DC blocker removes a constant level and passes tones`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        psg.write(M4aPsg.NR51, 0x44)
        psg.write(0x04000070, 0x80)
        psg.write(0x04000073, 0x20)
        for (i in 0 until 16) psg.write(M4aPsg.WAVE_RAM + i, 0xFF) // constant 30
        psg.write(0x04000074, 0)
        psg.write(0x04000075, 0x80)
        val out = FloatArray(2 * rate)
        synth.render(out, rate, 1f)
        assertTrue(out[0] > 29f, "step passes: ${out[0]}")
        assertTrue(kotlin.math.abs(out[2 * (rate - 1)]) < 0.1f, "DC left ${out[2 * (rate - 1)]}")
    }
}
