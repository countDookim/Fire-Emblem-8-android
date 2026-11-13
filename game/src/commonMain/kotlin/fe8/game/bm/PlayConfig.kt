package fe8.game.bm

/**
 * `PLAY_FLAG_HARD` (include/types.h) in `PlaySt.chapterStateBits`: the playthrough was started
 * on the difficult (hard) mode. [initPlayConfig] sets it at new game; `IsDifficultMode` and the
 * ranking thresholds (src/gamerankings.c) read it.
 */
public const val PLAY_FLAG_HARD: Int = 1 shl 6

/**
 * Ported from src/bmio.c: InitPlayConfig
 *
 * Resets [playSt] for a new game: zeroes the whole struct, so every chapter, gold and ranking
 * field starts empty, sets [PLAY_FLAG_HARD] when [isDifficult], and gives the options screen
 * settings their defaults (text speed 1, everything else off or 0). The C writes each option
 * explicitly after zeroing, which is why the fields below are all assigned.
 *
 * @param playSt the player state (`gPlaySt`) to initialize.
 * @param isDifficult whether the difficult (hard) mode was chosen (`isDifficult` in the C).
 * @param controllerMode value for `config.controller` (`unk` in the C; its meaning is unknown,
 *   see [PlaySt.controller]).
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
