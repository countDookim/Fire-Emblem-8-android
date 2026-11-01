package gbafe.audio

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class M4aPullSourceTest {
    @Test
    fun `runs driver frames on demand and keeps the queue short`() {
        val info = M4aSoundInfo(Array(12) { M4aChannel() }, null)
        info.soundInit()
        info.soundMode(M4aSoundInfo.FREQ_13379 or (13 shl 12) or (7 shl 8))
        val pcm = PcmStream(info.pcmFreq, 48000)
        val driver = M4aDriver({ 0 }, info, emptyList(), pcm)
        val source = M4aPullSource(driver, pcm)
        val out = ShortArray(2 * 800)
        repeat(600) {
            val before = pcm.queued
            source.render(out, 800)
            assertTrue(pcm.queued in 0..info.pcmSamplesPerVBlank + 2, "queued ${pcm.queued} (was $before)")
        }
    }
}
