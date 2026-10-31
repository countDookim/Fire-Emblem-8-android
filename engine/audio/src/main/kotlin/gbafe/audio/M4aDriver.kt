package gbafe.audio

/**
 * One frame of m4a sound, in the original's order: the VBlank IRQ's `m4aSoundVSync`, then
 * `SoundMain` (each player's `MPlayMain` along the `intp` chain, then the mixer), then the mixed
 * samples to [pcm]. Not a port of `SoundMain`: `CgbSound` (CGB channels) and the `ident` lock
 * are not modelled yet.
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
    /** The sequencer that runs [chain]; also starts songs (`songNumStart`). */
    public val sequencer: M4aSequencer = M4aSequencer(bus, info)

    /** Runs one frame (VBlank + `SoundMain`) and pushes its samples to [pcm]. */
    public fun frame() {
        info.vSync()
        for (p in chain) sequencer.step(p)
        M4aMixer.frame(info, bus)
        pcm?.push(info)
    }
}
