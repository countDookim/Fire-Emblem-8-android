package fe8.game.scene

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.text.Font
import gbafe.text.Text
import gbafe.text.TextSystem

/** `TALK_FLAG_*` bits of [TalkState.config] (include/scene.h). */
public object TalkFlags {
    /** `TALK_FLAG_INSTANTSHIFT`. */
    public const val INSTANTSHIFT: Int = 1 shl 0

    /** `TALK_FLAG_NOBUBBLE`. */
    public const val NOBUBBLE: Int = 1 shl 1

    /** `TALK_FLAG_NOSKIP`. */
    public const val NOSKIP: Int = 1 shl 2

    /** `TALK_FLAG_NOFAST`. */
    public const val NOFAST: Int = 1 shl 3

    /** `TALK_FLAG_4`. */
    public const val FLAG_4: Int = 1 shl 4

    /** `TALK_FLAG_SPRITE`. */
    public const val SPRITE: Int = 1 shl 5

    /** `TALK_FLAG_SILENT`. */
    public const val SILENT: Int = 1 shl 6

    /** `TALK_FLAG_7`. */
    public const val FLAG_7: Int = 1 shl 7

    /** `TALK_FLAG_8`. */
    public const val FLAG_8: Int = 1 shl 8
}

/**
 * The talk window state and set-up half of `src/scene.c` (`sTalkStateCore`, `sTalkFont`,
 * `sTalkText`): [initTalk], the flag/delay/colour setters, [talkOnInit], [talkPrepNextChar] and
 * the talk lock. The interpreter (`TalkInterpret`, `Talk_OnIdle`) is not part of this class yet.
 *
 * Everything outside these functions (faces, bubble, tilemap, palettes) goes through [host].
 * Nothing allocates per call.
 */
