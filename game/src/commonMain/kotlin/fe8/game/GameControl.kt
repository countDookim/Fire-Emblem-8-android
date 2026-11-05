package fe8.game

import fe8.game.bm.PlaySt
import fe8.game.title.TitleScreen
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield

/**
 * The game control proc `gProcScr_GameControl` (decomp `src/gamecontrol.c`), the root of the game's
 * flow: intro, title, save menu, chapters. The whole script is transcribed 1:1 (122 commands,
 * checked against the ROM by `GameControlScriptRomTest`); the intro and op anim scripts are given
 * as [introScript] and [opAnimScript], the title as [titleScreen], and every call past the title
 * is a stand-in in [hooks] until it is ported.
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
    /** The stand-ins for the calls past the title that are not ported yet. */
    public val hooks: Hooks = Hooks(),
) {
    /** `gPlaySt`, the play state the game control functions read and write. */
    public val playSt: PlaySt = PlaySt()

    /**
     * `gPlaySt.config` option bits (u32 at `gPlaySt+0x40`) as far as the sound wrapper uses them:
     * only the `disableBgm` and `disableSoundEffects` bits are mapped onto [playSt]; the setter
     * ignores the others.
     */
    public var playStConfig: Int
        get() = (playSt.disableBgm shl 8) or (playSt.disableSoundEffects shl 9)
        set(value) {
            playSt.disableBgm = (value shr 8) and 1
            playSt.disableSoundEffects = (value shr 9) and 1
        }

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

    /** The script (see the class doc); [ProcScript.newState] creates a [State]. */
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
            call2(ProcBoolFunc { startClassReel(it) }), // PROC_CALL_2
            call(ProcFunc { postClassReel(it) }),
            yield(),
            goto(LGAMECTRL_OP_ANIM),
            label(3),
            yield(),
            call(ProcFunc { hooks.sub8009A84.invoke(it) }),
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
            label(LGAMECTRL_EXEC_SAVEMENU),
            call(ProcFunc { enableSoundEffects() }),
            call(ProcFunc { startSaveMenu(it) }),
            yield(),
            call(ProcFunc { switchPostSaveMenu(it) }),
            yield(),
            goto(LGAMECTRL_EXEC_BM),
            label(LGAMECTRL_EXEC_BM_EXT),
            call(ProcFunc { rememberChapterId(it) }),
            yield(),
            call(ProcFunc { hooks.startBattleMap.invoke(it) }),
            yield(),
            goto(LGAMECTRL_POST_NORMAL_CHAPTER),
            label(LGAMECTRL_EXEC_BM),
            call(ProcFunc { checkNewGameAndBranch(it) }),
            call(ProcFunc { checkGameCompleteAndBranch(it) }),
            call(ProcFunc { rememberChapterId(it) }),
            call(ProcFunc { startIntroMonologue(it) }),
            yield(),
            startChildBlocking { hooks.worldMapWrapperScript },
            call(ProcFunc { hooks.endWorldMap.invoke(it) }),
            call(ProcFunc { sub8009E28(it) }),
            yield(),
            call(ProcFunc { hooks.startBattleMap.invoke(it) }),
            yield(),
            label(LGAMECTRL_POST_NORMAL_CHAPTER),
            yield(),
            call(ProcFunc { postChapterSwitch(it) }),
            yield(),
            call(ProcFunc { restoreChapterId(it) }),
            label(19),
            call(ProcFunc { hooks.chapterSwitch.invoke(it) }),
            call(ProcFunc { hooks.callPostChapterSaveMenu.invoke(it) }),
            yield(),
            goto(LGAMECTRL_EXEC_BM),
            label(8),
            call(ProcFunc { sub8009E28(it) }),
            call(ProcFunc { hooks.startResumedGame.invoke(it) }),
            yield(),
            call(ProcFunc { rememberChapterId(it) }),
            call(ProcFunc { handlePostNormalOrExtraChapter(it) }),
            goto(LGAMECTRL_POST_NORMAL_CHAPTER),
            label(14),
            yield(),
            label(LGAMECTRL_POST_EXTRA_MAP),
            yield(),
            goto(LGAMECTRL_TITLE_DIRECT),
            label(LGAMECTRL_EXEC_BM_),
            goto(LGAMECTRL_EXEC_BM),
            label(12),
            call(ProcFunc { hooks.startLinkArenaMainMenu.invoke(it) }),
            yield(),
            call(ProcFunc { hooks.sub8009ABC.invoke(it) }),
            goto(LGAMECTRL_EXEC_SAVEMENU),
            label(16),
            call(ProcFunc { hooks.clearTemporaryUnits.invoke(it) }),
            call(ProcFunc { hooks.declareCompletedChapter.invoke(it) }),
            call(ProcFunc { hooks.makeSaveMenuPostChapter.invoke(it) }),
            yield(),
            label(LGAMECTRL_EXEC_ENDING_SCENE),
            call(ProcFunc { hooks.savePlayThroughData.invoke(it) }),
            call(ProcFunc { hooks.callGameEndingEvent.invoke(it) }),
            yield(),
            whileTrue(ProcBoolFunc { hooks.eventEngineExists.invoke(it) }),
            call(ProcFunc { hooks.startFinScreen.invoke(it) }),
            call(ProcFunc { hooks.setGameEndFlag.invoke(it) }),
            sleep(30),
            goto(LGAMECTRL_TITLE_DIRECT),
            label(LGAMECTRL_ERASE_SAVE),
            call(ProcFunc { hooks.callEraseMural.invoke(it) }),
            call(ProcFunc { hooks.startMidFadeFromBlack.invoke(it) }),
            repeat(ProcFunc { waitForFade(it) }),
            call(ProcFunc { hooks.callEraseSaveEvent.invoke(it) }),
            whileTrue(ProcBoolFunc { hooks.eventEngineExists.invoke(it) }),
            yield(),
            call(ProcFunc { hooks.startMidFadeToBlack.invoke(it) }),
            repeat(ProcFunc { waitForFade(it) }),
            call(ProcFunc { hooks.endMuralBackground.invoke(it) }),
            goto(LGAMECTRL_GAME_INTRO_UI),
            end(),
        ),
        newState = { State() },
    )

    /**
     * Jumps [proc] to the erase-save label when L + Right + Select are held .
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

    /** Holds [proc] with a blocking stand-in child (the stand-in for screens not ported yet). */
    private fun hold(proc: Proc, what: String) {
        engine.startBlocking(ProcScript("$what (stand-in)", listOf(block())), proc)
    }

    private fun startClassReel(proc: Proc): Boolean {
        val hook = hooks.startClassReel
        if (hook != null) return hook.invoke(proc)
        hold(proc, "GamceControl_StartClassReel")
        return false
    }

    private fun startSaveMenu(proc: Proc) {
        val hook = hooks.startSaveMenu
        if (hook != null) hook.invoke(proc) else hold(proc, "StartSaveMenu")
    }

    private fun waitForFade(proc: Proc) {
        val hook = hooks.waitForFade
        if (hook != null) hook.invoke(proc) else engine.breakProc(proc)
    }

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

    /**
     * Stand-ins for the calls of [script] past the title whose functions are not ported yet
     * (each is replaced by its port in the task that ports it); the defaults do nothing, except
     * where noted.
     */
    public class Hooks(
        /**
         * `StartSaveMenu` (save menu, task M5-35): the default holds the game control proc with a
         * blocking child, like the title stand-in.
         */
        public var startSaveMenu: ProcFunc? = null,
        /** `ProcScr_WorldMapWrapper` (the world map), started as a blocking child. */
        public var worldMapWrapperScript: ProcScript =
            ProcScript("ProcScr_WorldMapWrapper (stand-in)", listOf(end())),
        /** `EventEngineExists` for `PROC_WHILE`: the event engine is not ported, so false. */
        public var eventEngineExists: ProcBoolFunc = ProcBoolFunc { false },
        /** `WaitForFade` for `PROC_REPEAT`: the default breaks at once. */
        public var waitForFade: ProcFunc? = null,
        /**
         * `GamceControl_StartClassReel` (`PROC_CALL_2`, class reel, task M5-35): the default holds
         * the game control proc with a blocking child, like the title stand-in.
         */
        public var startClassReel: ProcBoolFunc? = null,
        /** `sub_8009A84`. Stand-in until its port. */
        public var sub8009A84: ProcFunc = ProcFunc { },
        /** `StartBattleMap`. Stand-in until its port. */
        public var startBattleMap: ProcFunc = ProcFunc { },
        /** `EndWM`. Stand-in until its port. */
        public var endWorldMap: ProcFunc = ProcFunc { },
        /** `GameControl_ChapterSwitch`. Stand-in until its port. */
        public var chapterSwitch: ProcFunc = ProcFunc { },
        /** `GameControl_CallPostChapterSaveMenu`. Stand-in until its port. */
        public var callPostChapterSaveMenu: ProcFunc = ProcFunc { },
        /** `GameCtrl_StartResumedGame`. Stand-in until its port. */
        public var startResumedGame: ProcFunc = ProcFunc { },
        /** `StartLinkArenaMainMenu`. Stand-in until its port. */
        public var startLinkArenaMainMenu: ProcFunc = ProcFunc { },
        /** `sub_8009ABC`. Stand-in until its port. */
        public var sub8009ABC: ProcFunc = ProcFunc { },
        /** `ClearTemporaryUnits`. Stand-in until its port. */
        public var clearTemporaryUnits: ProcFunc = ProcFunc { },
        /** `GameCtrl_DeclareCompletedChapter`. Stand-in until its port. */
        public var declareCompletedChapter: ProcFunc = ProcFunc { },
        /** `Make6C_SaveMenuPostChapter` (the post-chapter save menu). Stand-in until its port. */
        public var makeSaveMenuPostChapter: ProcFunc = ProcFunc { },
        /** `GameCtrl_SavePlayThroughData`. Stand-in until its port. */
        public var savePlayThroughData: ProcFunc = ProcFunc { },
        /** `CallGameEndingEvent` (the ending / credits). Stand-in until its port. */
        public var callGameEndingEvent: ProcFunc = ProcFunc { },
        /** `StartFinScreen`. Stand-in until its port. */
        public var startFinScreen: ProcFunc = ProcFunc { },
        /** `_SetGameEndFlag`. Stand-in until its port. */
        public var setGameEndFlag: ProcFunc = ProcFunc { },
        /** `GameControl_CallEraseMural` (erase-save). Stand-in until its port. */
        public var callEraseMural: ProcFunc = ProcFunc { },
        /** `StartMidFadeFromBlack`. Stand-in until its port. */
        public var startMidFadeFromBlack: ProcFunc = ProcFunc { },
        /** `CallEraseSaveEvent`. Stand-in until its port. */
        public var callEraseSaveEvent: ProcFunc = ProcFunc { },
        /** `StartMidFadeToBlack`. Stand-in until its port. */
        public var startMidFadeToBlack: ProcFunc = ProcFunc { },
        /** `EndMuralBackground`. Stand-in until its port. */
        public var endMuralBackground: ProcFunc = ProcFunc { },
        /** `GmDataInit` (world map data). Stand-in until its port. */
        public var gmDataInit: ProcFunc = ProcFunc { },
        /** `StartIntroMonologue`. Stand-in until its port. */
        public var startIntroMonologue: ProcFunc = ProcFunc { },
        /** `EndAllMus`. Stand-in until its port. */
        public var endAllMus: ProcFunc = ProcFunc { },
        /**
         * The three blend calls of `sub_8009E28` (`SetBlendConfig(3, 0, 0, 16)`,
         * `SetBlendTargetA(1, 1, 1, 1, 1)`, `SetBlendBackdropA(1)`) on the LCD control buffer.
         * Stand-in until the buffer is shared with [GameControl].
         */
        public var blendSetup: ProcFunc = ProcFunc { },
    )

    /**
     * Branches after the save menu on `nextAction`; on event return it first runs `GmDataInit`
     * ([Hooks.gmDataInit]) and goes to the battle map label.
     *
     * Transcribed from src/gamecontrol.c: GameControl_SwitchPostSaveMenu
     * (not verified: only the event-return branch occurs in the reference traces)
     */
    public fun switchPostSaveMenu(proc: Proc) {
        when ((proc.state as State).nextAction) {
            GAME_ACTION_EVENT_RETURN -> {
                hooks.gmDataInit.invoke(proc)
                engine.goto(proc, LGAMECTRL_EXEC_BM)
            }
            GAME_ACTION_CLASS_REEL, GAME_ACTION_USR_SKIPPED, GAME_ACTION_PLAYED_THROUGH ->
                engine.goto(proc, LGAMECTRL_EXEC_BM)
            GAME_ACTION_4 -> engine.goto(proc, LGAMECTRL_RESUMED_GAME)
            GAME_ACTION_5 -> engine.goto(proc, LGAMECTRL_TITLE_FLAG_FALSE)
            GAME_ACTION_6 -> engine.goto(proc, LGAMECTRL_LINK_ARENA)
            GAME_ACTION_EXTRA_MAP -> engine.goto(proc, LGAMECTRL_EXTRA_MAP)
            GAME_ACTION_C -> engine.goto(proc, LGAMECTRL_EXEC_BM_)
            else -> Unit
        }
    }

    /**
     * Skips to the direct battle map label when the save menu type is 2 or 4.
     *
     * Transcribed from src/gamecontrol.c: GameCtrl_CheckNewGameAndBranch
     * (not verified: no reference run has save_menu_type 2 or 4)
     */
    public fun checkNewGameAndBranch(proc: Proc) {
        if (playSt.saveMenuType == 2 || playSt.saveMenuType == 4) {
            engine.goto(proc, LGAMECTRL_EXEC_BM_EXT)
        }
    }

    /**
     * Goes to the ending scene when the game is complete and not already in the post game.
     *
     * Transcribed from src/gamecontrol.c: GameCtrl_CheckGameCompleteAndBranch
     * (not verified: no reference run has the complete state bit)
     */
    public fun checkGameCompleteAndBranch(proc: Proc) {
        if (playSt.chapterStateBits and PLAY_FLAG_POSTGAME != 0) return
        if (playSt.chapterStateBits and PLAY_FLAG_COMPLETE == 0) return
        engine.goto(proc, LGAMECTRL_EXEC_ENDING_SCENE)
    }

    /**
     * Copies `gPlaySt.chapterIndex` to `ch_index`.
     *
     * Ported from src/gamecontrol.c: GameControl_RememberChapterId
     */
    public fun rememberChapterId(proc: Proc) {
        (proc.state as State).chIndex = playSt.chapterIndex and 0xFF
    }

    /**
     * Copies `ch_index` back to `gPlaySt.chapterIndex` (an s8).
     *
     * Ported from src/gamecontrol.c: GameControl_RestoreChapterId
     */
    public fun restoreChapterId(proc: Proc) {
        playSt.chapterIndex = (proc.state as State).chIndex.toByte().toInt()
    }

    /**
     * Starts the intro monologue ([Hooks.startIntroMonologue]) in chapter 0 unless in the post
     * game.
     *
     * Ported from src/gamecontrol.c: GameCtrlStartIntroMonologue
     */
    public fun startIntroMonologue(proc: Proc) {
        if (playSt.chapterStateBits and PLAY_FLAG_POSTGAME != 0) return
        if (playSt.chapterIndex != 0) return
        hooks.startIntroMonologue.invoke(proc)
    }

    /**
     * Sets the alpha blend (`SetBlendConfig(3, 0, 0, 16)`, `SetBlendTargetA(1, 1, 1, 1, 1)`,
     * `SetBlendBackdropA(1)`) through [Hooks.blendSetup], the LCD control buffer not being owned
     * here.
     *
     * Partially ported from src/gamecontrol.c: sub_8009E28
     */
    public fun sub8009E28(proc: Proc) {
        hooks.blendSetup.invoke(proc)
    }

    /**
     * Ends all music ([Hooks.endAllMus]) then branches on `nextAction`: title on event return,
     * label 16 (ending scene) when played through.
     *
     * Partially ported from src/gamecontrol.c: GameControl_PostChapterSwitch
     */
    public fun postChapterSwitch(proc: Proc) {
        hooks.endAllMus.invoke(proc)
        when ((proc.state as State).nextAction) {
            GAME_ACTION_EVENT_RETURN -> engine.goto(proc, LGAMECTRL_TITLE_DIRECT)
            GAME_ACTION_PLAYED_THROUGH -> engine.goto(proc, LGAMECTRL_POST_CHAPTER_ENDING)
            else -> Unit
        }
    }

    /**
     * Goes to the extra map label when `PLAY_FLAG_EXTRA_MAP` is set, else to the normal chapter
     * label.
     *
     * Ported from src/gamecontrol.c: GameControlHandlePostNormalOrExtraChapter
     */
    public fun handlePostNormalOrExtraChapter(proc: Proc) {
        engine.goto(
            proc,
            if (playSt.chapterStateBits and PLAY_FLAG_EXTRA_MAP != 0) {
                LGAMECTRL_POST_EXTRA_MAP
            } else {
                LGAMECTRL_POST_NORMAL_CHAPTER
            },
        )
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

        /** `LGAMECTRL_EXEC_BM_EXT`. */
        public const val LGAMECTRL_EXEC_BM_EXT: Int = 6

        /** `LGAMECTRL_EXEC_BM`. */
        public const val LGAMECTRL_EXEC_BM: Int = 7

        /** `LGAMECTRL_POST_NORMAL_CHAPTER`. */
        public const val LGAMECTRL_POST_NORMAL_CHAPTER: Int = 9

        /** `LGAMECTRL_POST_EXTRA_MAP`. */
        public const val LGAMECTRL_POST_EXTRA_MAP: Int = 10

        /** `LGAMECTRL_EXEC_BM_`. */
        public const val LGAMECTRL_EXEC_BM_: Int = 15

        /** `LGAMECTRL_EXEC_ENDING_SCENE`. */
        public const val LGAMECTRL_EXEC_ENDING_SCENE: Int = 17

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

        /** `GAME_ACTION_4`. */
        public const val GAME_ACTION_4: Int = 4

        /** `GAME_ACTION_5`. */
        public const val GAME_ACTION_5: Int = 5

        /** `GAME_ACTION_6`. */
        public const val GAME_ACTION_6: Int = 6

        /** `GAME_ACTION_EXTRA_MAP`. */
        public const val GAME_ACTION_EXTRA_MAP: Int = 7

        /** `GAME_ACTION_C`. */
        public const val GAME_ACTION_C: Int = 0xC

        /** `PLAY_FLAG_POSTGAME`. */
        public const val PLAY_FLAG_POSTGAME: Int = 1 shl 2

        /** `PLAY_FLAG_COMPLETE`. */
        public const val PLAY_FLAG_COMPLETE: Int = 1 shl 5

        /** `PLAY_FLAG_EXTRA_MAP`. */
        public const val PLAY_FLAG_EXTRA_MAP: Int = 1 shl 7

        /** `gPlaySt.config.disableBgm` bit. */
        public const val CONFIG_DISABLE_BGM: Int = 1 shl 8

        /** `gPlaySt.config.disableSoundEffects` bit. */
        public const val CONFIG_DISABLE_SOUND_EFFECTS: Int = 1 shl 9

        // Unnamed script labels (the numbers of the decomp's `PROC_LABEL`s).
        private const val LGAMECTRL_RESUMED_GAME = 8
        private const val LGAMECTRL_LINK_ARENA = 12
        private const val LGAMECTRL_EXTRA_MAP = 14
        private const val LGAMECTRL_POST_CHAPTER_ENDING = 16
        private const val LGAMECTRL_TITLE_FLAG_FALSE = 25

        private const val L_BUTTON = 0x200
        private const val DPAD_RIGHT = 0x10
        private const val SELECT_BUTTON = 0x4
    }
}
