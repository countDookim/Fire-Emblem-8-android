package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-10f: differential verification of the turn-2 tutorial hooks of `EventInfos` against
 * `ram_trace_newgame_easy` (`PC-cap3`). At each dispatch frame the test seeds `gPlaySt` and
 * the flags from the dump before the hook frame, calls the Kotlin function, and
 * compares the queue bytes, the return value and the event it starts (`tutorial_counter - 1`
 * of the tutorial list) with the next dump:
 * - `StartAfterUnitMovedEvent` and `CheckTutorialEvent` (`AFTERMOVE`, 7) at `f22447 -> f22448`.
 * - `CheckBattleForecastTutorialEvent` (`FORECAST`, 8) at `f22524..22565`,
 *   `StartBattleForecastTutorialEvent` at `f22565 -> f22566`.
 * - `RunTutorialEvent(POSTACTION)` through `RunWaitEvents` with
 *   `POSTACTION` (1, 9) queued at `f23366..24028 -> f24029`.
 * - `StartPlayerPhaseStartTutorialEvent` (`PLAYERPHASE`, 10) at `f24209 -> f24210`.
 * Frames where another type is queued check that the hook leaves the queue untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class EasyTutorialTurn2DispatchDifferentialTest {
    private var shared: Rig? = null
    private var loaded = false

    /** Releases cached trace references once all tests in this class complete. */
    @AfterAll
    fun releaseRig() {
        shared = null
        loaded = false
    }

    private fun rig(): Rig {
        if (!loaded) {
            shared = loadRig()
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data or ROM not present")
        return r!!
    }

    @Test
    @VerifiesPort("StartAfterUnitMovedEvent", "RunTutorialEvent")
    fun `StartAfterUnitMovedEvent dispatches Tutorial6 at f22448`() {
        val r = rig()
        for (f in listOf(22439, 22440, 22524, 23366, 24199)) {
            r.assertUntouched(f) { it.startAfterUnitMovedEvent() }
        }
        r.assertDispatch(22447, 22448, expected = true) { it.startAfterUnitMovedEvent() }
        // After the dispatch nothing is queued: the hook is a no-op at f22448.
        r.assertUntouched(22448) { it.startAfterUnitMovedEvent() }
    }

    @Test
    @VerifiesPort("CheckBattleForecastTutorialEvent", "CheckTutorialEvent")
    fun `CheckBattleForecastTutorialEvent is true only while FORECAST is queued`() {
        val r = rig()
        for (f in listOf(22440, 22447, 22448, 22523, 22566, 23366, 24029, 24199)) {
            assertEquals(false, r.check(f) { it.checkBattleForecastTutorialEvent() }, "f$f")
        }
        for (f in listOf(22524, 22525, 22530, 22560, 22565)) {
            assertEquals(true, r.check(f) { it.checkBattleForecastTutorialEvent() }, "f$f")
        }
        // CheckTutorialEvent keys on both type and counter.
        assertEquals(true, r.check(23366) { it.checkTutorialEvent(1) }, "f23366 POSTACTION")
        assertEquals(false, r.check(23366) { it.checkTutorialEvent(5) }, "f23366 FORECAST")
        assertEquals(false, r.check(24029) { it.checkTutorialEvent(1) }, "f24029 cleared")
    }

    @Test
    @VerifiesPort("StartBattleForecastTutorialEvent", "RunTutorialEvent")
    fun `StartBattleForecastTutorialEvent dispatches Tutorial7 at f22566`() {
        val r = rig()
        r.assertUntouched(22523) { it.startBattleForecastTutorialEvent() }
        r.assertUntouched(22447) { it.startBattleForecastTutorialEvent() }
        r.assertDispatch(22565, 22566, expected = null) { it.startBattleForecastTutorialEvent() }
    }

    @Test
    @VerifiesPort("RunTutorialEvent")
    fun `RunTutorialEvent POSTACTION dispatches Tutorial8 at f24029`() {
        val r = rig()
        r.assertUntouched(23365) { it.runTutorialEvent(1) }
        r.assertUntouched(22565) { it.runTutorialEvent(1) }
        // RunWaitEvents also starts the matching misc event, which the engine runs at f24202.
        r.assertDispatch(
            24028, 24029, expected = null, extraCalls = listOf(MISC_EVENT to EV_EXEC_CUTSCENE),
        ) { it.runWaitEvents(EIRIKA_X, EIRIKA_Y) }
        assertEquals(MISC_EVENT, r.engineStart(24202), "f24202 misc event")
    }

    @Test
    @VerifiesPort("StartPlayerPhaseStartTutorialEvent", "RunTutorialEvent")
    fun `StartPlayerPhaseStartTutorialEvent dispatches Tutorial9 at f24210`() {
        val r = rig()
        for (f in listOf(24198, 22447, 23366)) {
            r.assertUntouched(f) { it.startPlayerPhaseStartTutorialEvent() }
        }
        r.assertDispatch(24199, 24210, expected = null, eventFrame = 24210) {
            it.startPlayerPhaseStartTutorialEvent()
        }
    }

    private class Rig(
        private val romBytes: ByteArray,
        private val trace: EventTrace,
    ) {
        private val chapters = ChapterData(RomView.ofRom(romBytes))
        private val mem = ScriptMemory { a ->
            val o = a - ROM_BASE
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }

        private fun playSt(step: Int): PlaySt =
            PlaySt().also { it.decode(trace.at(step).playSt!!, 0) }

        private fun tutorialPtr(chapterIndex: Int, index: Int): Int {
            val group = chapters.getChapterEventDataPointer(chapterIndex)
            val list = u32(romBytes, group - ROM_BASE + TUTORIAL_EVENTS_OFFSET)
            return u32(romBytes, list - ROM_BASE + 4 * index)
        }

        /** Events requested through `CallEvent`: (script, execType). */
        private class Seeded(val infos: EventInfos, val playSt: PlaySt) {
            val calls = mutableListOf<Pair<Int, Int>>()
        }

        private fun seed(step: Int): Seeded {
            val playSt = playSt(step)
            val snap = trace.at(step)
            val flags = Fe8EventFlags.create().also {
                it.copyIn(snap.permanentFlags!! + snap.chapterFlags!!)
            }
            val infos = EventInfos(mem, flags, playSt)
            val seeded = Seeded(infos, playSt)
            infos.hooks = object : EventInfoHooks {
                override fun callEvent(script: Int, execType: Int) {
                    seeded.calls += script to execType
                }

                override fun chapterEventGroup(chapter: Int): Int =
                    chapters.getChapterEventDataPointer(chapter)
            }
            return seeded
        }

        /** `pEventStart` of the `StdEventEngine` at [step]. */
        fun engineStart(step: Int): Int = checkNotNull(trace.at(step).engine).pEventStart

        /** Runs [body] on state seeded from the dump at [step]. */
        fun <T> check(step: Int, body: (EventInfos) -> T): T = body(seed(step).infos)

        /** The hook is a no-op: no event requested and the queue bytes unchanged. */
        fun assertUntouched(step: Int, body: (EventInfos) -> Any?) {
            val s = seed(step)
            val before = s.playSt.tutorialExecType to s.playSt.tutorialCounter
            body(s.infos)
            assertEquals(emptyList<Pair<Int, Int>>(), s.calls, "f$step started an event")
            assertEquals(before, s.playSt.tutorialExecType to s.playSt.tutorialCounter, "f$step")
        }

        /**
         * Seeds from the dump at [from], runs [body], and compares with the dump at [to]: the
         * queue is cleared, the requested event is entry `counter - 1` of the tutorial list
         * with `EV_EXEC_CUTSCENE`, and it is the `StdEventEngine` script of that dump.
         */
        fun assertDispatch(
            from: Int,
            to: Int,
            expected: Boolean?,
            eventFrame: Int = to,
            extraCalls: List<Pair<Int, Int>> = emptyList(),
            body: (EventInfos) -> Any?,
        ) {
            val s = seed(from)
            val queued = s.playSt.tutorialCounter
            val result = body(s.infos)
            if (expected != null) assertEquals(expected, result, "f$from return value")
            val want = playSt(to)
            assertEquals(want.tutorialExecType, s.playSt.tutorialExecType, "f$to tutExecType")
            assertEquals(want.tutorialCounter, s.playSt.tutorialCounter, "f$to tutCounter")
            val script = tutorialPtr(s.playSt.chapterIndex, queued - 1)
            val wantCalls = listOf(script to EV_EXEC_CUTSCENE) + extraCalls
            assertEquals(wantCalls, s.calls, "f$from event requested")
            val engine = checkNotNull(trace.at(eventFrame).engine) { "engine at f$eventFrame" }
            assertEquals(engine.pEventStart, script, "f$eventFrame pEventStart")
            assertEquals(engine.execType, EV_EXEC_CUTSCENE, "f$eventFrame execType")
        }
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val ROM_BASE = 0x08000000
        const val TUTORIAL_EVENTS_OFFSET = 0x1C

        /** Eirika's tile in the turn-2 battle (the destination of her own tile, f22440). */
        const val MISC_EVENT = 0x089EF0D8
        const val EIRIKA_X = 4
        const val EIRIKA_Y = 5

        fun loadRig(): Rig? {
            val ref = ReferenceData.fromEnvironment()
            val assets =
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
            val romFile = File(assets, "baserom.gba")
            if (!ref.available || !romFile.isFile) return null
            val trace = EventTrace.prologue(ref, RUN) ?: return null
            return Rig(romFile.readBytes(), trace)
        }

        fun u32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
