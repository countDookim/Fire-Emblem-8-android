package fe8.game.bm

import fe8.game.RomView
import fe8.game.ui.ItemIcon
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The item row of the item menus (`DrawItemMenuLine`, src/bmitem.c): icon, name and uses.
 * The icon bookkeeping tables are `DrawnIconLookupTable` and `IconGFXIDLookupTable`
 * ([ItemIcon]).
 */
public class ItemMenuLine(
    private val text: TextSystem,
    private val items: ItemInfo,
    private val messages: MsgStrings,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val drawnIconLookupTable: ByteArray,
    private val iconGfxIdLookupTable: ByteArray,
) {
    /**
     * Ported from src/bmitem.c: DrawItemMenuLine
     *
     * Draws the name of [item] into [row] (white, grey when it is not [isUsable]) and puts it at
     * [at] + 2 of [tm], the uses at [at] + 11 (blue, grey) and the icon at [at]. The grey rows
     * are (not verified: the captured weapon list has only usable rows).
     */
    public fun drawItemMenuLine(row: Text, item: Int, isUsable: Boolean, tm: ShortArray, at: Int) {
        text.setParams(row, 0, if (isUsable) COLOR_WHITE else COLOR_GRAY)
        text.drawString(row, items.getItemName(item, messages))

        text.putText(row, tm, at + 2)

        text.putNumberOrBlank(
            tm, at + 11, if (isUsable) COLOR_BLUE else COLOR_GRAY, items.getItemUses(item),
        )

        ItemIcon.drawIcon(
            tm, at, items.getItemIconId(item), ICON_PALETTE_BASE, drawnIconLookupTable,
            iconGfxIdLookupTable, hardware, rom,
        )
    }

    private companion object {
        const val COLOR_WHITE = 0
        const val COLOR_GRAY = 1
        const val COLOR_BLUE = 2
        const val ICON_PALETTE_BASE = 0x4000
    }
}
