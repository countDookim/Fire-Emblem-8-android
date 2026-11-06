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
    /** `gBG1TilemapBuffer`. */
    public fun bg1Tilemap(): ShortArray

    /** `IsBattleDeamonActive`. */
    public fun isBattleDeamonActive(): Boolean

    /** `TalkToggleInvertedPalette(flag)`. */
    public fun toggleInvertedPalette(flag: Int)

    /** `InitTalkTextWin(x, y, width, height)` (window 0 box and layer masks). */
    public fun initTalkTextWin(x: Int, y: Int, width: Int, height: Int)

    /** Turns window 0, window 1 and the object window off (the end of `ClearTalkBubble`). */
    public fun disableTalkWindows()

    /** The blend set-up of `TalkOpen_InitBlend` for the case without `TALK_FLAG_8`. */
    public fun initTalkBlend()

    /** `SetBlendAlpha(a, b)`. */
    public fun setBlendAlpha(a: Int, b: Int)

    /** `SpriteText_Clear(text)`. */
    public fun spriteTextClear(text: Text)
}

/** State of a `gProcScr_TalkOpen` proc: `unk58` is [t], `unk64..unk6A` the bubble anchor. */
public class TalkOpenState {
    /** `unk64`: anchor column. */
    public var x: Int = 0

    /** `unk66`: anchor row. */
    public var y: Int = 0

    /** `unk68`: width. */
    public var w: Int = 0

    /** `unk6A`: height. */
    public var h: Int = 0

    /** `unk58`: frame counter. */
    public var t: Int = 0
}

/** State of the bubble-open, shift-clear and shift-clear-all procs: `unk64` and `unk66`. */
public class TalkCounterState {
    /** `unk64`. */
    public var n: Int = 0

    /** `unk66` (only the shift-clear-all proc). */
    public var limit: Int = 0
}

/**
 * The speech bubble and line shift-clear procs of `src/scene.c`: `gProcScr_TalkOpen`,
 * `gProcScr_TalkBubbleOpen`, `gProcScr_TalkShiftClear`, `gProcScr_TalkShiftClearAll`, and
 * `ClearTalkBubble`, `PutTalkBubble`, `TalkFlushAllLine`. Drawing goes to the tilemaps of
 * [host] and [bubbleHost].
 */
