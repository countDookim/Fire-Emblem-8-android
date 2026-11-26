package fe8.game.ui

import fe8.game.RomView
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import gbafe.text.Font
import gbafe.text.Text
import gbafe.text.TextSystem

/** Per-proc state of `gProcScr_HelpBox`: `struct HelpBoxProc` (`+0x2C..`). */
public class HelpBoxState {
    /** `info` (+0x2C): the info the box was started with; null for the no-help-sprite start. */
    public var info: HelpBoxInfo? = null

    /** `xBox`, `yBox`, `wBox`, `hBox` (+0x30..0x36): the box rectangle drawn this frame. */
    public var xBox: Int = 0
    /** `yBox`. */
    public var yBox: Int = 0
    /** `wBox`. */
    public var wBox: Int = 0
    /** `hBox`. */
    public var hBox: Int = 0

    /** `xBoxInit`, `yBoxInit` (+0x38, +0x3A) and `xBoxFinal`, `yBoxFinal` (+0x3C, +0x3E). */
    public var xBoxInit: Int = 0
    /** `yBoxInit`. */
    public var yBoxInit: Int = 0
    /** `xBoxFinal`. */
    public var xBoxFinal: Int = 0
    /** `yBoxFinal`. */
    public var yBoxFinal: Int = 0

    /** `wBoxInit`, `hBoxInit` (+0x40, +0x42) and `wBoxFinal`, `hBoxFinal` (+0x44, +0x46). */
    public var wBoxInit: Int = 0
    /** `hBoxInit`. */
    public var hBoxInit: Int = 0
    /** `wBoxFinal`. */
    public var wBoxFinal: Int = 0
    /** `hBoxFinal`. */
    public var hBoxFinal: Int = 0

    /** `timer` (+0x48) and `timerMax` (+0x4A). */
    public var timer: Int = 0
    /** `timerMax`. */
    public var timerMax: Int = 0

    /** `msgId` (+0x4C) and `item` (+0x4E). */
    public var msgId: Int = 0
    /** `item`. */
    public var item: Int = 0

    /** `noHelpSprite` (+0x52). */
    public var noHelpSprite: Boolean = false
}

/**
 * The parts of `struct HelpBoxInfo` the box proc reads: the display position, the message and the
 * optional `populate` callback (`adj*` and `redirect` belong to the stat screen).
 */
public class HelpBoxInfo(
    /** `xDisplay`. */
    public val xDisplay: Int,
    /** `yDisplay`. */
    public val yDisplay: Int,
    /** `msgId`. */
    public val msgId: Int,
    /** `populate`, or null. */
    public val populate: ((HelpBoxState) -> Unit)? = null,
)

/**
 * The help box of the stat screen and save menu (`src/statscreen.c`, `src/helpbox.c`, `PC-11e`):
 * [startHelpBoxExtNoHelpSprite] starts `gProcScr_HelpBox`, which grows the frame from a small box
 * to the message size ([helpBoxOnLoop]) and, after [closeHelpBox], shrinks it ([helpBoxWaitClose]).
 *
 * Text (`ClearHelpBoxText`, `StartHelpBoxTextInit`, `GetStringTextBox`, PC-11f) is behind [Hooks].
 *
 * @param engine proc scheduler running `gProcScr_HelpBox`
 * @param layers sprite layer lists receiving the frame (layer 0)
 * @param hardware OBJ tile and palette buffers for [loadHelpBoxGfx]
 * @param rom ROM view holding the box graphics and `gObject_*`
 * @param text text system for the box's sprite font
 * @param helpPrompt the prompt sprite whose proc the box locks while it is open
 */
