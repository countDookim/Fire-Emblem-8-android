package fe8.game.text

import gbafe.text.TextSystem

/** FE8U (US ROM) addresses for the `fontgrp.c` text system. */
public object Fe8Text {
    /** `TextGlyphs_System`. */
    public const val GLYPHS_SYSTEM: Int = 0x0858C7EC

    /** `TextGlyphs_Talk`. */
    public const val GLYPHS_TALK: Int = 0x0858F6F4

    /** `DrawTextGlyph` (Thumb, bit 0 set), the value stored in `Font.drawGlyph`. */
    public const val DRAW_TEXT_GLYPH: Int = 0x08004219

    /** `GetTextDrawDest` (Thumb), the value stored in `Font.get_draw_dest`. */
    public const val GET_TEXT_DRAW_DEST: Int = 0x080041E9

    /** `gDefaultFont` (EWRAM). */
    public const val G_DEFAULT_FONT: Int = 0x02028E58

    /** The [TextSystem.Config] for FE8U. */
    public val CONFIG: TextSystem.Config =
        TextSystem.Config(GLYPHS_SYSTEM, GLYPHS_TALK, DRAW_TEXT_GLYPH, GET_TEXT_DRAW_DEST)
}
