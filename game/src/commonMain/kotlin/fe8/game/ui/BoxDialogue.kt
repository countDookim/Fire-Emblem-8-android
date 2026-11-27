package fe8.game.ui

import fe8.game.RomView
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.render.SpriteLayers
import gbafe.text.Font
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/** Per-proc state of `gUnknown_08A01800`: the box text size handed to the text proc. */
public class BoxDialogue8A01800State {
    /** `unk_2c`: the text column (box width divided by 8). */
    public var unk2c: Int = 0

    /** `unk_30`: the text row count, 0 to 5. */
    public var unk30: Int = 0

    /** `unk_5c`: the message id. */
    public var msgId: Int = 0
}

/**
 * Per-proc state of `ProcScr_BoxDialogueDrawTextExt`: `struct ProcBoxDialogueDrawTextExt`, its
 * offsets placed by the capture of `ram_trace_newgame_easy` (`str` +0x2C, `unk_30` +0x30, the
 * five texts +0x34, `current_line` +0x48, `unk_4a`/`4c`/`4e` +0x4A.., `unk_50`/`51` +0x50,
 * `x_offset` +0x52, `unk_54`..`57` +0x54, `timer` +0x58, `unk_59` +0x59).
 */
public class BoxDialogueTextState {
    /** `str`: the message bytes (with [strPos] as the pointer into them). */
    public var str: ByteArray = ByteArray(0)

    /** The offset of `str` inside [str]. */
    public var strPos: Int = 0

    /** `unk_30`: the font the text is drawn with. */
    public var font: Font? = null

    /** `texts`: the strips of the lines. */
    public var texts: List<Text> = emptyList()

    /** `current_line`. */
    public var currentLine: Int = 0

    /** `unk_4a`: the frames left until the next character. */
    public var unk4a: Int = 0

    /** `unk_4c`: the frames between characters (the text speed). */
    public var unk4c: Int = 0

    /** `unk_4e`: the characters drawn per step. */
    public var unk4e: Int = 0

    /** `unk_50` and `unk_51`: the box position minus 8. */
    public var unk50: Int = 0
    /** `unk_51`. */
    public var unk51: Int = 0

    /** `x_offset`: the width of the current line up to its first `[A]` or `[X]` plus 2. */
    public var xOffset: Int = 0

    /** `unk_54` and `unk_55`: the text columns and rows; `unk_56` and `unk_57`: the next size. */
    public var unk54: Int = 0
    /** `unk_55`. */
    public var unk55: Int = 0
    /** `unk_56`. */
    public var unk56: Int = 0
    /** `unk_57`. */
    public var unk57: Int = 0

    /** `timer` (+0x58). */
    public var timer: Int = 0

    /** `unk_59`: 1 while the red colour is toggled on. */
    public var unk59: Int = 0
}

/**
 * Per-proc state of `gProcScr_BoxDialogue`: `struct ProcBoxDialogue` (`x` at `+0x2C`, `y` at
 * `+0x30`, `msg` at `+0x34`, `unk_38` at `+0x38`, `unk_3c` at `+0x3C`, `pad_idx` at `+0x40`,
 * placed by the capture of `ram_trace_newgame_easy` f18230: the pointer `unk_3c` cannot share
 * `+0x3C` with the byte `pad_idx`, and `+0x40` holds `0xFF` there).
 */
public class BoxDialogueState {
    /** `x` and `y`: the box position. */
    public var x: Int = 0
    /** `y`. */
    public var y: Int = 0

    /** `msg`: the message id shown. */
    public var msg: Int = 0

    /** `unk_38`: set to 1 by the start function. */
    public var unk38: Int = 0

    /** `pad_idx`: the palette index, `0xFF` for the default. */
    public var padIdx: Int = 0

    /** `unk_3c`: the VRAM destination of the box graphics (0: default). */
    public var vramDst: Int = 0
}

/**
 * The dialogue box of the tutorial text (`src/helpbox.c`, `PC-11b2`): [startBoxDialogue]
 * (`sub_808AA04`) starts `gProcScr_BoxDialogue` and the box size comes from [getBoxDialogueSize].
 *
 * Stand-ins ([Hooks]): the graphics loading and the special-prefix substitution; the box frame
 * ([sub808ACFC]), the box size ([drawBoxDialogueText]), [mergeScript] and the teardown
 * ([sub808A9F0]) and the text proc (`ProcScr_BoxDialogueDrawTextExt`) are ported.
 *
 * @param engine proc scheduler running the box procs
 * @param textSystem text system whose active font measures the characters
 * @param messages the message table read for the box text
 * @param newKeys `gKeyStatusPtr->newKeys`
 * @param view ROM view holding the `gObject_*` sprite shapes of the box frame
 * @param layers sprite layer lists receiving the frame (layer 2)
 */
