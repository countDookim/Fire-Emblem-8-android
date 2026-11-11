package fe8.testing

import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Sanity checks of the harness `--audio` capture (`audio_menu`) that the audio level tests use. */
class MgbaAudioReferenceTest {
    private val run = ReferenceData.fromEnvironment().run("audio_menu")

    @Test
    fun `audio capture is 32768 Hz, frame-aligned and not silent once the music starts`() {
        assumeTrue(run.hasAudio, "audio_menu reference run not present")
        val end = run.audioEnd
        val steps = end.size - 1
        assertTrue(steps == 1340, "steps $steps")
        assertTrue(end[steps] * 2 == run.audio.size, "index matches samples")
        val perFrame = end[steps].toDouble() / steps
        // 32768 Hz / 59.7275 Hz (GBA frame) = 548.6 samples per frame.
        assertTrue(perFrame in 548.0..549.2, "samples per frame $perFrame")
        val title = rms(run.audio, end[1000], end[1300])
        assertTrue(title > 500.0, "title/menu RMS $title")
    }

    private fun rms(a: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in 2 * from until 2 * to) sum += a[i].toDouble() * a[i]
        return sqrt(sum / (2 * (to - from)))
    }

    /**
     * Level check of the DirectSound path: mGBA's output is `48 * (psg + 4 * s8)` at master
     * volume 0x100 (`GBAAudioSample`, `_applyBias`), so the game's PCM bytes in the
     * `ram_trace_menu_cgb` dumps (same input) predict the DirectSound part of `audio_menu`. The
     * rest of the energy is the PSG (menu SEs on CGB channels): positive, and smaller.
     */
    @Test
    fun `DirectSound bytes from the RAM traces explain most of mGBA's output level`() {
        val trace = ReferenceData.fromEnvironment().run("ram_trace_menu_cgb")
        assumeTrue(run.hasAudio && trace.dumps.isNotEmpty(), "reference runs not present")
        val pcm = 0x03005760 - GbaMem.IWRAM // gSoundInfo.pcmBuffer (L, then R)
        var sum = 0.0
        var n = 0
        for ((name, frame) in trace.dumps) {
            if (frame !in 900 until 1100) continue
            val iw = trace.region(name, "iwram")
            for (i in pcm until pcm + 2 * 0x630) {
                val v = 48.0 * 4 * iw[i]
                sum += v * v
                n++
            }
        }
        val ds = sqrt(sum / n)
        val end = run.audioEnd
        val mgba = rms(run.audio, end[900], end[1100])
        val psg = sqrt(mgba * mgba - ds * ds)
        assertTrue(ds / mgba in 0.8..1.0, "DirectSound RMS $ds vs mGBA $mgba")
        assertTrue(psg > 0.2 * mgba, "PSG share $psg of $mgba")
    }
}
