package fe8.game.scene

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/** State of a `ProcScr_BrownTextBox` proc (`struct BrownTextBoxProc` fields used by the loop). */
internal class BrownTextBoxState {
    /** `x`. */
    var x: Int = 0

    /** `y`. */
    var y: Int = 0

    /** `chr`: byte offset of the box tiles in OBJ VRAM. */
    var chr: Int = 0

    /** `pal`. */
    var pal: Int = 0

    /** `textId`. */
    var textId: Int = 0

    /** `width`: box width in tiles. */
    var width: Int = 0

    /** `oam0Attr`: 0, or `OAM0_BLEND`. */
    var oam0Attr: Int = 0
}

/** State of the fade procs: `blendVal`. */
internal class BrownTextBoxFadeState {
    /** `blendVal`. */
    var blendVal: Int = 0
}

/**
 * The brown text box of `src/popup.c` (`StartBrownTextBox` and its procs): a frame of OBJ
 * sprites around one line of sprite text that fades in, stays 100 frames and fades out, blocking
 * its parent proc.
 *
 * The sprite-text calls of `StartBrownTextBoxCore` are the hooks [textWidth] (the
 * `GetStringTextLen(GetStringFromIndex(textId))` pixel width) and [drawText] (the sprite text
 * drawing after `Decompress`); the defaults measure 0 and draw nothing. The game wires both to
 * `gbafe.text`.
 *
 * @param textWidth pixel width of the message with the active (system) font.
 * @param drawText draws the message into the box tiles: arguments are the text id, the byte
 *   offset `chr` of the box tiles in OBJ VRAM, the box palette bank `pal`, and the centring width
 *   `(r4 + 3) * 8` that `GetStringTextCenteredPos` gets.
 */
