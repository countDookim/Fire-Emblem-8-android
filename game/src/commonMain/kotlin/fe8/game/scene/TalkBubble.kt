package fe8.game.scene

import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.text.Text
import gbafe.text.TextSystem

/** Hardware services the bubble and shift-clear procs need beyond [TalkHost]. */
public interface TalkBubbleHost {
    /** `gBG1TilemapBuffer`: the tilemap the bubble frame and tail are put on. */
    public fun bg1Tilemap(): ShortArray

    /**
     * `IsBattleDeamonActive` (`gBattleDeamonActive`): true while the battle animation system runs
     * (fight events talk with a fixed bubble at the bottom of the screen).
     */
    public fun isBattleDeamonActive(): Boolean

    /**
     * `TalkToggleInvertedPalette(flag)`: loads the inverted (non-zero) or normal text and bubble
     * palettes.
     */
    public fun toggleInvertedPalette(flag: Int)

    /**
     * `InitTalkTextWin(x, y, width, height)`: enables window 0 over the bubble interior (the
     * bubble box inset by one tile) with every layer shown inside and BG0 hidden outside, so the
     * text, which is on BG0, is cut off at the bubble edge while it scrolls.
     */
    public fun initTalkTextWin(x: Int, y: Int, width: Int, height: Int)

    /** Turns window 0, window 1 and the object window off (the last step of `ClearTalkBubble`). */
    public fun disableTalkWindows()

    /**
     * The blend set-up of `TalkOpen_InitBlend` for the case without `TALK_FLAG_8`: alpha
     * blending of BG1 (the bubble) against BG2, BG3, the sprites and the backdrop (not BG0, the
     * text), starting with the bubble fully transparent.
     */
    public fun initTalkBlend()

    /**
     * `SetBlendAlpha(a, b)`: alpha blending with first-target weight [a] and second-target weight
     * [b] (of 16).
     */
    public fun setBlendAlpha(a: Int, b: Int)

    /** `SpriteText_Clear(text)`: zeroes the OBJ tiles of a sprite-text line. */
    public fun spriteTextClear(text: Text)
}

/**
 * State of a `gProcScr_TalkOpen` proc: the bubble to put, in tiles, and its opening frame count.
 */
public class TalkOpenState {
    /** Tile column of the face the bubble points at, clamped to 0..30 (`unk64`). */
    public var x: Int = 0

    /** Tile row of the bubble's bottom edge, where its tail is drawn: always 8 (`unk66`). */
    public var y: Int = 0

    /** Bubble width in tiles, [TalkState.activeWidth] (`unk68`). */
    public var width: Int = 0

    /** Bubble height in tiles, always 6: two text lines of 2 rows plus the border (`unk6A`). */
    public var height: Int = 0

    /** Frames of the opening animation run so far, up to 12 (`unk58`). */
    public var frameTimer: Int = 0
}

/** State of the bubble-open, shift-clear and shift-clear-all procs (`unk64` and `unk66`). */
public class TalkCounterState {
    /**
     * Frames counted (`unk64`): in the shift procs the pixels the text has scrolled; in the bubble
     * opening, twice the animation frame shown.
     */
    public var counter: Int = 0

    /** Pixels `gProcScr_TalkShiftClearAll` scrolls before it ends (`unk66`). */
    public var limit: Int = 0
}

/**
 * The speech bubble and line shift-clear procs of `src/scene.c`: `gProcScr_TalkOpen` (the bubble
 * slides in and fades in), `gProcScr_TalkBubbleOpen` (the bubble's grow-in tile animation),
 * `gProcScr_TalkShiftClear` (one line scrolls up) and `gProcScr_TalkShiftClearAll` (all text
 * scrolls away), and `ClearTalkBubble`, `PutTalkBubble`, `TalkFlushAllLine`. Drawing goes to the
 * tilemaps of [host] and [bubbleHost].
 */
