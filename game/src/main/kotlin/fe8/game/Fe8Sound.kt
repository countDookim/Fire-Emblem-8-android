package fe8.game

import gbafe.audio.M4aBus
import gbafe.audio.M4aSongTable
import gbafe.audio.M4aSound
import gbafe.audio.M4aSoundInfo

/** FE8's m4a configuration (addresses from the decomp's symbol map, mode from `m4aSoundInit`). */
public object Fe8Sound {
    /** `gMPlayTable`. */
    public const val M_PLAY_TABLE: Int = 0x08224404

    /** `gSongTable`. */
    public const val SONG_TABLE: Int = 0x08224470

    /** `NUM_MUSIC_PLAYERS`. */
    public const val PLAYERS: Int = 9

    /** Entries of `gSongTable`. */
    public const val SONGS: Int = 1000

    /** `SONG_OPENING` (song 1): the op anim / title BGM. */
    public const val SONG_OPENING: Int = 1

    /** `m4aSoundInit` mode: DA_BIT_8 | FREQ_13379 | 13 << MASVOL | 7 << MAXCHN. */
    public const val MODE: Int = 0x900000 or M4aSoundInfo.FREQ_13379 or (13 shl 12) or (7 shl 8)

    /** The game's sound state over [rom] (whole ROM or [RomView.SOUND_WINDOWS]), initialised. */
    public fun create(rom: RomView): M4aSound {
        val bus = M4aBus { rom.u8(it) }
        return M4aSound(bus, M_PLAY_TABLE, PLAYERS, M4aSongTable(bus, SONG_TABLE, SONGS))
            .also { it.init(MODE) }
    }
}
