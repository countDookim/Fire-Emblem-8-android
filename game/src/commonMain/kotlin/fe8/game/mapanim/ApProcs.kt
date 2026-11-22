package fe8.game.mapanim

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.render.ApHandle
import gbafe.render.SpriteAnimPlayer

/**
 * The procs that play one sprite animation each (`APProc` in `src/ap.c`): every frame the proc
 * draws its handle at its position and ends when the animation has finished.
 */
public class ApProcs(
    private val engine: ProcEngine,
    private val player: SpriteAnimPlayer,
) {
    /** Fields of `struct APProc`. */
    public class State {
        /** `pHandle`: the animation handle the proc plays (null when allocation failed). */
        public var handle: ApHandle? = null

        /** `xPosition`. */
        public var x: Int = 0

        /** `yPosition`. */
        public var y: Int = 0
    }

    private val pool = Array(POOL_SIZE) { State() }
    private var next = 0
    private var pending: State? = null

    /** `ProcScr_ApProc`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_ApProc",
        listOf(
            setEndCb(ProcFunc { onEnd(it) }),
            repeat(ProcFunc { onUpdate(it) }),
            end(),
        ),
        { pending ?: State() },
    )

    /**
     * Allocates a handle for [definition] on object layer [objLayer], switches it to animation
     * [anim], sets its tile base and starts a proc in tree 3 that plays it at ([x], [y]).
     *
     * Verified through the Prologue level-up gain labels (`ram_trace_prologue_levelup`).
     *
     * Ported from src/ap.c: APProc_Create
     */
    public fun create(
        definition: Int,
        x: Int,
        y: Int,
        tileBase: Int,
        anim: Int,
        objLayer: Int,
    ): Proc {
        val handle = player.create(definition, objLayer)
        player.switchAnimation(handle, anim)
        // The original writes through the pointer even when the pool was full; a missing handle
        // cannot occur in the captured window.
        if (handle != null) handle.tileBase = tileBase
        val state = pool[next]
        next = (next + 1) % pool.size
        state.handle = handle
        state.x = x
        state.y = y
        pending = state
        val proc = engine.start(script, 3)
        pending = null
        return proc
    }

    /**
     * Draws and advances the animation, and ends the proc once its handle has been freed.
     *
     * Ported from src/ap.c: APProc_OnUpdate
     */
    public fun onUpdate(proc: Proc) {
        val state = proc.state as State
        if (!player.update(state.handle, state.x, state.y)) {
            val handle = state.handle
            if (handle == null || handle.pDefinition == 0) engine.end(proc)
        }
    }

    /**
     * Frees the animation handle when the proc ends.
     *
     * Ported from src/ap.c: APProc_OnEnd
     */
    public fun onEnd(proc: Proc) {
        player.delete((proc.state as State).handle)
    }

    /**
     * Moves [proc]'s animation to ([x], [y]) and, unless [tileBase] is `-1`, sets its tile base.
     *
     * Transcribed from src/ap.c: APProc_SetParameters (not verified: PC-cap1 only calls it with
     * `tileBase != -1`; exercised through SaveDrawSlotsDifferentialTest, which compares sprites
     * but not the handle's tile base)
     */
    public fun setParameters(proc: Proc, x: Int, y: Int, tileBase: Int) {
        val state = proc.state as State
        state.x = x
        state.y = y
        if (tileBase != -1) state.handle!!.tileBase = tileBase
    }

    /**
     * Ends [proc].
     *
     * Transcribed from src/ap.c: APProc_Delete (not verified: not run in PC-cap1 before the
     * suspend bookmark is deleted)
     */
    public fun delete(proc: Proc) {
        engine.end(proc)
    }

    /**
     * Ends every animation proc.
     *
     * Ported from src/ap.c: APProc_DeleteAll
     */
    public fun deleteAll() {
        engine.endEach(script)
    }

    private companion object {
        const val POOL_SIZE: Int = 16
    }
}