public class BoxDialogue(
    private val engine: ProcEngine,
    private val textSystem: TextSystem,
    private val messages: MsgStrings,
    private val newKeys: () -> Int = { 0 },
    private val view: RomView? = null,
    private val layers: SpriteLayers = SpriteLayers(),
) {
    /** The callees of the box procs that are not ported (no-ops until ported). */
    public class Hooks {
        /** `Decompress(src, dst)` of a box graphic: ROM address, VRAM address. */
        public var decompress: (Int, Int) -> Unit = { _, _ -> }

        /** `ApplyPalette(src, palId)`: ROM address of 16 colours, palette bank. */
        public var applyPalette: (Int, Int) -> Unit = { _, _ -> }

        /** `ClearAllTalkFlags()`. */
        public var clearAllTalkFlags: () -> Unit = {}

        /** `PlaySoundEffect(SONG_2E6)`: the box opening sound. */
        public var playOpenSound: () -> Unit = {}

        /** `StringInsertSpecialPrefixByCtrl()`: substitutes the control-code prefixes. */
        public var stringInsertSpecialPrefix: (message: ByteArray) -> ByteArray = { it.copyOf() }

        /** `PlaySoundEffect(songId)` of the text procs: `SONG_2E5` or `SONG_6E` per character. */
        public var playSound: (songId: Int) -> Unit = {}

        /** `GetTextDisplaySpeed()`. */
        public var textDisplaySpeed: () -> Int = { 0 }

        /** `GetGameClock()`. */
        public var gameClock: () -> Int = { 0 }

        /**
         * `StartTalkWaitForInput(proc, x, y)`: starts the blocking press-a-key arrow child of
         * [proc] at pixel (x, y); see `Talk.startTalkWaitForInput`.
         */
        public var startTalkWaitForInput: (proc: Proc, x: Int, y: Int) -> Unit = { _, _, _ -> }

        /** `SetFaceDisplayBitsById(0, GetFaceDisplayBitsById(0) & ~0x10 or | 0x10)`: [set]. */
        public var setFaceTalkBit: (set: Boolean) -> Unit = {}

        /** `PlaySoundEffect(SONG_2E7)`. */
        public var playCloseSound: () -> Unit = {}

        /** Observer of every [BoxDialogue.sub808ACFC] call: x, y, width, height (tests). */
        public var frameDrawn: ((Int, Int, Int, Int) -> Unit)? = null
    }

    /** The not-yet-ported callees. */
    public val hooks: Hooks = Hooks()

    /** `gBoxDialogueConf.unk_42`: the dialogue box config bits. */
    private var config: Int = 0

    /** `gBoxDialogueConf.font`. */
    public val font: Font = Font()

    /** `gBoxDialogueConf.texts`. */
    public val texts: List<Text> = List(TEXT_COUNT) { Text() }

    /** `gBoxDialogueConf.unk_40`: tile number of the box font plus its palette. */
    public var unk40: Int = 0
        private set

    /**
     * Sets the dialogue box config.
     *
     * Ported from src/helpbox.c: SetDialogueBoxConfig
     */
    public fun setDialogueBoxConfig(value: Int) {
        config = value and 0xFFFF
    }

    /**
     * Returns the dialogue box config.
     *
     * Ported from src/helpbox.c: GetDialogueBoxConfig
     */
    public fun getDialogueBoxConfig(): Int = config

    /** `ProcScr_TalkBoxIdle`: blocks until ended. */
    public val talkBoxIdleScript: ProcScript = ProcScript(
        "ProcScr_TalkBoxIdle",
        listOf(block(), end()),
    )

    /**
     * `gProcScr_BoxDialogue`: draws the box, waits for B or START, then plays the close sound.
     *
     * Ported from src/helpbox.c: gProcScr_BoxDialogue (the script position of every frame from
     * f18230 to the end at f18384 is verified, `BoxDialogueMergeDifferentialTest`; the jump to
     * label 2 on B or START is not run there)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_BoxDialogue",
        listOf(
            sleep(0),
            call(ProcFunc { sub808A848(it) }),
            label(0),
            repeat(ProcFunc { sub808A87C(it) }),
            label(1),
            block(),
            label(3),
            sleep(6),
            label(2),
            call(ProcFunc { sub808A8AC() }),
            sleep(10),
            end(),
        ),
        newState = { BoxDialogueState() },
    )

    /**
     * `ProcScr_BoxDialogueDrawTextExt`: draws the message character by character.
     *
     * Ported from src/helpbox.c: ProcScr_BoxDialogueDrawTextExt (the script position of every
     * frame f18238..f18385 is verified, `BoxDialogueMergeDifferentialTest`: the labels 0, 4 and
     * 2; the labels 1, 5 and 6 are not run)
     */
    public val drawTextExtScript: ProcScript = ProcScript(
        "ProcScr_BoxDialogueDrawTextExt",
        listOf(
            sleep(0),
            call(ProcFunc { sub808B09C(it) }),
            label(0),
            repeat(ProcFunc { boxDialogueInterpreterMain(it) }),
            goto(2),
            label(1),
            repeat(ProcFunc { sub808B788(it) }),
            label(4),
            repeat(ProcFunc { sub808B7B8(it) }),
            goto(0),
            label(5),
            repeat(ProcFunc { sub808B7B8(it) }),
            call(ProcFunc { sub808B804(it) }),
            goto(0),
            label(6),
            repeat(ProcFunc { sub808B870(it) }),
            goto(0),
            label(2),
            call(ProcFunc { sub808B844(it) }),
            end(),
        ),
        newState = { BoxDialogueTextState() },
    )

    /**
     * `gUnknown_08A01800`: waits six frames, then starts the text proc ([sub808B928]).
     *
     * Ported from src/helpbox.c: gUnknown_08A01800
     */
    public val unknown08A01800Script: ProcScript = ProcScript(
        "gUnknown_08A01800",
        listOf(
            sleep(6),
            call(ProcFunc { sub808B928(it) }),
            end(),
        ),
        newState = { BoxDialogue8A01800State() },
    )

    /**
     * `ProcScr_MergeBoxDialogue`: grows the box frame until [sub808A9F0] breaks it, then shrinks
     * it again and ends the idle proc.
     *
     * Ported from src/helpbox.c: ProcScr_MergeBoxDialogue (verified by the script position of
     * every frame f18231..f18378)
     */
    public val mergeScript: ProcScript = ProcScript(
        "ProcScr_MergeBoxDialogue",
        listOf(
            repeat(ProcFunc { mergeBoxDialogue1(it) }),
            call(ProcFunc { mergeBoxDialogue2(it) }),
            repeat(ProcFunc { mergeBoxDialogue3(it) }),
            end(),
        ),
        newState = { HelpBoxState() },
    )

    /**
     * Draws the frame of the box interpolated by [method] at the proc's timer.
     *
     * Ported from src/helpbox.c: sub_808A8E4
     */
    public fun sub808A8E4(proc: Proc, method: Int) {
        val s = proc.state as HelpBoxState
        val x = s.xBoxFinal
        val y = s.yBoxFinal
        val w = Interpolate.interpolate(method, s.wBoxInit, s.wBoxFinal, s.timer, s.timerMax)
        val h = Interpolate.interpolate(method, s.hBoxInit, s.hBoxFinal, s.timer, s.timerMax)
        s.xBox = x
        s.yBox = y
        sub808ACFC(x, y, w, h)
    }

    /**
     * Opening step of the merge proc: draws the frame and advances the timer up to its maximum.
     *
     * Ported from src/helpbox.c: MergeBoxDialogue1
     */
    public fun mergeBoxDialogue1(proc: Proc) {
        val s = proc.state as HelpBoxState
        sub808A8E4(proc, Interpolate.RCUBIC)
        if (s.timer < s.timerMax) s.timer++
    }

    /**
     * Starts the closing step: the box shrinks from the base size over a third of the time.
     *
     * Ported from src/helpbox.c: MergeBoxDialogue2
     */
    public fun mergeBoxDialogue2(proc: Proc) {
        val s = proc.state as HelpBoxState
        s.resetHelpBoxInitSize()
        s.timerMax /= 3
        s.timer = s.timerMax
    }

    /**
     * Closing step of the merge proc: draws the shrinking frame; when the timer runs out, ends
     * the proc's step and the idle proc.
     *
     * Ported from src/helpbox.c: MergeBoxDialogue3
     */
    public fun mergeBoxDialogue3(proc: Proc) {
        val s = proc.state as HelpBoxState
        sub808A8E4(proc, Interpolate.LINEAR)
        s.timer--
        if (s.timer < 0) {
            engine.breakProc(proc)
            engine.endEach(talkBoxIdleScript)
        }
    }

    /**
     * Clears the box text strips, ends the text procs and starts closing the merge procs.
     *
     * Ported from src/helpbox.c: sub_808A9F0
     */
    public fun sub808A9F0() {
        sub808BAA4()
        engine.breakEach(mergeScript)
    }

    /**
     * Clears the box text strips (config bit 0: [getDialogueBoxConfig] ushr 8 of them, else the
     * three or five of the yellow box) and ends the text procs.
     *
     * Partially ported from src/helpbox.c: sub_808BAA4 (verified for config 0x110 through the
     * strips 0, 1 and 4 of the `f18550` VRAM dump; the strips 2 and 3 are overwritten there, and
     * the config bit 0 arm is not run)
     */
    public fun sub808BAA4() {
        textSystem.setTextFont(font)
        if (getDialogueBoxConfig() and 1 == 0) {
            for (i in 0..2) textSystem.spriteTextDrawBackground(texts[i])
            if (getDialogueBoxConfig() and 0x10 != 0 && getDialogueBoxConfig() and 0x20 == 0) {
                textSystem.spriteTextDrawBackground(texts[3])
                textSystem.spriteTextDrawBackground(texts[4])
            }
        } else {
            for (i in 0 until (getDialogueBoxConfig() ushr 8 and 0xFF)) {
                textSystem.spriteTextDrawBackgroundExt(texts[i], 0)
            }
        }
        engine.endEach(drawTextExtScript)
        engine.endEach(unknown08A01800Script)
        textSystem.setTextFont(null)
    }

    /**
     * Ends every box dialogue proc.
     *
     * Transcribed from src/helpbox.c: sub_808BB74 (not verified: the capture window ends the
     * box through `sub_808A8AC`, never through this function)
     */
    public fun sub808BB74() {
        engine.endEach(script)
        engine.endEach(talkBoxIdleScript)
        engine.endEach(mergeScript)
        engine.endEach(drawTextExtScript)
        engine.endEach(unknown08A01800Script)
    }

    /**
     * Initialises the box graphics and draws the text.
     *
     * Partially ported from src/helpbox.c: sub_808A848 (the `pad_idx != 0xFF` arm is not exercised)
     */
    public fun sub808A848(proc: Proc) {
        val s = proc.state as BoxDialogueState
        if (s.padIdx == 0xFF) {
            initBoxDialogue(0, -1)
        } else {
            initBoxDialogue(s.vramDst, s.padIdx)
        }
        drawBoxDialogueText(s.x, s.y, s.msg)
    }

    /**
     * Loads the box graphics at [vramDst] (0: the default `0x06013000`) and palette [padIdx]
     * (negative: 5), claims the sprite text strips and sets `gBoxDialogueConf.unk_40`.
     *
     * Partially ported from src/helpbox.c: InitBoxDialogue (the config 0x110 path of the Easy
     * tutorial box is verified: font, strips, `unk_40`, palette; the graphics destinations, the
     * open sound, config bits 0x20 and 1 (the `Pal_Text` arm) are not)
     */
    public fun initBoxDialogue(vramDst: Int, padIdx: Int) {
        val dst = if (vramDst == 0) DEFAULT_VRAM else vramDst
        val pad = (if (padIdx < 0) 5 else padIdx).and(0xF) + 0x10
        val yellow = getDialogueBoxConfig() and 0x10 != 0
        if (yellow) {
            hooks.decompress(G_GFX_YELLOW_TEXT_BOX, dst + 0x360)
            hooks.decompress(G_GFX_YELLOW_TEXT_BOX2, dst + 0x760)
            hooks.decompress(G_GFX_YELLOW_TEXT_BOX3, dst + 0xB60)
            hooks.decompress(G_GFX_YELLOW_TEXT_BOX4, dst + 0xF80)
            hooks.decompress(G_GFX_YELLOW_TEXT_BOX5, dst + 0x1380)
        } else {
            hooks.decompress(G_GFX_HELP_TEXT_BOX, dst + 0x360)
            hooks.decompress(G_GFX_HELP_TEXT_BOX2, dst + 0x760)
            hooks.decompress(G_GFX_HELP_TEXT_BOX3, dst + 0xB60)
            hooks.decompress(G_GFX_HELP_TEXT_BOX4, dst + 0xF60)
            hooks.decompress(G_GFX_HELP_TEXT_BOX5, dst + 0x1360)
        }
        hooks.clearAllTalkFlags()
        if (getDialogueBoxConfig() and 1 == 0) {
            textSystem.initSpriteTextFont(font, dst, pad)
            for (i in 0..2) textSystem.initSpriteText(texts[i])
            if (yellow && getDialogueBoxConfig() and 0x20 == 0) {
                textSystem.initSpriteText(texts[3])
                textSystem.initSpriteText(texts[4])
            }
            textSystem.setTextFont(null)
            hooks.applyPalette(if (yellow) G_PAL_YELLOW_TEXT_BOX else G_PAL_HELP_TEXT_BOX, pad)
        } else {
            textSystem.initSpriteTextFont(font, dst, pad)
            for (i in 0 until (getDialogueBoxConfig() ushr 8 and 0xFF)) {
                textSystem.initSpriteText(texts[i])
            }
            textSystem.setTextFont(null)
            hooks.applyPalette(PAL_TEXT, pad)
        }
        unk40 = (((dst shl 17) ushr 22) + (pad and 0xF) * 0x1000) and 0xFFFF
        if (yellow) hooks.playOpenSound()
    }

    /**
     * Jumps to label 2 when B or START is pressed, unless the config has bit 1 or 7 set.
     *
     * Partially ported from src/helpbox.c: sub_808A87C (the not-pressed wait is verified; the
     * config 0x82 early return and the B/START arm are not exercised: no press in the window)
     */
    public fun sub808A87C(proc: Proc) {
        if (getDialogueBoxConfig() and 0x82 != 0) return
        if (newKeys() and (KEY_B or KEY_START) != 0) engine.goto(proc, 2)
    }

    /**
     * Plays the close sound if the config has bit 4 set, selects the system glyphs and runs
     * `sub_808A9F0`.
     *
     * Ported from src/helpbox.c: sub_808A8AC (verified at f18373 of `ram_trace_newgame_easy`: the
     * close sound once for config 0x110 and `sub_808A9F0` running; the glyph set is not compared)
     */
    public fun sub808A8AC() {
        if (getDialogueBoxConfig() and 0x10 != 0) hooks.playCloseSound()
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_SYSTEM)
        sub808A9F0()
    }

    /**
     * Ends every box dialogue, clears the config and starts the box proc at ([x], [y]) for message
     * [msgId], with its `ProcScr_TalkBoxIdle` companion; with [parent] the box blocks it,
     * otherwise it starts in tree 3.
     *
     * Ported from src/helpbox.c: sub_808AA04
     */
    public fun startBoxDialogue(x: Int, y: Int, msgId: Int, parent: Proc? = null): Proc {
        engine.endEach(script)
        setDialogueBoxConfig(0)
        val proc = if (parent == null) {
            engine.start(script, TREE)
        } else {
            engine.startBlocking(script, parent)!!
        }
        val s = proc.state as BoxDialogueState
        s.x = x
        s.y = y
        s.msg = msgId
        s.padIdx = 0xFF
        s.unk38 = 1
        engine.start(talkBoxIdleScript, 0)
        return proc
    }

    private val rom: RomView get() = view ?: error("BoxDialogue needs a RomView to draw")
    private val gObject32x16 by lazy { rom.objN(HelpBox.G_OBJECT_32X16) }
    private val gObject16x16 by lazy { rom.objN(HelpBox.G_OBJECT_16X16) }
    private val gObject8x16 by lazy { rom.objN(HelpBox.G_OBJECT_8X16) }
    private val gObject16x8 by lazy { rom.objN(HelpBox.G_OBJECT_16X8) }
    private val gObject8x8 by lazy { rom.objN(HelpBox.G_OBJECT_8X8) }

    /**
     * Starts the merge proc for message [msg] at ([x], [y]): sizes the box from the message,
     * places its final position and starts the text proc.
     *
     * Partially ported from src/helpbox.c: DrawBoxDialogueText (verified for config 0x110 by the
     * merge proc fields of `BoxDialogueMergeDifferentialTest`; the config bit 0 arm and the
     * special-prefix substitution (a [Hooks] stand-in) are not exercised)
     */
    public fun drawBoxDialogueText(x: Int, y: Int, msg: Int) {
        engine.endEach(mergeScript)
        val proc = engine.start(mergeScript, TREE)
        val s = proc.state as HelpBoxState
        s.xBoxInit = x
        s.yBoxInit = y
        s.resetHelpBoxInitSize()
        s.info = null
        s.timer = 0
        s.timerMax = if (getDialogueBoxConfig() and 1 != 0) 0 else 12
        s.item = 0
        s.msgId = msg
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_TALK)
        val str = hooks.stringInsertSpecialPrefix(messages.getStringFromIndex(msg))
        val (wInner, hInner) = getBoxDialogueSize(str, 0)
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_SYSTEM)
        setBoxDialogueSize(s, wInner, hInner)
        var px = x
        var py = y
        if (getDialogueBoxConfig() and 0x100 != 0) {
            px += (0xD8 - s.wBoxFinal) / 2
            py += (0x90 - s.hBoxFinal) / 2
        }
        sub808A7B0(s, px, py)
        sub808BAA4()
        sub808BA60(s.msgId, wInner, hInner)
    }

    /**
     * Sets the final box size: [w] rounded down to a multiple of 8 and [h].
     *
     * Ported from src/helpbox.c: SetBoxDialogueSize (verified through the box size of the merge
     * proc)
     */
    public fun setBoxDialogueSize(s: HelpBoxState, w: Int, h: Int) {
        s.wBoxFinal = w and 0xF8
        s.hBoxFinal = h
    }

    /**
     * Sets the final box position from ([x], [y]), keeping the box on the screen unless the config
     * has bit 6 set.
     *
     * Partially ported from src/helpbox.c: sub_808A7B0 (verified for config 0x110, the width
     * clamp and the config bit 0 and 6 arms are not exercised)
     */
    public fun sub808A7B0(s: HelpBoxState, x: Int, y: Int) {
        val ySpan = s.hBoxFinal + 0x10
        if (s.wBoxFinal >= 0xC0) s.wBoxFinal = 0xC0
        val xSpan = s.wBoxFinal + 0x10
        if (getDialogueBoxConfig() and 1 == 0) {
            s.xBoxFinal = x
            s.yBoxFinal = y + 8
            if (getDialogueBoxConfig() and 0x40 == 0) {
                if (s.xBoxFinal + xSpan > 0xF0) s.xBoxFinal = 0xF0 - xSpan
                if (s.yBoxFinal + ySpan > 0xA0) s.yBoxFinal = 0xA0 - 8 - ySpan
            }
            s.xBoxFinal += 8
            return
        }
        s.xBoxFinal = x
        s.yBoxFinal = y
    }

    /**
     * Starts the proc that begins the text six frames later, with the text size in [x] and [y]
     * (pixels) of message [msgId].
     *
     * Partially ported from src/helpbox.c: sub_808BA60 (verified for a size of two lines; the
     * clamps to 0 and 5 lines are not exercised)
     */
    public fun sub808BA60(msgId: Int, x: Int, y: Int) {
        val proc = engine.start(unknown08A01800Script, TREE)
        val s = proc.state as BoxDialogue8A01800State
        s.msgId = msgId
        s.unk2c = x / 8
        s.unk30 = (y / 16).coerceIn(0, 5)
    }

    /**
     * Puts the box frame of size [width] x [height] at ([x], [y]) on sprite layer 2: the filled
     * middle, the top and bottom edges, the sides and the corners.
     *
     * Partially ported from src/helpbox.c: sub_808ACFC (verified for config 0x110 by the layer 2
     * sprites of every 10th frame from f18240 to f18370; the config bit 0 arm and the
     * not-yellow side sprites are not exercised)
     */
    public fun sub808ACFC(x: Int, y: Int, width: Int, height: Int) {
        hooks.frameDrawn?.invoke(x, y, width, height)
        val sp14 = height shr 5
        val w = width.coerceIn(0x20, 0xC0)
        val h = height.coerceIn(0x10, 0x50)
        val base = unk40
        fun rowY(j: Int): Int = minOf(0x10 * (j + 1), h) - 0x10
        if (getDialogueBoxConfig() and 1 == 0) {
            val spriteHeight = (h + 0xF) / 0x10
            val spriteWidth = (w + 7) / 8 + 1
            var i = 0
            while (i < spriteWidth - 4) {
                for (j in spriteHeight downTo 0) {
                    layers.put(L, x + 8 * i, y + rowY(j), gObject32x16, base + i + j * 0x40)
                }
                i += 4
            }
            while (i < spriteWidth) {
                for (j in spriteHeight downTo 0) {
                    val obj = if (i < spriteWidth - 2) gObject16x16 else gObject8x16
                    layers.put(L, x + 8 * i, y + rowY(j), obj, base + i + j * 0x40)
                }
                if (i < spriteWidth - 2) i += 1
                i += 1
            }
            i = 0
            while (i < spriteWidth) {
                val l = 8 * i
                if (i < spriteWidth - 2) {
                    val top = base + 0x1B + (if ((i + 6) % 10 == 0) 2 else 0)
                    val bottom = base + 0x3B + (if ((i + 6) % 8 == 0) 2 else 0)
                    layers.put(L, x + l, y - 8, gObject16x8, top)
                    layers.put(L, x + l, y + h, gObject16x8, bottom)
                    i += 1
                } else {
                    layers.put(L, x + l, y - 8, gObject8x8, base + 0x1B)
                    layers.put(L, x + l, y + h, gObject8x8, base + 0x3B)
                }
                i += 1
            }
            val yellow = getDialogueBoxConfig() and 0x10 != 0
            for (j in spriteHeight downTo 0) {
                val k = rowY(j)
                val left = if (yellow) base + (if (j and 1 != 0) 0x7F else 0x5F) else base + 0x5F
                val right = if (yellow) base + (if (j == sp14) 0x7E else 0x1F) else base + 0x1F
                layers.put(L, x - 8, y + k, gObject8x16, left)
                layers.put(L, x + 8 * i, y + k, gObject8x16, right)
            }
            layers.put(L, x - 8, y - 8, gObject8x8, base + 0x5B)
            layers.put(L, x + 8 * i, y - 8, gObject8x8, base + 0x5C)
            layers.put(L, x - 8, y + h, gObject8x8, base + 0x5D)
            layers.put(L, x + 8 * i, y + h, gObject8x8, base + 0x5E)
        } else {
            val spriteWidth = (w + 0x1F) / 0x20
            val spriteHeight = getDialogueBoxConfig() / 0x100 - 1
            for (i in spriteWidth - 1 downTo 0) {
                for (j in spriteHeight downTo 0) {
                    layers.put(L, x + 0x20 * i, y + 0x10 * j, gObject32x16, base + 4 * i + j * 0x40)
                }
            }
        }
    }

    /**
     * Measures message [msg]: returns the (width, height) of its text box by scanning the control
     * codes and the glyph widths of the active font.
     *
     * Partially ported from src/helpbox.c: GetBoxDialogueSize (the box size of the tutorial
     * message is verified through `DrawBoxDialogueText`: glyph widths, [NL], the `0x80` skip and
     * the terminator; the `0x02`, `0x03`, `0x18`, `0x19` and `0x04..0x07` paths are not)
     */
    public fun getBoxDialogueSize(msg: Int): Pair<Int, Int> =
        getBoxDialogueSize(messages.getStringFromIndex(msg), 0)

    /** [getBoxDialogueSize] over the string at [start] of [str]. */
    public fun getBoxDialogueSize(str: ByteArray, start: Int): Pair<Int, Int> {
        val charWidth = IntArray(1)
        var w = 0
        var h = 16
        var wOut = 0
        var hOut = 0
        var i = start
        loop@ while (true) {
            when (str[i].toInt() and 0xFF) {
                0x12, 0x13, 0x14, 0x00 -> {
                    if (wOut < w) wOut = w
                    if (hOut < h) hOut = h
                    break@loop
                }
                0x80 -> i += 2
                0x01 -> {
                    h += 16
                    if (wOut < w) wOut = w
                    w = 0
                    i++
                }
                0x18, 0x19 -> {
                    w = 0
                    i++
                }
                0x04, 0x05, 0x06, 0x07 -> i++
                0x02 -> {
                    i++
                    if (hOut < h) hOut = h
                    h = 0
                    if (wOut < w) wOut = w
                    w = 0
                }
                0x03 -> {
                    i++
                    if (hOut < h) hOut = h
                    h = 0
                    if (wOut < w + 8) wOut = w + 8
                    w = 0
                }
                else -> {
                    i = textSystem.getCharTextLen(str, i, charWidth)
                    w += charWidth[0]
                }
            }
        }
        return Pair(wOut, hOut)
    }

    /**
     * Whether the box proc exists and has `unk_38` clear (the text then waits per character).
     *
     * Ported from src/helpbox.c: sub_808B904 (returns true where C returns 1)
     */
    public fun sub808B904(): Boolean {
        val proc = engine.find(script) ?: return true
        return (proc.state as BoxDialogueState).unk38 != 0
    }

    /**
     * Starts the text proc ([drawTextExtScript]) for the message of [proc]: sets the strip
     * colours, then the text speed and characters per step.
     *
     * Partially ported from src/helpbox.c: sub_808B928 (the config 0x110 arm; the config bit 0
     * arm colours and the `StringInsertSpecialPrefixByCtrl` substitution, a [Hooks] stand-in, are
     * not exercised)
     */
    public fun sub808B928(proc: Proc) {
        val s = proc.state as BoxDialogue8A01800State
        textSystem.setTextFont(font)
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_SYSTEM)
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_TALK)
        if (getDialogueBoxConfig() and 1 == 0) {
            for (i in 0..2) textSystem.setColor(texts[i], 6)
            if (getDialogueBoxConfig() and 0x10 != 0 && getDialogueBoxConfig() and 0x20 == 0) {
                textSystem.setColor(texts[3], 6)
                textSystem.setColor(texts[4], 6)
            }
        } else {
            for (i in 0 until (getDialogueBoxConfig() ushr 8 and 0xFF)) {
                textSystem.setColor(texts[i], 0)
            }
        }
        textSystem.setTextFont(null)
        engine.endEach(drawTextExtScript)
        val other = engine.start(drawTextExtScript, TREE)
        val t = other.state as BoxDialogueTextState
        t.font = font
        t.texts = texts
        t.currentLine = 0
        t.str = hooks.stringInsertSpecialPrefix(messages.getStringFromIndex(s.msgId))
        t.strPos = 0
        t.unk54 = s.unk2c
        t.unk55 = s.unk30
        if (sub808B904()) {
            t.unk4c = hooks.textDisplaySpeed()
            t.unk4e = if (t.unk4c != 0) 1 else 0x80
        } else {
            t.unk4c = 0
            t.unk4e = 0x80
        }
        t.unk4a = 0
    }

    /**
     * Records the box position and the width of the first line of the text.
     *
     * Partially ported from src/helpbox.c: sub_808B09C (the capture runs it once; `unk_50` and
     * `unk_51` are only stored)
     */
    public fun sub808B09C(proc: Proc) {
        val t = proc.state as BoxDialogueTextState
        val merge = engine.find(mergeScript)!!.state as HelpBoxState
        t.unk59 = 0
        t.unk50 = (merge.xBox - 8) and 0xFF
        t.unk51 = (merge.yBox - 8) and 0xFF
        t.xOffset = dialogBoxGetGlyphLen(t.str, t.strPos)
    }

    /**
     * Returns the pixel width of the line at [start] of [str] up to its first `[A]` or `[X]`,
     * plus 2 (0 when a line break or answer comes first).
     *
     * Partially ported from src/helpbox.c: DialogBoxGetGlyphLen (the `[A]` and `[X]` result and
     * the glyph widths are verified through `x_offset` of the capture; the `[Yes]`, `[No]` and
     * `[NL]` reset to 0 is not run)
     */
    public fun dialogBoxGetGlyphLen(str: ByteArray, start: Int): Int {
        val charWidth = IntArray(1)
        var x = 0
        var i = start
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_TALK)
        while (true) {
            when (str[i].toInt() and 0xFF) {
                0x02, 0x04, 0x05, 0x06, 0x07, 0x12, 0x13, 0x14 -> i++
                0x01, 0x18, 0x19 -> {
                    i++
                    x = 0
                }
                0x80 -> i += 2
                0x00, 0x03 -> return x + 2
                else -> {
                    i = textSystem.getCharTextLen(str, i, charWidth)
                    x += charWidth[0]
                }
            }
        }
    }

    /**
     * Clears the talking bit of face 0 when the config has bit 2 set.
     *
     * Transcribed from src/helpbox.c: sub_808B0D4 (not verified: the config bit 2 arm is not run)
     */
    public fun sub808B0D4() {
        if (getDialogueBoxConfig() and 4 != 0) hooks.setFaceTalkBit(false)
    }

    /**
     * Sets the talking bit of face 0 when the config has bit 2 set.
     *
     * Transcribed from src/helpbox.c: sub_808B0F8 (not verified: the config bit 2 arm is not run)
     */
    public fun sub808B0F8() {
        if (getDialogueBoxConfig() and 4 != 0) hooks.setFaceTalkBit(true)
    }

    /**
     * Clears the text strips and restarts the page: timer and line 0.
     *
     * Transcribed from src/helpbox.c: sub_808B11C (not verified: only reached by `[NL2]` and the
     * page codes, which the captured message does not contain)
     */
    public fun sub808B11C(proc: Proc) {
        val t = proc.state as BoxDialogueTextState
        for (i in 0..2) textSystem.spriteTextDrawBackground(texts[i])
        if (getDialogueBoxConfig() and 0x10 != 0 && getDialogueBoxConfig() and 0x20 == 0) {
            textSystem.spriteTextDrawBackground(texts[3])
            textSystem.spriteTextDrawBackground(texts[4])
        }
        t.timer = 0
        t.currentLine = 0
    }

    /**
     * Draws the next characters of the text: up to `unk_4e` per call (all of them when a
     * direction, A or B key is newly pressed and the config bit 3 is clear), interpreting the
     * control codes.
     *
     * Partially ported from src/helpbox.c: BoxDialogueInterpreter_Main (the arms run by the
     * capture: the draw, `[NL]`, `[A]`, `[X]` with the config bit 1 clear, `[ToggleRed]`; the
     * `[Yes]`, `[No]`, `[LoadOverworldFaces]`, `[ToggleColorInvert]`, `[NormalPrint]`,
     * `[FastPrint]`, `[CloseSpeechFast]`, `[NL2]` and pause arms are transcribed without a
     * capture run, and `[Yes]` / `[No]` (`StartYesNoChoice`) are not ported)
     */
    public fun boxDialogueInterpreterMain(proc: Proc) {
        val t = proc.state as BoxDialogueTextState
        var count = t.unk4e
        if (newKeys() and (KEY_A or KEY_B or KEY_DPAD) != 0 && getDialogueBoxConfig() and 8 == 0) {
            count = 0x80
        } else {
            t.unk4a--
            if (t.unk4a > 0) return
            t.unk4a = t.unk4c
        }
        sub808B0F8()
        textSystem.setTextFont(t.font)
        var i = 0
        loop@ while (i < count) {
            val c = t.str[t.strPos].toInt() and 0xFF
            when (c) {
                0x80 -> {
                    t.strPos++
                    val code = t.str[t.strPos].toInt() and 0xFF
                    if (code == 0x21) {
                        t.unk59 = (t.unk59 + 1) and 1
                        t.strPos++
                        continue@loop
                    } else if (code == 0x04) {
                        sub808B0D4()
                        engine.goto(engine.find(script)!!, 1)
                        engine.goto(proc, 1)
                        engine.endEach(talkBoxIdleScript)
                        t.strPos++
                        break@loop
                    } else if (code == 0x25) {
                        t.strPos++
                        break@loop
                    }
                    if (endOfPage(proc, t)) break@loop
                }
                0x12, 0x13, 0x14 -> if (endOfPage(proc, t)) break@loop
                0x00 -> {
                    sub808B0D4()
                    if (getDialogueBoxConfig() and 2 == 0) {
                        engine.breakProc(proc)
                    } else {
                        engine.goto(engine.find(script)!!, 1)
                        engine.goto(proc, 1)
                        engine.endEach(talkBoxIdleScript)
                    }
                    break@loop
                }
                0x01 -> {
                    sub808B0D4()
                    t.strPos++
                    if (t.unk55 == t.currentLine + 1) {
                        t.timer = 0
                        engine.goto(proc, 4)
                        break@loop
                    }
                    t.currentLine++
                    i++
                    continue@loop
                }
                0x04, 0x05, 0x06, 0x07 -> {
                    sub808B0D4()
                    t.strPos++
                    t.unk4a = 8 shl (c - 4)
                    break@loop
                }
                0x02 -> {
                    sub808B0D4()
                    t.strPos++
                    if ((t.str[t.strPos].toInt() and 0xFF) == 0x01) t.strPos++
                    if ((t.str[t.strPos].toInt() and 0xFF) == 0x00) {
                        if (getDialogueBoxConfig() and 2 == 0) {
                            engine.breakProc(proc)
                        } else {
                            engine.goto(engine.find(script)!!, 1)
                            engine.goto(proc, 1)
                            engine.endEach(talkBoxIdleScript)
                        }
                    } else if (getDialogueBoxConfig() and 0x10 != 0) {
                        sub808B11C(proc)
                    } else if ((t.str[t.strPos].toInt() and 0xFF) != 0) {
                        t.timer = 0
                        engine.goto(proc, 5)
                    }
                    break@loop
                }
                0x03 -> {
                    sub808B0D4()
                    t.strPos++
                    val merge = engine.find(mergeScript)!!.state as HelpBoxState
                    hooks.startTalkWaitForInput(
                        proc,
                        merge.xBoxFinal + t.xOffset,
                        merge.yBoxFinal + t.currentLine * 16 + 8,
                    )
                    t.xOffset = dialogBoxGetGlyphLen(t.str, t.strPos)
                    break@loop
                }
                else -> {
                    val text = t.texts[t.currentLine]
                    if (getDialogueBoxConfig() and 1 != 0) {
                        textSystem.setColor(text, 1)
                    } else {
                        textSystem.setColor(text, if (t.unk59 != 0) 10 else 6)
                    }
                    t.strPos = textSystem.drawCharacter(text, t.str, t.strPos)
                    if (hooks.textDisplaySpeed() != 1 || hooks.gameClock() and 1 != 0) {
                        val song = if (getDialogueBoxConfig() and 0x10 != 0) SONG_2E5 else SONG_6E
                        hooks.playSound(song)
                    }
                }
            }
            i++
        }
        textSystem.setTextFont(null)
    }

    /**
     * The shared tail of the `[NormalPrint]`, `[FastPrint]`, `[CloseSpeechFast]` and unknown
     * `0x80` codes of [boxDialogueInterpreterMain]: skips the code (and a following `[NL]`) and
     * restarts the page at the merge proc's size. Returns true: the caller leaves the loop.
     */
    private fun endOfPage(proc: Proc, t: BoxDialogueTextState): Boolean {
        val merge = engine.find(mergeScript)
        sub808B0D4()
        t.strPos++
        if ((t.str[t.strPos].toInt() and 0xFF) == 0x01) t.strPos++
        if (merge != null) {
            val m = merge.state as HelpBoxState
            sub808B11C(proc)
            val (a, b) = getBoxDialogueSize(t.str, t.strPos)
            t.unk56 = a and 0xFF
            t.unk57 = b and 0xFF
            t.unk54 = m.wBoxFinal and 0xFF
            t.unk55 = m.hBoxFinal and 0xFF
            t.timer = 0
            engine.goto(proc, 6)
        }
        return true
    }

    /**
     * Jumps the box and text procs to label 0 and 1 when the idle proc exists.
     *
     * Transcribed from src/helpbox.c: sub_808B788 (not verified: the `[LoadOverworldFaces]` and
     * config bit 1 `[X]` paths are not run by the capture)
     */
    public fun sub808B788(proc: Proc) {
        if (engine.find(talkBoxIdleScript) != null) {
            engine.goto(engine.find(script)!!, 0)
            engine.goto(proc, 0)
        }
    }

    /**
     * Scrolls the text strips one pixel row, and after 16 frames resets the cursor of the
     * current line and breaks.
     *
     * Ported from src/helpbox.c: sub_808B7B8 (the timer, break and script position are verified,
     * f18301..f18316; the scrolled tiles are not: the capture has no VRAM dump there)
     */
    public fun sub808B7B8(proc: Proc) {
        val t = proc.state as BoxDialogueTextState
        sub808A530(t.unk54 + 1, t.unk55)
        t.timer++
        if (t.timer == 16) {
            textSystem.setCursor(texts[t.currentLine], 0)
            engine.breakProc(proc)
        }
    }

    /**
     * Scrolls the text strip words left by one pixel across [a] tiles and [b] tile row pairs,
     * filling the last row with the background (or the next row's tile).
     *
     * Transcribed from src/helpbox.c: sub_808A530 (not verified: no VRAM dump of the scroll in
     * the capture)
     */
    public fun sub808A530(a: Int, b: Int) {
        var ptr = ((unk40 and 0x3FF) + texts[0].chrPosition) * 0x20 + 0x06010000
        for (i in 0 until b * 2) {
            var r4 = ptr
            for (j in 0 until a) {
                for (k in 0..6) {
                    textSystem.writeVramWord(r4, textSystem.readVramWord(r4 + 4))
                    r4 += 4
                }
                if (i == b * 2 - 1) {
                    val fill = if (getDialogueBoxConfig() and 1 == 0) 0x44444444 else 0
                    textSystem.writeVramWord(r4, fill)
                } else {
                    textSystem.writeVramWord(
                        r4,
                        textSystem.readVramWord(ptr + ((j + 0x20) shl 3) * 4),
                    )
                }
                r4 += 4
            }
            ptr += 0x400
        }
    }

    /**
     * Ends the page: goes back one line (or breaks at line 0, then ends the page).
     *
     * Transcribed from src/helpbox.c: sub_808B804 (not verified: label 5 is not run)
     */
    public fun sub808B804(proc: Proc) {
        val t = proc.state as BoxDialogueTextState
        if (t.currentLine == 0) engine.breakProc(proc) else engine.goto(proc, 5)
        if (t.currentLine != 0) t.currentLine--
        t.timer = 0
    }

    /**
     * Sends the box proc to label 3 and ends the text proc, restoring the system glyphs.
     *
     * Ported from src/helpbox.c: sub_808B844 (verified: the box proc reaches label 3 at f18367)
     */
    public fun sub808B844(proc: Proc) {
        engine.goto(engine.find(script)!!, 3)
        engine.breakProc(proc)
        textSystem.setTextFont(null)
        textSystem.setTextFontGlyphs(TextSystem.GLYPHS_SYSTEM)
    }

    /**
     * Blends the box size towards the next page's size over two frames, then breaks.
     *
     * Transcribed from src/helpbox.c: sub_808B870 (not verified: label 6 is not run)
     */
    public fun sub808B870(proc: Proc) {
        val t = proc.state as BoxDialogueTextState
        val merge = engine.find(mergeScript)
        t.timer++
        if (merge != null) {
            val x = (t.unk54 * (2 - t.timer) + t.timer * t.unk56) / 2
            val y = (t.unk55 * (2 - t.timer) + t.timer * t.unk57) / 2
            setBoxDialogueSize(merge.state as HelpBoxState, x, y)
        }
        if (t.timer == 2) {
            t.unk54 = t.unk56 / 8
            t.unk55 = minOf(t.unk57 / 16, 5)
            engine.breakProc(proc)
        }
    }

    private companion object {
        /** `B_BUTTON` and `START_BUTTON` key bits. */
        const val KEY_A = 0x0001
        const val KEY_B = 0x0002
        const val KEY_DPAD = 0x00F0

        /** `SONG_2E5` and `SONG_6E`: the blip of a drawn character. */
        const val SONG_2E5 = 0x2E5
        const val SONG_6E = 0x6E
        const val KEY_START = 0x0008

        /** `PROC_TREE_3`. */
        const val TREE = 3

        /** The sprite layer of the box frame. */
        const val L = 2

        /** `gBoxDialogueConf.texts` length. */
        const val TEXT_COUNT = 5

        /** The default OBJ tile address of the box font. */
        const val DEFAULT_VRAM = 0x06013000

        /** ROM addresses (FE8U) of the box graphics and palettes. */
        const val G_GFX_YELLOW_TEXT_BOX = 0x08A029A8
        const val G_GFX_YELLOW_TEXT_BOX2 = 0x08A02A1C
        const val G_GFX_YELLOW_TEXT_BOX3 = 0x08A02A94
        const val G_GFX_YELLOW_TEXT_BOX4 = 0x08A02B3C
        const val G_GFX_YELLOW_TEXT_BOX5 = 0x08A02BAC
        const val G_GFX_HELP_TEXT_BOX = 0x08A0285C
        const val G_GFX_HELP_TEXT_BOX2 = 0x08A02884
        const val G_GFX_HELP_TEXT_BOX3 = 0x08A028AC
        const val G_GFX_HELP_TEXT_BOX4 = 0x08A02914
        const val G_GFX_HELP_TEXT_BOX5 = 0x08A02980
        const val G_PAL_YELLOW_TEXT_BOX = 0x0859EF80
        const val G_PAL_HELP_TEXT_BOX = 0x0859EF60
        const val PAL_TEXT = 0x0859EF00
    }
}
