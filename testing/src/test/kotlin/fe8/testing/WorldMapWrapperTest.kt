package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import fe8.game.RomView
import fe8.game.flags.Fe8EventFlags
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WorldMapPath
import gba.core.VerifiesPort
import java.io.File
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** `NewWorldMap` and the wrapper script against the prologue `procs` capture. */
class WorldMapWrapperTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("NewWorldMap")
    fun `wrapper starts the world map main proc with the original timer`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val expected = ProcArrayDump.ofWatch(trace, FIRST_STEP_AFTER_INIT, names)
        val main = checkNotNull(expected.find("ProcScr_WorldMapMain"))

        val engine = ProcEngine()
        val mainScript = ProcScript("ProcScr_WorldMapMain", listOf(block(), end())) {
            WorldMapMainProc()
        }
        val fadeIn = ProcScript("ProcScr_BmFadeIN", listOf(end()))
        val wm = WorldMapMain(engine, PlaySt(), GmData(ByteArray(0x8000), RngService()), mainScript, fadeIn)
        engine.start(wm.wrapperScript, 3)
        repeat(3) { engine.run(engine.root(3)) }
        val ours = checkNotNull(engine.find(mainScript))
        val state = ours.state as WorldMapMainProc
        assertEquals(main.u32(0x2C), state.timer, "timer")
        assertEquals((main.u8(0x29) shr 1) and 1, state.flags1, "flags_1")
    }

    @Test
    @VerifiesPort("WorldMap_CallBeginningEvent")
    fun `beginning event sets the current node and chapter like the original`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM not present")
        val run = ref.run("ram_trace_prologue")
        val playStTrace = run.watch("gplayst")
        val flagTrace = run.watch("flags")
        val before = 4860
        val after = 4950
        val iwram = run.region("r%05d".format(before), "iwram").copyOf(0x8000)
        val expectedNode =
            run.region("r%05d".format(after), "iwram")[GmData.BASE + 0xC8].toInt() and 0xFF
        // Poison the outputs so a missing write fails, since the trace already holds them.
        iwram[GmData.BASE + 0xC8] = 0x7F
        val playSt = PlaySt()
        playSt.chapterIndex = 0x55
        playSt.chapterModeIndex = playStTrace.u8(before, 0x1B)
        val flags = Fe8EventFlags.create().also { it.copyIn(flagTrace.step(before)) }
        val engine = ProcEngine()
        val wm = WorldMapMain(
            engine, playSt, GmData(iwram, RngService()),
            ProcScript("m", listOf(end())), ProcScript("f", listOf(end())),
        )
        var event: Int? = null
        var chapterSeen = -1
        wm.callBeginningEvent(
            WorldMapMainProc(),
            WmNodeData(RomView.ofRom(romFile.readBytes())),
            { flags.checkFlag(it) },
            WorldMapMain.BeginningHooks(
                beginningEvent = { chapterSeen = it; 1 },
                callEvent = { event = it },
            ),
        )
        assertEquals(expectedNode, GmData(iwram, RngService()).currentNode, "current_node")
        assertEquals(playStTrace.u8(after, 0x0E), playSt.chapterIndex and 0xFF, "chapterIndex")
        assertEquals(playSt.chapterIndex, chapterSeen)
        assertEquals(1, event, "CallEvent reached")
    }

    @Test
    @VerifiesPort("sub_80B8FD4")
    fun `sub_80B8FD4 sets state_0 and clears state_3 like the original`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        val before = run.region("r%05d".format(4890), "iwram").copyOf(0x8000)
        // Step 4920 is after sub_80B8FD4 but before the later code sets state_3 again.
        val expected =
            run.region("r%05d".format(4920), "iwram")[GmData.BASE].toInt() and 0xFF
        val gm = GmData(before, RngService())
        val wm = WorldMapMain(
            ProcEngine(), PlaySt(), gm,
            ProcScript("m", listOf(end())), ProcScript("f", listOf(end())),
        )
        wm.sub80B8FD4()
        assertEquals(expected and 0x09, gm.stateRaw and 0x09, "state_0/state_3")
    }

    /**
     * `WorldMap_Init` (script position 3, entered by the `call`) spans trace steps 4899-4901 in
     * the original: the main proc's script position and the wrapper's stay put while the procs
     * `WorldMap_Init` creates appear piecemeal (`ProcScr_GMapScreen` at 4900, 15 more at 4901,
     * in creation order of one call). So the snapshots lie inside one multi-frame callback
     * (`SetupGraphicSystemsForWorldMap` loads gfx and lags frames, `OnVBlank` keeps running), not
     * a yield; `gGameClock` still advances 30 in the 30 steps 4890-4920 (r dumps). Untagged.
     */
    @Test
    fun `world map init is one multi-frame callback in the original`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        for (step in 4899..4901) {
            assertEquals(3, checkNotNull(dump(step).find("ProcScr_WorldMapMain")).scrCur, "$step")
        }
        assertEquals(null, dump(4899).find("ProcScr_GMapScreen"))
        assertEquals(1, dump(4900).live.size - dump(4899).live.size)
        assertEquals(15, dump(4901).live.size - dump(4900).live.size)
        assertEquals(5, checkNotNull(dump(4902).find("ProcScr_WorldMapMain")).scrCur)
        val clock = { step: Int ->
            run.region("r%05d".format(step), "iwram").let {
                (it[0x14].toInt() and 0xFF) or ((it[0x15].toInt() and 0xFF) shl 8)
            }
        }
        assertEquals(30, clock(4920) - clock(4890), "gGameClock")
    }

    /**
     * The sleeps of `ProcScr_WorldMapMain`, aligned on completed main-proc updates rather than
     * raw trace steps: the original's `WorldMap_Init` callback spans several trace steps (see the
     * test above), so the comparison covers only the positions after it: the `sleep(4)` (position
     * 5) is held 5 steps and the `sleep(1)` (position 7) 1 step, up to the repeat (position 10).
     * Not tagged: no script is tagged `@VerifiesPort` in this project.
     */
    @Test
    fun `main script prefix sleeps for the same number of steps as the original`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM not present")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val expected = (4898..4930).map { step ->
            ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_WorldMapMain")?.scrCur
        }

        val engine = ProcEngine()
        val nodes = WmNodeData(RomView.ofRom(romFile.readBytes()))
        val fadeIn = ProcScript("ProcScr_BmFadeIN", listOf(end()))
        val gm = GmData(ByteArray(0x8000), RngService())
        val builder = WorldMapMain(engine, PlaySt(), gm, ProcScript("m", listOf(end())), fadeIn)
        val main = ProcScript(
            "ProcScr_WorldMapMain", builder.mainScriptPrefix(nodes, { false }) + block(),
        ) { WorldMapMainProc() }
        val real = WorldMapMain(engine, PlaySt(), gm, main, fadeIn)
        engine.start(real.wrapperScript, 3)
        val ours = (4898..4930).map {
            engine.run(engine.root(3))
            engine.find(main)?.scrCur
        }
        assertEquals(mapOf(5 to 5, 7 to 1), dwell(expected), "original")
        assertEquals(dwell(expected), dwell(ours))
    }

    /** Steps spent at each script position from 5 up to (not including) 10. */
    private fun dwell(positions: List<Int?>): Map<Int, Int> {
        val steps = linkedMapOf<Int, Int>()
        for (p in positions) {
            if (p == null || p < 5 || p >= 10) continue
            steps[p] = (steps[p] ?: 0) + 1
        }
        return steps
    }

    /**
     * `sub_80B8FEC` against the node icon proc (`ProcScr_GmNodeIconDisplay`) at step 4902.
     * The trace covers only the branch with no valid+cleared node (node 0 is cleared but not
     * valid), so `merge_next_node` false is checked and the `nodeId` write is not. The output
     * flag is poisoned to true first.
     */
    @Test
    @VerifiesPort("sub_80B8FEC")
    fun `sub_80B8FEC clears merge_next_node when no node is next like the original`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM not present")
        val run = ref.run("ram_trace_prologue")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = ProcArrayDump.ofWatch(run.watch("procs"), 4902, names)
        val icon = checkNotNull(procs.find("ProcScr_GmNodeIconDisplay"))
        val expectedMerge = (icon.u8(0x32) shr 1) and 1
        val gm = GmData(run.region("r04890", "iwram").copyOf(0x8000), RngService())
        val path = WorldMapPath(gm, WmPathData(RomView.ofRom(romFile.readBytes())))
        val wm = WorldMapMain(
            ProcEngine(), PlaySt(), gm,
            ProcScript("m", listOf(end())), ProcScript("f", listOf(end())),
        )
        val proc = WorldMapMainProc()
        proc.gmIcon.mergeNextNode = true
        proc.gmIcon.nodeId = 0x7F
        wm.sub80B8FEC(proc) { path.getNextUnclearedNode() }
        assertEquals(expectedMerge, if (proc.gmIcon.mergeNextNode) 1 else 0, "merge_next_node")
    }

    /** `WmMain_MoveCamera`'s clamps by hand from the decomp arithmetic (untagged: no trace). */
    @Test
    fun `wmMainMoveCamera keeps the cursor inside the margins`() {
        val gm = GmData(ByteArray(0x8000), RngService())
        val wm = WorldMapMain(
            ProcEngine(), PlaySt(), gm,
            ProcScript("m", listOf(end())), ProcScript("f", listOf(end())),
        )
        fun camera(ix: Int, iy: Int, cx: Int, cy: Int): Pair<Int, Int> {
            gm.ix = ix shl 8
            gm.iy = iy shl 8
            gm.xCamera = cx
            gm.yCamera = cy
            wm.wmMainMoveCamera()
            return gm.xCamera to gm.yCamera
        }
        assertEquals(0 to 0, camera(0x10, 0x10, 0x40, 0x40))
        assertEquals(0x30 - 0x20 to 0x50 - 0x20, camera(0x30, 0x50, 0x40, 0x60))
        assertEquals(0x100 - 0xD0 to 0x100 - 0x80, camera(0x100, 0x100, 0, 0))
        assertEquals(0xF0 to 0xA0, camera(0x1C0, 0x120, 0, 0))
        assertEquals(0x80 to 0x60, camera(0xA0, 0x90, 0x80, 0x60))
    }

    private companion object {
        /** First step at which the original's `NewWorldMap` has set the timer (128 before). */
        const val FIRST_STEP_AFTER_INIT = 4902
    }
}