public class TalkBubble(
    private val text: TextSystem,
    private val engine: ProcEngine,
    private val talk: Talk,
    private val host: TalkHost,
    private val bubbleHost: TalkBubbleHost,
) {
    private val onEndFn = ProcFunc { }
    private val initBlendFn = ProcFunc { talkOpenInitBlend(it) }
    private val putBubbleFn = ProcFunc { talkOpenPutTalkBubble(it) }
    private val openIdleFn = ProcFunc { talkOpenOnIdle(it) }
    private val bubbleIdleFn = ProcFunc { talkBubbleOpenOnIdle(it) }
    private val shiftInitFn = ProcFunc { talkShiftClearOnInit(it) }
    private val shiftIdleFn = ProcFunc { talkShiftClearOnIdle(it) }
    private val clearAllInitFn = ProcFunc { talkShiftClearAllOnInit(it) }
    private val clearAllIdleFn = ProcFunc { talkShiftClearAllOnIdle(it) }

    /** `gProcScr_TalkOpen`. */
    public val openScript: ProcScript = ProcScript(
        "gProcScr_TalkOpen",
        listOf(
            mark(Talk.PROC_MARK_TALK), setEndCb(onEndFn), call(initBlendFn),
            repeat(putBubbleFn), repeat(openIdleFn), end(),
        ),
    ) { TalkOpenState() }

    /** `gProcScr_TalkBubbleOpen`. */
    public val bubbleOpenScript: ProcScript = ProcScript(
        "gProcScr_TalkBubbleOpen", listOf(repeat(bubbleIdleFn), end()),
    ) { TalkCounterState() }

    /** `gProcScr_TalkShiftClear`. */
    public val shiftClearScript: ProcScript = ProcScript(
        "gProcScr_TalkShiftClear",
        listOf(
            mark(Talk.PROC_MARK_TALK), call(shiftInitFn), repeat(shiftIdleFn), sleep(1), end(),
        ),
    ) { TalkCounterState() }

    /** `gProcScr_TalkShiftClearAll`. */
    public val shiftClearAllScript: ProcScript = ProcScript(
        "gProcScr_TalkShiftClearAll",
        listOf(mark(Talk.PROC_MARK_TALK), call(clearAllInitFn), repeat(clearAllIdleFn), end()),
    ) { TalkCounterState() }

    /**
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
     * Ported from src/scene.c: StartTalkOpen
     */
    public fun startTalkOpen(talkFace: Int, parent: Proc) {
        val proc = engine.startBlocking(openScript, parent)!!
        val o = proc.state as TalkOpenState
        o.x = FACE_HPOS[talkFace]
        o.y = 8
        o.w = talk.state.activeWidth
        o.h = 6
        if (o.x < 0) o.x = 0
        if (o.x >= 30) o.x = 30
        talk.state.speakingFaceSlot = talkFace
        talk.state.speakingWidth = talk.state.activeWidth
    }

    /**
     * Transcribed from src/scene.c: TalkOpen_InitBlend
     */
    public fun talkOpenInitBlend(proc: Proc) {
        (proc.state as TalkOpenState).t = 0
        if (talk.checkTalkFlag(TalkFlags.FLAG_8) == 0) bubbleHost.initTalkBlend()
    }

    /**
     * Ported from src/scene.c: TalkOpen_PutTalkBubble
     */
    public fun talkOpenPutTalkBubble(proc: Proc) {
        val o = proc.state as TalkOpenState
        putTalkBubble(o.x, o.y, o.w, o.h)
        engine.breakProc(proc)
    }

    /**
     * Ported from src/scene.c: TalkOpen_OnIdle
     */
    public fun talkOpenOnIdle(proc: Proc) {
        val o = proc.state as TalkOpenState
        o.t++
        val v = Interpolate.interpolate(Interpolate.RSQUARE, -30, 0, o.t, 12)
        host.setBgPosition(1, 0, v / 2)
        if (talk.checkTalkFlag(TalkFlags.FLAG_8) == 0) {
            bubbleHost.setBlendAlpha(v / 2 + 16, 1 - v / 2)
        }
        if (o.t == 12) engine.breakProc(proc)
    }

    /**
     * Ported from src/scene.c: PutTalkBubble
     */
    public fun putTalkBubble(xAnchor: Int, yAnchorIn: Int, widthIn: Int, height: Int) {
        val s = talk.state
        var yAnchor = yAnchorIn
        var width = widthIn
        var xTail = 0
        var x = 0
        bubbleHost.bg1Tilemap().fill(0)
        var kind = if (xAnchor < 16) 0 else 1
        if (bubbleHost.isBattleDeamonActive()) kind += 2
        var y = yAnchor - height + 1
        when (kind) {
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
        s.xText = x + 1
        s.yText = y + 1
        putTalkBubbleTm(bubbleHost.bg1Tilemap(), x, y, width, height)
        if (s.invertedFlags and 2 != 0) {
            bubbleHost.toggleInvertedPalette(s.invertedFlags and 1)
            s.invertedFlags = s.invertedFlags xor 2
        }
        if (s.invertedFlags and 1 == 0) {
            putTalkBubbleTail(bubbleHost.bg1Tilemap(), xTail, yAnchor, kind)
        }
        bubbleHost.initTalkTextWin(x, y, width, height)
        startOpenTalkBubble()
        host.talkBgSync(2)
    }

    /**
     * Ported from src/scene.c: StartOpenTalkBubble
     */
    public fun startOpenTalkBubble() {
        val proc = engine.start(bubbleOpenScript, 3)
        (proc.state as TalkCounterState).n = 0
    }

    /**
     * Ported from src/scene.c: TalkBubbleOpen_OnIdle
     */
    public fun talkBubbleOpenOnIdle(proc: Proc) {
        val c = proc.state as TalkCounterState
        if (c.n++ and 1 != 0) return
        host.decompressToVram(
            BUBBLE_IMAGES[c.n shr 1],
            host.backgroundTileDataOffset(1) + BUBBLE_DEST,
        )
        if (c.n shr 1 == BUBBLE_IMAGES.size - 1) engine.breakProc(proc)
    }

    /**
     * Ported from src/scene.c: PutTalkBubbleTail
     */
    public fun putTalkBubbleTail(buf: ShortArray, x: Int, y: Int, kind: Int) {
        fun set(dx: Int, dy: Int, tile: Int, flags: Int) {
            buf[(x + dx) + ((y + dy) shl 5)] = ((0x10 + tile) or (3 shl 12) or flags).toShort()
        }
        when (kind) {
            0 -> {
                set(0, 0, 4, 0)
                set(1, 0, 4, H)
                set(0, 1, 6, H)
                set(1, 1, 5, H)
            }
            1 -> {
                set(0, 0, 4, 0)
                set(1, 0, 4, H)
                set(0, 1, 5, 0)
                set(1, 1, 6, 0)
            }
            2 -> {
                set(0, 0, 8, H)
                set(0, 1, 9, H)
                set(1, 0, 7, H)
                set(1, 1, 7, H + V)
            }
            3 -> {
                set(0, 0, 7, 0)
                set(0, 1, 7, V)
                set(1, 0, 8, 0)
                set(1, 1, 9, 0)
            }
            4 -> {
                set(0, 0, 9, H + V)
                set(0, 1, 8, H + V)
                set(1, 0, 7, H)
                set(1, 1, 7, H + V)
            }
            5 -> {
                set(0, 0, 7, 0)
                set(0, 1, 7, V)
                set(1, 0, 9, V)
                set(1, 1, 8, V)
            }
        }
    }

    /**
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
            set(i, y + height, 1, V)
        }
        for (i in y until y + height) {
            set(x, i, 2, 0)
            set(x + width, i, 2, H)
        }
        for (i in x + 1 until x + width) {
            for (j in y + 1 until y + height) set(i, j, 3, 0)
        }
        set(x, y, 0, 0)
        set(x + width, y, 0, H)
        set(x, y + height, 0, V)
        set(x + width, y + height, 0, H + V)
    }

    /**
     * Ported from src/scene.c: TalkShiftClear_OnInit
     */
    public fun talkShiftClearOnInit(proc: Proc) {
        val s = talk.state
        fillRect(host.bg0Tilemap(), s.xText, s.yText + 4, s.activeWidth - 2, s.lines * 2)
        host.talkBgSync(1)
        (proc.state as TalkCounterState).n = 0
    }

    /**
     * Ported from src/scene.c: TalkShiftClear_OnIdle
     */
    public fun talkShiftClearOnIdle(proc: Proc) {
        val s = talk.state
        val c = proc.state as TalkCounterState
        c.n++
        host.setBgPosition(0, 0, c.n)
        if (c.n < 16) return
        s.lineActive--
        s.topTextNum++
        host.setBgPosition(0, 0, 0)
        val tm = host.bg0Tilemap()
        for (i in 0 until s.lines - 1) {
            text.putText(byLine(i), tm, 0x20 * (s.yText + 2 * i) + s.xText)
        }
        fillRect(tm, s.xText, s.yText + (s.lines - 1) * 2, s.activeWidth - 2, 2)
        text.clearText(byLine(s.lines - 1))
        byLine(s.lines - 1).colorId = s.printColor
        host.talkBgSync(1)
        engine.breakProc(proc)
    }

    /**
     * Transcribed from src/scene.c: TalkShiftClearAll_OnInit
     */
    public fun talkShiftClearAllOnInit(proc: Proc) {
        val s = talk.state
        val c = proc.state as TalkCounterState
        fillRect(host.bg0Tilemap(), s.xText, s.yText + 4, s.activeWidth - 2, s.lines * 2)
        host.talkBgSync(1)
        c.n = 0
        c.limit = when {
            s.lineActive == 0 -> 16
            s.lineActive + 1 >= s.lines -> s.lines * 16
            else -> (s.lineActive + 1) * 16
        }
    }

    /**
     * Transcribed from src/scene.c: TalkShiftClearAll_OnIdle
     */
    public fun talkShiftClearAllOnIdle(proc: Proc) {
        val c = proc.state as TalkCounterState
        c.n++
        host.setBgPosition(0, 0, c.n)
        if (c.n >= c.limit) {
            host.setBgPosition(0, 0, 0)
            talk.clearPutTalkText()
            engine.breakProc(proc)
        }
    }

    /** `Proc_StartBlocking(gProcScr_TalkShiftClear, parent)`. */
    public fun startTalkShiftClear(parent: Proc) {
        engine.startBlocking(shiftClearScript, parent)
    }

    /** `Proc_StartBlocking(gProcScr_TalkShiftClearAll, parent)`. */
    public fun startTalkShiftClearAll(parent: Proc) {
        engine.startBlocking(shiftClearAllScript, parent)
    }

    /** `Proc_EndEach(gProcScr_TalkShiftClearAll)`. */
    public fun endTalkShiftClearAll() {
        engine.endEach(shiftClearAllScript)
    }

    /**
     * Transcribed from src/scene.c: TalkFlushAllLine
     */
    public fun talkFlushAllLine() {
        talk.state.lineActive = 0
        for (i in 0 until 2) {
            bubbleHost.spriteTextClear(talk.texts[i])
            talk.texts[i].colorId = 0
            talk.texts[i].x = 4
        }
    }

    private fun byLine(line: Int): Text {
        val s = talk.state
        return talk.texts[(line + s.topTextNum) % s.lines]
    }

    private fun fillRect(tm: ShortArray, x: Int, y: Int, w: Int, h: Int) {
        // The ARM routine behind TileMap_FillRect counts both sizes inclusively.
        for (r in 0..h) for (c in 0..w) tm[0x20 * (y + r) + x + c] = 0
    }

    private companion object {
        const val H = 0x400
        const val V = 0x800
        const val BUBBLE_DEST = 0x06000200

        /** `gTalkFaceHPosLut`. */
        val FACE_HPOS = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)

        /** `Img_TalkBubbleOpening_A..E`, then `Img_TalkBubble`. */
        val BUBBLE_IMAGES = intArrayOf(
            0x089E8484, 0x089E8434, 0x089E83E0, 0x089E8384, 0x089E82E0, Talk.IMG_TALK_BUBBLE,
        )
    }
}
