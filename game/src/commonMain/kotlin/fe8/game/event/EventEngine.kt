package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_UNK4
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventConst.EV_STATE_0002
import fe8.game.event.EventConst.EV_STATE_NOSKIP
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.yield

/**
 * The event engine: `ProcScr_StdEventEngine` / `ProcScr_BattleEventEngine` and
 * `EventEngine_OnUpdate` on the [ProcEngine]. Commands are dispatched through preallocated
 * handler tables ([lo] for codes 0x00-0x7F, [hi] for 0x80-0xFF); an unregistered code runs a
 * handler returning `EVC_ERROR`. Per-frame execution allocates nothing.
 */
public class EventEngine(
    /** The proc engine the engine procs run on. */
    public val procs: ProcEngine,
    /** The event globals (`gEventSlots` and friends). */
    public val state: EventState,
    /** Script word source. */
    public val memory: ScriptMemory,
    /** Environment hooks. */
    public val hooks: EventEngineHooks = object : EventEngineHooks {},
) {
    /** `gEventLoCmdTable`, indexed by command code 0x00-0x7F. */
    public val lo: Array<EventHandler> = Array(CODES) { UNKNOWN }

    /** `gEventHiCmdTable`, indexed by command code - 0x80. */
    public val hi: Array<EventHandler> = Array(CODES) { UNKNOWN }

    private val onUpdateFn = ProcFunc { onUpdate(it.state as EventEngineProc) }
    private val onEndFn = ProcFunc { hooks.onEnd(it.state as EventEngineProc) }
    private val freezeMenuFn = ProcFunc { hooks.freezeMenu() }

    /** `ProcScr_StdEventEngine`. */
    public val stdScript: ProcScript = ProcScript(
        "ProcScr_StdEventEngine",
        listOf(
            mark(PROC_MARK_EVENT),
            setEndCb(onEndFn),
            yield(),
            call(freezeMenuFn),
            repeat(onUpdateFn),
            yield(),
            end(),
        ),
        { EventEngineProc() },
    )

    /** `ProcScr_BattleEventEngine`. */
    public val battleScript: ProcScript = ProcScript(
        "ProcScr_BattleEventEngine",
        listOf(
            mark(PROC_MARK_EVENT),
            setEndCb(onEndFn),
            yield(),
            repeat(onUpdateFn),
            yield(),
            end(),
        ),
        { EventEngineProc() },
    )

    /** Registers [handler] for command [code] (0x00-0xFF). */
    public fun register(code: Int, handler: EventHandler) {
        if (code < CODES) lo[code] = handler else hi[code - CODES] = handler
    }

    /**
     * Creates a standard (or, if [battle], battle) engine proc on proc tree [rootIndex] running
     * the script at [events], and returns its state.
     *
     * Partially ported from src/event.c: EventEngine_Create. Missing: the slot queue/counter and
     * call-queue resets, the `EventEngine_OnEnd` side effects and the std/battle existence
     * checks; only the proc start and field set-up are done.
     */
    public fun create(
        events: Int,
        execType: Int,
        rootIndex: Int = 0,
        battle: Boolean = false,
    ): EventEngineProc {
        val proc = procs.start(if (battle) battleScript else stdScript, rootIndex)
        val s = proc.state as EventEngineProc
        s.reset()
        s.proc = proc
        s.pEventStart = events
        s.pEventCurrent = events
        s.execType = execType
        return s
    }

    /**
     * One frame of the engine: runs commands until one yields.
     *
     * Ported from src/event.c: EventEngine_OnUpdate. `EVC_STOP_CONTINUE` and `EVC_UNK4` re-run
     * the same command in the loop as in C. Deviation: C does the same for `EVC_ERROR`, which
     * hangs the frame forever on an unknown command; here `EVC_ERROR` ends the frame instead.
     */
    public fun onUpdate(proc: EventEngineProc) {
        if (hooks.fadeExists()) return

        if (canStartSkip(proc) && hooks.startPressed()) {
            startSkip(proc)
            return
        }

        if (proc.execType != EV_EXEC_WORLDMAP && proc.execType != EV_EXEC_UNK4) {
            hooks.showAllLayers()
        }

        val cb = proc.callback
        if (cb != null) {
            cb.invoke(proc)
            return
        }

        proc.evStateBits = proc.evStateBits or EV_STATE_0002

        while (true) {
            state.slots[0] = 0

            val code = memory.u16(proc.pEventCurrent) ushr 8
            val handler = if (code < CODES) lo[code] else hi[code - CODES]

            when (handler.run(proc)) {
                EVC_ADVANCE_CONTINUE -> advance(proc)
                EVC_ADVANCE_YIELD -> {
                    advance(proc)
                    return
                }
                EVC_STOP_YIELD -> return
                EVC_END -> {
                    procs.breakProc(proc.proc!!)
                    return
                }
                EVC_ERROR -> return // deviation, see the KDoc
                else -> Unit // STOP_CONTINUE, UNK4: run the same command again
            }
        }
    }

    /** `pEventCurrent += (*pEventCurrent >> 4) & 0xF` in u16 units. */
    private fun advance(proc: EventEngineProc) {
        val len = (memory.u16(proc.pEventCurrent) ushr 4) and 0xF
        proc.pEventCurrent += len * 2
    }

    /**
     * Partially ported from src/event.c: EventEngine_CanStartSkip. Missing: the
     * `IsBattleDeamonActive` and `ProcScr_MuDeathFade` checks, which are only behind
     * [EventEngineHooks.skipBlockedByOthers].
     */
    public fun canStartSkip(proc: EventEngineProc): Boolean =
        (proc.evStateBits and EV_STATE_0002) != 0 &&
            (proc.evStateBits and EV_STATE_SKIPPING) == 0 &&
            (proc.evStateBits and EV_STATE_NOSKIP) == 0 &&
            !hooks.skipBlockedByOthers()

    /**
     * Partially ported from src/event.c: EventEngine_StartSkip. Missing: the fade and world map
     * effects (`sub_800D488` etc.); only the skipping bit is set, the rest is behind
     * [EventEngineHooks.startSkipEffects].
     */
    public fun startSkip(proc: EventEngineProc) {
        proc.evStateBits = proc.evStateBits or EV_STATE_SKIPPING
        hooks.startSkipEffects(proc)
    }

    /** The first running standard engine proc, or null. */
    public fun findStd(): Proc? = procs.find(stdScript)

    /** Constants. */
    public companion object {
        /** `PROC_MARK_EVENT`. */
        public const val PROC_MARK_EVENT: Int = 6

        private const val CODES = 0x80

        private val UNKNOWN = EventHandler { EVC_ERROR }
    }
}
