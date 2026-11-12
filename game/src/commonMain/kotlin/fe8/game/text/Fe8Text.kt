package fe8.game.text

import gbafe.text.TextSystem

/**
 * FE8U (US ROM) addresses of the `src/fontgrp.c` glyph tables, draw functions and colour table, and
 * the [TextSystem.Config] that hands them to the game-neutral text system. The code pointers are
 * Thumb addresses: bit 0 is set, as the GBA stores a function pointer to Thumb code.
 */
public object Fe8Text {
    /**
     * ROM address of `TextGlyphs_System`: the 256-entry glyph pointer table that
     * `SetTextFontGlyphs(TEXT_GLYPHS_SYSTEM)` (and so `InitSystemTextFont`) selects.
     */
    public const val GLYPHS_SYSTEM: Int = 0x0858C7EC

    /**
     * ROM address of `TextGlyphs_Talk`: the 256-entry glyph pointer table that
     * `SetTextFontGlyphs(TEXT_GLYPHS_TALK)` (and so `InitTalkTextFont`, used by the talk window)
     * selects.
     */
    public const val GLYPHS_TALK: Int = 0x0858F6F4

    /**
     * Thumb code pointer to `DrawTextGlyph`, the value `InitSystemTextFont` stores in
     * `Font.drawGlyph`: draws a glyph into background tile VRAM.
     */
    public const val DRAW_TEXT_GLYPH: Int = 0x08004219

    /**
     * Thumb code pointer to `GetTextDrawDest`, the value `InitTextFont` stores in
     * `Font.get_draw_dest`: the VRAM address of the tile under the pen of a background font.
     */
    public const val GET_TEXT_DRAW_DEST: Int = 0x080041E9

    /**
     * Thumb code pointer to `DrawSpriteTextGlyph`, the value `InitSpriteTextFont` stores in
     * `Font.drawGlyph`: draws a glyph into OBJ tile VRAM.
     */
    public const val DRAW_SPRITE_TEXT_GLYPH: Int = 0x08004701

    /**
     * Thumb code pointer to `GetSpriteTextDrawDest`, the value `InitSpriteTextFont` stores in
     * `Font.get_draw_dest`: the OBJ tile VRAM address under the pen of a sprite font.
     */
    public const val GET_SPRITE_TEXT_DRAW_DEST: Int = 0x080046E1

    /**
     * ROM address of `s2bppTo4bppLutTable`: 13 pointers, one per text colour index (`TEXT_COLOR_*`
     * of `include/fontgrp.h`: white, gray, blue, gold, green, black, ..., and the mask at 9). Each
     * points at a 256-entry table that expands one byte of four 2bpp glyph pixels into four 4bpp
     * pixels of that colour.
     */
    public const val COLOR_LUT_TABLE: Int = 0x08588240

    /** ROM address of `TextGlyphs_Special`. */
    public const val GLYPHS_SPECIAL: Int = 0x08590B44

    /**
     * EWRAM address of `gDefaultFont`, the `struct Font` that `SetTextFont(NULL)` selects and
     * `InitTextFont(NULL, ...)` fills. Not used by [CONFIG]; tests compare `gActiveFont` with it.
     */
    public const val G_DEFAULT_FONT: Int = 0x02028E58

    /** The preconfigured [TextSystem.Config] holding ROM addresses and draw hooks for FE8U. */
    public val CONFIG: TextSystem.Config =
        TextSystem.Config(
            GLYPHS_SYSTEM, GLYPHS_TALK, DRAW_TEXT_GLYPH, GET_TEXT_DRAW_DEST, COLOR_LUT_TABLE,
            DRAW_SPRITE_TEXT_GLYPH, GET_SPRITE_TEXT_DRAW_DEST, GLYPHS_SPECIAL,
        )
}
