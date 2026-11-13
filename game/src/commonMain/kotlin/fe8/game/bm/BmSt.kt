package fe8.game.bm

import fe8.game.bm.StructCodec.put16
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s16
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * Mutable model of the decomp's `struct BmSt` (`gBmSt`, include/types.h, 0x40 bytes) with every
 * field as a named property: the state of the chapter map screen while it is running (camera,
 * cursor, locks, per-phase flags). Unlike [PlaySt] it is not part of the save files, except in
 * the mid-chapter suspend block; `ClearBattleMapState` (src/bmio.c) zeroes it when a map starts,
 * keeping only `lock`. [decode] and [encode] round-trip the 0x40 bytes exactly. Signed
 * fields hold their sign-extended value, unsigned ones their zero-extended value (a `u32` holds
 * its 32 bits in the [Int]). `Vec2` members are split into `...X` / `...Y`.
 */
public class BmSt {
    /**
     * `main_loop_ended` (s8, 0x00): set to 1 at the end of each main loop frame (the map's
     * `OnMain` in src/bm.c and the battle animation loops) and cleared when the next VBlank
     * flushes the display (src/bm.c); the level up and magic effects poll it to know that a
     * frame has run.
     */
    public var mainLoopEnded: Int = 0

    /**
     * `lock` (s8, 0x01): the game logic lock counter: `LockGame` adds 1, `UnlockGame` takes 1
     * and `GetGameLock` returns it (src/bm.c); the map logic procs wait while it is not 0. It
     * is the one field kept by `ClearBattleMapState`.
     */
    public var lock: Int = 0

    /**
     * `gameGfxSemaphore` (s8, 0x02): how many `BMapDispSuspend` calls have not been undone by
     * `BMapDispResume` (src/bmio.c); the map display procs are blocked while it is above 0.
     */
    public var gameGfxSemaphore: Int = 0

    /** `_unk04` (u8, 0x03): unknown; the decomp names it `_unk04` though it sits at 0x03. */
    public var unk03: Int = 0

    /**
     * `gameStateBits` (u8, 0x04): `BM_FLAG_*` bits (include/types.h): 0x01 `BM_FLAG_0` (when
     * set the map render also scrolls BG2 with the camera, src/bmmap.c), 0x02 `BM_FLAG_1`
     * (set while the player phase shows a unit's movement range) and 0x08 `BM_FLAG_3` (set and
     * cleared around the same selection, src/playerphase.c; meaning unknown), 0x10
     * `BM_FLAG_PREPSCREEN`, 0x20 `BM_FLAG_5` (the decomp guesses it mutes battle animation
     * music), 0x40 `BM_FLAG_LINKARENA` (a link arena battle).
     */
    public var gameStateBits: Int = 0

    /** `_unk05` (u8, 0x05): unknown; no read or write of it was found in src/. */
    public var unk05: Int = 0

    /**
     * `prevVCount` (u16, 0x06): `REG_VCOUNT` stored at the end of a main loop frame (src/bm.c,
     * the battle animation loops); nothing in src/ reads it.
     */
    public var prevVCount: Int = 0

    /** `_unk08` (u32, 0x08): unknown; no read or write of it was found in src/. */
    public var unk08: Int = 0

    /** `camera.x` (s16, 0x0C): the map camera's left edge in map pixels. */
    public var cameraX: Int = 0

    /** `camera.y` (s16, 0x0E): the map camera's top edge in map pixels. */
    public var cameraY: Int = 0

    /**
     * `cameraPrevious.x` (s16, 0x10): `camera.x` at the last map redraw; the map render compares
     * them to find new tile columns.
     */
    public var cameraPreviousX: Int = 0

    /**
     * `cameraPrevious.y` (s16, 0x12): `camera.y` at the last map redraw, likewise for tile rows.
     */
    public var cameraPreviousY: Int = 0

    /** `playerCursor.x` (s16, 0x14): the map cursor's tile column. */
    public var playerCursorX: Int = 0

    /** `playerCursor.y` (s16, 0x16): the map cursor's tile row. */
    public var playerCursorY: Int = 0

    /**
     * `cursorPrevious.x` (s16, 0x18): the cursor column of the last unit-info refresh (-1 forces
     * one; src/bmudisp.c).
     */
    public var cursorPreviousX: Int = 0

    /** `cursorPrevious.y` (s16, 0x1A): the cursor row of the last unit-info refresh. */
    public var cursorPreviousY: Int = 0

    /**
     * `cursorTarget.x` (s16, 0x1C): the cursor's target x in map pixels; menus open beside it
     * (`cursorTarget.x - camera.x`, src/bmmenu.c).
     */
    public var cursorTargetX: Int = 0

    /** `cursorTarget.y` (s16, 0x1E): the cursor's target y in map pixels. */
    public var cursorTargetY: Int = 0

