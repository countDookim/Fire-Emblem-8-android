package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.GameControl
import fe8.game.RomView
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The title idle through the real `Fe8Boot` (D15-1, wiring only, no port claim) against
 * `ram_trace_title_idle_reel`: BOOT inputs (A at frame 181, START held 4 frames from 700), then no
 * input. The title times out after 815 idle steps and ends ([TITLE_LIFE] frames after its first
 * frame in the capture). The class reel needs battle animations (M6-G): it is a stand-in here, so
 * the game goes straight to the op anim, as after a reel (GameControl label `LGAMECTRL_OP_ANIM`),
 * instead of holding on a black screen; the op anim then returns to the title, and the second idle
 * goes to the op anim again (`idle_status` odd). Not compared: the reel's own frames
 * (capture f1560..9837).
 */
class TitleIdleBootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val names by lazy { ProcArrayDump.romNames() }
    private val rom = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Run(
        val title: List<Boolean>,
        val opAnim: List<Boolean>,
        val idle: List<Int>,
        val next: List<Int>,
    )

    private fun run(frames: Int): Run {
        var call = 0
        val a: Any = 0x0001
        val start: Any = 0x0008
        val zero: Any = 0
        val keys: () -> Any = {
            when (call) {
                181 -> a
                in 700..703 -> start
                else -> zero
            }
        }
        @Suppress("UNCHECKED_CAST")
        val boot = Fe8Boot(RomView.ofRom(rom.readBytes()), keys as () -> Int, RngService())
        val title = ArrayList<Boolean>()
        val op = ArrayList<Boolean>()
        val idle = ArrayList<Int>()
        val next = ArrayList<Int>()
        repeat(frames) {
            call++
            boot.frame()
            val procs = boot.engine.procs.mapNotNull { it.script?.name }
            title.add("gProcScr_TitleScreen" in procs)
            op.add("ProcScr_OpAnim" in procs)
            val ctl = boot.engine.find(boot.control.script)
            idle.add((ctl?.state as GameControl.State?)?.idleStatus ?: -1)
            next.add((ctl?.state as GameControl.State?)?.nextAction ?: -1)
        }
        return Run(title, op, idle, next)
    }

    /** Frame numbers (1-based) at which [flags] turns true. */
    private fun rises(flags: List<Boolean>) =
        flags.indices.filter { flags[it] && (it == 0 || !flags[it - 1]) }.map { it + 1 }

    private fun falls(flags: List<Boolean>) =
        flags.indices.filter { !flags[it] && it > 0 && flags[it - 1] }.map { it + 1 }

    @Test
    fun `title idle goes to the op anim and back without a black hang`() {
        assumeTrue(ref.available && rom.isFile && names.isNotEmpty(), "reference data missing")
        val data = ref.run("ram_trace_title_idle_reel").watch("procs")
        val capTitle = (1..LAST_CAP).map { step ->
            ProcArrayDump.ofWatch(data, step, names).live.any { it.name == "gProcScr_TitleScreen" }
        }
        val capStart = rises(capTitle).first()
        val capEnd = falls(capTitle).first()
        assertEquals(703, capStart)
        assertEquals(1559, capEnd)

        val ours = run(RUN_FRAMES)
        val start = rises(ours.title).first()
        val end = falls(ours.title).first()
        assertEquals(capStart, start, "title start")
        // The timeout is on the capture's frame (nextAction 1 after step 1541); the exit's two
        // fades (FadeCore) end one frame earlier here than in the capture (15 frames, not 16).
        assertEquals(1541, ours.next.indexOf(1) + 1, "timeout frame")
        assertTrue(end in capEnd - 1..capEnd, "title ends $end, capture $capEnd")
        // No reel: the op anim starts right after the title ends (the reel took 8278 frames).
        val firstOp = rises(ours.opAnim).first { it > end }
        assertTrue(firstOp - end <= 3, "op anim ${firstOp - end} frames after the title")
        // The op anim returns to the title (no hang); its idle ends in the op anim again.
        val secondTitle = rises(ours.title).first { it > firstOp }
        val secondEnd = falls(ours.title).first { it > secondTitle }
        val secondOp = rises(ours.opAnim).first { it > secondEnd }
        assertTrue(secondOp - secondEnd <= 3, "second op anim ${secondOp - secondEnd}")
        // idle_status counts the class reel branches: 1 after the first idle, 2 after the second.
        assertEquals(1, ours.idle[secondTitle - 1])
        assertEquals(2, ours.idle[secondOp - 1])
    }

    private companion object {
        const val LAST_CAP = 1600
        const val RUN_FRAMES = 6500
    }
}
