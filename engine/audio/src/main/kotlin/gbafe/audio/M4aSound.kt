package gbafe.audio

/**
 * A game's whole m4a sound state: the shared [info] and the `gMPlayTable` [players] (table order),
 * set up by [init] (`m4aSoundInit`), plus its song table for [songNumStart].
 */
public class M4aSound(
    private val bus: M4aBus,
    /** GBA address of `gMPlayTable` (12-byte entries: info, tracks, `unk_8`, pad, `unk_A`). */
    public val mPlayTable: Int,
    /** `NUM_MUSIC_PLAYERS`. */
    public val playerCount: Int,
    /** The game's `gSongTable`. */
    public val songs: M4aSongTable,
    /** DirectSound channel slots (`MAX_DIRECTSOUND_CHANNELS`). */
    channelCount: Int = 12,
) {
    /** `gSoundInfo`. CGB channels are not modelled yet (`MPlayExtender` is skipped). */
    public val info: M4aSoundInfo = M4aSoundInfo(Array(channelCount) { M4aChannel() }, null)

    /** The players in `gMPlayTable` order, each with `unk_8` track slots. */
    public val players: List<M4aPlayer> =
        List(playerCount) { i -> M4aPlayer(Array(maxOf(1, entry(i, 8))) { M4aTrack() }) }

    /** Starts songs ([songNumStart]). */
    public val sequencer: M4aSequencer = M4aSequencer(bus, info)

    /** A driver running the players opened by [init] and the mixer, pushing to [pcm]. */
    public fun driver(pcm: PcmStream?): M4aDriver = M4aDriver(bus, info, info.chain(), pcm)

    /**
     * `m4aSoundInit`: `SoundInit`, `m4aSoundMode([mode])`, then `MPlayOpen` of each `gMPlayTable`
     * player with the entry's `unk_8` tracks and `unk_B` = `unk_A`. The SoundMainRAM copy is not
     * needed; `MPlayExtender` (CGB) and `memAccArea` (`MEMACC`, unsupported) are skipped.
     *
     * Ported from src/m4a.c: m4aSoundInit
     */
    public fun init(mode: Int) {
        info.soundInit()
        info.soundMode(mode)
        for (i in players.indices) {
            info.open(players[i], entry(i, 8))
            players[i].unkB = entry(i, 0x0A)
        }
    }

    /** `m4aSongNumStart(n)`. */
    public fun songNumStart(n: Int): Boolean = sequencer.songNumStart(players, songs, n)

    /**
     * `m4aSongNumStartOrChange(n)`: starts song [n] unless its player already plays it (live
     * tracks, not paused). WIP: not checked against a trace yet.
     */
    public fun songNumStartOrChange(n: Int): Boolean {
        val p = players[songs.player(n and 0xFFFF)]
        val header = songs.header(n and 0xFFFF)
        val playing = p.status and 0xFFFF != 0 && p.status >= 0
        return if (p.songHeader != header || !playing) sequencer.start(p, header) else true
    }

    private fun entry(i: Int, offset: Int): Int = bus.read8(mPlayTable + 12 * i + offset)
}
