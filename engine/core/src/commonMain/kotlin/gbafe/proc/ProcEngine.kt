package gbafe.proc

/**
 * The GBA FE proc ("process") scheduler, decomp `src/proc.c`: a fixed array of [maxProcs] procs allocated from a
 * free-list stack, 8 root trees, scripts of [ProcCmd]s run until a command yields, and per-frame repeat callbacks.
 * Shared by FE6/FE7/FE8 (same design; FE8 has 64 procs).
 */
public class ProcEngine(
    /** `MAX_PROC_COUNT`. */
    public val maxProcs: Int = 64,
) {
    /** `sProcArray`. */
    public val procs: List<Proc> = List(maxProcs) { Proc(it) }

    private val allocList = arrayOfNulls<Proc>(maxProcs + 1)
    private var allocHead = 0
    private val roots = arrayOfNulls<Proc>(8)

    init {
        init()
    }

    /**
     * Clears every proc and the root trees and refills the allocation stack in array order.
     *
     * Ported from src/proc.c: Proc_Init
     */
    public fun init() {
        for (p in procs) {
            p.script = null; p.scrScript = null; p.scrCur = 0; p.endCb = null; p.idleCb = null; p.name = null
            p.parent = null; p.rootIndex = -1; p.child = null; p.next = null; p.prev = null
            p.sleepTime = 0; p.mark = 0; p.flags = 0; p.lockCnt = 0; p.state = null
            allocList[p.slot] = p
        }
        allocList[maxProcs] = null
        allocHead = 0
        roots.fill(null)
    }

    /** The youngest root proc of tree [index] (`ROOT_PROC`). */
    public fun root(index: Int): Proc? = roots[index]

    /**
     * Starts [script] as a root proc of tree [rootIndex] (0..7) and runs it until it yields.
     *
     * Ported from src/proc.c: Proc_Start
     */
    public fun start(script: ProcScript, rootIndex: Int): Proc = start(script, null, rootIndex)

    /** Starts [script] as a child of [parent] and runs it until it yields ([start] with a proc parent). */
    public fun start(script: ProcScript, parent: Proc): Proc = start(script, parent, -1)

    private fun start(script: ProcScript, parent: Proc?, rootIndex: Int): Proc {
        val proc = allocList[allocHead++] ?: error("proc array full")
        proc.script = script
        proc.scrScript = script
        proc.scrCur = 0
        proc.endCb = null; proc.idleCb = null
        proc.parent = null; proc.rootIndex = -1; proc.child = null; proc.next = null; proc.prev = null
        proc.sleepTime = 0; proc.mark = 0; proc.lockCnt = 0
        proc.flags = FLAG_STARTING
        proc.state = script.newState()
        if (parent == null) insertRoot(proc, rootIndex) else insertChild(proc, parent)
        runScript(proc)
        proc.flags = proc.flags and FLAG_STARTING.inv()
        return proc
    }

    /**
     * [start] as a child of [parent] that blocks the parent (its `lockCnt`) until it ends; null if the child
     * ended while starting.
     *
     * Ported from src/proc.c: Proc_StartBlocking
     */
    public fun startBlocking(script: ProcScript, parent: Proc): Proc? {
        val proc = start(script, parent)
        if (proc.script == null) return null
        proc.flags = proc.flags or FLAG_BLOCKING
        parent.lockCnt++
        return proc
    }

    /**
     * Unlinks [proc] and ends it and all its descendants (children first), running their end callbacks
     * (`DeleteProcessRecursive`).
     *
     * Ported from src/proc.c: Proc_End
     */
    public fun end(proc: Proc?) {
        if (proc == null) return
        unlink(proc)
        deleteRecursive(proc)
    }

    private fun deleteRecursive(proc: Proc) {
        proc.prev?.let { deleteRecursive(it) }
        proc.child?.let { deleteRecursive(it) }
        if (proc.flags and FLAG_ENDED != 0) return
        proc.endCb?.invoke(proc)
        allocList[--allocHead] = proc
        proc.script = null
        proc.idleCb = null
        proc.flags = proc.flags or FLAG_ENDED
        if (proc.flags and FLAG_BLOCKING != 0) proc.parent!!.lockCnt--
    }

    private fun insertRoot(proc: Proc, rootIndex: Int) {
        val root = roots[rootIndex]
        if (root != null) {
            root.next = proc
            proc.prev = root
        }
        proc.rootIndex = rootIndex
        roots[rootIndex] = proc
    }

    private fun insertChild(proc: Proc, parent: Proc) {
        parent.child?.let { it.next = proc; proc.prev = it }
        parent.child = proc
        proc.parent = parent
    }

    private fun unlink(proc: Proc) {
        proc.next?.prev = proc.prev
        proc.prev?.next = proc.next
        val parent = proc.parent
        if (parent != null) {
            if (parent.child === proc) parent.child = proc.prev
        } else if (roots[proc.rootIndex] === proc) {
            roots[proc.rootIndex] = proc.prev
        }
        proc.next = null
        proc.prev = null
    }

    private fun runRecursive(proc: Proc) {
        proc.prev?.let { runRecursive(it) }
        if (proc.lockCnt == 0 && proc.flags and FLAG_STARTING == 0) {
            if (proc.idleCb == null) runScript(proc)
            proc.idleCb?.invoke(proc)
            if (proc.flags and FLAG_ENDED != 0) return
        }
        proc.child?.let { runRecursive(it) }
    }

    /**
     * Runs one frame of [proc], its older siblings and all their children (pre-order): scripts advance until a
     * command yields, then repeat callbacks run.
     *
     * Ported from src/proc.c: Proc_Run
     */
    public fun run(proc: Proc?) {
        if (proc != null) runRecursive(proc)
    }

    /**
     * Clears [proc]'s repeat callback: its script continues next frame.
     *
     * Ported from src/proc.c: Proc_Break
     */
    public fun breakProc(proc: Proc) {
        proc.idleCb = null
    }

    /**
     * The first live proc running [script] in array order, or null.
     *
     * Ported from src/proc.c: Proc_Find
     */
    public fun find(script: ProcScript): Proc? = procs.firstOrNull { it.script === script }

    /**
     * Jumps [proc] to `PROC_LABEL(label)` in its script and clears its repeat callback; no-op if the label is
     * missing.
     *
     * Ported from src/proc.c: Proc_Goto
     */
    public fun goto(proc: Proc, label: Int) {
        val script = proc.script!!
        val cmds = script.cmds
        var i = 0
        while (cmds[i].opcode != 0) {
            if (cmds[i].opcode == 0x0B && cmds[i].imm == label) {
                proc.scrScript = script
                proc.scrCur = i
                proc.idleCb = null
                return
            }
            i++
        }
    }

    /** Ends every proc running [script] (`Proc_EndEach`). */
    public fun endEach(script: ProcScript) {
        for (p in procs) if (p.script === script) end(p)
    }

    /** Clears the repeat callback of every proc running [script] (`Proc_BreakEach`). */
    public fun breakEach(script: ProcScript) {
        for (p in procs) if (p.script === script) p.idleCb = null
    }

    /**
     * Sets [proc]'s mark.
     *
     * Ported from src/proc.c: Proc_SetMark
     */
    public fun setMark(proc: Proc, mark: Int) {
        proc.mark = mark and 0xFF
    }

    /**
     * Sets [proc]'s end callback.
     *
     * Transcribed from src/proc.c: Proc_SetEndCb (not verified: the game never calls it)
     */
    public fun setEndCb(proc: Proc, func: ProcFunc?) {
        proc.endCb = func
    }

    /**
     * Sets [proc]'s repeat callback.
     *
     * Transcribed from src/proc.c: Proc_SetRepeatCb (not verified: its only caller,
     *   PlayPhaseForcePressAButtonInRangeDisp in playerphase.c, never runs in the 41 reference
     *   runs)
     */
    public fun setRepeatCb(proc: Proc, func: ProcFunc?) {
        proc.idleCb = func
    }

    /**
     * Calls [func] on every live proc in array order.
     *
     * Transcribed from src/proc.c: Proc_ForAll (not verified: its only callers, sub_8009A84 via
     *   GameControl label 3 and ClassReel_OnEnd, were not found running in any of the 41 reference
     *   runs; the search was not exhaustive)
     */
    public fun forAll(func: ProcFunc) {
        for (p in procs) if (p.script != null) func.invoke(p)
    }

    /**
     * Calls [func] on every proc running [script] in array order.
     *
     * Ported from src/proc.c: Proc_ForEach
     */
    public fun forEach(script: ProcScript, func: ProcFunc) {
        for (p in procs) if (p.script === script) func.invoke(p)
    }

    /**
     * Calls [func] on every proc (ended ones included, as in the original) with [mark].
     *
     * Transcribed from src/proc.c: Proc_ForEachMarked (not verified: the game never calls it)
     */
    public fun forEachMarked(mark: Int, func: ProcFunc) {
        for (p in procs) if (p.mark == mark) func.invoke(p)
    }

    /**
     * Increments the lock count of every proc with [mark].
     *
     * Ported from src/proc.c: Proc_BlockEachMarked
     */
    public fun blockEachMarked(mark: Int) {
        for (p in procs) if (p.mark == mark) p.lockCnt = (p.lockCnt + 1) and 0xFF
    }

    /**
     * Decrements the lock count of every locked proc with [mark].
     *
     * Ported from src/proc.c: Proc_UnblockEachMarked
     */
    public fun unblockEachMarked(mark: Int) {
        for (p in procs) if (p.mark == mark && p.lockCnt > 0) p.lockCnt--
    }

    /**
     * Ends every proc with [mark].
     *
     * Ported from src/proc.c: Proc_EndEachMarked
     */
    public fun endEachMarked(mark: Int) {
        for (p in procs) if (p.mark == mark) end(p)
    }

    /**
     * The first proc running [script] after [proc] in array order (from the start if [proc] is null), or null.
     *
     * Ported from src/proc.c: Proc_FindAfter
     */
    public fun findAfter(script: ProcScript?, proc: Proc?): Proc? {
        for (i in (proc?.slot?.plus(1) ?: 0) until maxProcs) if (procs[i].script === script) return procs[i]
        return null
    }

    /**
     * The first proc whose parent is [parent] after [proc] in array order (from the start if [proc] is null), or
     * null.
     *
     * Ported from src/proc.c: Proc_FindAfterWithParent
     */
    public fun findAfterWithParent(proc: Proc?, parent: Proc?): Proc? {
        for (i in (proc?.slot?.plus(1) ?: 0) until maxProcs) if (procs[i].parent === parent) return procs[i]
        return null
    }

    /**
     * Ends every child of [parent] (and their subtrees), in array order.
     *
     * Transcribed from src/sysutil.c: EndAllProcChildren (not verified: the traces have no children
     * left to end when it runs)
     */
    public fun endAllChildren(parent: Proc) {
        var proc: Proc? = null
        while (true) {
            proc = findAfterWithParent(proc, parent) ?: break
            end(proc)
        }
    }

    /**
     * Number of procs running [script], or of all live procs if [script] is null.
     *
     * Ported from src/proc.c: CountProcs
     */
    public fun count(script: ProcScript?): Int =
        if (script == null) procs.count { it.script != null } else procs.count { it.script === script }

    private val sleepCb = ProcFunc { p ->
        p.sleepTime--
        if (p.sleepTime == 0) breakProc(p)
    }

    /** Executes the command at [proc]'s `scrCur`; true = continue with the next command this frame. */
    private fun exec(proc: Proc): Boolean {
        val script = proc.script!!
        val cmd = proc.scrScript!!.cmds[proc.scrCur]
        when (cmd.opcode) {
            0x00 -> { end(proc); return false }
            0x01 -> { proc.name = cmd.ptr as String; proc.scrCur++ }
            0x02 -> { proc.scrCur++; (cmd.ptr as ProcFunc).invoke(proc) }
            0x03 -> { proc.idleCb = cmd.ptr as ProcFunc; proc.scrCur++; return false }
            0x04 -> { proc.endCb = cmd.ptr as ProcFunc; proc.scrCur++ }
            0x05 -> { start(scriptOf(cmd), proc); proc.scrCur++ }
            0x06 -> { startBlocking(scriptOf(cmd), proc); proc.scrCur++; return false }
            0x07 -> { start(scriptOf(cmd), null, proc.sleepTime); proc.scrCur++ } // root index from sleepTime (sic)
            0x08 -> { if (find(scriptOf(cmd)) != null) return false; proc.scrCur++ }
            0x09 -> { endEach(scriptOf(cmd)); proc.scrCur++ }
            0x0A -> { breakEach(scriptOf(cmd)); proc.scrCur++ }
            0x0B, 0x13, 0x15, 0x19 -> proc.scrCur++
            0x0C -> goto(proc, cmd.imm)
            0x0D -> { proc.scrScript = scriptOf(cmd); proc.scrCur = 0; proc.idleCb = null } // Proc_GotoScript
            0x0E -> {
                if (cmd.imm != 0) { proc.sleepTime = cmd.imm; proc.idleCb = sleepCb }
                proc.scrCur++
                return false
            }
            0x0F -> { proc.mark = cmd.imm and 0xFF; proc.scrCur++ }
            0x10 -> return false
            0x11 -> {
                if (procs.count { it.script === script } > 1) { end(proc); return false }
                proc.scrCur++
            }
            0x12 -> { proc.flags = proc.flags or FLAG_UNK2; proc.scrCur++ }
            0x14 -> {
                proc.scrCur++
                if ((cmd.ptr as ProcBoolFunc).invoke(proc)) { proc.scrCur--; return false }
            }
            0x16 -> { proc.scrCur++; return (cmd.ptr as ProcBoolFunc).invoke(proc) }
            0x17 -> {
                procs.firstOrNull { it !== proc && it.script === script }?.let { end(it) }
                proc.scrCur++
            }
            0x18 -> { proc.scrCur++; return (cmd.ptr as ProcArgFunc).invoke(cmd.imm.toShort().toInt(), proc) }
            else -> error("bad proc opcode ${cmd.opcode}")
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun scriptOf(cmd: ProcCmd): ProcScript = (cmd.ptr as () -> ProcScript)()

    private fun runScript(proc: Proc) {
        if (proc.script == null || proc.lockCnt > 0 || proc.idleCb != null) return
        while (exec(proc)) {
            if (proc.script == null) return
        }
    }

    /** `PROC_FLAG_*` bits of [Proc.flags]. */
    public companion object {
        /** Proc has ended (`PROC_FLAG_ENDED`). */
        public const val FLAG_ENDED: Int = 0x01

        /** Proc is blocking its parent (`PROC_FLAG_BLOCKING`). */
        public const val FLAG_BLOCKING: Int = 0x02

        /** Bit set by `PROC_SET_BIT4` (`PROC_FLAG_UNK2`). */
        public const val FLAG_UNK2: Int = 0x04

        /** Proc is inside [start] (`PROC_FLAG_STARTING`). */
        public const val FLAG_STARTING: Int = 0x08
    }
}
