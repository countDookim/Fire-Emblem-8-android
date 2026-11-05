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
 * field as a named property. [decode] and [encode] round-trip the 0x40 bytes exactly. Signed
 * fields hold their sign-extended value, unsigned ones their zero-extended value (a `u32` holds
 * its 32 bits in the [Int]). `Vec2` members are split into `...X` / `...Y`.
 */
public class BmSt {
    /** `main_loop_ended` (s8, 0x00). */
    public var mainLoopEnded: Int = 0

    /** `lock` (s8, 0x01). */
    public var lock: Int = 0

    /** `gameGfxSemaphore` (s8, 0x02). */
    public var gameGfxSemaphore: Int = 0

    /** `_unk04` (u8, 0x03). */
    public var unk03: Int = 0

    /** `gameStateBits` (u8, 0x04). */
    public var gameStateBits: Int = 0

    /** `_unk05` (u8, 0x05). */
    public var unk05: Int = 0

    /** `prevVCount` (u16, 0x06). */
    public var prevVCount: Int = 0

    /** `_unk08` (u32, 0x08). */
    public var unk08: Int = 0

    /** `camera.x` (s16, 0x0C). */
    public var cameraX: Int = 0

    /** `camera.y` (s16, 0x0E). */
    public var cameraY: Int = 0

    /** `cameraPrevious.x` (s16, 0x10). */
    public var cameraPreviousX: Int = 0

    /** `cameraPrevious.y` (s16, 0x12). */
    public var cameraPreviousY: Int = 0

    /** `playerCursor.x` (s16, 0x14). */
    public var playerCursorX: Int = 0

    /** `playerCursor.y` (s16, 0x16). */
    public var playerCursorY: Int = 0

    /** `cursorPrevious.x` (s16, 0x18). */
    public var cursorPreviousX: Int = 0

    /** `cursorPrevious.y` (s16, 0x1A). */
    public var cursorPreviousY: Int = 0

    /** `cursorTarget.x` (s16, 0x1C). */
    public var cursorTargetX: Int = 0

    /** `cursorTarget.y` (s16, 0x1E). */
    public var cursorTargetY: Int = 0

    /** `playerCursorDisplay.x` (s16, 0x20). */
    public var playerCursorDisplayX: Int = 0

    /** `playerCursorDisplay.y` (s16, 0x22). */
    public var playerCursorDisplayY: Int = 0

    /** `mapRenderOrigin.x` (u16, 0x24). */
    public var mapRenderOriginX: Int = 0

    /** `mapRenderOrigin.y` (u16, 0x26). */
    public var mapRenderOriginY: Int = 0

    /** `cameraMax.x` (s16, 0x28). */
    public var cameraMaxX: Int = 0

    /** `cameraMax.y` (s16, 0x2A). */
    public var cameraMaxY: Int = 0

    /** `um_tmp_item` (u16, 0x2C). */
    public var umTmpItem: Int = 0

    /** `itemUnk2E` (u16, 0x2E). */
    public var itemUnk2E: Int = 0

    /** `unk30` (u16, 0x30). */
    public var unk30: Int = 0

    /** `unk32` (s16, 0x32). */
    public var unk32: Int = 0

    /** `unk34` (s16, 0x34). */
    public var unk34: Int = 0

    /** `unk36` (s8, 0x36). */
    public var unk36: Int = 0

    /** `unk37` (s8, 0x37). */
    public var unk37: Int = 0

    /** `altBlendACa` (u8, 0x38). */
    public var altBlendACa: Int = 0

    /** `altBlendACb` (u8, 0x39). */
    public var altBlendACb: Int = 0

    /** `altBlendBCa` (u8, 0x3A). */
    public var altBlendBCa: Int = 0

    /** `altBlendBCb` (u8, 0x3B). */
    public var altBlendBCb: Int = 0

    /** `just_resumed` (u8, 0x3C). */
    public var justResumed: Int = 0

    /** `taken_action` (u8, 0x3D). */
    public var takenAction: Int = 0

    /** `swapActionRangeCount` (u8, 0x3E). */
    public var swapActionRangeCount: Int = 0

    /** `unk3F` (s8, 0x3F). */
    public var unk3F: Int = 0