    /**
     * `playerCursorDisplay.x` (s16, 0x20): where the cursor sprite is drawn, in map pixels; it is a
     * multiple of 16 once the cursor has arrived (src/playerphase.c).
     */
    public var playerCursorDisplayX: Int = 0

    /** `playerCursorDisplay.y` (s16, 0x22): the cursor sprite's y, likewise. */
    public var playerCursorDisplayY: Int = 0

    /**
     * `mapRenderOrigin.x` (u16, 0x24): the map tile column at the top left of the drawn map,
     * `camera.x >> 4` (src/bmmap.c).
     */
    public var mapRenderOriginX: Int = 0

    /** `mapRenderOrigin.y` (u16, 0x26): the map tile row at the top, `camera.y >> 4`. */
    public var mapRenderOriginY: Int = 0

    /**
     * `cameraMax.x` (s16, 0x28): the largest `camera.x`: map width * 16 - 240 (`DISPLAY_WIDTH`,
     * src/bmmap.c).
     */
    public var cameraMaxX: Int = 0

    /**
     * `cameraMax.y` (s16, 0x2A): the largest `camera.y`: map height * 16 - 160 (`DISPLAY_HEIGHT`).
     */
    public var cameraMaxY: Int = 0

    /**
     * `um_tmp_item` (u16, 0x2C): the item the unit menu's current entry shows (src/bmmenu.c sets
     * it, e.g. for the dance entry; its help box and name read it).
     */
    public var umTmpItem: Int = 0

    /**
     * `itemUnk2E` (u16, 0x2E): an item waiting to be put into the convoy (`AddItemToConvoy`,
     * src/convoymenu.c; 0 for none).
     */
    public var itemUnk2E: Int = 0

    /** `unk30` (u16, 0x30): unknown; no read or write of it was found in src/. */
    public var unk30: Int = 0

    /**
     * `unk32` (s16, 0x32): the camera x offset within a 16-pixel tile (0..15) during the camera's
     * stepped scroll (src/bm.c); 0 when aligned.
     */
    public var unk32: Int = 0

    /** `unk34` (s16, 0x34): the same for the y axis (0..15). */
    public var unk34: Int = 0

    /**
     * `unk36` (s8, 0x36): the camera's x step per frame (signed) while it scrolls to a tile
     * boundary (src/bm.c).
     */
    public var unk36: Int = 0

    /** `unk37` (s8, 0x37): the camera's y step per frame (signed). */
    public var unk37: Int = 0

    /**
     * `altBlendACa` (u8, 0x38): first alpha blend weight (`Ca`, of 16) of pair A, which effects
     * that blend a screen band by scanline use instead of the normal blend setting
     * (`PhaseIntro` for the text band, src/bb.c).
     */
    public var altBlendACa: Int = 0

    /** `altBlendACb` (u8, 0x39): second alpha blend weight (`Cb`) of pair A. */
    public var altBlendACb: Int = 0

    /**
     * `altBlendBCa` (u8, 0x3A): first blend weight of pair B (`PhaseIntro` uses it for the
     * squares).
     */
    public var altBlendBCa: Int = 0

    /** `altBlendBCb` (u8, 0x3B): second blend weight of pair B. */
    public var altBlendBCb: Int = 0

    /**
     * `just_resumed` (u8, 0x3C): set to true when the map is restored from a suspend save
     * (src/bmio.c); the action-end and battle record code check it.
     */
    public var justResumed: Int = 0

    /**
     * `taken_action` (u8, 0x3D): `BM_TAKEN_ACTION_*` bits for what the unit has done this move
     * (src/playerphase.c): 0x01 `TAKE`, 0x02 `TRADE`, 0x04 `SUPPLY`, 0x08 `BALLISTA`; the unit
     * menu hides entries according to them (src/bmmenu.c).
     */
    public var takenAction: Int = 0

    /**
     * `swapActionRangeCount` (u8, 0x3E): bit 0 chooses which danger zone range is drawn
     * (`GenerateDangerZoneRange`, src/playerphase.c); reset to 0 when menus open.
     */
    public var swapActionRangeCount: Int = 0

    /**
     * `unk3F` (s8, 0x3F): an item slot index: set from `gActionData.itemSlotIndex` (src/bmmenu.c);
     * the trade screen uses it as hover start (src/bmtrade.c); 0xFF for none.
     */
    public var unk3F: Int = 0

