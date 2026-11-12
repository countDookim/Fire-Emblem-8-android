package fe8.game.worldmap

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitSprites
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The map sprite (SMS) callees of `src/worldmap_gmapunit.c` that live in `src/bmudisp.c` and
 * `src/bmunit.c`: the class sprite lookup, the loading of a unit's standing sprite, its facing
 * copy and its OAM entry. The graphics slots are the battle map's [UnitSprites].
 *
 * Wire [classSmsId] / [startWorldMapSms] into `WorldMapUnit.Hooks` as lambdas (they run when a
 * sprite is made) and [setStandingMuFacing] / [smsDisplayOne] as they are (they run every frame
 * and do not allocate).
 *
 * @param rom the ROM (class data, sprite table, objects)
 * @param sprites the layers the sprite entries go to
 * @param unitSprites the map sprite graphics slots
 */
public class WorldMapSms(
    private val rom: RomView,
    private val sprites: SpriteLayers,
    private val unitSprites: UnitSprites,
) {
    /** `gObject_16x16`, `gObject_16x32`, `gObject_32x32` (include/ctc.h): the sprite shapes. */
    private val object16x16: SpriteObject = rom.objN(OBJECT_16X16)
    private val object16x32: SpriteObject = rom.objN(OBJECT_16X32)
    private val object32x32: SpriteObject = rom.objN(OBJECT_32X32)

    /**
     * Ported from src/bmunit.c: GetClassSMSId
     *
     * The map sprite id of class [classId] (`gClassData[classId - 1].SMSId`).
     */
    public fun classSmsId(classId: Int): Int =
        rom.u8(UnitLoader.CLASS_DATA + (classId - 1) * UnitLoader.CLASS_DATA_SIZE + SMS_ID_OFFSET)

    /**
     * `StartWorldMapSMS(smsId, frameId, slot)` (src/bmudisp.c:410,
     * [UnitSprites.startWorldMapSms]):
     * unpacks map sprite sheet [smsId] into graphics slot [slot] and records where it landed for
     * [frameId] (`gUnitSpriteSlots[frameId]`), so a world map unit gets its map sprite.
     */
    public fun startWorldMapSms(smsId: Int, frameId: Int, slot: Int) {
        unitSprites.startWorldMapSms(smsId, frameId, slot)
    }

    /**
     * `SetStandingMuFacingWM(frameId, dst)` (src/bmudisp.c:757,
     * [UnitSprites.setStandingMuFacingWm]): copies the standing animation frame picked by the
     * game clock modulo 72 into the unit's tiles at VRAM address [dst], four rows of 4 tiles
     * starting at tile offset [frameId].
     */
    public val setStandingMuFacing: SetStandingMuFacing = SetStandingMuFacing { frameId, dst ->
        unitSprites.setStandingMuFacingWm(frameId, dst - VRAM_BASE)
    }

    /**
     * Ported from src/bmudisp.c: SMS_DisplayOne
     *
     * Puts the standing sprite of class `classId` on `layer` at (`x`, `y`) unless it is off
     * screen; `blend` adds the semi-transparency bit. A 16x16 sprite reads its tiles 0x40
     * further on, in the lower rows of its column.
     */
    public val smsDisplayOne: SmsDisplayOne =
        SmsDisplayOne { classId, layer, x, y, oam2, blend ->
            displayOne(classId, layer, x, y, oam2, blend)
        }

    /** The body of [smsDisplayOne]: picks the object and offsets by the class sprite's size. */
    private fun displayOne(classId: Int, layer: Int, x: Int, y: Int, oam2: Int, blend: Boolean) {
        if (x < -16 || x > DISPLAY_WIDTH + 16) return
        if (y < -32 || y > DISPLAY_HEIGHT + 32) return
        val flag = if (blend) OAM0_BLEND else 0
        when (unitSprites.iconSize(classSmsId(classId))) {
            SIZE_16X16 -> sprites.putExt(
                layer, (x - 8) and 0x1FF, ((y - 16) and 0xFF) or flag, object16x16, oam2 + 0x40,
            )
            SIZE_16X32 -> sprites.putExt(
                layer, (x - 8) and 0x1FF, ((y - 32) and 0xFF) or flag, object16x32, oam2,
            )
            SIZE_32X32 -> sprites.putExt(
                layer, (x - 16) and 0x1FF, ((y - 32) and 0xFF) or flag, object32x32, oam2,
            )
        }
    }

    private companion object {
        /** ROM addresses of the three `gObject_*` shapes (not listed in the decomp). */
        const val OBJECT_16X16 = 0x08590F4C
        const val OBJECT_32X32 = 0x08590F54
        const val OBJECT_16X32 = 0x08590F6C

        /** Offset of `SMSId` in `struct ClassData` (include/bmunit.h:67). */
        const val SMS_ID_OFFSET = 6

        /** GBA address of VRAM. */
        const val VRAM_BASE = 0x06000000

        /** `DISPLAY_WIDTH`, `DISPLAY_HEIGHT`. */
        const val DISPLAY_WIDTH = 240
        const val DISPLAY_HEIGHT = 160

        /** `OAM0_BLEND`: attr0 mode bits for semi-transparency. */
        const val OAM0_BLEND = 0x0400

        /** `UNIT_ICON_SIZE_16x16`, `_16x32`, `_32x32` (include/unit_icon_data.h). */
        const val SIZE_16X16 = 0
        const val SIZE_16X32 = 1
        const val SIZE_32X32 = 2
    }
}
