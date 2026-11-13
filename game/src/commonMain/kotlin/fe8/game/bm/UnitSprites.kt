package fe8.game.bm

import fe8.game.RomView
import gbafe.core.time.GameClock
import gbafe.render.BgHardware

/**
 * The standing map sprites ("SMS", the little unit figures on the chapter map, `unit_icon_wait_*`
 * in the decomp) of src/bmudisp.c: the `gSMSGfxBuffer` frames, the `gUnitSpriteSlots` lookup, the
 * two slot counters and the OBJ palettes of the sprites.
 *
 * Each sprite sheet has three idle frames (the figure bobbing on the spot). All three are
 * unpacked into [gfxBuffer], one slot per sprite; [syncUnitSpriteSheet] then copies one whole
 * frame (every loaded sprite at once) to OBJ VRAM `+0x1000` (OBJ tile 0x80) as the game clock
 * advances, which animates every map figure together.
 */
public class UnitSprites(
    /** ROM the sprite sheets and palettes are read from. */
    private val rom: RomView,
    /** `gPaletteBuffer` owner. */
    private val hardware: BgHardware,
    /** `gBmSt` (for `BM_FLAG_LINKARENA`). */
    private val bmSt: BmSt,
) {
    /**
     * `gUnitSpriteSlots` (`UNITSPRITE_MAX` = 0xD0 bytes): by sprite id (the class `SMSId`, bit 7 =
     * `UNITSPRITE_ID_BITS`, set means a still image) the slot number / 2 the sprite was loaded
     * into, 0xFF while it is not loaded. [startWorldMapSms] indexes it by world map unit instead.
     */
    public val slots: ByteArray = ByteArray(UNITSPRITE_MAX) { -1 }

    /**
     * `gSMSGfxBuffer`: the three idle animation frames, 0x2000 bytes each (8 tile rows of
     * `CHR_LINE` = 0x20 tiles of `CHR_SIZE` = 0x20 bytes, 4 bits per pixel). A sprite occupies
     * the same place in all three frames (see [slotToChr]).
     */
    public val gfxBuffer: ByteArray = ByteArray(3 * FRAME_SIZE)

    /**
     * `gSMS32xGfxIndexCounter`: the next free slot for a 16x32 or 32x32 sprite; counts up from 0
     * ([useUnitSprite]).
     */
    public var gfx32Counter: Int = 0

    /**
     * `gSMS16xGfxIndexCounter`: the next free slot for a 16x16 sprite; counts down from the top
     * ([resetUnitSprites]: 0x3F).
     */
    public var gfx16Counter: Int = 0

    /**
     * `gSMSSyncFlag`: counts sprites loaded since the last [forceSyncUnitSpriteSheet]; nonzero
     * tells `RefreshUnitSprites` the new sprites must be copied to VRAM now rather than waiting
     * for the next 72-tick animation boundary.
     */
    public var syncFlag: Int = 0

    /** `GetGameClock()`: the frame counter that selects the animation frame (0 to 71). */
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

    /**
     * `UnitSpriteUnpackBuf` (`gGenericBuffer`): the decompressed sprite sheet of the sprite being
     * loaded: its three frames one after another.
     */
    private val unpackBuf = ByteArray(0x2000)

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpritePalettes
     *
     * Loads the four map sprite palettes into OBJ palette banks 12-15 (`OBJPAL_MAPSPRITES`: blue
     * player, red enemy, green NPC, gray for units that have acted), then the fourth team (purple)
     * palette into bank 11 (`OBJPAL_UNITSPRITE_PURPLE`): the link arena palette in a link arena
     * (`BM_FLAG_LINKARENA`), otherwise the light rune palette. Run when the chapter map graphics
     * are set up (`InitMoreBMapGraphics`, src/bmio.c).
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
     * Run every frame by the map's VBlank proc (`PROC_CALL(SyncUnitSpriteSheet)` in
     * `sProc_BMVSync`, src/bmio.c) and by some menus that draw unit sprites. The idle animation
     * loops every 72 game clock ticks: frame 0 from tick 0, frame 1 from 32, frame 2 from 36 and
     * frame 1 again from 68. It copies the whole 0x2000 byte frame only on those four ticks, into
     * OBJ VRAM `+0x1000` (OBJ tile 0x80, where the `oam2Base` of every handle points).
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
     * Called after sprites were loaded (`RefreshUnitSprites` when [syncFlag] is set, and by
     * many screens after they draw unit sprites). Clears the sync flag and requests the copy of
     * the frame that belongs to this tick (the same 72-tick cycle as [syncUnitSpriteSheet], but
     * a range test, so it also works between the four change ticks).
     */
    public fun forceSyncUnitSpriteSheet() {
        syncFlag = 0
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
     * Unloads every sprite (all slots 0xFF) and resets the counters: 16x32 and 32x32 sprites
     * fill from slot 0, 16x16 sprites from slot 0x3F down. Run when the chapter map is set up
     * (src/bmio.c) and by screens that reload unit sprites (preparations, support list, bonus
     * claim, after a battle). The 96 slots of `sSlotToChrLut` are 0x00-0x5F.
     */
    public fun resetUnitSprites() {
        slots.fill(-1)
        gfx32Counter = 0
        gfx16Counter = 0x40 - 1
    }

    /**
     * Transcribed from src/bmudisp.c: ResetUnitSpritesB (not verified: never called in the trace)
     *
     * As [resetUnitSprites] but the 16x16 slots count down from 0x5F, the top of all 96 slots.
     * Nothing in the decomp calls it (also not in `include/bmudisp.h`), so when it was meant
     * to be used is unknown.
     */
    public fun resetUnitSpritesB() {
        slots.fill(-1)
        gfx32Counter = 0
        gfx16Counter = 0x60 - 1
    }

    /**
     * Ported from src/bmudisp.c: UseUnitSprite
     *
     * Returns the OBJ tile number (relative to OBJ VRAM `+0x1000`, so add 0x80 for `oam2`) of
     * sprite [id], first loading it into the next free slot when it is not loaded yet: the sheet
     * is decompressed, copied into all three frames, and [syncFlag] is raised. A 32x32 sprite
     * skips the last column pair of a 32 slot band (`& 0x1E == 0x1E`) so it does not straddle
     * two bands. Called by `RefreshUnitSprites` for every unit and trap on the map, and by
     * `TornOutUnitSprite`.
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

    /**
     * The `size` of sprite [id] in `unit_icon_wait_table` (`UNIT_ICON_SIZE_16x16` = 0,
     * `UNIT_ICON_SIZE_16x32` = 1, `UNIT_ICON_SIZE_32x32` = 2); the table entry is 8 bytes:
     * a u16 whose meaning is unknown, the u16 size, the sheet pointer.
     */
    public fun iconSize(id: Int): Int = rom.u16(INFO_TABLE + 8 * (id and 0x7F) + 2)

    /**
     * Ported from src/bmudisp.c: StartWorldMapSMS
     *
     * Loads sprite [smsId] into slot [slot] for the world map army marker [frameId] (its index,
     * 0 to 6; the world map passes slot 0, 4, 8 ... 0x18 from `gUnknown_08205F7C`), which
     * `SetStandingMuFacingWM` later animates. A 16x16 sprite goes to the lower two tile rows
     * of its column ([applyUnitSpriteUiImage16x16]). Returns the tile offset, which the caller
     * ignores (src/worldmap_gmapunit.c).
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
     * Like [applyUnitSpriteImage16x16], but clears the upper two tile rows and puts the 16x16
     * frames in the lower two tile rows of the 4 row slot area, where the world map and `Mu`
     * (moving unit) standing copy expects them (it copies four tile rows). Returns the tile
     * offset.
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
     * Run every frame for each world map army marker standing still (src/worldmap_gmapunit.c;
     * [frameId] is its `gUnknown_08205F7C` slot). Copies the idle frame of this game clock tick
     * (0, 1, 2, 1 as in [forceSyncUnitSpriteSheet]), four tile rows of four tiles starting at the
     * tile [frameId] of each row, to the VRAM byte offset [dstOffset] (the C passes `0x06010000 +
     * tile * 0x20`; the original queues the transfers for VBlank, here they are immediate).
     */
    public fun setStandingMuFacingWm(frameId: Int, dstOffset: Int) {
        val phase = now() % 72
        val frame = when {
            phase >= 68 -> 1
            phase >= 36 -> 2
            phase >= 32 -> 1
            else -> 0
        }
        var rowByteOffset = frameId * CHR_SIZE
        for (i in 0..3) {
            val from = frame * FRAME_SIZE + rowByteOffset
            gfxBuffer.copyInto(
                hardware.vram, dstOffset + rowByteOffset, from, from + 4 * CHR_SIZE,
            )
            rowByteOffset += CHR_SIZE * CHR_LINE
        }
    }

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpriteImage16x16
     *
     * Copies the three 16x16 frames (2x2 tiles each, 4 tiles apart in the sheet) from the unpack
     * buffer into [slot]: tile rows 0 and 1 of its column. Returns its tile offset.
     */
    public fun applyUnitSpriteImage16x16(slot: Int, id: Int): Int =
        applyImage(slot, id, 4, 2, 2)

    /**
     * Ported from src/bmudisp.c: ApplyUnitSpriteImage16x32
     *
     * Copies the three 16x32 frames (2 wide, 4 tall, 8 tiles apart) from the unpack buffer into
     * [slot] (even and odd slot together). Returns its tile offset.
     */
    public fun applyUnitSpriteImage16x32(slot: Int, id: Int): Int =
        applyImage(slot, id, 8, 4, 2)

    /**
     * Transcribed from src/bmudisp.c: ApplyUnitSpriteImage32x32 (not verified: no 32x32 sprite is
     * loaded in the traced frames)
     *
     * Copies the three 32x32 frames (4 wide, 4 tall, 16 tiles apart) from the unpack buffer into
     * [slot] and the next three slots. Returns its tile offset.
     */
    public fun applyUnitSpriteImage32x32(slot: Int, id: Int): Int =
        applyImage(slot, id, 16, 4, 4)

    /**
     * The shared body of the three `ApplyUnitSpriteImage*` functions: copies [rows] rows of
     * [tilesPerRow] tiles from each of the three frames in the unpack buffer into the character
     * block of [slot], and returns that block's tile offset. [frameTiles] is the tile step between
     * frames; it applies only while id bit 7 is clear, otherwise all three frames read the first
     * image (a sprite that does not animate).
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

    /**
     * `sSlotToChrLut[slot]`, computed: the sheet tile where [slot] starts. The sheet is 0x20 tiles
     * wide and 4 tile rows per band (3 bands, 32 slots each); within a band an even slot is in
     * the upper two tile rows and an odd slot in the lower two, and every pair of slots is a
     * further 2 tiles to the right.
     */
    private fun slotToChr(slot: Int): Int =
        (slot / 32) * 4 * CHR_LINE + (slot and 1) * 2 * CHR_LINE + ((slot and 0x1F) shr 1) * 2

    private companion object {
        /** OBJ VRAM `0x06010000` + 0x1000, as an offset into the VRAM array. */
        const val OBJ_SHEET_VRAM = 0x11000
        const val UNITSPRITE_MAX = 0xD0
        /** The sprite id bit that marks a non-animated (single image) sprite. */
        const val UNITSPRITE_ID_BITS = 7
        const val OBJPAL_UNITSPRITE_PURPLE = 11
        const val BM_FLAG_LINKARENA = 1 shl 6
        /** Bytes of one 4 bits per pixel 8x8 tile. */
        const val CHR_SIZE = 0x20
        /** Tiles per tile row of the frame buffer (`CHR_LINE`, src/hardware.h). */
        const val CHR_LINE = 0x20
        const val FRAME_SIZE = 8 * CHR_LINE * CHR_SIZE
        const val SIZE_16X16 = 0
        const val SIZE_16X32 = 1
        const val SIZE_32X32 = 2
        /** `unit_icon_wait_table` (src/unit_icon_wait_data.c). */
        const val INFO_TABLE = 0x088AF880
        /** `gPal_MapSprite`: four 16 colour palettes. */
        const val PAL_MAP_SPRITE = 0x0859EE20
        /** `gPal_MapSpriteArena`. */
        const val PAL_ARENA = 0x0859EEA0
        /** `gPal_LightRune`. */
        const val PAL_LIGHT_RUNE = 0x0859EEC0
    }
}
