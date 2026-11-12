package gba.audio

/**
 * One frame of m4a sound, in the original's order: `m4aSoundVSync`, then `m4aSoundMain`
 * (`SoundMain`: each player's `MPlayMain` along the `intp` chain, `CgbSound`, then the mixer
 * `SoundMainRAM`), then the mixed samples to [pcm]. Not a port of `SoundMain`: the `ident`
 * lock is not modelled. CGB channels are stepped ([M4aCgb.sound]) into [psg] registers, played
 * by a [PsgSynth] mixed in [pcm]. In FE8U both calls are made by the VBlank handler `OnVBlank`
 * (src/bm.c): `m4aSoundVSync` right after `IncrementGameClock`, `m4aSoundMain` last.
 */
public class M4aDriver(
    private val bus: M4aBus,
    /** The shared channels and PCM buffer. */
    public val info: M4aSoundInfo,
    /** Players in `intp` chain order (the order `SoundMain` runs `MPlayMain`). */
    public val chain: List<M4aPlayer>,
    /** Receives each frame's samples; null to only mix. */
    public val pcm: PcmStream? = null,
) {
    /** PSG (CGB sound) registers written by `CgbSound` (played by [PsgSynth]). */
    public val psg: M4aPsg = M4aPsg()

    /** The sequencer that runs [chain]; also starts songs (`songNumStart`). */
    public val sequencer: M4aSequencer = M4aSequencer(bus, info)

    /** Frames run by [main]; sets the phase of the PSG's 512 Hz sequencer (`startAtFrame`). */
    private var frames = 0

    /** Runs one frame (VBlank + `SoundMain`) and pushes its samples to [pcm]. */
    public fun frame() {
        vSync()
        main()
    }

    /** `m4aSoundVSync`: counts down the PCM DMA period ([M4aSoundInfo.vSync]). */
    public fun vSync() {
        info.vSync()
    }

    /** `m4aSoundMain` / `SoundMain`: sequencers, `CgbSound`, mixer, then the samples to [pcm]. */
    public fun main() {
        frames++
        for (i in chain.indices) sequencer.step(chain[i]) // no iterator allocation
        M4aCgb.sound(info, psg, bus)
        M4aMixer.frame(info, bus)
        if (pcm != null && pcm.psg == null && info.pcmFreq > 0) {
            // once: allocation-free afterwards; sequencer phase as if run since boot (frame 0)
            pcm.psg = PsgSynth(psg, info.pcmFreq).also { it.startAtFrame(frames) }
        }
        pcm?.push(info)
    }
}