public class TalkBubble(
    private val text: TextSystem,
    private val engine: ProcEngine,
    private val talk: Talk,
    private val host: TalkHost,
    private val bubbleHost: TalkBubbleHost,
) {
    /** `TalkOpen_OnEnd`: an empty function in the decomp. */
    private val onEndFn = ProcFunc { }
    private val initBlendFn = ProcFunc { talkOpenInitBlend(it) }
    private val putBubbleFn = ProcFunc { talkOpenPutTalkBubble(it) }
    private val openIdleFn = ProcFunc { talkOpenOnIdle(it) }
    private val bubbleIdleFn = ProcFunc { talkBubbleOpenOnIdle(it) }
    private val shiftInitFn = ProcFunc { talkShiftClearOnInit(it) }
    private val shiftIdleFn = ProcFunc { talkShiftClearOnIdle(it) }
    private val clearAllInitFn = ProcFunc { talkShiftClearAllOnInit(it) }
    private val clearAllIdleFn = ProcFunc { talkShiftClearAllOnIdle(it) }

    /**
     * `gProcScr_TalkOpen`: [talkOpenInitBlend], put the bubble once, then 12 frames of
     * [talkOpenOnIdle].
     */
    public val openScript: ProcScript = ProcScript(
        "gProcScr_TalkOpen",
        listOf(
            mark(Talk.PROC_MARK_TALK), setEndCb(onEndFn), call(initBlendFn),
            repeat(putBubbleFn), repeat(openIdleFn), end(),
        ),
    ) { TalkOpenState() }

    /**
     * `gProcScr_TalkBubbleOpen`: runs [talkBubbleOpenOnIdle] until the last tile image is loaded.
     */
    public val bubbleOpenScript: ProcScript = ProcScript(
        "gProcScr_TalkBubbleOpen", listOf(repeat(bubbleIdleFn), end()),
    ) { TalkCounterState() }

    /**
     * `gProcScr_TalkShiftClear`: scrolls the dialogue up one line (16 pixels), one pixel per frame.
     */
    public val shiftClearScript: ProcScript = ProcScript(
        "gProcScr_TalkShiftClear",
        listOf(
            mark(Talk.PROC_MARK_TALK), call(shiftInitFn), repeat(shiftIdleFn), sleep(1), end(),
        ),
    ) { TalkCounterState() }

    /**
     * `gProcScr_TalkShiftClearAll`: scrolls the whole dialogue out of the bubble, then clears it.
     */
    public val shiftClearAllScript: ProcScript = ProcScript(
        "gProcScr_TalkShiftClearAll",
        listOf(mark(Talk.PROC_MARK_TALK), call(clearAllInitFn), repeat(clearAllIdleFn), end()),
    ) { TalkCounterState() }

    /**
     * Closes the bubble: no bubble belongs to a speaker any more, BG1 is blanked, the dialogue
     * text is cleared and the text window is turned off.
     *
     * Ported from src/scene.c: ClearTalkBubble
     */
    public fun clearTalkBubble() {
        talk.state.speakingFaceSlot = -1
        bubbleHost.bg1Tilemap().fill(0)
        host.talkBgSync(2)
        talk.clearPutTalkText()
        bubbleHost.disableTalkWindows()
    }

    /**
     * Starts the bubble-opening proc for the face in [talkFace], blocking [parent]. The bubble is
     * 6 tiles tall with its bottom edge at row 8, [TalkState.activeWidth] wide, pointing at the
     * face's column (`gTalkFaceHPosLut`, clamped to 0..30). The speaker and width are
     * remembered so [Talk.talkHasCorrectBubble] knows the bubble is current.
     *
     * Ported from src/scene.c: StartTalkOpen
     */
    public fun startTalkOpen(talkFace: Int, parent: Proc) {
        val proc = engine.startBlocking(openScript, parent)!!
        val openState = proc.state as TalkOpenState
        openState.x = FACE_HPOS[talkFace]
        openState.y = 8
        openState.width = talk.state.activeWidth
        openState.height = 6
        if (openState.x < 0) openState.x = 0
        if (openState.x >= 30) openState.x = 30
        talk.state.speakingFaceSlot = talkFace
        talk.state.speakingWidth = talk.state.activeWidth
    }

    /**
     * Transcribed from src/scene.c: TalkOpen_InitBlend (not verified: no @VerifiesPort test names
     * it)
     */
    public fun talkOpenInitBlend(proc: Proc) {
        (proc.state as TalkOpenState).frameTimer = 0
        if (talk.checkTalkFlag(TalkFlags.FLAG_8) == 0) bubbleHost.initTalkBlend()
    }

    /**
     * Puts the bubble on the tilemap once, then ends this step of `gProcScr_TalkOpen`.
     *
     * Ported from src/scene.c: TalkOpen_PutTalkBubble
     */
    public fun talkOpenPutTalkBubble(proc: Proc) {
        val openState = proc.state as TalkOpenState
        putTalkBubble(openState.x, openState.y, openState.width, openState.height)
        engine.breakProc(proc)
    }

    /**
     * One of the 12 opening frames: BG1 slides down from 15 pixels above its place (the
     * `RSQUARE` interpolation from -30, halved) while the bubble's blend alpha rises.
     *
     * Ported from src/scene.c: TalkOpen_OnIdle
     */
    public fun talkOpenOnIdle(proc: Proc) {
        val openState = proc.state as TalkOpenState
        openState.frameTimer++
        val bgOffset =
            Interpolate.interpolate(Interpolate.RSQUARE, -30, 0, openState.frameTimer, 12)
        host.setBgPosition(1, 0, bgOffset / 2)
        if (talk.checkTalkFlag(TalkFlags.FLAG_8) == 0) {
            bubbleHost.setBlendAlpha(bgOffset / 2 + 16, 1 - bgOffset / 2)
        }
        if (openState.frameTimer == 12) engine.breakProc(proc)
    }

    /**
     * Draws a bubble of [widthIn] by [height] tiles whose tail points at column [xAnchor] and
     * row [yAnchorIn] on BG1, and starts its opening animation. It also sets the text origin
     * ([TalkState.xText], [TalkState.yText]) to one tile inside the bubble.
     *
     * The tail kind is 0 when [xAnchor] is in the left half (< 16) and 1 otherwise; plus 2
     * when the battle animation system is active ([TalkBubbleHost.isBattleDeamonActive]), which
     * ignores the anchor and uses a fixed 20-wide bubble at row 14. The tail is not drawn while
     * the inverted palettes are on (`invertedFlags` bit 0).
     *
     * Ported from src/scene.c: PutTalkBubble
     */
    public fun putTalkBubble(xAnchor: Int, yAnchorIn: Int, widthIn: Int, height: Int) {
        val talkState = talk.state
        var yAnchor = yAnchorIn
        var width = widthIn
        var xTail = 0
        var x = 0
        bubbleHost.bg1Tilemap().fill(0)
        var tailKind = if (xAnchor < 16) 0 else 1
        if (bubbleHost.isBattleDeamonActive()) tailKind += 2
        var y = yAnchor - height + 1
        when (tailKind) {
            0 -> {
                xTail = xAnchor + 3
                x = xTail - width / 2
                if (x < 1) x = 1
            }
            1 -> {
                xTail = xAnchor - 5
                x = if ((width + 1) / 2 + xTail >= 30) 0x1D - width else xTail - width / 2
            }
            2 -> {
                x = 9
                y = 14
                width = 20
                xTail = 8
                yAnchor = 16
            }
            3 -> {
                x = 1
                y = 14
                width = 20
                xTail = 20
                yAnchor = 16
            }
        }
        talkState.xText = x + 1
        talkState.yText = y + 1
        putTalkBubbleTm(bubbleHost.bg1Tilemap(), x, y, width, height)
        if (talkState.invertedFlags and 2 != 0) {
            bubbleHost.toggleInvertedPalette(talkState.invertedFlags and 1)
            talkState.invertedFlags = talkState.invertedFlags xor 2
        }
        if (talkState.invertedFlags and 1 == 0) {
            putTalkBubbleTail(bubbleHost.bg1Tilemap(), xTail, yAnchor, tailKind)
        }
        bubbleHost.initTalkTextWin(x, y, width, height)
        startOpenTalkBubble()
        host.talkBgSync(2)
    }

    /**
     * Starts `gProcScr_TalkBubbleOpen` (on proc tree 3), the animation that grows the bubble's
     * border tiles.
     *
     * Ported from src/scene.c: StartOpenTalkBubble
     */
    public fun startOpenTalkBubble() {
        val proc = engine.start(bubbleOpenScript, 3)
        (proc.state as TalkCounterState).counter = 0
    }

    /**
     * Every second frame loads the next of the six bubble tile images (`Img_TalkBubbleOpening_A`
     * to `_E`, then the final `Img_TalkBubble`) into BG1's tile data, and breaks after the last.
     *
     * Ported from src/scene.c: TalkBubbleOpen_OnIdle
     */
    public fun talkBubbleOpenOnIdle(proc: Proc) {
        val counterState = proc.state as TalkCounterState
        if (counterState.counter++ and 1 != 0) return
        host.decompressToVram(
            BUBBLE_IMAGES[counterState.counter shr 1],
            host.backgroundTileDataOffset(1) + BUBBLE_DEST,
        )
        if (counterState.counter shr 1 == BUBBLE_IMAGES.size - 1) engine.breakProc(proc)
    }

    /**
     * Writes the 2x2 tiles of the bubble's tail at ([x], [y]) on [buf]. [kind] 0 and 1 are the
     * tails of a bubble in the left and right half of the screen, 2 and 3 the same for the
     * fixed fight-event bubble; 4 and 5 exist in the decomp but `PutTalkBubble` never selects
     * them. The tiles are bubble tiles 4..9 (tile 0x10 + n of BG1) in palette 3, mirrored with
     * the flip bits as the C does.
     *
     * Ported from src/scene.c: PutTalkBubbleTail
     */
    public fun putTalkBubbleTail(buf: ShortArray, x: Int, y: Int, kind: Int) {
        fun set(dx: Int, dy: Int, tile: Int, flags: Int) {
            buf[(x + dx) + ((y + dy) shl 5)] = ((0x10 + tile) or (3 shl 12) or flags).toShort()
        }
        when (kind) {
            0 -> {
                set(0, 0, 4, 0)
                set(1, 0, 4, TILE_HFLIP)
                set(0, 1, 6, TILE_HFLIP)
                set(1, 1, 5, TILE_HFLIP)
            }
            1 -> {
                set(0, 0, 4, 0)
                set(1, 0, 4, TILE_HFLIP)
                set(0, 1, 5, 0)
                set(1, 1, 6, 0)
            }
            2 -> {
                set(0, 0, 8, TILE_HFLIP)
                set(0, 1, 9, TILE_HFLIP)
                set(1, 0, 7, TILE_HFLIP)
                set(1, 1, 7, TILE_HFLIP + TILE_VFLIP)
            }
            3 -> {
                set(0, 0, 7, 0)
                set(0, 1, 7, TILE_VFLIP)
                set(1, 0, 8, 0)
                set(1, 1, 9, 0)
            }
            4 -> {
                set(0, 0, 9, TILE_HFLIP + TILE_VFLIP)
                set(0, 1, 8, TILE_HFLIP + TILE_VFLIP)
                set(1, 0, 7, TILE_HFLIP)
                set(1, 1, 7, TILE_HFLIP + TILE_VFLIP)
            }
            5 -> {
                set(0, 0, 7, 0)
                set(0, 1, 7, TILE_VFLIP)
                set(1, 0, 9, TILE_VFLIP)
                set(1, 1, 8, TILE_VFLIP)
            }
        }
    }

    /**
     * Writes the bubble frame at ([x], [y]), [widthIn] by [heightIn] tiles, on [buf]: corners
     * (bubble tile 0), top and bottom edges (1), side edges (2) and the filled inside (3), in
     * palette 3 with flipped copies for the other corners and edges.
     *
     * Ported from src/scene.c: PutTalkBubbleTm
     */
    public fun putTalkBubbleTm(buf: ShortArray, x: Int, y: Int, widthIn: Int, heightIn: Int) {
        fun set(cx: Int, cy: Int, tile: Int, flags: Int) {
            buf[cx + (cy shl 5)] = ((0x10 + tile) or (3 shl 12) or flags).toShort()
        }
        val width = widthIn - 1
        val height = heightIn - 1
        for (i in x until x + width) {
            set(i, y, 1, 0)
            set(i, y + height, 1, TILE_VFLIP)
        }
        for (i in y until y + height) {
            set(x, i, 2, 0)
            set(x + width, i, 2, TILE_HFLIP)
        }
        for (i in x + 1 until x + width) {
            for (j in y + 1 until y + height) set(i, j, 3, 0)
        }
        set(x, y, 0, 0)
        set(x + width, y, 0, TILE_HFLIP)
        set(x, y + height, 0, TILE_VFLIP)
        set(x + width, y + height, 0, TILE_HFLIP + TILE_VFLIP)
    }

    /**
     * Start of a line scroll: blanks the tilemap rows under the visible lines (from row
     * `yText + 4`) so the scroll shows empty space, and starts counting pixels.
     *
     * Ported from src/scene.c: TalkShiftClear_OnInit
     */
    public fun talkShiftClearOnInit(proc: Proc) {
        val talkState = talk.state
        fillRect(
            host.bg0Tilemap(),
            talkState.xText,
            talkState.yText + 4,
            talkState.activeWidth - 2,
            talkState.lines * 2,
        )
        host.talkBgSync(1)
        (proc.state as TalkCounterState).counter = 0
    }

    /**
     * Scrolls BG0 up one pixel per frame. After 16 pixels (one line) the first line is dropped
     * (`topTextNum` + 1, `lineActive` - 1), BG0 returns to its place, the remaining lines are
     * put again one line higher and the freed line is cleared for new text.
     *
     * Ported from src/scene.c: TalkShiftClear_OnIdle
     */
    public fun talkShiftClearOnIdle(proc: Proc) {
        val talkState = talk.state
        val counterState = proc.state as TalkCounterState
        counterState.counter++
        host.setBgPosition(0, 0, counterState.counter)
        if (counterState.counter < 16) return
        talkState.lineActive--
        talkState.topTextNum++
        host.setBgPosition(0, 0, 0)
        val tm = host.bg0Tilemap()
        for (i in 0 until talkState.lines - 1) {
            text.putText(byLine(i), tm, 0x20 * (talkState.yText + 2 * i) + talkState.xText)
        }
        fillRect(
            tm,
            talkState.xText,
            talkState.yText + (talkState.lines - 1) * 2,
            talkState.activeWidth - 2,
            2,
        )
        text.clearText(byLine(talkState.lines - 1))
        byLine(talkState.lines - 1).colorId = talkState.printColor
        host.talkBgSync(1)
        engine.breakProc(proc)
    }

    /**
     * Transcribed from src/scene.c: TalkShiftClearAll_OnInit (not verified: no @VerifiesPort test
     * names it)
     */
    public fun talkShiftClearAllOnInit(proc: Proc) {
        val talkState = talk.state
        val counterState = proc.state as TalkCounterState
        fillRect(
            host.bg0Tilemap(),
            talkState.xText,
            talkState.yText + 4,
            talkState.activeWidth - 2,
            talkState.lines * 2,
        )
        host.talkBgSync(1)
        counterState.counter = 0
        counterState.limit = when {
            talkState.lineActive == 0 -> 16
            talkState.lineActive + 1 >= talkState.lines -> talkState.lines * 16
            else -> (talkState.lineActive + 1) * 16
        }
    }

    /**
     * Transcribed from src/scene.c: TalkShiftClearAll_OnIdle (not verified: no @VerifiesPort test
     * names it)
     */
    public fun talkShiftClearAllOnIdle(proc: Proc) {
        val counterState = proc.state as TalkCounterState
        counterState.counter++
        host.setBgPosition(0, 0, counterState.counter)
        if (counterState.counter >= counterState.limit) {
            host.setBgPosition(0, 0, 0)
            talk.clearPutTalkText()
            engine.breakProc(proc)
        }
    }

    /**
     * `Proc_StartBlocking(gProcScr_TalkShiftClear, parent)`: scrolls one line, blocking [parent].
     */
    public fun startTalkShiftClear(parent: Proc) {
        engine.startBlocking(shiftClearScript, parent)
    }

    /** `Proc_StartBlocking(gProcScr_TalkShiftClearAll, parent)`: scrolls all text away. */
    public fun startTalkShiftClearAll(parent: Proc) {
        engine.startBlocking(shiftClearAllScript, parent)
    }

    /** `Proc_EndEach(gProcScr_TalkShiftClearAll)`: stops a running scroll-away. */
    public fun endTalkShiftClearAll() {
        engine.endEach(shiftClearAllScript)
    }

    /**
     * Transcribed from src/scene.c: TalkFlushAllLine (not verified: no @VerifiesPort test names it)
     */
    public fun talkFlushAllLine() {
        talk.state.lineActive = 0
        for (i in 0 until 2) {
            bubbleHost.spriteTextClear(talk.texts[i])
            talk.texts[i].colorId = 0
            talk.texts[i].x = 4
        }
    }

    /** `TALK_TEXT_BY_LINE(line)`. */
    private fun byLine(line: Int): Text {
        val s = talk.state
        return talk.texts[(line + s.topTextNum) % s.lines]
    }

    /** Zeroes a rectangle of the tilemap `tm`: `TileMap_FillRect(tm + (x, y), w, h, 0)`. */
    private fun fillRect(tm: ShortArray, x: Int, y: Int, w: Int, h: Int) {
        // The ARM routine behind TileMap_FillRect counts both sizes inclusively.
        for (r in 0..h) for (c in 0..w) tm[0x20 * (y + r) + x + c] = 0
    }

    private companion object {
        /** `TILE_HFLIP` (`include/hardware.h`): tilemap entry flag, mirror horizontally. */
        const val TILE_HFLIP = 0x400

        /** `TILE_VFLIP`: tilemap entry flag, mirror vertically. */
        const val TILE_VFLIP = 0x800

        /** Where the bubble tiles go: tile 0x10 (0x200 bytes) of BG1's tile data. */
        const val BUBBLE_DEST = 0x06000200

        /**
         * `gTalkFaceHPosLut`: the tile column each of the 8 talk face slots sits at (the
         * decomp's `GetTalkFaceHPos`; its fight-event case, 4 or 26, is not ported).
         */
        val FACE_HPOS = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)

        /** `Img_TalkBubbleOpening_A..E`, then `Img_TalkBubble`. */
        val BUBBLE_IMAGES = intArrayOf(
            0x089E8484, 0x089E8434, 0x089E83E0, 0x089E8384, 0x089E82E0, Talk.IMG_TALK_BUBBLE,
        )
    }
}
