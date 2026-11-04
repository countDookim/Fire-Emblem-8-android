package fe8.game

import fe8.game.title.TitleScreen
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.yield

/**
 * The game control proc `gProcScr_GameControl` (decomp `src/gamecontrol.c`), the root of the game's
 * flow: intro, title, save menu, chapters. Partial: only its start up to the op anim
 * (`ProcScr_GameEarlyStartUI` and `ProcScr_OpAnim`, given as [introScript] and [opAnimScript]) is
 * ported; after that a stand-in `PROC_BLOCK` holds it.
 */
public class GameControl(
    /** The proc engine the game control proc runs on. */
    public val engine: ProcEngine,
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus,
    /** The intro proc script, started as a blocking child. */
    private val introScript: () -> ProcScript,
    /**
     * The op anim (intro cutscene) script `ProcScr_OpAnim`, started as a blocking child after the
     * intro.
     */
    private val opAnimScript: () -> ProcScript,
    /** The title screen (null: a stand-in proc holds the game control proc there). */
    private val titleScreen: (() -> TitleScreen)? = null,
    /** The m4a state the [sound] wrapper drives (null: no sound data). */
    m4a: gba.audio.M4aSound? = null,
) {
    /**
     * `gPlaySt.config` option bits (u32 at `gPlaySt+0x40`); only the sound bits are used so far.
     */
    public var playStConfig: Int = 0

    /** `src/soundwrapper.c` state and calls (`gSoundSt`), reading [playStConfig]. */
    public val sound: SoundWrapper = SoundWrapper(engine, m4a) { playStConfig }
    /** `struct GameCtrlProc` fields after the proc header. */
    public class State {
        /** `nextAction` (`GAME_ACTION_*`). */
        public var nextAction: Int = 0

        /** `nextChapter`. */
        public var nextChapter: Int = 0

        /** `idle_status`. */
        public var idleStatus: Int = 0

        /** `unk_2C`. */
        public var unk2C: Int = 0

        /** `unk_2E`. */
        public var unk2E: Int = 0

        /** `ch_index`. */
        public var chIndex: Int = 0
    }

    /** The script (partial, see the class doc); [ProcScript.newState] creates a [State]. */
    public val script: ProcScript = ProcScript(
        "gProcScr_GameControl",
        listOf(
            name("GAMECTRL"),
            mark(PROC_MARK_GAMECTRL),
            ProcCmd(0x15),
            call(ProcFunc { callEraseSaveEventWithKeyCombo(it) }),
            call(ProcFunc { setUnk2E(it) }),
            repeat(ProcFunc { breakSelf(it) }),
            label(LGAMECTRL_GAME_INTRO_UI),
            startChildBlocking(introScript),
            label(LGAMECTRL_OP_ANIM),
            call(ProcFunc { enableSoundEffects() }),
            startChildBlocking(opAnimScript),
            call(ProcFunc { postIntro(it) }),
            yield(),
            goto(LGAMECTRL_TITLE_DIRECT),
            label(LGAMECTRL_CLASS_REEL),
            block(), // not ported: GamceControl_StartClassReel
            call(ProcFunc { postClassReel(it) }),
            yield(),
            goto(LGAMECTRL_OP_ANIM),
            label(3),
            yield(),
            block(), // not ported: sub_8009A84
            call(ProcFunc { postClassReel(it) }),
            yield(),
            goto(LGAMECTRL_OP_ANIM),
            label(LGAMECTRL_TITLE_DIRECT),
            call(ProcFunc { enableSoundEffects() }),
            call(ProcFunc { startTitleScreenWithMusic(it) }),
            goto(LGAMECTRL_POST_TITLE_IDLE),
            label(24),
            call(ProcFunc { enableSoundEffects() }),
            call(ProcFunc { startTitleScreen(it, 1) }), // StartTitleScreen_FlagTrue
            goto(LGAMECTRL_POST_TITLE_IDLE),
            label(25),
            call(ProcFunc { enableSoundEffects() }),
            call(ProcFunc { startTitleScreen(it, 0) }), // StartTitleScreen_FlagFalse
            goto(LGAMECTRL_POST_TITLE_IDLE),
            label(LGAMECTRL_POST_TITLE_IDLE),
            yield(),
            call(ProcFunc { postIntro(it) }),
            yield(),
            goto(LGAMECTRL_OP_ANIM),
            block(), // not ported: save menu, battle map, chapters, ...
            end(),
        ),
        newState = { State() },
    )

    /**
     * Jumps [proc] to the erase-save label when L + Right + Select are held (no-op while that part
     * of the script is not ported).
     *
     * Partially ported from src/gamecontrol.c: GameControl_CallEraseSaveEventWithKeyCombo
     */
    public fun callEraseSaveEventWithKeyCombo(proc: Proc) {
        if (keys.heldKeys == L_BUTTON or DPAD_RIGHT or SELECT_BUTTON) {
            engine.goto(proc, LGAMECTRL_ERASE_SAVE)
        }
    }

    /**
     * Sets `nextAction` of the running game control proc.
     *
     * Ported from src/gamecontrol.c: SetNextGameActionId
     */
    public fun setNextGameActionId(id: Int) {
        (engine.find(script)!!.state as State).nextAction = id and 0xFF
    }

    /**
     * Clears the `disableBgm` and `disableSoundEffects` option bits.
     *
     * Ported from src/gamecontrol.c: GameControl_EnableSoundEffects
     */
    public fun enableSoundEffects() {
        playStConfig = playStConfig and (CONFIG_DISABLE_BGM or CONFIG_DISABLE_SOUND_EFFECTS).inv()
    }

    /**
     * Stand-in for the title screen (task M4-10): a blocking child that holds the game control
     * proc where the title screen would.
     */
    public val titleStandInScript: ProcScript = ProcScript("gProcScr_TitleScreen (stand-in)", listOf(block()))

    /** `StartTitleScreen_WithMusic`: title in mode 0, then `StartBgmExt(SONG_MAIN_THEME, 0, 0)`. */
    private fun startTitleScreenWithMusic(proc: Proc) {
        startTitleScreen(proc, 0)
        sound.startBgmExt(SoundWrapper.SONG_MAIN_THEME, 0, -1)
    }

    private fun startTitleScreen(proc: Proc, mode: Int) {
        val title = titleScreen
        if (title != null) title().start(proc, mode) else engine.startBlocking(titleStandInScript, proc)
    }

    /**
     * Branches after the op anim or the title screen on `nextAction`: title (skipped), title with
     * flag (played through), class reel or op anim (alternating on `idle_status`), save menu (event
     * return; `sub_8009AD8` is not modelled).
     *
     * Partially ported from src/gamecontrol.c: GameControl_PostIntro
     */
    public fun postIntro(proc: Proc) {
        val st = proc.state as State
        when (st.nextAction) {
            GAME_ACTION_USR_SKIPPED -> engine.goto(proc, LGAMECTRL_TITLE_DIRECT)
            GAME_ACTION_EVENT_RETURN -> engine.goto(proc, LGAMECTRL_EXEC_SAVEMENU)
            GAME_ACTION_CLASS_REEL -> {
                engine.goto(proc, if (st.idleStatus and 1 == 0) LGAMECTRL_CLASS_REEL else LGAMECTRL_OP_ANIM)
                st.idleStatus++
            }
            GAME_ACTION_PLAYED_THROUGH -> engine.goto(proc, 24)
        }
    }

    /**
     * After the class reel: title on event return, op anim on class reel.
     *
     * Ported from src/gamecontrol.c: sub_8009B64
     */
    public fun postClassReel(proc: Proc) {
        when ((proc.state as State).nextAction) {
            GAME_ACTION_EVENT_RETURN -> engine.goto(proc, LGAMECTRL_TITLE_DIRECT)
            GAME_ACTION_CLASS_REEL -> engine.goto(proc, LGAMECTRL_OP_ANIM)
        }
    }

    /**
     * Repeat callback that only breaks: the script continues next frame.
     *
     * Ported from src/gamecontrol.c: GameControl_8009A60_Null
     */
    public fun breakSelf(proc: Proc) {
        engine.breakProc(proc)
    }

    /**
     * Sets `unk_2E` to 20.
     *
     * Ported from src/gamecontrol.c: GameControl_8009A58
     */
    public fun setUnk2E(proc: Proc) {
        (proc.state as State).unk2E = 20
    }

    /**
     * Starts the game control proc on tree 3 with `nextAction = GAME_ACTION_EVENT_RETURN`. The main
     * loop and VBlank routines are set by the caller (they are fixed in [GameLoop]).
     *
     * Ported from src/gamecontrol.c: StartGame
     */
    public fun startGame(): Proc {
        val proc = engine.start(script, 3)
        val st = proc.state as State
        st.nextAction = GAME_ACTION_EVENT_RETURN
        st.nextChapter = 0
        st.idleStatus = 0
        return proc
    }

    /** Constants from `gamecontrol.h` / `proc.h` / `gbaio.h`. */
    public companion object {
        /** `PROC_MARK_GAMECTRL`. */
        public const val PROC_MARK_GAMECTRL: Int = 0x0B

        /** `LGAMECTRL_GAME_INTRO_UI`. */
        public const val LGAMECTRL_GAME_INTRO_UI: Int = 0

        /** `LGAMECTRL_OP_ANIM`. */
        public const val LGAMECTRL_OP_ANIM: Int = 1

        /** `LGAMECTRL_CLASS_REEL`. */
        public const val LGAMECTRL_CLASS_REEL: Int = 2

        /** `LGAMECTRL_TITLE_DIRECT`. */
        public const val LGAMECTRL_TITLE_DIRECT: Int = 4

        /** `LGAMECTRL_EXEC_SAVEMENU`. */
        public const val LGAMECTRL_EXEC_SAVEMENU: Int = 5

        /** `LGAMECTRL_POST_TITLE_IDLE`. */
        public const val LGAMECTRL_POST_TITLE_IDLE: Int = 26

        /** `LGAMECTRL_ERASE_SAVE`. */
        public const val LGAMECTRL_ERASE_SAVE: Int = 18

        /** `GAME_ACTION_EVENT_RETURN`. */
        public const val GAME_ACTION_EVENT_RETURN: Int = 0

        /** `GAME_ACTION_USR_SKIPPED`. */
        public const val GAME_ACTION_USR_SKIPPED: Int = 2

        /** `GAME_ACTION_CLASS_REEL`. */
        public const val GAME_ACTION_CLASS_REEL: Int = 1

        /** `GAME_ACTION_PLAYED_THROUGH`. */
        public const val GAME_ACTION_PLAYED_THROUGH: Int = 3

        /** `gPlaySt.config.disableBgm` bit. */
        public const val CONFIG_DISABLE_BGM: Int = 1 shl 8

        /** `gPlaySt.config.disableSoundEffects` bit. */
        public const val CONFIG_DISABLE_SOUND_EFFECTS: Int = 1 shl 9

        private const val L_BUTTON = 0x200
        private const val DPAD_RIGHT = 0x10
        private const val SELECT_BUTTON = 0x4
    }
}