    /** Reads the 0x40 bytes at [offset] of [bytes] into this state. Does not allocate. */
    public fun decode(bytes: ByteArray, offset: Int) {
        val b = bytes
        val o = offset
        mainLoopEnded = s8(b, o)
        lock = s8(b, o + 0x01)
        gameGfxSemaphore = s8(b, o + 0x02)
        unk03 = u8(b, o + 0x03)
        gameStateBits = u8(b, o + 0x04)
        unk05 = u8(b, o + 0x05)
        prevVCount = u16(b, o + 0x06)
        unk08 = u32(b, o + 0x08)
        cameraX = s16(b, o + 0x0C)
        cameraY = s16(b, o + 0x0E)
        cameraPreviousX = s16(b, o + 0x10)
        cameraPreviousY = s16(b, o + 0x12)
        playerCursorX = s16(b, o + 0x14)
        playerCursorY = s16(b, o + 0x16)
        cursorPreviousX = s16(b, o + 0x18)
        cursorPreviousY = s16(b, o + 0x1A)
        cursorTargetX = s16(b, o + 0x1C)
        cursorTargetY = s16(b, o + 0x1E)
        playerCursorDisplayX = s16(b, o + 0x20)
        playerCursorDisplayY = s16(b, o + 0x22)
        mapRenderOriginX = u16(b, o + 0x24)
        mapRenderOriginY = u16(b, o + 0x26)
        cameraMaxX = s16(b, o + 0x28)
        cameraMaxY = s16(b, o + 0x2A)
        umTmpItem = u16(b, o + 0x2C)
        itemUnk2E = u16(b, o + 0x2E)
        unk30 = u16(b, o + 0x30)
        unk32 = s16(b, o + 0x32)
        unk34 = s16(b, o + 0x34)
        unk36 = s8(b, o + 0x36)
        unk37 = s8(b, o + 0x37)
        altBlendACa = u8(b, o + 0x38)
        altBlendACb = u8(b, o + 0x39)
        altBlendBCa = u8(b, o + 0x3A)
        altBlendBCb = u8(b, o + 0x3B)
        justResumed = u8(b, o + 0x3C)
        takenAction = u8(b, o + 0x3D)
        swapActionRangeCount = u8(b, o + 0x3E)
        unk3F = s8(b, o + 0x3F)
    }

    /** Writes the 0x40 bytes of this state at [offset] of [bytes]. Does not allocate. */
    public fun encode(bytes: ByteArray, offset: Int) {
        val b = bytes
        val o = offset
        put8(b, o, mainLoopEnded)
        put8(b, o + 0x01, lock)
        put8(b, o + 0x02, gameGfxSemaphore)
        put8(b, o + 0x03, unk03)
        put8(b, o + 0x04, gameStateBits)
        put8(b, o + 0x05, unk05)
        put16(b, o + 0x06, prevVCount)
        put32(b, o + 0x08, unk08)
        put16(b, o + 0x0C, cameraX)
        put16(b, o + 0x0E, cameraY)
        put16(b, o + 0x10, cameraPreviousX)
        put16(b, o + 0x12, cameraPreviousY)
        put16(b, o + 0x14, playerCursorX)
        put16(b, o + 0x16, playerCursorY)
        put16(b, o + 0x18, cursorPreviousX)
        put16(b, o + 0x1A, cursorPreviousY)
        put16(b, o + 0x1C, cursorTargetX)
        put16(b, o + 0x1E, cursorTargetY)
        put16(b, o + 0x20, playerCursorDisplayX)
        put16(b, o + 0x22, playerCursorDisplayY)
        put16(b, o + 0x24, mapRenderOriginX)
        put16(b, o + 0x26, mapRenderOriginY)
        put16(b, o + 0x28, cameraMaxX)
        put16(b, o + 0x2A, cameraMaxY)
        put16(b, o + 0x2C, umTmpItem)
        put16(b, o + 0x2E, itemUnk2E)
        put16(b, o + 0x30, unk30)
        put16(b, o + 0x32, unk32)
        put16(b, o + 0x34, unk34)
        put8(b, o + 0x36, unk36)
        put8(b, o + 0x37, unk37)
        put8(b, o + 0x38, altBlendACa)
        put8(b, o + 0x39, altBlendACb)
        put8(b, o + 0x3A, altBlendBCa)
        put8(b, o + 0x3B, altBlendBCb)
        put8(b, o + 0x3C, justResumed)
        put8(b, o + 0x3D, takenAction)
        put8(b, o + 0x3E, swapActionRangeCount)
        put8(b, o + 0x3F, unk3F)
    }

    public companion object {
        /** `sizeof(struct BmSt)`. */
        public const val SIZE: Int = 0x40
    }
}
