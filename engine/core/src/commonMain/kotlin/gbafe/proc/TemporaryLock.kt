package gbafe.proc

import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/**
 * The temporary lock of `src/bmlib.c` (`ProcScr_TemporaryLock`): a child proc that blocks its
 * parent for a number of frames, used to hold a script for a fixed time (a talk face's fade, a
 * pause between events).
 */
public class TemporaryLock(private val engine: ProcEngine) {
    /** State of the lock proc (`unk58`). */
    private class LockState {
        /** Remaining frames to keep the parent blocked. */
        var frames = 0
    }

    /** `ProcScr_TemporaryLock`: yields once, then counts down every frame and ends at 0. */
    public val script: ProcScript = ProcScript(
        "ProcScr_TemporaryLock",
        listOf(yield(), repeat(ProcFunc { onLoop(it) }), end()),
    ) { LockState() }

    /**
     * Blocks [proc] for [duration] frames with a lock child.
     *
     * Ported from src/bmlib.c: StartTemporaryLock
     */
    public fun start(proc: Proc, duration: Int) {
        val lock = engine.startBlocking(script, proc) ?: return
        (lock.state as LockState).frames = duration
    }

    /** The remaining frames of the lock proc [lock] (the decomp's `unk58`). */
    public fun remainingFrames(lock: Proc): Int = (lock.state as LockState).frames

    /**
     * Ends the lock (`Proc_Break`) once the count reached 0, else counts one frame down.
     *
     * Ported from src/bmlib.c: TemporaryLock_OnLoop
     */
    private fun onLoop(proc: Proc) {
        val state = proc.state as LockState
        if (state.frames == 0) {
            engine.breakProc(proc)
            return
        }
        state.frames--
    }
}
