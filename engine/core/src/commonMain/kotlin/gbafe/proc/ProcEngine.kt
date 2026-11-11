package gbafe.proc

/**
 * The GBA FE proc ("process") scheduler, decomp `src/proc.c`: the game's cooperative multitasking.
 * A proc is a small script ([ProcScript] of [ProcCmd]s) that runs until a command yields (sleep,
 * wait for a child, repeat callback) and continues on a later frame. Procs live in a fixed array
 * of [maxProcs] slots handed out from a free-slot stack and form 8 root trees (tree 0 is
 * `PROC_TREE_VSYNC`) of parents, children and siblings. [run] advances one tree per call.
 */
public class ProcEngine(
    /** Number of proc slots (`MAX_PROC_COUNT`, 64 in the decomp). */
    public val maxProcs: Int = 64,
) {
    /** All proc slots, used or free, indexed by [Proc.slot] (`sProcArray`). */
    private val procArray: Array<Proc> = Array(maxProcs) { Proc(it) }

    /** Read-only view of every proc slot, free ones included (a free slot has a null script). */
    public val procs: List<Proc> = procArray.asList()

    /** Free-proc stack (`sProcAllocList`): entries below [allocHead] are in use, the rest free. */
    private val allocList = arrayOfNulls<Proc>(maxProcs + 1)

    /** Index of the next free entry in [allocList] (`sProcAllocListHead`). */
    private var allocHead = 0

    /** Youngest root proc of each of the 8 root trees (`gProcTreeRootArray`). */
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
        for (i in 0 until maxProcs) {
            val slotProc = procArray[i]
            slotProc.script = null; slotProc.scrScript = null; slotProc.scrCur = 0
            slotProc.endCb = null; slotProc.idleCb = null; slotProc.name = null
            slotProc.parent = null; slotProc.rootIndex = -1; slotProc.child = null
            slotProc.next = null; slotProc.prev = null
            slotProc.sleepTime = 0; slotProc.mark = 0; slotProc.flags = 0; slotProc.lockCnt = 0
            slotProc.state = null
            allocList[slotProc.slot] = slotProc
        }
        allocList[maxProcs] = null
        allocHead = 0
        roots.fill(null)
    }

    /**
     * The youngest root proc of tree [index] (`ROOT_PROC`), or null if the tree is empty. Older
     * roots of the tree are reached through [Proc.prev].
     */
    public fun root(index: Int): Proc? = roots[index]

    /**
     * Starts [script] as a root proc of tree [rootIndex] (0..7, the C `parent` argument below 8)
     * and runs it until it yields.
     *
     * Ported from src/proc.c: Proc_Start
     */
    public fun start(script: ProcScript, rootIndex: Int): Proc = start(script, null, rootIndex)

    /**
     * Starts [script] as a child of [parent] and runs it until it yields (`Proc_Start` with a proc
     * pointer as the parent).
     */
    public fun start(script: ProcScript, parent: Proc): Proc = start(script, parent, -1)

    /**
     * Shared body of the public `start` overloads (`Proc_Start`, which the C code also uses for
     * [startBlocking]): pops a free slot, resets it, links it under [parent] (or into root tree
     * [rootIndex] when [parent] is null) and runs its script until it yields. Throws when all
     * [maxProcs] slots are in use.
     */
    private fun start(script: ProcScript, parent: Proc?, rootIndex: Int): Proc {
        val proc = allocList[allocHead++] ?: error("proc array full")
        proc.script = script
        proc.scrScript = script
        proc.scrCur = 0
        proc.endCb = null; proc.idleCb = null
        proc.parent = null; proc.rootIndex = -1; proc.child = null
        proc.next = null; proc.prev = null
        proc.sleepTime = 0; proc.mark = 0; proc.lockCnt = 0
        // STARTING keeps the frame loop from re-running the script while it is still being started.
        proc.flags = FLAG_STARTING
        proc.state = script.newState()
        if (parent == null) insertRoot(proc, rootIndex) else insertChild(proc, parent)
        runScript(proc)
        proc.flags = proc.flags and FLAG_STARTING.inv()
        return proc
    }

    /**
     * [start] as a child of [parent] that blocks the parent (raises its `lockCnt`, so the parent's
     * script and repeat callback pause) until the child ends; null if the child already ended
     * while starting.
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
     * Unlinks [proc] from its siblings and parent, then ends it and all its descendants (children
     * before parent), running their end callbacks (`DeleteProcessRecursive`). No-op for null.
     *
     * Ported from src/proc.c: Proc_End
     */
    public fun end(proc: Proc?) {
        if (proc == null) return
        unlink(proc)
        deleteRecursive(proc)
    }

    /**
     * Ends [proc]'s older siblings (empty after [unlink] on the public path), its children, then
     * [proc] itself (`DeleteProcessRecursive`): runs the end callback, returns the slot to the free
     * stack and releases the parent if [proc] was blocking it. Skips procs that already ended.
     */
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

    /** Makes [proc] the youngest root of tree [rootIndex], chaining the old root as its elder. */
    private fun insertRoot(proc: Proc, rootIndex: Int) {
        val previousRoot = roots[rootIndex]
        if (previousRoot != null) {
            previousRoot.next = proc
            proc.prev = previousRoot
        }
        proc.rootIndex = rootIndex
        roots[rootIndex] = proc
    }

    /** Makes [proc] [parent]'s youngest child, chaining the old youngest child as its elder. */
    private fun insertChild(proc: Proc, parent: Proc) {
        parent.child?.let { it.next = proc; proc.prev = it }
        parent.child = proc
        proc.parent = parent
    }

    /**
     * Cuts [proc] out of its sibling chain and, if it was the youngest child (or root), makes its
     * elder sibling the new youngest (`UnlinkProcess`).
     */
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

    /**
     * One frame for [proc] (`RunProcessRecursive`): older siblings first, then [proc] (script,
     * then repeat callback) unless it is blocked (`lockCnt`) or still starting, then its children.
     * Does not descend into the children if [proc] ended during its own turn.
     */
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
     * Runs one frame of [proc], its older siblings and all their children (pre-order): each proc's
     * script advances until a command yields (unless a repeat callback is set), then its repeat
     * callback runs. Callers pass a tree's [root].
     *
     * Ported from src/proc.c: Proc_Run
     */
    public fun run(proc: Proc?) {
        if (proc != null) runRecursive(proc)
    }

    /**
     * Clears [proc]'s repeat callback so its script resumes on the next [run] (`Proc_Break`).
     *
     * Ported from src/proc.c: Proc_Break
     */
    public fun breakProc(proc: Proc) {
        proc.idleCb = null
    }

    /**
     * The first proc running [script] in array order, or null (`Proc_Find`).
     *
     * Ported from src/proc.c: Proc_Find
     */
    public fun find(script: ProcScript): Proc? {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.script === script) return candidate
        }
        return null
    }

    /**
     * Moves [proc] to the `PROC_LABEL(label)` command of its own [Proc.script] (not of the script
     * a `PROC_JUMP` moved it into) and clears its repeat callback; no-op if the label is missing.
     *
     * Ported from src/proc.c: Proc_Goto
     */
    public fun goto(proc: Proc, label: Int) {
        val script = proc.script!!
        val cmds = script.cmds
        var cmdIndex = 0
        while (cmds[cmdIndex].opcode != 0) {
            if (cmds[cmdIndex].opcode == 0x0B && cmds[cmdIndex].imm == label) {
                proc.scrScript = script
                proc.scrCur = cmdIndex
                proc.idleCb = null
                return
            }
            cmdIndex++
        }
    }

    /** Ends every proc running [script], children included (`Proc_EndEach`). */
    public fun endEach(script: ProcScript) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.script === script) end(candidate)
        }
    }

    /** Clears the repeat callback of every proc running [script] (`Proc_BreakEach`). */
    public fun breakEach(script: ProcScript) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.script === script) candidate.idleCb = null
        }
    }

    /**
     * Sets [proc]'s mark, the group tag for the `*EachMarked` functions (`PROC_MARK_*` in
     * `include/proc.h`), truncated to its u8 field.
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
     * Calls [func] on every live proc (non-null script) in array order.
     *
     * Transcribed from src/proc.c: Proc_ForAll (not verified: its only callers, sub_8009A84 via
     *   GameControl label 3 and ClassReel_OnEnd, were not found running in any of the 41 reference
     *   runs; the search was not exhaustive)
     */
    public fun forAll(func: ProcFunc) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.script != null) func.invoke(candidate)
        }
    }

    /**
     * Calls [func] on every proc running [script] in array order.
     *
     * Ported from src/proc.c: Proc_ForEach
     */
    public fun forEach(script: ProcScript, func: ProcFunc) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.script === script) func.invoke(candidate)
        }
    }

    /**
     * Calls [func] on every slot whose mark equals [mark], in array order. Free slots are
     * included, as in the original (a freed proc keeps its mark, and free slots start at mark 0).
     *
     * Transcribed from src/proc.c: Proc_ForEachMarked (not verified: the game never calls it)
     */
    public fun forEachMarked(mark: Int, func: ProcFunc) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.mark == mark) func.invoke(candidate)
        }
    }

    /**
     * Adds a block (`lockCnt`) to every proc with [mark], so none of them runs until
     * [unblockEachMarked] (e.g. `BMapDispSuspend` freezes the `PROC_MARK_DISP` procs while the map
     * graphics are suspended). Free slots
     * with the mark are counted too, as in the original; the count wraps as a u8.
     *
     * Ported from src/proc.c: Proc_BlockEachMarked
     */
    public fun blockEachMarked(mark: Int) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.mark == mark) candidate.lockCnt = (candidate.lockCnt + 1) and 0xFF
        }
    }

    /**
     * Removes one block (`lockCnt`) from every proc with [mark] that has one, undoing
     * [blockEachMarked].
     *
     * Ported from src/proc.c: Proc_UnblockEachMarked
     */
    public fun unblockEachMarked(mark: Int) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.mark == mark && candidate.lockCnt > 0) candidate.lockCnt--
        }
    }

    /**
     * Ends every proc with [mark] (running end callbacks); free slots with that mark are skipped
     * because [end] ignores procs that already ended.
     *
     * Ported from src/proc.c: Proc_EndEachMarked
     */
    public fun endEachMarked(mark: Int) {
        for (i in 0 until maxProcs) {
            val candidate = procArray[i]
            if (candidate.mark == mark) end(candidate)
        }
    }

    /**
     * The first proc running [script] after [proc] in array order (from the start if [proc] is
     * null), or null. Calling it again with the previous result iterates all matches.
     *
     * Ported from src/proc.c: Proc_FindAfter
     */
    public fun findAfter(script: ProcScript?, proc: Proc?): Proc? {
        val start = if (proc != null) proc.slot + 1 else 0
        for (i in start until maxProcs) {
            if (procArray[i].script === script) return procArray[i]
        }
        return null
    }

    /**
     * The first slot after [proc] in array order (from the start if [proc] is null) whose
     * [Proc.parent] is [parent], or null. A null [parent] also matches free slots and root
     * procs.
     *
     * Ported from src/proc.c: Proc_FindAfterWithParent
     */
    public fun findAfterWithParent(proc: Proc?, parent: Proc?): Proc? {
        val start = if (proc != null) proc.slot + 1 else 0
        for (i in start until maxProcs) {
            if (procArray[i].parent === parent) return procArray[i]
        }
        return null
    }

    /**
     * Ends every direct child of [parent] (and their subtrees), in array order.
     *
     * Transcribed from src/sysutil.c: EndAllProcChildren (not verified: the traces have no children
     * left to end when it runs)
     */
    public fun endAllChildren(parent: Proc) {
        var child: Proc? = null
        while (true) {
            child = findAfterWithParent(child, parent) ?: break
            end(child)
        }
    }

    /**
     * Number of procs running [script], or of all live procs if [script] is null.
     *
     * Ported from src/proc.c: CountProcs
     */
    public fun count(script: ProcScript?): Int {
        var n = 0
        for (i in 0 until maxProcs) {
            val p = procArray[i]
            if (script == null) {
                if (p.script != null) n++
            } else {
                if (p.script === script) n++
            }
        }
        return n
    }

    /**
     * Repeat callback installed by `PROC_SLEEP` (`UpdateSleep`): counts `sleepTime` down each
     * frame and, at zero, clears itself so the script resumes.
     */
    private val sleepCb = ProcFunc { sleeper ->
        sleeper.sleepTime--
        if (sleeper.sleepTime == 0) breakProc(sleeper)
    }

    /**
     * Executes the command at [proc]'s `scrCur`; true = continue with the next command this
     * frame, false = the script yields (or the proc ended).
     */
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
            // The original reads the root tree index from sleepTime (sic).
            0x07 -> { start(scriptOf(cmd), null, proc.sleepTime); proc.scrCur++ }
            0x08 -> { if (find(scriptOf(cmd)) != null) return false; proc.scrCur++ }
            0x09 -> { endEach(scriptOf(cmd)); proc.scrCur++ }
            0x0A -> { breakEach(scriptOf(cmd)); proc.scrCur++ }
            0x0B, 0x13, 0x15, 0x19 -> proc.scrCur++
            0x0C -> goto(proc, cmd.imm)
            // Proc_GotoScript
            0x0D -> { proc.scrScript = scriptOf(cmd); proc.scrCur = 0; proc.idleCb = null }
            0x0E -> {
                if (cmd.imm != 0) { proc.sleepTime = cmd.imm; proc.idleCb = sleepCb }
                proc.scrCur++
                return false
            }
            0x0F -> { proc.mark = cmd.imm and 0xFF; proc.scrCur++ }
            0x10 -> return false
            0x11 -> {
                if (count(script) > 1) { end(proc); return false }
                proc.scrCur++
            }
            0x12 -> { proc.flags = proc.flags or FLAG_UNK2; proc.scrCur++ }
            0x14 -> {
                proc.scrCur++
                if ((cmd.ptr as ProcBoolFunc).invoke(proc)) { proc.scrCur--; return false }
            }
            0x16 -> { proc.scrCur++; return (cmd.ptr as ProcBoolFunc).invoke(proc) }
            0x17 -> {
                for (i in 0 until maxProcs) {
                    val candidate = procArray[i]
                    if (candidate !== proc && candidate.script === script) {
                        end(candidate)
                        break
                    }
                }
                proc.scrCur++
            }
            0x18 -> {
                proc.scrCur++
                return (cmd.ptr as ProcArgFunc).invoke(cmd.imm.toShort().toInt(), proc)
            }
            else -> error("bad proc opcode ${cmd.opcode}")
        }
        return true
    }

    /**
     * Resolves the script a command refers to. Commands hold a `() -> ProcScript` factory rather
     * than the script, so scripts can refer to each other (and to themselves) before they exist.
     */
    @Suppress("UNCHECKED_CAST")
    private fun scriptOf(cmd: ProcCmd): ProcScript = (cmd.ptr as () -> ProcScript)()

    /**
     * Runs [proc]'s script commands until one yields (`RunProcessScript`); no-op while the proc is
     * blocked (`lockCnt`), waiting on a repeat callback, or ended.
     */
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

        /** Set by `PROC_SET_BIT4`; its meaning is unknown, nothing reads it (`PROC_FLAG_UNK2`). */
        public const val FLAG_UNK2: Int = 0x04

        /** Proc is still inside [start]; the frame loop skips it (`PROC_FLAG_STARTING`). */
        public const val FLAG_STARTING: Int = 0x08
    }
}
