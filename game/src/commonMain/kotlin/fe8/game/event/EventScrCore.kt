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
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_ABORT
import fe8.game.event.EventConst.EV_STATE_NOSKIP
import fe8.game.event.EventConst.EV_STATE_SKIPPING

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
) {
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
        engine.register(0x00, event00Null)
        engine.register(0x01, event01End)
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
