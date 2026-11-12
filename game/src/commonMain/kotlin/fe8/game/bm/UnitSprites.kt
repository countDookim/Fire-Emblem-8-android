package fe8.game.bm

import fe8.game.RomView
import gbafe.core.time.GameClock
import gbafe.render.BgHardware

/**
 * Map sprite (unit icon) graphics slots of src/bmudisp.c: the `gSMSGfxBuffer` frames, the
 * `gUnitSpriteSlots` lookup, the two slot counters and the OBJ palettes of the sprites.
 */
public class UnitSprites(
    /** ROM the sprite sheets and palettes are read from. */
    private val rom: RomView,
    /** `gPaletteBuffer` owner. */
    private val hardware: BgHardware,
    /** `gBmSt` (for `BM_FLAG_LINKARENA`). */
    private val bmSt: BmSt,
) {
    /** `gUnitSpriteSlots` (0xD0 bytes; 0xFF = unused). */
    public val slots: ByteArray = ByteArray(UNITSPRITE_MAX) { -1 }

    /** `gSMSGfxBuffer` (3 frames of 0x2000 bytes, 8 rows of 0x20 tiles of 0x20 bytes). */
    public val gfxBuffer: ByteArray = ByteArray(3 * FRAME_SIZE)

    /** `gSMS32xGfxIndexCounter`. */
    public var gfx32Counter: Int = 0

    /** `gSMS16xGfxIndexCounter`. */
    public var gfx16Counter: Int = 0

    /** `gSMSSyncFlag`. */
    public var syncFlag: Int = 0

    /** `GetGameClock()`. */
    public var gameClock: () -> Int = { 0 }

    /**
     * The game clock itself; when set it is read instead of [gameClock] (a lambda result boxes
     * the clock every call, which the idle frame must not).
     */
    public var clockSource: GameClock? = null

    /** The current game clock tick: [clockSource] when set, otherwise [gameClock]. */
    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /**
     * Hook for `RegisterDataMove(src, dst, size)` of [forceSyncUnitSpriteSheet]: gets the
     * frame number (0 to 2) and the byte count; the default copies at once into OBJ VRAM instead
     * of queueing the transfer for `FlushTiles`.
     */
    public var registerDataMove: (frame: Int, size: Int) -> Unit = { frame, size ->
        copyFrame(frame, size)
    }

    /** Copies the first [size] bytes of animation frame [frame] (0 to 2) into OBJ VRAM. */
    private fun copyFrame(frame: Int, size: Int) {
        val from = frame * FRAME_SIZE
        gfxBuffer.copyInto(hardware.vram, OBJ_SHEET_VRAM, from, from + size)
    }

    /** `UnitSpriteUnpackBuf` (`gGenericBuffer`). */
    private val unpackBuf = ByteArray(0x2000)

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpritePalettes
     *
     * Four map sprite banks to OBJ banks 12-15, then the arena or light rune palette to bank 11.
     */
    public fun applyUnitSpritePalettes() {
        applyPalettes(PAL_MAP_SPRITE, 0x1C, 4)
        val purplePalette =
            if (bmSt.gameStateBits and BM_FLAG_LINKARENA != 0) PAL_ARENA else PAL_LIGHT_RUNE
        applyPalettes(purplePalette, 0x10 + OBJPAL_UNITSPRITE_PURPLE, 1)
    }

    /**
     * Transcribed from src/bmudisp.c: SyncUnitSpriteSheet (not verified: the trace has no dump at
     * a tick that starts an animation frame)
     *
     * Copies the animation frame that starts at this game clock tick into OBJ VRAM `+0x1000`.
     */
    public fun syncUnitSpriteSheet() {
        when (now() % 72) {
            0 -> copyFrame(0, FRAME_SIZE)
            32 -> copyFrame(1, FRAME_SIZE)
            36 -> copyFrame(2, FRAME_SIZE)
            68 -> copyFrame(1, FRAME_SIZE)
        }
    }

    /**
     * Ported from src/bmudisp.c: ForceSyncUnitSpriteSheet (the `>= 68` branch is not observed)
     *
     * Clears the sync flag and requests the copy of the frame shown at this game clock tick.
     */
    public fun forceSyncUnitSpriteSheet() {
        syncFlag = 0
        // The sprite cycle is 72 ticks: frame 0 at tick 0, frame 1 at 32, frame 2 at 36 and
        // frame 1 again at 68, as in syncUnitSpriteSheet.
        val clockPhase = now() % 72
        val frameIndex = when {
            clockPhase >= 68 -> 1
            clockPhase >= 36 -> 2
            clockPhase >= 32 -> 1
            else -> 0
        }
        registerDataMove(frameIndex, FRAME_SIZE)
    }

    /**
     * Copies [count] palettes of 16 colours from the ROM at [romPaletteAddr] into the palette
     * buffer from palette index [firstPaletteIndex] on, then requests the palette sync.
     */
    private fun applyPalettes(romPaletteAddr: Int, firstPaletteIndex: Int, count: Int) {
        rom.pal(romPaletteAddr, 16 * count).copyInto(hardware.palette, firstPaletteIndex * 16)
        hardware.enablePaletteSync()
    }

    /**
     * Ported from src/bmudisp.c: ResetUnitSprites
     *
     * Frees every slot and resets the counters (16x16 slots count down from 0x3F).
     */
    public fun resetUnitSprites() {
        slots.fill(-1)
        gfx32Counter = 0
        gfx16Counter = 0x40 - 1
    }

    /**
     * Transcribed from src/bmudisp.c: ResetUnitSpritesB (not verified: never called in the trace)
     *
     * As [resetUnitSprites] but the 16x16 slots count down from 0x5F.
     */
    public fun resetUnitSpritesB() {
        slots.fill(-1)
        gfx32Counter = 0
        gfx16Counter = 0x60 - 1
    }

    /**
     * Ported from src/bmudisp.c: UseUnitSprite
     *
     * Returns the OBJ tile number (relative to the buffer) of sprite [id], loading it into the
     * next free slot first when it is not loaded yet.
     */
    public fun useUnitSprite(id: Int): Int {
        if ((slots[id].toInt() and 0xFF) == 0xFF) {
            val infoEntry = INFO_TABLE + 8 * (id and 0x7F)
            rom.decompressInto(rom.u32(infoEntry + 4), unpackBuf, 0)
            when (rom.u16(infoEntry + 2)) {
                SIZE_16X16 -> {
                    slots[id] = (applyUnitSpriteImage16x16(gfx16Counter, id) / 2).toByte()
                    gfx16Counter -= 1
                }
                SIZE_16X32 -> {
                    slots[id] = (applyUnitSpriteImage16x32(gfx32Counter, id) / 2).toByte()
                    gfx32Counter += 2
                }
                SIZE_32X32 -> {
                    if ((gfx32Counter and 0x1E) == 0x1E) gfx32Counter += 2
                    slots[id] = (applyUnitSpriteImage32x32(gfx32Counter, id) / 2).toByte()
                    gfx32Counter += 4
                }
            }
            syncFlag++
        }
        return (slots[id].toInt() and 0xFF) shl 1
    }

    /** The `size` of sprite [id] in `unit_icon_wait_table` (0 = 16x16, 1 = 16x32, 2 = 32x32). */
    public fun iconSize(id: Int): Int = rom.u16(INFO_TABLE + 8 * (id and 0x7F) + 2)

    /**
     * Ported from src/bmudisp.c: StartWorldMapSMS
     *
     * Loads sprite [smsId] into the frame column [slot] for the world map unit [frameId]
     * (a 16x16 sprite goes to the lower two rows of its column, as the standing-sprite copy
     * expects); returns the tile offset.
     */
    public fun startWorldMapSms(smsId: Int, frameId: Int, slot: Int): Int {
        val infoEntry = INFO_TABLE + 8 * (smsId and 0x7F)
        rom.decompressInto(rom.u32(infoEntry + 4), unpackBuf, 0)
        when (rom.u16(infoEntry + 2)) {
            SIZE_16X16 -> slots[frameId] = (applyUnitSpriteUiImage16x16(slot, smsId) / 2).toByte()
            SIZE_16X32 -> slots[frameId] = (applyUnitSpriteImage16x32(slot, smsId) / 2).toByte()
            SIZE_32X32 -> slots[frameId] = (applyUnitSpriteImage32x32(slot, smsId) / 2).toByte()
        }
        return (slots[frameId].toInt() and 0xFF) shl 1
    }

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpriteUiImage16x16
     *
     * Like [applyUnitSpriteImage16x16], but clears the upper two rows and puts the 16x16 frames
     * in the lower two rows of [slot]'s column; returns its tile offset.
     */
    public fun applyUnitSpriteUiImage16x16(slot: Int, id: Int): Int {
        val chrTile = slotToChr(slot)
        val outByteOffset = chrTile * CHR_SIZE
        val frameStep = ((id shr UNITSPRITE_ID_BITS) xor 1) and 1
        for (i in 0 until 3) {
            val imgOff = i * frameStep * 4 * CHR_SIZE
            val base = i * FRAME_SIZE + outByteOffset
            gfxBuffer.fill(0, base, base + 2 * CHR_SIZE)
            val row1 = base + CHR_SIZE * CHR_LINE
            gfxBuffer.fill(0, row1, row1 + 2 * CHR_SIZE)
            unpackBuf.copyInto(
                gfxBuffer, base + 2 * CHR_SIZE * CHR_LINE, imgOff, imgOff + 2 * CHR_SIZE,
            )
            unpackBuf.copyInto(
                gfxBuffer, base + 3 * CHR_SIZE * CHR_LINE, 2 * CHR_SIZE + imgOff,
                4 * CHR_SIZE + imgOff,
            )
        }
        return chrTile
    }

    /**
     * Ported from src/bmudisp.c: SetStandingMuFacingWM
     *
     * Copies the animation frame of this game clock tick, four rows of four tiles at the tile
     * column [frameId], to OBJ VRAM at the byte offset [dstOffset] (the original queues the
     * transfers for VBlank).
     */
    public fun setStandingMuFacingWm(frameId: Int, dstOffset: Int) {
        val phase = now() % 72
        val frame = when {
            phase >= 68 -> 1
            phase >= 36 -> 2
            phase >= 32 -> 1
            else -> 0
        }
        var a = frameId * CHR_SIZE
        for (i in 0..3) {
            val from = frame * FRAME_SIZE + a
            gfxBuffer.copyInto(hardware.vram, dstOffset + a, from, from + 4 * CHR_SIZE)
            a += CHR_SIZE * CHR_LINE
        }
    }

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpriteImage16x16
     *
     * Copies the 16x16 frames from the unpack buffer into [slot]; returns its tile offset.
     */
    public fun applyUnitSpriteImage16x16(slot: Int, id: Int): Int =
        applyImage(slot, id, 4, 2, 2)

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpriteImage16x32
     *
     * Copies the 16x32 frames from the unpack buffer into [slot]; returns its tile offset.
     */
    public fun applyUnitSpriteImage16x32(slot: Int, id: Int): Int =
        applyImage(slot, id, 8, 4, 2)

    /**
     * Transcribed from src/bmudisp.c: ApplyUnitSpriteImage32x32 (not verified: no 32x32 sprite is
     * loaded in the traced frames)
     *
     * Copies the 32x32 frames from the unpack buffer into [slot]; returns its tile offset.
     */
    public fun applyUnitSpriteImage32x32(slot: Int, id: Int): Int =
        applyImage(slot, id, 16, 4, 4)

    /**
     * The shared body of the three `ApplyUnitSpriteImage*` functions: copies [rows] rows of
     * [tilesPerRow] tiles from each of the three frames in the unpack buffer into the character
     * block of [slot], and returns that block's tile offset. [frameTiles] is the tile step between
     * frames; it applies only while id bit 7 is clear, otherwise all frames read the same image.
     */
    private fun applyImage(slot: Int, id: Int, frameTiles: Int, rows: Int, tilesPerRow: Int): Int {
        val chrTile = slotToChr(slot)
        val outByteOffset = chrTile * CHR_SIZE
        // 1 while id bit 7 is clear (the frames step through the image), 0 when it is set.
        val frameStep = ((id shr UNITSPRITE_ID_BITS) xor 1) and 1
        for (i in 0 until 3) {
            val frameByteOffset = i * frameStep * frameTiles * CHR_SIZE
            for (r in 0 until rows) {
                unpackBuf.copyInto(
                    gfxBuffer,
                    i * FRAME_SIZE + r * CHR_SIZE * CHR_LINE + outByteOffset,
                    r * tilesPerRow * CHR_SIZE + frameByteOffset,
                    r * tilesPerRow * CHR_SIZE + frameByteOffset + tilesPerRow * CHR_SIZE,
                )
            }
        }
        return chrTile
    }

    /** `sSlotToChrLut[slot]`: even slots in rows 0/4/8, odd slots two rows below. */
    private fun slotToChr(slot: Int): Int =
        (slot / 32) * 4 * CHR_LINE + (slot and 1) * 2 * CHR_LINE + ((slot and 0x1F) shr 1) * 2

    private companion object {
        const val OBJ_SHEET_VRAM = 0x11000
        const val UNITSPRITE_MAX = 0xD0
        const val UNITSPRITE_ID_BITS = 7
        const val OBJPAL_UNITSPRITE_PURPLE = 11
        const val BM_FLAG_LINKARENA = 1 shl 6
        const val CHR_SIZE = 0x20
        const val CHR_LINE = 0x20
        const val FRAME_SIZE = 8 * CHR_LINE * CHR_SIZE
        const val SIZE_16X16 = 0
        const val SIZE_16X32 = 1
        const val SIZE_32X32 = 2
        const val INFO_TABLE = 0x088AF880
        const val PAL_MAP_SPRITE = 0x0859EE20
        const val PAL_ARENA = 0x0859EEA0
        const val PAL_LIGHT_RUNE = 0x0859EEC0
    }
}
