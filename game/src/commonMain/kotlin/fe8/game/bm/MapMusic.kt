package fe8.game.bm

import fe8.game.SoundWrapper
import gbafe.core.flags.FlagStore

/**
 * The battle map music choice of `src/bm.c`: [getCurrentMapMusicIndex] and [startMapSongBgm].
 */
public class MapMusic(
    private val playSt: PlaySt,
    private val chapters: ChapterData,
    private val units: UnitTable,
    private val flags: FlagStore,
    private val sound: SoundWrapper,
    /** `GetBattleMapKind`. */
    private val battleMapKind: () -> Int,
) {
    /**
     * Ported from src/bm.c: GetCurrentMapMusicIndex
     *
     * Verified against traces: the blue slot lookup (Prologue, chapter 6; the songs start at
     * r19387 / r01754 after the phase switch). The green dump matches its slot, but whether
     * StartMapSongBgm started that song is unchecked. Not covered: red, the `EVFLAG_BGM_CHANGE`
     * alternates and the victory song.
     *
     * The `mapBgmIds` entry of the current chapter for the phase's faction (the alternates after
     * `EVFLAG_BGM_CHANGE`); in the player phase the victory song once the red units alive are
     * down to the chapter's threshold (1 in a skirmish).
     */
    public fun getCurrentMapMusicIndex(): Int {
        val chapterIndex = playSt.chapterIndex
        val isBgmChanged = flags.checkFlag(EVFLAG_BGM_CHANGE)
        val blueBgmSlot = if (isBgmChanged) MAP_BGM_BLUE_GREEN_ALT else MAP_BGM_BLUE
        val redBgmSlot = if (isBgmChanged) MAP_BGM_RED_ALT else MAP_BGM_RED
        val greenBgmSlot = if (isBgmChanged) MAP_BGM_BLUE_GREEN_ALT else MAP_BGM_GREEN
        return when (playSt.faction) {
            FACTION_RED -> chapters.mapBgmId(chapterIndex, redBgmSlot)
            FACTION_GREEN -> chapters.mapBgmId(chapterIndex, greenBgmSlot)
            FACTION_BLUE -> {
                if (!isBgmChanged) {
                    val threshold = chapters.victorySongEnemyThreshold(chapterIndex)
                    if (battleMapKind() == BattleMapKind.SKIRMISH || threshold != 0) {
                        val remainingEnemyCount = units.countUnitsInState(FACTION_RED, UnitTable.US_UNAVAILABLE)
                        val mapKind = battleMapKind()
                        if ((mapKind != BattleMapKind.SKIRMISH && remainingEnemyCount <= threshold) ||
                            (mapKind == BattleMapKind.SKIRMISH && remainingEnemyCount <= 1)
                        ) {
                            return SONG_GRASP_AT_VICTORY
                        }
                    }
                }
                chapters.mapBgmId(chapterIndex, blueBgmSlot)
            }
            else -> 0
        }
    }

    /**
     * Ported from src/bm.c: StartMapSongBgm
     *
     * Verified against traces: from the stopped state, `gSoundSt` (songId, is_song_playing, unk7,
     * maxChannels) after the call (Prologue, chapter 6). The m4a player state is not compared.
     */
    public fun startMapSongBgm() {
        sound.startBgm(getCurrentMapMusicIndex(), -1)
    }

    private companion object {
        /** Event flag indicating that the alternate chapter music should play (flag 4). */
        const val EVFLAG_BGM_CHANGE = 4

        /** Chapter data BGM table slot for default player phase music (0). */
        const val MAP_BGM_BLUE = 0

        /** Chapter data BGM table slot for default enemy phase music (1). */
        const val MAP_BGM_RED = 1

        /** Chapter data BGM table slot for default NPC/ally phase music (2). */
        const val MAP_BGM_GREEN = 2

        /** Chapter data BGM table slot for alternate player/NPC music (6). */
        const val MAP_BGM_BLUE_GREEN_ALT = 6

        /** Chapter data BGM table slot for alternate enemy music (7). */
        const val MAP_BGM_RED_ALT = 7

        /** Song ID for the player phase near-victory theme ("Grasp at Victory", 0x10). */
        const val SONG_GRASP_AT_VICTORY = 0x10

        /** Player faction ID (0x00). */
        const val FACTION_BLUE = 0x00

        /** Ally/NPC faction ID (0x40). */
        const val FACTION_GREEN = 0x40

        /** Enemy faction ID (0x80). */
        const val FACTION_RED = 0x80
    }
}
