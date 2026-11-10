package fe8.game.bm

/**
 * `PLAY_FLAG_HARD` flag in `PlaySt.chapterStateBits` (include/types.h).
 *
 * Indicates that the playthrough is running in Difficult / Hard mode.
 */
public const val PLAY_FLAG_HARD: Int = 1 shl 6

/**
 * Ported from src/bmio.c: InitPlayConfig
 *
 * Initializes gameplay options and state configuration for a new game or chapter restart.
 * Zeroes [playSt], resets `chapterIndex` to 0, sets the [PLAY_FLAG_HARD] flag when [isDifficult] is true,
 * and sets default option settings (`controller` = [controllerMode], `textSpeed` = 1, all others 0).
 *
 * @param playSt the battle map player state struct to initialize.
 * @param isDifficult true if playing on Difficult (Hard) mode.
 * @param controllerMode initial controller configuration mode (`unk` in decomp).
 */
public fun initPlayConfig(playSt: PlaySt, isDifficult: Boolean, controllerMode: Int) {
    playSt.decode(ByteArray(PlaySt.SIZE), 0)
    playSt.chapterIndex = 0
    if (isDifficult) playSt.chapterStateBits = playSt.chapterStateBits or PLAY_FLAG_HARD
    playSt.controller = controllerMode
    playSt.animationType = 0
    playSt.disableTerrainDisplay = 0
    playSt.unitDisplayType = 0
    playSt.autoCursor = 0
    playSt.textSpeed = 1
    playSt.gameSpeed = 0
    playSt.disableBgm = 0
    playSt.disableSoundEffects = 0
    playSt.windowColor = 0
    playSt.disableAutoEndTurns = 0
    playSt.noSubtitleHelp = 0
    playSt.battleForecastType = 0
    playSt.debugControlRed = 0
    playSt.debugControlGreen = 0
    playSt.unitColor = 0
    playSt.unk41x5 = 0
}
