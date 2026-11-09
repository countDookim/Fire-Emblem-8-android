package fe8.testing

import fe8.game.RomView
import fe8.game.opanim.OpSubtitleSetup
import fe8.game.opanim.OpSubtitleSlides
import gba.core.VerifiesPort
import gba.data.Decompress
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Original subtitle initialization compared with the prologue capture around frame 1230. */
class OpSubtitleInitDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun u16(mem: ByteArray, at: Int) =
        (mem[at].toInt() and 0xFF) or ((mem[at + 1].toInt() and 0xFF) shl 8)

    @Test
    @VerifiesPort("OpSubtitle_Init")
    fun `init matches LCD buffer, BG2 map, palette and tiles`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        // Init is the first callback of the subtitle proc: it leaves index 0 and hold 60.
        val initFrame = (1200..1240).first {
            ProcArrayDump.ofWatch(trace, it).find(SCRIPT)?.let { p ->
                p.u8(0x29) == 0 && p.u16(0x2A) == 60
            } == true
        }
        assertEquals(1214, initFrame)

        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        hardware.vram.fill(0x5A)
        hardware.tilemaps.forEach { it.fill(0x5A5A.toShort()) }
        val state = OpSubtitleSlides.State(33, 1, 7)
        val bgm = mutableListOf<Pair<Int, Int>>()
        OpSubtitleSetup(rom, hardware) { song, player -> bgm += song to player }.init(state)
        assertEquals(0, state.index)
        assertEquals(60, state.holdTimer)
        assertEquals(listOf(3 to 0), bgm)

        // Only the fields nothing between Init and frame 1230 changes: the fade may enable
        // BG1..BG3 (DISPCNT 0x0E00); WINCNT is leftover state SetupBackgrounds does not reset.
        val iwram = run.region("r01230", "iwram")
        val lcd = hardware.lcd
        assertEquals(u16(iwram, LCD) and 0xF1FF, lcd.dispcnt and 0xF1FF, "dispcnt")
        for (bg in 0..3) {
            assertEquals(u16(iwram, LCD + 0x0C + 4 * bg), lcd.bgCnt[bg], "bg${bg}cnt")
            assertEquals(u16(iwram, LCD + 0x1C + 4 * bg), lcd.bgHofs[bg], "bg${bg} hofs")
            assertEquals(u16(iwram, LCD + 0x1E + 4 * bg), lcd.bgVofs[bg], "bg${bg} vofs")
        }
        assertEquals(u16(iwram, LCD + 0x3C), lcd.bldcnt, "bldcnt")
        assertEquals(iwram[LCD + 0x44].toInt() and 0xFF, lcd.blendCoeffA, "eva")
        assertEquals(iwram[LCD + 0x45].toInt() and 0xFF, lcd.blendCoeffB, "evb")
        assertEquals(iwram[LCD + 0x46].toInt() and 0xFF, lcd.blendY, "evy")

        val ewram = run.region("r01230", "ewram")
        val map = ShortArray(0x400) { i -> u16(ewram, BG2_MAP + 2 * i).toShort() }
        assertArrayEquals(map, hardware.tilemaps[2], "BG2 tilemap buffer")
        // The fade-in scales the palette until it completes; compare once it has finished.
        val settled = run.region("r01440", "ewram")
        val palette = ShortArray(48) { i -> u16(settled, PALETTE + 2 * i).toShort() }
        assertArrayEquals(palette, hardware.palette.copyOfRange(0, 48), "palette banks 0..2")
        assertEquals(true, hardware.modifiedPalette)
        assertEquals(4, hardware.modifiedBgs and 4)

        // Tile data at the BG2 character base, from a full dump where it is still intact.
        val vram = run.region("f01500", "vram")
        val image = rom.blob(LENS_FLARE_IMG)
        val length = Decompress.decode(image.data, image.offset).size
        val base = 0x8000
        assertArrayEquals(
            vram.copyOfRange(base, base + length),
            hardware.vram.copyOfRange(base, base + length),
            "lens flare tiles",
        )
    }

    @Test
    @VerifiesPort("OpSubtitle_SetupBackgrounds", "sub_80C488C", "sub_80C48F0")
    fun `scene setup matches BG3 data after sub_80C5218`() {
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        // sub_80C5218 ran at frame 2424, so BG3 is set up in the frame 2700 dump.
        val proc = checkNotNull(
            ProcArrayDump.ofWatch(run.watch("procs"), 2700).find(SCRIPT)
        )
        assertEquals(3, proc.u8(0x29))
        val iwram = run.region("f02700", "iwram")
        val vram = run.region("f02700", "vram")
        val pal = run.region("f02700", "pal")

        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        hardware.vram.fill(0x5A)
        hardware.tilemaps.forEach { it.fill(0x5A5A.toShort()) }
        hardware.lcd.bgCnt.fill(0xFFFF)
        hardware.lcd.bgHofs.fill(9)
        hardware.lcd.bgVofs.fill(9)
        val setup = OpSubtitleSetup(rom, hardware)
        setup.setupBackgrounds()
        // BG1's scroll is changed afterwards (-3), so compare control and the other scrolls.
        for (bg in 0..3) {
            assertEquals(
                u16(iwram, LCD + 0x0C + 4 * bg) and 0xFFFC, hardware.lcd.bgCnt[bg] and 0xFFFC,
                "bg${bg}cnt without priority",
            )
            if (bg == 1) continue
            assertEquals(u16(iwram, LCD + 0x1C + 4 * bg), hardware.lcd.bgHofs[bg], "bg$bg hofs")
            assertEquals(u16(iwram, LCD + 0x1E + 4 * bg), hardware.lcd.bgVofs[bg], "bg$bg vofs")
        }

        val base = hardware.tileDataOffset(3)
        setup.loadCommonScreen(3)
        val image = rom.blob(COMM_GAME_BG_SCREEN)
        val length = Decompress.decode(image.data, image.offset).size
        assertArrayEquals(
            vram.copyOfRange(base, base + 0x5000.coerceAtMost(length)),
            hardware.vram.copyOfRange(base, base + 0x5000.coerceAtMost(length)),
            "common screen tiles",
        )
        assertArrayEquals(
            vram.copyOfRange(base + 0x5000, base + 0x5020),
            hardware.vram.copyOfRange(base + 0x5000, base + 0x5020),
            "cleared tile",
        )
        assertEquals(8, hardware.modifiedBgs and 8)
        assertEquals(true, hardware.modifiedPalette)
        assertArrayEquals(
            pal.copyOfRange(0xE * 32, 0xF * 32),
            ByteArray(32) { i ->
                (hardware.palette[0xE * 16 + i / 2].toInt() shr (8 * (i and 1))).toByte()
            },
            "palette bank 0xE",
        )

        setup.fillIndexedMap(3)
        val mapBase = 0x7800
        assertArrayEquals(
            vram.copyOfRange(mapBase, mapBase + 0x500),
            ByteArray(0x500) { i ->
                (hardware.tilemaps[3][i / 2].toInt() shr (8 * (i and 1))).toByte()
            },
            "BG3 map",
        )
    }

    private companion object {
        const val SCRIPT = 0x08AA21BC
        const val LCD = 0x3080
        const val BG2_MAP = 0x23CA8
        const val PALETTE = 0x228A8
        const val LENS_FLARE_IMG = 0x08B17B64
        const val COMM_GAME_BG_SCREEN = 0x08B12DB4
    }
}
