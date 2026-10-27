package fe8.game

import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.startChildBlocking

/**
 * The game control proc `gProcScr_GameControl` (decomp `src/gamecontrol.c`), the root of the game's flow: intro,
 * title, save menu, chapters. Partial: only its start up to the intro (`ProcScr_GameEarlyStartUI`, given as
 * [introScript]) is ported; after the intro a stand-in `PROC_BLOCK` holds it where the op anim would start.
 */
public class GameControl(
    /** The proc engine the game control proc runs on. */
    public val engine: ProcEngine,
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus,
    /** The intro proc script, started as a blocking child. */
    private val introScript: () -> ProcScript,
) {
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
            block(), // not ported: GameControl_EnableSoundEffects, ProcScr_OpAnim, ...
            end(),
        ),
        newState = { State() },
    )

    /**
     * Jumps [proc] to the erase-save label when L + Right + Select are held (no-op while that part of the script
     * is not ported).
     *
     * Ported from src/gamecontrol.c: GameControl_CallEraseSaveEventWithKeyCombo
     */
    public fun callEraseSaveEventWithKeyCombo(proc: Proc) {
        if (keys.heldKeys == L_BUTTON or DPAD_RIGHT or SELECT_BUTTON) engine.goto(proc, LGAMECTRL_ERASE_SAVE)
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
     * Starts the game control proc on tree 3 with `nextAction = GAME_ACTION_EVENT_RETURN`. The main loop and VBlank
     * routines are set by the caller (they are fixed in [GameLoop]).
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

        /** `LGAMECTRL_ERASE_SAVE`. */
        public const val LGAMECTRL_ERASE_SAVE: Int = 18

        /** `GAME_ACTION_EVENT_RETURN`. */
        public const val GAME_ACTION_EVENT_RETURN: Int = 0

        private const val L_BUTTON = 0x200
        private const val DPAD_RIGHT = 0x10
        private const val SELECT_BUTTON = 0x4
    }
}
