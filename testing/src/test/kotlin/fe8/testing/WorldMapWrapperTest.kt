package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import fe8.game.RomView
import fe8.game.flags.Fe8EventFlags
import fe8.game.worldmap.WmNodeData
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

    private companion object {

        /** First step at which the original's `NewWorldMap` has set the timer (128 before). */
        const val FIRST_STEP_AFTER_INIT = 4902
    }
}
