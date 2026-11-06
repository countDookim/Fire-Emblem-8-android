package fe8.game.scene

import fe8.game.bm.StructCodec.put16
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8
import gbafe.proc.ProcFunc

/**
 * Mutable model of the decomp's `struct TalkState` (`sTalkStateCore`, include/scene.h, 0x84 bytes
 * at 0x03000048) with every field as a named property. [encode] and [decode] round-trip the 0x84
 * bytes. Signed fields (`s8`) hold their sign-extended value, unsigned ones their zero-extended
 * value, so C comparisons between an `s8` and a `u8` field behave as in C.
 *
 * Pointers are kept as GBA addresses (0 is NULL): [str] and [strBackup] point into the message
 * buffer, [faces] hold `FaceProc` pointers. [unk38] is a callback; it is encoded as 1 when set.
 */
public class TalkState {
    /** `str` (0x00): address of the next message byte. */
    public var str: Int = 0

    /** `strBackup` (0x04): address to resume at after a nested string, or 0. */
    public var strBackup: Int = 0

    /** `printColor` (u8, 0x08). */
    public var printColor: Int = 0

    /** `lineActive` (u8, 0x09). */
    public var lineActive: Int = 0

    /** `lines` (u8, 0x0A). */
    public var lines: Int = 0

    /** `topTextNum` (u8, 0x0B). */
    public var topTextNum: Int = 0

    /** `xText` (u8, 0x0C). */
    public var xText: Int = 0

    /** `yText` (u8, 0x0D). */
    public var yText: Int = 0

    /** `activeWidth` (u8, 0x0E). */
    public var activeWidth: Int = 0

    /** `speakingFaceSlot` (s8, 0x0F). */
    public var speakingFaceSlot: Int = 0

    /** `speakingWidth` (u8, 0x10). */
    public var speakingWidth: Int = 0

    /** `activeFaceSlot` (u8, 0x11). */
    public var activeFaceSlot: Int = 0

    /** `instantScroll` (s8, 0x12). */
    public var instantScroll: Int = 0

    /** `printDelay` (s8, 0x13). */
    public var printDelay: Int = 0

    /** `printClock` (s8, 0x14). */
    public var printClock: Int = 0

    /** `putLines` (u8, 0x15). */
    public var putLines: Int = 0

    /** `mouthMoveEnabled` (u8, 0x16). */
    public var mouthMoveEnabled: Int = 0

    /** `faceSmileEnabled` (u8, 0x17). */
    public var faceSmileEnabled: Int = 0

    /** `faces[8]` (0x18): `FaceProc` addresses, 0 for NULL. */
    public val faces: IntArray = IntArray(FACE_COUNT)

    /** `unk38` (0x38): per-print callback, null for NULL. */
    public var unk38: ProcFunc? = null

    /** `userNumber` (u32, 0x3C). */
    public var userNumber: Int = 0

    /** `userNumberString[0x20]` (0x40). */
    public val userNumberString: ByteArray = ByteArray(STRING_SIZE)

    /** `userString[0x20]` (0x60). */
    public val userString: ByteArray = ByteArray(STRING_SIZE)

    /** `config` (u16, 0x80): `TALK_FLAG_*` bits. */
    public var config: Int = 0

    /** `unk82` (u8, 0x82). */
    public var unk82: Int = 0

    /** `invertedFlags` (u8, 0x83). */
    public var invertedFlags: Int = 0

    /** Writes the [SIZE] bytes the GBA keeps in RAM to [out] at [offset]. */
    public fun encode(out: ByteArray, offset: Int = 0) {
        put32(out, offset, str)
        put32(out, offset + 0x04, strBackup)
        put8(out, offset + 0x08, printColor)
        put8(out, offset + 0x09, lineActive)
        put8(out, offset + 0x0A, lines)
        put8(out, offset + 0x0B, topTextNum)
        put8(out, offset + 0x0C, xText)
        put8(out, offset + 0x0D, yText)
        put8(out, offset + 0x0E, activeWidth)
        put8(out, offset + 0x0F, speakingFaceSlot)
        put8(out, offset + 0x10, speakingWidth)
        put8(out, offset + 0x11, activeFaceSlot)
        put8(out, offset + 0x12, instantScroll)
        put8(out, offset + 0x13, printDelay)
        put8(out, offset + 0x14, printClock)
        put8(out, offset + 0x15, putLines)
        put8(out, offset + 0x16, mouthMoveEnabled)
        put8(out, offset + 0x17, faceSmileEnabled)
        for (i in 0 until FACE_COUNT) put32(out, offset + 0x18 + 4 * i, faces[i])
        put32(out, offset + 0x38, if (unk38 != null) 1 else 0)
        put32(out, offset + 0x3C, userNumber)
        userNumberString.copyInto(out, offset + 0x40)
        userString.copyInto(out, offset + 0x60)
        put16(out, offset + 0x80, config)
        put8(out, offset + 0x82, unk82)
        put8(out, offset + 0x83, invertedFlags)
    }

    /**
     * Reads every field except [unk38] (a callback has no bytes to restore; it is left as it is)
     * from the [SIZE] bytes at [offset] in [src].
     */
    public fun decode(src: ByteArray, offset: Int = 0) {
        str = u32(src, offset)
        strBackup = u32(src, offset + 0x04)
        printColor = u8(src, offset + 0x08)
        lineActive = u8(src, offset + 0x09)
        lines = u8(src, offset + 0x0A)
        topTextNum = u8(src, offset + 0x0B)
        xText = u8(src, offset + 0x0C)
        yText = u8(src, offset + 0x0D)
        activeWidth = u8(src, offset + 0x0E)
        speakingFaceSlot = s8(src, offset + 0x0F)
        speakingWidth = u8(src, offset + 0x10)
        activeFaceSlot = u8(src, offset + 0x11)
        instantScroll = s8(src, offset + 0x12)
        printDelay = s8(src, offset + 0x13)
        printClock = s8(src, offset + 0x14)
        putLines = u8(src, offset + 0x15)
        mouthMoveEnabled = u8(src, offset + 0x16)
        faceSmileEnabled = u8(src, offset + 0x17)
        for (i in 0 until FACE_COUNT) faces[i] = u32(src, offset + 0x18 + 4 * i)
        userNumber = u32(src, offset + 0x3C)
        src.copyInto(userNumberString, 0, offset + 0x40, offset + 0x40 + STRING_SIZE)
        src.copyInto(userString, 0, offset + 0x60, offset + 0x60 + STRING_SIZE)
        config = u16(src, offset + 0x80)
        unk82 = u8(src, offset + 0x82)
        invertedFlags = u8(src, offset + 0x83)
    }

    /** Size constants. */
    public companion object {
        /** `sizeof(struct TalkState)`. */
        public const val SIZE: Int = 0x84

        /** Entries of [faces]. */
        public const val FACE_COUNT: Int = 8

        /** Length of [userNumberString] and [userString]. */
        public const val STRING_SIZE: Int = 0x20
    }
}
