package fe8.game.bm

import fe8.game.RomView
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The frame of the map-animation level-up panel: the box graphics on BG1 and the class name and
 * stat labels on BG0.
 *
 * [vram] is the video memory image and [bg1TileBase] the byte offset in it of BG1's tile data
 * (`GetBackgroundTileDataOffset(1)`); [palette] is the 512-entry palette buffer.
 */
public class MapLevelUpFrame(
    private val rom: RomView,
    private val units: UnitTable,
    private val battles: Array<BattleUnit>,
    private val text: TextSystem,
    private val messages: MsgStrings,
    private val bg0: ShortArray,
    private val bg1: ShortArray,
    private val palette: ShortArray,
    private val vram: ByteArray,
    private val bg1TileBase: Int,
) {
    private val tmpText = Text()

    /**
     * Draws the panel frame for actor [actorId] with its origin at ([x], [y]): clears BG1, loads
     * the frame tiles at tile 0x200 of BG1 and the frame palette into bank 5, puts the frame
     * tilemap on BG1, then writes the class name and the centered stat labels on BG0.
     *
     * Verified against `ram_trace_prologue_levelup` (BG0, BG1, palette bank and tile data when
     * the panel first appears). The magic-label branch is not exercised: the captured unit has
     * no magic rank.
     *
     * Ported from src/mapanim_lvupfx.c: PutManimLevelUpFrame
     */
    public fun putManimLevelUpFrame(actorId: Int, x: Int, y: Int) {
        bg1.fill(0)

        val tiles = ByteArray(TILE_BUFFER_SIZE)
        val tileBytes = rom.decompressInto(IMG_FRAME, tiles, 0)
        tiles.copyInto(vram, bg1TileBase + 0x200 * CHR_SIZE, 0, tileBytes)

        val tsa = ByteArray(TILE_BUFFER_SIZE)
        rom.decompressInto(TSA_FRAME, tsa, 0)
        val tileref = 0x200 + (BGPAL_MANIM_INFO shl 12)
        for (i in 0 until FRAME_TILES) {
            val entry = (tsa[2 * i].toInt() and 0xFF) or ((tsa[2 * i + 1].toInt() and 0xFF) shl 8)
            bg1[i] = (entry + tileref).toShort()
        }

        for (i in 0 until PALETTE_COLORS) {
            palette[BGPAL_MANIM_INFO * PALETTE_COLORS + i] = rom.u16(PAL_FRAME + 2 * i).toShort()
        }

        val unit = units.unitOffset(battles[actorId].bytes[0x0B].toInt())
        val classData = StructCodec.u32(units.bytes, unit + 4)
        putString(
            (y * 32) + x + 2, COLOR_WHITE,
            messages.getStringFromIndex(rom.u16(classData)),
        )

        val magic = if (units.unitHasMagicRank(battles[actorId].bytes[0x0B].toInt())) 1 else 0
        var label = LABEL_INFO
        while (rom.u8(label) != 0xFF) {
            val msg = rom.u32(rom.u32(label + 4 + 4 * magic))
            text.putStringCentered(
                bg0, (y + rom.u8(label + 1)) * 32 + x + rom.u8(label), COLOR_GOLD, 3,
                messages.getStringFromIndex(msg),
            )
            label += 12
        }
    }

    // Private copy of src/bmlib.c PutString; checked only through the frame test.
    private fun putString(at: Int, color: Int, str: ByteArray) {
        text.initText(tmpText, (text.getStringTextLen(str) + 7) / 8)
        tmpText.colorId = color
        text.drawString(tmpText, str)
        text.putText(tmpText, bg0, at)
    }

    private companion object {
        const val LABEL_INFO = 0x089A3ED4
        const val IMG_FRAME = 0x088035B0
        const val TSA_FRAME = 0x088039E8
        const val PAL_FRAME = 0x08803B10
        const val BGPAL_MANIM_INFO = 5
        const val PALETTE_COLORS = 16
        const val CHR_SIZE = 32
        const val FRAME_TILES = 0x20 * 0x1C / 2
        const val TILE_BUFFER_SIZE = 0x2000
        const val COLOR_WHITE = 0
        const val COLOR_GOLD = 3
    }
}
