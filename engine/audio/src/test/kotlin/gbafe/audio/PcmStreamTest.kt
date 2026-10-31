package gbafe.audio

import org.junit.jupiter.api.Assertions.assertEquals
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
}
