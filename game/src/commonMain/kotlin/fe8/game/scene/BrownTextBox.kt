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

/** State of a `ProcScr_BrownTextBox` proc: the `struct BrownTextBoxProc` fields the loop reads. */
internal class BrownTextBoxState {
    /** Screen X of the box's top-left corner in pixels (`x`, 0x30). */
    var x: Int = 0

    /** Screen Y of the box's top-left corner in pixels (`y`, 0x34). */
    var y: Int = 0

    /**
     * Byte offset in OBJ VRAM of the box tiles; the text tiles follow 0x400 bytes on (`chr`, 0x38).
     */
    var chr: Int = 0

    /** OBJ palette bank of the box frame; the text uses the bank after it (`pal`, 0x3C). */
    var pal: Int = 0

    /** Id of the message shown in the box (`textId`, 0x40). */
    var textId: Int = 0

    /** Box width in 8-pixel tiles: the text width in tiles plus 5 (`width`, 0x44). */
    var width: Int = 0

    /** OAM0 bits ORed into every sprite's y: 0, or `OAM0_BLEND` while fading (`oam0Attr`, 0x48). */
    var oam0Attr: Int = 0
}

/** State of the fade procs, the `blendVal` field (0x4C) of the box proc's layout. */
internal class BrownTextBoxFadeState {
    /** Frames into the fade, which is also the blend weight in sixteenths (0..16, `blendVal`). */
    var blendVal: Int = 0
}

