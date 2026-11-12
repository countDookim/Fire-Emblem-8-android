package fe8.testing

import fe8.game.Fe8Game
import fe8.game.Fe8Sound
import fe8.game.RomView
import gba.input.ActionSource
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The Prologue world map through the real `Fe8Boot` New Game path against `ram_trace_prologue`
 * (wiring only, no port claim): per-frame proc-tree checkpoints at the world map start, a few mid
 * points and the exit.
 *
 * Alignment: the boot's monologue is shorter than the original's, so frames are matched by the
 * world map's own ticks. The original runs `WorldMap_Init` over steps 4899-4901 and the port
 * atomically: the first boot frame with `ProcScr_WorldMapMain` is step 4902. After that boot frame
 * `f0 + n` is step `4902 + n` except for the original's lag steps ([LAG_STEPS]: the lag of
 * `GmapRm_StartUpdateDirect`, where the original's game loop skips passes), which the port does
 * not have. The exit is aligned on `ProcScr_WorldMapMain` reaching command 106 (step 9288); the
 * boot's world map is shorter than the original's by the unit and dot commands that are still
 * release stubs (D11-wm8e), so only the exit's own frames are compared there.
 */
class WorldMapBootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val rom = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private val names by lazy { ProcArrayDump.romNames() }

    private fun ours(p: PrologueBoot, only: Set<String>?): List<String> =
        p.boot.engine.procs.filter { it.script != null }
            .filter { only == null || it.script!!.name in only }
            .map { "${it.script!!.name}@${it.scrCur}/l${it.lockCnt}" }.sorted()

    private fun theirs(s: Int, only: Set<String>?): List<String> =
        ProcArrayDump.ofWatch(ref.run("ram_trace_prologue").watch("procs"), s, names).live
            .filter { only == null || it.name in only }
            .map { "${it.name}@${it.scrCur}/l${it.u8(0x28)}" }.sorted()

    /**
     * The boot with A pressed throughout (title, save menu, monologue; and the world map's talk
     * page, which stays on its wait-for-input page without a key and so never reaches the exit).
     */
    private fun boot(): PrologueBoot {
        lateinit var holder: PrologueBoot
        holder = PrologueBoot(rom, Pulse { f -> f % 20 in 5..8 })
        return holder
    }

    private fun worldMapMain(p: PrologueBoot) =
        p.boot.engine.procs.firstOrNull { it.script?.name == "ProcScr_WorldMapMain" }

    @Test
    fun `world map start and mid points match the procs watch`() {
        assumeTrue(ref.available && rom.isFile && names.isNotEmpty(), "reference data missing")
        val p = boot()
        while (worldMapMain(p) == null && p.frame < 9000) p.advance()
        assumeTrue(worldMapMain(p) != null, "world map not reached")
        // The monologue's A presses must not reach the text: none before step 5100 matters.
        var step = 4902
        var checked = 0
        var first = true
        while (step <= LAST_CHECKED) {
            if (step in LAG_STEPS) {
                step++
                continue
            }
            if (!first) p.advance()
            first = false
            if (step in CHECK_STEPS) {
                assertEquals(theirs(step, null), ours(p, null), "proc tree at step $step")
                checked++
            }
            step++
        }
        assertEquals(CHECK_STEPS.size, checked)
    }

    @Test
    fun `world map exit matches the procs watch`() {
        assumeTrue(ref.available && rom.isFile && names.isNotEmpty(), "reference data missing")
        val p = boot()
        val shared = setOf(
            "ProcScr_WorldMapMain", "ProcScr_WorldMapWrapper", "gProcScr_GameControl",
            "ProcScr_WorldMapFaceCtrl", "ProcScr_WorldMapFaceHolder", "gProcScr_GmapMuEntry2",
            "ProcScr_GmapRM", "ProcScr_GmapRmUpdateDirect", "ProcScr_WorldMapTextHandler",
            "ProcScr_StdEventEngine",
        )
        // Run to the frame in which the main proc passes label 15 (command 106): step 9288.
        var guard = 0
        while ((worldMapMain(p)?.scrCur ?: 0) < 106 && guard++ < 12000) p.advance()
        assumeTrue(guard < 12000, "world map exit not reached")
        var checked = 0
        for (s in 9288..9313) {
            assertEquals(theirs(s, shared), ours(p, shared), "proc tree at step $s")
            checked++
            p.advance()
        }
        assertEquals(26, checked)
    }

    /**
     * Steady world map frames (every proc sits in a repeat loop: the signature of all proc
     * scripts and cursors is unchanged around the frame) allocate nothing, with the m4a driver
     * running. Frames that start or end a command are not counted, nor the frames of the text
     * phase (the talk system's own allocation).
     */
    @Test
    fun `steady world map frames allocate no bytes`() {
        assumeTrue(AllocationMeter.supported, "allocation meter unavailable")
        assumeTrue(rom.isFile, "ROM unavailable")
        // Two passes over the same deterministic boot; a frame counts only if it allocates in both
        // (the minimum, as `AllocationMeter.measureMin` does). A first-time branch inside a long
        // scanline loop (WorldMapLineFade, WorldMapMuEntry) deoptimizes the JIT-compiled code once,
        // and HotSpot re-creates the scalar-replaced objects of the inlined callers (88..96 bytes
        // in one frame): that depends on what ran in the JVM before, and is gone in the second
        // pass. Code that really allocates does so in the same frames in both passes.
        val first = steadyFrameBytes()
        val second = steadyFrameBytes()
        assertTrue(second.size > 100, "steady map frames: ${second.size}")
        val allocating = second.entries
            .filter { (t, bytes) -> minOf(bytes, first[t] ?: Long.MAX_VALUE) != 0L }
            .map { (t, bytes) -> "map frame $t: $bytes" }
        assertEquals(emptyList<String>(), allocating.take(10), "bytes in steady world map frames")
    }

    /** One boot to the world map; the allocated bytes of each steady map frame, by map frame. */
    private fun steadyFrameBytes(): Map<Int, Long> {
        val view = RomView.ofRom(rom.readBytes())
        val game = Fe8Game(rom = view, sound = Fe8Sound.create(view))
        var frame = 0
        var titleAt = Int.MAX_VALUE
        var inMap = false
        game.actionSource = ActionSource {
            when {
                inMap -> 0L
                frame - titleAt in 0..3 -> 0x8L
                frame % 20 in 5..8 -> 0x1L
                else -> 0L
            }
        }
        val boot = game.boot!!
        fun talking() = boot.engine.procs.any { it.script?.name == "gProcScr_Talk" }
        fun signature(): Int {
            var h = 0
            for (proc in boot.engine.procs) {
                if (proc.script != null) h = h * 31 + proc.script.hashCode() * 7 + proc.scrCur
            }
            return h
        }
        val signatures = ArrayList<Int>()
        val bytes = ArrayList<Long>()
        var mapFrames = 0
        while (frame < 14000 && mapFrames < 3600) {
            if (titleAt == Int.MAX_VALUE &&
                boot.engine.procs.any { it.script?.name == "gProcScr_DrawTitleSprites" }
            ) titleAt = frame + 30
            inMap = worldMapMain(boot) != null
            game.input.update(game.actionSource.poll())
            val before = AllocationMeter.current()
            boot.frame()
            val after = AllocationMeter.current()
            game.present(0f)
            frame++
            if (inMap) {
                mapFrames++
                // The talk system's text printing (steps 5106 on) allocates per page; it is the
                // talk's own cost, not the world map wiring's.
                signatures.add(if (talking()) -1 else signature())
                bytes.add(after - before)
            }
        }
        val steady = LinkedHashMap<Int, Long>()
        for (t in 1 until signatures.size - 1) {
            if (signatures[t] != -1 && signatures[t - 1] == signatures[t] &&
                signatures[t] == signatures[t + 1]
            ) steady[t] = bytes[t]
        }
        return steady
    }

    private fun worldMapMain(boot: fe8.game.Fe8Boot) =
        boot.engine.procs.firstOrNull { it.script?.name == "ProcScr_WorldMapMain" }

    private companion object {
        val LAG_STEPS = setOf(4944, 4945, 4946, 4947)
        val CHECK_STEPS = (4902..4943).toSet() + setOf(4948, 4950, 4980, 5010, 5040, 5060, 5090)
        const val LAST_CHECKED = 5090
    }
}
