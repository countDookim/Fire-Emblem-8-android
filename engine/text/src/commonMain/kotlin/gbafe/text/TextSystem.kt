package gbafe.text

/**
 * Hardware and game services [TextSystem] needs: the language setting, the palette copy and raw
 * VRAM access. Everything else of `src/fontgrp.c` is [TextSystem]'s own state.
 */
public interface TextHost {
    /** `GetLang` (`gLanguageMode`): `LANG_JAPANESE` (0, Shift-JIS) or `LANG_ENGLISH` (1, ASCII). */
    public fun language(): Int

    /**
     * Loads the text palette into bank [palid]: `ApplyPalette(Pal_Text, palid)` (the system
     * palette) or, when [talk] is set, `ApplyPalette(Pal_Text + 0x10, palid)` (the talk-window
     * palette); then makes colour 0 of the bank transparent, `PAL_COLOR(palid, 0) = 0`.
     */
    public fun applyTextPalette(talk: Boolean, palid: Int)

    /** Reads the little-endian 32-bit word at VRAM address [addr] (`0x06000000..`). */
    public fun readVram32(addr: Int): Int

    /** Writes the little-endian 32-bit word [value] at VRAM address [addr]. */
    public fun writeVram32(addr: Int, value: Int)

    /** Zeroes [byteCount] bytes of VRAM at [dest]: `CpuFastFill16(0, dest, byteCount)`. */
    public fun clearVram(dest: Int, byteCount: Int)
}

