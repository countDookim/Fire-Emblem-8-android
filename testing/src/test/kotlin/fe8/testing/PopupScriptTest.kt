package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.Popup
import fe8.game.ui.PopupScript
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The hosted popup proc compared with the item popup capture, step by step. */
class PopupScriptTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("NewPopupCore", "NewPopup_Simple")
    fun `hosted popup proc follows the traced script position and ends with it`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run("ram_trace_item_popup")
        val dumps = (18570..18750).map { it to ProcArrayDump.ofEwram(dump(run, it)) }
        val first = dumps.indexOfFirst { it.second.find(Popup.PROC_SCRIPT) != null }
        val last = dumps.indexOfLast { it.second.find(Popup.PROC_SCRIPT) != null }
        assertTrue(first >= 0 && last in first until dumps.size - 1, "no popup lifetime")
        val iwram = region(run, dumps[first].first, "iwram")
        val popupItem = (iwram[GPOPUPITEM].toInt() and 0xFF) or
            ((iwram[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)
        val romBytes = romFile.readBytes()
        val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val host = VramHost()
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        text.initTextFont(null, 0x06001000, 0x80, 0)
        val engine = ProcEngine()
        var musicAlive = true
        val musicScript = ProcScript(
            "music",
            listOf(
                ProcScripts.repeat { if (!musicAlive) engine.end(it) },
                ProcScripts.end(),
            ),
        )
        val volumes = ArrayList<List<Int>>()
        val deps = PopupScript.Dependencies(
            rom = RomView.ofRom(romBytes),
            messages = messages,
            text = text,
            hardware = BgHardware(),
            drawnIconLookupTable = ByteArray(0x200),
            iconGfxIdLookupTable = ByteArray(0x100),
            popupItem = { popupItem },
            popupNumber = { 0 },
            unitNameTextId = { 0 },
            loadIconPalette = { _, _ -> },
            startBgmVolumeChange = { proc, v, e, t ->
                volumes.add(listOf(v, e, t))
                // Stand-in for the BGM volume proc: blocks the popup while the trace has one.
                engine.startBlocking(musicScript, proc)
            },
            playSoundEffect = { },
            newKeys = { 0 }, // the capture has no key wait: the clock runs out
        )
        val popup = PopupScript(engine, deps)
        val original0 = dumps[first].second.find(Popup.PROC_SCRIPT)!!
        val mine = popup.start(null, GOT_ITEM, 0x60, 0)
        assertNotNull(mine)
        val state = mine!!.state as Popup.PopupProc
        assertEquals(original0.u32(0x2C), state.pDefinition, "pDefinition")
        assertEquals(original0.u32(0x30), state.clock, "clock")
        assertEquals(original0.u8(0x36), state.winStyle, "winStyle")
        assertEquals(original0.u16(0x40), state.iconObjTileId, "iconObjTileId")
        assertEquals(original0.u8(0x42), state.iconPalId, "iconPalId")
        var endStep = -1
        for (i in first..last + 1) {
            val (step, d) = dumps[i]
            val original = d.find(Popup.PROC_SCRIPT)
            musicAlive = d.find(MUSIC_SCRIPT) != null
            // The first snapshot is taken after the frame that started the proc, whose own
            // Proc_Run already ticked its sleep once, so every snapshot is one run.
            engine.run(engine.root(3))
            if (original == null) {
                assertNull(mine.script, "step $step: mine still alive")
                endStep = step
                break
            }
            assertNotNull(mine.script, "step $step: mine ended early")
            assertEquals(original.scrCur, mine.scrCur, "step $step scrCur")
        }
        assertEquals(dumps[last + 1].first, endStep, "end step")
        assertEquals(listOf(listOf(0x100, 0x80, 0x10), listOf(0x80, 0x100, 0x10)), volumes)
    }

    private fun region(run: ReferenceData.Run, step: Int, name: String): ByteArray =
        try {
            run.region("r$step", name)
        } catch (e: java.io.IOException) {
            run.region("f$step", name)
        }

    private fun dump(run: ReferenceData.Run, step: Int): ByteArray = region(run, step, "ewram")

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val GOT_ITEM = 0x08592230
        const val MUSIC_SCRIPT = 0x08587970
        const val ROM_BASE = 0x08000000

        /** `gPopupItem` (0x030005F4) in the IWRAM dump. */
        const val GPOPUPITEM = 0x5F4
    }

    /** English host with a plain VRAM array; palette changes are ignored. */
    private class VramHost : TextHost {
        val vram = ByteArray(0x18000)

        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) = Unit

        override fun readVram32(addr: Int): Int {
            var v = 0
            for (k in 0..3) v = v or ((vram[addr - 0x06000000 + k].toInt() and 0xFF) shl (8 * k))
            return v
        }

        override fun writeVram32(addr: Int, value: Int) {
            for (k in 0..3) vram[addr - 0x06000000 + k] = (value shr (8 * k)).toByte()
        }

        override fun clearVram(dest: Int, byteCount: Int) {
            vram.fill(0, dest - 0x06000000, dest - 0x06000000 + byteCount)
        }
    }
}
