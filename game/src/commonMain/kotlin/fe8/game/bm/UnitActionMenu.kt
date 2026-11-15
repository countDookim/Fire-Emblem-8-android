package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.u16
import fe8.game.scene.IntConsumer
import fe8.game.scene.IntSupplier
import fe8.game.ui.Menu
import fe8.game.ui.UiFrame
import gbafe.proc.Proc
import gbafe.render.BgHardware
import gbafe.text.Text

/**
 * The unit action menu's item callbacks of src/bmmenu.c (the items of `gUnitActionMenuItems`,
 * src/menu_def.c) for the Prologue: the availability of the Attack (`AttackCommandUsability`)
 * and Item (`ItemCommandUsability`) commands and the effect of Wait (`EffectWait`).
 * [install] registers them in [Menu.callbacks] under the ROM addresses the `MenuItemDef`s
 * hold, together with a stand-in for the availability callback of every other command (EP-30,
 * EP-31, EP-32 and EP-33 port the real ones; see [Hooks.unportedUsability]).
 *
 * It also holds the weapon select menu the Attack command opens (`gWeaponSelectMenuDef`): its
 * start, its availability, draw and switch-in callbacks, and its choice and cancel
 * ([weaponSelectMenuSelected], [itemMenuButtonBPressed]). The target selection a choice starts
 * is [Hooks.startAttackTargetSelection] (`NewTargetSelection`, [TargetSelection]).
 *
 * The unit acting is [active] (`gActiveUnit`, `gActionData`); unit fields are read from
 * [units]. The weapon rows are drawn with [Hooks.drawItemMenuLine] ([ItemMenuLine]).
 */
