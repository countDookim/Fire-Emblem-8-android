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
import fe8.game.event.EventConst.EV_STATE_NOFADE
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
 * The event engine: runs event scripts (chapter cutscenes, battle quotes, chest and tile-change
 * scripts) command by command. It is the `ProcScr_StdEventEngine` proc
 * (the map engine, one at a time; later calls wait in a queue) and the
 * `ProcScr_BattleEventEngine` proc (used for quotes during a battle), both driven by
 * `EventEngine_OnUpdate` on the [ProcEngine]. A command is one u16 header word (code in the
 * high byte, length in words in bits 4-7) followed by its arguments. Commands are dispatched
 * through preallocated handler tables ([lo] for codes 0x00-0x7F, [hi] for 0x80-0xFF, the world
 * map commands); an unregistered code runs a handler returning `EVC_ERROR`. Per-frame
 * execution allocates nothing.
 */
public class EventEngine(
    /** The proc engine the engine procs run on. */
    public val procs: ProcEngine,
    /** The event globals (`gEventSlots` and friends). */
    public val state: EventState,
    /** Reads the script words (event commands) by GBA or mod address. */
    public val memory: ScriptMemory,
    /** The game systems the engine drives (fades, menus, talk, world map); no-ops by default. */
    public val hooks: EventEngineHooks = object : EventEngineHooks {},
) {
    /** `gEventLoCmdTable`, indexed by command code 0x00-0x7F. */
    public val lo: Array<EventHandler> = Array(CODES) { UNKNOWN }

    /** `gEventHiCmdTable`, indexed by command code - 0x80. */
    public val hi: Array<EventHandler> = Array(CODES) { UNKNOWN }

    private val onUpdateFn = ProcFunc { onUpdate(it.state as EventEngineProc) }
    private val onEndFn = ProcFunc { onEnd(it.state as EventEngineProc) }
    private val freezeMenuFn = ProcFunc { hooks.freezeMenu() }

    /**
     * `ProcScr_StdEventEngine`: the map event engine. Freezes the menu once before the first
     * script command so a menu left open does not react while the event runs.
     */
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

    /**
     * `ProcScr_BattleEventEngine`: the engine for events inside a battle (battle quotes); the
     * same as [stdScript] without freezing the menu.
     */
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
     * Creates a standard engine proc on proc tree 3 running the script at [events]. [execType]
     * is an `EV_EXEC_*` value: it decides what the engine locks while running (cutscene and
     * gameplay events lock the game) and what it cleans up in [onEnd].
     *
     * Ported from src/event.c: EventEngine_Create. Delegated to hooks: `gLCDControlBuffer.blendY`
     * ([EventEngineHooks.blendY]), `gPlaySt.chapterIndex` ([EventEngineHooks.chapterIndex]) and
     * `LockGame` ([EventEngineHooks.lockGame]).
     */
    public fun create(events: Int, execType: Int): EventEngineProc {
        val proc = procs.start(stdScript, PROC_TREE_3)
        val eventState = proc.state as EventEngineProc
        eventState.reset()
        eventState.proc = proc
        eventState.callback = null
        eventState.pEventStart = events
        eventState.pEventIdk = events
        eventState.pEventCurrent = events
        eventState.evStateBits = 0
        eventState.evStallTimer = 0
        eventState.overwrittenTextSpeed = -1
        eventState.execType = execType
        eventState.activeTextType = 0
        eventState.chapterIndex = 0xFF
        eventState.mapSpritePalIdOverride = 0
        eventState.pUnitLoadData = 0
        eventState.unitLoadCount = 0
        eventState.chance = 0

        if (hooks.blendY() == 0x10) eventState.evStateBits = eventState.evStateBits or EV_STATE_FADEDIN

        if (execType == EV_EXEC_CUTSCENE || execType == EV_EXEC_GAMEPLAY) {
            eventState.chapterIndex = hooks.chapterIndex() and 0xFF
            hooks.lockGame()
        }
        return eventState
    }

    /**
     * Creates a battle engine proc on proc tree 3 running the script at [events]. It runs as
     * `EV_EXEC_QUIET` (no fade, no locking) and starts with `EV_STATE_ABORT`, so ending it does
     * not start the next queued call.
     *
     * Transcribed from src/event.c: EventEngine_CreateBattle (not verified: no test checks it
     *   against a trace yet). `chapterIndex` keeps its reset value.
     */
    public fun createBattle(events: Int): EventEngineProc {
        val proc = procs.start(battleScript, PROC_TREE_3)
        val eventState = proc.state as EventEngineProc
        eventState.reset()
        eventState.proc = proc
        eventState.callback = null
        eventState.pEventStart = events
        eventState.pEventIdk = events
        eventState.pEventCurrent = events
        eventState.evStateBits = 0
        eventState.evStallTimer = 0
        eventState.overwrittenTextSpeed = -1
        eventState.execType = EV_EXEC_QUIET
        eventState.activeTextType = 0
        eventState.mapSpritePalIdOverride = 0
        eventState.pUnitLoadData = 0
        eventState.unitLoadCount = 0
        eventState.chance = 0
        eventState.evStateBits = eventState.evStateBits or EV_STATE_ABORT
        return eventState
    }

    /**
     * True while a standard (map) event engine proc runs, which blocks player input on the map.
     *
     * Transcribed from src/event.c: EventEngineExists (not verified: no test checks it against a
     *   trace yet).
     */
    public fun eventEngineExists(): Boolean = findStd() != null

    /**
     * True while a battle event engine proc runs.
     *
     * Transcribed from src/event.c: BattleEventEngineExists (not verified: no test checks it
     *   against a trace yet).
     */
    public fun battleEventEngineExists(): Boolean = procs.find(battleScript) != null

    /**
     * Ends every running standard and battle engine proc (their `onEnd` still runs).
     *
     * Transcribed from src/event.c: DeleteEventEngines (not verified: no test checks it against a
     *   trace yet).
     */
    public fun deleteEventEngines() {
        procs.endEach(stdScript)
        procs.endEach(battleScript)
    }

    /**
     * Queues an event call to start after the running engine ends (the queue holds 16 calls;
     * a call to a full queue is silently dropped).
     *
     * Transcribed from src/event.c: EnqueueEventCall (not verified: the call queue is empty in the
     *   prologue). The first unused entry of
     * [EventState.callQueue] receives the call; only its `execType` and `isUsed` bytes are
     * written, the padding bytes are kept.
     */
    public fun enqueueEventCall(events: Int, execType: Int) {
        val callQueue = state.callQueue
        for (i in 0 until QUEUE_ENTRIES) {
            val meta = callQueue[2 * i + 1]
            if ((meta ushr 8) and 0xFF == 0) {
                callQueue[2 * i] = events
                callQueue[2 * i + 1] = (meta and 0xFFFF0000.toInt()) or 0x100 or (execType and 0xFF)
                break
            }
        }
    }

    /**
     * Starts the oldest queued event call, if any, once the previous engine has ended.
     *
     * Transcribed from src/event.c: CallNextQueuedEvent (not verified: the call queue is empty in
     *   the prologue). Starts the first entry if used, then shifts
     * the queue down one entry and clears the last (its padding bytes are kept).
     */
    public fun callNextQueuedEvent() {
        val callQueue = state.callQueue
        if ((callQueue[1] ushr 8) and 0xFF == 1) create(callQueue[0], callQueue[1] and 0xFF)

        for (i in 0 until QUEUE_ENTRIES - 1) {
            callQueue[2 * i] = callQueue[2 * i + 2]
            callQueue[2 * i + 1] = callQueue[2 * i + 3]
        }
        val last = 2 * (QUEUE_ENTRIES - 1)
        callQueue[last] = 0
        callQueue[last + 1] = callQueue[last + 1] and 0xFFFF0000.toInt()
    }

    /**
     * The usual way to run an event script: starts it now, or queues it when an event is already
     * running so scripts never overlap.
     *
     * Transcribed from src/event.c: CallEvent (not verified: the call queue is empty in the
     *   prologue). Enqueues the call if a standard engine is running,
     * else starts it now.
     */
    public fun callEvent(events: Int, execType: Int) {
        if (findStd() != null) enqueueEventCall(events, execType) else create(events, execType)
    }

    /**
     * End callback of both engine procs: restores the text font and, by `execType`, unlocks the
     * game and tears down the talk and portrait state that the script left behind, then starts
     * the next queued call unless the proc was a battle engine (`EV_STATE_ABORT`).
     * `EV_EXEC_UNK5` is set by the END command after a cutscene (the decomp calls it unused).
     *
     * Transcribed from src/event.c: EventEngine_OnEnd (not verified: the call queue is empty in the
     *   prologue). Delegated to hooks:
     * [EventEngineHooks.resetTextFont], [EventEngineHooks.restoreAfterEvent5],
     * [EventEngineHooks.endMapForChangeGm], [EventEngineHooks.resumeGameplay],
     * [EventEngineHooks.endEventTalk], [EventEngineHooks.changeUnitSpritePalette] and
     * [EventEngineHooks.eventEndCleanup].
     */
    public fun onEnd(proc: EventEngineProc) {
        hooks.resetTextFont()

        when (proc.execType) {
            EV_EXEC_UNK4 -> Unit // set by the END command when a world map script finishes
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
     * One frame of the engine: runs commands until one yields. A pending map fade pauses the
     * engine; the Start button skips the event (see [startSkip]); an engine callback replaces
     * script execution (the unit load with movement command sets one while its units walk in).
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
            state.slots[0] = 0 // slot 0 is the null slot: each command starts with it cleared

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

    /**
     * Moves to the next command: `pEventCurrent += (*pEventCurrent >> 4) & 0xF` in C, where the
     * length nibble counts u16 words; here the address is in bytes, hence the doubling.
     */
    private fun advance(proc: EventEngineProc) {
        val len = (memory.u16(proc.pEventCurrent) ushr 4) and 0xF
        proc.pEventCurrent += len * 2
    }

    /**
     * Whether pressing Start may skip the running event: the engine has run a command, is not
     * already skipping, and the script has not forbidden skipping (`EV_STATE_NOSKIP`).
     *
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
     * Starts skipping the event (Start pressed): fades the screen out unless the script forbids
     * fading, and blocks the conversation procs so skipped dialogue stops.
     *
     * Ported from src/event.c: EventEngine_StartSkip. `GetZero` is always 0, so its guard is
     * dropped. The fade to black (0x40, locking the engine proc) and `Proc_BlockEachMarked(5)`
     * are verified for the Prologue castle cutscene. The `sub_800D488` branch
     * ([EventEngineHooks.skipFadedIn]), the world map branch ([EventEngineHooks.worldMapExists])
     * and `sub_80BA424` ([EventEngineHooks.worldMapSkip]) are hooks, not run by the capture.
     */
    public fun startSkip(proc: EventEngineProc) {
        proc.evStateBits = proc.evStateBits or EV_STATE_SKIPPING

        if (hooks.worldMapExists()) {
            hooks.skipFadedIn(proc)
        } else if ((proc.evStateBits and EV_STATE_NOFADE) == 0) {
            if ((proc.evStateBits and EV_STATE_FADEDIN) != 0) {
                hooks.skipFadedIn(proc)
            } else {
                proc.proc?.let { hooks.startLockingFadeToBlack(SKIP_FADE_SPEED, it) }
            }
            proc.evStateBits = proc.evStateBits or EV_STATE_FADEDIN
        }

        if (proc.execType == EV_EXEC_WORLDMAP) hooks.worldMapSkip(proc)

        procs.blockEachMarked(PROC_MARK_TALK)
    }

    /** The first running standard (map) engine proc, or null when no map event runs. */
    public fun findStd(): Proc? = procs.find(stdScript)

    /** Proc marks, tree and table sizes shared by the engine. */
    public companion object {
        /** `PROC_MARK_EVENT`: marks the engine procs so other code can find or block them. */
        public const val PROC_MARK_EVENT: Int = 6

        /** `PROC_TREE_3`: the root proc tree the engine procs are started on (decomp value 3). */
        public const val PROC_TREE_3: Int = 3

        /** `PROC_MARK_TALK`: mark of the conversation procs that skipping blocks. */
        private const val PROC_MARK_TALK = 5

        /** Speed argument of `StartLockingFadeToBlack` in the skip fade (0x40 in the decomp). */
        private const val SKIP_FADE_SPEED = 0x40

        /** Number of commands per handler table: 0x80 low codes and 0x80 high codes. */
        private const val CODES = 0x80

        /** Entries in `gEventCallQueue`. */
        private const val QUEUE_ENTRIES = 16

        /** Default handler of unregistered codes: reports `EVC_ERROR`. */
        private val UNKNOWN = EventHandler { EVC_ERROR }
    }
}
