package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import gba.core.sram.SaveStorage
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Zero bytes per frame across the steady enemy-phase windows of `ram_trace_prologue_enemy_phase`
 * (see [EnemyPhaseReplayTest]) through the production [Fe8Boot.frame] and GPU [Fe8Boot.pack]
 * path: phase banners, AI unit walks, target cursors, map battles (including MISS AP hold, hit
 * flash, death fade, and EXP bar), the one-enemy-left event, map fades, green phase, and turn-2
 * idle.
 */
class EnemyPhaseAllocationTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private class Image(private val image: ByteArray) : SaveStorage {
        override fun load(dest: ByteArray): Boolean {
            image.copyInto(dest)
            return true
        }

        override fun store(src: ByteArray) {}
    }

    @Test
    fun `enemy phase steady windows allocate nothing`() {
        val ref = ReferenceData.fromEnvironment()
        val romFile = File(assets, "baserom.gba")
        assumeTrue(AllocationMeter.supported && ref.available && romFile.isFile)
        // Two passes over the same deterministic boot; a frame counts as allocating only if it
        // allocates in both (the minimum, as `AllocationMeter.measureMin` and
        // `PlayerPhaseAllocationTest` do): a one-off JIT deoptimization in one pass is gone in the
        // second pass, while real per-frame allocations recur in both.
        val first = enemyPhaseBytes(ref, romFile)
        val second = enemyPhaseBytes(ref, romFile)
        val bytes = LongArray(first.size) { minOf(first[it], second[it]) }
        for ((name, from, to, run) in STEADY_WINDOWS) {
            var best = 0
            var current = 0
            for (f in from..to) {
                current = if (bytes[f] == 0L) current + 1 else 0
                best = maxOf(best, current)
            }
            assertTrue(best >= run, "$name f$from..$to: longest allocation-free run $best")
        }
    }

    /**
     * Replays [EnemyPhaseReplayTest]'s Prologue enemy-phase inputs from first idle through turn 2
     * idle and returns the heap bytes allocated by `boot.frame()` + `boot.pack()` indexed by frame
     * offset after first idle.
     */
    private fun enemyPhaseBytes(ref: ReferenceData, romFile: File): LongArray {
        val run = ref.run("ram_trace_prologue_enemy_phase")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        var frame = 0
        var idleAt = -1
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frame - idleAt + idleFrame) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = Image(ref.run("ram_trace_prologue_resume").sram("s01001")),
        )
        boot.gpuFrame
        val out = LongArray(2000)
        var enemySeen = false
        var reachedTurn2 = false
        while (frame < 6000) {
            val before = AllocationMeter.current()
            boot.frame()
            boot.pack()
            val used = AllocationMeter.current() - before
            frame++
            val idle = boot.engine.find(boot.playerPhaseScript)?.scrCur == 16
            if (idleAt < 0 && idle) {
                idleAt = frame
                continue
            }
            if (idleAt < 0) continue
            if (boot.control.playSt.faction == 0x80) enemySeen = true
            val idx = frame - idleAt
            if (idx < out.size) out[idx] = used
            if (enemySeen && boot.control.playSt.chapterTurnNumber == 2 && idle) {
                reachedTurn2 = true
                for (k in 1..120) {
                    val b = AllocationMeter.current()
                    boot.frame()
                    boot.pack()
                    if (idx + k < out.size) out[idx + k] = AllocationMeter.current() - b
                }
                break
            }
        }
        check(reachedTurn2) { "turn 2 not reached within $frame frames" }
        return out
    }

    private class Window(val name: String, val from: Int, val to: Int, val run: Int) {
        operator fun component1() = name
        operator fun component2() = from
        operator fun component3() = to
        operator fun component4() = run
    }

    private companion object {
        // Transition frames (suspend-save flushes, phase-switch events, proc creation, lazy hit
        // palette fade init, and first-seen MU/AP sprite object cache parses) may allocate once.
        // Every steady stretch between transitions must sustain 0 B/frame.
        val STEADY_WINDOWS = listOf(
            Window("enemy phase banner", 367, 435, 50),
            Window("soldier 1 walk", 437, 468, 8),
            Window("soldier 1 target cursor", 477, 516, 30),
            Window("soldier 1 MISS AP hold", 559, 567, 7),
            Window("soldier 1 battle round 1", 575, 653, 60),
            Window("soldier 1 hit flash and drain", 655, 693, 30),
            Window("soldier 1 death fade", 695, 727, 25),
            Window("soldier 1 exp bar", 730, 829, 80),
            Window("soldier 1 map fade", 837, 852, 12),
            Window("soldier 2 walk", 854, 893, 30),
            Window("soldier 2 target cursor", 895, 941, 35),
            Window("soldier 2 battle", 952, 1073, 90),
            Window("soldier 2 death fade", 1075, 1107, 25),
            Window("soldier 2 exp bar", 1110, 1210, 80),
            // Prologue one-enemy-left event (`0x089EF0D8`, EP-11-hooks) runs f1214..1549, shifting
            // the remaining windows by +336 frames.
            Window("one-enemy-left cursor", 1215, 1275, 50),
            Window("one-enemy-left talk", 1293, 1454, 120),
            Window("one-enemy-left talk wait", 1464, 1533, 50),
            Window("soldier 2 map fade", 1551, 1566, 12),
            Window("green phase", 1575, 1586, 10),
            Window("turn 2 player phase banner", 1599, 1667, 50),
            Window("turn 2 idle map", 1697, 1788, 60),
        )
    }
}
