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

    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /**
     * Stand-in for `RegisterDataMove(src, dst, size)` of [forceSyncUnitSpriteSheet]: gets the
     * frame number (0 to 2) and the byte count; the default copies at once into OBJ VRAM instead
     * of queueing the transfer for `FlushTiles`.
     */
    public var registerDataMove: (frame: Int, size: Int) -> Unit = { frame, size ->
        copyFrame(frame, size)
    }

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
        val src = if (bmSt.gameStateBits and BM_FLAG_LINKARENA != 0) PAL_ARENA else PAL_LIGHT_RUNE
        applyPalettes(src, 0x10 + OBJPAL_UNITSPRITE_PURPLE, 1)
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
        val frame = now() % 72
        val which = when {
            frame >= 68 -> 1
            frame >= 36 -> 2
            frame >= 32 -> 1
            else -> 0
        }
        registerDataMove(which, FRAME_SIZE)
    }

    private fun applyPalettes(src: Int, palId: Int, count: Int) {
        rom.pal(src, 16 * count).copyInto(hardware.palette, palId * 16)
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
            val info = INFO_TABLE + 8 * (id and 0x7F)
            rom.decompressInto(rom.u32(info + 4), unpackBuf, 0)
            when (rom.u16(info + 2)) {
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

    /** The shared body: [rows] rows of [tiles] tiles per frame, frames [frameTiles] apart. */
    private fun applyImage(slot: Int, id: Int, frameTiles: Int, rows: Int, tiles: Int): Int {
        val lut = slotToChr(slot)
        val outOff = lut * CHR_SIZE
        val second = ((id shr UNITSPRITE_ID_BITS) xor 1) and 1
        for (i in 0 until 3) {
            val imgOff = i * second * frameTiles * CHR_SIZE
            for (r in 0 until rows) {
                unpackBuf.copyInto(
                    gfxBuffer,
                    i * FRAME_SIZE + r * CHR_SIZE * CHR_LINE + outOff,
                    r * tiles * CHR_SIZE + imgOff,
                    r * tiles * CHR_SIZE + imgOff + tiles * CHR_SIZE,
                )
            }
        }
        return lut
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
