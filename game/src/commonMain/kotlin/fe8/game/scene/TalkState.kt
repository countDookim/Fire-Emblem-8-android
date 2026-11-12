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
    /** GBA address of the next message byte (`str`, 0x00). */
    public var str: Int = 0

    /** GBA address to resume at after a nested string, or 0 when not nested (`strBackup`, 0x04). */
    public var strBackup: Int = 0

    /**
     * Colour index the dialogue is drawn with (`printColor`, u8, 0x08): 1 from `StartTalkExt`, 4
     * after `[ToggleRed]` (`sub_8006F00`); the code `0x80 0x00..0x03 n` sets it to `n`.
     */
    public var printColor: Int = 0

    /**
     * Zero-based line the text is being drawn on (`lineActive`, u8, 0x09). It reaches [lines]
     * when every line is used, which makes `TalkPrepNextChar` start a line shift.
     */
    public var lineActive: Int = 0

    /** Number of text lines (`Text` strips) in use, as set by `InitTalk` (`lines`, u8, 0x0A). */
    public var lines: Int = 0

    /**
     * Index of the `Text` strip that is currently the first line (`topTextNum`, u8, 0x0B): the
     * strips are used cyclically, so a line shift only adds one to it (`TALK_TEXT_BY_LINE`).
     */
    public var topTextNum: Int = 0

    /**
     * Tile column on BG0 where the text starts (`xText`, u8, 0x0C): the argument of
     * `StartTalkExt`, replaced by the bubble's column plus 1 when `PutTalkBubble` places a bubble.
     */
    public var xText: Int = 0

    /** Tile row on BG0 where the text starts (`yText`, u8, 0x0D); set like [xText]. */
    public var yText: Int = 0

    /**
     * Width in tiles the bubble needs for the text to come (`activeWidth`, u8, 0x0E): 2 plus the
     * text width in pixels rounded up to tiles, from `GetStrTalkLen`.
     */
    public var activeWidth: Int = 0

    /**
     * Talk face slot (0..7) the open bubble belongs to, or -1 when no bubble is open
     * (`speakingFaceSlot`, s8, 0x0F).
     */
    public var speakingFaceSlot: Int = 0

    /** [activeWidth] at the time the open bubble was opened (`speakingWidth`, u8, 0x10). */
    public var speakingWidth: Int = 0

    /**
     * Talk face slot (0..7) of the character who speaks next, set by the `[Open*]` and `[Move*]`
     * codes, or 0xFF if none (`activeFaceSlot`, u8, 0x11).
     */
    public var activeFaceSlot: Int = 0

    /**
     * Non-zero while the text is printed without per-character delay (`instantScroll`, s8, 0x12):
     * set by a D-pad, A or B press unless `TALK_FLAG_NOFAST`, cleared after any waiting control
     * code and at line shifts.
     */
    public var instantScroll: Int = 0

    /**
     * Frames between two printed characters (`printDelay`, s8, 0x13): `GetTextDisplaySpeed` (the
     * text-speed option: 8, 4, 1 or 0 frames) at `StartTalkExt`.
     */
    public var printDelay: Int = 0

    /** Frames counted since the last printed character (`printClock`, s8, 0x14). */
    public var printClock: Int = 0

    /**
     * 1 once the active line's `Text` was put on the BG0 tilemap (`putLines`, u8, 0x15); `[NL]`
     * resets it so the next line is put when its first character is drawn.
     */
    public var putLines: Int = 0

    /** 1 if the speaking face's mouth moves while text prints (`mouthMoveEnabled`, u8, 0x16). */
    public var mouthMoveEnabled: Int = 0

    /** 1 if the faces use their smiling mouth set (`faceSmileEnabled`, u8, 0x17). */
    public var faceSmileEnabled: Int = 0

    /**
     * The `FaceProc` of each of the 8 talk face slots, 0 for none (`faces[8]`, 0x18). In this
     * port a face is the handle [TalkFace.FACE_HANDLE] plus its face slot.
     */
    public val faces: IntArray = IntArray(FACE_COUNT)

    /**
     * Callback run once when the control code `0x80 0x24` is read (`unk38`, 0x38); set by
     * `sub_8006ABC`. Its purpose is unknown.
     */
    public var unk38: ProcFunc? = null

    /**
     * Number that the `[G]` code (`0x80 0x05`) prints, set by `SetTalkNumber` (`userNumber`, 0x3C).
     */
    public var userNumber: Int = 0

    /** The decimal text of [userNumber] that `[G]` reads as a nested string (0x40). */
    public val userNumberString: ByteArray = ByteArray(STRING_SIZE)

    /**
     * String a script supplies with `SetTalkUnkStr`, read as a nested string by `0x80 0x06` (0x60).
     */
    public val userString: ByteArray = ByteArray(STRING_SIZE)

    /** Bitfield of `TALK_FLAG_*` configuration flags (`config`, u16, 0x80). */
    public var config: Int = 0

    /**
     * 1 after the print blip (`SONG_6E`) has played (`unk82`, u8, 0x82): while [instantScroll] is
     * on no further blip plays. Cleared whenever the text is cleared and by `StartTalkExt`.
     */
    public var unk82: Int = 0

    /**
     * Inverted-colour state (`invertedFlags`, u8, 0x83), toggled by `[ToggleColorInvert]`
     * (`0x80 0x25`): bit 0 = inverted palettes wanted (and no bubble tail drawn), bit 1 = palette
     * swap pending, applied by the next `PutTalkBubble`.
     */
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
        /** `sizeof(struct TalkState)` (include/scene.h). */
        public const val SIZE: Int = 0x84

        /** Entries of [faces]. */
        public const val FACE_COUNT: Int = 8

        /** Length of [userNumberString] and [userString]. */
        public const val STRING_SIZE: Int = 0x20
    }
}
