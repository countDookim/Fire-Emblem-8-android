package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_END
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_CONTINUE
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EVC_UNK4
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventConst.EV_EXEC_UNK4
import fe8.game.event.EventConst.EV_EXEC_UNK5
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventConst.EV_STATE_0008
import fe8.game.event.EventConst.EV_STATE_0020
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_ABORT
import fe8.game.event.EventConst.EV_STATE_NOSKIP
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import gbafe.core.flags.FlagStore
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus

/** ROM address of `EventScr_08592114`, the script `Event01_End` jumps to after a cutscene. */
public const val EVENT_SCR_08592114: Int = 0x08592114

/** Inputs read by the core event commands; defaults are "nothing pressed", speed 0. */
public interface EventInput {
    /** `gKeyStatusPtr->newKeys & B_BUTTON`. */
    public fun bPressed(): Boolean = false

    /** `gKeyStatusPtr->heldKeys & A_BUTTON`. */
    public fun aHeld(): Boolean = false

    /** `gPlaySt.config.gameSpeed`. */
    public fun gameSpeed(): Int = 0
}

/**
 * Game state the checks of [EventScrCore] read that is not modelled here yet. The defaults are
 * "empty": no tutorial events, no units, map kind 0, trigger id 0.
 */
public interface EventScrHooks {
    /**
     * Word [index] of `GetChapterEventDataPointer(chapterIndex)->tutorialEvents`; 0 ends the
     * list. Needed by `EnqueueTutEvent` (chapter event tables are not ported).
     */
    public fun tutorialEvent(chapterIndex: Int, index: Int): Int = 0

    /** `CountRedUnits`: living units with ids 0x81..0xBF (the unit table is not wired here). */
    public fun countRedUnits(): Int = 0

    /** `CountGreenUnits`: living units with ids 0x41..0x7F. */
    public fun countGreenUnits(): Int = 0

    /** `GetBattleMapKind` (needs the world map chapter tables). */
    public fun battleMapKind(): Int = 0

    /** `GetEventTriggerId(script)`: trigger id of the registered event at [script], else 0. */
    public fun eventTriggerId(script: Int): Int = 0
}

/**
 * The control-flow and slot commands of `src/eventscr.c`: NULL, END, SETVAL, slot operations,
 * slot queue, LABEL, GOTO, CALL, branches, STAL and counter operations. Handlers are
 * preallocated; running them allocates nothing.
 *
 * `gEventSlots` are `u32` in C and held as [Int] in [EventState.slots]. Slot indices that reach
 * past the 14 slots (a u16 index in SETVAL/queue ops, a nibble in slot ops, a u16 in branches)
 * address the following global `gEventSlotQueue` as in C; beyond that reads give 0 and writes
 * are dropped.
 */