internal class BrownTextBox(
    private val engine: ProcEngine,
    private val hw: BgHardware,
    private val sprites: SpriteLayers,
    private val rom: RomView,
    private val textWidth: (textId: Int) -> Int = { 0 },
    private val drawText: (textId: Int, chr: Int, pal: Int, centerWidth: Int) -> Unit =
        { _, _, _, _ -> },
) {
    private val obj8x8: SpriteObject by lazy { rom.objN(G_OBJECT_8X8) }
    private val obj16x8: SpriteObject by lazy { rom.objN(G_OBJECT_16X8) }
    private val obj32x16: SpriteObject by lazy { rom.objN(G_OBJECT_32X16) }

    /** `ProcScr_BrownTextBox`. */
    val boxScript: ProcScript = ProcScript(
        "ProcScr_BrownTextBox",
        listOf(yield(), setEndCb(ProcFunc { }), repeat(ProcFunc { brownTextBoxLoop(it) }), end()),
    ) { BrownTextBoxState() }

    /** `ProcScr_08592530`. */
    val fadeScript: ProcScript = ProcScript(
        "ProcScr_08592530",
        listOf(
            call(ProcFunc { sub8011A48(it) }),
            repeat(ProcFunc { sub8011AA0(it) }),
            sleep(100),
            call(ProcFunc { sub8011AF4(it) }),
            repeat(ProcFunc { sub8011B4C(it) }),
            call(ProcFunc { sub8011B90() }),
            end(),
        ),
    ) { BrownTextBoxFadeState() }

    /**
     * Transcribed from src/popup.c: StartBrownTextBox
     * (not verified: no reference test)
     */
    fun startBrownTextBox(textId: Int, x: Int, y: Int, parent: Proc) {
        startBrownTextBoxCore(x, y, textId, 0x5000, 9, parent)
        engine.startBlocking(fadeScript, parent)
    }

    /**
     * Ported from src/popup.c: StartBrownTextBoxCore
     * (verified at the hold frame against ram_trace_newgame_bm f09559: OAM width, palettes, box
     * and text tiles in OBJ VRAM; not covered: other chr/pal/x/y values, other message lengths)
     */
    fun startBrownTextBoxCore(x: Int, y: Int, textId: Int, chr: Int, pal: Int, parent: Proc) {
        val proc = engine.start(boxScript, parent)
        val s = proc.state as BrownTextBoxState
        s.x = x
        s.y = y
        s.chr = chr
        s.pal = pal
        s.textId = textId
        s.oam0Attr = 0

        applyPalette(G_PAL_BROWN_TEXT_BOX, s.pal + 0x10)
        applyPalette(PAL_TEXT, s.pal + 0x11)
        rom.decompressInto(G_GFX_BROWN_TEXT_BOX, hw.vram, OBJ_VRAM + s.chr)

        val r4 = textWidth(textId) / 8
        s.width = r4 + 5
        drawText(textId, s.chr, s.pal, (r4 + 3) * 8)
    }

    private fun applyPalette(src: Int, palId: Int) {
        rom.pal(src, 16).copyInto(hw.palette, palId * 16)
        hw.enablePaletteSync()
    }

    /**
     * Ported from src/popup.c: BrownTextBox_Loop
     * (verified at the hold frame against ram_trace_newgame_bm f09559 for one width (12 tiles);
     * not covered: the OAM0_BLEND frames, other widths, odd/even loop remainders)
     */
    fun brownTextBoxLoop(proc: Proc) {
        val s = proc.state as BrownTextBoxState
        val oam2A = (((s.chr + 0x400) and 0x1FFFF) / CHR_SIZE) or ((s.pal + 1) shl 12)
        val oam2B = ((s.chr and 0x1FFFF) / CHR_SIZE) or (s.pal shl 12)
        val x = s.x
        val y = s.y + s.oam0Attr
        val w = s.width

        sprites.putExt(4, x, y, obj16x8, oam2B)
        sprites.putExt(4, x + (w - 2) * 8, y, obj16x8, oam2B + 4)
        sprites.putExt(4, x, y + 24, obj16x8, oam2B + 0xD)
        sprites.putExt(4, x + (w - 2) * 8, y + 24, obj16x8, oam2B + 0x11)

        sprites.putExt(4, x, y + 8, obj8x8, oam2B + 6)
        sprites.putExt(4, x, y + 16, obj8x8, oam2B + 11)
        sprites.putExt(4, x + (w - 1) * 8, y + 8, obj8x8, oam2B + 10)
        sprites.putExt(4, x + (w - 1) * 8, y + 16, obj8x8, oam2B + 12)

        var i = 2
        while (i < w - 2) {
            sprites.putExt(4, x + i * 8, y, obj16x8, oam2B + 2)
            i += 2
        }
        while (i < w - 1) {
            sprites.putExt(4, x + i * 8, y, obj8x8, oam2B + 2)
            i++
        }

        i = 2
        while (i < w - 2) {
            sprites.putExt(4, x + i * 8, y + 24, obj16x8, oam2B + 15)
            i += 2
        }

        i = 1
        while (i < w - 2) {
            sprites.putExt(4, x + i * 8, y + 8, obj16x8, oam2B + 8)
            sprites.putExt(4, x + i * 8, y + 16, obj16x8, oam2B + 8)
            i += 2
        }
        while (i < w - 1) {
            sprites.putExt(4, x + i * 8, y + 8, obj8x8, oam2B + 8)
            sprites.putExt(4, x + i * 8, y + 16, obj8x8, oam2B + 8)
            i++
        }

        for (k in 0 until 3) {
            sprites.putExt(0, x + 8 + k * 32, y + 8, obj32x16, oam2A + k * 4)
        }
    }

    /**
     * Transcribed from src/popup.c: sub_8011A1C
     * (not verified: no reference test)
     */
    fun sub8011A1C(proc: Proc?, doBlend: Boolean) {
        val s = proc?.state as? BrownTextBoxState ?: return
        s.oam0Attr = if (doBlend) OAM0_BLEND else 0
    }

    /**
     * Transcribed from src/popup.c: sub_8011A48
     * (not verified: no reference test)
     */
    fun sub8011A48(proc: Proc) {
        (proc.state as BrownTextBoxFadeState).blendVal = 0
        val lcd = hw.lcd
        lcd.setBlendConfig(1, 0, 0x10, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(1, 1, 1, 1, 1)
        lcd.setBlendBackdropA(1)
        lcd.setBlendBackdropB(1)
        sub8011A1C(engine.find(boxScript), true)
    }

    /**
     * Transcribed from src/popup.c: sub_8011AA0
     * (not verified: no reference test)
     */
    fun sub8011AA0(proc: Proc) {
        val s = proc.state as BrownTextBoxFadeState
        s.blendVal++
        val blendVal = s.blendVal
        hw.lcd.setBlendConfig(1, blendVal, 0x10 - blendVal, 0)
        if (blendVal == 0x10) {
            engine.breakProc(proc)
            setBlendNone()
            sub8011A1C(engine.find(boxScript), false)
        }
    }

    /**
     * Transcribed from src/popup.c: sub_8011AF4
     * (not verified: no reference test)
     */
    fun sub8011AF4(proc: Proc) {
        (proc.state as BrownTextBoxFadeState).blendVal = 0
        val lcd = hw.lcd
        lcd.setBlendConfig(1, 0x10, 0, 0)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
        lcd.setBlendTargetB(1, 1, 1, 1, 1)
        lcd.setBlendBackdropA(1)
        lcd.setBlendBackdropB(1)
        sub8011A1C(engine.find(boxScript), true)
    }

    /**
     * Transcribed from src/popup.c: sub_8011B4C
     * (not verified: no reference test)
     */
    fun sub8011B4C(proc: Proc) {
        val s = proc.state as BrownTextBoxFadeState
        s.blendVal++
        val blendVal = s.blendVal
        hw.lcd.setBlendConfig(1, 0x10 - blendVal, blendVal, 0)
        if (blendVal == 0x10) {
            engine.end(engine.find(boxScript))
            engine.breakProc(proc)
        }
    }

    /**
     * Transcribed from src/popup.c: sub_8011B90
     * (not verified: no reference test)
     */
    fun sub8011B90() {
        setBlendNone()
    }

    /** The `SetBlendNone` macro (include/hardware.h). */
    private fun setBlendNone() {
        hw.lcd.setBlendConfig(0, 0x10, 0, 0)
    }

    private companion object {
        const val OAM0_BLEND = 0x0400
        const val CHR_SIZE = 32
        const val OBJ_VRAM = 0x10000
        const val G_OBJECT_8X8 = 0x08590F44
        const val G_OBJECT_16X8 = 0x08590F7C
        const val G_OBJECT_32X16 = 0x08590F8C
        const val PAL_TEXT = 0x0859EF00
        const val G_GFX_BROWN_TEXT_BOX = 0x08A4CF2C
        const val G_PAL_BROWN_TEXT_BOX = 0x08A4D0CC
    }
}
