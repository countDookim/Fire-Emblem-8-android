package fe8.testing

import fe8.game.RomView
import fe8.game.event.EventEngineProc
import fe8.game.event.ScriptMemory
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WorldMapScroll
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The world map camera (`gGMData.xCamera/yCamera`) against the `r` dumps (every 30 steps) of
 * `ram_trace_prologue`. The prologue only runs `Event85_WmSetCameraOntoUnit`
 * (`WM_CENTERCAMONLORD`): the
 * camera moves once, 0,0 to 32,8, between steps 4890 and 4920 and stays there to step 9330.
 */
class WorldMapScrollTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("Event85_WmSetCameraOntoUnit", "GetWMCenteredCameraPosition")
    fun `centring the camera on the leader gives the camera of the original every 30 steps`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM not present")
        val run = ref.run("ram_trace_prologue")
        val iwram = run.region("r04890", "iwram").copyOf(0x8000)
        val gm = GmData(iwram, RngService())
        // Poison the outputs: the dump holds the original's camera only from step 4920 on.
        gm.xCamera = 0x7F
        gm.yCamera = 0x7F
        val nodes = WmNodeData(RomView.ofRom(romFile.readBytes()))
        val scroll = WorldMapScroll(ProcEngine(), gm, nodes)
        val events = scroll.CameraEvents(ScriptMemory { 0 })
        // `WM_CENTERCAMONLORD(WM_MU_0)`: the argument word is unit slot 0.
        events.event85SetCameraOntoUnit.run(EventEngineProc())

        for (step in 4920..9300 step 30) {
            // Every 300th step holds a full `f` dump instead of an `r` dump.
            val name = if (step % 300 == 0) "f%05d" else "r%05d"
            val dump = GmData(run.region(name.format(step), "iwram").copyOf(0x8000), RngService())
            assertEquals(dump.xCamera to dump.yCamera, gm.xCamera to gm.yCamera, "camera at $step")
        }
    }
}
