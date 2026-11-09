package gba.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PcmStreamTest {
    private fun info(spv: Int, value: (Int) -> Int): M4aSoundInfo {
        val info = M4aSoundInfo(emptyArray(), null)
        info.pcmDmaCounter = 1
        info.pcmDmaPeriod = 7
        info.pcmSamplesPerVBlank = spv
        for (i in 0 until spv) {
            info.pcmBuffer[i] = value(i).toByte()
            info.pcmBuffer[i + M4aSoundInfo.PCM_DMA_BUF_SIZE] = (-value(i)).toByte()
        }
        return info
    }

    @Test
    fun `same rate passes samples through, left from the second half`() {
        val s = PcmStream(1000, 1000)
        s.push(info(8) { it })
        val out = ShortArray(16)
        s.render(out, 8)
        assertEquals((0 until 8).map { listOf(-it * 256, it * 256) }.flatten(), out.map { it.toInt() })
        assertEquals(0, s.queued)
    }

    @Test
    fun `double output rate interpolates midpoints`() {
        val s = PcmStream(1000, 2000)
        s.push(info(4) { it * 2 })
        val out = ShortArray(8)
        s.render(out, 4)
        assertEquals(listOf(0, 256, 512, 768), (0 until 4).map { out[2 * it + 1].toInt() })
    }

    @Test
    fun `underrun repeats the last sample`() {
        val s = PcmStream(1000, 1000)
        s.push(info(2) { 5 + it })
        val out = ShortArray(8)
        s.render(out, 4)
        assertEquals(listOf(5, 6, 6, 6).map { it * 256 }, (0 until 4).map { out[2 * it + 1].toInt() })
    }

    @Test
    fun `startup reserve covers one delayed producer frame`() {
        val s = PcmStream(1000, 1000, capacity = 32, prefillFrames = 20)
        val out = ShortArray(20)
        s.push(info(10) { it + 1 })
        s.render(out, 10)
        assertEquals(List(10) { 0 }, (0 until 10).map { out[2 * it + 1].toInt() })
        assertEquals(10, s.queued)
        s.push(info(10) { it + 11 })
        s.render(out, 10)
        assertEquals((1..10).map { it * 256 }, (0 until 10).map { out[2 * it + 1].toInt() })
        // The next producer frame is delayed: the queued frame must still play normally.
        s.render(out, 10)
        assertEquals((11..20).map { it * 256 }, (0 until 10).map { out[2 * it + 1].toInt() })
        s.render(out, 1)
        assertEquals(20 * 256, out[1].toInt())
        s.push(info(10) { it + 21 })
        s.render(out, 10)
        assertEquals((21..30).map { it * 256 }, (0 until 10).map { out[2 * it + 1].toInt() })
    }

    @Test
    fun `startup reserve accepts full ring and preserves interpolation phase`() {
        val s = PcmStream(1000, 2000, capacity = 4, prefillFrames = 4)
        val out = ShortArray(4)
        s.push(info(2) { (it + 1) * 2 })
        s.render(out, 2)
        assertEquals(listOf(0, 0), listOf(out[1].toInt(), out[3].toInt()))
        s.push(info(2) { (it + 3) * 2 })
        s.render(out, 1)
        assertEquals(512, out[1].toInt())
        s.render(out, 2)
        assertEquals(listOf(768, 1024), listOf(out[1].toInt(), out[3].toInt()))
    }

    @Test
    fun `counters distinguish prefill silence from repeated underrun samples`() {
        val stream = PcmStream(1000, 1000, capacity = 8, prefillFrames = 2)
        val out = ShortArray(8)
        stream.push(info(1) { 5 })
        stream.render(out, 2)
        assertEquals(listOf(0, 0, 0, 0), out.take(4).map { it.toInt() })
        assertEquals(2L, stream.outputFrames)
        assertEquals(2L, stream.prefillSilenceFrames)
        assertEquals(0L, stream.underrunFrames)
        stream.push(info(1) { 6 })
        stream.render(out, 4)
        assertEquals(listOf(5, 6, 6, 6).map { it * 256 },
            (0 until 4).map { out[2 * it + 1].toInt() })
        assertEquals(6L, stream.outputFrames)
        assertEquals(2L, stream.prefillSilenceFrames)
        assertEquals(2L, stream.underrunFrames)
        stream.push(info(1) { 7 })
        stream.render(out, 1)
        assertEquals(7 * 256, out[1].toInt())
        assertEquals(7L, stream.outputFrames)
        assertEquals(2L, stream.underrunFrames)
    }

    @Test
    fun `startup reserve must fit in ring`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            PcmStream(1000, 1000, capacity = 4, prefillFrames = 5)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            PcmStream(1000, 1000, capacity = 4, prefillFrames = -1)
        }
    }

    @Test
    fun `two frame startup reserve survives delayed display callback`() {
        val stream = PcmStream(13379, 48000, capacity = 2048, prefillFrames = 448)
        val input = info(224) { 1 }
        val output = ShortArray(480)
        var display = 0L
        var audio = 0L
        var lastDisplay = 0L
        var accumulator = 0L
        var minQueued = Int.MAX_VALUE
        // FixedStepClock's exact GBA frame ratio; model 240-frame (5 ms) sink writes.
        // Other AudioTrack chunk sizes and device scheduling may exhaust this reserve.
        while (audio * 5_000_000L <= 32_000_000_000L) {
            val displayTime = display * 1_000_000_000L / 60 +
                if (display == 1800L) 15_000_000L else 0L
            val audioTime = audio * 5_000_000L
            if (displayTime <= audioTime) {
                accumulator += (displayTime - lastDisplay) * 16_777_216L
                lastDisplay = displayTime
                while (accumulator >= 280_896L * 1_000_000_000L) {
                    accumulator -= 280_896L * 1_000_000_000L
                    stream.push(input)
                }
                display++
            } else {
                stream.render(output, 240)
                if (audioTime in 29_000_000_000L..31_000_000_000L) {
                    minQueued = minOf(minQueued, stream.queued)
                }
                audio++
            }
        }
        assertTrue(minQueued > 0, "queued input around delayed callback: $minQueued")
    }
}
