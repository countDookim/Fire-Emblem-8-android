package gbafe.text

/**
 * Hardware and game services [TextSystem] needs; everything outside `fontgrp.c`'s own state.
 */
public interface TextHost {
    /** `GetLang`: the language mode (0 = Japanese / Shift-JIS, 1 = English / ASCII). */
    public fun language(): Int

    /**
     * `ApplyPalette(Pal_Text [+ 0x10 for the talk variant], palid)` followed by
     * `PAL_COLOR(palid, 0) = 0`.
     */
    public fun applyTextPalette(talk: Boolean, palid: Int)

    /** Reads the little-endian 32-bit word at VRAM address [addr] (`0x06000000..`). */
    public fun readVram32(addr: Int): Int

    /** Writes the little-endian 32-bit word [value] at VRAM address [addr]. */
    public fun writeVram32(addr: Int, value: Int)

    /** `CpuFastFill16(0, dest, byteCount)` on VRAM. */
    public fun clearVram(dest: Int, byteCount: Int)
}

/**
 * The text-font part of `src/fontgrp.c`: `gDefaultFont`, `gActiveFont`, font and [Text] setup, and
 * glyph-width measuring. Game-neutral; the game supplies the glyph-table addresses and the
 * function-pointer values through [Config], and its ROM.
 *
 * No call allocates. A glyph pointer that is NULL where C would dereference it throws here
 * (undefined behaviour in C).
 */
