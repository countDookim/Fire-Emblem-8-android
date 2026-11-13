package fe8.game.bm

import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep

/**
 * The target selection proc of src/uiselecttarget.c: the cursor that walks the targets of
 * [SelectTargets] (one tile each) and hands the A, B and R buttons and the target changes to the
 * [SelectInfo] it was started with.
 *
 * The proc's fields past `PROC_HEADER` are [State]; targets are indices of [targets]. Nothing
 * allocates per frame (`gKeyStatusPtr` is [keys]).
 *
 * Not written, because no capture runs them: `NewTargetSelection_Specialized` (the `onAPress`
 * override, so [handleSelectInput] has no such branch), `sub_804FBBC` and `sub_804FBDC` (they set
 * and clear the frozen flag, so [targetSelectionLoop]'s frozen branch is only transcribed).
 */
public class TargetSelection(
    private val engine: ProcEngine,
    private val targets: SelectTargets,
    private val keys: KeyStatus,
    private val camMove: CamMove,
    /** BG 0 and 1 of the original's `ClearBg0Bg1`, cleared when an action asks for it. */
    private val clearBg0Bg1: () -> Unit,
) {
    /** The callbacks of a `struct SelectInfo`; every one defaults to doing nothing. */
    public interface SelectInfo {
        /** `onInit`: the selection starts. */
        public fun onInit(proc: Proc) {}

        /** `onEnd`: the selection ends. */
        public fun onEnd(proc: Proc) {}

        /** `onUnk08`: runs after [onInit]. */
        public fun onUnk08(proc: Proc) {}

        /** `onSwitchIn`: the cursor arrives on [target] (an index of the list). */
        public fun onSwitchIn(proc: Proc, target: Int): Int = 0

        /** `onSwitchOut`: the cursor leaves [target]. */
        public fun onSwitchOut(proc: Proc, target: Int): Int = 0

        /** `onSelect`: A on [target]; returns the `TARGETSELECTION_ACTION_*` bits. */
        public fun onSelect(proc: Proc, target: Int): Int = 0

        /** `onCancel`: B on [target]; returns the `TARGETSELECTION_ACTION_*` bits. */
        public fun onCancel(proc: Proc, target: Int): Int = 0

        /** `onHelp`: R on [target]; returns the `TARGETSELECTION_ACTION_*` bits. */
        public fun onHelp(proc: Proc, target: Int): Int = 0
    }

    /** `struct SelectTargetProc` past `PROC_HEADER`. */
    public class State {
        /** `selectRoutines` (+0x2C). */
        public var info: SelectInfo? = null

        /** `currentTarget` (+0x30) as an index of the list. */
        public var currentTarget: Int = SelectTargets.NONE

        /** `flags` (+0x34): `TARGETSELECTION_FLAG_*`. */
        public var flags: Int = 0
    }

    /** Services outside the ported file. */
    public class Hooks {
        /** `PlaySoundEffect`. */
        public var playSoundEffect: (song: Int) -> Unit = {}

        /** `EndFaceById`. */
        public var endFaceById: (slot: Int) -> Unit = {}

        /** `PutMapCursor(x, y, kind)` (`MapCursor.put`). */
        public var putMapCursor: PlayerPhase.PutMapCursor = PlayerPhase.PutMapCursor { _, _, _ -> }

        /** `RefreshBMapGraphics` (the proc script's step after a target change that never ends). */
        public var refreshBMapGraphics: ProcFunc = ProcFunc {}

        /** `LockGame`. */
        public var lockGame: () -> Unit = {}

        /** `UnlockGame`. */
        public var unlockGame: () -> Unit = {}
    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks()

    private var realX = 0
    private var realY = 0

    /**
     * `gProcScr_TargetSelection` (0x085B655C): LABEL 0, REPEAT [targetSelectionLoop], SLEEP 1,
     * CALL `RefreshBMapGraphics`, GOTO 0, END. The loop never ends by itself, so the call is
     * never reached.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_TargetSelection",
        listOf(
            label(0),
            repeat(ProcFunc { targetSelectionLoop(it) }),
            sleep(1),
            call(ProcFunc { hooks.refreshBMapGraphics.invoke(it) }),
            goto(0),
            end(),
        ),
        newState = { State() },
    )

    /**
     * Ported from src/uiselecttarget.c: NewTargetSelection
     *
     * Locks the game and starts the selection proc (tree 3) with [info], on the first target
     * `GetLinkedTargets` links; runs `onInit`, `onUnk08` and `onSwitchIn`, and clears the new
     * keys so the press that opened the selection does not select (not verified: the dumps show
     * the proc's first run a frame later, after the keys were renewed).
     */
    public fun newTargetSelection(info: SelectInfo): Proc {
        hooks.lockGame()
        val proc = engine.start(script, PROC_TREE_3)
        val state = proc.state as State
        state.flags = FLAG_GAMELOCK
        state.info = info
        state.currentTarget = targets.getLinkedTargets()

        info.onInit(proc)
        info.onUnk08(proc)
        info.onSwitchIn(proc, state.currentTarget)

        keys.newKeys = 0
        return proc
    }

    /**
     * Ported from src/uiselecttarget.c: TargetSelection_GetRealCursorPosition
     *
     * Sets [realX] and [realY] to the pixel position of the current target.
     */
    private fun getRealCursorPosition(state: State) {
        realX = targets.targetX(state.currentTarget) * 16
        realY = targets.targetY(state.currentTarget) * 16
    }

    /**
     * Ported from src/uiselecttarget.c: TargetSelection_Loop
     *
     * One frame: the d-pad ([handleMoveInput]), the buttons ([handleSelectInput]) and the actions
     * they ask for (end, sounds 0x6A and 0x6B, clearing BG 0 and 1, ending the face), then the
     * camera follows the current target and the cursor is put on it (kind 2) unless a camera
     * move was started. The frozen branch (only the cursor, kind 4) is transcribed (not
     * verified: nothing sets the flag in the captures). The sounds are not in the dumps (not
     * verified).
     */
    private fun targetSelectionLoop(proc: Proc) {
        val state = proc.state as State
        if (state.flags and FLAG_FROZEN != 0) {
            getRealCursorPosition(state)
            hooks.putMapCursor.put(realX, realY, 4)
            return
        }

        handleMoveInput(proc, state)

        val actions = handleSelectInput(proc, state)

        if (actions and ACTION_END != 0) endTargetSelection(proc)

        if (actions and ACTION_SE_6A != 0) hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)

        if (actions and ACTION_SE_6B != 0) hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)

        if (actions and ACTION_CLEARBGS != 0) clearBg0Bg1()

        if (actions and ACTION_ENDFACE != 0) hooks.endFaceById(0)

        if (actions and ACTION_ENDFAST == 0) {
            getRealCursorPosition(state)
            if (!camMove.ensureCameraOntoPosition(proc, realX shr 4, realY shr 4)) {
                hooks.putMapCursor.put(realX, realY, 2)
            }
        }
    }

    /**
     * Ported from src/uiselecttarget.c: EndTargetSelection
     *
     * Runs `onEnd`, unlocks the game if the proc locked it and ends the proc.
     */
    public fun endTargetSelection(proc: Proc) {
        val state = proc.state as State
        state.info!!.onEnd(proc)

        if (state.flags and FLAG_GAMELOCK != 0) hooks.unlockGame()

        engine.end(proc)
    }

    /**
     * Transcribed from src/uiselecttarget.c: TargetSelection_HandleMoveInput (not verified: the
     *   captures never press the d-pad, so only the return without a change runs)
     *
     * Left and up (auto-repeating) go to the next target, right and down to the previous one;
     * when the target changed `onSwitchOut` and `onSwitchIn` run and sound 0x67 plays.
     */
    private fun handleMoveInput(proc: Proc, state: State) {
        val current = state.currentTarget

        if ((KEY_LEFT or KEY_UP) and keys.repeatedKeys != 0) {
            if (targets.targetNext(current) != SelectTargets.NONE) {
                state.currentTarget = targets.targetNext(current)
            }
        }

        if ((KEY_RIGHT or KEY_DOWN) and keys.repeatedKeys != 0) {
            if (targets.targetPrev(state.currentTarget) != SelectTargets.NONE) {
                state.currentTarget = targets.targetPrev(state.currentTarget)
            }
        }

        if (state.currentTarget == current) return

        val info = state.info!!
        info.onSwitchOut(proc, current)
        info.onSwitchIn(proc, state.currentTarget)

        hooks.playSoundEffect(SONG_SE_SYS_CURSOR_LR1)
    }

    /**
     * Ported from src/uiselecttarget.c: TargetSelection_HandleSelectInput
     *
     * A calls `onSelect`, else B `onCancel`, else R `onHelp` (not verified: no capture presses R)
     * with the current target, and returns the action bits that call returns.
     */
    private fun handleSelectInput(proc: Proc, state: State): Int {
        val info = state.info!!
        val new = keys.newKeys
        return when {
            new and KEY_A != 0 -> info.onSelect(proc, state.currentTarget)
            new and KEY_B != 0 -> info.onCancel(proc, state.currentTarget)
            new and KEY_R != 0 -> info.onHelp(proc, state.currentTarget)
            else -> 0
        }
    }

    /** Constants of the proc. */
    public companion object {
        /** `TARGETSELECTION_FLAG_GAMELOCK`: the proc locked the game and unlocks it at its end. */
        public const val FLAG_GAMELOCK: Int = 1

        /** `TARGETSELECTION_FLAG_FROZEN`: only the cursor is shown, no input. */
        public const val FLAG_FROZEN: Int = 1 shl 6

        /** `TARGETSELECTION_ACTION_ENDFAST`: skip the camera and cursor of this frame. */
        public const val ACTION_ENDFAST: Int = 1

        /** `TARGETSELECTION_ACTION_END`: end the selection. */
        public const val ACTION_END: Int = 1 shl 1

        /** `TARGETSELECTION_ACTION_SE_6A`: play the select sound. */
        public const val ACTION_SE_6A: Int = 1 shl 2

        /** `TARGETSELECTION_ACTION_SE_6B`: play the cancel sound. */
        public const val ACTION_SE_6B: Int = 1 shl 3

        /** `TARGETSELECTION_ACTION_CLEARBGS`: clear BG 0 and 1. */
        public const val ACTION_CLEARBGS: Int = 1 shl 4

        /** `TARGETSELECTION_ACTION_ENDFACE`: end the face in slot 0. */
        public const val ACTION_ENDFACE: Int = 1 shl 5

        private const val PROC_TREE_3 = 3
        private const val KEY_A = 1
        private const val KEY_B = 2
        private const val KEY_RIGHT = 0x10
        private const val KEY_LEFT = 0x20
        private const val KEY_UP = 0x40
        private const val KEY_DOWN = 0x80
        private const val KEY_R = 0x100
        private const val SONG_SE_SYS_CURSOR_LR1 = 0x67
        private const val SONG_SE_SYS_WINDOW_SELECT1 = 0x6A
        private const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B
    }
}
