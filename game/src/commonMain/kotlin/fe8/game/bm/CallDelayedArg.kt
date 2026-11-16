package fe8.game.bm

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat

/**
 * Delayed call procs from `src/bmlib.c`: runs a callback with an integer argument after
 * [delay] frames on proc tree 3 (`ProcScr_CallDelayedArg`).
 *
 * @param engine the proc engine where delayed calls run on tree 3.
 */
public class CallDelayedArg(private val engine: ProcEngine) {
    /** Fields of `struct CallDelayedProc` (`src/bmlib.c`). */
    public class State {
        /** `func`: the callback to invoke. */
        public var func: ((Int) -> Unit)? = null

        /** `arg`: the integer passed to [func]. */
        public var arg: Int = 0

        /** `clock`: frames left before invocation. */
        public var clock: Int = 0
    }

    private val pool = Array(POOL_SIZE) { State() }
    private var next = 0
    private var pending: State? = null

    /**
     * `ProcScr_CallDelayedArg` (src/bmlib.c:1796): repeats [onLoop] until the clock reaches -1.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_CallDelayedArg",
        listOf(
            repeat(ProcFunc { onLoop(it) }),
            end(),
        ),
        { pending ?: State() },
    )

    /**
     * Starts a `ProcScr_CallDelayedArg` on tree 3 with [delay] countdown ticks.
     *
     * Ported from src/bmlib.c: CallDelayedArg
     */
    public fun callDelayedArg(func: (Int) -> Unit, arg: Int, delay: Int): Proc {
        val state = pool[next]
        next = (next + 1) % POOL_SIZE
        state.func = func
        state.arg = arg
        state.clock = delay
        pending = state
        val proc = engine.start(script, 3)
        pending = null
        return proc
    }

    /**
     * Counts down [State.clock]. When it reaches -1, invokes [State.func] with [State.arg]
     * and breaks the proc.
     *
     * Ported from src/bmlib.c: CallDelayedArg_OnLoop
     */
    public fun onLoop(proc: Proc) {
        val state = proc.state as State
        state.clock--
        if (state.clock == -1) {
            val func = state.func
            state.func = null
            func?.invoke(state.arg)
            engine.breakProc(proc)
        }
    }

    private companion object {
        const val POOL_SIZE = 8
    }
}
