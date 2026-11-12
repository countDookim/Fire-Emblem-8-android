package fe8.game.scene

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcBoolFunc
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
 * The talk window of `src/scene.c` (`sTalkStateCore`, `sTalkFont`, `sTalkText`): set-up
 * ([initTalk], the flag/delay/colour setters, [talkOnInit], [talkPrepNextChar], the talk lock),
 * the interpreter ([talkOnIdle], [talkInterpret]) and the `gProcScr_Talk`, `gProcScr_TalkPause`
 * and `gProcScr_TalkWaitForInput` procs ([talkScript], [pauseScript], [waitForInputScript]).
 * `StartTalkExt` is not part of this class: the caller fills [state] and starts [talkScript].
 *
 * Everything outside these functions (faces, bubble, shift procs, skip listener, tilemap,
 * palettes, sound, input) goes through [host]. Nothing allocates per call.
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

    private val onEndFn = ProcFunc { talkOnEnd() }
    private val onInitFn = ProcFunc { talkOnInit() }
    private val onIdleFn = ProcFunc { talkOnIdle(it) }
    private val pauseIdleFn = ProcFunc { talkPauseOnIdle(it) }
    private val waitIdleFn = ProcFunc { talkWaitForInputOnIdle(it) }

    /** `gProcScr_Talk`: sleep one frame, init, then idle. */
    public val talkScript: ProcScript = ProcScript(
        "gProcScr_Talk",
        listOf(
            mark(PROC_MARK_TALK),
            setEndCb(onEndFn),
            sleep(1),
            call(onInitFn),
            repeat(onIdleFn),
            end(),
        ),
    )

    /** `gProcScr_TalkPause`; [TalkPauseState.frames] is `unk64`. */
    public val pauseScript: ProcScript = ProcScript(
        "gProcScr_TalkPause",
        listOf(mark(PROC_MARK_TALK), sleep(1), repeat(pauseIdleFn), end()),
        { TalkPauseState() },
    )

    /** `gProcScr_TalkWaitForInput`; the sprite position is in [TalkWaitState]. */
    public val waitForInputScript: ProcScript = ProcScript(
        "gProcScr_TalkWaitForInput",
        listOf(mark(PROC_MARK_TALK), sleep(8), repeat(waitIdleFn), sleep(1), end()),
        { TalkWaitState() },
    )

    /**
     * `ProcScr_TalkSpriteShiftClear`: scrolls the sprite text up one line.
     *
     * Transcribed from src/scene.c: ProcScr_TalkSpriteShiftClear (not verified: the script
     * position is checked per step in WorldMapEventHostTest, which cannot tag a script)
     */
    public val spriteShiftClearScript: ProcScript = ProcScript(
        "ProcScr_TalkSpriteShiftClear",
        listOf(
            mark(PROC_MARK_TALK),
            call(ProcFunc { sub80080D0(it) }),
            sleep(0),
            call(ProcFunc { sub8008108() }),
            sleep(1),
            end(),
        ),
    )

    /**
     * `ProcScr_0859160C`: blocks the shift-clear proc until [spriteShiftStepScript] is gone.
     *
     * Transcribed from src/scene.c: ProcScr_0859160C (not verified: see
     * [spriteShiftClearScript])
     */
    public val spriteShiftWaitScript: ProcScript = ProcScript(
        "ProcScr_0859160C",
        listOf(
            setEndCb(ProcFunc { sub8008F54() }),
            whileTrue(ProcBoolFunc { sub8008F3C() }),
            end(),
        ),
    )

    /**
     * `ProcScr_08591624`: moves the sprite text tiles up one pixel row per frame.
     *
     * Transcribed from src/scene.c: ProcScr_08591624 (not verified: see [spriteShiftClearScript])
     */
    public val spriteShiftStepScript: ProcScript = ProcScript(
        "ProcScr_08591624",
        listOf(
            call(ProcFunc { sub8008FAC(it.state as TalkSpriteShiftState) }),
            repeat(ProcFunc { sub8008FB4(it) }),
            end(),
        ),
        { TalkSpriteShiftState() },
    )

    /**
     * Sets up the sprite text at OBJ tile [chr] with [lines] lines and palette bank [palId] of
     * the object palettes.
     *
     * Transcribed from src/scene.c: InitSpriteTalk (not verified: the lines, texts and font are
     * checked against `sTalkState`, `sTalkText` and `sTalkFont` in WorldMapEventHostTest, but the
     * object palette writes are not seen in the dump: the palette mutation checks survived)
     */
    public fun initSpriteTalk(chr: Int, lines: Int, palId: Int) {
        text.initSpriteTextFont(font, OBJ_VRAM + (0x3FF and chr) * CHR_SIZE, palId)
        text.setTextFont(font)
        text.setTextFontGlyphs(TextSystem.GLYPHS_TALK)
        host.applyPalette(PAL_SPRITE_TALK, palId + 0x10)
        host.setPaletteColor(palId + 0x10, 4, rgb(7, 18, 28))
        host.setPaletteColor(palId + 0x10, 14, rgb(14, 13, 12))
        host.setPaletteColor(palId + 0x10, 15, rgb(31, 31, 31))
        state.lines = lines
        for (i in 0 until lines) {
            text.initSpriteText(texts[i])
            text.spriteTextClear(texts[i])
            texts[i].colorId = 0
            texts[i].x = 4
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = r or (g shl 5) or (b shl 10)

    /**
     * `Proc_StartBlocking(ProcScr_TalkSpriteShiftClear, proc)` of [talkSpritePrepNextChar]: the
     * line shift as a child of [parent] that blocks it.
     */
    private fun startSpriteShiftClear(parent: Proc) {
        engine.startBlocking(spriteShiftClearScript, parent)
    }

    /**
     * Ported from src/scene.c: sub_80080D0
     *
     * Starts the row shifter for the `TALK_FLAG_7` text (it fills the vacated row with 0 and
     * shifts 0x1C tile rows); the other branch (0x19 rows, fill `0x44444444`) is not run by any
     * capture and fails loudly.
     */
    public fun sub80080D0(proc: Proc) {
        check(checkTalkFlag(TalkFlags.FLAG_7) != 0) {
            "not verified: no capture shifts a sprite talk without TALK_FLAG_7"
        }
        sub8008F64(0x200, 0x1C, 0, proc)
    }

    /**
     * Ported from src/scene.c: sub_8008108
     *
     * After the shift: one line less is active and the second line is blanked (the
     * non-`TALK_FLAG_7` branch is not run by any capture and fails loudly).
     */
    public fun sub8008108() {
        state.lineActive--
        check(checkTalkFlag(TalkFlags.FLAG_7) != 0) {
            "not verified: no capture shifts a sprite talk without TALK_FLAG_7"
        }
        text.spriteTextClear(texts[1])
        texts[1].colorId = 0
        texts[1].x = 4
    }

    /** Ported from src/scene.c: sub_8008F3C */
    public fun sub8008F3C(): Boolean = engine.find(spriteShiftStepScript) != null

    /**
     * Transcribed from src/scene.c: sub_8008F54 (not verified: the shifter breaks itself after 16
     * frames, so ending it from here is not observable)
     */
    public fun sub8008F54() {
        engine.endEach(spriteShiftStepScript)
    }

    /**
     * Ported from src/scene.c: sub_8008F64
     *
     * Starts the row shifter on tree 0 and the proc that blocks [parent] until it is done.
     */
    public fun sub8008F64(chr: Int, b: Int, c: Int, parent: Proc) {
        val proc = engine.start(spriteShiftStepScript, 0)
        val shift = proc.state as TalkSpriteShiftState
        shift.vram = (0x3FF and chr) * CHR_SIZE + OBJ_VRAM
        shift.rows = b
        shift.fill = c
        engine.startBlocking(spriteShiftWaitScript, parent)
    }

    /** Ported from src/scene.c: sub_8008FAC */
    public fun sub8008FAC(shift: TalkSpriteShiftState) {
        shift.step = 0
    }

    /**
     * Ported from src/scene.c: sub_8008FB4
     *
     * Moves the first `rows * 8` words of each of four tile planes up by one word per frame (the
     * word at +7 comes from the next plane, the last plane takes the fill); breaks after 16
     * frames.
     */
    public fun sub8008FB4(proc: Proc) {
        val shift = proc.state as TalkSpriteShiftState
        var i = 0
        while (i < shift.rows * 8) {
            var j = 0
            while (j <= 0x300) {
                val base = shift.vram + (i + j) * 4
                for (k in 0 until 7) {
                    text.writeVramWord(base + 4 * k, text.readVramWord(base + 4 * (k + 1)))
                }
                val next = if (j < 0x300) text.readVramWord(base + 0x400) else shift.fill
                text.writeVramWord(base + 28, next)
                j += 0x100
            }
            i += 8
        }
        shift.step++
        if (shift.step > 15) engine.breakProc(proc)
    }

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
        val talkState = state
        if (!talkHasCorrectBubble() && talkState.activeFaceSlot != NO_FACE &&
            checkTalkFlag(TalkFlags.NOBUBBLE) == 0
        ) {
            talkState.activeWidth = bubbleWidth()
            host.clearTalkBubble()
            host.startTalkOpen(talkState.activeFaceSlot, proc)
            host.setTalkFaceLayer(talkState.activeFaceSlot, checkTalkFlag(TalkFlags.FLAG_4))
            return true
        }
        if (talkState.lineActive >= talkState.lines) {
            talkState.instantScroll = 0
            host.startTalkShiftClear(proc)
            return true
        }
        if (talkState.putLines == 0) {
            text.putText(
                talkTextByLine(talkState.lineActive),
                host.bg0Tilemap(),
                0x20 * (talkState.yText + talkState.lineActive * 2) + talkState.xText,
            )
            host.talkBgSync(1)
            talkState.putLines = 1
        }
        if (talkState.mouthMoveEnabled != 0) host.setTalkFaceMouthMove(talkState.activeFaceSlot)
        return false
    }

    /**
     * Transcribed from src/scene.c: TalkPrepNextChar (the `activeWidth` line only; not verified:
     * `GetStrTalkLen` is host-supplied, so the traced widths cannot be reproduced independently)
     */
    // Not @VerifiesPort: no oracle for GetStrTalkLen other than the traced width itself.
    private fun bubbleWidth(): Int {
        val talkState = state
        // The width comes from the pending string, or from the backup while one is nested.
        val measured = if (talkState.strBackup == 0) talkState.str else talkState.strBackup
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

    /**
     * Proc end callback of `gProcScr_Talk`: ends the skip listener and the shift-clear-all procs.
     *
     * Ported from src/scene.c: Talk_OnEnd
     */
    public fun talkOnEnd() {
        host.endTalkSkipListener()
        host.endTalkShiftClearAll()
    }

    /**
     * Sets the active face slot and returns it.
     *
     * Transcribed from src/scene.c: SetActiveTalkFace (not verified: a one-line store, only
     * covered through the state its callers produce)
     */
    // Not @VerifiesPort: a one-line store, covered through the interpreter's compared state.
    public fun setActiveTalkFace(slot: Int): Int {
        state.activeFaceSlot = slot and 0xFF
        return slot
    }

    /**
     * Per-frame body of `gProcScr_Talk`: waits for face moves and the print clock, then runs
     * [talkInterpret] and draws characters until a code asks to wait.
     *
     * Ported from src/scene.c: Talk_OnIdle
     */
    public fun talkOnIdle(proc: Proc) {
        val talkState = state
        if (host.isTalkFaceMoving()) return
        if (talkState.instantScroll == 0) {
            talkState.printClock = (talkState.printClock + 1).toByte().toInt()
            if (talkState.printClock < talkState.printDelay) return
        }
        talkState.printClock = 0
        while (true) {
            host.setTalkFaceNoMouthMove(talkState.activeFaceSlot)
            when (talkInterpret(proc)) {
                0 -> {
                    engine.breakProc(proc)
                    return
                }
                2 -> if (talkState.instantScroll == 0 && talkState.printDelay > 0) return
                3 -> {
                    talkState.printClock = talkState.printDelay
                    talkState.instantScroll = 0
                    return
                }
                else -> {
                    val prep = if (checkTalkFlag(TalkFlags.SPRITE) == 0) {
                        talkPrepNextChar(proc)
                    } else {
                        talkSpritePrepNextChar(proc)
                    }
                    if (prep) return
                    talkState.str = drawCharacter()
                    if (checkTalkFlag(TalkFlags.SILENT) == 0) playTypeSound()
                    if (talkState.instantScroll == 0 && talkState.printDelay > 0) return
                }
            }
        }
    }

    /** The sound branch after a drawn character of [talkOnIdle] (its C `break`s skip the sound). */
    private fun playTypeSound() {
        val talkState = state
        if (checkTalkFlag(TalkFlags.FLAG_7) != 0) {
            host.playSoundEffect(SONG_7A)
            return
        }
        if (host.textDisplaySpeed() == 1 && host.gameClock() and 1 == 0) return
        if (talkState.instantScroll != 0 && talkState.unk82 != 0) return
        talkState.unk82 = 1
        host.playSoundEffect(SONG_6E)
    }

    /** `sTalkState->str = Text_DrawCharacter(TALK_TEXT_BY_LINE(lineActive), str)`. */
    private fun drawCharacter(): Int {
        val talkState = state
        val stringBytes = strArray(talkState.str)
        val offset = strOffset(talkState.str)
        val nextOffset =
            text.drawCharacter(talkTextByLine(talkState.lineActive), stringBytes, offset)
        return talkState.str + (nextOffset - offset)
    }

    /**
     * `TalkPrepNextChar` for sprite talks: shifts when all lines are used, else marks the line
     * as put.
     *
     * Ported from src/scene.c: TalkSpritePrepNextChar
     */
    public fun talkSpritePrepNextChar(proc: Proc): Boolean {
        val talkState = state
        if (talkState.lineActive >= talkState.lines) {
            talkState.instantScroll = 0
            startSpriteShiftClear(proc)
            return true
        }
        if (talkState.putLines == 0) talkState.putLines = 1
        return false
    }

    /**
     * Executes control codes at the string cursor until a printable character (returns 1) or a
     * code that needs a frame boundary: 0 = end of text, 2 = continue this frame, 3 = wait.
     * Face codes call [host]; the interpreter keeps the cursor and the strings.
     *
     * Ported from src/scene.c: TalkInterpret
     */
    public fun talkInterpret(proc: Proc): Int {
        val talkState = state
        while (true) {
            val command = byteAt(talkState.str)
            if (command != CH_NORMAL_PRINT &&
                command != CH_FAST_PRINT &&
                command != CH_CLOSE_SPEECH_FAST
            ) break
            talkState.str++
            val len = host.getStrTalkLen(talkState.str, talkHasCorrectBubble())
            talkState.activeWidth = (2 + (len + 7) / 8) and 0xFF
        }
        val command = byteAt(talkState.str)
        when (command) {
            0x81 -> return interpretPauseCode(proc)
            CH_X -> {
                if (talkState.strBackup == 0) return 0
                talkState.str = talkState.strBackup + 2
                talkState.strBackup = 0
                return talkInterpret(proc)
            }
            CH_NL -> {
                if (talkState.putLines == 1 || talkState.lineActive == 1) {
                    talkState.lineActive = (talkState.lineActive + 1) and 0xFF
                }
                talkState.putLines = 0
                talkState.str++
                return 2
            }
            CH_2NL -> {
                if (checkTalkFlag(TalkFlags.FLAG_7) != 0) {
                    host.talkFlushAllLine()
                    talkState.str++
                } else if (checkTalkFlag(TalkFlags.INSTANTSHIFT) == 0) {
                    host.startTalkShiftClearAll(proc)
                } else {
                    clearTalkText()
                }
                talkState.str++
                return 3
            }
            CH_A -> {
                startTalkWaitForInput(
                    proc,
                    talkState.xText * 8 + talkTextByLine(talkState.lineActive).x + 4,
                    talkState.yText * 8 + talkState.lineActive * 16 + 8,
                )
                talkState.str++
                return 3
            }
            in CH_PAUSE8..CH_PAUSE64 -> {
                if (talkState.instantScroll != 0) {
                    talkState.str++
                    return 2
                }
                startPause(proc, PAUSE_DURATIONS[command - CH_PAUSE8])
                talkState.str++
                return 3
            }
            CH_CLOSE_SPEECH_SLOW -> {
                host.clearTalkBubble()
                talkState.str++
                return 3
            }
            CH_TOGGLE_MOUTH -> {
                talkState.mouthMoveEnabled = 1 - talkState.mouthMoveEnabled
                talkState.str++
                return 3
            }
            CH_TOGGLE_SMILE -> {
                talkState.faceSmileEnabled = 1 - talkState.faceSmileEnabled
                talkState.str++
                return 3
            }
            CH_LOAD_FACE -> return interpretLoadFace(proc)
            CH_CLEAR_FACE -> {
                if (talkHasCorrectBubble()) host.clearTalkBubble()
                host.startFaceFadeOut(talkState.faces[talkState.activeFaceSlot])
                talkState.faces[talkState.activeFaceSlot] = 0
                talkState.str++
                host.startTemporaryLock(proc, 16)
                return 3
            }
            CH_SEND_TO_BACK -> {
                setTalkFlag(TalkFlags.FLAG_4)
                talkState.str++
                return 3
            }
            CH_FAST_PRINT2 -> {
                clearTalkFlag(TalkFlags.FLAG_4)
                talkState.str++
                return 3
            }
            in CH_OPEN_FIRST..CH_OPEN_LAST -> {
                host.setTalkFaceNoMouthMove(talkState.activeFaceSlot)
                setActiveTalkFace(command - CH_OPEN_FIRST)
                talkState.str++
                return 3
            }
            CH_YES, CH_NO, CH_BUY_SELL, CH_SHOP_CONTINUE -> {
                host.startTalkChoice(
                    command >= CH_BUY_SELL,
                    talkTextByLine(talkState.lineActive),
                    host.bg0Tilemap(),
                    0x20 * (talkState.yText + talkState.lineActive * 2) + talkState.xText,
                    if (command == CH_YES || command == CH_BUY_SELL) 1 else 2,
                    talkState.printColor,
                    proc,
                )
                talkState.str++
                return 3
            }
            0x80 -> return interpretExtendedCode(proc)
        }
        return 1
    }

    /** `case 0x81` of `TalkInterpret`: the `0x81 0x40` short pause. */
    private fun interpretPauseCode(proc: Proc): Int {
        val talkState = state
        if (byteAt(talkState.str + 1) != 0x40) return 1
        talkState.str += 2
        talkTextByLine(talkState.lineActive).x += 6
        if (talkState.instantScroll != 0 || talkState.printDelay <= 0) return 2
        startPause(proc, PAUSE_DURATIONS[0])
        return 3
    }

    /** `case CHFE_L_LoadFace` of `TalkInterpret`: the run of face open/load codes. */
    private fun interpretLoadFace(proc: Proc): Int {
        val talkState = state
        while (true) {
            val code = byteAt(talkState.str)
            if (code in CH_OPEN_FIRST..CH_OPEN_LAST) {
                setActiveTalkFace(code - CH_OPEN_FIRST)
                talkState.str++
            } else if (code == CH_LOAD_FACE) {
                talkState.str++
                host.talkLoadFace(proc)
                talkState.str += 2
            } else {
                break
            }
        }
        return 3
    }

    /** `case 0x80` of `TalkInterpret`: the two-byte codes. */
    private fun interpretExtendedCode(proc: Proc): Int {
        val talkState = state
        talkState.str++
        val code = byteAt(talkState.str)
        when (code) {
            0x24 -> {
                talkState.unk38?.invoke(proc)
                talkState.str++
                return 3
            }
            0x21 -> {
                toggleTalkPrintColor()
                talkState.str++
                return talkInterpret(proc)
            }
            in 0x00..0x03 -> {
                talkState.str++
                talkState.printColor = byteAt(talkState.str)
                for (i in 0 until talkState.lines) texts[i].colorId = talkState.printColor
                talkState.str++
                return 3
            }
            0x25 -> {
                talkState.invertedFlags = 3 - (talkState.invertedFlags and 1)
                talkState.str++
                return 3
            }
            0x04 -> {
                lockTalk(proc)
                talkState.str++
                return 3
            }
            0x05 -> {
                host.numberToStringAscii(talkState.userNumber, talkState.userNumberString)
                talkState.str--
                talkState.strBackup = talkState.str
                talkState.str = STATE_ADDRESS + USER_NUMBER_STRING
                return talkInterpret(proc)
            }
            0x20 -> {
                talkState.strBackup = talkState.str - 1
                talkState.str = host.tacticianName()
                return talkInterpret(proc)
            }
            0x06 -> {
                talkState.str--
                talkState.strBackup = talkState.str
                talkState.str = STATE_ADDRESS + USER_STRING
                return talkInterpret(proc)
            }
            in 0x0A..0x11 -> {
                host.moveTalkFace(talkState.activeFaceSlot, code - 10)
                setActiveTalkFace(code - 10)
                talkState.str++
                return 3
            }
            0x07, 0x08 -> {
                talkState.str++
                return 3
            }
            in 0x16..0x1B -> {
                talkState.str++
                host.setFaceBlinkControl(
                    talkState.faces[talkState.activeFaceSlot],
                    BLINK_MODES[code - 0x16],
                )
                return 3
            }
            in 0x1C..0x1F -> {
                talkState.str++
                host.setFaceEyes(talkState.faces[talkState.activeFaceSlot], EYE_MODES[code - 0x1C])
                return 3
            }
        }
        return 0
    }

    /** `Proc_StartBlocking(gProcScr_TalkPause, proc)` plus `unk64 = frames`. */
    private fun startPause(proc: Proc, frames: Int) {
        val child = engine.startBlocking(pauseScript, proc) ?: return
        (child.state as TalkPauseState).frames = frames
    }

    /**
     * Idle callback of `gProcScr_TalkPause`: counts [TalkPauseState.frames] down, then breaks.
     *
     * Transcribed from src/scene.c: TalkPause_OnIdle (not verified: the trace has no pause code)
     */
    // Not @VerifiesPort: no TalkPause proc exists in the traced talk.
    public fun talkPauseOnIdle(proc: Proc) {
        val pauseState = proc.state as TalkPauseState
        if (pauseState.frames == 0) {
            engine.breakProc(proc)
            return
        }
        pauseState.frames--
    }

    /**
     * Starts the blocking "press a key" arrow proc at pixel ([x], [y]).
     *
     * Ported from src/scene.c: StartTalkWaitForInput
     */
    public fun startTalkWaitForInput(parent: Proc, x: Int, y: Int) {
        val child = engine.startBlocking(waitForInputScript, parent) ?: return
        val waitState = child.state as TalkWaitState
        waitState.x = x
        waitState.y = y
        waitState.unk68 = 0
    }

    /**
     * Idle callback of `gProcScr_TalkWaitForInput`: draws the arrow and breaks on a D-pad, A or B
     * press.
     *
     * Ported from src/scene.c: TalkWaitForInput_OnIdle
     */
    public fun talkWaitForInputOnIdle(proc: Proc) {
        val waitState = proc.state as TalkWaitState
        val frame = (host.gameClock() ushr 1) and 0xF
        if (checkTalkFlag(TalkFlags.FLAG_7) == 0) {
            host.putSprite(2, waitState.x, waitState.y, frame, 4)
        } else {
            host.putSprite(0, waitState.x, waitState.y, frame, 0xB2BF)
        }
        if (host.newKeys() and (DPAD_ANY or A_BUTTON or B_BUTTON) != 0) engine.breakProc(proc)
    }

    /**
     * Empties the three lines without touching the background.
     *
     * Transcribed from src/scene.c: ClearTalkText (not verified: only reached by the `[2NL]`
     * code with `TALK_FLAG_INSTANTSHIFT`, which the trace does not contain)
     */
    // Not @VerifiesPort: no [2NL] code in the traced message.
    public fun clearTalkText() {
        val talkState = state
        talkState.lineActive = 0
        talkState.unk82 = 0
        talkState.putLines = 0
        talkState.topTextNum = 0
        for (i in 0 until talkState.lines) {
            text.clearText(texts[i])
            texts[i].colorId = talkState.printColor
        }
    }

    /**
     * Blanks background 0 and the three lines and resets the line cursor (what closing the
     * bubble does to the text).
     *
     * Ported from src/scene.c: ClearPutTalkText
     */
    public fun clearPutTalkText() {
        val talkState = state
        host.bg0Tilemap().fill(0)
        host.talkBgSync(1)
        talkState.lineActive = 0
        talkState.unk82 = 0
        talkState.putLines = 0
        talkState.topTextNum = 0
        for (i in 0 until talkState.lines) {
            text.clearText(texts[i])
            text.clearText(texts[i])
            texts[i].colorId = talkState.printColor
        }
    }

    /** The byte at GBA address [addr] of the string memory. */
    private fun byteAt(addr: Int): Int = strArray(addr)[strOffset(addr)].toInt() and 0xFF

    /** The array that holds the string at GBA address [addr]. */
    private fun strArray(addr: Int): ByteArray = when (addr - STATE_ADDRESS) {
        in USER_NUMBER_STRING until USER_STRING -> state.userNumberString
        in USER_STRING until USER_STRING + TalkState.STRING_SIZE -> state.userString
        else -> host.stringArray(addr)
    }

    /** Offset of [addr] in [strArray]. */
    private fun strOffset(addr: Int): Int = when (addr - STATE_ADDRESS) {
        in USER_NUMBER_STRING until USER_STRING -> addr - STATE_ADDRESS - USER_NUMBER_STRING
        in USER_STRING until USER_STRING + TalkState.STRING_SIZE ->
            addr - STATE_ADDRESS - USER_STRING
        else -> host.stringOffset(addr)
    }

    /** `TALK_TEXT_BY_LINE(line)`. */
    private fun talkTextByLine(line: Int): Text =
        texts[(line + state.topTextNum) % state.lines]

    /** Addresses of the FE8U (US ROM) talk assets and the control-code values. */
    public companion object {
        /** `sTalkStateCore` (IWRAM). */
        public const val STATE_ADDRESS: Int = 0x03000048

        /** `PROC_MARK_TALK`. */
        public const val PROC_MARK_TALK: Int = 5

        private const val USER_NUMBER_STRING = 0x40
        private const val USER_STRING = 0x60
        private const val SONG_6E = 0x6E
        private const val SONG_7A = 0x7A
        private const val DPAD_ANY = 0x00F0
        private const val A_BUTTON = 1
        private const val B_BUTTON = 2
        private const val CH_X = 0x00
        private const val CH_NL = 0x01
        private const val CH_2NL = 0x02
        private const val CH_A = 0x03
        private const val CH_PAUSE8 = 0x04
        private const val CH_PAUSE64 = 0x07
        private const val CH_OPEN_FIRST = 0x08
        private const val CH_OPEN_LAST = 0x0F
        private const val CH_LOAD_FACE = 0x10
        private const val CH_CLEAR_FACE = 0x11
        private const val CH_NORMAL_PRINT = 0x12
        private const val CH_FAST_PRINT = 0x13
        private const val CH_CLOSE_SPEECH_FAST = 0x14
        private const val CH_CLOSE_SPEECH_SLOW = 0x15
        private const val CH_TOGGLE_MOUTH = 0x16
        private const val CH_TOGGLE_SMILE = 0x17
        private const val CH_YES = 0x18
        private const val CH_NO = 0x19
        private const val CH_BUY_SELL = 0x1A
        private const val CH_SHOP_CONTINUE = 0x1B
        private const val CH_SEND_TO_BACK = 0x1C
        private const val CH_FAST_PRINT2 = 0x1D

        /** `gTalkPauseDurations`. */
        private val PAUSE_DURATIONS = intArrayOf(8, 16, 32, 64)

        /** `SetFaceBlinkControl` modes of codes 0x80 0x16..0x1B. */
        private val BLINK_MODES = intArrayOf(0, 1, 3, 2, 4, 5)

        /** `sub_80064D4` modes of codes 0x80 0x1C..0x1F. */
        private val EYE_MODES = intArrayOf(0, 2, 3, 4)
        /** `Img_TalkBubble` (LZ77 tiles). */
        public const val IMG_TALK_BUBBLE: Int = 0x089E8238

        /** `Pal_TalkBubble`. */
        public const val PAL_TALK_BUBBLE: Int = 0x089E84D4

        private const val TEXT_COUNT = 3
        private const val VRAM = 0x06000000
        private const val OBJ_VRAM = 0x06010000

        /** `gUnknown_0859EF20`: the sprite talk palette. */
        private const val PAL_SPRITE_TALK = 0x0859EF20
        private const val BUBBLE_DEST = 0x06000200
        private const val CHR_SIZE = 0x20
        private const val NO_FACE = 0xFF
    }
}

/** Proc fields of `gProcScr_TalkPause`. */
public class TalkPauseState {
    /** Frames left to wait before resuming dialogue (`unk64`). */
    public var frames: Int = 0
}

/** Proc fields of `gProcScr_TalkWaitForInput`. */
public class TalkWaitState {
    /** Arrow prompt X coordinate in pixels (`unk64`). */
    public var x: Int = 0

    /** Arrow prompt Y coordinate in pixels (`unk66`). */
    public var y: Int = 0

    /** Arrow state field (`unk68`). */
    public var unk68: Int = 0
}

/** Proc fields of `ProcScr_08591624` (`struct TalkDebugProc`). */
public class TalkSpriteShiftState {
    /** VRAM address of the first tile row (`unk_4c`). */
    public var vram: Int = 0

    /** Tile rows to shift (`unk_54`). */
    public var rows: Int = 0

    /** Word shifted in at the bottom (`unk_58`). */
    public var fill: Int = 0

    /** Frames run (`unk_64`). */
    public var step: Int = 0
}
