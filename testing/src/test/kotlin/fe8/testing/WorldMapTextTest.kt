package fe8.testing

import fe8.game.worldmap.GMapTextProc
import fe8.game.worldmap.WmHblankStatus
import fe8.game.worldmap.WmTextHost
import fe8.game.worldmap.WorldMapText
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `src/worldmap_text.c` against `ram_trace_prologue`: the text handler proc's fields (`procs`
 * watch), the text OBJ tile rows (full dump f05100), the text window OAM entries (f05400 and
 * later) and `gWmHblankStatus` (r dumps). The text is first shown at step 5102 and hidden at 9250.
 */
class WorldMapTextTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun newText(
        hardware: BgHardware = BgHardware(),
        hblank: WmHblankStatus = WmHblankStatus(),
        layers: SpriteLayers = SpriteLayers(),
        engine: ProcEngine = ProcEngine(),
    ) = WorldMapText(engine, hardware, hblank, layers, NullHost)

    private fun run(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        return ref.run("ram_trace_prologue")
    }

    private fun fields(run: ReferenceData.Run,step: Int): List<Int> {
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val proc = checkNotNull(
            ProcArrayDump.ofWatch(run.watch("procs"), step, names)
                .find("ProcScr_WorldMapTextHandler"),
        )
        return listOf(proc.u8(0x29), proc.u8(0x2A))
    }

    private fun GMapTextProc.pair() = listOf(drawAtBottom, visible)

    @Test
    @VerifiesPort("WmText_Init")
    fun `text handler init matches the proc fields and hblank bit`() {
        val run = run()
        val hblank = WmHblankStatus().apply { bits = 0xFF }
        val engine = ProcEngine()
        val text = newText(hblank = hblank, engine = engine)
        val proc = engine.start(text.script, 3)
        assertEquals(fields(run, 4903), (proc.state as GMapTextProc).pair(), "fields")
        val ewram = run.region("r05130", "ewram")
        assertEquals(ewram[HBLANK_STATUS].toInt() and 1, hblank.bits and 1, "gWmHblankStatus bit 0")
    }

    @Test
    @VerifiesPort("ShowWmText")
    fun `show text sets visible and draw position like step 5102`() {
        val run = run()
        assertNotEquals(fields(run, 5101), fields(run, 5102), "trace changes at 5102")
        val engine = ProcEngine()
        val text = newText(engine = engine)
        val proc = engine.start(text.script, 3)
        text.showWmText(1)
        assertEquals(fields(run, 5102), (proc.state as GMapTextProc).pair())
    }

    @Test
    @VerifiesPort("HideWmText")
    fun `hide text clears visible like step 9250`() {
        val run = run()
        assertNotEquals(fields(run, 9249), fields(run, 9250), "trace changes at 9250")
        val engine = ProcEngine()
        val text = newText(engine = engine)
        val proc = engine.start(text.script, 3)
        text.showWmText(1)
        text.hideWmText()
        assertEquals(fields(run, 9250), (proc.state as GMapTextProc).pair())
    }

    @Test
    @VerifiesPort("ClearWmTextVram")
    fun `clear text vram matches the OBJ tile rows of the f05100 dump`() {
        val run = run()
        val expected = run.region("f05100", "vram")
            .copyOfRange(WorldMapText.TEXT_VRAM, WorldMapText.TEXT_VRAM + 0x1000)
        val hardware = BgHardware()
        // Garbage everywhere; only the 0x80 bytes after each cleared 0x380 are real data.
        hardware.vram.fill(0x5A, WorldMapText.TEXT_VRAM - 2, WorldMapText.TEXT_VRAM + 0x1002)
        for (row in 0 until 4) {
            val tail = row * 0x400 + 0x380
            expected.copyInto(
                hardware.vram, WorldMapText.TEXT_VRAM + tail, tail, row * 0x400 + 0x400,
            )
        }
        newText(hardware = hardware).clearWmTextVram()
        assertEquals(
            0x5A,
            hardware.vram[WorldMapText.TEXT_VRAM - 1].toInt() and 0xFF,
            "byte before the rows",
        )
        assertArrayEquals(
            expected,
            hardware.vram.copyOfRange(WorldMapText.TEXT_VRAM, WorldMapText.TEXT_VRAM + 0x1000),
            "OBJ tile rows",
        )
    }

    @Test
    @VerifiesPort("WmText_Loop_DrawText")
    fun `text window sprites match the OAM of the dumps while shown`() {
        val run = run()
        for (step in listOf(5400, 6000, 9000)) {
            val layers = SpriteLayers().also { it.clear() }
            val engine = ProcEngine()
            val text = newText(layers = layers, engine = engine)
            val proc = engine.start(text.script, 3)
            text.showWmText(1)
            text.loopDrawText(proc.state as GMapTextProc)
            val oam = OamBuffer()
            layers.pushLayerObjects(1) { oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
            assertEquals(4, oam.hiPut / 4, "objects")
            val dump = run.region("f%05d".format(step), "oam")
            for (i in 0 until 4) {
                for (k in 0 until 3) {
                    assertEquals(
                        (dump[i * 8 + 2 * k].toInt() and 0xFF) or
                            ((dump[i * 8 + 2 * k + 1].toInt() and 0xFF) shl 8),
                        oam.buffer[i * 4 + k].toInt() and 0xFFFF,
                        "f$step obj $i attr $k",
                    )
                }
            }
        }
    }

    @Test
    fun `hidden text puts no sprites`() {
        val layers = SpriteLayers().also { it.clear() }
        val engine = ProcEngine()
        val text = newText(layers = layers, engine = engine)
        val proc = engine.start(text.script, 3)
        text.loopDrawText(proc.state as GMapTextProc)
        assertEquals(0, layers.allocIt)
    }

    private companion object {
        /** `gWmHblankStatus` (0x0201BE30) as an EWRAM offset. */
        const val HBLANK_STATUS = 0x1BE30
    }

    private object NullHost : WmTextHost {
        override fun endTalk() {}

        override fun initSpriteTalk(chr: Int, pal: Int, lines: Int) {}

        override fun startTalkMsg(x: Int, y: Int, msg: Int) {}

        override fun setTalkPrintDelay(delay: Int) {}

        override fun setTalkFlag(flag: Int) {}
    }
}
