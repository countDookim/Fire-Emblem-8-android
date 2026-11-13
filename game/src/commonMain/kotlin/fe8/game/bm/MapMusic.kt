package fe8.game.bm

import fe8.game.SoundWrapper
import gbafe.core.flags.FlagStore

/**
 * The battle map music choice of `src/bm.c`: [getCurrentMapMusicIndex] and [startMapSongBgm].
 * Each chapter has a table of map songs (`mapBgmIds` in its ROM chapter data) with one slot per
 * phase colour (player, enemy, NPC) plus alternates; the song heard on the map is the slot of
 * the phase that is running, or, in the player phase, a victory song when few enemies are
 * left. The song is chosen again at every phase start (`StartMapSongBgm` is a step of the map
 * main loop, the phase banner and the config menu).
 */
public class MapMusic(
    private val playSt: PlaySt,
    private val chapters: ChapterData,
    private val units: UnitTable,
    private val flags: FlagStore,
    private val sound: SoundWrapper,
    /** `GetBattleMapKind`: story chapter, dungeon or skirmish (`BattleMapKind`). */
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
     * the story sets `EVFLAG_BGM_CHANGE`: slot 6 for player and NPC, slot 7 for enemy). In the
     * player phase, unless that flag is set, the victory song (`SONG_GRASP_AT_VICTORY`) instead
     * once the enemy units still available are down to the chapter's
     * `victorySongEnemyThreshold` (in a story or dungeon chapter with a nonzero threshold) or
     * to 1 (in a skirmish). A faction other than the three returns 0 (the C falls off the end
     * of the function there, so its value is undefined).
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
     * Starts the song the current phase should play: does nothing if that song is already
     * playing, fades the playing one out first, and does nothing if the BGM option is off (all
     * in `StartOrChangeBgm`). The `-1` is the port's value for the C `NULL` music player.
     *
     * Verified against traces: from the stopped state, `gSoundSt` (songId, is_song_playing, unk7,
     * maxChannels) after the call (Prologue, chapter 6). The m4a player state is not compared.
     */
    public fun startMapSongBgm() {
        sound.startBgm(getCurrentMapMusicIndex(), -1)
    }

    private companion object {
        /**
         * `EVFLAG_BGM_CHANGE` (event-flags.h, flag 4): set by chapter events to switch the map
         * to its alternate songs.
         */
        const val EVFLAG_BGM_CHANGE = 4

        /** `MAP_BGM_BLUE` (chapterdata.h): `mapBgmIds` slot 0, the player phase song. */
        const val MAP_BGM_BLUE = 0

        /** `MAP_BGM_RED` (chapterdata.h): `mapBgmIds` slot 1, the enemy phase song. */
        const val MAP_BGM_RED = 1

        /** `MAP_BGM_GREEN` (chapterdata.h): `mapBgmIds` slot 2, the NPC phase song. */
        const val MAP_BGM_GREEN = 2

        /**
         * `MAP_BGM_BLUE_GREEN_ALT` (chapterdata.h): `mapBgmIds` slot 6, the player and NPC
         * phase song once `EVFLAG_BGM_CHANGE` is set.
         */
        const val MAP_BGM_BLUE_GREEN_ALT = 6

        /**
         * `MAP_BGM_RED_ALT` (chapterdata.h): `mapBgmIds` slot 7, the enemy phase song once
         * `EVFLAG_BGM_CHANGE` is set.
         */
        const val MAP_BGM_RED_ALT = 7

        /**
         * `SONG_GRASP_AT_VICTORY` (constants/songs.h, 0x10): the player phase song played when
         * few enemies are left.
         */
        const val SONG_GRASP_AT_VICTORY = 0x10

        /** `FACTION_BLUE` (bmunit.h): the player faction (0x00). */
        const val FACTION_BLUE = 0x00

        /** `FACTION_GREEN` (bmunit.h): the ally NPC faction (0x40). */
        const val FACTION_GREEN = 0x40

        /** `FACTION_RED` (bmunit.h): the enemy faction (0x80). */
        const val FACTION_RED = 0x80
    }
}
