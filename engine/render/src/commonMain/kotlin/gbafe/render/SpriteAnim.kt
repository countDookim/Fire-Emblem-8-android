package gbafe.render

/** Read access to the halfwords of ROM animation data (`const u16 *` in `src/ap.c`). */
public fun interface ApRom {
    /** The unsigned halfword at address [addr]. */
    public fun u16(addr: Int): Int
}

/** What `src/ap.c` calls outside itself; pointers are plain Int addresses (0 = NULL). */
public interface ApHost {
    /**
     * The sprite object (`1 + 3 * count` halfwords) at [addr], cached by the host so that calling
     * this every frame does not allocate. Stands for the `const u16 *` that `PutSpriteExt` takes.
     */
    public fun objectAt(addr: Int): SpriteObject

    /** `SIN(a)` (`include/hardware.h`): [a] masked to 0..255 (256 = one turn); 12-bit fixed. */
    public fun sin(a: Int): Int

    /** `COS(a)` (`include/hardware.h`): same angle unit as [sin]; 12-bit fixed point. */
    public fun cos(a: Int): Int

    /** `SetObjAffine(index, pa, pb, pc, pd)` (`src/hardware.c`). */
    public fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int)

    /**
     * `Register2dChrMove(src, OBJ_VRAM0 + dstOffset, width, height)`: queues a copy of [width] x
     * [height] 4bpp tiles from the graphics at [src] to the OBJ VRAM byte offset [dstOffset] (the
     * original does it during VBlank).
     */
    public fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int)

    /** `gLCDControlBuffer.dispcnt.obj1dMap`. */
    public fun obj1dMap(): Boolean
}

/**
 * One `struct APHandle` of `include/ap.h`. ROM pointers are Int addresses, 0 meaning NULL.
 * Instances live in the fixed pool of [SpriteAnimPlayer].
 */
public class ApHandle {
    /** `pDefinition`: the animation definition root in ROM (null marks a free slot). */
    public var pDefinition: Int = 0

    /** `pFrameData`: the frame (object composition) data array of the definition. */
    public var pFrameData: Int = 0

    /** `pAnimDataStart`: start of the current animation's data (where a loop returns to). */
    public var pAnimDataStart: Int = 0

    /** `pAnimDataCurrent`: cursor in the animation data: (duration, frame offset) pairs. */
    public var pAnimDataCurrent: Int = 0

    /** `pCurrentObjData`: object data of the frame being shown (count, then 3 halfwords each). */
    public var pCurrentObjData: Int = 0

    /** `pCurrentRotScale`: the frame's rotation/scaling entries (null if none). */
    public var pCurrentRotScale: Int = 0

    /** `frameTimer` (s16): game frames left on the current animation frame. */
    public var frameTimer: Int = 0

    /** `frameInterval` (u16, 0x100 is one frame). */
    public var frameInterval: Int = 0

    /** `subframeTimer` (u16): fractional frames carried over (8-bit fraction) between steps. */
    public var subframeTimer: Int = 0

    /** `objLayer`: the [SpriteLayers] layer the sprites go in (the decomp's "HiObj layer"). */
    public var objLayer: Int = 0

    /** `gfxNeedsUpdate`: the current frame's tiles must be copied to OBJ VRAM. */
    public var gfxNeedsUpdate: Boolean = false

    /** `rotScaleIndex`: first OAM affine matrix the frame's rotation/scaling entries use. */
    public var rotScaleIndex: Int = 0

    /** `tileBase`: the `oam2` added to every entry (tile number base, priority, palette bank). */
    public var tileBase: Int = 0

    /** `pGraphics` (address understood by [ApHost.register2dChrMove]; 0 = none). */
    public var pGraphics: Int = 0
}

/**
 * The sprite animation player of `src/ap.c`: a pool of [AP_MAX_COUNT] [ApHandle]s whose frame
 * data is read from ROM through [rom]; sprites are drawn into [layers].
 */