/**
 * The text-font part of `src/fontgrp.c`: `gDefaultFont`, `gActiveFont`, font and [Text] setup,
 * glyph drawing into VRAM tiles and glyph-width measuring. Game-neutral; the game supplies the
 * glyph-table addresses and the function-pointer values through [Config], and its ROM. Not ported
 * here: the other number and special-character sizes, `DrawTextGlyphNoClear` and the text-print
 * procs.
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
        /** ROM address of `TextGlyphs_System`, the glyph table of menus and system text. */
        public val glyphsSystem: Int,
        /** ROM address of `TextGlyphs_Talk`, the glyph table of the dialogue window font. */
        public val glyphsTalk: Int,
        /** Function-pointer value stored in [Font.drawGlyph] (`DrawTextGlyph`). */
        public val drawGlyph: Int,
        /** Function-pointer value stored in [Font.getDrawDest] (`GetTextDrawDest`). */
        public val getDrawDest: Int,
        /**
         * `s2bppTo4bppLutTable`: ROM address of the table of 13 colour-LUT pointers in FE8U,
         * indexed by [Text.colorId] (see [getColorLut]).
         */
        public val colorLutTable: Int,
        /**
         * Value stored in [Font.drawGlyph] by sprite fonts (`DrawSpriteTextGlyph`); 0 if unused.
         */
        public val drawSpriteGlyph: Int = 0,
        /**
         * Value stored in [Font.getDrawDest] by sprite fonts (`GetSpriteTextDrawDest`); 0 if
         * unused.
         */
        public val getSpriteDrawDest: Int = 0,
        /** ROM address of `TextGlyphs_Special`, the glyph pointers of the special characters. */
        public val glyphsSpecial: Int = 0,
    )

    /** `sSpecialCharStList[64]`: the colour (-1 ends the list), id and tile slot of each entry. */
    private val specialColor = IntArray(SPECIAL_CHAR_COUNT).also { it[0] = -1 }
    private val specialId = IntArray(SPECIAL_CHAR_COUNT)
    private val specialChr = IntArray(SPECIAL_CHAR_COUNT)

    /** `gDefaultFont`. */
    public val defaultFont: Font = Font()

    /**
     * `gActiveFont`, the font every draw and measure call uses. It is null in C until the first
     * `SetTextFont`; here it starts as [defaultFont].
     */
    public var activeFont: Font = defaultFont
        private set

    /**
     * Initialises [font] (null means [defaultFont]) as a BG font drawing to [vramDest], whose first
     * tile is number [chr] in palette bank [palid], makes it the active font and applies the
     * system font setup (system palette, system glyphs).
     * The `tileref` is `TILEREF(chr, palid)` (`chr + (palid shl 12)`); this port masks [chr] to 10
     * bits first, which differs from C only for tile numbers above 0x3FF.
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
     * Transcribed from src/fontgrp.c: ResetTextFont (not verified: the captured window starts
     * after its caller, `PlayerPhase_ApplyUnitMovement`; the tests call it as that caller does)
     *
     * Frees the active font's tiles and forgets the special characters drawn.
     */
    public fun resetTextFont() {
        activeFont.chrCounter = 0
        specialColor[0] = -1
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
     * Selects the active font's glyph table: the system glyphs for [GLYPHS_SYSTEM] (menus), the
     * talk glyphs otherwise ([GLYPHS_TALK], dialogue).
     *
     * Ported from src/fontgrp.c: SetTextFontGlyphs
     */
    public fun setTextFontGlyphs(glyphset: Int) {
        activeFont.glyphs =
            if (glyphset == GLYPHS_SYSTEM) config.glyphsSystem else config.glyphsTalk
    }

    /**
     * Gives [text] the next [tileWidth] tile columns of the active font and clears it. Each column
     * is two tiles tall, so the font's tile counter advances by [tileWidth] column slots.
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
     * Gives [text] the next [tileWidth] tile columns of the active font twice over, for double
     * buffering ([putText] flips between the two halves), without clearing it.
     *
     * Ported from src/fontgrp.c: InitTextDb
     */
    public fun initTextDb(text: Text, tileWidth: Int) {
        text.chrPosition = activeFont.chrCounter
        text.tileWidth = tileWidth
        text.dbId = 0
        text.dbEnabled = true
        text.isPrinting = false
        activeFont.chrCounter += tileWidth * 2
    }

    /**
     * Resets [text]'s pen to 0 and its colour to white (`TEXT_COLOR_SYSTEM_WHITE`) and zeroes its
     * whole tile strip in VRAM, `tileWidth * 2` tiles of `CHR_SIZE` bytes.
     *
     * Ported from src/fontgrp.c: ClearText
     */
    public fun clearText(text: Text) {
        text.x = 0
        text.colorId = COLOR_SYSTEM_WHITE
        host.clearVram(getTextDrawDest(text), text.tileWidth * 2 * CHR_SIZE)
    }

    /**
     * Switches the active font to dialogue text: the talk palette, the normal glyph-drawing routine
     * and the talk glyph table (the system variant is `initSystemTextFont`).
     *
     * Ported from src/fontgrp.c: InitTalkTextFont
     */
    public fun initTalkTextFont() {
        host.applyTextPalette(true, activeFont.palid)
        activeFont.drawGlyph = config.drawGlyph
        setTextFontGlyphs(GLYPHS_TALK)
    }

    /**
     * `InitSystemTextFont` (`src/fontgrp.c`): the active font's palette bank gets the system text
     * palette, drawing uses `DrawTextGlyph` and the glyphs are the system table.
     */
    private fun initSystemTextFont() {
        host.applyTextPalette(false, activeFont.palid)
        activeFont.drawGlyph = config.drawGlyph
        setTextFontGlyphs(GLYPHS_SYSTEM)
    }

    /**
     * Width in pixels of the string at [str]`[start]` up to its terminator or newline
     * (`CHAR_NEWLINE`, 0x01). ASCII fonts (`lang != 0`) delegate to [getStringTextLenAscii];
     * Shift-JIS fonts add up the glyph found for each two-byte character (nothing for an
     * unmatched one) and skip a lone byte below 0x20.
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
     * ASCII width of the string at [start]: the sum of the glyph widths up to the terminator or
     * newline. Unlike [getCharTextLenAscii] there is no `'?'` fallback: a missing glyph is a NULL
     * dereference in C and throws here.
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
     * ASCII width of one character, stored in `width[0]`; a missing glyph falls back to the
     * glyph of `'?'`. Returns the offset after the character.
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
     * VRAM address of the tile under [text]'s pen: the strip start plus the pen's tile column
     * (`x / 8`), in the active font's tile area; each column is two tiles (`2 * CHR_SIZE` bytes).
     * This is the `get_draw_dest` callback of a BG font; when the active font's pointer is
     * [Config.getSpriteDrawDest] the call goes to [getSpriteTextDrawDest], as the C function
     * pointer would.
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
     * Initialises [font] as a sprite (OBJ) font drawing to the OBJ tiles at [vramDest] and makes it
     * the active font. [c] is the OBJ palette number (only its low 4 bits count; the font's
     * `palid` is `(c and 0xF) + 16`); `tileref` is the OBJ tile number of [vramDest].
     *
     * Ported from src/fontgrp.c: InitSpriteTextFont
     * (verified through `sTalkFont` in WorldMapEventHostTest)
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
     * Gives [text] a sprite-text strip: 32 tile columns of two rows, so the font's tile counter
     * advances by 64 tiles. Pen and colour are reset (colour 0) without touching VRAM.
     *
     * Ported from src/fontgrp.c: InitSpriteText
     * (verified through `sTalkText` in WorldMapEventHostTest)
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
     * Resets the pen and zeroes the first 0x360 bytes of each of the two tile rows of [text]'s
     * strip (the second row is 0x400 bytes on); does nothing when it has no tiles.
     *
     * Transcribed from src/fontgrp.c: SpriteText_Clear (not verified: the bytes 0x340..0x360 of a
     * row are never glyph data in the capture; WorldMapEventHostTest checks the rest)
     */
    public fun spriteTextClear(text: Text) {
        if (text.tileWidth == 0) return
        text.x = 0
        val dest = getTextDrawDest(text)
        for (i in 0 until 0x360 step 4) {
            host.writeVram32(dest + i, 0)
            host.writeVram32(dest + 0x400 + i, 0)
        }
    }

    /** Reads the 32-bit word at VRAM address [addr] through the host. */
    public fun readVramWord(addr: Int): Int = host.readVram32(addr)

    /** Writes the 32-bit word [value] at VRAM address [addr] through the host. */
    public fun writeVramWord(addr: Int, value: Int) {
        host.writeVram32(addr, value)
    }

    /**
     * Resets the pen and fills 0x800 bytes (both tile rows of a 32-column strip) at the draw
     * destination with the 32-bit pattern [b] (a word of eight 4bpp pixels).
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
     * VRAM address of the OBJ tile under [text]'s pen: the `get_draw_dest` callback of a sprite
     * font. Unlike [getTextDrawDest] it advances `CHR_SIZE` bytes per tile column, since the two
     * rows of a sprite strip are 0x400 bytes apart rather than interleaved.
     *
     * Ported from src/fontgrp.c: GetSpriteTextDrawDest
     * (verified through the Prologue brown text box tiles in ram_trace_newgame_bm f09559; not
     * covered: dbId != 0, chrPosition != 0)
     */
    public fun getSpriteTextDrawDest(text: Text): Int =
        activeFont.vramDest + (text.dbId * text.tileWidth + text.chrPosition + text.x / 8) * CHR_SIZE

    /**
     * Draws [glyph] (ROM address) as OBJ tiles and advances the pen by the glyph width. The 16
     * bitmap rows go to two 8-row halves, the second `0x400` bytes on. Pixels are OR-ed in, so the
     * strip must be clear; the colour comes from [Text.colorId] through [getColorLut].
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
     * Pen x at which [str] is centred in a box [x] pixels wide: `(x - width) / 2`.
     *
     * Ported from src/fontgrp.c: GetStringTextCenteredPos
     */
    public fun getStringTextCenteredPos(x: Int, str: ByteArray, start: Int = 0): Int =
        (x - getStringTextLen(str, start)) / 2

    /**
     * Sets the pen to [x] and the colour to [colorId] (a `TEXT_COLOR_*` index), then draws [str].
     *
     * Ported from src/fontgrp.c: Text_InsertDrawString
     */
    public fun textInsertDrawString(text: Text, x: Int, colorId: Int, str: ByteArray) {
        text.x = x
        text.colorId = colorId
        drawString(text, str)
    }

    /**
     * ROM address of the lookup table for colour index [color] (`s2bppTo4bppLutTable[color]`):
     * 256 16-bit entries that turn one byte of four 2bpp glyph pixels into four 4bpp pixels in
     * that text colour's palette indices.
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
     * Calls the active font's `drawGlyph` function pointer. Only `DrawTextGlyph` and
     * `DrawSpriteTextGlyph` are ported; any other value (`DrawTextGlyphNoClear`) throws.
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

    /**
     * Colours four 2bpp pixels twice over: looks up the low two bytes of [v] in the colour table
     * at [lutBase], byte 0's entry in bits 0-15 and byte 1's entry in bits 16-31 (8 pixels, 4bpp).
     */
    private fun lutWord(lutBase: Int, v: Int): Int {
        val lo = rom[lutBase + (v and 0xFF) * 2].toInt() and 0xFF or
            ((rom[lutBase + (v and 0xFF) * 2 + 1].toInt() and 0xFF) shl 8)
        val hi = (v shr 8) and 0xFF
        val hv = rom[lutBase + hi * 2].toInt() and 0xFF or
            ((rom[lutBase + hi * 2 + 1].toInt() and 0xFF) shl 8)
        return lo or (hv shl 16)
    }

    /** OR-s [value] into the VRAM word at [addr] (glyph pixels are added over cleared tiles). */
    private fun orWord(addr: Int, value: Int) {
        host.writeVram32(addr, host.readVram32(addr) or value)
    }

    /**
     * Draws the character at [str]`[start]` and returns the offset after it (one byte for ASCII
     * fonts, two for Shift-JIS). ASCII fonts delegate to [drawCharacterAscii].
     *
     * Ported from src/fontgrp.c: Text_DrawCharacter
     */
    public fun drawCharacter(text: Text, str: ByteArray, start: Int): Int {
        if (activeFont.lang != 0) return drawCharacterAscii(text, str, start)
        return drawCharacterSjis(text, str, start)
    }

    /**
     * Draws one ASCII character; a character without a glyph is drawn as `'?'`.
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
     * Draws the string at [start] up to its terminator or newline (`CHAR_NEWLINE`), advancing the
     * pen per glyph. ASCII fonts take the ASCII path.
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
     * Draws an ASCII string up to its terminator or newline, character by character.
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
     * Loads `sSpecialCharStList` from [bytes] at [offset] (its EWRAM layout: 64 entries of
     * colour s8, id s8, tile slot s16), to resume from a dump.
     */
    public fun loadSpecialCharList(bytes: ByteArray, offset: Int) {
        for (i in 0 until SPECIAL_CHAR_COUNT) {
            val at = offset + i * 4
            specialColor[i] = bytes[at].toInt()
            specialId[i] = bytes[at + 1].toInt()
            specialChr[i] = (bytes[at + 2].toInt() and 0xFF) or (bytes[at + 3].toInt() shl 8)
        }
    }

    /**
     * Ported from src/fontgrp.c: Text_SetParams
     *
     * Sets the pen to [x] pixels and the colour to [colorId].
     */
    public fun setParams(text: Text, x: Int, colorId: Int) {
        text.x = x
        text.colorId = colorId
    }

    /**
     * Writes the special character [id] in [color] to the tilemap [tm] at [at] and [at] + 0x20
     * (nothing for `TEXT_SPECIAL_NOTHING`, which clears both).
     *
     * Ported from src/fontgrp.c: PutSpecialChar
     */
    public fun putSpecialChar(tm: ShortArray, at: Int, color: Int, id: Int) {
        if (id == SPECIAL_NOTHING) {
            tm[at] = 0
            tm[at + 0x20] = 0
            return
        }

        val chr = getSpecialCharChr(color, id) * 2 + activeFont.tileref

        tm[at] = chr.toShort()
        tm[at + 0x20] = (chr + 1).toShort()
    }

    /**
     * Writes [number] in decimal ending at [at] and growing left, each digit the special
     * character `idZero` + digit; 0 is the single digit `idZero`.
     *
     * Ported from src/fontgrp.c: PutNumberExt
     */
    public fun putNumberExt(tm: ShortArray, at: Int, color: Int, number: Int, idZero: Int) {
        if (number == 0) {
            putSpecialChar(tm, at, color, idZero)
            return
        }

        var n = number
        var i = at
        while (n != 0) {
            putSpecialChar(tm, i, color, n % 10 + idZero)
            n /= 10
            i--
        }
    }

    /**
     * Writes [number] ending at [at] as in [putNumber], but 100 as the two characters of the
     * 100 symbol and a negative number or 255 as two dashes, each ending at [at] + 1 (the two
     * start at [at] - 1).
     *
     * Ported from src/fontgrp.c: PutNumberTwoChr
     */
    public fun putNumberTwoChr(tm: ShortArray, at: Int, color: Int, number: Int) {
        if (number == 100) {
            putTwoSpecialChar(tm, at - 1, color, SPECIAL_100_A, SPECIAL_100_B)
        } else if (number < 0 || number == 255) {
            putTwoSpecialChar(tm, at - 1, color, SPECIAL_DASH, SPECIAL_DASH)
        } else {
            putNumber(tm, at, color, number)
        }
    }

    /**
     * Writes the special characters [idA] and [idB] side by side from [at].
     *
     * Ported from src/fontgrp.c: PutTwoSpecialChar
     */
    public fun putTwoSpecialChar(tm: ShortArray, at: Int, color: Int, idA: Int, idB: Int) {
        putSpecialChar(tm, at, color, idA)
        putSpecialChar(tm, at + 1, color, idB)
    }

    /**
     * Draws [str] into [text] with the pen at [x] in [color], then puts it at [offset] of [tm]
     * ([putText]). The original's null [text] (a temporary one) is not written.
     *
     * Ported from src/fontgrp.c: PutDrawText
     */
    public fun putDrawText(
        text: Text,
        tm: ShortArray,
        offset: Int,
        color: Int,
        x: Int,
        str: ByteArray,
    ) {
        text.x = x
        text.colorId = color
        drawString(text, str)

        putText(text, tm, offset)
    }

    /**
     * Writes [number] in the big digits ending at [at].
     *
     * Ported from src/fontgrp.c: PutNumber
     */
    public fun putNumber(tm: ShortArray, at: Int, color: Int, number: Int) {
        putNumberExt(tm, at, color, number, SPECIAL_BIGNUM_0)
    }

    /**
     * Writes [number] ending at [at], or two dashes ending at [at] for a negative number or 255
     * (the dash branch is not verified: no captured row has it).
     *
     * Ported from src/fontgrp.c: PutNumberOrBlank
     */
    public fun putNumberOrBlank(tm: ShortArray, at: Int, color: Int, number: Int) {
        if (number < 0 || number == 0xFF) {
            putSpecialChar(tm, at - 1, color, SPECIAL_DASH)
            putSpecialChar(tm, at, color, SPECIAL_DASH)
        } else {
            putNumber(tm, at, color, number)
        }
    }

    /**
     * The tile slot of special character [id] in [color]: the entry of the list, drawn and added
     * on first use.
     *
     * Ported from src/fontgrp.c: GetSpecialCharChr
     */
    private fun getSpecialCharChr(color: Int, id: Int): Int {
        var i = 0
        while (true) {
            if (specialColor[i] < 0) return addSpecialChar(i, color, id)

            if (specialColor[i] == color && specialId[i] == id) return specialChr[i]

            i++
        }
    }

    /**
     * Appends special character [id] in [color] as entry [i] of the list, drawing it into the
     * active font's next tile slot.
     *
     * Ported from src/fontgrp.c: AddSpecialChar
     */
    private fun addSpecialChar(i: Int, color: Int, id: Int): Int {
        specialColor[i] = color
        specialId[i] = id
        specialChr[i] = activeFont.chrCounter++

        specialColor[i + 1] = -1

        drawSpecialCharGlyph(
            specialChr[i], color, readU32(config.glyphsSpecial - ROM_BASE + id * 4),
        )

        return specialChr[i]
    }

    /**
     * Draws the 2bpp [glyph] (ROM address) in [color] into the 64 bytes of tile slot [a] of the
     * active font: 16 rows of one colour-table word each.
     *
     * Ported from src/fontgrp.c: DrawSpecialCharGlyph
     */
    private fun drawSpecialCharGlyph(a: Int, color: Int, glyph: Int) {
        val dest = activeFont.vramDest + a * 64
        val lut = getColorLut(color) - ROM_BASE
        val bitmap = glyph - ROM_BASE + GLYPH_BITMAP

        for (i in 0 until 16) {
            val v = readU32(bitmap + i * 4)
            val lo = rom[lut + (v and 0xFF) * 2].toInt() and 0xFF or
                ((rom[lut + (v and 0xFF) * 2 + 1].toInt() and 0xFF) shl 8)
            val hi = rom[lut + ((v shr 8) and 0xFF) * 2].toInt() and 0xFF or
                ((rom[lut + ((v shr 8) and 0xFF) * 2 + 1].toInt() and 0xFF) shl 8)

            host.writeVram32(dest + i * 4, (hi shl 16) + lo)
        }
    }

    /**
     * Writes the tilemap entries that show [text] to the 32-entry-wide tilemap [tm] at [offset]:
     * the top row at [offset], the bottom row 0x20 entries (one map row) later, numbered from
     * the font's `tileref`. When double-buffering is enabled, flips [Text.dbId] so the next draw
     * goes to the other half.
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

    /** `glyphs[index]` of table [table] (ROM address): the glyph's ROM address, 0 for NULL. */
    private fun glyphAt(table: Int, index: Int): Int = readU32(table - ROM_BASE + index * 4)

    /** `glyph->width` (`struct Glyph` +5): the glyph's advance in pixels. */
    private fun glyphWidth(glyph: Int): Int = rom[glyph - ROM_BASE + 5].toInt() and 0xFF

    /** Little-endian 32-bit word at ROM offset [o]. */
    private fun readU32(o: Int): Int =
        (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8) or
            ((rom[o + 2].toInt() and 0xFF) shl 16) or ((rom[o + 3].toInt() and 0xFF) shl 24)

    /** Constants of the text system. */
    public companion object {
        /** `TEXT_GLYPHS_SYSTEM`: the glyph set of menus and system text. */
        public const val GLYPHS_SYSTEM: Int = 0

        /** `TEXT_GLYPHS_TALK`: the glyph set of dialogue text. */
        public const val GLYPHS_TALK: Int = 1

        /**
         * `TEXT_COLOR_SYSTEM_WHITE` (= `TEXT_COLOR_0123`): colour index 0, the default text colour.
         */
        public const val COLOR_SYSTEM_WHITE: Int = 0

        /**
         * `CHAR_NEWLINE` (`src/fontgrp.c`): the byte `[NL]` that ends a string's drawn/measured
         * line.
         */
        public const val CHAR_NEWLINE: Int = 0x01

        /** `TEXT_SPECIAL_BIGNUM_0`. */
        private const val SPECIAL_BIGNUM_0 = 0x00

        /** `TEXT_SPECIAL_DASH`. */
        private const val SPECIAL_DASH = 0x14

        /** `TEXT_SPECIAL_HP_A` and `_B`: the two halves of the HP symbol. */
        public const val SPECIAL_HP_A: Int = 0x22

        /** See [SPECIAL_HP_A]. */
        public const val SPECIAL_HP_B: Int = 0x23

        private const val SPECIAL_100_A = 0x28
        private const val SPECIAL_100_B = 0x29

        /** `TEXT_SPECIAL_NOTHING`. */
        private const val SPECIAL_NOTHING = 0xFF

        /** Entries of `sSpecialCharStList`. */
        private const val SPECIAL_CHAR_COUNT = 64

        /** `CHR_SIZE` (`include/hardware.h`): bytes of one 8x8 4bpp tile. */
        private const val CHR_SIZE = 0x20

        /**
         * Offset of `bitmap` in `struct Glyph`: after `sjisNext` (4), `sjisByte1` (1), `width` (1),
         * 2 padding.
         */
        private const val GLYPH_BITMAP = 8

        /**
         * Start of the cartridge ROM in the GBA address space; ROM pointers minus this index [rom].
         */
        private const val ROM_BASE = 0x08000000

        /** Character whose glyph stands in for a missing ASCII glyph. */
        private const val QUESTION_MARK = '?'.code
    }
}
