package fe8.game.ui

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
import gbafe.text.MsgStrings
import gbafe.text.TextSystem

/** Per-proc state of `gProcScr_BoxDialogue`: `struct ProcBoxDialogue` (`+0x2C..`). */
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
 * Stand-ins ([Hooks]): the drawing (`InitBoxDialogue` graphics, `DrawBoxDialogueText`) is not
 * ported yet.
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
        /** `InitBoxDialogue(vram_dst, pad_idx)`. */
        public var initBoxDialogue: (Int, Int) -> Unit = { _, _ ->
            throw NotImplementedError("not ported: InitBoxDialogue")
        }

        /** `DrawBoxDialogueText(x, y, msg)`. */
        public var drawBoxDialogueText: (Int, Int, Int) -> Unit = { _, _, _ ->
            throw NotImplementedError("not ported: DrawBoxDialogueText")
        }

        /** `PlaySoundEffect(SONG_2E7)`. */
        public var playCloseSound: () -> Unit = {}
    }

    /** The not-yet-ported callees. */
    public val hooks: Hooks = Hooks()

    /** `gBoxDialogueConf.unk_42`: the dialogue box config bits. */
    private var config: Int = 0

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
     * Transcribed from src/helpbox.c: gProcScr_BoxDialogue (not verified: the procs watch cannot
     * tag the script position beyond the first frame)
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
     * Initialises the box graphics and draws the text.
     *
     * Transcribed from src/helpbox.c: sub_808A848 (not verified: not run in
     * the f18230 window; the drawing callees are stand-ins)
     */
    public fun sub808A848(proc: Proc) {
        val s = proc.state as BoxDialogueState
        if (s.padIdx == 0xFF) {
            hooks.initBoxDialogue(0, -1)
        } else {
            hooks.initBoxDialogue(s.vramDst, s.padIdx)
        }
        hooks.drawBoxDialogueText(s.x, s.y, s.msg)
    }

    /**
     * Jumps to label 2 when B or START is pressed, unless the config has bit 1 or 7 set.
     *
     * Transcribed from src/helpbox.c: sub_808A87C (not verified: no B/START press in the window)
     */
    public fun sub808A87C(proc: Proc) {
        if (getDialogueBoxConfig() and 0x82 != 0) return
        if (newKeys() and (KEY_B or KEY_START) != 0) engine.goto(proc, 2)
    }

    /**
     * Plays the close sound if the config has bit 4 set.
     *
     * Transcribed from src/helpbox.c: sub_808A8AC (not verified: the close
     * sound is a stand-in and the proc has not ended in the window)
     */
    public fun sub808A8AC() {
        if (getDialogueBoxConfig() and 0x10 != 0) hooks.playCloseSound()
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
    }
}
