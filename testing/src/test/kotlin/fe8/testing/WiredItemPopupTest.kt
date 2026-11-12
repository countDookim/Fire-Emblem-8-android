package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.ui.GotItemPopup
import fe8.game.ui.Popup
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Exercises the real Prologue event and popup through the device boot wiring. */
class WiredItemPopupTest {
    @Test
    fun `prologue popup displays captured icon and grants item after closing`() {
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "reference capture unavailable")
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(dir, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run("ram_trace_item_popup")
        val refOam = run.region("f18630", "oam")
        val refPal = run.region("f18630", "pal")
        val refVram = run.region("f18630", "vram")
        val refEwram = run.region("f18630", "ewram")
        val tracedGot = ProcArrayDump.ofEwram(refEwram).find(GotItemPopup.PROC_SCR_GOT_ITEM)!!
        val tracedUnitOffset = tracedGot.u32(0x54) - 0x02000000
        val finalEwram = try {
            run.region("r18750", "ewram")
        } catch (e: java.io.IOException) {
            run.region("f18750", "ewram")
        }
        val tracedInventory =
            finalEwram.copyOfRange(tracedUnitOffset + 0x1E, tracedUnitOffset + 0x28)
        val icon = (0 until 128).first { u16(refOam, it * 8 + 4) == 0x4240 }
        val attrs = ShortArray(3) { u16(refOam, icon * 8 + it * 2).toShort() }
        var frame = 0
        var holdKeys = false
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            { if (!holdKeys && frame % 20 in 5..8) 1 else 0 },
            RngService(),
        )
        fun proc(name: String) = boot.engine.procs.firstOrNull { it.script?.name == name }
        while (frame < 24000 && proc("ProcScr_GotItem") == null) {
            boot.frame()
            frame++
        }
        val got = proc("ProcScr_GotItem")
        assertNotNull(got, "opening did not start the got-item proc")
        holdKeys = true
        val state = got!!.state as GotItemPopup.GotItemPopupProc
        val unitOffset = boot.units.unitOffset(state.unit)
        val itemId = state.item and 0xFF
        fun inventory() = (0 until 5).map {
            boot.units.bytes[unitOffset + 0x1E + 2 * it].toInt() and 0xFF
        }
        assertTrue(itemId !in inventory(), "item was granted before popup")
        assertTrue(got.parent!!.lockCnt > 0, "event must be blocked")
        var allocationChecked = false
        var iconSeen = false
        var volumeBlocks = 0
        var volumeStarts = 0
        var wasVolumeActive = false
        var popupState: Popup.PopupProc? = null
        var activeFrames = 0
        while (got.script != null && activeFrames++ < 300) {
            boot.frame()
            frame++
            val popup = proc("ProcScr_Popup")
            if (popup != null) popupState = popup.state as Popup.PopupProc
            val volume = proc("sMusicProc2Script")
            val volumeActive = volume?.parent === popup && popup != null
            if (volumeActive) {
                if (!wasVolumeActive) volumeStarts++
                assertTrue(popup!!.lockCnt > 0, "volume change must block popup")
                volumeBlocks++
            }
            wasVolumeActive = volumeActive
            val iconProc = proc("ProcScr_PopupUpdateIcon")
            if (iconProc != null) {
                if (!allocationChecked && iconProc.idleCb != null && AllocationMeter.supported) {
                    val oam = boot.loop.oam
                    val saved = oam.buffer.copyOf()
                    val savedPut = oam.hiPut
                    val callback = iconProc.idleCb!!
                    val allocated = AllocationMeter.measureMin(
                        warmup = 20_000, iterations = 10_000,
                    ) {
                        oam.hiPut = 0
                        callback.invoke(iconProc)
                    }
                    saved.copyInto(oam.buffer)
                    oam.hiPut = savedPut
                    assertEquals(0L, allocated, "popup icon callback allocated")
                    allocationChecked = true
                }
                assertTrue(itemId !in inventory(), "item was granted while popup was visible")
                val oam = boot.loop.oam.oam
                if ((0 until 128).any { i ->
                    (0..2).all { j -> oam[i * 4 + j] == attrs[j] }
                }) {
                    iconSeen = true
                    val hw = boot.ui.env.hardware
                    val palette = ShortArray(16) { u16(refPal, 512 + 4 * 32 + it * 2).toShort() }
                    assertArrayEquals(palette, hw.paletteRam.copyOfRange(20 * 16, 21 * 16))
                    val drawn = popupState!!
                    val textTiles = drawn.xTileSize - 2
                    assertArrayEquals(
                        refVram.copyOfRange(0x2000, 0x2000 + textTiles * 64),
                        hw.vram.copyOfRange(0x2000, 0x2000 + textTiles * 64),
                        "popup glyph tiles",
                    )
                    for (row in 0..1) {
                        for (col in 0 until textTiles) {
                            val cell = (drawn.yTileReal + 1 + row) * 32 +
                                drawn.xTileReal + 1 + col
                            val expected = u16(refEwram, 0x22CA8 + cell * 2)
                            assertEquals(expected, hw.mapBuffer(0)[cell].toInt() and 0xFFFF)
                            assertEquals(
                                expected, u16(hw.vram, hw.vramTilemapOffsets[0] + cell * 2),
                                "synced popup text cell",
                            )
                        }
                    }
                    for (offset in listOf(0, 0x400)) {
                        val start = 0x10000 + 0x240 * 0x20 + offset
                        assertArrayEquals(
                            refVram.copyOfRange(start, start + 0x40),
                            hw.vram.copyOfRange(start, start + 0x40),
                        )
                    }
                }
            }
        }
        assertTrue(got.script == null, "got-item proc did not finish")
        val drawn = popupState!!
        val hw = boot.ui.env.hardware
        // VBlank after the end callback must transfer the cleared rectangle to VRAM.
        boot.frame()
        assertTrue(
            (0 until 128).none { i ->
                (0..2).all { j -> boot.loop.oam.oam[i * 4 + j] == attrs[j] }
            },
            "popup icon remained in hardware OAM after closing",
        )
        for (bg in 0..1) {
            for (row in 0..drawn.yTileSize) {
                for (col in 0..drawn.xTileSize) {
                    val cell = (drawn.yTileReal + row) * 32 + drawn.xTileReal + col
                    assertEquals(0, hw.mapBuffer(bg)[cell].toInt(), "cleared BG$bg buffer")
                    assertEquals(
                        0, u16(hw.vram, hw.vramTilemapOffsets[bg] + 2 * cell),
                        "cleared BG$bg VRAM",
                    )
                }
            }
        }
        assertTrue(volumeBlocks > 0, "real BGM volume proc did not block the popup")
        assertEquals(2, volumeStarts, "popup must dim and restore BGM")
        assertTrue(iconSeen, "captured popup icon never appeared in hardware OAM")
        if (AllocationMeter.supported) {
            assertTrue(allocationChecked, "icon callback was not measured")
        }
        assertTrue(itemId in inventory(), "item missing after popup")
        assertArrayEquals(
            tracedInventory, boot.units.bytes.copyOfRange(unitOffset + 0x1E, unitOffset + 0x28),
            "inventory after the popup",
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