public class TextSystem(
    private val rom: ByteArray,
    private val host: TextHost,
    private val config: Config,
) {
    /** Addresses of the game's glyph tables and font routines. */
    public class Config(
        /** `TextGlyphs_System`. */
        public val glyphsSystem: Int,
        /** `TextGlyphs_Talk`. */
        public val glyphsTalk: Int,
        /** Function-pointer value stored in [Font.drawGlyph] (`DrawTextGlyph`). */
        public val drawGlyph: Int,
        /** Function-pointer value stored in [Font.getDrawDest] (`GetTextDrawDest`). */
        public val getDrawDest: Int,
        /** `s2bppTo4bppLutTable`: ROM address of the table of 13 colour-LUT pointers. */
        public val colorLutTable: Int,
        /** Function-pointer value stored in [Font.drawGlyph] (`DrawSpriteTextGlyph`). */
        public val drawSpriteGlyph: Int = 0,
        /** Function-pointer value stored in [Font.getDrawDest] (`GetSpriteTextDrawDest`). */
        public val getSpriteDrawDest: Int = 0,
    )

    /** `gDefaultFont`. */
    public val defaultFont: Font = Font()

    /** `gActiveFont`; null in C until the first `SetTextFont`, here it starts as the default. */
    public var activeFont: Font = defaultFont
        private set

    /**
     * Initialises [font] (null means [defaultFont]) to draw at [vramDest] from tile [chr] with
     * palette [palid], makes it the active font and applies the system font setup.
     *
     * Ported from src/fontgrp.c: InitTextFont
     */
    public fun initTextFont(font: Font?, vramDest: Int, chr: Int, palid: Int) {
        val f = font ?: defaultFont
        f.vramDest = vramDest
        f.getDrawDest = config.getDrawDest
        f.palid = palid
        f.tileref = (chr and 0x3FF) or (palid shl 12)
        f.chrCounter = 0
        f.lang = host.language()
        setTextFont(f)
        initSystemTextFont()
    }

    /**
     * Makes [font] (null means [defaultFont]) the active font.
     *
     * Ported from src/fontgrp.c: SetTextFont
     */
    public fun setTextFont(font: Font?) {
        activeFont = font ?: defaultFont
    }

    /**
     * Selects the active font's glyph table: [GLYPHS_SYSTEM], or the talk table otherwise.
     *
     * Ported from src/fontgrp.c: SetTextFontGlyphs
     */
    public fun setTextFontGlyphs(glyphset: Int) {
        activeFont.glyphs =
            if (glyphset == GLYPHS_SYSTEM) config.glyphsSystem else config.glyphsTalk
    }

    /**
     * Gives [text] the next [tileWidth] tiles of the active font and clears it.
     *
     * Ported from src/fontgrp.c: InitText
     */
    public fun initText(text: Text, tileWidth: Int) {
        text.chrPosition = activeFont.chrCounter
        text.tileWidth = tileWidth
        text.dbId = 0
        text.dbEnabled = false
        text.isPrinting = false
        activeFont.chrCounter += tileWidth
        clearText(text)
    }

    /**
     * Resets [text]'s pen and colour and zeroes its tile strip in VRAM.
     *
     * Ported from src/fontgrp.c: ClearText
     */
    public fun clearText(text: Text) {
        text.x = 0
        text.colorId = COLOR_SYSTEM_WHITE
        host.clearVram(getTextDrawDest(text), text.tileWidth * 2 * CHR_SIZE)
    }

    /**
     * Sets the active font up for the talk window: talk palette, draw routine and talk glyphs.
     *
     * Ported from src/fontgrp.c: InitTalkTextFont
     */
    public fun initTalkTextFont() {
        host.applyTextPalette(true, activeFont.palid)
        activeFont.drawGlyph = config.drawGlyph
        setTextFontGlyphs(GLYPHS_TALK)
    }

    private fun initSystemTextFont() {
        host.applyTextPalette(false, activeFont.palid)
        activeFont.drawGlyph = config.drawGlyph
        setTextFontGlyphs(GLYPHS_SYSTEM)
    }

    /**
     * Width in pixels of the string at [str]`[start]` up to its terminator or newline (0x01);
     * ASCII fonts (`lang != 0`) delegate to [getStringTextLenAscii].
     *
     * Ported from src/fontgrp.c: GetStringTextLen
     */
    public fun getStringTextLen(str: ByteArray, start: Int = 0): Int {
        if (activeFont.lang != 0) return getStringTextLenAscii(str, start)
        return getStringTextLenSjis(str, start)
    }

    /**
     * Measures the character at [str]`[start]`: stores its width in `pWidth[0]` (left unchanged if
     * the glyph is not found, as in C) and returns the offset after the character; ASCII fonts
     * delegate to [getCharTextLenAscii].
     *
     * Ported from src/fontgrp.c: GetCharTextLen
     */
    public fun getCharTextLen(str: ByteArray, start: Int, pWidth: IntArray): Int {
        if (activeFont.lang != 0) return getCharTextLenAscii(str, start, pWidth)
        return getCharTextLenSjis(str, start, pWidth)
    }

    /**
     * ASCII width of the string at [start].
     *
     * Ported from src/fontgrp.c: GetStringTextLenASCII
     */
    public fun getStringTextLenAscii(str: ByteArray, start: Int = 0): Int {
        var width = 0
        var i = start
        while (str[i].toInt() != 0 && str[i].toInt() != CHAR_NEWLINE) {
            width += glyphWidth(glyphAt(activeFont.glyphs, str[i++].toInt() and 0xFF))
        }
        return width
    }

    /**
     * ASCII width of one character; a missing glyph falls back to `'?'`.
     *
     * Ported from src/fontgrp.c: GetCharTextLenASCII
     */
    public fun getCharTextLenAscii(str: ByteArray, start: Int, width: IntArray): Int {
        var glyph = glyphAt(activeFont.glyphs, str[start].toInt() and 0xFF)
        if (glyph == 0) glyph = glyphAt(activeFont.glyphs, QUESTION_MARK)
        width[0] = glyphWidth(glyph)
        return start + 1
    }

    /**
     * VRAM address of [text]'s current tile strip, at the pen's tile: the `get_draw_dest` of the
     * active font.
     *
     * Ported from src/fontgrp.c: GetTextDrawDest
     */
    public fun getTextDrawDest(text: Text): Int {
        if (config.getSpriteDrawDest != 0 && activeFont.getDrawDest == config.getSpriteDrawDest) {
            return getSpriteTextDrawDest(text)
        }
        val chrNumber = text.dbId * text.tileWidth + text.chrPosition + text.x / 8
        return activeFont.vramDest + chrNumber * 2 * CHR_SIZE
    }

    /**
     * Initialises [font] for sprite text drawn to OBJ tiles at [vramDest] with palette [c] and
     * makes it the active font.
     *
     * Transcribed from src/fontgrp.c: InitSpriteTextFont
     * (not verified: compared only through the Prologue brown text box tiles)
     */
    public fun initSpriteTextFont(font: Font, vramDest: Int, c: Int) {
        font.vramDest = vramDest
        font.getDrawDest = config.getSpriteDrawDest
        font.palid = (c and 0xF) + 16
        font.tileref = (vramDest and 0x1FFFF) shr 5
        font.chrCounter = 0
        font.lang = host.language()
        setTextFont(font)
        font.drawGlyph = config.drawSpriteGlyph
    }

    /**
     * Gives [text] a 32-tile strip of the active font (64 tiles of its tile counter).
     *
     * Transcribed from src/fontgrp.c: InitSpriteText
     * (not verified: compared only through the Prologue brown text box tiles)
     */
    public fun initSpriteText(text: Text) {
        text.chrPosition = activeFont.chrCounter
        text.tileWidth = 32
        text.dbId = 0
        text.dbEnabled = false
        text.isPrinting = false
        activeFont.chrCounter += 64
        text.x = 0
        text.colorId = 0
    }

    /**
     * Resets the pen and fills 0x800 bytes at the draw destination with the word [b].
     *
     * Transcribed from src/fontgrp.c: SpriteText_DrawBackgroundExt
     * (not verified: compared only through the Prologue brown text box tiles)
     */
    public fun spriteTextDrawBackgroundExt(text: Text, b: Int) {
        text.x = 0
        val dest = getTextDrawDest(text)
        for (i in 0 until 0x800 step 4) host.writeVram32(dest + i, b)
    }

    /**
     * VRAM address of the sprite text tile at the pen.
     *
     * Ported from src/fontgrp.c: GetSpriteTextDrawDest
     * (verified through the Prologue brown text box tiles in ram_trace_newgame_bm f09559; not
     * covered: dbId != 0, chrPosition != 0)
     */
    public fun getSpriteTextDrawDest(text: Text): Int =
        activeFont.vramDest + (text.dbId * text.tileWidth + text.chrPosition + text.x / 8) * CHR_SIZE

    /**
     * Draws [glyph] (ROM address) as OBJ tiles (two 8-row halves, the second `0x400` bytes on)
     * and advances the pen.
     *
     * Ported from src/fontgrp.c: DrawSpriteTextGlyph
     * (verified through the Prologue brown text box tiles in ram_trace_newgame_bm f09559; not
     * covered: colour ids other than 0)
     */
    public fun drawSpriteTextGlyph(text: Text, glyph: Int) {
        val lut = getColorLut(text.colorId) - ROM_BASE
        val shift = (text.x and 7) * 2
        val bitmap = glyph - ROM_BASE + GLYPH_BITMAP
        for (half in 0..1) {
            val dest = getTextDrawDest(text) + half * 0x400
            for (i in 0 until 8) {
                val row = (readU32(bitmap + (half * 8 + i) * 4).toLong() and 0xFFFFFFFFL) shl shift
                orWord(dest + i * 4, lutWord(lut, row.toInt()))
                orWord(dest + i * 4 + 32, lutWord(lut, (row shr 16).toInt()))
                orWord(dest + i * 4 + 64, lutWord(lut, (row shr 32).toInt()))
            }
        }
        text.x += glyphWidth(glyph)
    }

    /**
     * Pen x that centres [str] in [x] pixels.
     *
     * Transcribed from src/fontgrp.c: GetStringTextCenteredPos
     * (not verified: compared only through the Prologue brown text box tiles)
     */
    public fun getStringTextCenteredPos(x: Int, str: ByteArray, start: Int = 0): Int =
        (x - getStringTextLen(str, start)) / 2

    /**
     * Sets the pen x and colour of [text], then draws [str].
     *
     * Transcribed from src/fontgrp.c: Text_InsertDrawString
     * (not verified: compared only through the Prologue brown text box tiles)
     */
    public fun textInsertDrawString(text: Text, x: Int, colorId: Int, str: ByteArray) {
        text.x = x
        text.colorId = colorId
        drawString(text, str)
    }

    /**
     * ROM address of the 256-entry 2bpp-to-4bpp lookup table (16-bit entries) for [color].
     *
     * Ported from src/fontgrp.c: GetColorLut
     */
    public fun getColorLut(color: Int): Int =
        readU32(config.colorLutTable - ROM_BASE + color * 4)

    /**
     * Draws [glyph] (ROM address) at the pen of [text] with its colour and advances the pen by the
     * glyph width. The pixels are OR-ed into the tiles, which must have been cleared.
     *
     * Ported from src/fontgrp.c: DrawTextGlyph
     */
    public fun drawTextGlyph(text: Text, glyph: Int) {
        val dest = getTextDrawDest(text)
        val subx = text.x and 7
        drawGlyphRam(getColorLut(text.colorId), dest, glyph + GLYPH_BITMAP, subx)
        text.x = (text.x + glyphWidth(glyph)) and 0xFF
    }

    /**
     * Calls the active font's `drawGlyph` function pointer; only `DrawTextGlyph` is ported.
     */
    private fun callDrawGlyph(text: Text, glyph: Int) {
        if (config.drawSpriteGlyph != 0 && activeFont.drawGlyph == config.drawSpriteGlyph) {
            drawSpriteTextGlyph(text, glyph)
            return
        }
        check(activeFont.drawGlyph == config.drawGlyph) { "unsupported drawGlyph pointer" }
        drawTextGlyph(text, glyph)
    }

    /**
     * Draws the 16 rows of a 2bpp glyph [bitmap] (ROM address) shifted right by [subx] pixels into
     * the 4bpp tiles at [dest], OR-ing in the pixels through the colour table [lut]: the ARM
     * routine `DrawGlyph` of arm.s (reached through `DrawGlyphRam`).
     *
     * Transcribed from asm/arm.s: DrawGlyph (not verified: exercised only through the
     * Text_DrawCharacter test).
     */
    private fun drawGlyphRam(lut: Int, dest: Int, bitmap: Int, subx: Int) {
        val lutBase = lut - ROM_BASE
        for (row in 0 until 16) {
            val wide = (readU32(bitmap - ROM_BASE + row * 4).toLong() and 0xFFFFFFFFL) shl
                (subx * 2)
            val d = dest + row * 4
            orWord(d, lutWord(lutBase, wide.toInt()))
            orWord(d + 0x40, lutWord(lutBase, (wide shr 16).toInt()))
            orWord(d + 0x80, lutWord(lutBase, (wide shr 32).toInt()))
        }
    }

    /** Two LUT entries for the low two bytes of [v]: byte 0 in bits 0-15, byte 1 in 16-31. */
    private fun lutWord(lutBase: Int, v: Int): Int {
        val lo = rom[lutBase + (v and 0xFF) * 2].toInt() and 0xFF or
            ((rom[lutBase + (v and 0xFF) * 2 + 1].toInt() and 0xFF) shl 8)
        val hi = (v shr 8) and 0xFF
        val hv = rom[lutBase + hi * 2].toInt() and 0xFF or
            ((rom[lutBase + hi * 2 + 1].toInt() and 0xFF) shl 8)
        return lo or (hv shl 16)
    }

    private fun orWord(addr: Int, value: Int) {
        host.writeVram32(addr, host.readVram32(addr) or value)
    }

    /**
     * Draws the character at [str]`[start]` and returns the offset after it. ASCII fonts delegate
     * to [drawCharacterAscii].
     *
     * Ported from src/fontgrp.c: Text_DrawCharacter
     */
    public fun drawCharacter(text: Text, str: ByteArray, start: Int): Int {
        if (activeFont.lang != 0) return drawCharacterAscii(text, str, start)
        return drawCharacterSjis(text, str, start)
    }

    /**
     * ASCII character draw; a missing glyph falls back to `'?'`.
     *
     * Ported from src/fontgrp.c: Text_DrawCharacterAscii
     */
    public fun drawCharacterAscii(text: Text, str: ByteArray, start: Int): Int {
        var glyph = glyphAt(activeFont.glyphs, str[start].toInt() and 0xFF)
        if (glyph == 0) glyph = glyphAt(activeFont.glyphs, QUESTION_MARK)
        callDrawGlyph(text, glyph)
        return start + 1
    }

    // Transcribed from src/fontgrp.c: Text_DrawCharacter (Shift-JIS branch; not verified: the US
    // ROM has no Shift-JIS glyph lists and the trace runs with lang = 1).
    // Not @VerifiesPort: no ground truth for lang == 0.
    private fun drawCharacterSjis(text: Text, str: ByteArray, start: Int): Int {
        var byte1 = str[start].toInt() and 0xFF
        var byte2 = str[start + 1].toInt() and 0xFF
        while (true) {
            var glyph = glyphAt(activeFont.glyphs, (byte2 - 0x40) and 0xFF)
            while (glyph != 0) {
                if ((rom[glyph - ROM_BASE + 4].toInt() and 0xFF) == byte1) {
                    callDrawGlyph(text, glyph)
                    break
                }
                glyph = readU32(glyph - ROM_BASE)
            }
            if (glyph != 0) break
            byte1 = 0x81
            byte2 = 0xA7
        }
        return start + 2
    }

    /**
     * Draws the string at [start] up to its terminator or newline. ASCII fonts take the ASCII path.
     *
     * Ported from src/fontgrp.c: Text_DrawString
     */
    public fun drawString(text: Text, str: ByteArray, start: Int = 0) {
        if (activeFont.lang != 0) {
            drawStringAscii(text, str, start)
            return
        }
        drawStringSjis(text, str, start)
    }

    /**
     * ASCII string draw.
     *
     * Ported from src/fontgrp.c: Text_DrawStringASCII
     */
    public fun drawStringAscii(text: Text, str: ByteArray, start: Int = 0) {
        var i = start
        while (str[i].toInt() != 0 && str[i].toInt() != CHAR_NEWLINE) {
            i = drawCharacterAscii(text, str, i)
        }
    }

    // Transcribed from src/fontgrp.c: Text_DrawString (Shift-JIS branch; not verified: see above).
    // Not @VerifiesPort: no ground truth for lang == 0.
    private fun drawStringSjis(text: Text, str: ByteArray, start: Int) {
        var i = start
        while (str[i].toInt() != 0 && str[i].toInt() != CHAR_NEWLINE) {
            var byte1 = str[i++].toInt() and 0xFF
            if (byte1 >= 0x20) {
                var byte2 = str[i++].toInt() and 0xFF
                var retry = true
                while (retry) {
                    retry = false
                    var glyph = glyphAt(activeFont.glyphs, (byte2 - 0x40) and 0xFF)
                    while (glyph != 0) {
                        if ((rom[glyph - ROM_BASE + 4].toInt() and 0xFF) == byte1) {
                            callDrawGlyph(text, glyph)
                            break
                        }
                        glyph = readU32(glyph - ROM_BASE)
                        if (glyph == 0) {
                            byte1 = 0x81
                            byte2 = 0xA7
                            retry = true
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes the tilemap entries of [text]'s two tile rows to [tm] at [offset] (row 2 is 0x20
     * entries later) and toggles the double-buffer id when enabled.
     *
     * Ported from src/fontgrp.c: PutText
     */
    public fun putText(text: Text, tm: ShortArray, offset: Int) {
        var tileref = activeFont.tileref + (text.dbId * text.tileWidth + text.chrPosition) * 2
        for (i in 0 until text.tileWidth) {
            tm[offset + i] = tileref++.toShort()
            tm[offset + i + 0x20] = tileref++.toShort()
        }
        if (text.dbEnabled) text.dbId = text.dbId xor 1
    }

    // Transcribed from src/fontgrp.c: GetStringTextLen (Shift-JIS branch; not verified: the US
    // ROM has no Shift-JIS glyph lists and the trace runs with lang = 1).
    // Not @VerifiesPort: no ground truth for lang == 0.
    private fun getStringTextLenSjis(str: ByteArray, start: Int): Int {
        var width = 0
        var i = start
        while (str[i].toInt() != 0 && str[i].toInt() != CHAR_NEWLINE) {
            val byte1 = str[i++].toInt()
            if (byte1 >= 0x20) {
                val byte2 = str[i++].toInt()
                var glyph = glyphAt(activeFont.glyphs, (byte2 - 0x40) and 0xFF)
                while (glyph != 0) {
                    if (rom[glyph - ROM_BASE + 4].toInt() == byte1) {
                        width += glyphWidth(glyph)
                        break
                    }
                    glyph = readU32(glyph - ROM_BASE)
                }
            }
        }
        return width
    }

    // Transcribed from src/fontgrp.c: GetCharTextLen (Shift-JIS branch; not verified: see above).
    // Not @VerifiesPort: no ground truth for lang == 0.
    private fun getCharTextLenSjis(str: ByteArray, start: Int, pWidth: IntArray): Int {
        val byte1 = str[start]
        val byte2 = str[start + 1].toInt()
        var glyph = glyphAt(activeFont.glyphs, (byte2 - 0x40) and 0xFF)
        while (glyph != 0) {
            if (rom[glyph - ROM_BASE + 4] == byte1) {
                pWidth[0] = glyphWidth(glyph)
                break
            }
            glyph = readU32(glyph - ROM_BASE)
        }
        return start + 2
    }

    /** `glyphs[index]`: the glyph address, 0 for NULL. */
    private fun glyphAt(table: Int, index: Int): Int = readU32(table - ROM_BASE + index * 4)

    /** `glyph->width` (`struct Glyph` +5). */
    private fun glyphWidth(glyph: Int): Int = rom[glyph - ROM_BASE + 5].toInt() and 0xFF

    private fun readU32(o: Int): Int =
        (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8) or
            ((rom[o + 2].toInt() and 0xFF) shl 16) or ((rom[o + 3].toInt() and 0xFF) shl 24)

    /** Constants of the text system. */
    public companion object {
        /** `TEXT_GLYPHS_SYSTEM`. */
        public const val GLYPHS_SYSTEM: Int = 0

        /** `TEXT_GLYPHS_TALK`. */
        public const val GLYPHS_TALK: Int = 1

        /** `TEXT_COLOR_SYSTEM_WHITE`. */
        public const val COLOR_SYSTEM_WHITE: Int = 0

        /** `CHAR_NEWLINE`. */
        public const val CHAR_NEWLINE: Int = 0x01

        private const val CHR_SIZE = 0x20
        private const val GLYPH_BITMAP = 8
        private const val ROM_BASE = 0x08000000
        private const val QUESTION_MARK = '?'.code
    }
}
