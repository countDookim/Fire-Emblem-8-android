package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventConst.EV_EXEC_GAMEPLAY
import fe8.game.event.EventConst.EV_EXEC_QUIET
import fe8.game.event.EventConst.EV_EXEC_UNK4
import fe8.game.event.EventConst.EV_EXEC_UNK5
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventConst.EV_STATE_0002
import fe8.game.event.EventConst.EV_STATE_ABORT
import fe8.game.event.EventConst.EV_STATE_CHANGEGM
import fe8.game.event.EventConst.EV_STATE_FADEDIN
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
    private val onEndFn = ProcFunc { onEnd(it.state as EventEngineProc) }
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

    /** True when command [code] (0x00-0xFF) still has the default handler (reports an error). */
    public fun isUnregistered(code: Int): Boolean =
        (if (code < CODES) lo[code] else hi[code - CODES]) === UNKNOWN

    /** Registers [handler] for command [code] (0x00-0xFF). */
    public fun register(code: Int, handler: EventHandler) {
        if (code < CODES) lo[code] = handler else hi[code - CODES] = handler
    }

    /**
     * Creates a standard engine proc on proc tree 3 running the script at [events].
     *
     * Ported from src/event.c: EventEngine_Create. Delegated to hooks: `gLCDControlBuffer.blendY`
     * ([EventEngineHooks.blendY]), `gPlaySt.chapterIndex` ([EventEngineHooks.chapterIndex]) and
     * `LockGame` ([EventEngineHooks.lockGame]).
     */
    public fun create(events: Int, execType: Int): EventEngineProc {
        val proc = procs.start(stdScript, PROC_TREE_3)
        val s = proc.state as EventEngineProc
        s.reset()
        s.proc = proc
        s.callback = null
        s.pEventStart = events
        s.pEventIdk = events
        s.pEventCurrent = events
        s.evStateBits = 0
        s.evStallTimer = 0
        s.overwrittenTextSpeed = -1
        s.execType = execType
        s.activeTextType = 0
        s.chapterIndex = 0xFF
        s.mapSpritePalIdOverride = 0
        s.pUnitLoadData = 0
        s.unitLoadCount = 0
        s.chance = 0

        if (hooks.blendY() == 0x10) s.evStateBits = s.evStateBits or EV_STATE_FADEDIN

        if (execType == EV_EXEC_CUTSCENE || execType == EV_EXEC_GAMEPLAY) {
            s.chapterIndex = hooks.chapterIndex() and 0xFF
            hooks.lockGame()
        }
        return s
    }

    /**
     * Creates a battle engine proc on proc tree 3 running the script at [events].
     *
     * Ported from src/event.c: EventEngine_CreateBattle. `chapterIndex` keeps its reset value.
     */
    public fun createBattle(events: Int): EventEngineProc {
        val proc = procs.start(battleScript, PROC_TREE_3)
        val s = proc.state as EventEngineProc
        s.reset()
        s.proc = proc
        s.callback = null
        s.pEventStart = events
        s.pEventIdk = events
        s.pEventCurrent = events
        s.evStateBits = 0
        s.evStallTimer = 0
        s.overwrittenTextSpeed = -1
        s.execType = EV_EXEC_QUIET
        s.activeTextType = 0
        s.mapSpritePalIdOverride = 0
        s.pUnitLoadData = 0
        s.unitLoadCount = 0
        s.chance = 0
        s.evStateBits = s.evStateBits or EV_STATE_ABORT
        return s
    }

    /** Ported from src/event.c: EventEngineExists. */
    public fun eventEngineExists(): Boolean = findStd() != null

    /** Ported from src/event.c: BattleEventEngineExists. */
    public fun battleEventEngineExists(): Boolean = procs.find(battleScript) != null

    /** Ported from src/event.c: DeleteEventEngines. */
    public fun deleteEventEngines() {
        procs.endEach(stdScript)
        procs.endEach(battleScript)
    }

    /**
     * Ported from src/event.c: EnqueueEventCall. The first unused entry of
     * [EventState.callQueue] receives the call; only its `execType` and `isUsed` bytes are
     * written, the padding bytes are kept.
     */
    public fun enqueueEventCall(events: Int, execType: Int) {
        val q = state.callQueue
        for (i in 0 until QUEUE_ENTRIES) {
            val meta = q[2 * i + 1]
            if ((meta ushr 8) and 0xFF == 0) {
                q[2 * i] = events
                q[2 * i + 1] = (meta and 0xFFFF0000.toInt()) or 0x100 or (execType and 0xFF)
                break
            }
        }
    }

    /**
     * Ported from src/event.c: CallNextQueuedEvent. Starts the first entry if used, then shifts
     * the queue down one entry and clears the last (its padding bytes are kept).
     */
    public fun callNextQueuedEvent() {
        val q = state.callQueue
        if ((q[1] ushr 8) and 0xFF == 1) create(q[0], q[1] and 0xFF)

        for (i in 0 until QUEUE_ENTRIES - 1) {
            q[2 * i] = q[2 * i + 2]
            q[2 * i + 1] = q[2 * i + 3]
        }
        val last = 2 * (QUEUE_ENTRIES - 1)
        q[last] = 0
        q[last + 1] = q[last + 1] and 0xFFFF0000.toInt()
    }

    /**
     * Ported from src/event.c: CallEvent. Enqueues the call if a standard engine is running,
     * else starts it now.
     */
    public fun callEvent(events: Int, execType: Int) {
        if (findStd() != null) enqueueEventCall(events, execType) else create(events, execType)
    }

    /**
     * Ported from src/event.c: EventEngine_OnEnd. Delegated to hooks:
     * [EventEngineHooks.resetTextFont], [EventEngineHooks.restoreAfterEvent5],
     * [EventEngineHooks.endMapForChangeGm], [EventEngineHooks.resumeGameplay],
     * [EventEngineHooks.endEventTalk], [EventEngineHooks.changeUnitSpritePalette] and
     * [EventEngineHooks.eventEndCleanup].
     */
    public fun onEnd(proc: EventEngineProc) {
        hooks.resetTextFont()

        when (proc.execType) {
            EV_EXEC_UNK4 -> Unit
            EV_EXEC_UNK5 -> {
                hooks.restoreAfterEvent5(proc.mapSpritePalIdOverride)
                if ((proc.evStateBits and EV_STATE_CHANGEGM) != 0) {
                    hooks.endMapForChangeGm()
                    state.callQueue.fill(0)
                }
                hooks.resumeGameplay() // C falls through into EV_EXEC_GAMEPLAY
            }
            EV_EXEC_GAMEPLAY -> hooks.resumeGameplay()
        }

        if (proc.execType != EV_EXEC_QUIET) {
            hooks.endEventTalk()
            if (proc.execType == EV_EXEC_CUTSCENE) {
                hooks.changeUnitSpritePalette(proc.mapSpritePalIdOverride)
            }
            hooks.eventEndCleanup(proc)
        }

        if ((proc.evStateBits and EV_STATE_ABORT) == 0) callNextQueuedEvent()
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

        /** `PROC_TREE_3`. */
        public const val PROC_TREE_3: Int = 3

        private const val CODES = 0x80
        private const val QUEUE_ENTRIES = 16

        private val UNKNOWN = EventHandler { EVC_ERROR }
    }
}
