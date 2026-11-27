package fe8.game.ui

import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.text.Font
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

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
 * Stand-ins ([Hooks]): the drawing (`InitBoxDialogue` graphics, `DrawBoxDialogueText`,
 * `sub_808ACFC`) is not ported yet; [mergeScript] and the teardown ([sub808A9F0]) are.
 *
 * @param engine proc scheduler running the box procs
 * @param textSystem text system whose active font measures the characters
 * @param messages the message table read for the box text
 * @param newKeys `gKeyStatusPtr->newKeys`
 */
public class BoxDialogue(
    private val engine: ProcEngine,
    private val textSystem: TextSystem,
    private val messages: MsgStrings,
    private val newKeys: () -> Int = { 0 },
) {
    /** The callees of the box procs that are not ported; each throws until ported. */
    public class Hooks {
        /** `Decompress(src, dst)` of a box graphic: ROM address, VRAM address. */
        public var decompress: (Int, Int) -> Unit = { _, _ -> }

        /** `ApplyPalette(src, palId)`: ROM address of 16 colours, palette bank. */
        public var applyPalette: (Int, Int) -> Unit = { _, _ -> }

        /** `ClearAllTalkFlags()`. */
        public var clearAllTalkFlags: () -> Unit = {}

        /** `PlaySoundEffect(SONG_2E6)`: the box opening sound. */
        public var playOpenSound: () -> Unit = {}

        /** `DrawBoxDialogueText(x, y, msg)`. */
        public var drawBoxDialogueText: (Int, Int, Int) -> Unit = { _, _, _ ->
            throw NotImplementedError("not ported: DrawBoxDialogueText")
        }

        /** `PlaySoundEffect(SONG_2E7)`. */
        public var playCloseSound: () -> Unit = {}

        /** `sub_808ACFC(x, y, w, h)`: draws the box frame (`PC-11b2c`). */
        public var drawBoxFrame: (Int, Int, Int, Int) -> Unit = { _, _, _, _ ->
            throw NotImplementedError("not ported: sub_808ACFC (PC-11b2c)")
        }
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
     * `ProcScr_BoxDialogueDrawTextExt`: placeholder for the text drawing proc (`PC-11b2c`); only
     * its identity is used here, by [sub808BAA4] and [sub808BB74].
     */
    public val drawTextExtScript: ProcScript = ProcScript(
        "ProcScr_BoxDialogueDrawTextExt",
        listOf(block(), end()),
    )

    /**
     * `gUnknown_08A01800`: placeholder proc script whose body is not ported; only its identity is
     * used here, by [sub808BAA4] and [sub808BB74].
     */
    public val unknown08A01800Script: ProcScript = ProcScript(
        "gUnknown_08A01800",
        listOf(block(), end()),
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
        hooks.drawBoxFrame(x, y, w, h)
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
        hooks.drawBoxDialogueText(s.x, s.y, s.msg)
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

    /**
     * Measures message [msg]: returns the (width, height) of its text box by scanning the control
     * codes and the glyph widths of the active font.
     *
     * Transcribed from src/helpbox.c: GetBoxDialogueSize (not verified: the captured box starts at
     * (0, 0), so it is never called, and only the control-code paths are checked, no glyph width)
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

    private companion object {
        /** `B_BUTTON` and `START_BUTTON` key bits. */
        const val KEY_B = 0x0002
        const val KEY_START = 0x0008

        /** `PROC_TREE_3`. */
        const val TREE = 3

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
