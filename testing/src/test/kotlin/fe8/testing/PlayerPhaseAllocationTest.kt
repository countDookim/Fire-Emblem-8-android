package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import gba.core.sram.SaveStorage
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Zero bytes per frame in the wired player phase: the select, move and Wait replay of
 * `ram_trace_prologue_select_move` (see [PlayerPhaseBootTest]) through the production frame and
 * GPU pack path, measured over windows of steady frames.
 */
class PlayerPhaseAllocationTest {
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
    fun `select move and Wait windows allocate nothing`() {
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(AllocationMeter.supported && ref.available && File(assets, "baserom.gba").isFile)
        // Two passes over the same deterministic boot; a frame counts as allocating only if it
        // allocates in both (the minimum, as `AllocationMeter.measureMin` does; see
        // WorldMapBootTest). A one-off JIT deoptimization re-creates scalar-replaced objects in
        // one frame, depending on what ran in the JVM before, and is gone in the second pass.
        // Code that really allocates does so in the same frames in both passes.
        val first = selectMoveWaitBytes(ref)
        val second = selectMoveWaitBytes(ref)
        val bytes = LongArray(2000) { minOf(first[it], second[it]) }
        // A frame that starts or ends something (a cursor move, a menu, the MU walk, the save
        // write) may allocate. Each steady stretch of the replay must hold a long run of frames
        // that allocate nothing: the idle phase with its side windows, the cursor moves in the
        // range display, the unit menu and the idle phase after the Wait.
        for ((from, to, run) in listOf(
            Triple(1176, 1249, 20), Triple(1264, 1295, 10), Triple(1314, 1355, 20),
            Triple(1360, 1420, 40),
        )) {
            var best = 0
            var current = 0
            for (f in from..to) {
                current = if (bytes[f] == 0L) current + 1 else 0
                best = maxOf(best, current)
            }
            assertTrue(best >= run, "longest allocation-free run in f$from..f$to: $best frames")
        }
    }

    /** One replay of the select, move and Wait trace; the allocated bytes of each frame. */
    private fun selectMoveWaitBytes(ref: ReferenceData): LongArray {
        val run = ref.run("ram_trace_prologue_select_move")
        val captured = Array<Any>(2000) { if (it < run.inputs.size) run.inputs[it] else 0 }
        val pulse = arrayOf<Any>(0, 1)
        var frame = 0
        var idleAt = -1
        val boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else captured[(frame - idleAt + 1172).coerceIn(0, 1999)]
            } as () -> Int),
            RngService(),
            saveStorage = Image(ref.run("ram_trace_prologue_resume").sram("s01001")),
        )
        boot.gpuFrame // the GPU pack path
        val bytes = LongArray(2000)
        while (idleAt < 0 || frame - idleAt + 1172 < 1420) {
            val before = AllocationMeter.current()
            boot.frame()
            boot.pack()
            val used = AllocationMeter.current() - before
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
            if (idleAt >= 0) bytes[frame - idleAt + 1172] = used
        }
        return bytes
    }

    @Test
    fun `cursor resting on an enemy allocates nothing`() {
        restOnEnemy(burst = false)
    }

    @Test
    fun `cursor resting on an enemy with the Burst unit window allocates nothing`() {
        restOnEnemy(burst = true)
    }

    /**
     * After the replay the cursor goes to the first enemy unit (single-frame D-pad presses) and
     * rests there for 60 frames; the unit window (the mini box, or Burst with `unitDisplayType`
     * 1 set before the windows start) draws that enemy every frame it may.
     */
    private fun restOnEnemy(burst: Boolean) {
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(AllocationMeter.supported && ref.available && File(assets, "baserom.gba").isFile)
        // Two passes over the same deterministic boot, a frame counts as allocating only if it
        // allocates in both (the minimum, as `AllocationMeter.measureMin` does; see
        // WorldMapBootTest): a one-off JIT deoptimization is gone in the second pass.
        val first = restOnEnemyBytes(ref, burst)
        val second = restOnEnemyBytes(ref, burst)
        var best = 0
        var current = 0
        for (i in first.indices) {
            current = if (minOf(first[i], second[i]) == 0L) current + 1 else 0
            best = maxOf(best, current)
        }
        // A per-frame allocation (boxing) leaves no run at all; a stray JIT-time allocation may
        // split the 60 frames once.
        assertTrue(best >= 30, "cursor on enemy burst=$burst: longest zero run $best of 60")
    }

    /** One boot, the cursor walk to the first enemy; the bytes of each of the 60 resting frames. */
    private fun restOnEnemyBytes(ref: ReferenceData, burst: Boolean): LongArray {
        val pulse = arrayOf<Any>(0, 1)
        val dpad = arrayOf<Any>(0, 0x10, 0x20, 0x40, 0x80)
        var frame = 0
        var phase = 0 // 0 boot, 1 move the cursor
        var key = 0
        lateinit var boot: Fe8Boot
        boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (phase == 0) pulse[if (frame % 20 in 5..8) 1 else 0] else dpad[key]
            } as () -> Int),
            RngService(),
            saveStorage = Image(ref.run("ram_trace_prologue_resume").sram("s01001")),
        )
        boot.gpuFrame
        val resume = boot.control.hooks.startResumedGame
        boot.control.hooks.startResumedGame = gbafe.proc.ProcFunc {
            resume.invoke(it)
            if (burst) boot.control.playSt.unitDisplayType = 1
        }
        while (true) {
            boot.frame()
            frame++
            if (boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) break
            check(frame < 3000)
        }
        phase = 1
        val units = boot.units
        val enemy = (0x81..0xB2).first { units.isValid(it) }
        val tx = units.xPos(enemy)
        val ty = units.yPos(enemy)
        val bm = boot.loop.bmSt
        var guard = 0
        while ((bm.playerCursorX != tx || bm.playerCursorY != ty ||
                bm.playerCursorDisplayX != tx * 16 || bm.playerCursorDisplayY != ty * 16) &&
            guard++ < 400
        ) {
            key = if (guard % 8 != 1) 0 else if (bm.playerCursorX < tx) 1
            else if (bm.playerCursorX > tx) 2 else if (bm.playerCursorY > ty) 3 else 4
            boot.frame()
            boot.pack()
        }
        key = 0
        check(bm.playerCursorX == tx && bm.playerCursorY == ty) { "cursor not on the enemy" }
        if (burst) {
            check(boot.engine.procs.any { it.script?.name == "gProcScr_UnitDisplay_Burst" }) {
                "no Burst window"
            }
        }
        repeat(40) { boot.frame(); boot.pack() } // the window slides in
        val bytes = LongArray(60)
        for (i in bytes.indices) {
            val before = AllocationMeter.current()
            boot.frame()
            boot.pack()
            bytes[i] = AllocationMeter.current() - before
        }
        return bytes
    }
}