public class SpriteAnimPlayer(
    private val rom: ApRom,
    private val layers: SpriteLayers,
    private val host: ApHost,
) {
    /** `sAPArray`. */
    public val handles: Array<ApHandle> = Array(AP_MAX_COUNT) { ApHandle() }

    private fun u16(addr: Int): Int = rom.u16(addr)

    private fun usable(handle: ApHandle?): Boolean = handle != null && handle.pDefinition != 0

    /**
     * Frees every handle.
     *
     * Transcribed from src/ap.c: AP_ClearAll (not verified: no @VerifiesPort test names it)
     */
    public fun clearAll() {
        for (handle in handles) handle.pDefinition = 0
    }

    /**
     * Allocates a handle for [definition] on OBJ layer [objLayer] (null when the pool is full).
     *
     * Transcribed from src/ap.c: AP_Create (not verified: no @VerifiesPort test names it)
     */
    public fun create(definition: Int, objLayer: Int): ApHandle? {
        val result = find(0) ?: return null
        init(result, definition, objLayer)
        return result
    }

    /**
     * Frees [handle].
     *
     * Transcribed from src/ap.c: AP_Delete (not verified: no @VerifiesPort test names it)
     */
    public fun delete(handle: ApHandle?) {
        if (handle != null && handle.pDefinition != 0) handle.pDefinition = 0
    }

    /**
     * Draws [handle] at ([x], [y]) then advances it one step; false when the animation ended or
     * the handle is free.
     *
     * Transcribed from src/ap.c: AP_Update (not verified: no @VerifiesPort test names it)
     */
    public fun update(handle: ApHandle?, x: Int, y: Int): Boolean {
        if (!usable(handle)) return false
        display(handle, x, y)
        return execFrame(handle)
    }

    /**
     * Puts the current frame of [handle] into the sprite layers (and queues its rotscale and
     * graphics).
     *
     * Transcribed from src/ap.c: AP_Display (not verified: no @VerifiesPort test names it)
     */
    public fun display(handle: ApHandle?, x: Int, y: Int) {
        if (handle == null || handle.pDefinition == 0) return
        var rotScaleMask = 0
        if (handle.pCurrentRotScale != 0) {
            queueObjRotScale(handle)
            rotScaleMask = handle.rotScaleIndex shl 9
        }
        layers.putExt(
            handle.objLayer,
            x or rotScaleMask,
            y,
            host.objectAt(handle.pCurrentObjData),
            handle.tileBase,
        )
        if (handle.pGraphics != 0 && handle.gfxNeedsUpdate) queueObjGraphics(handle)
    }

    /**
     * Advances [handle] by one game frame; false when the animation ended (or the handle is free).
     *
     * Transcribed from src/ap.c: AP_ExecFrame (not verified: no @VerifiesPort test names it)
     */
    public fun execFrame(handle: ApHandle?): Boolean {
        if (handle == null) return false
        while (true) {
            if (handle.pDefinition == 0) return false
            if (handle.frameInterval == 0) return true

            if (handle.frameTimer != 0) {
                handle.frameTimer = (handle.frameTimer - 1).toShort().toInt()
                if (handle.frameTimer > 0) return true
                handle.frameTimer = 0
                handle.pAnimDataCurrent += 4
            }

            if (u16(handle.pAnimDataCurrent) == 0) {
                when (u16(handle.pAnimDataCurrent + 2)) {
                    0xFFFF -> {
                        handle.pAnimDataCurrent = handle.pAnimDataStart
                        continue
                    }
                    1 -> {
                        delete(handle)
                        return false
                    }
                    0 -> return false
                }
            }

            val tmp = u16(handle.pAnimDataCurrent) * handle.frameInterval
            handle.subframeTimer = (handle.subframeTimer + tmp) and 0xFFFF

            if (handle.subframeTimer < 0x100) {
                handle.frameTimer = 1
                continue
            }

            handle.frameTimer = handle.subframeTimer shr 8
            handle.subframeTimer = handle.subframeTimer and 0xFF

            handle.pCurrentObjData =
                handle.pFrameData + 2 * (u16(handle.pFrameData + 2 * u16(handle.pAnimDataCurrent + 2)) / 2)

            if (u16(handle.pCurrentObjData) and 0x8000 != 0) {
                handle.pCurrentRotScale = handle.pCurrentObjData
                handle.pCurrentObjData += 2 * ((u16(handle.pCurrentObjData) and 0x7FFF) * 3 + 1)
            } else {
                handle.pCurrentRotScale = 0
            }

            handle.gfxNeedsUpdate = true
            return true
        }
    }

    /**
     * Sends the rotscale entries of the current frame to the OBJ affine matrices.
     *
     * Transcribed from src/ap.c: AP_QueueObjRotScale (not verified: Div of 0 gives 0 here; the BIOS
     * call is not modelled)
     */
    public fun queueObjRotScale(handle: ApHandle?) {
        if (!usable(handle)) return
        handle!!
        if (handle.pCurrentRotScale == 0) return
        val count = u16(handle.pCurrentRotScale) and 0x7FFF
        var it = handle.pCurrentRotScale + 2
        for (i in 0 until count) {
            val angle = u16(it)
            val scaleX = u16(it + 2).toShort().toInt()
            val scaleY = u16(it + 4).toShort().toInt()
            host.setObjAffine(
                handle.rotScaleIndex + i,
                div(host.cos(angle) * 16, scaleX),
                div(-host.sin(angle) * 16, scaleY),
                div(host.sin(angle) * 16, scaleX),
                div(host.cos(angle) * 16, scaleY),
            )
            it += 6
        }
    }

    private fun div(a: Int, b: Int): Int = if (b == 0) 0 else a / b

    /**
     * Selects animation [index] of the definition and shows its first frame.
     *
     * Transcribed from src/ap.c: AP_SwitchAnimation (not verified: no @VerifiesPort test names it)
     */
    public fun switchAnimation(handle: ApHandle?, index: Int) {
        if (handle == null || handle.pDefinition == 0) return
        val animDataList = handle.pDefinition + 2 * (u16(handle.pDefinition + 2) / 2)
        handle.pAnimDataStart = animDataList + 2 * (u16(animDataList + 2 * index) / 2)
        handle.pAnimDataCurrent = handle.pAnimDataStart
        execDummyFrame(handle)
    }

    /**
     * Loads [definition] into a live handle and shows its first frame.
     *
     * Transcribed from src/ap.c: AP_SetDefinition (not verified: no @VerifiesPort test names it)
     */
    public fun setDefinition(handle: ApHandle?, definition: Int) {
        if (handle == null || handle.pDefinition == 0) return
        loadDefinition(handle, definition)
        execDummyFrame(handle)
    }

    /**
     * Copies the tiles of the current frame's objects to OBJ VRAM at `tileBase`.
     *
     * Transcribed from src/ap.c: AP_QueueObjGraphics (not verified: no @VerifiesPort test names it)
     */
    public fun queueObjGraphics(handle: ApHandle?) {
        if (!usable(handle)) return
        handle!!
        var remaining = u16(handle.pCurrentObjData)
        var itObjData = handle.pCurrentObjData + 2
        var itGfxData = handle.pCurrentObjData + 2 + remaining * 6
        var tileOffset = 0
        while (remaining-- > 0) {
            val idx = objSizeIndex(itObjData)
            val widthInTiles = SIZE_LUT[idx]
            val heightInTiles = SIZE_LUT[idx + 1]
            host.register2dChrMove(
                handle.pGraphics + (u16(itGfxData) and 0x3FF) * 0x20,
                (handle.tileBase and 0x3FF) * 0x20 + tileOffset,
                widthInTiles,
                heightInTiles,
            )
            tileOffset += if (!host.obj1dMap()) widthInTiles * 0x20 else ((widthInTiles * widthInTiles) and 0x3FF) * 0x20
            itObjData += 6
            itGfxData += 2
        }
        handle.gfxNeedsUpdate = false
    }

    /** `OBJ_SIZE_TABLE_INDEX(itObjData)`: [SIZE_LUT] index for the entry at [it] (shape, size). */
    private fun objSizeIndex(it: Int): Int =
        (((u16(it) and 0xC000) shr 12) + ((u16(it + 2) and 0xC000) shr 14)) * 2

    /**
     * Points [handle] at [definition] and its default animation.
     *
     * Transcribed from src/ap.c: AP_LoadDefinition (not verified: no @VerifiesPort test names it)
     */
    public fun loadDefinition(handle: ApHandle, definition: Int) {
        handle.pDefinition = definition
        handle.pFrameData = definition + 2 * (u16(definition) / 2)
        val animList = definition + 2 * (u16(definition + 2) / 2)
        handle.pAnimDataStart = animList + 2 * (u16(animList) / 2)
        handle.pAnimDataCurrent = handle.pAnimDataStart
    }

    /**
     * Runs one frame at full speed, keeping the frame interval.
     *
     * Transcribed from src/ap.c: AP_ExecDummyFrame (not verified: no @VerifiesPort test names it)
     */
    public fun execDummyFrame(handle: ApHandle) {
        handle.frameTimer = 0
        val tmp = handle.frameInterval
        handle.frameInterval = 0x100
        execFrame(handle)
        handle.frameInterval = tmp
    }

    /**
     * Sets up [handle] for [definition] on layer [objLayer] and runs its first frame.
     *
     * Transcribed from src/ap.c: AP_Init (not verified: no @VerifiesPort test names it)
     */
    public fun init(handle: ApHandle, definition: Int, objLayer: Int) {
        loadDefinition(handle, definition)
        handle.pGraphics = 0
        handle.tileBase = 0
        handle.frameTimer = 0
        handle.objLayer = objLayer and 0xFFFF
        handle.pCurrentRotScale = 0
        handle.rotScaleIndex = 0
        handle.frameInterval = 0x100
        handle.subframeTimer = 0
        execFrame(handle)
    }

    /**
     * The first handle whose definition is [definition] (0 finds a free one); null if none.
     *
     * Transcribed from src/ap.c: AP_Find (not verified: no @VerifiesPort test names it)
     */
    public fun find(definition: Int): ApHandle? {
        for (handle in handles) if (handle.pDefinition == definition) return handle
        return null
    }

    /** Pool size and the object size table. */
    public companion object {
        /** `AP_MAX_COUNT`. */
        public const val AP_MAX_COUNT: Int = 0x14

        /**
         * `sOamTileSizeLut` (width, height in tiles), indexed `((shape * 4) + size) * 2` with the
         * OAM attr0 shape and attr1 size fields (see `OBJ_SIZE_TABLE_INDEX`).
         */
        private val SIZE_LUT = intArrayOf(
            1, 1, 2, 2, 4, 4, 8, 8,
            2, 1, 4, 1, 4, 2, 8, 4,
            1, 2, 1, 4, 2, 4, 4, 8,
        )
    }
}
