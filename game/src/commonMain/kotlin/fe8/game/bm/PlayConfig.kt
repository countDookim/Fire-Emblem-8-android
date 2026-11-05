package fe8.game.bm

/** `PLAY_FLAG_HARD` of `PlaySt.chapterStateBits` (include/types.h). */
public const val PLAY_FLAG_HARD: Int = 1 shl 6

/**
 * Transcribed from src/bmio.c: InitPlayConfig (verified by M5-32's full-image check; not yet)
 *
 * Zeroes [playSt], clears `chapterIndex`, sets the hard flag when [isDifficult] and sets the
 * default option bits, with `config.controller` = [unk] and `config.textSpeed` = 1.
 */
public fun initPlayConfig(playSt: PlaySt, isDifficult: Boolean, unk: Int) {
    playSt.decode(ByteArray(PlaySt.SIZE), 0)
    playSt.chapterIndex = 0
    if (isDifficult) playSt.chapterStateBits = playSt.chapterStateBits or PLAY_FLAG_HARD
    playSt.controller = unk
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
