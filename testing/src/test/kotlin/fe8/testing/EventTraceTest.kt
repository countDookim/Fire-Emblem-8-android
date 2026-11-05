package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [EventTrace] against `ram_trace_prologue` (per step) and `ram_trace_newgame_bm` (per frame):
 * the event engine of the Prologue beginning scene.
 *
 * Observed: the engine proc for EventScr_Prologue_BeginningScene first appears at step 9508 with
 * pEventStart at the script's first command but pEventCurrent already inside
 * EventScr_Prologue_RenaisThroneCutscene (the first command calls it), so no sampled step has the
 * pc inside the 212-byte BeginningScene body before the call.
 */
class EventTraceTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `beginning scene engine starts at the script and calls the throne cutscene`() {
        val trace = prologue()
        val first = trace.snapshots().first { it.engine?.startScript?.name == BEGINNING_SCENE_NAME }
        val engine = checkNotNull(first.engine)
        assertEquals(FIRST_BEGINNING_STEP, first.step)
        assertEquals(BEGINNING_SCENE, engine.pEventStart)
        assertEquals(ScriptPos(BEGINNING_SCENE_NAME, 0), engine.startScript)
        assertEquals(RENAIS_THRONE_NAME, engine.currentScript?.name)
        assertEquals(1, engine.execType)
        // The caller position pushed on the active queue is the start of the beginning scene.
        assertEquals(BEGINNING_SCENE, first.activeQueue[0].evt1)
    }

    @Test
    fun `engine later enters the throne cutscene with the beginning scene as caller`() {
        val trace = prologue()
        val inThrone = trace.snapshots().filter { it.step >= FIRST_BEGINNING_STEP }.first {
            it.engine?.currentScript?.name == RENAIS_THRONE_NAME && it.engine.pEventCurrent != 0
        }
        assertEquals(BEGINNING_SCENE, inThrone.activeQueue[0].evt1)
        // And the throne cutscene is re-entered after the text/background sub-scripts.
        val reentered = trace.snapshots().filter { it.step > 13500 }.first {
            it.engine?.currentScript?.name == RENAIS_THRONE_NAME
        }
        assertEquals(BEGINNING_SCENE, reentered.activeQueue[0].evt1)
    }

    @Test
    fun `engine is gone before phase intro`() {
        val trace = prologue()
        val gone = trace.snapshots()
            .filter { it.step > FIRST_BEGINNING_STEP }
            .first { it.engine == null }
        assertEquals(LAST_ENGINE_STEP + 1, gone.step)
        assertTrue(gone.step < PHASE_INTRO_FRAME)
        for (step in gone.step..PHASE_INTRO_FRAME) assertNull(trace.at(step).engine, "step $step")
    }

    @Test
    fun `newgame_bm dumps decode like the per-step watches`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val frames = EventTrace.newGameBm(ref)
        assumeTrue(frames != null, "ram_trace_newgame_bm not present")
        val steps = prologue()
        val engineFrames = frames!!.snapshots().filter { it.engine != null }.toList()
        assertTrue(engineFrames.isNotEmpty(), "engine seen in newgame_bm")
        for (frame in frames.steps) {
            if (frame !in steps.steps.first()..steps.steps.last()) continue
            val a = frames.at(frame)
            val b = steps.at(frame)
            assertEquals(b.engine, a.engine, "engine at $frame")
            assertEquals(b.callQueue, a.callQueue, "call queue at $frame")
            assertEquals(b.activeQueue, a.activeQueue, "active queue at $frame")
            assertEquals(b.slotCounter, a.slotCounter, "slot counter at $frame")
            assertTrue(b.slots.contentEquals(a.slots), "slots at $frame")
            assertTrue(b.slotQueue.contentEquals(a.slotQueue), "slot queue at $frame")
            assertTrue(
                b.permanentFlags.contentEquals(a.permanentFlags),
                "permanent flags at $frame",
            )
            assertTrue(b.chapterFlags.contentEquals(a.chapterFlags), "chapter flags at $frame")
        }
        assertNotNull(frames.at(frames.steps.first()))
    }

    private fun prologue(): EventTrace {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val trace = EventTrace.prologue(ref)
        assumeTrue(trace != null, "ram_trace_prologue watches not present")
        return trace!!
    }

    private companion object {
        const val BEGINNING_SCENE = 0x089EEBE8
        const val BEGINNING_SCENE_NAME = "EventScr_Prologue_BeginningScene"
        const val RENAIS_THRONE_NAME = "EventScr_Prologue_RenaisThroneCutscene"

        /** First step with the BeginningScene engine (observed). */
        const val FIRST_BEGINNING_STEP = 9508

        /** Last step with an engine proc (observed). */
        const val LAST_ENGINE_STEP = 19311

        /** First frame of ProcScr_PhaseIntro. */
        const val PHASE_INTRO_FRAME = 19316
    }
}