public class EventScrCore(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val input: EventInput = object : EventInput {},
    private val flags: FlagStore = Fe8EventFlags.create(),
    private val rng: RngService,
    private val playSt: PlaySt,
    private val keys: KeyStatus,
    private val hooks: EventScrHooks = object : EventScrHooks {},
) {
    private var engine: EventEngine? = null

    private fun slot(i: Int): Int = when {
        i < EventState.SLOT_COUNT -> state.slots[i]
        i < EventState.SLOT_COUNT + EventState.SLOT_QUEUE_WORDS ->
            state.slotQueue[i - EventState.SLOT_COUNT]
        else -> 0
    }

    private fun setSlot(i: Int, value: Int) {
        when {
            i < EventState.SLOT_COUNT -> state.slots[i] = value
            i < EventState.SLOT_COUNT + EventState.SLOT_QUEUE_WORDS ->
                state.slotQueue[i - EventState.SLOT_COUNT] = value
            else -> Unit
        }
    }

    /** `EVT_CMD_ARGV(scr)[k]` as `s16`. */
    private fun argS16(cur: Int, k: Int): Int = memory.u16(cur + 2 + 2 * k).toShort().toInt()

    /** `(u16)EVT_CMD_ARGV(scr)[k]`. */
    private fun argU16(cur: Int, k: Int): Int = memory.u16(cur + 2 + 2 * k)

    /** `EVT_CMD_ARG32_LE` / `EVT_CMD_ARG32_BE`: the words at `cur + 4` and `cur + 6`. */
    private fun arg32(cur: Int): Int = argU16(cur, 1) or (argU16(cur, 2) shl 16)

    private fun subCmd(cur: Int): Int = memory.u16(cur) and 0xF

    /** Ported from src/eventscr.c: Event00_NULL */
    public val event00Null: EventHandler = EventHandler { EVC_ADVANCE_CONTINUE }

    /**
     * Ported from src/eventscr.c: Event01_End
     *
     * Sub command 1 (ENDB) clears the whole call stack first. A non-empty stack is popped
     * (`pEventIdk`/`pEventCurrent` restored, then the CALL is advanced past by the engine).
     */
    public val event01End: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_ABORT != 0) return@EventHandler EVC_END
        val q = state.activeQueue
        if (subCmd(proc.pEventCurrent) == EVSUBCMD_ENDB) q.fill(0)
        if (q[0] != 0) {
            proc.pEventIdk = q[0]
            proc.pEventCurrent = q[1]
            for (i in 0 until 14) q[i] = q[i + 2]
            q[14] = 0
            q[15] = 0
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        when (proc.execType) {
            EV_EXEC_WORLDMAP -> {
                proc.execType = EV_EXEC_UNK4
                EVC_END
            }
            EV_EXEC_CUTSCENE -> {
                proc.evStateBits = (proc.evStateBits and EV_STATE_SKIPPING.inv()) or EV_STATE_NOSKIP
                proc.execType = EV_EXEC_UNK5
                proc.pEventIdk = EVENT_SCR_08592114
                proc.pEventCurrent = EVENT_SCR_08592114
                EVC_STOP_CONTINUE
            }
            else -> EVC_END
        }
    }

    /** `(u16)EVT_CMD_ARGV(scr)[0]`, or `gEventSlots[2]` (as u16) when the s16 is negative. */
    private fun evArg(cur: Int): Int {
        val raw = argU16(cur, 0)
        return if (raw.toShort() < 0) state.slots[2] and 0xFFFF else raw
    }

    /** `1 << (s16)n` as an ARM register shift: only the low byte counts, 32 or more gives 0. */
    private fun bit(n: Int): Int {
        val sh = n.toShort().toInt() and 0xFF
        return if (sh >= 32) 0 else 1 shl sh
    }

    /**
     * Ported from src/eventscr.c: Event02_EvBitAndIdMod
     *
     * Sub command low 3 bits 0 clear/set an `evStateBits` bit (`evStateBits` is `u16`), 1
     * clear/set an event flag; the high bit (`0x8`) selects set.
     */
    public val event02EvBitAndIdMod: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val word = memory.u16(cur)
        val lo = word and 0x7
        val hi = (word and 0xF) shr 3
        val arg = evArg(cur)
        when (lo) {
            0 ->
                proc.evStateBits = if (hi == 0) {
                    proc.evStateBits and bit(arg).inv() and 0xFFFF
                } else {
                    (proc.evStateBits or bit(arg)) and 0xFFFF
                }
            1 -> {
                val id = arg.toShort().toInt()
                if (hi == 0) flags.clearFlag(id) else flags.setFlag(id)
            }
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event03_CheckEvBitOrId
     *
     * Sub command 0 stores whether an `evStateBits` bit is set in slot C, 1 whether an event
     * flag is set.
     */
    public val event03CheckEvBitOrId: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val arg = evArg(cur)
        when (subCmd(cur)) {
            0 -> {
                val sh = arg.toShort().toInt() and 0xFF
                state.slots[0xC] = if (sh < 32 && (proc.evStateBits ushr sh) and 1 != 0) 1 else 0
            }
            1 -> state.slots[0xC] = if (flags.checkFlag(arg.toShort().toInt())) 1 else 0
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event04_CheckRandom
     *
     * Slot C gets a draw in `0..max` (`NextRN_N(max + 1)`) from [rng], or 0 when `max` is 0
     * (no draw is consumed).
     */
    public val event04CheckRandom: EventHandler = EventHandler { proc ->
        val max = argU16(proc.pEventCurrent, 0)
        state.slots[0xC] = if (max == 0) 0 else rng.nextN(max + 1)
        EVC_ADVANCE_CONTINUE
    }

    /** Ported from src/eventscr.c: Event05_SetSlot */
    public val event05SetSlot: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        setSlot(argU16(cur, 0), arg32(cur))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event06_SlotOperation
     *
     * Division and modulo are unsigned (`gEventSlots` is `u32`). Assumption: a zero divisor
     * (libgcc's `__div0` returns) yields 0. Shifts use the low byte of the shift register as ARM
     * does, so counts of 32 or more give 0 (Kotlin would mask the count by 31).
     */
    public val event06SlotOperation: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val arg = argU16(cur, 0)
        val a = slot((arg shr 4) and 0xF)
        val b = slot((arg shr 8) and 0xF)
        val dest = arg and 0xF
        val sh = b and 0xFF
        val result = when (subCmd(cur)) {
            EVSUBCMD_SADD -> a + b
            EVSUBCMD_SSUB -> a - b
            EVSUBCMD_SMUL -> a * b
            EVSUBCMD_SDIV -> if (b == 0) 0 else Integer.divideUnsigned(a, b)
            EVSUBCMD_SMOD -> if (b == 0) 0 else Integer.remainderUnsigned(a, b)
            EVSUBCMD_SAND -> a and b
            EVSUBCMD_SORR -> a or b
            EVSUBCMD_SXOR -> a xor b
            EVSUBCMD_SLSL -> if (sh >= 32) 0 else a shl sh
            EVSUBCMD_SLSR -> if (sh >= 32) 0 else a ushr sh
            else -> return@EventHandler EVC_ERROR
        }
        setSlot(dest, result)
        EVC_ADVANCE_CONTINUE
    }

    /** Ported from src/eventscr.c: Event07_SlotQueueOperations */
    public val event07SlotQueueOperations: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        when (subCmd(cur)) {
            0 -> state.slotQueuePush(slot(argS16(cur, 0)))
            1 -> state.slotQueuePush(state.slots[1])
            2 -> setSlot(argS16(cur, 0), state.slotQueuePop())
        }
        EVC_ADVANCE_CONTINUE
    }

    /** Ported from src/eventscr.c: Event08_Label */
    public val event08Label: EventHandler = EventHandler { EVC_ADVANCE_CONTINUE }

    /**
     * Ported from src/eventscr.c: Event09_Goto
     *
     * Scans from `pEventIdk` for the label command with the argument of this command, leaving
     * `pEventCurrent` on it. Deviation: C loops forever when the label is missing; here the scan
     * stops after [GOTO_SCAN_LIMIT] commands and the handler returns `EVC_ERROR`.
     */
    public val event09Goto: EventHandler = EventHandler { proc -> goto(proc) }

    private fun goto(proc: EventEngineProc): Int {
        val ref = (argU16(proc.pEventCurrent, 0) shl 16) or (EV_CMD_LABEL shl 8) or 0x20
        var cur = proc.pEventIdk
        proc.pEventCurrent = cur
        var n = 0
        while ((memory.u16(cur) or (memory.u16(cur + 2) shl 16)) != ref) {
            if (++n > GOTO_SCAN_LIMIT) return EVC_ERROR
            cur += ((memory.u16(cur) ushr 4) and 0xF) * 2
        }
        proc.pEventCurrent = cur
        return EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event0A_Call
     *
     * Pushes `(pEventIdk, pEventCurrent)` on the 8-deep call stack (the oldest entry falls off)
     * and jumps to the target, or to slot 2 when the 32-bit target is negative.
     */
    public val event0aCall: EventHandler = EventHandler { proc ->
        var dst = arg32(proc.pEventCurrent)
        if (dst < 0) dst = state.slots[2]
        val q = state.activeQueue
        for (i in 15 downTo 2) q[i] = q[i - 2]
        q[0] = proc.pEventIdk
        q[1] = proc.pEventCurrent
        proc.pEventIdk = dst
        proc.pEventCurrent = dst
        EVC_UNK4
    }

    /**
     * Ported from src/eventscr.c: Event0B_EnqueueCall
     *
     * A negative target is replaced by slot 2; a null target does nothing. Sub command 0 calls
     * the event with this engine's `execType` (needs the engine given to [registerAll]), 1
     * records it as the tutorial event ([enqueueTutEvent]) with the argument as exec type.
     */
    public val event0bEnqueueCall: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        var ptr = arg32(cur)
        if (ptr < 0) ptr = state.slots[2]
        if (ptr != 0) {
            when (subCmd(cur)) {
                0 -> engine?.callEvent(ptr, proc.execType)
                1 -> enqueueTutEvent(ptr, argU16(cur, 0))
            }
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventinfo.c: EnqueueTutEvent
     *
     * Looks [ptr] up in the current chapter's tutorial event list ([EventScrHooks.tutorialEvent])
     * and, when found at index i, stores `tutorial_counter = i + 1` and
     * `tutorial_exec_type = eventEnqueueType` (u8) in [PlaySt].
     */
    public fun enqueueTutEvent(ptr: Int, eventEnqueueType: Int) {
        var i = 0
        while (true) {
            val e = hooks.tutorialEvent(playSt.chapterIndex, i)
            if (e == 0) return
            if (e == ptr) {
                playSt.tutorialCounter = (i + 1) and 0xFF
                playSt.tutorialExecType = eventEnqueueType and 0xFF
                return
            }
            i = (i + 1) and 0xFFFF
        }
    }

    /** Ported from src/eventscr.c: Event0C_Branch (compares the slots as signed `int`). */
    public val event0cBranch: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val v1 = slot(argU16(cur, 1))
        val v2 = slot(argU16(cur, 2))
        val taken = when (subCmd(cur)) {
            EVSUBCMD_BEQ -> v1 == v2
            EVSUBCMD_BNE -> v1 != v2
            EVSUBCMD_BGE -> v1 >= v2
            EVSUBCMD_BGT -> v1 > v2
            EVSUBCMD_BLE -> v1 <= v2
            EVSUBCMD_BLT -> v1 < v2
            else -> return@EventHandler EVC_ERROR
        }
        if (taken) goto(proc) else EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event0E_STAL
     *
     * Bit 0 of the sub command allows ending the stall with B or `EV_STATE_0008`, bit 1 allows
     * the 4x speed-up (game speed or A held) unless `EV_STATE_0040` is set.
     */
    public val event0eStal: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val sub = subCmd(cur)
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) {
            proc.evStallTimer = 0
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        if (sub and 1 != 0 &&
            (proc.evStateBits and EV_STATE_0008 != 0 || input.bPressed())
        ) {
            proc.evStallTimer = 0
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        val timer = proc.evStallTimer.toShort().toInt()
        if (timer <= 0) {
            proc.evStallTimer = memory.u16(cur + 2)
        } else {
            var step = 1
            if (proc.evStateBits and EV_STATE_0040 == 0 && sub and 2 != 0 &&
                (input.gameSpeed() != 0 || input.aHeld())
            ) {
                step = 4
            }
            val left = timer - step
            if (left <= 0) {
                proc.evStallTimer = 0
                return@EventHandler EVC_ADVANCE_CONTINUE
            }
            proc.evStallTimer = left and 0xFFFF
        }
        EVC_STOP_YIELD
    }

    /**
     * Ported from src/eventscr.c: Event10_ModifyEvBit
     *
     * While skipping, a non-zero argument first clears `EV_STATE_SKIPPING` and sets
     * `EV_STATE_FADEDIN`. Arguments 0-4 then set the NOSKIP/0020/0040 bits (0: all clear,
     * 1: all set, 2: only 0040, 3: only NOSKIP, 4: NOSKIP and 0020); anything else is an error.
     */
    public val event10ModifyEvBit: EventHandler = EventHandler { proc ->
        val arg = argU16(proc.pEventCurrent, 0)
        if (proc.evStateBits and EV_STATE_SKIPPING != 0 && arg != 0) {
            proc.evStateBits = (proc.evStateBits and EV_STATE_SKIPPING.inv()) or EV_STATE_FADEDIN
        }
        val mask = EV_STATE_NOSKIP or EV_STATE_0020 or EV_STATE_0040
        val set = when (arg) {
            0 -> 0
            1 -> mask
            2 -> EV_STATE_0040
            3 -> EV_STATE_NOSKIP
            4 -> EV_STATE_NOSKIP or EV_STATE_0020
            else -> return@EventHandler EVC_ERROR
        }
        proc.evStateBits = (proc.evStateBits and mask.inv()) or set
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event11_SetIgnoredKeys
     *
     * Sub command 0 sets the key ignore mask ([KeyStatus.applyIgnoreMask]) to the argument.
     */
    public val event11SetIgnoredKeys: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        if (subCmd(cur) == 0) keys.applyIgnoreMask(argU16(cur, 0))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event19_Checks
     *
     * Stores a game-state query in slot C. The unit counts, battle map kind and event trigger id
     * come from [EventScrHooks]; the rest reads [PlaySt] and the proc. Unknown sub commands do
     * nothing.
     */
    public val event19Checks: EventHandler = EventHandler { proc ->
        val c = state.slots
        when (subCmd(proc.pEventCurrent)) {
            0 -> c[0xC] = playSt.chapterModeIndex
            1 -> c[0xC] = proc.chapterIndex
            2 -> c[0xC] = if (playSt.chapterStateBits and PLAY_FLAG_HARD != 0) 1 else 0
            3 -> c[0xC] = playSt.chapterTurnNumber
            4 -> c[0xC] = hooks.countRedUnits() and 0xFFFF
            5 -> c[0xC] = hooks.countGreenUnits() and 0xFFFF
            6 -> c[0xC] = hooks.battleMapKind()
            7 -> {
                val hard = playSt.chapterStateBits and PLAY_FLAG_HARD != 0
                c[0xC] = if (playSt.controller != 0 || hard) 0 else 1
            }
            8 -> c[0xC] = if (playSt.chapterIndex == CHAPTER_NO_GOLD) 0 else playSt.partyGoldAmount
            9 -> c[0xC] = hooks.eventTriggerId(proc.pEventStart) and 0xFFFF
            0xA -> c[0xC] = if (playSt.chapterStateBits and PLAY_FLAG_COMPLETE != 0) 1 else 0
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr.c: Event0F_CounterOps
     *
     * Nibble `(low byte of word 1) % 8` of `gEventSlotCounter`. An unknown sub command is
     * undefined in C (uninitialized `newValue`); here it leaves the counter untouched.
     */
    public val event0fCounterOps: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val shift = 4 * ((memory.u16(cur + 2) and 0xFF) % 8)
        val old = (state.slotCounter ushr shift) and 0xF
        val new = when (subCmd(cur)) {
            EVSUBCMD_COUNTER_CHECK -> {
                state.setEventSlotC(old)
                return@EventHandler EVC_ADVANCE_CONTINUE
            }
            EVSUBCMD_COUNTER_SET -> argU16(cur, 0) ushr 8
            EVSUBCMD_COUNTER_INC -> minOf(old + 1, 0xF)
            EVSUBCMD_COUNTER_DEC -> maxOf(old - 1, 0)
            else -> return@EventHandler EVC_ADVANCE_CONTINUE
        }
        state.setEventSlotCounter((state.slotCounter and (0xF shl shift).inv()) or
            ((new and 0xF) shl shift))
        EVC_ADVANCE_CONTINUE
    }

    /** Registers the handlers of this class on [engine]. */
    public fun registerAll(engine: EventEngine) {
        this.engine = engine
        engine.register(0x04, event04CheckRandom)
        engine.register(0x0B, event0bEnqueueCall)
        engine.register(0x10, event10ModifyEvBit)
        engine.register(0x11, event11SetIgnoredKeys)
        engine.register(0x19, event19Checks)
        engine.register(0x00, event00Null)
        engine.register(0x01, event01End)
        engine.register(0x02, event02EvBitAndIdMod)
        engine.register(0x03, event03CheckEvBitOrId)
        engine.register(0x05, event05SetSlot)
        engine.register(0x06, event06SlotOperation)
        engine.register(0x07, event07SlotQueueOperations)
        engine.register(0x08, event08Label)
        engine.register(0x09, event09Goto)
        engine.register(0x0A, event0aCall)
        engine.register(0x0C, event0cBranch)
        engine.register(0x0E, event0eStal)
        engine.register(0x0F, event0fCounterOps)
    }

    private companion object {
        const val EV_CMD_LABEL = 0x08
        const val CHAPTER_NO_GOLD = 5
        const val PLAY_FLAG_COMPLETE = 1 shl 5
        const val GOTO_SCAN_LIMIT = 0x10000
        const val EVSUBCMD_ENDB = 1
        const val EVSUBCMD_SADD = 0
        const val EVSUBCMD_SSUB = 1
        const val EVSUBCMD_SMUL = 2
        const val EVSUBCMD_SDIV = 3
        const val EVSUBCMD_SMOD = 4
        const val EVSUBCMD_SAND = 5
        const val EVSUBCMD_SORR = 6
        const val EVSUBCMD_SXOR = 7
        const val EVSUBCMD_SLSL = 8
        const val EVSUBCMD_SLSR = 9
        const val EVSUBCMD_BEQ = 0
        const val EVSUBCMD_BNE = 1
        const val EVSUBCMD_BGE = 2
        const val EVSUBCMD_BGT = 3
        const val EVSUBCMD_BLE = 4
        const val EVSUBCMD_BLT = 5
        const val EVSUBCMD_COUNTER_CHECK = 0
        const val EVSUBCMD_COUNTER_SET = 1
        const val EVSUBCMD_COUNTER_INC = 2
        const val EVSUBCMD_COUNTER_DEC = 3
    }
}