    /** Reads the 0x40 bytes at [offset] of [bytes] into this state. Does not allocate. */
    public fun decode(bytes: ByteArray, offset: Int) {
        mainLoopEnded = s8(bytes, offset)
        lock = s8(bytes, offset + 0x01)
        gameGfxSemaphore = s8(bytes, offset + 0x02)
        unk03 = u8(bytes, offset + 0x03)
        gameStateBits = u8(bytes, offset + 0x04)
        unk05 = u8(bytes, offset + 0x05)
        prevVCount = u16(bytes, offset + 0x06)
        unk08 = u32(bytes, offset + 0x08)
        cameraX = s16(bytes, offset + 0x0C)
        cameraY = s16(bytes, offset + 0x0E)
        cameraPreviousX = s16(bytes, offset + 0x10)
        cameraPreviousY = s16(bytes, offset + 0x12)
        playerCursorX = s16(bytes, offset + 0x14)
        playerCursorY = s16(bytes, offset + 0x16)
        cursorPreviousX = s16(bytes, offset + 0x18)
        cursorPreviousY = s16(bytes, offset + 0x1A)
        cursorTargetX = s16(bytes, offset + 0x1C)
        cursorTargetY = s16(bytes, offset + 0x1E)
        playerCursorDisplayX = s16(bytes, offset + 0x20)
        playerCursorDisplayY = s16(bytes, offset + 0x22)
        mapRenderOriginX = u16(bytes, offset + 0x24)
        mapRenderOriginY = u16(bytes, offset + 0x26)
        cameraMaxX = s16(bytes, offset + 0x28)
        cameraMaxY = s16(bytes, offset + 0x2A)
        umTmpItem = u16(bytes, offset + 0x2C)
        itemUnk2E = u16(bytes, offset + 0x2E)
        unk30 = u16(bytes, offset + 0x30)
        unk32 = s16(bytes, offset + 0x32)
        unk34 = s16(bytes, offset + 0x34)
        unk36 = s8(bytes, offset + 0x36)
        unk37 = s8(bytes, offset + 0x37)
        altBlendACa = u8(bytes, offset + 0x38)
        altBlendACb = u8(bytes, offset + 0x39)
        altBlendBCa = u8(bytes, offset + 0x3A)
        altBlendBCb = u8(bytes, offset + 0x3B)
        justResumed = u8(bytes, offset + 0x3C)
        takenAction = u8(bytes, offset + 0x3D)
        swapActionRangeCount = u8(bytes, offset + 0x3E)
        unk3F = s8(bytes, offset + 0x3F)
    }

    /** Writes the 0x40 bytes of this state at [offset] of [bytes]. Does not allocate. */
    public fun encode(bytes: ByteArray, offset: Int) {
        put8(bytes, offset, mainLoopEnded)
        put8(bytes, offset + 0x01, lock)
        put8(bytes, offset + 0x02, gameGfxSemaphore)
        put8(bytes, offset + 0x03, unk03)
        put8(bytes, offset + 0x04, gameStateBits)
        put8(bytes, offset + 0x05, unk05)
        put16(bytes, offset + 0x06, prevVCount)
        put32(bytes, offset + 0x08, unk08)
        put16(bytes, offset + 0x0C, cameraX)
        put16(bytes, offset + 0x0E, cameraY)
        put16(bytes, offset + 0x10, cameraPreviousX)
        put16(bytes, offset + 0x12, cameraPreviousY)
        put16(bytes, offset + 0x14, playerCursorX)
        put16(bytes, offset + 0x16, playerCursorY)
        put16(bytes, offset + 0x18, cursorPreviousX)
        put16(bytes, offset + 0x1A, cursorPreviousY)
        put16(bytes, offset + 0x1C, cursorTargetX)
        put16(bytes, offset + 0x1E, cursorTargetY)
        put16(bytes, offset + 0x20, playerCursorDisplayX)
        put16(bytes, offset + 0x22, playerCursorDisplayY)
        put16(bytes, offset + 0x24, mapRenderOriginX)
        put16(bytes, offset + 0x26, mapRenderOriginY)
        put16(bytes, offset + 0x28, cameraMaxX)
        put16(bytes, offset + 0x2A, cameraMaxY)
        put16(bytes, offset + 0x2C, umTmpItem)
        put16(bytes, offset + 0x2E, itemUnk2E)
        put16(bytes, offset + 0x30, unk30)
        put16(bytes, offset + 0x32, unk32)
        put16(bytes, offset + 0x34, unk34)
        put8(bytes, offset + 0x36, unk36)
        put8(bytes, offset + 0x37, unk37)
        put8(bytes, offset + 0x38, altBlendACa)
        put8(bytes, offset + 0x39, altBlendACb)
        put8(bytes, offset + 0x3A, altBlendBCa)
        put8(bytes, offset + 0x3B, altBlendBCb)
        put8(bytes, offset + 0x3C, justResumed)
        put8(bytes, offset + 0x3D, takenAction)
        put8(bytes, offset + 0x3E, swapActionRangeCount)
        put8(bytes, offset + 0x3F, unk3F)
    }

    /** Struct size and layout constants. */
    public companion object {
        /** `sizeof(struct BmSt)`: 0x40 (64 bytes). */
        public const val SIZE: Int = 0x40
    }
}
