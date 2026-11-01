package gbafe.audio

/**
 * An [AudioSource] that runs the m4a [driver] from the audio thread: before each [render] it runs
 * just enough driver frames for [pcm] to hold the requested output, so the music follows the
 * device clock. For sound tests without a game loop (the launcher's "Test music"); the game itself
 * runs the driver on its own 59.73 Hz frames. Not a port; allocates nothing per call.
 */
public class M4aPullSource(
    /** Runs one frame and pushes it to [pcm]. */
    private val driver: M4aDriver,
    /** The stream [driver] pushes to. */
    private val pcm: PcmStream,
) : AudioSource {
    override fun render(buffer: ShortArray, frames: Int) {
        check(driver.info.pcmSamplesPerVBlank > 0) { "sound not initialised (m4aSoundInit)" }
        val needed = (frames.toLong() * pcm.inputRate / pcm.outputRate).toInt() + 2
        while (pcm.queued < needed) driver.frame()
        pcm.render(buffer, frames)
    }
}
