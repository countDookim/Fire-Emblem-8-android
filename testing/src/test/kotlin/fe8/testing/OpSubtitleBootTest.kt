package fe8.testing

import fe8.game.Fe8Game
import fe8.game.Fe8Sound
import fe8.game.RomView
import gba.core.VerifiesPort
import gba.input.ActionSource
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The opening monologue through the real `Fe8Boot` New Game path (title START, save-menu
 * stand-in, `GameCtrlStartIntroMonologue`) against the `ram_trace_prologue` and Start-skip
 * `procs` captures. Wiring only, no port claim.
 *
 * Alignment: the boot reaches the monologue after a different, shorter lead-in than the original
 * (the save and difficulty menus are stand-ins), so frames are matched by the monologue's own
 * ticks: boot tick `j` after the proc appears is original frame [FIRST] + j. The original runs
 * each scene-setup CALL across several video frames and the port atomically, so after such a
 * command the original frame counter jumps ahead by the original span (the same rule as
 * `OpSubtitleSkipReplayTest`): [Plan] lists, per boot tick, the original frame whose dump is
 * compared. Our boot is therefore shorter than the original by the summed spans.
 */
class OpSubtitleBootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val rom = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    /** Boot tick j (0 = the tick that created the proc) -> original frame compared after it. */
    private class Plan(val frames: IntArray, val runs: IntArray, val spans: Int)

    private fun plan(
        trace: gba.testkit.WatchTrace, last: Int, script: gbafe.proc.ProcScript, start: Int = last + 1,
    ): Plan {
        val runs = ArrayList<Int>()
        val out = ArrayList<Int>()
        var spanEnd = 0
        var spans = 0
        for (frame in FIRST + 1..last) {
            if (frame <= spanEnd) continue
            val node = ProcArrayDump.ofWatch(trace, frame).find(SUBTITLE)
            if (frame < start && node != null && script.cmds[node.scrCur - 1].opcode == 0x02) {
                var end = frame
                while (
                    ProcArrayDump.ofWatch(trace, end + 1).find(SUBTITLE)?.scrCur == node.scrCur
                ) end++
                spanEnd = end + 1
                spans += spanEnd - frame
                out.add(spanEnd)
                runs.add(frame)
            } else {
                out.add(frame)
                runs.add(frame)
            }
        }
        return Plan(out.toIntArray(), runs.toIntArray(), spans)
    }

    private class Run(val game: Fe8Game) {
        var frame = 0
        var press = -1
        var tickBytes = 0L
        var presentBytes = 0L
        var titleAt = Int.MAX_VALUE
        init {
            game.actionSource = ActionSource {
                when {
                    frame - titleAt in 0..3 -> 0x8L
                    frame == press -> 0x8L
                    else -> 0L
                }
            }
        }
        val boot get() = game.boot!!
        fun tick() {
            if (titleAt == Int.MAX_VALUE &&
                boot.engine.procs.any { it.script?.name == "gProcScr_DrawTitleSprites" }
            ) titleAt = frame + 30
            game.input.update(game.actionSource.poll())
            val a = AllocationMeter.current()
            boot.frame()
            val b = AllocationMeter.current()
            game.present(0f)
            tickBytes = b - a
            presentBytes = AllocationMeter.current() - b
            frame++
        }
        fun subtitle(): Proc? = boot.engine.find(boot.opSubtitle.script)
    }

    private fun run(withSound: Boolean = true): Run? {
        assumeTrue(ref.available && rom.isFile, "reference data or ROM unavailable")
        val view = RomView.ofRom(rom.readBytes())
        return Run(Fe8Game(rom = view, sound = if (withSound) Fe8Sound.create(view) else null))
    }

    @Test
    fun `New Game plays the monologue from step 1213 and releases it at 4898`() {
        playThrough(run()!!, allocationFree = false)
    }

    /**
     * The monologue's own code and its wiring (fades, key status, proc starts) allocate nothing
     * in frames inside one command's repeat loop. Run without the m4a driver: its sequencer
     * allocates on some song events (open item), which is not part of the wiring.
     */
    @Test
    fun `monologue steady frames allocate no bytes`() {
        assumeTrue(AllocationMeter.supported, "allocation meter unavailable")
        playThrough(run(withSound = false)!!, allocationFree = true)
    }

    /** Every live proc's script and cursor: unchanged while all procs sit in repeat loops. */
    private fun signature(run: Run): Int {
        var h = 0
        for (proc in run.boot.engine.procs) {
            if (proc.script != null) h = h * 31 + proc.script.hashCode() * 7 + proc.scrCur
        }
        return h
    }

    @Test
    @VerifiesPort("OpSubtitle_HandleStartPress")
    fun `Start through Fe8Boot skips like the captures`() {
        for (start in listOf(1505, 2375, 4405)) {
            val run = run()!!
            val trace = ref.run("ram_trace_subtitle_skip_$start").watch("procs")
            val dumps = ref.run("ram_trace_subtitle_skip_$start")
            val plan = plan(trace, start + 30, run.boot.opSubtitle.script, start)
            // Tick j of the monologue runs original frame runs[j] (runs[0] is the creating tick, original frame 1214).
            val pressTick = plan.runs.indexOf(start)
            assertTrue(pressTick > 0, "$start has a boot tick")
            while (run.subtitle() == null && run.frame < 20000) run.tick()
            val parent = run.subtitle()!!.parent!!
            run.press = run.frame - 1 + pressTick
            var fadeSeen = false
            for (j in plan.frames.indices) {
                val c = plan.frames[j]
                val node = ProcArrayDump.ofWatch(trace, c).find(SUBTITLE)
                val ours = run.subtitle()
                assertEquals(node != null, ours != null, "$start tick $j frame $c exists")
                if (node != null) assertEquals(node.scrCur, ours!!.scrCur, "$start tick $j cursor")
                // After the end the game control proc runs on (it may block again at once).
                if (node != null) assertEquals(1, parent.lockCnt, "$start tick $j lock")
                // From start + 24 the original's next code (the world map start, D11-wm9) begins
                // its own BGM fades; they are not wired yet.
                if (j >= pressTick && c <= start + 23) {
                    // The BGM players fade as in the capture (tick before the procs, dump after).
                    val expected = M4aRam.sound(dumps.region("r%05d".format(c), "iwram")).first[0]
                    val ours0 = run.boot.m4a!!.players[0]
                    assertEquals(
                        listOf(expected.fadeOI, expected.fadeOC, expected.fadeOV),
                        listOf(ours0.fadeOI, ours0.fadeOC, ours0.fadeOV),
                        "$start tick $j frame $c BGM1 fade",
                    )
                    assertEquals(false, run.boot.control.sound.isSongPlaying, "$start playing")
                    fadeSeen = true
                }
                if (j + 1 < plan.frames.size) run.tick()
            }
            assertTrue(fadeSeen)
            assertNull(run.subtitle(), "$start monologue ended")
        }
    }

    private fun playThrough(run: Run, allocationFree: Boolean) {
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val plan = plan(trace, LAST, run.boot.opSubtitle.script)
        while (run.subtitle() == null && run.frame < 20000) run.tick()
        assertNotNull(run.subtitle(), "monologue never started")
        val parent = run.subtitle()!!.parent!!
        val allocating = ArrayList<String>()
        var steady = 0
        val cursors = arrayListOf(signature(run))
        val bytes = ArrayList<Long>().apply { add(0L) }
        for (j in plan.frames.indices) {
            val node = ProcArrayDump.ofWatch(trace, plan.frames[j]).find(SUBTITLE)
            val ours = run.subtitle()
            assertEquals(node != null, ours != null, "tick $j frame ${plan.frames[j]} exists")
            if (node != null) {
                assertEquals(node.scrCur, ours!!.scrCur, "tick $j frame ${plan.frames[j]} cursor")
                assertEquals(1, parent.lockCnt, "tick $j lock")
            }
            if (j + 1 < plan.frames.size) {
                val cursor = ours?.scrCur
                run.tick()
                cursors.add(signature(run))
                bytes.add(run.tickBytes)
            }
        }
        assertNull(run.subtitle())
        // Frames where every proc sits inside one command's repeat loop, not the first or last of it (those enter or
        // leave a command: start procs, decompress, set up scenes, break the repeat), allocate
        // nothing.
        for (t in 1 until cursors.size - 1) {
            if (cursors[t - 1] == cursors[t] && cursors[t] == cursors[t + 1]) {
                steady++
                if (bytes[t] != 0L) allocating.add("tick $t cursor ${cursors[t]}: ${bytes[t]}")
            }
        }
        assertTrue(steady > 3000, "steady frames: $steady")
        if (allocationFree) {
            assertEquals(emptyList<String>(), allocating, "bytes allocated in steady frames")
        }
        assertEquals(6, plan.spans, "original frames spent inside atomic scene-setup callbacks")
        assertEquals(0, parent.lockCnt, "game control released")
    }

    private companion object {
        const val SUBTITLE = 0x08AA21BC
        const val FIRST = 1213
        const val LAST = 4898
    }
}
