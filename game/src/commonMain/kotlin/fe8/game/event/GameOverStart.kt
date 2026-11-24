package fe8.game.event

import fe8.game.bm.PlaySt
import gbafe.core.flags.FlagStore

/**
 * `sub_80837B0` of `src/eventinfo.c`, the start of the game over (not wired into the game loop:
 * the game over screen itself, PC-8*, and `EventCallGameOverExt`, PC-7b, are not ported yet).
 *
 * @param flags the event flags.
 * @param playSt supplies `config.disableBgm`.
 * @param startBgm `StartBgm(song, NULL)`.
 * @param callGameOverEvent `CallGameOverEvent`, normally [EventEngine.callGameOverEvent].
 */
public class GameOverStart(
    private val flags: FlagStore,
    private val playSt: PlaySt,
    private val startBgm: (song: Int) -> Unit,
    private val callGameOverEvent: () -> Unit,
) {
    /**
     * Sets `EVFLAG_GAMEOVER`, starts the game over song, turns the BGM option off for the rest
     * of the session (the title resets it) and calls the game over event.
     *
     * Ported from src/eventinfo.c: sub_80837B0
     */
    public fun startGameOver() {
        flags.setFlag(EVFLAG_GAMEOVER)
        startBgm(SONG_GAME_OVER)
        playSt.disableBgm = 1
        callGameOverEvent()
    }

    /** Constants of the game over start. */
    public companion object {
        /** `EVFLAG_GAMEOVER` (constants/event-flags.h). */
        public const val EVFLAG_GAMEOVER: Int = 0x65

        /** `SONG_GAME_OVER` (constants/songs.h). */
        public const val SONG_GAME_OVER: Int = 0x3E
    }
}
