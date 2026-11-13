package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.u16
import fe8.game.scene.IntConsumer
import fe8.game.ui.Menu
import fe8.game.ui.UiFrame
import gbafe.render.BgHardware
import gbafe.text.Text

/**
 * The unit action menu's item callbacks of src/bmmenu.c (the items of `gUnitActionMenuItems`,
 * src/menu_def.c) for the Prologue: the Attack and Item availability and the Wait effect.
 * [install] registers them, and, until M6-C and M6-H, a stand-in for every other availability callback, in
 * [Menu.callbacks] under the ROM addresses the `MenuItemDef`s hold.
 *
 * It also holds the weapon select menu the Attack command opens (`gWeaponSelectMenuDef`): its
 * start, its availability, draw and switch-in callbacks, and its choice and cancel
 * ([weaponSelectMenuSelected], [itemMenuButtonBPressed]). The target selection a choice starts
 * is [Hooks.startAttackTargetSelection] (`NewTargetSelection`, [TargetSelection]).
 *
 * The unit acting is [active] (`gActiveUnit`, `gActionData`).
 */
public class UnitActionMenu(
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val items: ItemInfo,
    private val loader: UnitLoader,
    /** `gBmMapMovement` and `gBmMapRange`, filled by the range display. */
    private val map: BmMap,
    /** The reach lookups of src/bmitem.c and the standing reach range. */
    private val range: AttackRange,
    /** The BG tilemaps the choice and cancel clear. */
    private val hardware: BgHardware,
) {
    /** Services outside the ported file. */
    public class Hooks {
        /**
         * `MakeTargetListForWeapon(unit, item)` then `GetSelectTargetCount()`
         * ([AttackTargets.makeTargetListForWeapon], [SelectTargets.getSelectTargetCount]): the
         * number of targets of [item] for the unit with id `unit`. The game wires it to them; a
         * test may answer without a map.
         */
        public var weaponTargetCount: (unit: Int, item: Int) -> Int = { _, _ ->
            error("MakeTargetListForWeapon is not ported")
        }

        /**
         * `IsPositionMagicSealed(x, y)` (bmunit.c, not ported): whether an enemy magic-sealing
         * unit reaches the tile.
         */
        public var isPositionMagicSealed: (x: Int, y: Int) -> Boolean = { _, _ -> false }

        /**
         * The availability (`MENU_*`) of the unit menu items whose commands are not ported (every
         * item but Attack, Item and Wait), given the item def address and the item number.
         */
        public var unportedUsability: (itemDef: Int, number: Int) -> Int = { _, _ ->
            error("unit menu item availability is not ported")
        }

        /** `ResetIconGraphics`; the game wires [fe8.game.ui.ItemIcon.resetIconGraphics]. */
        public var resetIconGraphics: () -> Unit = {}

        /** `LoadIconPalettes(palId)`; the game wires [fe8.game.ui.ItemIcon.loadIconPalette]. */
        public var loadIconPalettes: IntConsumer = IntConsumer {}

        /**
         * `StartFace(0, GetUnitPortraitId(unit), 0xB0, 0xC, 2)` then
         * `SetFaceBlinkControlById(0, 5)` for the unit with id `unit`. Stand-in until MC-4
         * (the faces of the menu).
         */
        public var startUnitFace: IntConsumer = IntConsumer {}

        /**
         * `ForceMenuItemPanel(proc, unit, 0xF, 0xB)`: the item panel beside the weapon list, for
         * the unit with id `unit`. Stand-in until MC-4 (menuitempanel.c).
         */
        public var forceMenuItemPanel: IntConsumer = IntConsumer {}

        /**
         * `UpdateMenuItemPanel(slot)`: shows item slot `slot` in the panel. Stand-in until MC-4.
         */
        public var updateMenuItemPanel: IntConsumer = IntConsumer {}

        /**
         * `DrawItemMenuLine(text, item, isUsable, gBG0TilemapBuffer + offset)`: the icon, name
         * and uses of one weapon row. Stand-in until MC-4 (bmitem.c; needs icons and numbers).
         */
        public var drawItemMenuLine: ItemLineDrawer = ItemLineDrawer { _, _, _, _ -> }

        /**
         * `MakeTargetListForWeapon(unit, item)` ([AttackTargets.makeTargetListForWeapon]): the
         * target list of the equipped weapon, for the unit with id `unit`.
         */
        public var makeTargetListForWeapon: (unit: Int, item: Int) -> Unit = { _, _ ->
            error("MakeTargetListForWeapon is not ported")
        }

        /**
         * `NewTargetSelection(&gSelectInfo_Attack)`: the cursor that picks the attack target. The
         * game wires [TargetSelection] with the attack callbacks (MC-6, MC-10 stand-ins).
         */
        public var startAttackTargetSelection: () -> Unit = {}

        /**
         * `ResetTextFont()`: not ported (no text font reset exists); it only restores the default
         * font for the next text draw.
         */
        public var resetTextFont: () -> Unit = {}

        /** The cursor's screen x, where the unit menu reopens (`ItemMenu_ButtonBPressed`). */
        public var cursorScreenX: () -> Int = { 0 }

        /** `DisplayMoveRangeGraphics(flags)`; the game wires [MoveLimitView]. */
        public var displayMoveRangeGraphics: IntConsumer = IntConsumer {}

        /** `HideMoveRangeGraphics()`; the game wires [MoveLimitView]. */
        public var hideMoveRangeGraphics: () -> Unit = {}

        /**
         * `MenuFrozenHelpBox(menu, 0x858)`, the ballista ammo help of a disabled Attack
         * (not verified: no capture has it; not ported).
         */
        public var ballistaAmmoHelp: () -> Unit = { error("MenuFrozenHelpBox is not ported") }

        /**
         * `StartUnitBallistaSelect(menu, menuItem)` (not verified: no capture has a ballista
         * unit; not ported). Returns the menu actions.
         */
        public var startUnitBallistaSelect: () -> Int = {
            error("StartUnitBallistaSelect is not ported")
        }
    }

    /** Draws one weapon row: [Hooks.drawItemMenuLine]. */
    public fun interface ItemLineDrawer {
        /**
         * Draws [item] (usable or not, [isUsable]) with [text] at tile offset [tilemapOffset] of
         * the BG 0 tilemap.
         */
        public fun draw(text: Text, item: Int, isUsable: Boolean, tilemapOffset: Int)
    }

    /** Services outside the ported file. */
    public val hooks: Hooks = Hooks()

    private lateinit var menus: Menu

    /** Registers the unit menu's callbacks in [menu]. */
    public fun install(menu: Menu, rom: RomView) {
        menus = menu
        val itemDefs = rom.u32(Menu.UNIT_ACTION_MENU_DEF + 8)
        var i = 0
        while (rom.u32(itemDefs + ITEM_DEF_SIZE * i + 0xC) != 0) {
            val usability = rom.u32(itemDefs + ITEM_DEF_SIZE * i + 0xC)
            if (usability != Menu.MENU_ALWAYS_ENABLED) {
                menu.callbacks[usability] = Menu.MenuFunc { _, _, itemDef, number ->
                    hooks.unportedUsability(itemDef, number)
                }
            }
            i++
        }
        menu.callbacks[ATTACK_COMMAND_USABILITY] =
            Menu.MenuFunc { _, _, itemDef, number -> attackCommandUsability(itemDef, number) }
        menu.callbacks[ITEM_COMMAND_USABILITY] =
            Menu.MenuFunc { _, _, itemDef, number -> itemCommandUsability(itemDef, number) }
        menu.callbacks[EFFECT_WAIT] = Menu.MenuFunc { m, item, _, _ -> effectWait(m!!, item!!) }
        menu.callbacks[UNIT_ACTION_MENU_ATTACK] =
            Menu.MenuFunc { m, item, _, _ -> unitActionMenuAttack(m!!, item!!) }
        menu.callbacks[DISPLAY_UNIT_STANDING_ATTACK_RANGE] =
            Menu.MenuFunc { m, item, _, _ -> displayUnitStandingAttackRange(m!!, item!!) }
        menu.callbacks[HIDE_MOVE_RANGE_GRAPHICS_WRAPPER] =
            Menu.MenuFunc { m, item, _, _ -> hideMoveRangeGraphicsWrapper(m!!, item!!) }
        menu.callbacks[WEAPON_SELECT_MENU_IS_AVAILABLE] =
            Menu.MenuFunc { _, _, itemDef, number -> weaponSelectMenuIsAvailable(itemDef, number) }
        menu.callbacks[WEAPON_SELECT_MENU_DRAW] =
            Menu.MenuFunc { m, item, _, _ -> weaponSelectMenuDraw(m!!, item!!) }
        menu.callbacks[WEAPON_SELECT_MENU_SWITCH_IN] =
            Menu.MenuFunc { m, item, _, _ -> weaponSelectMenuSwitchIn(m!!, item!!) }
        menu.callbacks[WEAPON_SELECT_MENU_SELECTED] =
            Menu.MenuFunc { m, item, _, _ -> weaponSelectMenuSelected(m!!, item!!) }
        menu.callbacks[BALLISTA_RANGE_MENU_SWITCH_OUT] =
            Menu.MenuFunc { m, item, _, _ -> ballistaRangeMenuSwitchOut(m!!, item!!) }
        menu.callbacks[ITEM_MENU_BUTTON_B_PRESSED] =
            Menu.MenuFunc { m, item, _, _ -> itemMenuButtonBPressed(m!!, item!!) }
    }

    /**
     * Ported from src/bmmenu.c: EffectWait
     *
     * Chooses the Wait action and returns the menu actions: skip the cursor, end the menu, the
     * select sound, clear the menu BGs.
     */
    public fun effectWait(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        active.action.unitActionType = UNIT_ACTION_WAIT

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /**
     * Ported from src/bmmenu.c: ItemCommandUsability
     *
     * Not shown once the unit has moved, for a Phantom, or without an item; else enabled. The
     * moved and Phantom branches are (not verified: the captured unit has neither).
     */
    public fun itemCommandUsability(def: Int, number: Int): Int {
        val o = units.unitOffset(active.unit)

        if (units.state(active.unit) and US_HAS_MOVED != 0) return Menu.MENU_NOTSHOWN

        if (units.classId(active.unit) == CLASS_PHANTOM) return Menu.MENU_NOTSHOWN

        if (u16(units.bytes, o + OFF_ITEMS) == 0) return Menu.MENU_NOTSHOWN

        return Menu.MENU_ENABLED
    }

    /**
     * Ported from src/bmmenu.c: AttackCommandUsability
     *
     * Enabled when the unit has not moved, is not in a ballista, and has a weapon it can use now
     * with at least one target in reach ([Hooks.weaponTargetCount]); else not shown. The moved
     * and ballista branches are (not verified: the captured unit has neither).
     */
    public fun attackCommandUsability(def: Int, number: Int): Int {
        val o = units.unitOffset(active.unit)

        if (units.state(active.unit) and US_HAS_MOVED != 0) return Menu.MENU_NOTSHOWN

        if (units.state(active.unit) and US_IN_BALLISTA != 0) return Menu.MENU_NOTSHOWN

        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            val item = u16(units.bytes, o + OFF_ITEMS + 2 * i)

            if (item == 0) break

            if (items.getItemAttributes(item) and ItemInfo.IA_WEAPON == 0) continue

            if (!canUnitUseWeaponNow(o, item)) continue

            if (hooks.weaponTargetCount(active.unit, item) == 0) continue

            return Menu.MENU_ENABLED
        }

        return Menu.MENU_NOTSHOWN
    }

    /**
     * Ported from src/bmmenu.c: UnitActionMenu_Attack
     *
     * Chooses Attack: resets the icon graphics, loads the icon palettes at 4 and opens the weapon
     * select menu ([startUnitWeaponSelect]). The disabled-item and in-ballista branches are
     * (not verified: no capture has them; [Hooks.ballistaAmmoHelp],
     * [Hooks.startUnitBallistaSelect]).
     */
    public fun unitActionMenuAttack(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        if (menuItem.availability == Menu.MENU_DISABLED) {
            hooks.ballistaAmmoHelp()
            return Menu.MENU_ACT_SND6B
        }

        hooks.resetIconGraphics()

        hooks.loadIconPalettes.invoke(ICON_PALETTE_ID)

        if (units.state(active.unit) and US_IN_BALLISTA != 0) {
            return hooks.startUnitBallistaSelect()
        }

        return startUnitWeaponSelect(menu, menuItem)
    }

    /**
     * Ported from src/bmmenu.c: StartUnitWeaponSelect
     *
     * Starts the weapon select menu as an orphan menu, the unit's face (not for a Phantom) and
     * the item panel ([Hooks.startUnitFace], [Hooks.forceMenuItemPanel]: MC-4), then
     * [subEventInfo80832C4]. Returns the menu actions that end the unit menu.
     */
    public fun startUnitWeaponSelect(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        menus.startOrphanMenu(Menu.WEAPON_SELECT_MENU_DEF)

        if (units.classId(active.unit) != CLASS_PHANTOM) hooks.startUnitFace.invoke(active.unit)

        hooks.forceMenuItemPanel.invoke(active.unit)

        subEventInfo80832C4()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /**
     * Transcribed from src/eventinfo.c: sub_80832C4 (not verified: it returns 0 and writes
     * nothing, so no capture can show it)
     */
    private fun subEventInfo80832C4(): Int = 0

    /**
     * Ported from src/bmmenu.c: DisplayUnitStandingAttackRange
     *
     * Switch-in of the Attack item: clears `gBmMapMovement` to -1 and `gBmMapRange` to 0, adds
     * the standing reach of all the unit's usable weapons to the range, and shows the squares
     * (blue and red). The in-ballista range (1 to 10 tiles) is (not verified: no ballista unit).
     */
    public fun displayUnitStandingAttackRange(
        menu: Menu.MenuProc,
        menuItem: Menu.MenuItemProc,
    ): Int {
        map.bmMapFill(map.movement, -1)
        map.bmMapFill(map.range, 0)

        if (units.state(active.unit) and US_IN_BALLISTA != 0) {
            range.mapAddInBoundedRange(units.xPos(active.unit), units.yPos(active.unit), 1, 10)
        } else {
            val reach = range.getUnitWeaponReachBits(units.unitOffset(active.unit), -1)
            range.generateUnitStandingReachRange(active.unit, reach)
        }

        hooks.displayMoveRangeGraphics.invoke(
            PlayerPhase.LIMITVIEW_BLUE or PlayerPhase.LIMITVIEW_RED,
        )

        return 0
    }

    /**
     * Ported from src/bmmenu.c: HideMoveRangeGraphicsWrapper
     *
     * Switch-out of the Attack item: hides the range squares.
     */
    public fun hideMoveRangeGraphicsWrapper(
        menu: Menu.MenuProc,
        menuItem: Menu.MenuItemProc,
    ): Int {
        hooks.hideMoveRangeGraphics()
        return 0
    }

    /**
     * Ported from src/bmmenu.c: WeaponSelectMenu_IsAvailable
     *
     * Item [number] of the unit is shown when it is a weapon the unit can use with at least one
     * target in reach ([Hooks.weaponTargetCount]); else not shown. The weapon list is built
     * from this answer, once per slot. The captured slots show only that a non-weapon is not
     * shown: the unusable-weapon and no-target answers are (not verified: no captured slot has
     * a weapon that fails them).
     */
    public fun weaponSelectMenuIsAvailable(def: Int, number: Int): Int {
        val o = units.unitOffset(active.unit)
        val item = u16(units.bytes, o + OFF_ITEMS + 2 * number)

        if (items.getItemAttributes(item) and ItemInfo.IA_WEAPON == 0) return Menu.MENU_NOTSHOWN

        if (!loader.canUnitUseWeapon(o, item)) return Menu.MENU_NOTSHOWN

        if (hooks.weaponTargetCount(active.unit, item) == 0) return Menu.MENU_NOTSHOWN

        return Menu.MENU_ENABLED
    }

    /**
     * Transcribed from src/bmmenu.c: WeaponSelectMenu_Draw (not verified: the row itself is
     * drawn by [Hooks.drawItemMenuLine], MC-4, so no captured tile shows its arguments)
     *
     * Draws the weapon row of [menuItem]: the item in its slot, greyed when the unit cannot use
     * it now.
     */
    public fun weaponSelectMenuDraw(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        val o = units.unitOffset(active.unit)
        val item = u16(units.bytes, o + OFF_ITEMS + 2 * menuItem.itemNumber)

        val isUsable = loader.canUnitUseWeapon(o, item)

        hooks.drawItemMenuLine.draw(
            menuItem.text, item, isUsable, menuItem.yTile * TILEMAP_WIDTH + menuItem.xTile,
        )

        return 0
    }

    /**
     * Ported from src/bmmenu.c: WeaponSelectMenu_SwitchIn
     *
     * Switch-in of a weapon row: shows its slot in the item panel ([Hooks.updateMenuItemPanel])
     * and the squares of that weapon's reach alone (red).
     */
    public fun weaponSelectMenuSwitchIn(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        hooks.updateMenuItemPanel.invoke(menuItem.itemNumber)

        map.bmMapFill(map.movement, -1)
        map.bmMapFill(map.range, 0)

        val reach = range.getUnitWeaponReachBits(
            units.unitOffset(active.unit), menuItem.itemNumber,
        )
        range.generateUnitStandingReachRange(active.unit, reach)

        hooks.displayMoveRangeGraphics.invoke(PlayerPhase.LIMITVIEW_RED)

        return 0
    }

    /**
     * Ported from src/bmmenu.c: WeaponSelectMenu_Selected
     *
     * Chooses the weapon in the row's slot: it becomes the unit's first item
     * ([equipUnitItemSlot]), the action's item slot is 0, BG 0 and 1 are cleared
     * ([UiFrame.clearBg0Bg1]), the target list of the weapon is made, the target selection
     * starts ([Hooks.startAttackTargetSelection]) and `sub_80832C8` runs. Returns the menu
     * actions that end the menu and the face.
     */
    public fun weaponSelectMenuSelected(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        equipUnitItemSlot(active.unit, menuItem.itemNumber)
        active.action.itemSlotIndex = 0

        UiFrame.clearBg0Bg1(hardware)

        hooks.makeTargetListForWeapon(active.unit, units.item(active.unit, 0))

        hooks.startAttackTargetSelection()

        subEventInfo80832C8()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_ENDFACE
    }

    /**
     * Transcribed from src/eventinfo.c: sub_80832C8 (not verified: it returns 0 and writes
     * nothing, so no capture can show it)
     */
    private fun subEventInfo80832C8(): Int = 0

    /**
     * Ported from src/bmitem.c: EquipUnitItemSlot
     *
     * Moves item [itemSlot] of unit [unit] to slot 0, shifting the slots before it down by one.
     */
    public fun equipUnitItemSlot(unit: Int, itemSlot: Int) {
        val at = units.unitOffset(unit) + OFF_ITEMS
        val item = u16(units.bytes, at + 2 * itemSlot)

        for (i in itemSlot downTo 1) {
            units.bytes[at + 2 * i] = units.bytes[at + 2 * (i - 1)]
            units.bytes[at + 2 * i + 1] = units.bytes[at + 2 * (i - 1) + 1]
        }

        units.bytes[at] = item.toByte()
        units.bytes[at + 1] = (item shr 8).toByte()
    }

    /**
     * Ported from src/bmmenu.c: BallistaRangeMenu_SwitchOut
     *
     * Switch-out of a weapon row: hides the range squares unless the menu is ending (the
     * choice keeps them for the target selection). The weapon list's menu state tests the bit
     * `US_DEAD`, which is the same bit as `MENU_STATE_ENDING`.
     */
    public fun ballistaRangeMenuSwitchOut(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        if (menu.state and US_DEAD == 0) {
            hooks.hideMoveRangeGraphics()
        }

        return 0
    }

    /**
     * Ported from src/bmmenu.c: ItemMenu_ButtonBPressed
     *
     * B in the weapon list: clears BG 2, reopens the unit menu at the cursor, hides the range
     * squares and returns the actions that end the menu and the face.
     */
    public fun itemMenuButtonBPressed(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.enableBgSyncByMask(1 shl 2)

        hooks.resetTextFont()

        menus.startSemiCenteredOrphanMenu(Menu.UNIT_ACTION_MENU_DEF, hooks.cursorScreenX(), 1, 22)

        hooks.hideMoveRangeGraphics()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6B or
            Menu.MENU_ACT_CLEAR or Menu.MENU_ACT_ENDFACE
    }

    /**
     * Ported from src/bmitem.c: CanUnitUseWeaponNow
     *
     * Whether the unit at byte offset [o] may wield [item] now: a weapon, not a magic weapon
     * while magic-sealed (the silenced and [Hooks.isPositionMagicSealed] test of
     * `IsUnitMagicSealed`, not verified: the captured weapons are not magic), and
     * [UnitLoader.canUnitUseWeapon].
     */
    internal fun canUnitUseWeaponNow(o: Int, item: Int): Boolean {
        if (item == 0) return false

        val attributes = items.getItemAttributes(item)

        if (attributes and ItemInfo.IA_WEAPON == 0) return false

        if (attributes and ItemInfo.IA_MAGIC != 0 && isUnitMagicSealed(o)) return false

        return loader.canUnitUseWeapon(o, item)
    }

    /** Transcribed from src/bmunit.c: IsUnitMagicSealed (not verified: no magic weapon). */
    private fun isUnitMagicSealed(o: Int): Boolean =
        (units.bytes[o + OFF_STATUS].toInt() and 0xF) == UnitLoader.UNIT_STATUS_SILENCED ||
            hooks.isPositionMagicSealed(
                units.bytes[o + 0x10].toInt(),
                units.bytes[o + 0x11].toInt(),
            )

    /** ROM addresses and constants of the unit menu. */
    public companion object {
        /** ROM address of `AttackCommandUsability` (thumb). */
        public const val ATTACK_COMMAND_USABILITY: Int = 0x080249AD

        /** ROM address of `ItemCommandUsability` (thumb). */
        public const val ITEM_COMMAND_USABILITY: Int = 0x080232B9

        /** ROM address of `EffectWait` (thumb). */
        public const val EFFECT_WAIT: Int = 0x08022739

        /** ROM address of `UnitActionMenu_Attack` (thumb). */
        public const val UNIT_ACTION_MENU_ATTACK: Int = 0x08022B31

        /** ROM address of `DisplayUnitStandingAttackRange` (thumb). */
        public const val DISPLAY_UNIT_STANDING_ATTACK_RANGE: Int = 0x08022C31

        /** ROM address of `HideMoveRangeGraphicsWrapper` (thumb). */
        public const val HIDE_MOVE_RANGE_GRAPHICS_WRAPPER: Int = 0x08022C99

        /** ROM address of `WeaponSelectMenu_IsAvailable` (thumb). */
        public const val WEAPON_SELECT_MENU_IS_AVAILABLE: Int = 0x08022CA5

        /** ROM address of `WeaponSelectMenu_Draw` (thumb). */
        public const val WEAPON_SELECT_MENU_DRAW: Int = 0x08022D35

        /** ROM address of `WeaponSelectMenu_SwitchIn` (thumb). */
        public const val WEAPON_SELECT_MENU_SWITCH_IN: Int = 0x08022D85

        /** ROM address of `WeaponSelectMenu_Selected` (thumb). */
        public const val WEAPON_SELECT_MENU_SELECTED: Int = 0x08022CF1

        /** ROM address of `BallistaRangeMenu_SwitchOut` (thumb). */
        public const val BALLISTA_RANGE_MENU_SWITCH_OUT: Int = 0x08022DD9

        /** ROM address of `ItemMenu_ButtonBPressed` (thumb). */
        public const val ITEM_MENU_BUTTON_B_PRESSED: Int = 0x08022861

        /** `UNIT_ACTION_WAIT`. */
        public const val UNIT_ACTION_WAIT: Int = 1

        private const val ITEM_DEF_SIZE = 0x24
        private const val ICON_PALETTE_ID = 4
        private const val TILEMAP_WIDTH = 32
        private const val US_HAS_MOVED = 1 shl 6
        private const val US_IN_BALLISTA = 1 shl 11
        private const val US_DEAD = 1 shl 2
        private const val CLASS_PHANTOM = 0x51
        private const val OFF_ITEMS = 0x1E
        private const val OFF_STATUS = 0x30
    }
}
