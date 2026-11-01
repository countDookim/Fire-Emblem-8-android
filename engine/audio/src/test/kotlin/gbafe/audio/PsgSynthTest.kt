package gbafe.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PsgSynthTest {
    private val rate = 48000

    private fun crossings(out: FloatArray, frames: Int, right: Boolean): Int {
        var n = 0
        val o = if (right) 1 else 0
        for (i in 1 until frames) if (out[2 * i - 2 + o] < 0 && out[2 * i + o] >= 0) n++
        return n
    }

    @Test
    fun `square 2 plays its register frequency and duty, panned by NR51`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
        psg.write(M4aPsg.NR51, 0x02) // ch2 right only
        psg.write(0x04000068, 0x80) // 50% duty
        psg.write(0x04000069, 0xF0) // volume 15, no envelope
        val fr = 2048 - 298 // 131072 / 298 = 439.8 Hz
        psg.write(0x0400006C, fr and 0xFF)
        psg.write(0x0400006D, 0x80 or (fr ushr 8))
        val out = FloatArray(2 * rate)
        synth.render(out, rate, 1f)
        val n = crossings(out, rate, right = true)
        assertTrue(n in 438..441, "crossings $n")
        assertTrue((0 until rate).all { out[2 * it] == 0f }, "left silent")
        assertEquals(15f, out.maxOrNull())
        assertTrue(synth.isOn(2))
    }

    @Test
    fun `envelope decays and length stops the channel`() {
        val psg = M4aPsg()
        val synth = PsgSynth(psg, rate)
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
        psg.write(M4aPsg.NR51, 0x22)
        psg.write(M4aPsg.NR50, 0x37) // left 4/8, right 8/8
        psg.write(M4aPsg.SOUNDCNT_H, 0x01) // 50%
        psg.write(0x04000068, 0x80)
        psg.write(0x04000069, 0xF0)
        psg.write(0x0400006D, 0x80)
        val out = FloatArray(2 * 1000)
        synth.render(out, 1000, 1f)
        assertEquals(3.75f, (0 until 1000).maxOf { out[2 * it] })
        assertEquals(7.5f, (0 until 1000).maxOf { out[2 * it + 1] })
    }
}
