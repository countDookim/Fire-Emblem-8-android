package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.u16
import fe8.game.ui.Menu

/**
 * The unit action menu's item callbacks of src/bmmenu.c (the items of `gUnitActionMenuItems`,
 * src/menu_def.c) for the Prologue: the Attack and Item availability and the Wait effect.
 * [install] registers them, and a stand-in for every other availability callback, in
 * [Menu.callbacks] under the ROM addresses the `MenuItemDef`s hold.
 *
 * The unit acting is [active] (`gActiveUnit`, `gActionData`).
 */
public class UnitActionMenu(
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val items: ItemInfo,
    private val loader: UnitLoader,
) {
    /** Services outside the ported file. */
    public class Hooks {
        /**
         * `MakeTargetListForWeapon(unit, item)` then `GetSelectTargetCount()` (bmtarget.c,
         * uiselecttarget.c, not ported): the number of targets of [item] for the unit with id
         * `unit`.
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
    }

    /** Services outside the ported file. */
    public val hooks: Hooks = Hooks()

    /** Registers the unit menu's callbacks in [menu]. */
    public fun install(menu: Menu, rom: RomView) {
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

        /** `UNIT_ACTION_WAIT`. */
        public const val UNIT_ACTION_WAIT: Int = 1

        private const val ITEM_DEF_SIZE = 0x24
        private const val US_HAS_MOVED = 1 shl 6
        private const val US_IN_BALLISTA = 1 shl 11
        private const val CLASS_PHANTOM = 0x51
        private const val OFF_ITEMS = 0x1E
        private const val OFF_STATUS = 0x30
    }
}
