package fe8.game.text

import gbafe.text.TextSystem

/**
 * FE8U (US ROM) addresses and runtime font configuration for the `src/fontgrp.c` text rendering
 * system.
 */
public object Fe8Text {
    /**
     * ROM address of `TextGlyphs_System`: the 256-glyph pointer table for the system font used in
     * menus, stat screens, action menus, and combat forecasts.
     */
    public const val GLYPHS_SYSTEM: Int = 0x0858C7EC

    /**
     * ROM address of `TextGlyphs_Talk`: the 256-glyph pointer table for the dialogue font used in
     * cutscene conversation bubbles and narrative text scrolls.
     */
    public const val GLYPHS_TALK: Int = 0x0858F6F4

    /**
     * Thumb code pointer to `DrawTextGlyph` (bit 0 set), the rasterizer function stored in
     * `Font.drawGlyph` that draws a font glyph bitmap into background tile VRAM.
     */
    public const val DRAW_TEXT_GLYPH: Int = 0x08004219

    /**
     * Thumb code pointer to `GetTextDrawDest` (bit 0 set), the function stored in
     * `Font.get_draw_dest` that computes target tile VRAM destinations for background text.
     */
    public const val GET_TEXT_DRAW_DEST: Int = 0x080041E9

    /**
     * Thumb code pointer to `DrawSpriteTextGlyph` (bit 0 set), rendering text glyphs directly into
     * OBJ (sprite) character memory for floating sprite labels.
     */
    public const val DRAW_SPRITE_TEXT_GLYPH: Int = 0x08004701

    /**
     * Thumb code pointer to `GetSpriteTextDrawDest` (bit 0 set), computing destination tile
     * addresses in OBJ character VRAM for sprite-rendered text.
     */
    public const val GET_SPRITE_TEXT_DRAW_DEST: Int = 0x080046E1

    /**
     * ROM address of `s2bppTo4bppLutTable`, the lookup table expanding 2bpp font glyph pixels to
     * 4bpp tile indices matching active text palette colors (normal, gray, blue, orange, green).
     */
    public const val COLOR_LUT_TABLE: Int = 0x08588240

    /**
     * EWRAM address of `gDefaultFont`, the global text font instance that tracks active glyph
     * tables, tile destinations, and character cursor positions.
     */
    public const val G_DEFAULT_FONT: Int = 0x02028E58

    /** The preconfigured [TextSystem.Config] holding ROM addresses and draw hooks for FE8U. */
    public val CONFIG: TextSystem.Config =
        TextSystem.Config(
            GLYPHS_SYSTEM, GLYPHS_TALK, DRAW_TEXT_GLYPH, GET_TEXT_DRAW_DEST, COLOR_LUT_TABLE,
            DRAW_SPRITE_TEXT_GLYPH, GET_SPRITE_TEXT_DRAW_DEST,
        )
}
