package fe8.game.event

import fe8.game.GameControl
import fe8.game.bm.PlaySt
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield

/**
 * `EventCallGameOverExt` and its proc `ProcScr_BmGameOver` of `src/eventcall.c`: the game over
 * sequence the lord-death event script (`EventScr_GameOver`) runs through `ASMC`. It waits, fades
 * the music and the screen out, ends the MUs, shows the game over screen and then returns to the
 * title (not wired into the game loop: that is PC-8).
 *
 * @param engine the proc engine the sequence runs on.
 * @param playSt supplies `chapterStateBits` and `config.disableBgm`.
 * @param fadeOutBgm `Sound_FadeOutBGM`.
 * @param startSlowFadeToBlack `StartSlowFadeToBlack`, normally `ScreenFade.startSlowFadeToBlack`.
 * @param endAllMus `EndAllMus`, normally `Mu.endAllMus`.
 * @param startGameOverScreen `StartGameOverScreen(parent)`: starts `ProcScr_GameOverScreen`
 *   blocking the given proc (PC-8a; a stand-in until then).
 * @param setNextGameActionId `SetNextGameActionId`.
 * @param endBMapMain `EndBMapMain`.
 * @param deleteEventEngines `DeleteEventEngines`, normally [EventEngine.deleteEventEngines].
 */
public class GameOverCaller(
    private val engine: ProcEngine,
    private val playSt: PlaySt,
    private val fadeOutBgm: (speed: Int) -> Unit,
    private val startSlowFadeToBlack: () -> Unit,
    private val endAllMus: () -> Unit,
    private val startGameOverScreen: (parent: Proc) -> Unit,
    private val setNextGameActionId: (id: Int) -> Unit,
    private val endBMapMain: () -> Unit,
    private val deleteEventEngines: () -> Unit,
) {
    /**
     * `ProcScr_BmGameOver`: sleeps 30 frames, fades the music, sleeps 10, fades the screen to
     * black, sleeps 80, ends the MUs, skips the screen for the tutorial and extra maps, shows the
     * game over screen and, after it ends (label 0), cleans up.
     *
     * Ported from src/eventcall.c: ProcScr_BmGameOver
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_BmGameOver",
        listOf(
            sleep(0x1E),
            call(ProcFunc { gameOverFadeOutCurrentBgm() }),
            sleep(0xA),
            call(ProcFunc { startSlowFadeToBlack() }),
            sleep(0x50),
            call(ProcFunc { endAllMus() }),
            call(ProcFunc { skilGameOverForToturialExtraMap(it) }),
            call(ProcFunc { startGameOverScreen(it) }),
            yield(),
            label(LABEL_POST_GAME_OVER),
            call(ProcFunc { postGameOverHandler() }),
            end(),
        ),
    )

    /**
     * Starts [script] blocking [proc] (the event engine proc running `EventScr_GameOver`).
     *
     * Ported from src/eventcall.c: EventCallGameOverExt
     */
    public fun eventCallGameOverExt(proc: Proc) {
        engine.startBlocking(script, proc)
    }

    /**
     * Fades the music out when the chapter is the tutorial or the BGM option is on.
     *
     * Transcribed from src/eventcall.c: GameOver_FadeOutCurrentBgm (not verified: PC-cap2 only
     * runs the condition false, `disableBgm = 1` since `sub_80837B0`; the fade call is not taken)
     */
    public fun gameOverFadeOutCurrentBgm() {
        val tutorial = (playSt.chapterStateBits and PLAY_FLAG_TUTORIAL) != 0
        if (tutorial || playSt.disableBgm == 0) fadeOutBgm(FADE_OUT_BGM_SPEED)
    }

    /**
     * Skips the game over screen (goes to label 0) in the tutorial and extra maps.
     *
     * Transcribed from src/eventcall.c: SkilGameOverForToturialExtraMap (not verified: PC-cap2
     * is neither a tutorial nor an extra map; the goto is not taken)
     */
    public fun skilGameOverForToturialExtraMap(proc: Proc) {
        val skipBits = PLAY_FLAG_TUTORIAL or GameControl.PLAY_FLAG_EXTRA_MAP
        if ((playSt.chapterStateBits and skipBits) != 0) engine.goto(proc, LABEL_POST_GAME_OVER)
    }

    /**
     * Returns to the title: `GAME_ACTION_EVENT_RETURN`, ends the battle map and the event engines.
     *
     * Ported from src/eventcall.c: PostGameOverHandler
     */
    public fun postGameOverHandler() {
        setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
        endBMapMain()
        deleteEventEngines()
    }

    /** Constants of the game over caller. */
    public companion object {
        /** `PLAY_FLAG_TUTORIAL` (include/types.h). */
        public const val PLAY_FLAG_TUTORIAL: Int = 1 shl 3

        /** `Sound_FadeOutBGM(4)`: the speed of the music fade. */
        public const val FADE_OUT_BGM_SPEED: Int = 4

        /** `PROC_LABEL(0x0)` of [script]. */
        public const val LABEL_POST_GAME_OVER: Int = 0
    }
}
