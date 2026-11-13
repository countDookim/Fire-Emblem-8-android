package fe8.game.bm

import fe8.game.RomView
import gbafe.render.ApHandle
import gbafe.render.ApHost
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The sprite animation side of the MU system: one [SpriteAnimPlayer] (`ap.c`) over the ROM, the
 * MU sheet buffers (`gMUGfxBuffer`) and the sprite layers, as the [MuHooks] sprite animation
 * hooks need it. A hook's `anim` value is the handle index + 1 (0 = none).
 */
internal class MuSpriteAnim(
    private val rom: RomView,
    private val hw: BgHardware,
    private val oam: OamBuffer,
    layers: SpriteLayers,
) : ApHost {
    /** The `ap.c` player; its handle pool holds the MUs' `sprite_anim` handles. */
    private val player = SpriteAnimPlayer({ rom.u16(it) }, layers, this)
    // Primitive keys avoid boxing on each lookup; the arrays grow only for new compositions.
    private var objectAddresses = IntArray(16)
    private var objects = arrayOfNulls<SpriteObject>(16)
    private var objectCount = 0

    /** `gMUGfxBuffer`: one decompressed sheet per slot. */
    private val gfxBuffer = ByteArray(MU_GFX_MAX_SIZE * Mu.MU_MAX_COUNT)

    // MU-only subset of the original 32-entry transfer registry; other producers are not queued
    // here. RegisterDataMove stores source pointers, so resolve sheet bytes only at VBlank.
    private val moveSources = IntArray(32)
    private val moveDestinations = IntArray(32)
    private val moveSizes = IntArray(32)
    private var moveCount = 0

    /** Flushes the queued MU tile rows in registration order at VBlank. */
    fun flushTileMoves() {
        for (i in 0 until moveCount) {
            gfxBuffer.copyInto(
                hw.vram, moveDestinations[i], moveSources[i], moveSources[i] + moveSizes[i],
            )
        }
        moveCount = 0
    }

    init {
        player.clearAll()
    }

    /**
     * The sprite animation part of `StartMu` (src/mu.c): `AP_Create` for the class's MU animation,
     * `AP_SwitchAnimation(ap, MU_FACING_SELECTED)`, decompressing the MU sheet into the slot's
     * `gMUGfxBuffer` area and setting `pGraphics` and `tileBase` (palette, `chr` and layer).
     * Returns the hook's `anim` value (handle index + 1), or 0 if the handle pool is full.
     */
    fun create(mu: Mu, proc: MuProc, config: MuConfig): Int {
        val ap = player.create(mu.getMuAnimForJid(proc.jid), 10) ?: return 0
        player.switchAnimation(ap, MU_FACING_SELECTED)
        val off = BUF_OFF_LUT[config.slot] * MU_GFX_MAX_SIZE
        rom.decompressInto(mu.getMuImg(proc), gfxBuffer, off)
        ap.pGraphics = GFX_BASE + off
        ap.tileBase = ((config.pal and 0xF) shl 12) + config.chr + proc.layer
        return player.handles.indexOf(ap) + 1
    }

    /** The handle for a hook's [anim] value (index + 1), or null for 0 or out of range. */
    fun handle(anim: Int): ApHandle? =
        if (anim in 1..player.handles.size) player.handles[anim - 1] else null

    /** `AP_SwitchAnimation` on the handle of [anim]. */
    fun switchAnimation(anim: Int, facing: Int) = player.switchAnimation(handle(anim), facing)

    /** `AP_Update(ap, x, y)`: advances the animation and puts its sprites at ([x], [y]). */
    fun update(anim: Int, x: Int, y: Int) {
        player.update(handle(anim), x, y)
    }

    /**
     * `ResetSpriteAnimClock` hook for [anim]: `frameTimer = 0`, `frameInterval = 0x100`.
     */
    fun resetClock(anim: Int) {
        val h = handle(anim) ?: return
        h.frameTimer = 0
        h.frameInterval = 0x100
    }

    /** `ap->objLayer` of [anim] (0 when it has no handle). */
    fun objLayer(anim: Int): Int = handle(anim)?.objLayer ?: 0

    /** `AP_Delete` on the handle of [anim]. */
    fun delete(anim: Int) = player.delete(handle(anim))

    /**
     * The sprite object at [addr], parsed from the ROM on first use and cached for the rest of
     * the boot. That first use allocates (48 B per object): a MU's sprite frame shown for the
     * first time. It is one-time per boot and frame, not per walk, so it is accepted.
     */
    override fun objectAt(addr: Int): SpriteObject {
        for (i in 0 until objectCount) {
            if (objectAddresses[i] == addr) return objects[i]!!
        }
        if (objectCount == objects.size) {
            objectAddresses = objectAddresses.copyOf(objectCount * 2)
            objects = objects.copyOf(objectCount * 2)
        }
        val obj = rom.objN(addr)
        objectAddresses[objectCount] = addr
        objects[objectCount++] = obj
        return obj
    }

    /** `SIN(a)`: `gSinLookup[a & 0xFF]` read from the ROM as a signed halfword. */
    override fun sin(a: Int): Int = rom.u16(SIN_LOOKUP + 2 * (a and 0xFF)).toShort().toInt()

    /** `COS(a)`: `gSinLookup[0x40 + (a & 0xFF)]`, a quarter turn after [sin]. */
    override fun cos(a: Int): Int =
        rom.u16(SIN_LOOKUP + 2 * (0x40 + (a and 0xFF))).toShort().toInt()

    override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {
        oam.setObjAffine(index, pa, pb, pc, pd)
    }

    /** `Register2dChrMove`: rows of [width] tiles from the sheet, 0x20 tiles apart in VRAM. */
    override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {
        val line = width * CHR_SIZE
        check(moveCount + height <= moveSources.size) { "MU tile transfer queue overflow" }
        for (row in 0 until height) {
            val to = OBJ_VRAM0 + dstOffset + row * CHR_SIZE * 0x20
            check(to >= OBJ_VRAM0 && to + line <= hw.vram.size) {
                "MU tile destination out of VRAM"
            }
            moveSources[moveCount] = src - GFX_BASE + row * line
            moveDestinations[moveCount] = to
            moveSizes[moveCount] = line
            moveCount++
        }
    }

    override fun obj1dMap(): Boolean = hw.lcd.dispcnt and 0x40 != 0

    private companion object {
        /** `MU_GFX_MAX_SIZE`: bytes of one decompressed MU sheet. */
        const val MU_GFX_MAX_SIZE = 0x2200

        /** Fake GBA address `pGraphics` points at; [gfxBuffer] offsets are added to it. */
        const val GFX_BASE = 0x02000000

        /** ROM address of `gSinLookup`. */
        const val SIN_LOOKUP = 0x080D751C

        /** Bytes per 4bpp tile (`CHR_SIZE`). */
        const val CHR_SIZE = 0x20

        /** Byte offset of `OBJ_VRAM0` in VRAM. */
        const val OBJ_VRAM0 = 0x10000

        /** `MU_FACING_SELECTED`: the facing the MU animation starts in. */
        const val MU_FACING_SELECTED = 4

        /** `sMuImgBufOffLut`: sheet area (in [MU_GFX_MAX_SIZE] units) of each MU slot (1-based). */
        val BUF_OFF_LUT = intArrayOf(0, 0, 2, 1, 3)
    }
}