public class UnitActionMenu(
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val items: ItemInfo,
    private val loader: UnitLoader,
    /** `gBmMapMovement` and `gBmMapRange`, which the range squares shown with the menu read. */
    private val map: BmMap,
    /** The weapon reach lookups of src/bmitem.c and the standing reach range of the unit. */
    private val range: AttackRange,
    /** The BG tilemaps that [weaponSelectMenuSelected] and [itemMenuButtonBPressed] clear. */
    private val hardware: BgHardware,
) {
    /** `StartFace(slot, fid, x, y, disp)`. */
    public fun interface FaceStarter {
        /** Starts portrait [fid] in face slot [slot] at pixel ([x], [y]) with bits [disp]. */
        public fun start(slot: Int, fid: Int, x: Int, y: Int, disp: Int)
    }

    /** `SetFaceBlinkControlById(slot, control)`. */
    public fun interface BlinkSetter {
        /** Sets the blink kind [control] of the face in [slot]. */
        public fun set(slot: Int, control: Int)
    }

    /** `ForceMenuItemPanel(menu, unit, x, y)`. */
    public fun interface PanelStarter {
        /** Starts the panel as a child of [menu] for the unit with id [unit] at tile ([x], [y]). */
        public fun start(menu: Proc, unit: Int, x: Int, y: Int)
    }

    /**
     * Services outside the ported file: functions of other C files, and the branches of this
     * menu that the Prologue captures never reach.
     */
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
         * The availability (`MENU_ENABLED`, `MENU_DISABLED` or `MENU_NOTSHOWN`) of the unit menu
         * items whose `isAvailable` callbacks are not ported (every item but Attack, Item and
         * Wait, whose callback is `MenuAlwaysEnabled`), given the `MenuItemDef` address and the
         * item number. The game's stand-in answers `MENU_NOTSHOWN` for all of them, so only the
         * three ported commands appear; EP-30 to EP-33 port the real callbacks.
         */
        public var unportedUsability: (itemDef: Int, number: Int) -> Int = { _, _ ->
            error("unit menu item availability is not ported")
        }

        /** `ResetIconGraphics`; the game wires [fe8.game.ui.ItemIcon.resetIconGraphics]. */
        public var resetIconGraphics: () -> Unit = {}

        /** `LoadIconPalettes(palId)`; the game wires [fe8.game.ui.ItemIcon.loadIconPalette]. */
        public var loadIconPalettes: IntConsumer = IntConsumer {}

        /** `gPlaySt.chapterIndex`, which `GetUnitPortraitId` reads. */
        public var chapterIndex: IntSupplier = IntSupplier { 0 }

        /** `StartFace(slot, fid, x, y, disp)` ([fe8.game.scene.TalkFace.startFace]). */
        public var startFace: FaceStarter = FaceStarter { _, _, _, _, _ -> }

        /**
         * `SetFaceBlinkControlById(slot, control)`
         * ([fe8.game.scene.TalkFace.setFaceBlinkControlById]).
         */
        public var setFaceBlinkControlById: BlinkSetter = BlinkSetter { _, _ -> }

        /**
         * `ForceMenuItemPanel(proc, unit, 0xF, 0xB)` ([MenuItemPanel.forceMenuItemPanel]): the item
         * panel at tile (15, 11), beside the weapon list, a child of the menu proc, for the unit
         * with id `unit`.
         */
        public var forceMenuItemPanel: PanelStarter = PanelStarter { _, _, _, _ -> }

        /**
         * `UpdateMenuItemPanel(slot)` ([MenuItemPanel.updateMenuItemPanel]): shows item slot `slot`
         * in the panel.
         */
        public var updateMenuItemPanel: IntConsumer = IntConsumer {}

        /**
         * `DrawItemMenuLine(text, item, isUsable, gBG0TilemapBuffer + offset)`: the icon, name
         * and uses of one weapon row ([ItemMenuLine.drawItemMenuLine]).
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
         * `NewTargetSelection(&gSelectInfo_Attack)`: starts the cursor that picks the attack
         * target. The game wires [TargetSelection.newTargetSelection] with the attack select
         * handlers (`AttackMapSelect_*`, [AttackMapSelect]); the default does nothing.
         */
        public var startAttackTargetSelection: () -> Unit = {}

        /**
         * `ResetTextFont()` ([gbafe.text.TextSystem.resetTextFont]): resets the active font's
         * tile counter and special-character state before the unit menu's text is drawn again.
         * The default does nothing; the game wires it to the text system.
         */
        public var resetTextFont: () -> Unit = {}

        /**
         * `gBmSt.cursorTarget.x - gBmSt.camera.x`: the cursor's screen x in pixels, which
         * decides the side the unit menu reopens on (`ItemMenu_ButtonBPressed`).
         */
        public var cursorScreenX: () -> Int = { 0 }

        /**
         * `DisplayMoveRangeGraphics(flags)` with `LIMITVIEW_*` flags; the game wires
         * [MoveLimitView].
         */
        public var displayMoveRangeGraphics: IntConsumer = IntConsumer {}

        /** `HideMoveRangeGraphics()`; the game wires [MoveLimitView]. */
        public var hideMoveRangeGraphics: () -> Unit = {}

        /**
         * `MenuFrozenHelpBox(menu, 0x858)`: the help box of message 0x858 (no ballista ammo left)
         * that a disabled (`MENU_DISABLED`) Attack opens (not verified: no capture has it; not
         * ported).
         */
        public var ballistaAmmoHelp: () -> Unit = { error("MenuFrozenHelpBox is not ported") }

        /**
         * `StartUnitBallistaSelect(menu, menuItem)`: opens the ballista range menu
         * (`gBallistaRangeMenuDef`) when the unit is in a ballista (not verified: no capture has
         * a ballista unit; not ported). Returns the menu actions (`MENU_ACT_*`).
         */
        public var startUnitBallistaSelect: () -> Int = {
            error("StartUnitBallistaSelect is not ported")
        }
    }

    /** Draws one weapon row of the weapon select menu: [Hooks.drawItemMenuLine]. */
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

    /**
     * Registers the callbacks of the unit action menu and the weapon select menu in [menu]:
     * the real ones of this class, and for every `isAvailable` callback of
     * `gUnitActionMenuItems` that is not `MenuAlwaysEnabled` one that answers
     * [Hooks.unportedUsability]. The ported callbacks are registered after those, so they take
     * precedence.
     */
    public fun install(menu: Menu, rom: RomView) {
        menus = menu
        // `MenuDef.menuItems` (+0x08); the list ends at an item whose `isAvailable` (+0x0C) is 0.
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
     * Chooses the Wait command: sets `gActionData.unitActionType` to `UNIT_ACTION_WAIT` and
     * returns `MENU_ACT_SKIPCURSOR | MENU_ACT_END | MENU_ACT_SND6A | MENU_ACT_CLEAR` (keep the
     * cursor, end the menu, play the select sound, clear the menu BGs).
     */
    public fun effectWait(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        active.action.unitActionType = UNIT_ACTION_WAIT

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /**
     * Ported from src/bmmenu.c: ItemCommandUsability
     *
     * The Item command is `MENU_NOTSHOWN` once the unit has moved (`US_HAS_MOVED`), for a
     * `CLASS_PHANTOM`, or when its first item slot is empty; else `MENU_ENABLED`. The moved
     * and Phantom branches are (not verified: the captured unit has neither).
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
     * The Attack command is `MENU_ENABLED` when the unit has not moved (`US_HAS_MOVED`), is not
     * in a ballista (`US_IN_BALLISTA`, whose command is `AttackBallistaCommandUsability`), and
     * has a weapon it can use now ([canUnitUseWeaponNow]) with at least one target in reach
     * ([Hooks.weaponTargetCount]); else `MENU_NOTSHOWN`. The moved and ballista branches are
     * (not verified: the captured unit has neither).
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
     * Chooses the Attack command: resets the icon graphics, loads the item icon palettes at
     * palette 4 (`BGPAL_ICONS`) and opens the weapon select menu ([startUnitWeaponSelect]). The
     * `MENU_DISABLED` and in-ballista branches are (not verified: no capture has them;
     * [Hooks.ballistaAmmoHelp], [Hooks.startUnitBallistaSelect]).
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

    /** Placeholders for the `NULL` menu and item that `sub_8022E54` passes. */
    private val noMenu = Menu.MenuProc()
    private val noItem = Menu.MenuItemProc().also { it.availability = Menu.MENU_ENABLED }

    /**
     * Ported from src/bmmenu.c: sub_8022E54
     *
     * `UnitActionMenu_Attack(NULL, NULL)`: what the cancel proc of the attack target selection
     * (`gProcScr_0859B630`) runs to reopen the weapon list. The original reads the `NULL` item's
     * `availability` from the BIOS area at address 0 (not reproduced: the item counts as
     * `MENU_ENABLED`, so the `MENU_DISABLED` branch is not taken).
     */
    public fun unitActionMenuAttackFromProc() {
        unitActionMenuAttack(noMenu, noItem)
    }

    /**
     * Ported from src/bmmenu.c: StartUnitWeaponSelect
     *
     * Opens the weapon list: starts `gWeaponSelectMenuDef` as an orphan menu, the unit's face
     * at pixel (0xB0, 0xC) (not for a `CLASS_PHANTOM`) and the item panel
     * ([Hooks.startFace], [Hooks.forceMenuItemPanel]), then [subEventInfo80832C4]. Returns
     * `MENU_ACT_SKIPCURSOR | MENU_ACT_END | MENU_ACT_SND6A | MENU_ACT_CLEAR`, which ends the
     * unit menu.
     */
    public fun startUnitWeaponSelect(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        val proc = menus.startOrphanMenu(Menu.WEAPON_SELECT_MENU_DEF)

        if (units.classId(active.unit) != CLASS_PHANTOM) {
            hooks.startFace.start(
                0, loader.getUnitPortraitId(active.unit, hooks.chapterIndex()), FACE_X, FACE_Y,
                FACE_DISP_KIND,
            )
            hooks.setFaceBlinkControlById.set(0, FACE_BLINK_CONTROL)
        }

        hooks.forceMenuItemPanel.start(proc, active.unit, PANEL_X, PANEL_Y)

        subEventInfo80832C4()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /**
     * Transcribed from src/eventinfo.c: sub_80832C4 (not verified: it returns 0 and writes
     * nothing, so no capture can show it). Its game meaning is unknown.
     */
    private fun subEventInfo80832C4(): Int = 0

    /**
     * Ported from src/bmmenu.c: DisplayUnitStandingAttackRange
     *
     * Switch-in (cursor on) of the Attack command: clears `gBmMapMovement` to -1 and
     * `gBmMapRange` to 0, adds the standing reach of all the unit's weapons (`slot` -1 of
     * `GetUnitWeaponReachBits`) to the range, and shows the squares (`LIMITVIEW_BLUE` and
     * `LIMITVIEW_RED`, the C literal 3). The in-ballista range (1 to 10 tiles) is (not
     * verified: no ballista unit).
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
     * Switch-out (cursor off) of the Attack command: hides the range squares.
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
     * Item slot [number] of the unit is `MENU_ENABLED` when it is a weapon (`IA_WEAPON`) the
     * unit can wield ([UnitLoader.canUnitUseWeapon], not the magic-seal test of
     * [canUnitUseWeaponNow]) with at least one target in reach ([Hooks.weaponTargetCount]); else
     * `MENU_NOTSHOWN`. The weapon list is built from this answer, once per slot. The captured
     * slots show only that a non-weapon is not shown: the unusable-weapon and no-target answers
     * are (not verified: no captured slot has a weapon that fails them).
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
     * Draws the weapon row of [menuItem]: the item in slot `itemNumber`, gray when the unit
     * cannot wield it ([UnitLoader.canUnitUseWeapon]), at the row's tile of the BG 0 tilemap.
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
     * Switch-in (cursor on) of a weapon row: shows its slot in the item panel
     * ([Hooks.updateMenuItemPanel]) and the squares of that weapon's reach alone
     * (`LIMITVIEW_RED`, the C literal 2).
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
     * ([equipUnitItemSlot]), `gActionData.itemSlotIndex` is 0, BG 0 and 1 are cleared
     * ([UiFrame.clearBg0Bg1]), the target list of the weapon is made, the target selection
     * starts ([Hooks.startAttackTargetSelection]) and `sub_80832C8` runs. Returns
     * `MENU_ACT_SKIPCURSOR | MENU_ACT_END | MENU_ACT_SND6A | MENU_ACT_ENDFACE` (end the menu
     * and the unit's face; the menu BGs were cleared above).
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
     * nothing, so no capture can show it). Its game meaning is unknown.
     */
    private fun subEventInfo80832C8(): Int = 0

    /**
     * Ported from src/bmitem.c: EquipUnitItemSlot
     *
     * Moves item [itemSlot] of unit [unit] to slot 0, shifting the slots before it down by one.
     */
    public fun equipUnitItemSlot(unit: Int, itemSlot: Int) {
        items.equipUnitItemSlot(units.bytes, units.unitOffset(unit), itemSlot)
    }

    /**
     * Ported from src/bmmenu.c: BallistaRangeMenu_SwitchOut
     *
     * Switch-out (cursor off) of a weapon row: hides the range squares unless the menu is ending
     * (the choice keeps them for the target selection). The decomp (named for the ballista
     * range menu, which shares this callback) tests `menu->state & US_DEAD`, which is bit 2,
     * the same bit as `MENU_STATE_ENDING`.
     */
    public fun ballistaRangeMenuSwitchOut(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        if (menu.state and Menu.MENU_STATE_ENDING == 0) {
            hooks.hideMoveRangeGraphics()
        }

        return 0
    }

    /**
     * Ported from src/bmmenu.c: ItemMenu_ButtonBPressed
     *
     * Cancel (B) in the weapon list: clears BG 2, reopens the unit action menu on the side away
     * from the cursor ([Hooks.cursorScreenX], columns 1 and 22), hides the range squares and
     * returns `MENU_ACT_SKIPCURSOR | MENU_ACT_END | MENU_ACT_SND6B | MENU_ACT_CLEAR |
     * MENU_ACT_ENDFACE` (end the menu and the face, play the cancel sound).
     */
    public fun itemMenuButtonBPressed(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.enableBgSyncByMask(BG2_SYNC_BIT)

        hooks.resetTextFont()

        menus.startSemiCenteredOrphanMenu(Menu.UNIT_ACTION_MENU_DEF, hooks.cursorScreenX(), 1, 22)

        hooks.hideMoveRangeGraphics()

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6B or
            Menu.MENU_ACT_CLEAR or Menu.MENU_ACT_ENDFACE
    }

    /**
     * Ported from src/bmitem.c: CanUnitUseWeaponNow
     *
     * Whether the unit at byte offset [o] in the unit table may wield [item] now: a weapon
     * (`IA_WEAPON`), not a magic weapon (`IA_MAGIC`) while the unit is magic-sealed
     * (`IsUnitMagicSealed`: `UNIT_STATUS_SILENCED` or [Hooks.isPositionMagicSealed]; not
     * verified: the captured weapons are not magic), and [UnitLoader.canUnitUseWeapon].
     */
    internal fun canUnitUseWeaponNow(o: Int, item: Int): Boolean {
        if (item == 0) return false

        val attributes = items.getItemAttributes(item)

        if (attributes and ItemInfo.IA_WEAPON == 0) return false

        if (attributes and ItemInfo.IA_MAGIC != 0 && isUnitMagicSealed(o)) return false

        return loader.canUnitUseWeapon(o, item)
    }

    /**
     * Transcribed from src/bmunit.c: IsUnitMagicSealed (not verified: no magic weapon)
     *
     * Whether the unit at byte offset [o] is silenced or stands on a tile an enemy magic-sealing
     * unit reaches.
     */
    private fun isUnitMagicSealed(o: Int): Boolean =
        (units.bytes[o + OFF_STATUS].toInt() and 0xF) == UnitLoader.UNIT_STATUS_SILENCED ||
            hooks.isPositionMagicSealed(
                units.bytes[o + OFF_X_POS].toInt(),
                units.bytes[o + OFF_Y_POS].toInt(),
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

        /** `sizeof(struct MenuItemDef)`. */
        private const val ITEM_DEF_SIZE = 0x24

        /** The `LoadIconPalettes` destination: palette 4, `BGPAL_ICONS`. */
        private const val ICON_PALETTE_ID = 4

        /** Tiles per row of a BG tilemap (`TILEMAP_INDEX`). */
        private const val TILEMAP_WIDTH = 32

        /** `BG2_SYNC_BIT`. */
        private const val BG2_SYNC_BIT = 1 shl 2

        /** The unit face's pixel position in the weapon list (the `StartFace` literals). */
        private const val FACE_X = 0xB0
        private const val FACE_Y = 0xC

        /** The `StartFace` display type literal 2. */
        private const val FACE_DISP_KIND = 2

        /** The `SetFaceBlinkControlById` literal 5 (not named in the decomp). */
        private const val FACE_BLINK_CONTROL = 5

        /** The tile position of the item panel (the `ForceMenuItemPanel` literals). */
        private const val PANEL_X = 0xF
        private const val PANEL_Y = 0xB

        /** `US_HAS_MOVED` of `Unit.state`. */
        private const val US_HAS_MOVED = 1 shl 6

        /** `US_IN_BALLISTA` of `Unit.state`. */
        private const val US_IN_BALLISTA = 1 shl 11

        /** `CLASS_PHANTOM`. */
        private const val CLASS_PHANTOM = 0x51

        /** `Unit.items` (+0x1E): five u16 item words. */
        private const val OFF_ITEMS = 0x1E

        /** `Unit.xPos` and `Unit.yPos`. */
        private const val OFF_X_POS = 0x10
        private const val OFF_Y_POS = 0x11

        /** `Unit.statusIndex` (low nibble; the high nibble is the duration). */
        private const val OFF_STATUS = 0x30
    }
}
