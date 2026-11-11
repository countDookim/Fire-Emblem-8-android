package fe8.game.ui

import fe8.game.RomView
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.jump
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileExists
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The generic menu (decomp `src/uimenu.c`): `sProc_Menu` with one `sProc_MenuItem` child per
 * shown item, drawn into a back BG (frame and item hover) and a front BG (item text).
 *
 * A menu is described by a `MenuDef` in the ROM (ADR 0015: the ROM is the vanilla data source),
 * which [MenuProc.def] and [MenuItemProc.def] hold as GBA addresses. The def's callbacks and the
 * item defs' callbacks (availability, draw, select, idle, switch in and out) are called through
 * [callbacks], keyed by the ROM address of the original function. Registered by default are the
 * `uimenu.c` ones: `MenuAlwaysEnabled` and `MenuCancelSelect`. The others are stand-ins until
 * their port: the unit menu's are registered by `fe8.game.bm.UnitActionMenu.install` (PP-10) and
 * the map menu's wait for PP-12 (an unregistered address fails loudly instead of guessing).
 */
public class Menu(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val hardware: BgHardware,
    private val text: TextSystem,
    private val messages: MsgStrings,
    private val keys: KeyStatus,
    private val hand: UiHand,
    private val greenText: GreenText,
    private val camMoveScript: ProcScript,
) {
    /** Services outside the ported file; the defaults do nothing. */
    public class Hooks {
        /** `LockGame`. */
        public var lockGame: () -> Unit = {}

        /** `UnlockGame`. */
        public var unlockGame: () -> Unit = {}

        /** `PlaySoundEffect`. */
        public var playSoundEffect: (Int) -> Unit = {}

        /** `GetGameClock`. */
        public var gameClock: () -> Int = { 0 }

        /** `EndFaceById`. */
        public var endFaceById: (Int) -> Unit = {}

        /**
         * `DisplayFrozenUiHand`: stand-in for PP-9 follow-up (a capture that runs a frozen menu).
         */
        public var displayFrozenUiHand: (Int, Int) -> Unit = { _, _ -> }
    }

    /** One callback of a `MenuDef` or `MenuItemDef`, called by ROM address ([callbacks]). */
    public fun interface MenuFunc {
        /**
         * Runs the callback. [menu] and [item] are null where the original takes no such
         * argument, [itemDef] is the `MenuItemDef` address for an availability callback, and
         * [number] is its item index. The result is the callback's C return value.
         */
        public fun invoke(menu: MenuProc?, item: MenuItemProc?, itemDef: Int, number: Int): Int
    }

    /** `struct MenuRect`: tile position and size, each an `s8`. */
    public class MenuRect(
        /** `x`. */
        public var x: Int = 0,
        /** `y`. */
        public var y: Int = 0,
        /** `w`. */
        public var w: Int = 0,
        /** `h`. */
        public var h: Int = 0,
    )

    /** `struct MenuProc` past `PROC_HEADER` (+0x2C). */
    public class MenuProc {
        /** `rect` (+0x2C). */
        public val rect: MenuRect = MenuRect()

        /** `def` (+0x30): ROM address of the `MenuDef`. */
        public var def: Int = 0

        /** `menuItems` (+0x34): the shown items' procs' fields; [itemCount] are set. */
        public val menuItems: Array<MenuItemProc?> = arrayOfNulls(MENU_ITEM_MAX)

        /** `itemCount` (+0x60), u8. */
        public var itemCount: Int = 0

        /** `itemCurrent` (+0x61), u8. */
        public var itemCurrent: Int = 0

        /** `itemPrevious` (+0x62), u8. */
        public var itemPrevious: Int = 0

        /** `state` (+0x63), u8 of `MENU_STATE_*`. */
        public var state: Int = 0

        /** `backBg` (+0x64 bits 0..1). */
        public var backBg: Int = 0

        /** `frontBg` (+0x64 bits 2..3). */
        public var frontBg: Int = 0

        /** `tileref` (+0x66), u16. */
        public var tileref: Int = 0

        /** `unk68` (+0x68), u16. */
        public var unk68: Int = 0

        /** The menu's proc. */
        public lateinit var proc: Proc
    }

    /** `struct MenuItemProc` past `PROC_HEADER` (+0x2A). */
    public class MenuItemProc {
        /** `xTile` (+0x2A), s16. */
        public var xTile: Int = 0

        /** `yTile` (+0x2C), s16. */
        public var yTile: Int = 0

        /** `def` (+0x30): ROM address of the `MenuItemDef`. */
        public var def: Int = 0

        /** `text` (+0x34). */
        public val text: Text = Text()

        /** `itemNumber` (+0x3C), s8. */
        public var itemNumber: Int = 0

        /** `availability` (+0x3D), u8: `MENU_ENABLED`, `MENU_DISABLED` or `MENU_NOTSHOWN`. */
        public var availability: Int = 0

        /** The item's proc. */
        public lateinit var proc: Proc
    }

    /** Services outside the ported file. */
    public val hooks: Hooks = Hooks()

    /** Callbacks by ROM address (thumb bit set, as stored in the defs). */
    public val callbacks: HashMap<Int, MenuFunc> = HashMap()

    private val cursor = IntArray(2)

    private val onIdleFn = ProcFunc { menuOnIdle(it) }
    private val endGreenTextFn = ProcFunc { greenText.endGreenText() }
    private val startGreenTextFn = ProcFunc { greenText.startGreenText(it) }
    private val redrawMenuFn = ProcFunc { redrawMenu(it) }
    private val onInitFn = ProcFunc { menuOnInit(it) }

    /** `sProc_MenuMain` (0x085B64B8). */
    public val menuMainScript: ProcScript = ProcScript(
        "sProc_MenuMain",
        listOf(repeat(onIdleFn), call(endGreenTextFn), end()),
    )

    /** `sProc_Menu` (0x085B64D0). */
    public val menuScript: ProcScript = ProcScript(
        "sProc_Menu",
        listOf(
            name("E_Menu"),
            sleep(0),
            whileExists { camMoveScript },
            call(startGreenTextFn),
            call(redrawMenuFn),
            call(onInitFn),
            jump { menuMainScript },
            end(),
        ),
        ::MenuProc,
    )

    /** `sProc_MenuItem` (0x085B6510): only blocks. */
    public val menuItemScript: ProcScript = ProcScript(
        "sProc_MenuItem",
        listOf(block()),
        ::MenuItemProc,
    )

    init {
        callbacks[MENU_ALWAYS_ENABLED] =
            MenuFunc { _, _, itemDef, number -> menuAlwaysEnabled(itemDef, number) }
        callbacks[MENU_CANCEL_SELECT] =
            MenuFunc { menu, item, _, _ -> menuCancelSelect(menu!!, item!!) }
    }

    private fun s8(value: Int): Int = value.toByte().toInt()

    private fun invokeCallback(
        address: Int,
        menu: MenuProc?,
        item: MenuItemProc?,
        itemDef: Int,
        number: Int,
    ): Int {
        val callback = callbacks[address]
            ?: error("menu callback at 0x${address.toString(16)} is not registered (PP-10/PP-12)")
        return callback.invoke(menu, item, itemDef, number)
    }

    /**
     * Starts a menu from [def] at its own rect and returns its proc, as a blocking child of
     * [parent] (or a root proc that locks the game when [parent] is null) with the item hover
     * on BG 1 and the item text on BG 0.
     *
     * Ported from src/uimenu.c: StartMenuAt
     */
    public fun startMenuAt(def: Int, rect: MenuRect, parent: Proc?): Proc =
        startMenuCore(def, rect, 1, 0, 0, 0, parent)

    /**
     * Starts [def] as an orphan menu (no parent), its rect's x set to [xTileRight] when
     * [xSubject] is left of pixel 120, else to [xTileLeft].
     *
     * Ported from src/uimenu.c: StartOrphanMenuAdjusted
     */
    public fun startOrphanMenuAdjusted(
        def: Int,
        xSubject: Int,
        xTileLeft: Int,
        xTileRight: Int,
    ): Proc {
        val rect = defRect(def)
        rect.x = if (xSubject < 120) xTileRight else xTileLeft
        return startMenuAt(def, rect, null)
    }

    /**
     * Starts [def] like [startOrphanMenuAdjusted], then moves a menu of more than 6 items up by
     * `sItemCountYOffsetLookup[itemCount]` tiles.
     *
     * Ported from src/uimenu.c: StartSemiCenteredOrphanMenu
     * The move of a long menu is not verified: the captures' unit menu has 2 items.
     */
    public fun startSemiCenteredOrphanMenu(
        def: Int,
        xSubject: Int,
        xTileLeft: Int,
        xTileRight: Int,
    ): Proc {
        val result = startOrphanMenuAdjusted(def, xSubject, xTileLeft, xTileRight)
        val menu = result.state as MenuProc

        if (menu.itemCount <= 6) return result

        // Not verified: no captured menu has more than 6 items.
        val offset = ITEM_COUNT_Y_OFFSET[menu.itemCount]
        menu.rect.y -= offset
        for (i in 0 until menu.itemCount) menu.menuItems[i]!!.yTile -= offset

        return result
    }

    private fun defRect(def: Int): MenuRect =
        MenuRect(s8(rom.u8(def)), s8(rom.u8(def + 1)), s8(rom.u8(def + 2)), s8(rom.u8(def + 3)))

    /**
     * Starts the menu proc and one item proc per shown item of [def]'s item list.
     *
     * Ported from src/uimenu.c: StartMenuCore
     */
    public fun startMenuCore(
        def: Int,
        rect: MenuRect,
        backBg: Int,
        tileref: Int,
        frontBg: Int,
        unk: Int,
        parent: Proc?,
    ): Proc {
        var xTileInner = rect.x + 1
        var yTileInner = rect.y + 1

        hardware.lcd.setBgPosition(frontBg, 0, 0)
        hardware.lcd.setBgPosition(backBg, 0, 0)

        hooks.playSoundEffect(SONG_68)

        val proc: Proc
        if (parent != null) {
            proc = engine.startBlocking(menuScript, parent)!!
            (proc.state as MenuProc).state = 0
        } else {
            hooks.lockGame()
            proc = engine.start(menuScript, 3)
            (proc.state as MenuProc).state = MENU_STATE_GAMELOCKING
        }
        val menu = proc.state as MenuProc
        menu.proc = proc

        if (rect.h < 0) menu.state = menu.state or MENU_STATE_NOTSHOWN

        val items = rom.u32(def + 8)
        var itemCount = 0
        var i = 0
        while (rom.u32(items + ITEM_DEF_SIZE * i + 0xC) != 0) {
            val itemDef = items + ITEM_DEF_SIZE * i
            var availability = overriddenMenuAvailability()

            if (availability == 0) {
                val isAvailable = rom.u32(itemDef + 0xC)
                availability = invokeCallback(isAvailable, null, null, itemDef, i) and 0xFF
            }

            if (availability != MENU_NOTSHOWN) {
                val itemProc = engine.start(menuItemScript, proc)
                val item = itemProc.state as MenuItemProc
                item.proc = itemProc
                menu.menuItems[itemCount++] = item

                item.def = itemDef
                item.itemNumber = i
                item.availability = availability

                item.xTile = xTileInner
                item.yTile = yTileInner

                if (menu.state and MENU_STATE_NOTSHOWN == 0) text.initText(item.text, rect.w - 1)

                yTileInner += 2
            }
            i++
        }

        menu.def = def
        menu.rect.x = rect.x
        menu.rect.y = rect.y
        menu.rect.w = rect.w
        menu.rect.h = rect.h
        menu.itemCount = itemCount
        menu.itemCurrent = 0
        menu.itemPrevious = 0xFF

        if (rect.y + rect.h < yTileInner) menu.rect.h = s8(yTileInner + 1 - rect.y)

        menu.backBg = backBg and 3
        menu.tileref = tileref
        menu.frontBg = frontBg and 3
        menu.unk68 = unk

        keys.newKeys = 0

        return proc
    }

    /**
     * Ends the menu of [proc]: switches out the current item, calls the def's end callback,
     * unlocks the game if the menu locked it, ends the proc and resets the BG positions. Returns
     * the menu proc's parent.
     *
     * Ported from src/uimenu.c: EndMenu
     */
    public fun endMenu(menu: MenuProc): Proc? {
        val item = menu.menuItems[menu.itemCurrent]!!

        menu.state = menu.state or MENU_STATE_ENDING

        val onSwitchOut = rom.u32(item.def + 0x20)
        if (onSwitchOut != 0) invokeCallback(onSwitchOut, menu, item, 0, 0)

        val onEnd = rom.u32(menu.def + 0x10)
        if (onEnd != 0) invokeCallback(onEnd, menu, null, 0, 0)

        if (menu.state and MENU_STATE_GAMELOCKING != 0) hooks.unlockGame()

        engine.end(menu.proc)

        hardware.lcd.setBgPosition(menu.frontBg, 0, 0)
        hardware.lcd.setBgPosition(menu.backBg, 0, 0)

        return menu.proc.parent
    }

    /**
     * Flags the menu's two BGs for sync.
     *
     * Transcribed from src/uimenu.c: SyncMenuBgs (not verified: the sync flags are consumed in
     * VBlank before the RAM dumps)
     */
    private fun syncMenuBgs(menu: MenuProc) {
        hardware.enableBgSyncByMask((1 shl menu.backBg) + (1 shl menu.frontBg))
    }

    /**
     * Clears the menu's two BG tilemaps and flags them for sync.
     *
     * Ported from src/uimenu.c: ClearMenuBgs
     */
    private fun clearMenuBgs(menu: MenuProc) {
        hardware.fill(hardware.mapBuffer(menu.frontBg), 0)
        hardware.fill(hardware.mapBuffer(menu.backBg), 0)

        syncMenuBgs(menu)
    }

    /**
     * Transcribed from src/uimenu.c: HasMenuChangedItem (not verified: only its callers'
     * switch in and out branches use it, and no item of the captured menus has such callbacks)
     */
    private fun hasMenuChangedItem(menu: MenuProc): Boolean = menu.itemCurrent != menu.itemPrevious

    /**
     * Calls the def's init callback and the current item's switch-in callback.
     *
     * Ported from src/uimenu.c: Menu_OnInit
     * The callbacks' calls are not verified: none of the captured defs has them.
     */
    public fun menuOnInit(proc: Proc) {
        val menu = proc.state as MenuProc

        val onInit = rom.u32(menu.def + 0xC)
        if (onInit != 0) invokeCallback(onInit, menu, null, 0, 0)

        val current = menu.menuItems[menu.itemCurrent]!!
        val onSwitchIn = rom.u32(current.def + 0x1C)
        if (onSwitchIn != 0) invokeCallback(onSwitchIn, menu, current, 0, 0)
    }

    /**
     * Draws the frame on the back BG, clears the front BG's rectangle, draws each item's text
     * (or runs its draw callback) and the hover bar of the current item.
     *
     * Ported from src/uimenu.c: RedrawMenu
     */
    public fun redrawMenu(proc: Proc) {
        val menu = proc.state as MenuProc

        if (menu.state and MENU_STATE_NOTSHOWN != 0) return

        UiFrame.drawUiFrame(
            hardware.mapBuffer(menu.backBg), rom,
            menu.rect.x, menu.rect.y, menu.rect.w, menu.rect.h,
            menu.tileref, rom.u8(menu.def + 4),
        )

        UiFrame.clearUiFrame(
            hardware.mapBuffer(menu.frontBg),
            menu.rect.x, menu.rect.y, menu.rect.w, menu.rect.h,
        )

        for (i in 0 until menu.itemCount) {
            val item = menu.menuItems[i]!!

            val onDraw = rom.u32(item.def + 0x10)
            if (onDraw != 0) {
                invokeCallback(onDraw, menu, item, 0, 0)
                continue
            }

            val color = rom.u8(item.def + 8)
            if (color != 0) item.text.colorId = color

            if (item.availability == MENU_DISABLED) item.text.colorId = TEXT_COLOR_SYSTEM_GRAY

            val nameMsgId = rom.u16(item.def + 4)
            if (nameMsgId == 0) {
                text.drawString(item.text, stringAt(rom.u32(item.def)))
            } else {
                text.drawString(item.text, messages.getStringFromIndex(nameMsgId))
            }

            text.putText(item.text, hardware.mapBuffer(menu.frontBg), item.yTile * 32 + item.xTile)
        }

        drawMenuItemHover(menu, menu.itemCurrent, true)
        syncMenuBgs(menu)
    }

    private fun stringAt(at: Int): ByteArray {
        var end = at
        while (rom.u8(end) != 0) end++
        return ByteArray(end - at + 1) { rom.u8(at + it).toByte() }
    }

    /**
     * Draws ([hover] true) or clears the hover bar of item [item] on the back BG, unless the
     * menu is flat.
     *
     * Ported from src/uimenu.c: DrawMenuItemHover
     */
    public fun drawMenuItemHover(menu: MenuProc, item: Int, hover: Boolean) {
        if (menu.state and MENU_STATE_FLAT != 0) return

        val x = menu.rect.x + 1
        val y = menu.menuItems[item]!!.yTile
        val w = menu.rect.w - 2

        if (hover) {
            UiFrame.drawUiItemHoverExt(hardware, menu.backBg, menu.tileref, x, y, w)
        } else {
            UiFrame.clearUiItemHoverExt(hardware, rom, menu.backBg, menu.tileref, x, y, w)
        }
    }

    /**
     * One frame of the menu: cursor and select input, then the actions the select callback
     * asked for (`MENU_ACT_*`), then the hand.
     *
     * Ported from src/uimenu.c: Menu_OnIdle
     * The frozen, doomed, end-face and doom branches are not verified: no capture runs them.
     */
    public fun menuOnIdle(proc: Proc) {
        val menu = proc.state as MenuProc

        if (menu.state and MENU_STATE_FROZEN != 0) {
            getMenuCursorPosition(menu, cursor)
            hooks.displayFrozenUiHand(cursor[0], cursor[1])
            return
        }

        if (menu.state and MENU_STATE_DOOMED != 0) {
            endMenu(menu)
            return
        }

        processMenuDpadInput(menu)
        val actions = processMenuSelectInput(menu)

        if (actions and MENU_ACT_END != 0) endMenu(menu)

        if (actions and MENU_ACT_SND6A != 0) hooks.playSoundEffect(SONG_SE_SYS_WINDOW_SELECT1)

        if (actions and MENU_ACT_SND6B != 0) hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)

        if (actions and MENU_ACT_CLEAR != 0) clearMenuBgs(menu)

        if (actions and MENU_ACT_ENDFACE != 0) hooks.endFaceById(0)

        if (actions and MENU_ACT_DOOM != 0) menu.state = menu.state or MENU_STATE_DOOMED

        if (actions and MENU_ACT_SKIPCURSOR != 0) return

        if (menu.state and MENU_STATE_NOCURSOR != 0) return

        getMenuCursorPosition(menu, cursor)
        applyMenuCursorVScroll(menu, cursor)

        hand.displayUiHand(cursor[0], cursor[1], hooks.gameClock())
    }

    /**
     * Moves the current item with the repeated up and down keys (wrapping only on a fresh press),
     * moves the hover bar and plays the cursor sound, and calls the switch callbacks.
     *
     * Ported from src/uimenu.c: ProcessMenuDpadInput
     * The switch-in and switch-out callbacks are not verified: none of the captured items has
     * them.
     */
    public fun processMenuDpadInput(menu: MenuProc) {
        menu.itemPrevious = menu.itemCurrent

        // Handle up keyin

        if (keys.repeatedKeys and DPAD_UP != 0) {
            if (menu.itemCurrent == 0) {
                if (keys.repeatedKeys != keys.newKeys) return

                menu.itemCurrent = menu.itemCount
            }

            menu.itemCurrent = (menu.itemCurrent - 1) and 0xFF
        }

        // Handle down keyin

        if (keys.repeatedKeys and DPAD_DOWN != 0) {
            // The wrap at the last item is not verified: no capture presses DOWN there.
            if (menu.itemCurrent == menu.itemCount - 1) {
                if (keys.repeatedKeys != keys.newKeys) return

                menu.itemCurrent = 0xFF
            }

            menu.itemCurrent = (menu.itemCurrent + 1) and 0xFF
        }

        // Update hover display

        if (menu.itemPrevious != menu.itemCurrent) {
            drawMenuItemHover(menu, menu.itemPrevious, false)
            drawMenuItemHover(menu, menu.itemCurrent, true)

            hooks.playSoundEffect(SONG_SE_SYS_CURSOR_UD1)
        }

        // Call def's switch in/out funcs

        if (hasMenuChangedItem(menu)) {
            val previous = menu.menuItems[menu.itemPrevious]!!
            val onSwitchOut = rom.u32(previous.def + 0x20)
            if (onSwitchOut != 0) invokeCallback(onSwitchOut, menu, previous, 0, 0)

            val current = menu.menuItems[menu.itemCurrent]!!
            val onSwitchIn = rom.u32(current.def + 0x1C)
            if (onSwitchIn != 0) invokeCallback(onSwitchIn, menu, current, 0, 0)
        }
    }

    /**
     * Runs the current item's idle callback, then on A its select callback, on B the def's B
     * callback or on R the def's R callback; returns the `MENU_ACT_*` bits of the last callback
     * that returned one.
     *
     * Ported from src/uimenu.c: ProcessMenuSelectInput
     * The idle and R callbacks are not verified: none of the captured defs has them.
     */
    public fun processMenuSelectInput(menu: MenuProc): Int {
        var result = 0

        val item = menu.menuItems[menu.itemCurrent]!!
        val onIdle = rom.u32(item.def + 0x18)

        if (onIdle != 0) result = invokeCallback(onIdle, menu, item, 0, 0)

        if (keys.newKeys and A_BUTTON != 0) {
            // A Button press

            result = overriddenMenuSelected()

            val onSelected = rom.u32(item.def + 0x14)
            if (result == 0xFF && onSelected != 0) {
                result = invokeCallback(onSelected, menu, item, 0, 0)
            }
        } else if (keys.newKeys and B_BUTTON != 0) {
            // B Button press

            val onBPress = rom.u32(menu.def + 0x18)
            if (onBPress != 0) result = invokeCallback(onBPress, menu, item, 0, 0)
        } else if (keys.newKeys and R_BUTTON != 0) {
            // R Button press

            val onRPress = rom.u32(menu.def + 0x1C)
            if (onRPress != 0) invokeCallback(onRPress, menu, null, 0, 0)
        }

        return result
    }

    /**
     * Stores the current item's tile position in pixels in [out] (x, y), x moved 4 left for a
     * def with a nonzero style.
     *
     * Ported from src/uimenu.c: GetMenuCursorPosition
     */
    public fun getMenuCursorPosition(menu: MenuProc, out: IntArray) {
        val item = menu.menuItems[menu.itemCurrent]!!
        out[0] = item.xTile * 8
        out[1] = item.yTile * 8

        if (rom.u8(menu.def + 4) != 0) out[0] -= 4
    }

    /**
     * Scrolls the menu's BGs and the cursor [out] for a menu of more than 9 items.
     *
     * Ported from src/uimenu.c: ApplyMenuCursorVScroll
     * The scrolling itself is not verified: no captured menu has more than 9 items.
     */
    public fun applyMenuCursorVScroll(menu: MenuProc, out: IntArray) {
        if (menu.itemCount <= 9) return

        val off = (menu.itemCount * 16 - 9 * 16) * menu.itemCurrent / 9

        hardware.lcd.setBgPosition(menu.frontBg, 0, off)
        hardware.lcd.setBgPosition(menu.backBg, 0, off)

        out[1] -= off
    }

    /**
     * Availability of an item def that the menu overrides, or 0 for none.
     *
     * Transcribed from src/uimenu.c: OverriddenMenuAvailability (not verified: the override
     * table is empty in the captures and AddMenuOverride is not ported)
     */
    private fun overriddenMenuAvailability(): Int = 0

    /**
     * `0xFF` (no override) for an item whose select an override table would replace.
     *
     * Transcribed from src/uimenu.c: OverriddenMenuSelected (not verified: see
     * [overriddenMenuAvailability])
     */
    private fun overriddenMenuSelected(): Int = 0xFF

    /**
     * Availability of an item that is always enabled.
     *
     * Ported from src/uimenu.c: MenuAlwaysEnabled
     */
    public fun menuAlwaysEnabled(itemDef: Int, number: Int): Int = MENU_ENABLED

    /**
     * The select result that clears and ends the menu with the cancel sound.
     *
     * Ported from src/uimenu.c: MenuCancelSelect
     */
    public fun menuCancelSelect(menu: MenuProc, item: MenuItemProc): Int =
        MENU_ACT_SKIPCURSOR or MENU_ACT_CLEAR or MENU_ACT_END or MENU_ACT_SND6B

    /** Menu constants (`include/uimenu.h`) and ROM addresses. */
    public companion object {
        /** `MENU_ITEM_MAX`. */
        public const val MENU_ITEM_MAX: Int = 11

        /** `MENU_ENABLED`. */
        public const val MENU_ENABLED: Int = 1

        /** `MENU_DISABLED`. */
        public const val MENU_DISABLED: Int = 2

        /** `MENU_NOTSHOWN`. */
        public const val MENU_NOTSHOWN: Int = 3

        /** `MENU_STATE_GAMELOCKING`. */
        public const val MENU_STATE_GAMELOCKING: Int = 1 shl 0

        /** `MENU_STATE_ENDING`. */
        public const val MENU_STATE_ENDING: Int = 1 shl 2

        /** `MENU_STATE_NOTSHOWN`. */
        public const val MENU_STATE_NOTSHOWN: Int = 1 shl 3

        /** `MENU_STATE_FLAT`. */
        public const val MENU_STATE_FLAT: Int = 1 shl 4

        /** `MENU_STATE_NOCURSOR`. */
        public const val MENU_STATE_NOCURSOR: Int = 1 shl 5

        /** `MENU_STATE_FROZEN`. */
        public const val MENU_STATE_FROZEN: Int = 1 shl 6

        /** `MENU_STATE_DOOMED`. */
        public const val MENU_STATE_DOOMED: Int = 1 shl 7

        /** `MENU_ACT_SKIPCURSOR`. */
        public const val MENU_ACT_SKIPCURSOR: Int = 1 shl 0

        /** `MENU_ACT_END`. */
        public const val MENU_ACT_END: Int = 1 shl 1

        /** `MENU_ACT_SND6A`. */
        public const val MENU_ACT_SND6A: Int = 1 shl 2

        /** `MENU_ACT_SND6B`. */
        public const val MENU_ACT_SND6B: Int = 1 shl 3

        /** `MENU_ACT_CLEAR`. */
        public const val MENU_ACT_CLEAR: Int = 1 shl 4

        /** `MENU_ACT_ENDFACE`. */
        public const val MENU_ACT_ENDFACE: Int = 1 shl 5

        /** `MENU_ACT_DOOM`. */
        public const val MENU_ACT_DOOM: Int = 1 shl 7

        /** ROM address of `gUnitActionMenuDef`. */
        public const val UNIT_ACTION_MENU_DEF: Int = 0x0859D1F0

        /** ROM address of `gMapMenuDef`. */
        public const val MAP_MENU_DEF: Int = 0x0859D214

        /** ROM address of `MenuAlwaysEnabled` (thumb). */
        public const val MENU_ALWAYS_ENABLED: Int = 0x0804F449

        /** ROM address of `MenuCancelSelect` (thumb). */
        public const val MENU_CANCEL_SELECT: Int = 0x0804F455

        private const val ITEM_DEF_SIZE = 0x24
        private const val TEXT_COLOR_SYSTEM_GRAY = 1
        private const val DPAD_UP = 0x0040
        private const val DPAD_DOWN = 0x0080
        private const val A_BUTTON = 0x0001
        private const val B_BUTTON = 0x0002
        private const val R_BUTTON = 0x0100
        private const val SONG_SE_SYS_CURSOR_UD1 = 0x66
        private const val SONG_68 = 0x68
        private const val SONG_SE_SYS_WINDOW_SELECT1 = 0x6A
        private const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B

        /** `sItemCountYOffsetLookup`. */
        private val ITEM_COUNT_Y_OFFSET = intArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 3)
    }
}
