package fe8.game.worldmap

import fe8.game.RomView
import gbafe.render.ApHost
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteObject

/**
 * The `src/ap.c` host of the world map's sprite animations (node flag, cursor, nation border):
 * sprite objects from the ROM, the sine table, affine parameters in the OAM buffer and OBJ tile
 * transfers.
 *
 * `Register2dChrMove` copies tiles at once and only from a ROM source address; a source in RAM
 * (decompressed buffers) is not copied. Stand-in until D11-wm9 gets a real transfer registry
 * for the world map.
 *
 * @param rom the ROM (objects, sine table, tiles)
 * @param hw OBJ VRAM and the display mode
 * @param oam the OAM buffer that holds the affine parameters
 */
internal class WorldMapApHost(
    private val rom: RomView,
    private val hw: BgHardware,
    private val oam: OamBuffer,
) : ApHost {
    private val objects = HashMap<Int, SpriteObject>()

    override fun objectAt(addr: Int): SpriteObject = objects.getOrPut(addr) { rom.objN(addr) }

    override fun sin(a: Int): Int = rom.u16(SIN_LOOKUP + 2 * (a and 0xFF)).toShort().toInt()

    override fun cos(a: Int): Int =
        rom.u16(SIN_LOOKUP + 2 * (0x40 + (a and 0xFF))).toShort().toInt()

    override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {
        oam.setObjAffine(index, pa, pb, pc, pd)
    }

    override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {
        if (src < RomView.ROM_BASE) return
        val line = width * CHR_SIZE
        for (row in 0 until height) {
            val to = OBJ_VRAM0 + dstOffset + row * CHR_SIZE * 0x20
            if (to + line > hw.vram.size) return
            for (i in 0 until line) hw.vram[to + i] = rom.u8(src + row * line + i).toByte()
        }
    }

    override fun obj1dMap(): Boolean = hw.lcd.dispcnt and 0x40 != 0

    private companion object {
        const val SIN_LOOKUP = 0x080D751C
        const val CHR_SIZE = 0x20
        const val OBJ_VRAM0 = 0x10000
    }
}
