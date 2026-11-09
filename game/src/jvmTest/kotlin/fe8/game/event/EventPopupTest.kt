package fe8.game.event

import fe8.game.RomView
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.scene.BrownTextBox
import fe8.game.scene.BrownTextBoxState
import gba.core.VerifiesPort
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests [EventPopup] and `Event3A_DisplayPopup` against the reference state at
 * `ram_trace_newgame_bm` r09540 -> r09541 and verifies slot fallback and skipping branches.
 */
class EventPopupTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    @VerifiesPort("Event3A_DisplayPopup")
    @Test
    fun `event3aDisplayPopup matches reference popup state at r09540`() {
        val dir = File(assets, "reference/out/ram_trace_newgame_bm")
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        assumeTrue(File(dir, "r09540.ewram.bin").isFile, "r09540.ewram.bin missing")
        assumeTrue(File(dir, "r09541.ewram.bin").isFile, "r09541.ewram.bin missing")

        val romBytes = romFile.readBytes()
        val mem = ScriptMemory { address ->
            val romOffset = address - 0x08000000
            if (romOffset in 0 until romBytes.size - 1) {
                (romBytes[romOffset].toInt() and 0xFF) or
                    ((romBytes[romOffset + 1].toInt() and 0xFF) shl 8)
            } else {
                0
            }
        }

        val preEwram = File(dir, "r09540.ewram.bin").readBytes()
        val postEwram = File(dir, "r09541.ewram.bin").readBytes()

        val procOffset = GPROC_EVENT_ENGINE - EWRAM_BASE
        val pEventCurrent40 = (preEwram[procOffset + 0x38].toInt() and 0xFF) or
            ((preEwram[procOffset + 0x39].toInt() and 0xFF) shl 8) or
            ((preEwram[procOffset + 0x3A].toInt() and 0xFF) shl 16) or
            ((preEwram[procOffset + 0x3B].toInt() and 0xFF) shl 24)
        assertEquals(0x089EECDC.toInt(), pEventCurrent40, "pEventCurrent at r09540")

        // In r09540, ProcScr_BrownTextBox is not yet started in EWRAM
        var foundIn40 = false
        for (slot in 0 until 64) {
            val off = (SPROC_ARRAY - EWRAM_BASE) + slot * PROC_SIZE
            val script = (preEwram[off].toInt() and 0xFF) or
                ((preEwram[off + 1].toInt() and 0xFF) shl 8) or
                ((preEwram[off + 2].toInt() and 0xFF) shl 16) or
                ((preEwram[off + 3].toInt() and 0xFF) shl 24)
            if (script == PROC_BROWN_TEXT_BOX) {
                foundIn40 = true
                break
            }
        }
        assertFalse(foundIn40, "ProcScr_BrownTextBox not present in r09540")

        // In r09541, ProcScr_BrownTextBox is alive in EWRAM
        var postX = -1
        var postY = -1
        var postTextId = -1
        var postChr = -1
        var postPal = -1
        var postWidth = -1
        for (slot in 0 until 64) {
            val off = (SPROC_ARRAY - EWRAM_BASE) + slot * PROC_SIZE
            val script = (postEwram[off].toInt() and 0xFF) or
                ((postEwram[off + 1].toInt() and 0xFF) shl 8) or
                ((postEwram[off + 2].toInt() and 0xFF) shl 16) or
                ((postEwram[off + 3].toInt() and 0xFF) shl 24)
            if (script == PROC_BROWN_TEXT_BOX) {
                postX = (postEwram[off + 0x30].toInt() and 0xFF) or
                    (postEwram[off + 0x31].toInt() shl 8)
                postY = (postEwram[off + 0x34].toInt() and 0xFF) or
                    (postEwram[off + 0x35].toInt() shl 8)
                postChr = (postEwram[off + 0x38].toInt() and 0xFF) or
                    ((postEwram[off + 0x39].toInt() and 0xFF) shl 8)
                postPal = (postEwram[off + 0x3C].toInt() and 0xFF) or
                    ((postEwram[off + 0x3D].toInt() and 0xFF) shl 8)
                postTextId = (postEwram[off + 0x40].toInt() and 0xFF) or
                    ((postEwram[off + 0x41].toInt() and 0xFF) shl 8)
                postWidth = (postEwram[off + 0x44].toInt() and 0xFF) or
                    ((postEwram[off + 0x45].toInt() and 0xFF) shl 8)
                break
            }
        }
        assertEquals(8, postX, "reference brown box x at r09541")
        assertEquals(0, postY, "reference brown box y at r09541")
        assertEquals(0x0664, postTextId, "reference brown box textId at r09541")
        assertEquals(0x5000, postChr, "reference brown box chr at r09541")
        assertEquals(9, postPal, "reference brown box pal at r09541")
        assertEquals(12, postWidth, "reference brown box width at r09541")

        // Test running Event3A_DisplayPopup
        val engine = ProcEngine()
        val parentScript = ProcScript("parent", listOf(gbafe.proc.ProcScripts.yield()))
        val dummyParent = engine.start(parentScript, 3)
        val hw = BgHardware()
        val romView = RomView.ofRom(romBytes)
        val brownTextBox = BrownTextBox(
            engine, hw, SpriteLayers(), romView,
            textWidth = { 7 * 8 },
        )

        val state = EventState()
        val popup = EventPopup(state, mem, brownTextBox)
        val proc = EventEngineProc().apply {
            pEventCurrent = pEventCurrent40
            evStateBits = 0
            this.proc = dummyParent
        }

        val result = popup.event3ADisplayPopup.run(proc)
        assertEquals(EVC_ADVANCE_YIELD, result, "Event3A yields")

        val boxProc = engine.find(brownTextBox.boxScript)
        assertNotNull(boxProc, "ProcScr_BrownTextBox created")
        val boxState = boxProc!!.state as BrownTextBoxState
        assertEquals(postTextId, boxState.textId, "textId matches reference")
        assertEquals(postX, boxState.x, "x matches reference")
        assertEquals(postY, boxState.y, "y matches reference")
        assertEquals(postChr, boxState.chr, "chr matches reference")
        assertEquals(postPal, boxState.pal, "pal matches reference")
        assertEquals(postWidth, boxState.width, "width matches reference")
        assertNotNull(engine.find(brownTextBox.fadeScript), "fade script created")
    }

    @Test
    fun `event3aDisplayPopup uses slot 2 when textId is negative`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        val state = EventState()
        state.slots[2] = 0x0543
        val words = shortArrayOf(
            0x3A41.toShort(), (-1).toShort(), 0x0008.toShort(), 0x0204.toShort(),
        )
        val mem = ScriptMemory { address ->
            val index = (address ushr 1) and 3
            words[index].toInt() and 0xFFFF
        }
        val engine = ProcEngine()
        val parentScript = ProcScript("parent", listOf(gbafe.proc.ProcScripts.yield()))
        val dummyParent = engine.start(parentScript, 3)
        val brownTextBox = BrownTextBox(
            engine, BgHardware(), SpriteLayers(), RomView.ofRom(romFile.readBytes()),
            textWidth = { 7 * 8 },
        )
        val popup = EventPopup(state, mem, brownTextBox)
        val proc = EventEngineProc().apply {
            pEventCurrent = 0
            evStateBits = 0
            this.proc = dummyParent
        }

        val result = popup.event3ADisplayPopup.run(proc)
        assertEquals(EVC_ADVANCE_YIELD, result)
        val boxProc = engine.find(brownTextBox.boxScript)
        assertNotNull(boxProc)
        val boxState = boxProc!!.state as BrownTextBoxState
        assertEquals(0x0543, boxState.textId)
        assertEquals(4, boxState.x)
        assertEquals(2, boxState.y)
    }

    @Test
    fun `event3aDisplayPopup continues and does not start box when skipping`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        val state = EventState()
        val words = shortArrayOf(0x3A41.toShort(), 0x0123.toShort(), 0.toShort(), 0.toShort())
        val mem = ScriptMemory { address ->
            val index = (address ushr 1) and 3
            words[index].toInt() and 0xFFFF
        }
        val engine = ProcEngine()
        val parentScript = ProcScript("parent", listOf(gbafe.proc.ProcScripts.yield()))
        val dummyParent = engine.start(parentScript, 3)
        val brownTextBox = BrownTextBox(
            engine, BgHardware(), SpriteLayers(), RomView.ofRom(romFile.readBytes()),
        )
        val popup = EventPopup(state, mem, brownTextBox)
        val proc = EventEngineProc().apply {
            pEventCurrent = 0
            evStateBits = EV_STATE_SKIPPING
            this.proc = dummyParent
        }

        val result = popup.event3ADisplayPopup.run(proc)
        assertEquals(EVC_ADVANCE_CONTINUE, result)
        assertNull(engine.find(brownTextBox.boxScript))
    }

    private companion object {
        const val EWRAM_BASE = 0x02000000
        const val GPROC_EVENT_ENGINE = 0x02025018
        const val SPROC_ARRAY = 0x02024E68
        const val PROC_SIZE = 0x6C
        const val PROC_BROWN_TEXT_BOX = 0x085924D8.toInt()
    }
}
