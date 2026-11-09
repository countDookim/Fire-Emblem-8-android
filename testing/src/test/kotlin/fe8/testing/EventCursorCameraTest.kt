package fe8.testing

import fe8.game.event.MapCursorProc
import fe8.game.event.createProcScrEventDisplayCursor
import fe8.game.event.eventDisplayCursorLoop
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class EventCursorCameraTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("EventDisplayCursor_Loop")
    fun `event display cursor loop matches prologue procs watch`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")

        val expectedOccurrences = listOf(
            9803 to (13 to 6),
            10635 to (13 to 4),
            12069 to (13 to 4),
            12725 to (13 to 3),
            13114 to (6 to 5),
            13677 to (6 to 5),
            14020 to (9 to 5),
            15093 to (7 to 5),
            15436 to (8 to 5),
            15994 to (9 to 5),
            18046 to (4 to 4),
            18272 to (4 to 4),
            18800 to (10 to 8),
        )

        var matchedOccurrences = 0
        for ((step, expectedPos) in expectedOccurrences) {
            val dump = ProcArrayDump.ofWatch(trace, step)
            val proc = dump.find(PROC_EVENT_DISPLAY_CURSOR)
            assertNotNull(proc, "ProcScr_EventDisplayCursor alive at step $step")
            val p = proc!!
            val x = p.u16(0x64).toShort().toInt()
            val y = p.u16(0x66).toShort().toInt()
            val subcmd = p.u16(0x68)
            val timer = p.u16(0x6A)

            assertEquals(expectedPos.first, x, "x at step $step")
            assertEquals(expectedPos.second, y, "y at step $step")
            assertEquals(1, subcmd, "subcmd at step $step")
            assertEquals(0, timer, "timer at step $step")

            // Test eventDisplayCursorLoop on the state decoded from the watch record
            val state = MapCursorProc(x = x, y = y, subcmd = subcmd, timer = timer)
            var cursorX = -1
            var cursorY = -1
            var cursorKind = -1
            eventDisplayCursorLoop(
                state,
                putMapCursor = { cx, cy, kind ->
                    cursorX = cx
                    cursorY = cy
                    cursorKind = kind
                },
            )
            assertEquals(x * 16, cursorX, "putMapCursor x at step $step")
            assertEquals(y * 16, cursorY, "putMapCursor y at step $step")
            assertEquals(0, cursorKind, "putMapCursor kind at step $step")

            matchedOccurrences++
        }
        assertEquals(expectedOccurrences.size, matchedOccurrences)

        // Also test subcmd & 0xc == 4 shining cursor advance branch
        val shiningState = MapCursorProc(x = 10, y = 20, subcmd = 4, timer = 5)
        var advanced = false
        eventDisplayCursorLoop(
            shiningState,
            putMapCursor = { _, _, _ -> error("should not be called for subcmd 4") },
            eventShinningCursorAdvance = { sx, sy, counter ->
                assertEquals(10, sx)
                assertEquals(20, sy)
                assertEquals(5, counter)
                advanced = true
                42
            },
        )
        assertTrue(advanced)
        assertEquals(42, shiningState.timer)

        // Test running via ProcEngine with createProcScrEventDisplayCursor
        val engine = ProcEngine()
        var loopCallCount = 0
        val script = createProcScrEventDisplayCursor(
            putMapCursor = { cx, cy, _ ->
                assertEquals(16, cx)
                assertEquals(32, cy)
                loopCallCount++
            },
        )
        val p = engine.start(script, 3)
        val s = p.state as MapCursorProc
        s.x = 1
        s.y = 2
        s.subcmd = 0
        engine.run(p)
        assertEquals(1, loopCallCount)
        engine.run(p)
        assertEquals(2, loopCallCount)
        engine.endEach(script)
        assertEquals(null, engine.find(script))
    }

    private companion object {
        const val PROC_EVENT_DISPLAY_CURSOR = 0x08591F08
    }
}
