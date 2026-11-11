package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitSprites
import fe8.game.worldmap.WorldMapGfxSetup
import fe8.game.worldmap.WorldMapMainProc
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The graphics setup that opens `WorldMap_Init` (`SetupGraphicSystemsForWorldMap`, `sub_80B8E60`,
 * `SetupGmapNodeGfx`) against `ram_trace_prologue`. The run starts from the RAM of `r04890` and
 * the VRAM of `f04800` (before step 4899) and compares the LCD buffer with `r04920`, the VRAM
 * regions that survive with `f05100`, and the palettes with `f05100`.
 */
class WorldMapGfxSetupTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun romFile() = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun fixture(): GfxSetupFixture {
        val run = ref.run("ram_trace_prologue")
        val iwram = run.region("r04890", "iwram")
        val ewram = run.region("r04890", "ewram")
        val rom = RomView.ofRom(romFile().readBytes())
        val hw = BgHardware()
        hw.lcd.readFrom(iwram, 0x3080)
        val vram = run.region("f04800", "vram")
        vram.copyInto(hw.vram, 0, 0, minOf(vram.size, hw.vram.size))
        for (i in hw.palette.indices) hw.palette[i] = ewram.u16(0x228A8 + 2 * i).toShort()
        val playSt = PlaySt()
        playSt.chapterWeatherId = ewram[0x2BCF0 + 0x15].toInt() and 0xFF
        val bmSt = BmSt()
        bmSt.cameraX = 0x55
        bmSt.cameraY = 0x66
        val sprites = UnitSprites(rom, hw, bmSt)
        val hooks = WorldMapGfxSetup.Hooks(
            resetUnitSprites = { sprites.resetUnitSprites() },
            applyUnitSpritePalettes = { sprites.applyUnitSpritePalettes() },
        )
        return GfxSetupFixture(hw, WorldMapGfxSetup(hw, rom, playSt, bmSt, hooks), bmSt)
    }

    private fun available() = ref.available && romFile().isFile

    private fun assertSame(expected: ByteArray, actual: ByteArray, range: IntRange, what: String) {
        val bad = range.filter { expected[it] != actual[it] }
        val first = bad.firstOrNull()?.toString(16)
        assertEquals(0, bad.size, "$what: ${bad.size} bytes differ, first at 0x$first")
    }

    private fun assertPalettes(expected: ByteArray, hw: BgHardware, banks: List<Int>) {
        for (bank in banks) {
            for (i in bank * 16 until bank * 16 + 16) {
                // Colour 14 of bank 0x14 is 0x7C1F in the ROM palette; a later part of
                // WorldMap_Init rewrites it to 0x77FB before r04920.
                if (i == LATER_REWRITTEN_COLOR) continue
                assertEquals(expected.u16(2 * i), hw.palette[i].toInt() and 0xFFFF, "color $i")
            }
        }
    }

    @Test
    @VerifiesPort("SetupGraphicSystemsForWorldMap")
    fun `graphic systems setup matches BG registers and camera`() {
        assumeTrue(available(), "reference data / ROM not present")
        val run = ref.run("ram_trace_prologue")
        val f = fixture()
        f.setup.setupGraphicSystemsForWorldMap()
        val lcd = f.hw.lcd
        val expected = BgHardware().lcd
        expected.readFrom(run.region("r04920", "iwram"), 0x3080)
        for (bg in 0..3) {
            assertEquals(expected.bgCnt[bg] and 3, lcd.bgCnt[bg] and 3, "BG$bg priority")
        }
        assertEquals(0, f.bmSt.cameraX)
        assertEquals(0, f.bmSt.cameraY)
        for (bg in 0..3) {
            assertEquals(0, f.hw.tilemaps[bg].count { it.toInt() != 0 }, "BG$bg tilemap cleared")
        }
        assertEquals(0, lcd.dispcnt and 0x1F00, "all layers off after SetDispEnable")
    }

    @Test
    @VerifiesPort("SetupGmapNodeGfx", "sub_80B8E60")
    fun `sub_80B8E60 loads graphics and sets window and blend state`() {
        assumeTrue(available(), "reference data / ROM not present")
        val run = ref.run("ram_trace_prologue")
        val f = fixture()
        f.setup.setupGraphicSystemsForWorldMap()
        val proc = WorldMapMainProc()
        proc.unk3a = 7
        f.setup.sub80B8E60(proc)
        assertEquals(0, proc.unk3a, "unk_3a")
        val lcd = f.hw.lcd
        val expected = BgHardware().lcd
        expected.readFrom(run.region("r04920", "iwram"), 0x3080)
        assertEquals(expected.wincnt, lcd.wincnt, "wincnt")
        assertEquals(expected.bldcnt, lcd.bldcnt, "bldcnt")
        assertEquals(expected.blendCoeffA, lcd.blendCoeffA, "blend A")
        assertEquals(expected.blendCoeffB, lcd.blendCoeffB, "blend B")
        assertEquals(expected.blendY, lcd.blendY, "blend Y")
        assertEquals(expected.dispcnt and 0xE000, lcd.dispcnt and 0xE000, "dispcnt windows")
        val vram = run.region("f05100", "vram")
        // Node gfx (0x06011000, 0x06010280), misc UI chr (0x06015300) and path gfx (0x06005000).
        assertSame(vram, f.hw.vram, 0x11000 until 0x11000 + 0x1000, "node gfx")
        assertSame(vram, f.hw.vram, 0x10280 until 0x10280 + 0x180, "castle node row 0")
        assertSame(vram, f.hw.vram, 0x15300 until 0x15300 + 0x100, "misc UI chr row 0")
        assertSame(vram, f.hw.vram, 0x5000 until 0x5000 + 0x800, "path gfx")
        val pal = run.region("f05100", "pal")
        assertPalettes(pal, f.hw, listOf(0xE, 0x10, 0x11, 0x13, 0x14, 0x1B))
    }

    private companion object {
        const val LATER_REWRITTEN_COLOR = 0x14 * 16 + 14
    }
}

private class GfxSetupFixture(val hw: BgHardware, val setup: WorldMapGfxSetup, val bmSt: BmSt)
