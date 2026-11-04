package gbafe.proc

import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endIfDuplicate
import gbafe.proc.ProcScripts.jump
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChildBlocking
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.whileTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Semantics of `src/proc.c` commands that the intro trace (ProcEngineDifferentialTest) does not reach. */
class ProcEngineTest {
    private val log = ArrayList<String>()
    private fun mark(s: String) = call { log += s }

    @Test
    fun `sleep waits n frames then continues`() {
        val e = ProcEngine()
        val p = e.start(ProcScript("s", listOf(mark("a"), sleep(3), mark("b"), block(), end())), 0)
        assertEquals(listOf("a"), log)
        repeat(2) { e.run(e.root(0)) }
        assertEquals(listOf("a"), log)
        e.run(e.root(0)) // third frame: sleepTime hits 0 -> break; script resumes next frame
        e.run(e.root(0))
        assertEquals(listOf("a", "b"), log)
        assertEquals(3, p.scrCur)
    }

    @Test
    fun `blocking child locks the parent until it ends, end callbacks run`() {
        val e = ProcEngine()
        var n = 0
        val child = ProcScript("c", listOf(setEndCb { log += "endCb" }, repeat { if (++n == 2) e.breakProc(it) }, end()))
        val parent = e.start(ProcScript("p", listOf(startChildBlocking { child }, mark("after"), block(), end())), 0)
        assertEquals(1, parent.lockCnt)
        e.run(e.root(0)) // parent locked; child repeat n = 1
        e.run(e.root(0))
        e.run(e.root(0))
        assertEquals(0, parent.lockCnt)
        assertEquals(listOf("endCb"), log)
        e.run(e.root(0))
        assertEquals(listOf("endCb", "after"), log)
        assertNull(parent.child)
    }

    @Test
    fun `goto, jump, while, whileExists and endIfDuplicate`() {
        val e = ProcEngine()
        val other = ProcScript("o", listOf(mark("jumped"), block(), end()))
        var w = 0
        val s = ProcScript("x", listOf(endIfDuplicate(), goto(5), mark("skipped"), label(9), block(), label(5),
            whileTrue { ++w < 3 }, mark("loop done"), jump { other }, end()))
        e.start(s, 0)
        e.run(e.root(0)); e.run(e.root(0))
        assertEquals(listOf("loop done", "jumped"), log)
        val dup = e.start(s, 1)
        assertNull(dup.script) // ended by PROC_END_IF_DUPLICATE
        val waiter = e.start(ProcScript("w", listOf(whileExists { s }, mark("gone"), block(), end())), 2)
        e.endEach(s)
        e.run(e.root(2))
        assertEquals("gone", log.last())
        assertEquals(2, waiter.scrCur)
    }
}
