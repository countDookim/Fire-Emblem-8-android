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
 * `Register2dChrMove` copies tiles at once (the original queues the transfer for VBlank), from a
 * ROM address or from [muGfx], the decompressed map sprite sheets (`GetMapUnitMMSGfxBuffer`).
 *
 * @param rom the ROM (objects, sine table, tiles)
 * @param hw OBJ VRAM and the display mode
 * @param oam the OAM buffer that holds the affine parameters
 */
public class WorldMapApHost(
    private val rom: RomView,
    private val hw: BgHardware,
    private val oam: OamBuffer,
) : ApHost {
    /** The seven decompressed map sprite sheets at `gUnknown_0200AF00` (0x2200 bytes each). */
    public val muGfx: ByteArray = ByteArray(MU_GFX_SLOTS * MU_GFX_SIZE)

    private val objects = HashMap<Int, SpriteObject>()

    /** The sprite object at ROM address [addr], parsed once and cached. */
    override fun objectAt(addr: Int): SpriteObject = objects.getOrPut(addr) { rom.objN(addr) }

    /** `gSinLookup[a & 0xFF]` (signed 4.12). */
    override fun sin(a: Int): Int = rom.u16(SIN_LOOKUP + 2 * (a and 0xFF)).toShort().toInt()

    /** `gCosLookup[a & 0xFF]`: the sine table read a quarter turn (0x40 entries) ahead. */
    override fun cos(a: Int): Int =
        rom.u16(SIN_LOOKUP + 2 * (0x40 + (a and 0xFF))).toShort().toInt()

    /** `SetObjAffine`: writes the affine parameters of OAM matrix [index]. */
    override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {
        oam.setObjAffine(index, pa, pb, pc, pd)
    }

    /**
     * `Register2dChrMove`: copies a [width] x [height] tile block from [src] (ROM or `muGfx`) to
     * OBJ VRAM at [dstOffset].
     */
    override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {
        val line = width * CHR_SIZE
        val inRom = src >= RomView.ROM_BASE
        if (!inRom && (src < MU_GFX_BASE || src >= MU_GFX_BASE + muGfx.size)) return
        for (row in 0 until height) {
            val to = OBJ_VRAM0 + dstOffset + row * CHR_SIZE * 0x20
            if (to + line > hw.vram.size) return
            if (inRom) {
                for (i in 0 until line) hw.vram[to + i] = rom.u8(src + row * line + i).toByte()
            } else {
                val from = src - MU_GFX_BASE + row * line
                if (from + line > muGfx.size) return
                muGfx.copyInto(hw.vram, to, from, from + line)
            }
        }
    }

    /** Whether OBJ tiles use 1D mapping (DISPCNT bit 6). */
    override fun obj1dMap(): Boolean = hw.lcd.dispcnt and 0x40 != 0

    private companion object {
        const val SIN_LOOKUP = 0x080D751C
        const val CHR_SIZE = 0x20
        const val OBJ_VRAM0 = 0x10000
        const val MU_GFX_BASE = 0x0200AF00
        const val MU_GFX_SLOTS = 7
        const val MU_GFX_SIZE = 0x2200
    }
}
