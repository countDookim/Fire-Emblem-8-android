package fe8.game.bm

import fe8.game.RomView
import fe8.game.text.Fe8Text
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

// Not @VerifiesPort: these cases test transcribed branches against decomp (`src/mapanim_infobox.c`
// and `src/bmlib.c`), not a reference RAM capture; capture verification remains tracked under
// EP-21b.

/**
 * Edge-case tests for [MapAnimInfoBox]: the one-segment HP bar minimum in
 * [MapAnimInfoBox.sub807BB40] (`maxHp >= 45, curHp = 1`) and the overflow glyph in
 * [MapAnimInfoBox.sub8013168] (`num >= 100`).
 */
class MapAnimInfoBoxEdgeTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun newInfoBox(
        rom: RomView = RomView(emptyList()),
        manim: ManimSt = ManimSt(),
        hw: BgHardware? = null,
    ): MapAnimInfoBox {
        val empty = ByteArray(0)
        return MapAnimInfoBox(
            ProcEngine(),
            manim,
            rom,
            TextSystem(empty, NoTextHost, Fe8Text.CONFIG),
            MsgStrings(empty, MsgHuffman(empty, 0, 0), 0),
            hw,
        )
    }

    private fun readSegmentBases(rom: RomView): List<Int> {
        val bases = mutableListOf<Int>()
        var it = MapAnimInfoBox.HP_BAR_TABLE
        while (rom.u16(it) != 0) {
            bases += rom.u16(it + 2)
            it += 4
        }
        return bases
    }

    @Test
    fun `sub807BB40 draws one-segment minimum for curHp 1 at high maxHp and zero for curHp 0`() {
        assumeTrue(romFile.isFile, "baserom.gba not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val infoBox = newInfoBox(rom = rom)
        val bases = readSegmentBases(rom)
        val pal = rom.u32(MapAnimInfoBox.PAL_TABLE)
        val index = 4

        for (maxHp in listOf(45, 80)) {
            val minTilemap = ShortArray(16)
            infoBox.sub807BB40(minTilemap, index, maxHp, 1, 0, MapAnimInfoBox.HP_BAR_TABLE)
            assertEquals(
                ((bases[0] + 1) + (pal shl 12)).toShort(),
                minTilemap[index],
                "maxHp=$maxHp curHp=1 first segment",
            )
            for (k in 1 until bases.size) {
                assertEquals(
                    (bases[k] + (pal shl 12)).toShort(),
                    minTilemap[index + k],
                    "maxHp=$maxHp curHp=1 segment $k",
                )
            }

            val zeroTilemap = ShortArray(16)
            infoBox.sub807BB40(zeroTilemap, index, maxHp, 0, 0, MapAnimInfoBox.HP_BAR_TABLE)
            for (k in bases.indices) {
                assertEquals(
                    (bases[k] + (pal shl 12)).toShort(),
                    zeroTilemap[index + k],
                    "maxHp=$maxHp curHp=0 segment $k",
                )
            }
        }
    }

    @Test
    fun `sub807BA28 and sub8013168 write overflow glyph into two trailing tiles`() {
        val infoBox = newInfoBox()
        val base = 32 + (MapAnimInfoBox.BM_BGPAL_BANIM_UNK5 shl 12)
        val index = 10
        val len = 3
        val sentinel = 0x7FFF.toShort()

        for (num in listOf(100, 120)) {
            val tilemap = ShortArray(20) { sentinel }
            infoBox.sub807BA28(tilemap, index, num, base, len, 0, true)
            assertEquals(sentinel, tilemap[index - 3], "num=$num before cleared range")
            assertEquals(0.toShort(), tilemap[index - 2], "num=$num leading blank")
            assertEquals((base + 10).toShort(), tilemap[index - 1], "num=$num tens overflow")
            assertEquals((base + 10).toShort(), tilemap[index], "num=$num ones overflow")
            assertEquals(sentinel, tilemap[index + 1], "num=$num after target")

            val digits = num.toString().padStart(8, ' ').toCharArray()
            val direct = ShortArray(20) { sentinel }
            infoBox.sub8013168(direct, index, digits, digits.lastIndex, base, len, true)
            assertEquals(sentinel, direct[index - 3], "direct num=$num before cleared range")
            assertEquals(0.toShort(), direct[index - 2], "direct num=$num leading blank")
            assertEquals((base + 10).toShort(), direct[index - 1], "direct num=$num tens overflow")
            assertEquals((base + 10).toShort(), direct[index], "direct num=$num ones overflow")
            assertEquals(sentinel, direct[index + 1], "direct num=$num after target")
        }

        val normal = ShortArray(20) { sentinel }
        infoBox.sub807BA28(normal, index, 99, base, len, 0, false)
        assertEquals(0.toShort(), normal[index - 2], "num=99 leading blank")
        assertEquals((base + 9).toShort(), normal[index - 1], "num=99 tens digit")
        assertEquals((base + 9).toShort(), normal[index], "num=99 ones digit")
    }

    @Test
    fun `sub807BD54 handles overflow HP 120 and one-segment minimum HP 1 of 80 without throwing`() {
        assumeTrue(romFile.isFile, "baserom.gba not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val hw = BgHardware()
        val manim = ManimSt()
        val infoBox = newInfoBox(rom = rom, manim = manim, hw = hw)
        val bases = readSegmentBases(rom)
        val pal = rom.u32(MapAnimInfoBox.PAL_TABLE)
        val numBase = 32 + (MapAnimInfoBox.BM_BGPAL_BANIM_UNK5 shl 12)
        val proc = MAInfoFrameProc()

        manim.hpInfoX[0] = 4
        manim.hpInfoY[0] = 15
        val row = (manim.hpInfoY[0] + 3) * BgTilemap.ROW
        val x = manim.hpInfoX[0]

        manim.hpMax[0] = 120
        manim.hpDisplayedQ4[0] = 120 * 16
        infoBox.sub807BD54(proc, 0)
        assertEquals(0.toShort(), hw.tilemaps[0][row + x + 1])
        assertEquals((numBase + 10).toShort(), hw.tilemaps[0][row + x + 2])
        assertEquals((numBase + 10).toShort(), hw.tilemaps[0][row + x + 3])

        manim.hpMax[0] = 80
        manim.hpDisplayedQ4[0] = 1 * 16
        infoBox.sub807BD54(proc, 0)
        assertEquals(0.toShort(), hw.tilemaps[0][row + x + 1])
        assertEquals(0.toShort(), hw.tilemaps[0][row + x + 2])
        assertEquals((numBase + 1).toShort(), hw.tilemaps[0][row + x + 3])
        assertEquals(((bases[0] + 1) + (pal shl 12)).toShort(), hw.tilemaps[0][row + x + 4])
        assertEquals((bases[1] + (pal shl 12)).toShort(), hw.tilemaps[0][row + x + 5])
    }

    private object NoTextHost : TextHost {
        override fun language(): Int = 1
        override fun applyTextPalette(talk: Boolean, palid: Int) {}
        override fun readVram32(addr: Int): Int = 0
        override fun writeVram32(addr: Int, value: Int) {}
        override fun clearVram(dest: Int, byteCount: Int) {}
    }
}