public class Talk(
    private val text: TextSystem,
    private val engine: ProcEngine,
    private val host: TalkHost,
) {
    /** `sTalkState` / `sTalkStateCore`. */
    public val state: TalkState = TalkState()

    /** `sTalkFont`. */
    public val font: Font = Font()

    /** `sTalkText[3]`. */
    public val texts: Array<Text> = Array(TEXT_COUNT) { Text() }

    /** `gProcScr_TalkLock` (0x08591388): a single `PROC_BLOCK`. */
    public val lockScript: ProcScript = ProcScript("gProcScr_TalkLock", listOf(block(), end()))

    /**
     * Sets up the talk font at tile [chr] of background 0, [lines] text lines and, when
     * [unpackBubble], the speech-bubble tiles (background 1) and palette 3.
     *
     * Ported from src/scene.c: InitTalk
     */
    public fun initTalk(chr: Int, lines: Int, unpackBubble: Boolean) {
        text.initTextFont(
            font,
            VRAM + host.backgroundTileDataOffset(0) + (0x3FF and chr) * CHR_SIZE,
            chr,
            2,
        )
        setInitTalkTextFont()
        state.lines = lines
        for (i in 0 until lines) {
            text.initText(texts[i], 30)
            texts[i].colorId = 1 // Text_SetColor
        }
        if (unpackBubble) {
            host.decompressToVram(IMG_TALK_BUBBLE, host.backgroundTileDataOffset(1) + BUBBLE_DEST)
            host.applyPalette(PAL_TALK_BUBBLE, 3)
        }
        clearTalkFaceRefs()
    }

    /**
     * Ported from src/scene.c: ClearTalkFaceRefs
     */
    public fun clearTalkFaceRefs() {
        for (i in 0 until TalkState.FACE_COUNT) state.faces[i] = 0
    }

    /**
     * Makes [font] the active font with the talk palette and glyphs.
     *
     * Ported from src/scene.c: SetInitTalkTextFont
     */
    public fun setInitTalkTextFont() {
        text.setTextFont(font)
        text.initTalkTextFont()
    }

    /**
     * Proc start callback of `gProcScr_Talk`: loads the UI gfx and homes backgrounds 0 and 1
     * unless the talk is a sprite talk, then starts the skip listener.
     *
     * Ported from src/scene.c: Talk_OnInit
     */
    public fun talkOnInit() {
        if (checkTalkFlag(TalkFlags.SPRITE) == 0) {
            host.loadObjUiGfx()
            host.setBgPosition(0, 0, 0)
            host.setBgPosition(1, 0, 0)
        }
        host.startTalkSkipListener()
    }

    /**
     * Before the next character is drawn: opens the bubble when the speaking face or width is out
     * of date (returns true), starts the line shift when all lines are used (true), otherwise puts
     * the active line on background 0 once and starts the mouth (false, the caller draws). It
     * never moves the string cursor or the pen; those belong to the interpreter and the draw call.
     *
     * Ported from src/scene.c: TalkPrepNextChar
     */
    public fun talkPrepNextChar(proc: Proc): Boolean {
        val s = state
        if (!talkHasCorrectBubble() && s.activeFaceSlot != NO_FACE &&
            checkTalkFlag(TalkFlags.NOBUBBLE) == 0
        ) {
            s.activeWidth = bubbleWidth()
            host.clearTalkBubble()
            host.startTalkOpen(s.activeFaceSlot, proc)
            host.setTalkFaceLayer(s.activeFaceSlot, checkTalkFlag(TalkFlags.FLAG_4))
            return true
        }
        if (s.lineActive >= s.lines) {
            s.instantScroll = 0
            host.startTalkShiftClear(proc)
            return true
        }
        if (s.putLines == 0) {
            text.putText(
                talkTextByLine(s.lineActive),
                host.bg0Tilemap(),
                0x20 * (s.yText + s.lineActive * 2) + s.xText,
            )
            host.talkBgSync(1)
            s.putLines = 1
        }
        if (s.mouthMoveEnabled != 0) host.setTalkFaceMouthMove(s.activeFaceSlot)
        return false
    }

    /**
     * Transcribed from src/scene.c: TalkPrepNextChar (the `activeWidth` line only; not verified:
     * `GetStrTalkLen` is host-supplied, so the traced widths cannot be reproduced independently)
     */
    // Not @VerifiesPort: no oracle for GetStrTalkLen other than the traced width itself.
    private fun bubbleWidth(): Int {
        val s = state
        // The width comes from the pending string, or from the backup while one is nested.
        val measured = if (s.strBackup == 0) s.str else s.strBackup
        return 2 + (host.getStrTalkLen(measured, false) + 7) / 8
    }

    /**
     * True when the open bubble already belongs to the active face at the active width.
     *
     * Ported from src/scene.c: TalkHasCorrectBubble
     */
    public fun talkHasCorrectBubble(): Boolean =
        state.speakingFaceSlot == state.activeFaceSlot && state.speakingWidth == state.activeWidth

    /**
     * Transcribed from src/scene.c: SetTalkLines (not verified: no traced call)
     */
    // Not @VerifiesPort: the run never calls it; lines only change through InitTalk.
    public fun setTalkLines(lines: Int) {
        state.lines = lines
    }

    /**
     * Transcribed from src/scene.c: ClearAllTalkFlags (not verified: config stays 0 in the run)
     */
    // Not @VerifiesPort: TalkState.config is 0 for the whole traced talk, so no flag is ever set
    // or cleared.
    public fun clearAllTalkFlags() {
        state.config = 0
    }

    /**
     * Transcribed from src/scene.c: SetTalkFlag (not verified: config stays 0 in the run)
     */
    // Not @VerifiesPort: see clearAllTalkFlags.
    public fun setTalkFlag(flag: Int) {
        state.config = (state.config or flag) and 0xFFFF
    }

    /**
     * Transcribed from src/scene.c: sub_8006ABC (not verified: unk38 stays NULL in the run)
     */
    // Not @VerifiesPort: unk38 is NULL for the whole traced talk.
    public fun setUnk38Callback(func: ProcFunc?) {
        state.unk38 = func
    }

    /**
     * Transcribed from src/scene.c: ClearTalkFlag (not verified: config stays 0 in the run)
     */
    // Not @VerifiesPort: see clearAllTalkFlags.
    public fun clearTalkFlag(flag: Int) {
        state.config = state.config and flag.inv() and 0xFFFF
    }

    /**
     * Transcribed from src/scene.c: CheckTalkFlag (not verified: config stays 0 in the run, so
     * every traced check is 0 whatever the mask)
     */
    // Not @VerifiesPort: see clearAllTalkFlags.
    public fun checkTalkFlag(flag: Int): Int = state.config and flag

    /**
     * Transcribed from src/scene.c: SetTalkPrintDelay (not verified: printDelay is only set by
     * StartTalkExt in the run)
     */
    // Not @VerifiesPort: no traced call.
    public fun setTalkPrintDelay(printDelay: Int) {
        state.printDelay = printDelay.toByte().toInt()
        if (state.printDelay < 0) state.printDelay = 0
    }

    /**
     * Transcribed from src/scene.c: SetTalkPrintColor (not verified: printColor stays 1 in the
     * run)
     */
    // Not @VerifiesPort: no traced call.
    public fun setTalkPrintColor(color: Int) {
        state.printColor = color and 0xFF
        for (i in 0 until state.lines) texts[i].colorId = state.printColor
    }

    /**
     * Transcribed from src/scene.c: sub_8006F00 (not verified: printColor stays 1 in the run)
     */
    // Not @VerifiesPort: no traced call.
    public fun toggleTalkPrintColor() {
        val next = if (state.printColor == 1) 4 else 1
        for (i in 0 until state.lines) talkTextByLine(i).colorId = next
        state.printColor = next
    }

    /**
     * Transcribed from src/scene.c: LockTalk (not verified: no TalkLock proc in the run)
     */
    // Not @VerifiesPort: the traced procs never contain gProcScr_TalkLock.
    public fun lockTalk(proc: Proc) {
        engine.startBlocking(lockScript, proc)
    }

    /**
     * Transcribed from src/scene.c: IsTalkLocked (not verified: no TalkLock proc in the run)
     */
    // Not @VerifiesPort: see lockTalk.
    public fun isTalkLocked(): Boolean = engine.find(lockScript) != null

    /**
     * Transcribed from src/scene.c: ResumeTalk (not verified: no TalkLock proc in the run)
     */
    // Not @VerifiesPort: see lockTalk.
    public fun resumeTalk() {
        engine.endEach(lockScript)
    }

    /** `TALK_TEXT_BY_LINE(line)`. */
    private fun talkTextByLine(line: Int): Text =
        texts[(line + state.topTextNum) % state.lines]

    /** Addresses of the FE8U (US ROM) talk assets. */
    public companion object {
        /** `Img_TalkBubble` (LZ77 tiles). */
        public const val IMG_TALK_BUBBLE: Int = 0x089E8238

        /** `Pal_TalkBubble`. */
        public const val PAL_TALK_BUBBLE: Int = 0x089E84D4

        private const val TEXT_COUNT = 3
        private const val VRAM = 0x06000000
        private const val BUBBLE_DEST = 0x06000200
        private const val CHR_SIZE = 0x20
        private const val NO_FACE = 0xFF
    }
}
