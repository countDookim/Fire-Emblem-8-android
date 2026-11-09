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
        val ch = playSt.chapterIndex
        val alt = flags.checkFlag(EVFLAG_BGM_CHANGE)
        val blue = if (alt) MAP_BGM_BLUE_GREEN_ALT else MAP_BGM_BLUE
        val red = if (alt) MAP_BGM_RED_ALT else MAP_BGM_RED
        val green = if (alt) MAP_BGM_BLUE_GREEN_ALT else MAP_BGM_GREEN
        return when (playSt.faction) {
            FACTION_RED -> chapters.mapBgmId(ch, red)
            FACTION_GREEN -> chapters.mapBgmId(ch, green)
            FACTION_BLUE -> {
                if (!alt) {
                    val threshold = chapters.victorySongEnemyThreshold(ch)
                    if (battleMapKind() == BattleMapKind.SKIRMISH || threshold != 0) {
                        val alive = units.countUnitsInState(FACTION_RED, UnitTable.US_UNAVAILABLE)
                        val kind = battleMapKind()
                        if ((kind != BattleMapKind.SKIRMISH && alive <= threshold) ||
                            (kind == BattleMapKind.SKIRMISH && alive <= 1)
                        ) {
                            return SONG_GRASP_AT_VICTORY
                        }
                    }
                }
                chapters.mapBgmId(ch, blue)
            }
            else -> 0
        }
    }

    /**
     * Transcribed from src/bm.c: StartMapSongBgm (not verified:
     *   only the Prologue's BGM is traced, the sound state is not compared)
     */
    public fun startMapSongBgm() {
        sound.startBgm(getCurrentMapMusicIndex(), -1)
    }

    private companion object {
        const val EVFLAG_BGM_CHANGE = 4
        const val MAP_BGM_BLUE = 0
        const val MAP_BGM_RED = 1
        const val MAP_BGM_GREEN = 2
        const val MAP_BGM_BLUE_GREEN_ALT = 6
        const val MAP_BGM_RED_ALT = 7
        const val SONG_GRASP_AT_VICTORY = 0x10
        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80
    }
}
