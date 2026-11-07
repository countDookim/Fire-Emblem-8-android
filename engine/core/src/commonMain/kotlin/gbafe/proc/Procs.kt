package gbafe.proc

/** One proc script command (`struct ProcCmd`: opcode, 16-bit immediate, pointer). */
public class ProcCmd(
    /** Opcode 0x00..0x19 (`PROC_*` macros in `include/proc.h`). */
    public val opcode: Int,
    /** `dataImm`: label, sleep time, mark or argument. */
    public val imm: Int = 0,
    /**
     * `dataPtr`: a [ProcFunc], [ProcBoolFunc], [ProcArgFunc], script factory or name, depending
     * on [opcode].
     */
    public val ptr: Any? = null,
)

/** A per-frame or one-shot proc callback (`ProcFunc`). */
public fun interface ProcFunc {
    /** Runs the callback on [proc]. */
    public fun invoke(proc: Proc)
}

/** A callback returning the C `s8` "keep going" flag (`PROC_WHILE`, `PROC_CALL_2`). */
public fun interface ProcBoolFunc {
    /** Runs the callback on [proc]. */
    public fun invoke(proc: Proc): Boolean
}

/** A `PROC_CALL_ARG` callback. */
public fun interface ProcArgFunc {
    /** Runs the callback with the command's [arg] on [proc]. */
    public fun invoke(arg: Int, proc: Proc): Boolean
}

/**
 * A proc script: the command list plus a factory for the proc's own fields (the struct past
 * `PROC_HEADER`).
 */
public class ProcScript(
    /** Debug name (the C symbol, e.g. `ProcScr_GameEarlyStartUI`). */
    public val name: String,
    /** Commands; must end with [ProcScripts.end]. */
    public val cmds: List<ProcCmd>,
    /** Creates the proc-specific state stored in [Proc.state] when the proc starts. */
    public val newState: () -> Any? = { null },
)

/** Builders mirroring the `PROC_*` macros. */
public object ProcScripts {
    /** `PROC_END`. */
    public fun end(): ProcCmd = ProcCmd(0x00)

    /** `PROC_NAME`. */
    public fun name(procName: String): ProcCmd = ProcCmd(0x01, 0, procName)

    /** `PROC_CALL`. */
    public fun call(callback: ProcFunc): ProcCmd = ProcCmd(0x02, 0, callback)

    /** `PROC_REPEAT`. */
    public fun repeat(callback: ProcFunc): ProcCmd = ProcCmd(0x03, 0, callback)

    /** `PROC_SET_END_CB`. */
    public fun setEndCb(callback: ProcFunc): ProcCmd = ProcCmd(0x04, 0, callback)

    /** `PROC_START_CHILD`. */
    public fun startChild(script: () -> ProcScript): ProcCmd = ProcCmd(0x05, 0, script)

    /** `PROC_START_CHILD_BLOCKING`. */
    public fun startChildBlocking(script: () -> ProcScript): ProcCmd =
        ProcCmd(0x06, 1, script)

    /** `PROC_WHILE_EXISTS`. */
    public fun whileExists(script: () -> ProcScript): ProcCmd = ProcCmd(0x08, 0, script)

    /** `PROC_END_EACH`. */
    public fun endEach(script: () -> ProcScript): ProcCmd = ProcCmd(0x09, 0, script)

    /** `PROC_BREAK_EACH`. */
    public fun breakEach(script: () -> ProcScript): ProcCmd = ProcCmd(0x0A, 0, script)

    /**
     * `PROC_JUMP`: continue in another script (the proc keeps its own `proc_script` for labels).
     */
    public fun jump(script: () -> ProcScript): ProcCmd = ProcCmd(0x0D, 0, script)

    /** `PROC_LABEL`. */
    public fun label(labelId: Int): ProcCmd = ProcCmd(0x0B, labelId)

    /** `PROC_GOTO`. */
    public fun goto(labelId: Int): ProcCmd = ProcCmd(0x0C, labelId)

    /** `PROC_SLEEP`. */
    public fun sleep(frames: Int): ProcCmd = ProcCmd(0x0E, frames)

    /** `PROC_YIELD` (= `PROC_SLEEP(0)`). */
    public fun yield(): ProcCmd = sleep(0)

    /** `PROC_MARK`. */
    public fun mark(markValue: Int): ProcCmd = ProcCmd(0x0F, markValue)

    /** `PROC_BLOCK`. */
    public fun block(): ProcCmd = ProcCmd(0x10)

    /** `PROC_END_IF_DUPLICATE`. */
    public fun endIfDuplicate(): ProcCmd = ProcCmd(0x11)

    /** `PROC_WHILE`. */
    public fun whileTrue(condition: ProcBoolFunc): ProcCmd = ProcCmd(0x14, 0, condition)

    /** `PROC_CALL_2`. */
    public fun call2(callback: ProcBoolFunc): ProcCmd = ProcCmd(0x16, 0, callback)

    /** `PROC_END_DUPLICATES`. */
    public fun endDuplicates(): ProcCmd = ProcCmd(0x17)

    /** `PROC_CALL_ARG`. */
    public fun callArg(callback: ProcArgFunc, arg: Int): ProcCmd = ProcCmd(0x18, arg, callback)
}

/** One proc slot (`struct Proc` header); [state] holds the script-specific fields. */
public class Proc internal constructor(
    /** Index in the proc array (`sProcArray`). */
    public val slot: Int,
) {
    /** `proc_script`, null when free. */
    public var script: ProcScript? = null

    /** The script `proc_scrCur` points into: [script], or another script after a `PROC_JUMP`. */
    public var scrScript: ProcScript? = null

    /** `proc_scrCur` as an index into [scrScript]'s commands. */
    public var scrCur: Int = 0

    /** `proc_endCb`. */
    public var endCb: ProcFunc? = null

    /** `proc_idleCb`: the repeat callback; script execution is paused while set. */
    public var idleCb: ProcFunc? = null

    /** `proc_name`. */
    public var name: String? = null

    /** `proc_parent` when it is a proc; null for a root proc (see [rootIndex]). */
    public var parent: Proc? = null

    /** Root tree index (0..7) of a root proc, or -1. */
    public var rootIndex: Int = -1

    /** `proc_child`: most recently added child. */
    public var child: Proc? = null

    /** `proc_next`: next (younger) sibling. */
    public var next: Proc? = null

    /** `proc_prev`: previous (older) sibling. */
    public var prev: Proc? = null

    /** `proc_sleepTime` (s16). */
    public var sleepTime: Int = 0

    /** `proc_mark` (u8). */
    public var mark: Int = 0

    /** `proc_flags` (u8, `PROC_FLAG_*`). */
    public var flags: Int = 0

    /** `proc_lockCnt` (u8): script and repeat callback are blocked while non-zero. */
    public var lockCnt: Int = 0

    /** The script's own fields ([ProcScript.newState]). */
    public var state: Any? = null
}