/**
 * The brown text box of `src/popup.c` (`StartBrownTextBox` and its procs): a frame of OBJ
 * sprites around one line of sprite text that fades in over 16 frames, stays 100 frames and
 * fades out over 16, blocking its parent proc. The event command that shows it is the popup
 * command's subcommand 1 (`src/eventscr.c`).
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

    /** `ProcScr_BrownTextBox`: draws the box every frame ([brownTextBoxLoop]). */
    val boxScript: ProcScript = ProcScript(
        "ProcScr_BrownTextBox",
        listOf(yield(), setEndCb(ProcFunc { }), repeat(ProcFunc { brownTextBoxLoop(it) }), end()),
    ) { BrownTextBoxState() }

    /** `ProcScr_08592530`: fade in, wait 100 frames, fade out, then blending is switched off. */
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
     * Shows message [textId] in a brown box at pixel ([x], [y]), blocking [parent] until the box
     * has faded out. The box tiles are at OBJ byte offset 0x5000 and use palette bank 9.
     *
     * Ported from src/popup.c: StartBrownTextBox
     */
    fun startBrownTextBox(textId: Int, x: Int, y: Int, parent: Proc) {
        startBrownTextBoxCore(x, y, textId, 0x5000, 9, parent)
        engine.startBlocking(fadeScript, parent)
    }

    /**
     * Starts the box proc and loads its palettes (the box colours in OBJ bank [pal], the system
     * text palette in the next bank) and tiles at OBJ byte offset [chr], then sizes the box to
     * the message ([textWidth] / 8 + 5 tiles) and draws the text centred in it.
     *
     * Ported from src/popup.c: StartBrownTextBoxCore
     * (verified at the hold frame against ram_trace_newgame_bm f09559: OAM width, palettes, box
     * and text tiles in OBJ VRAM; not covered: other chr/pal/x/y values, other message lengths)
     */
    fun startBrownTextBoxCore(x: Int, y: Int, textId: Int, chr: Int, pal: Int, parent: Proc) {
        val proc = engine.start(boxScript, parent)
        val boxState = proc.state as BrownTextBoxState
        boxState.x = x
        boxState.y = y
        boxState.chr = chr
        boxState.pal = pal
        boxState.textId = textId
        boxState.oam0Attr = 0

        applyPalette(G_PAL_BROWN_TEXT_BOX, boxState.pal + 0x10)
        applyPalette(PAL_TEXT, boxState.pal + 0x11)
        rom.decompressInto(G_GFX_BROWN_TEXT_BOX, hw.vram, OBJ_VRAM + boxState.chr)

        val textTileWidth = textWidth(textId) / 8
        boxState.width = textTileWidth + 5
        drawText(textId, boxState.chr, boxState.pal, (textTileWidth + 3) * 8)
    }

    private fun applyPalette(src: Int, palId: Int) {
        rom.pal(src, 16).copyInto(hw.palette, palId * 16)
        hw.enablePaletteSync()
    }

    /**
     * Puts the box on screen for one frame: a frame of 16x8 and 8x8 sprites (corners, top and
     * bottom edges, side pieces and the filled middle; the frame is 4 sprite rows = 32 pixels
     * high) on sprite layer 4, and the three 32x16 sprites that carry the text tiles on layer 0.
     * `oam0Attr` adds the blend flag to every sprite's y while fading.
     *
     * Ported from src/popup.c: BrownTextBox_Loop
     * (verified at the hold frame against ram_trace_newgame_bm f09559 for one width (12 tiles);
     * not covered: the OAM0_BLEND frames, other widths, odd/even loop remainders)
     */
    fun brownTextBoxLoop(proc: Proc) {
        val boxState = proc.state as BrownTextBoxState
        val textOam2 = (((boxState.chr + 0x400) and 0x1FFFF) / CHR_SIZE) or
            ((boxState.pal + 1) shl 12)
        val frameOam2 = ((boxState.chr and 0x1FFFF) / CHR_SIZE) or (boxState.pal shl 12)
        val x = boxState.x
        val y = boxState.y + boxState.oam0Attr
        val boxWidth = boxState.width

        sprites.putExt(4, x, y, obj16x8, frameOam2)
        sprites.putExt(4, x + (boxWidth - 2) * 8, y, obj16x8, frameOam2 + 4)
        sprites.putExt(4, x, y + 24, obj16x8, frameOam2 + 0xD)
        sprites.putExt(4, x + (boxWidth - 2) * 8, y + 24, obj16x8, frameOam2 + 0x11)

        sprites.putExt(4, x, y + 8, obj8x8, frameOam2 + 6)
        sprites.putExt(4, x, y + 16, obj8x8, frameOam2 + 11)
        sprites.putExt(4, x + (boxWidth - 1) * 8, y + 8, obj8x8, frameOam2 + 10)
        sprites.putExt(4, x + (boxWidth - 1) * 8, y + 16, obj8x8, frameOam2 + 12)

        var i = 2
        while (i < boxWidth - 2) {
            sprites.putExt(4, x + i * 8, y, obj16x8, frameOam2 + 2)
            i += 2
        }
        while (i < boxWidth - 1) {
            sprites.putExt(4, x + i * 8, y, obj8x8, frameOam2 + 2)
            i++
        }

        i = 2
        while (i < boxWidth - 2) {
            sprites.putExt(4, x + i * 8, y + 24, obj16x8, frameOam2 + 15)
            i += 2
        }

        i = 1
        while (i < boxWidth - 2) {
            sprites.putExt(4, x + i * 8, y + 8, obj16x8, frameOam2 + 8)
            sprites.putExt(4, x + i * 8, y + 16, obj16x8, frameOam2 + 8)
            i += 2
        }
        while (i < boxWidth - 1) {
            sprites.putExt(4, x + i * 8, y + 8, obj8x8, frameOam2 + 8)
            sprites.putExt(4, x + i * 8, y + 16, obj8x8, frameOam2 + 8)
            i++
        }

        for (k in 0 until 3) {
            sprites.putExt(0, x + 8 + k * 32, y + 8, obj32x16, textOam2 + k * 4)
        }
    }

    /**
     * Sets whether the box's sprites are alpha-blended (`OAM0_BLEND`), which they are only while
     * fading; does nothing when [proc] is not a box proc.
     *
     * Ported from src/popup.c: sub_8011A1C
     */
    fun sub8011A1C(proc: Proc?, doBlend: Boolean) {
        val boxState = proc?.state as? BrownTextBoxState ?: return
        boxState.oam0Attr = if (doBlend) OAM0_BLEND else 0
    }

    /**
     * Start of the fade-in: alpha blending of the sprites against everything behind (weight 0 of
     * the box, 16 of the rest, so the box is invisible), and the box sprites switched to blend.
     *
     * Ported from src/popup.c: sub_8011A48
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
     * One fade-in frame: raises the box's blend weight by 1/16; at 16 the proc ends, blending is
     * switched off and the box sprites are opaque again.
     *
     * Ported from src/popup.c: sub_8011AA0
     */
    fun sub8011AA0(proc: Proc) {
        val fadeState = proc.state as BrownTextBoxFadeState
        fadeState.blendVal++
        val blendVal = fadeState.blendVal
        hw.lcd.setBlendConfig(1, blendVal, 0x10 - blendVal, 0)
        if (blendVal == 0x10) {
            engine.breakProc(proc)
            setBlendNone()
            sub8011A1C(engine.find(boxScript), false)
        }
    }

    /**
     * Start of the fade-out: alpha blending with the box at full weight, and the box sprites
     * switched to blend.
     *
     * Ported from src/popup.c: sub_8011AF4
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
     * One fade-out frame: lowers the box's blend weight by 1/16; at 0 the box proc and this proc
     * end.
     *
     * Ported from src/popup.c: sub_8011B4C
     */
    fun sub8011B4C(proc: Proc) {
        val fadeState = proc.state as BrownTextBoxFadeState
        fadeState.blendVal++
        val blendVal = fadeState.blendVal
        hw.lcd.setBlendConfig(1, 0x10 - blendVal, blendVal, 0)
        if (blendVal == 0x10) {
            engine.end(engine.find(boxScript))
            engine.breakProc(proc)
        }
    }

    /**
     * After the fade-out: switches blending off (`SetBlendNone`).
     *
     * Ported from src/popup.c: sub_8011B90
     */
    fun sub8011B90() {
        setBlendNone()
    }

    /** The `SetBlendNone` macro (include/hardware.h). */
    private fun setBlendNone() {
        hw.lcd.setBlendConfig(0, 0x10, 0, 0)
    }

    private companion object {
        /** `OAM0_BLEND`: OAM attribute 0 bit that makes a sprite semi-transparent. */
        const val OAM0_BLEND = 0x0400

        /** Bytes of one 8x8 4bpp tile. */
        const val CHR_SIZE = 32

        /** Offset of the OBJ tile data in VRAM (`0x06010000`). */
        const val OBJ_VRAM = 0x10000

        /** `gObject_8x8`, `gObject_16x8` and `gObject_32x16`: ROM sprite compositions. */
        const val G_OBJECT_8X8 = 0x08590F44
        const val G_OBJECT_16X8 = 0x08590F7C
        const val G_OBJECT_32X16 = 0x08590F8C

        /** `Pal_Text`: the system text palette. */
        const val PAL_TEXT = 0x0859EF00

        /** `gGfx_BrownTextBox`: LZ77 tiles of the box frame. */
        const val G_GFX_BROWN_TEXT_BOX = 0x08A4CF2C

        /** `gPal_BrownTextBox`: the box colours. */
        const val G_PAL_BROWN_TEXT_BOX = 0x08A4D0CC
    }
}
