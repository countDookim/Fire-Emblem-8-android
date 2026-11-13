package fe8.game

import gba.audio.M4aBus
import gba.audio.M4aSongTable
import gba.audio.M4aSound
import gba.audio.M4aSoundInfo

/** FE8's m4a configuration (addresses from the decomp's symbol map, mode from `m4aSoundInit`). */
public object Fe8Sound {
    /** ROM address of `gMPlayTable` (one 12-byte entry per music player). */
    public const val M_PLAY_TABLE: Int = 0x08224404

    /** ROM address of `gSongTable`. */
    public const val SONG_TABLE: Int = 0x08224470

    /** Number of music players (`NUM_MUSIC_PLAYERS`, the entries between the two tables). */
    public const val PLAYERS: Int = 9

    /** Entries in `gSongTable`. */
    public const val SONGS: Int = 1000

    /**
     * The mode `m4aSoundInit` (src/m4a.c) passes to `m4aSoundMode`: `SOUND_MODE_DA_BIT_8 |
     * SOUND_MODE_FREQ_13379 | (13 << MASVOL_SHIFT) | (7 << MAXCHN_SHIFT)`.
     */
    public const val MODE: Int = 0x900000 or M4aSoundInfo.FREQ_13379 or (13 shl 12) or (7 shl 8)

    /** The game's sound state over [rom] (whole ROM or [RomView.SOUND_WINDOWS]), initialised. */
    public fun create(rom: RomView): M4aSound {
        val bus = M4aBus { address -> rom.u8(address) }
        return M4aSound(bus, M_PLAY_TABLE, PLAYERS, M4aSongTable(bus, SONG_TABLE, SONGS))
            .also { it.init(MODE) }
    }
}
