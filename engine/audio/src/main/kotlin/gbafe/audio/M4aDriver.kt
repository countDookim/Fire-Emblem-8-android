package gbafe.audio

/**
 * One frame of m4a sound, in the original's order: the VBlank IRQ's `m4aSoundVSync`, then
 * `SoundMain` (each player's `MPlayMain` along the `intp` chain, then the mixer), then the mixed
 * samples to [pcm]. Not a port of `SoundMain`: the `ident` lock is not modelled. CGB channels
 * are stepped ([M4aCgb.sound]) into [psg] registers, but no PSG synth plays them yet (M3-13).
 * In the game this runs inside the VBlank (`GameLoop`).
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
    /** PSG registers written by `CgbSound` (input of the PSG synth, still todo). */
    public val psg: M4aPsg = M4aPsg()

    /** The sequencer that runs [chain]; also starts songs (`songNumStart`). */
    public val sequencer: M4aSequencer = M4aSequencer(bus, info)

    /** Runs one frame (VBlank + `SoundMain`) and pushes its samples to [pcm]. */
    public fun frame() {
        vSync()
        main()
    }

    /** `m4aSoundVSync` (start of the VBlank interrupt). */
    public fun vSync() {
        info.vSync()
    }

    /** `m4aSoundMain` (end of the VBlank interrupt): sequencers, `CgbSound`, mixer, then [pcm]. */
    public fun main() {
        for (p in chain) sequencer.step(p)
        M4aCgb.sound(info, psg, bus)
        M4aMixer.frame(info, bus)
        pcm?.push(info)
    }
}