public class HelpBox(
    private val engine: ProcEngine,
    private val layers: SpriteLayers,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val text: TextSystem,
    private val helpPrompt: HelpPromptSprite,
    /** Callbacks for the parts that later tasks own. */
    public val hooks: Hooks = Hooks(),
) {
    /** External callbacks of the box. */
    public class Hooks(
        /** `PlaySoundEffect(songId)`. */
        public var playSoundEffect: (songId: Int) -> Unit = {},
        /**
         * `GetStringTextBox(GetStringFromIndex(msgId), &w, &h)` under the talk glyph set: the
         * message's content size. Stand-in (PC-11f, not ported): needs game text; 0 x 0 for now.
         */
        public var stringTextBox: (msgId: Int) -> Pair<Int, Int> = { 0 to 0 },
        /** `StartHelpBoxTextInit(item, msgId)`; stand-in (PC-11f): `ProcScr_HelpBoxIntro`. */
        public var startHelpBoxTextInit: (item: Int, msgId: Int) -> Unit = { _, _ -> },
        /** `ClearHelpBoxText` (stand-in, PC-11f: clears the text, ends the text procs). */
        public var clearHelpBoxText: () -> Unit = {},
        /**
         * `GetHelpBoxItemInfoKind(item)`: 0 none, 1 weapon, 2 staff, 3 save info. Stand-in
         * (PC-11f): only the `item == 0` and save info cases (no item data).
         */
        public var itemInfoKind: (item: Int) -> Int = { if (it == SAVE_INFO_ITEM) 3 else 0 },
        /** `GetStringTextLen(GetStringFromIndex(msgId))`, for weapon boxes. Stand-in (PC-11f). */
        public var stringTextLen: (msgId: Int) -> Int = { 0 },
        /** `GetUiHandPrevDisplayX/Y`, for a negative position. Stand-in (PC-11f). */
        public var uiHandPrevDisplay: () -> Pair<Int, Int> = { 0 to 0 },
    )

    /** `gHelpBoxSt.font`. */
    public val font: Font = Font()

    /** `gHelpBoxSt.text[0..2]`. */
    public val texts: List<Text> = List(3) { Text() }

    /** `gHelpBoxSt.oam2_base`. */
    public var oam2Base: Int = 0
        private set

    /** `sHbOrigin` (x, y in tiles); always 0 in the captured starts. */
    private var originX: Int = 0
    private var originY: Int = 0

    private val gObject8x8 = rom.objN(G_OBJECT_8X8)
    private val gObject16x16 = rom.objN(G_OBJECT_16X16)
    private val gObject32x16 = rom.objN(G_OBJECT_32X16)
    private val gObject16x8 = rom.objN(G_OBJECT_16X8)
    private val gObject32x8 = rom.objN(G_OBJECT_32X8)
    private val gObject8x16 = rom.objN(G_OBJECT_8X16)

    /**
     * `gProcScr_HelpBox`: yield, open, grow until a [closeHelpBox] jumps to label 99, close,
     * shrink.
     *
     * Transcribed from src/statscreen.c: gProcScr_HelpBox (not verified: the procs watch cannot
     * tag a script; the cursor positions match the capture every frame)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_HelpBox",
        listOf(
            yield(),
            call(ProcFunc { helpBoxOnOpen(it) }),
            repeat(ProcFunc { helpBoxOnLoop(it) }),
            label(LABEL_CLOSE),
            call(ProcFunc { helpBoxOnClose(it) }),
            repeat(ProcFunc { helpBoxWaitClose(it) }),
            end(),
        ),
        newState = { HelpBoxState() },
    )

    /**
     * Decompresses the five box tile blocks to OBJ VRAM at [vram] + 0x360.., initialises the box's
     * sprite font and its three text strips, loads `Pal_HelpBox` into bank [palId] + 0x10 and
     * sets [oam2Base]. A [vram] of 0 means `0x06013000`, a negative [palId] means 5.
     *
     * Ported from src/helpbox.c: LoadHelpBoxGfx
     * (verified for the save menu's call: tiles, palette bank and `oam2_base` at `f1154`; the
     * default [vram] and [palId] arms are not exercised)
     */
    public fun loadHelpBoxGfx(vram: Int, palId: Int) {
        val base = if (vram == 0) DEFAULT_VRAM else vram
        val pal = ((if (palId < 0) 5 else palId) and 0xF) + 0x10
        val offset = base - VRAM_BASE
        for ((i, gfx) in GFX_BLOCKS.withIndex()) {
            rom.decompressInto(gfx, hardware.vram, offset + GFX_OFFSETS[i])
        }
        text.initSpriteTextFont(font, base, pal)
        for (strip in texts) text.initSpriteText(strip)
        text.setTextFont(null)
        rom.pal(PAL_HELP_BOX, 16).copyInto(hardware.palette, pal * 16)
        hardware.enablePaletteSync()
        oam2Base = ((base shl 17) ushr 22) + (pal and 0xF) * 0x1000
    }

    /**
     * Starts (or re-targets) the help box at the display position of [info]; the box shows the
     * help sprite unless [noHelpSprite].
     *
     * Transcribed from src/statscreen.c: StartHelpBoxExt (not verified: the captures only start
     * the no-help-sprite box)
     */
    public fun startHelpBoxExt(info: HelpBoxInfo, noHelpSprite: Boolean): Proc {
        var proc = engine.find(script)
        val s: HelpBoxState
        if (proc == null) {
            proc = engine.start(script, BOX_ROOT)
            s = proc.state as HelpBoxState
            s.noHelpSprite = noHelpSprite
            setHelpBoxInitPosition(s, info.xDisplay, info.yDisplay)
            resetHelpBoxInitSize(s)
        } else {
            s = proc.state as HelpBoxState
            s.xBoxInit = s.xBox
            s.yBoxInit = s.yBox
            s.wBoxInit = s.wBox
            s.hBoxInit = s.hBox
        }
        s.info = info
        s.timer = 0
        s.timerMax = 12
        s.item = 0
        s.msgId = info.msgId
        info.populate?.invoke(s)
        val (wContent, hContent) = hooks.stringTextBox(s.msgId)
        applyHelpBoxContentSize(s, wContent, hContent)
        applyHelpBoxPosition(s, info.xDisplay, info.yDisplay)
        hooks.clearHelpBoxText()
        hooks.startHelpBoxTextInit(s.item, s.msgId)
        return proc
    }

    /**
     * Starts a help box with no help sprite and no animation from a point: the box grows from
     * 32x16 to the message size at ([x] + 8, [y] + 8). A negative [x] and [y] use the ui hand's
     * previous position.
     *
     * Ported from src/statscreen.c: StartHelpBoxExt_NoHelpSprite
     * (verified for (48, 48); the negative-position arm is not exercised and the content size is
     * the [Hooks.stringTextBox] stand-in)
     */
    public fun startHelpBoxExtNoHelpSprite(x: Int, y: Int, msgId: Int): Proc {
        val proc = engine.start(script, BOX_ROOT)
        val s = proc.state as HelpBoxState
        s.noHelpSprite = true
        var px = x
        var py = y
        if (x < 0 && y < 0) {
            val (hx, hy) = hooks.uiHandPrevDisplay()
            px = hx
            py = hy
        }
        s.timer = 0
        s.timerMax = 12
        s.item = 0
        s.msgId = msgId
        val (wContent, hContent) = hooks.stringTextBox(msgId)
        resetHelpBoxInitSize(s)
        applyHelpBoxContentSize(s, wContent, hContent)
        s.xBoxInit = px + 8
        s.yBoxInit = py + 8
        s.xBoxFinal = px + 8
        s.yBoxFinal = py + 8
        hooks.clearHelpBoxText()
        hooks.startHelpBoxTextInit(s.item, s.msgId)
        return proc
    }

    /**
     * Locks the prompt sprite proc and, for a box with a help sprite, plays `SONG_70`.
     *
     * Ported from src/statscreen.c: HelpBox_OnOpen
     * (verified for the no-help-sprite box; the `SONG_70` arm is not exercised)
     */
    public fun helpBoxOnOpen(proc: Proc) {
        val s = proc.state as HelpBoxState
        engine.find(helpPrompt.script)?.lockCnt = 1
        if (!s.noHelpSprite) hooks.playSoundEffect(SONG_70)
    }

    /**
     * Draws the growing box (`INTERPOLATE_RCUBIC`) and advances the timer to `timerMax`.
     *
     * Ported from src/statscreen.c: HelpBox_OnLoop
     */
    public fun helpBoxOnLoop(proc: Proc) {
        val s = proc.state as HelpBoxState
        updateHelpBoxDisplay(s, Interpolate.RCUBIC)
        if (s.timer < s.timerMax) s.timer++
    }

    /**
     * Unlocks the prompt sprite proc and, for a box with a help sprite, plays `SONG_71` and resets
     * the box's start size and position to shrink back to the info's display position.
     *
     * Ported from src/statscreen.c: HelpBox_OnClose
     * (verified for the no-help-sprite box; the `SONG_71` arm is not exercised)
     */
    public fun helpBoxOnClose(proc: Proc) {
        val s = proc.state as HelpBoxState
        engine.find(helpPrompt.script)?.lockCnt = 0
        if (!s.noHelpSprite) {
            hooks.playSoundEffect(SONG_71)
            resetHelpBoxInitSize(s)
            val info = s.info!!
            setHelpBoxInitPosition(s, info.xDisplay, info.yDisplay)
        }
    }

    /**
     * Draws the shrinking box (`INTERPOLATE_LINEAR`), counting the timer down by 3 per frame, and
     * ends the proc's script when it drops below 0.
     *
     * Ported from src/statscreen.c: HelpBox_WaitClose
     */
    public fun helpBoxWaitClose(proc: Proc) {
        val s = proc.state as HelpBoxState
        updateHelpBoxDisplay(s, Interpolate.LINEAR)
        s.timer -= 3
        if (s.timer < 0) engine.breakProc(proc)
    }

    /**
     * Clears the box text and jumps the box proc (if any) to its close label.
     *
     * Ported from src/statscreen.c: CloseHelpBox
     */
    public fun closeHelpBox() {
        val proc = engine.find(script) ?: return
        hooks.clearHelpBoxText()
        engine.goto(proc, LABEL_CLOSE)
    }

    private fun updateHelpBoxDisplay(s: HelpBoxState, method: Int) {
        s.xBox = Interpolate.interpolate(method, s.xBoxInit, s.xBoxFinal, s.timer, s.timerMax)
        s.yBox = Interpolate.interpolate(method, s.yBoxInit, s.yBoxFinal, s.timer, s.timerMax)
        s.wBox = Interpolate.interpolate(method, s.wBoxInit, s.wBoxFinal, s.timer, s.timerMax)
        s.hBox = Interpolate.interpolate(method, s.hBoxInit, s.hBoxFinal, s.timer, s.timerMax)
        displayHelpBoxObj(s.xBox, s.yBox, s.wBox, s.hBox, s.noHelpSprite)
    }

    private fun setHelpBoxInitPosition(s: HelpBoxState, x: Int, y: Int) {
        s.xBoxInit = x + originX * 8
        s.yBoxInit = y + originY * 8
    }

    private fun resetHelpBoxInitSize(s: HelpBoxState) {
        s.wBoxInit = 32
        s.hBoxInit = 16
    }

    private fun applyHelpBoxContentSize(s: HelpBoxState, width: Int, height: Int) {
        var w = 0xF0 and (width + 15)
        var h = height
        when (hooks.itemInfoKind(s.item)) {
            1 -> {
                if (w < 0x90) w = 0x90
                h += if (hooks.stringTextLen(s.msgId) > 8) 0x20 else 0x10
            }
            2 -> {
                if (w < 0x60) w = 0x60
                h += 0x10
            }
            3 -> {
                w = 0x80
                h += 0x10
            }
        }
        s.wBoxFinal = w
        s.hBoxFinal = h
    }

    private fun applyHelpBoxPosition(s: HelpBoxState, x0: Int, y0: Int) {
        val xSpan = s.wBoxFinal + 0x10
        val ySpan = s.hBoxFinal + 0x10
        val x = x0 + originX * 8
        val y = y0 + originY * 8
        s.xBoxFinal = x - 0x10 - xSpan / 6
        if (s.xBoxFinal < 0) s.xBoxFinal = 0
        if (s.xBoxFinal + xSpan > DISPLAY_WIDTH) s.xBoxFinal = DISPLAY_WIDTH - xSpan
        s.yBoxFinal = y + 0x10
        if (s.yBoxFinal + ySpan > DISPLAY_HEIGHT) s.yBoxFinal = y - ySpan
        s.xBoxFinal += 8
        s.yBoxFinal += 8
    }

    /**
     * Puts the box frame of size [w] x [h] at ([x], [y]) on layer 0: the filled middle in 32x16
     * (or 16x16) pieces, the top and bottom edges, the sides and the corners, and the help sprite
     * unless [noHelpSprite].
     *
     * Ported from src/helpbox.c: DisplayHelpBoxObj
     */
    public fun displayHelpBoxObj(x: Int, y: Int, width: Int, height: Int, noHelpSprite: Boolean) {
        val flag = (width + 7) and 0x10
        val anotherFlag = width and 0xF
        val w = width.coerceIn(0x20, 0xC0)
        val h = height.coerceIn(0x10, 0x30)
        val xCount = (w + 0x1F) / 0x20
        val yCount = (h + 0x0F) / 0x10
        val base = oam2Base

        var flagCur = flag
        for (ix in xCount - 1 downTo 0) {
            for (iy in yCount downTo 0) {
                val yPx = minOf((iy + 1) * 0x10, h) - 0x10
                var xPx = (ix + 1) * 0x20
                if (flagCur != 0) {
                    xPx -= 0x20
                    layers.put(L, x + xPx, y + yPx, gObject16x16, base + ix * 4 + iy * 0x40)
                } else {
                    if (xPx > w) xPx = w
                    xPx -= 0x20
                    layers.put(L, x + xPx, y + yPx, gObject32x16, base + ix * 4 + iy * 0x40)
                }
            }
            flagCur = 0
        }

        flagCur = flag
        for (ix in xCount - 1 downTo 0) {
            var xPx = (ix + 1) * 0x20
            if (flagCur != 0) {
                xPx -= 0x20
                layers.put(L, x + xPx, y - 8, gObject16x8, base + 0x1B)
                layers.put(L, x + xPx, y + h, gObject16x8, base + 0x3B)
                flagCur = 0
            } else {
                if (xPx > w) xPx = w
                xPx -= 0x20
                layers.put(L, x + xPx, y - 8, gObject32x8, base + 0x1B)
                layers.put(L, x + xPx, y + h, gObject32x8, base + 0x3B)
            }
        }

        for (iy in yCount downTo 0) {
            val yPx = minOf((iy + 1) * 0x10, h) - 0x10
            layers.put(L, x - 8, y + yPx, gObject8x16, base + 0x5F)
            layers.put(L, x + w, y + yPx, gObject8x16, base + 0x1F)
            if (anotherFlag != 0) layers.put(L, x + w - 8, y + yPx, gObject8x16, base + 0x1A)
        }

        layers.put(L, x - 8, y - 8, gObject8x8, base + 0x5B)
        layers.put(L, x + w, y - 8, gObject8x8, base + 0x5C)
        layers.put(L, x - 8, y + h, gObject8x8, base + 0x5D)
        layers.put(L, x + w, y + h, gObject8x8, base + 0x5E)
        if (anotherFlag != 0) {
            layers.put(L, x + w - 8, y - 8, gObject8x8, base + 0x1B)
            layers.put(L, x + w - 8, y + h, gObject8x8, base + 0x3B)
        }
        if (!noHelpSprite) {
            layers.put(L, x, y - 0xB, gObject32x16, (0x3FF and base) + 0x7B)
        }
    }

    /** Addresses and constants of the help box. */
    public companion object {
        /** `gObject_8x8`, `gObject_16x16`, `gObject_32x16`, `gObject_16x8`, `gObject_32x8`. */
        public const val G_OBJECT_8X8: Int = 0x08590F44
        /** `gObject_16x16`. */
        public const val G_OBJECT_16X16: Int = 0x08590F4C
        /** `gObject_32x16`. */
        public const val G_OBJECT_32X16: Int = 0x08590F8C
        /** `gObject_16x8`. */
        public const val G_OBJECT_16X8: Int = 0x08590F7C
        /** `gObject_32x8`. */
        public const val G_OBJECT_32X8: Int = 0x08590F9C

        /** `gObject_8x16`. */
        public const val G_OBJECT_8X16: Int = 0x08590F64

        /** `Pal_HelpBox`. */
        public const val PAL_HELP_BOX: Int = 0x0859EF40

        /** `OBJCHR_SAVEMENU_SLOTSEL_HELPBOX` and `OBJPAL_SAVEMENU_SLOTSEL_HELPBOX`. */
        public const val OBJCHR_SAVEMENU_SLOTSEL_HELPBOX: Int = 0x200
        /** `OBJPAL_SAVEMENU_SLOTSEL_HELPBOX`. */
        public const val OBJPAL_SAVEMENU_SLOTSEL_HELPBOX: Int = 9

        /** `OBJ_VRAM0` and the byte size of a 4bpp tile, for the save menu's `LoadHelpBoxGfx`. */
        public const val OBJ_VRAM0: Int = 0x06010000
        /** Byte size of a 4bpp tile. */
        public const val TILE_SIZE_4BPP: Int = 32

        /** The `item` of the save info box (`(u16)-2`). */
        public const val SAVE_INFO_ITEM: Int = 0xFFFE

        private const val LABEL_CLOSE = 99
        private const val BOX_ROOT = 3
        private const val L = 0
        private const val SONG_70 = 0x70
        private const val SONG_71 = 0x71
        private const val VRAM_BASE = 0x06000000
        private const val DEFAULT_VRAM = 0x06013000
        private const val DISPLAY_WIDTH = 240
        private const val DISPLAY_HEIGHT = 160

        /** `gGfx_HelpTextBox..5`, decompressed to `vram + 0x360, 0x760, 0xB60, 0xF60, 0x1360`. */
        private val GFX_BLOCKS = intArrayOf(
            0x08A0285C, 0x08A02884, 0x08A028AC, 0x08A02914, 0x08A02980,
        )
        private val GFX_OFFSETS = intArrayOf(0x360, 0x760, 0xB60, 0xF60, 0x1360)
    }
}
