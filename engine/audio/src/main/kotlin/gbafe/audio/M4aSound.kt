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
    /** `gSoundInfo` with `gCgbChans` (set up by [init]; `CgbSound` is not modelled yet). */
    public val info: M4aSoundInfo =
        M4aSoundInfo(Array(channelCount) { M4aChannel() }, Array(4) { M4aChannel() })

    /** The players in `gMPlayTable` order, each with `unk_8` track slots. */
    public val players: List<M4aPlayer> =
        List(playerCount) { i -> M4aPlayer(Array(maxOf(1, entry(i, 8))) { M4aTrack() }) }

    /** Starts songs ([songNumStart]). */
    public val sequencer: M4aSequencer = M4aSequencer(bus, info)

    /** A driver running the players opened by [init] and the mixer, pushing to [pcm]. */
    public fun driver(pcm: PcmStream?): M4aDriver = M4aDriver(bus, info, info.chain(), pcm)

    /**
     * `m4aSoundInit`: `SoundInit`, [mPlayExtender], `m4aSoundMode([mode])`, then `MPlayOpen` of each `gMPlayTable`
     * player with the entry's `unk_8` tracks and `unk_B` = `unk_A`. The SoundMainRAM copy is not
     * needed; `memAccArea` (`MEMACC`, unsupported) is skipped.
     *
     * Ported from src/m4a.c: m4aSoundInit
     */
    public fun init(mode: Int) {
        info.soundInit()
        mPlayExtender()
        info.soundMode(mode)
        for (i in players.indices) {
            info.open(players[i], entry(i, 8))
            players[i].unkB = entry(i, 0x0A)
        }
    }

    /**
     * `MPlayExtender(gCgbChans)`: if unlocked, clears the four CGB channels and gives each its
     * type (1..4) and `panMask` (0x11, 0x22, 0x44, 0x88). The sound register writes, the jump
     * table entries (all commands are built in) and the `CgbSound`/`CgbOscOff` hooks are not
     * modelled; `maxLines` is FE8's 0 (not modelled either).
     *
     * Ported from src/m4a.c: MPlayExtender
     */
    public fun mPlayExtender() {
        if (info.ident != M4aPlayer.ID_NUMBER) return
        val cgb = info.cgbChans ?: return
        for (i in cgb.indices) {
            cgb[i].apply {
                // CpuFill32(0): every field, then the type and pan mask.
                status = 0
                rightVolume = 0
                leftVolume = 0
                adsr = 0
                key = 0
                envelopeVolume = 0
                envelopeRight = 0
                envelopeLeft = 0
                echoVolume = 0
                echoLength = 0
                gateTime = 0
                midiKey = 0
                velocity = 0
                priority = 0
                rhythmPan = 0
                count = 0
                fw = 0
                length = 0
                sweep = 0
                frequency = 0
                wav = 0
                currentPointer = 0
                track = null
                prev = null
                next = null
                type = i + 1
                panMask = 0x11 shl i
            }
        }
    }

    /** `m4aSongNumStart(n)`. */
    public fun songNumStart(n: Int): Boolean = sequencer.songNumStart(players, songs, n)

    /**
     * `m4aSongNumStartOrChange(n)`: starts song [n] unless its player already plays it (live
     * tracks, not paused). Not a verified port: FE8 never calls it (no caller in the decomp), so no
     * trace can check it.
     */
    public fun songNumStartOrChange(n: Int): Boolean {
        val p = players[songs.player(n and 0xFFFF)]
        val header = songs.header(n and 0xFFFF)
        val playing = p.status and 0xFFFF != 0 && p.status >= 0
        return if (p.songHeader != header || !playing) sequencer.start(p, header) else true
    }

    private fun entry(i: Int, offset: Int): Int = bus.read8(mPlayTable + 12 * i + offset)
}
