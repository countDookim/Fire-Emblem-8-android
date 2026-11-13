package fe8.game.bm

import fe8.game.RomView
import fe8.game.ui.ItemIcon
import fe8.game.ui.UiFrame
import gbafe.proc.Proc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The item panel beside the weapon list (src/menuitempanel.c, `gProcCmd_MenuItemPanel`): a frame
 * on BG 0 and BG 1 with the Atk, Hit, Crit and Avoid of the weapon the cursor is on and its type
 * icon, a child proc of the menu. Only the weapon layout is written; the layout of a staff or
 * item (its description) is a stand-in until the item menu (MC-4b: the weapon list never holds
 * one), and `EndMenuItemPanel` is never run (the panel ends with its menu).
 */
public class MenuItemPanel(
    private val engine: ProcEngine,
    private val units: UnitTable,
    private val battle: BmBattle,
    private val items: ItemInfo,
    private val loader: UnitLoader,
    private val bm: BmSt,
    private val text: TextSystem,
    private val messages: MsgStrings,
    private val hardware: BgHardware,
    private val rom: RomView,
    /** `DrawnIconLookupTable` and `IconGFXIDLookupTable` ([ItemIcon]). */
    private val drawnIconLookupTable: ByteArray,
    private val iconGfxIdLookupTable: ByteArray,
    private val arrows: StatArrows,
) {
    /** `UpdateStatArrowSprites(x, y, isDown)`: puts a stat arrow sprite. */
    public fun interface StatArrows {
        /** Puts the up (or, when [isDown], down) arrow at ([x], [y]). */
        public fun put(x: Int, y: Int, isDown: Boolean)
    }

    /** `struct MenuItemPanelProc` past `PROC_HEADER`. */
    public class State {
        /** `unit` (+0x2C): the byte offset of the unit in the unit table. */
        public var unitOffset: Int = 0

        /** `x` (+0x30): the panel's left tile. */
        public var x: Int = 0

        /** `y` (+0x31): the panel's top tile. */
        public var y: Int = 0

        /** `IconPalIndex` (+0x32): the palette bank of the type icon. */
        public var iconPalIndex: Int = 0

        /** `ItemSlotIndex` (+0x33, s8): the unit's equipped weapon slot, -1 for none. */
        public var itemSlotIndex: Int = 0

        /** `text[6]` (+0x34): the strips of the panel; three are used. */
        public val text: Array<Text> = Array(TEXT_COUNT) { Text() }

        /** `draw_arrow` (+0x64): whether the stat arrows are drawn. */
        public var drawArrow: Boolean = false
    }

    private val idleFn = ProcFunc { menuItemPanelProcIdle(it) }

    /** `gProcCmd_MenuItemPanel`: `PROC_15`, then [menuItemPanelProcIdle] every frame. */
    public val script: ProcScript = ProcScript(
        "gProcCmd_MenuItemPanel",
        listOf(ProcCmd(0x15), repeat(idleFn), end()),
    ) { State() }

    /**
     * Ported from src/menuitempanel.c: MenuItemPanelProcIdle
     *
     * Puts an up or down arrow beside each of the four numbers where the weapon under the cursor
     * ([BmBattle.actor]) beats or trails the equipped one ([BmBattle.target]).
     * (The arrows themselves are not verified: the captured list has one weapon, so the numbers
     * are equal and none is drawn; the test checks that none is.)
     */
    public fun menuItemPanelProcIdle(proc: Proc) {
        val state = proc.state as State
        if (!state.drawArrow) return
        if (state.itemSlotIndex < 0) return

        val actor = battle.actor
        val target = battle.target
        val left = state.x * 8 + 0x33
        val right = state.x * 8 + 0x63
        val top = (state.y + 3) * 8
        val bottom = (state.y + 5) * 8

        if (actor.battleAttack > target.battleAttack) arrows.put(left, top, false)
        if (actor.battleAttack < target.battleAttack) arrows.put(left, top, true)

        if (actor.battleHitRate > target.battleHitRate) arrows.put(left, bottom, false)
        if (actor.battleHitRate < target.battleHitRate) arrows.put(left, bottom, true)

        if (actor.battleCritRate > target.battleCritRate) arrows.put(right, top, false)
        if (actor.battleCritRate < target.battleCritRate) arrows.put(right, top, true)

        if (actor.battleAvoidRate > target.battleAvoidRate) arrows.put(right, bottom, false)
        if (actor.battleAvoidRate < target.battleAvoidRate) arrows.put(right, bottom, true)
    }

    /**
     * Ported from src/menuitempanel.c: ForceMenuItemPanel
     *
     * Starts the panel as a child of [menu] for unit [unit] (an id) with its top left tile at
     * ([x], [y]) unless one exists: the three double-buffered strips, the icon palette, and the
     * stats of the equipped weapon, which are also the ones the arrows compare with.
     */
    public fun forceMenuItemPanel(menu: Proc, unit: Int, x: Int, y: Int) {
        if (engine.find(script) != null) return

        val proc = engine.start(script, menu)
        val state = proc.state as State
        val offset = units.unitOffset(unit)
        state.unitOffset = offset
        state.x = x
        state.y = y
        state.iconPalIndex = 3
        state.itemSlotIndex = battle.getUnitEquippedWeaponSlot(units.bytes, offset)
        state.drawArrow = true

        text.initTextDb(state.text[0], 0xC)
        text.initTextDb(state.text[1], 0xC)
        text.initTextDb(state.text[2], 0xC)

        ItemIcon.loadIconPalette(hardware, rom, 1, state.iconPalIndex)
        battle.battleGenerateUiStats(units.bytes, offset, BmBattle.BU_ISLOT_AUTO)

        copyStatsToTarget()
    }

    /**
     * Ported from src/menuitempanel.c: UpdateMenuItemPanel
     *
     * Redraws the panel for item slot [slotOrItem] of the unit (0..4; 5 is the borrowed item,
     * `gBmSt.um_tmp_item`; anything else an item word, shown as the ballista's): the frame, the
     * four numbers and the weapon type icon. Does nothing when there is no panel (the original
     * would read a null proc).
     */
    public fun updateMenuItemPanel(slotOrItem: Int) {
        val proc = engine.find(script) ?: return
        val state = proc.state as State
        val bg0 = hardware.mapBuffer(0)
        val base = state.x + 0x20 * state.y
        val strips = state.text
        val offset = state.unitOffset
        var slot = slotOrItem
        val item: Int

        text.clearText(strips[0])
        text.clearText(strips[1])
        text.clearText(strips[2])

        UiFrame.drawUiFrame2(hardware, rom, state.x, state.y, 0xE, 0x8, 0)

        when (slot) {
            0, 1, 2, 3, 4 ->
                item = StructCodec.u16(units.bytes, offset + UnitLoader.OFF_ITEMS + 2 * slot)
            BmBattle.BU_ISLOT_5 -> item = bm.umTmpItem
            else -> {
                item = slot
                slot = BmBattle.BU_ISLOT_BALLISTA
            }
        }

        when (items.getItemType(item)) {
            ITYPE_STAFF, ITYPE_ITEM, ITYPE_MONSTER, ITYPE_DANCE -> {
                // Stand-in until the item menu (MC-4b): the description of the item is not drawn.
                battle.actor.battleAttack = battle.target.battleAttack
                battle.actor.battleHitRate = battle.target.battleHitRate
                battle.actor.battleCritRate = battle.target.battleCritRate
                battle.actor.battleAvoidRate = battle.target.battleAvoidRate
            }
            else -> {
                battle.battleGenerateUiStats(units.bytes, offset, slot)

                if (slot == BmBattle.BU_ISLOT_BALLISTA) copyStatsToTarget()

                val actor = battle.actor
                val color =
                    if (loader.canUnitUseWeapon(units.bytes, offset, actor.weapon)) BLUE else GRAY

                label(strips[0], 0x1C, 0x4F1)
                label(strips[1], 0x02, 0x4F3)
                label(strips[2], 0x02, 0x4F4)
                label(strips[1], 0x32, 0x501)
                label(strips[2], 0x32, 0x4F5)

                textInsertDrawNumberOrBlank(strips[1], 0x24, color, actor.battleAttack)
                textInsertDrawNumberOrBlank(strips[2], 0x24, color, actor.battleHitRate)
                textInsertDrawNumberOrBlank(strips[1], 0x54, color, actor.battleCritRate)
                textInsertDrawNumberOrBlank(strips[2], 0x54, color, actor.battleAvoidRate)

                text.putText(strips[0], bg0, base + 0x20 + 1)
                text.putText(strips[1], bg0, base + 0x60 + 1)
                text.putText(strips[2], bg0, base + 0xA0 + 1)

                ItemIcon.drawIcon(
                    bg0, base + 0x20 + 8, items.getItemType(actor.weapon) + 0x70,
                    state.iconPalIndex shl 12, drawnIconLookupTable, iconGfxIdLookupTable,
                    hardware, rom,
                )
            }
        }

        hardware.enableBgSyncByMask(1)
    }

    /** `Text_InsertDrawString(strip, x, TEXT_COLOR_SYSTEM_WHITE, GetStringFromIndex(index))`. */
    private fun label(strip: Text, x: Int, index: Int) {
        text.textInsertDrawString(strip, x, WHITE, messages.getStringFromIndex(index))
    }

    /** `gBattleTarget.battle* = gBattleActor.battle*` for the four numbers of the panel. */
    private fun copyStatsToTarget() {
        battle.target.battleAttack = battle.actor.battleAttack
        battle.target.battleHitRate = battle.actor.battleHitRate
        battle.target.battleCritRate = battle.actor.battleCritRate
        battle.target.battleAvoidRate = battle.actor.battleAvoidRate
    }

    /**
     * Ported from src/fontgrp.c: Text_InsertDrawNumberOrBlank
     *
     * Sets the pen to [x] and the colour to [color], then draws [n] ([drawNumberOrBlank]).
     */
    private fun textInsertDrawNumberOrBlank(strip: Text, x: Int, color: Int, n: Int) {
        strip.x = x
        strip.colorId = color
        drawNumberOrBlank(strip, n)
    }

    /**
     * Ported from src/fontgrp.c: Text_DrawNumberOrBlank
     *
     * Draws [n], or the dashes of string 0x535 eight pixels back for 255 or -1 (not verified:
     * the captured numbers are all drawn).
     */
    private fun drawNumberOrBlank(strip: Text, n: Int) {
        if (n == 255 || n == -1) {
            strip.x -= 8
            text.drawString(strip, messages.getStringFromIndex(0x535))
            return
        }

        text.drawNumber(strip, n)
    }

    private companion object {
        const val TEXT_COUNT = 6
        const val WHITE = 0
        const val GRAY = 1
        const val BLUE = 2
        const val ITYPE_STAFF = 4
        const val ITYPE_ITEM = 9
        const val ITYPE_MONSTER = 11
        const val ITYPE_DANCE = 12
    }
}
