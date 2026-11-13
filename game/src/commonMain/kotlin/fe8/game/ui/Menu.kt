package fe8.game.ui

import fe8.game.scene.IntSupplier
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
 * The vertical list menu of the map (decomp `src/uimenu.c`): the unit command menu that opens
 * after a player unit has moved (`gUnitActionMenuDef`), the weapon-choice list of the attack
 * command (`gWeaponSelectMenuDef`) and the map menu that opens when A is pressed on an empty
 * tile or a unit that already acted (`gMapMenuDef`, `playerphase.c`). The player sees a framed
 * window with one line of text per entry, a highlight bar under the current entry and a pointing
 * hand ([UiHand]) at its left; up and down move, A selects, B cancels.
 *
 * `sProc_Menu` is the menu's proc, with one `sProc_MenuItem` child per shown entry. The frame and
 * the highlight bar go to a back BG, the text to a front BG (by default BG 1 and BG 0).
 *
 * A menu is described by a `MenuDef` in the ROM (ADR 0015: the ROM is the vanilla data source),
 * which [MenuProc.def] and [MenuItemProc.def] hold as GBA addresses. The def's callbacks and the
 * item defs' callbacks (availability, draw, select, idle, switch in and out) are called through
 * [callbacks], keyed by the ROM address of the original function. Registered by default are the
 * `uimenu.c` ones: `MenuAlwaysEnabled` and `MenuCancelSelect`. The others are stand-ins until
 * their port: the unit menu's are registered by `fe8.game.bm.UnitActionMenu.install` (PP-10), and
 * the remaining ones of the unit and map menus are no-op or refusal stand-ins registered by
 * `PlayerPhaseWiring`. An unregistered address fails loudly instead of guessing. The help-box
 * procs of `uimenu.c` (`sProc_MenuAutoHelpBox`, `sProc_MenuFrozenHelpBox`, `sProc_MenuFrozen`),
 * the menu overrides (`AddMenuOverride` and friends) and `EndAllMenus` are not ported here.
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
        /** `LockGame` (src/bm.c): `gBmSt.lock++`, which freezes the map's own processing. */
        public var lockGame: () -> Unit = {}

        /** `UnlockGame` (src/bm.c): undoes one `LockGame`. */
        public var unlockGame: () -> Unit = {}

        /** `PlaySoundEffect`: plays the sound effect with the given song id. */
        public var playSoundEffect: (Int) -> Unit = {}

        /** `GetGameClock`: the frame counter, used for the hand's animation. */
        public var gameClock: IntSupplier = IntSupplier { 0 }

        /** `EndFaceById(slot)`: removes the portrait of that slot (`MENU_ACT_ENDFACE`: 0). */
        public var endFaceById: (Int) -> Unit = {}

        /**
         * `DisplayFrozenUiHand(x, y)` (src/uiutils.c): draws the hand at (x, y) without the
         * bobbing, on sprite layer 3, for a frozen menu. Stand-in for PP-9 follow-up (a capture
         * that runs a frozen menu).
         */
        public var displayFrozenUiHand: (Int, Int) -> Unit = { _, _ -> }
    }

    /**
     * One callback of a `MenuDef` or `MenuItemDef`, called by ROM address ([callbacks]): an
     * `isAvailable`, `onDraw`, `onSelected`, `onIdle`, `onSwitchIn`/`onSwitchOut`, `onInit`,
     * `onEnd`, `onBPress` or `onRPress` function of `include/uimenu.h`.
     */
    public fun interface MenuFunc {
        /**
         * Runs the callback. [menu] and [item] are null where the original takes no such
         * argument, [itemDef] is the `MenuItemDef` address for an availability callback, and
         * [number] is its item index. The result is the callback's C return value: a `MENU_*`
         * availability for `isAvailable`, `MENU_ACT_*` bits for the select, idle and B-press
         * callbacks, otherwise ignored.
         */
        public fun invoke(menu: MenuProc?, item: MenuItemProc?, itemDef: Int, number: Int): Int
    }

    /**
     * `struct MenuRect`: the menu window's top-left tile and size in tiles (frame included),
     * each an `s8`. A negative height makes the menu invisible (`MENU_STATE_NOTSHOWN`).
     */
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

    /** `struct MenuProc` past `PROC_HEADER` (+0x2C): the state of one open menu. */
    public class MenuProc {
        /** `rect` (+0x2C): the window's tile rectangle. */
        public val rect: MenuRect = MenuRect()

        /** `def` (+0x30): ROM address of the `MenuDef`. */
        public var def: Int = 0

        /**
         * `menuItems` (+0x34): the shown entries in order, [itemCount] of them set (entries whose
         * availability is `MENU_NOTSHOWN` are left out, so an index is not the def's item number).
         */
        public val menuItems: Array<MenuItemProc?> = arrayOfNulls(MENU_ITEM_MAX)

        /** `itemCount` (+0x60), u8: number of shown entries. */
        public var itemCount: Int = 0

        /** `itemCurrent` (+0x61), u8: index of the entry under the hand. */
        public var itemCurrent: Int = 0

        /**
         * `itemPrevious` (+0x62), u8: the entry before the last up or down press (0xFF, that is
         * -1, at the start), to move the highlight bar and call the switch callbacks.
         */
        public var itemPrevious: Int = 0

        /** `state` (+0x63), u8 of `MENU_STATE_*` bits. */
        public var state: Int = 0

        /** `backBg` (+0x64 bits 0..1): the BG that holds the frame and the highlight bar. */
        public var backBg: Int = 0

        /** `frontBg` (+0x64 bits 2..3): the BG that holds the entries' text. */
        public var frontBg: Int = 0

        /**
         * `tileref` (+0x66), u16: added to every frame and highlight tile entry (a tile and
         * palette offset for the window frame on the back BG; 0 for the default frame).
         */
        public var tileref: Int = 0

        /** `unk68` (+0x68), u16: the `unk` argument of `StartMenuCore`; no code reads it. */
        public var unk68: Int = 0

        /** The menu's proc (Kotlin side: in the decomp the `MenuProc` is the proc itself). */
        public lateinit var proc: Proc
    }

    /** `struct MenuItemProc` past `PROC_HEADER` (+0x2A): one shown entry of a menu. */
    public class MenuItemProc {
        /** `xTile` (+0x2A), s16: left tile column of the entry's text. */
        public var xTile: Int = 0

        /** `yTile` (+0x2C), s16: top tile row of the entry; entries are 2 rows apart. */
        public var yTile: Int = 0

        /** `def` (+0x30): ROM address of the `MenuItemDef`. */
        public var def: Int = 0

        /** `text` (+0x34): the entry's text object, `rect.w - 1` tiles wide. */
        public val text: Text = Text()

        /** `itemNumber` (+0x3C), s8: index of the entry in the def's item list. */
        public var itemNumber: Int = 0

        /** `availability` (+0x3D), u8: `MENU_ENABLED`, `MENU_DISABLED` or `MENU_NOTSHOWN`. */
        public var availability: Int = 0

        /** The entry's proc (Kotlin side: in the decomp the `MenuItemProc` is the proc itself). */
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

    /**
     * `sProc_MenuMain` (0x085B64B8): runs [menuOnIdle] every frame; when it ends, stops the
     * green text colour proc (`PROC_CALL(EndGreenText)`).
     */
    public val menuMainScript: ProcScript = ProcScript(
        "sProc_MenuMain",
        listOf(repeat(onIdleFn), call(endGreenTextFn), end()),
    )

    /**
     * `sProc_Menu` (0x085B64D0): waits a frame and for any camera move to finish, starts the
     * green text colour proc (`PROC_CALL(StartGreenText)`), draws the menu, runs the init
     * callbacks, then jumps to [menuMainScript].
     */
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

    /** `sProc_MenuItem` (0x085B6510): only `PROC_BLOCK`; it exists to hold an entry's fields. */
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

    /** Sign-extends the low byte, for the `s8` fields of `MenuRect`. */
    private fun s8(value: Int): Int = value.toByte().toInt()

    /** Calls the ROM function at [address] through [callbacks]; fails if none is registered. */
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
     * Starts a menu from [def] at [rect] and returns its proc, as a child of [parent] that blocks
     * it (or a root proc of tree 3 that locks the game when [parent] is null), with the frame and
     * highlight bar on BG 1 and the text on BG 0 (`StartMenuCore` with `backBg` 1, `tileref` 0,
     * `frontBg` 0). The decomp's `StartMenu` is the same with `def`'s own rect.
     *
     * Ported from src/uimenu.c: StartMenuAt
     */
    public fun startMenuAt(def: Int, rect: MenuRect, parent: Proc?): Proc =
        startMenuCore(def, rect, 1, 0, 0, 0, parent)

    /**
     * Starts [def] as an orphan menu (no parent, so it locks the game) at its own rect. The
     * weapon-choice list is started this way.
     *
     * Ported from src/uimenu.c: StartOrphanMenu
     */
    public fun startOrphanMenu(def: Int): Proc = startMenuAt(def, defRect(def), null)

    /**
     * Starts [def] as an orphan menu (no parent), its rect's x set to [xTileRight] when
     * [xSubject] (the cursor's screen x in pixels) is left of pixel 120, half of the 240-pixel
     * screen, else to [xTileLeft]: the menu opens on the side away from the cursor. The map menu
     * is started this way with tile columns 1 and 0x17.
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
     * `sItemCountYOffsetLookup[itemCount]` tiles (1 to 3), so a long list stays on screen. The
     * unit command menu is started this way with columns 1 and 22.
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

    /** Reads the `MenuRect` at the start of the `MenuDef` at [def] (four `s8`). */
    private fun defRect(def: Int): MenuRect =
        MenuRect(s8(rom.u8(def)), s8(rom.u8(def + 1)), s8(rom.u8(def + 2)), s8(rom.u8(def + 3)))

    /**
     * Starts the menu proc and one item proc per shown item of [def]'s item list, which ends at
     * the first item def whose `isAvailable` is 0 (`MenuItemsEnd`). An item whose availability is
     * `MENU_NOTSHOWN` gets no proc, so it takes no line. Shown items go on tile rows
     * `rect.y + 1 + 2 * index`; the window is made tall enough for them. A negative `rect.h`
     * makes an invisible menu that has no text objects. Plays `SONG_68` (a song id the decomp
     * leaves unnamed) and clears the pending keys; nothing is drawn until the proc's first
     * run ([redrawMenu]).
     * [backBg] and [frontBg] (masked to 2 bits) are the BGs for the frame and the text, [tileref]
     * is added to the frame tiles, [unk] is unknown (stored, never read).
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
     * Ends [menu]: switches out the current item, calls the def's end callback, unlocks the game
     * if the menu locked it, ends the proc and resets the BG scroll (undoing
     * [applyMenuCursorVScroll]). It leaves the tiles on the BGs; a select callback returning
     * `MENU_ACT_CLEAR` wipes them. Returns the menu proc's parent.
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
     * Flags the menu's two BGs for upload to the display.
     *
     * Transcribed from src/uimenu.c: SyncMenuBgs (not verified: the sync flags are consumed in
     * VBlank before the RAM dumps)
     */
    private fun syncMenuBgs(menu: MenuProc) {
        hardware.enableBgSyncByMask((1 shl menu.backBg) + (1 shl menu.frontBg))
    }

    /**
     * Blanks the menu's two BG tilemaps (the whole BGs, not only the window) and flags them for
     * upload; this is what `MENU_ACT_CLEAR` does when a menu is dismissed.
     *
     * Ported from src/uimenu.c: ClearMenuBgs
     */
    private fun clearMenuBgs(menu: MenuProc) {
        hardware.fill(hardware.mapBuffer(menu.frontBg), 0)
        hardware.fill(hardware.mapBuffer(menu.backBg), 0)

        syncMenuBgs(menu)
    }

    /**
     * True when the last d-pad input moved to a different entry (`itemCurrent != itemPrevious`).
     *
     * Transcribed from src/uimenu.c: HasMenuChangedItem (not verified: only its callers'
     * switch in and out branches use it, and no item of the captured menus has such callbacks)
     */
    private fun hasMenuChangedItem(menu: MenuProc): Boolean = menu.itemCurrent != menu.itemPrevious

    /**
     * The first step after the menu is drawn: calls the def's `onInit` callback and the first
     * entry's switch-in callback (for the unit menu's attack entry that is
     * `DisplayUnitStandingAttackRange`, which shows the unit's attack range on the map).
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
     * Draws the whole menu: the window frame (style from `MenuDef.style`) on the back BG, a blank
     * rectangle on the front BG, each entry's text (or its draw callback) and the highlight bar
     * under the current entry. An entry's text colour is its def's `color` unless that is 0, and
     * the grey `TEXT_COLOR_SYSTEM_GRAY` when the entry is disabled (shown but not selectable).
     * The text is the def's message id if nonzero, else its literal string. Does nothing for an
     * invisible menu.
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

    /** The zero-terminated string at ROM address [at], terminator included, as a new array. */
    private fun stringAt(at: Int): ByteArray {
        var end = at
        while (rom.u8(end) != 0) end++
        return ByteArray(end - at + 1) { rom.u8(at + it).toByte() }
    }

    /**
     * Draws ([hover] true) or clears the highlight bar under entry [item] (an index into
     * [MenuProc.menuItems]) on the back BG: two tiles narrower than the window, one row below the
     * entry's row. Does nothing for a flat menu (`MENU_STATE_FLAT`), which has no highlight.
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
     * One frame of an open menu (the body of `sProc_MenuMain`). A frozen menu only shows its
     * hand without moving; a doomed one ends. Otherwise it reads the d-pad and the buttons, does
     * what the callbacks asked for in `MENU_ACT_*` bits (end the menu, play the select or cancel
     * sound, blank the BGs, remove the portrait in slot 0, or doom the menu so it ends next
     * frame) and, unless the callbacks or `MENU_STATE_NOCURSOR` hide it, draws the hand.
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
     * Moves the current entry with the repeated up and down keys, wrapping from the first to the
     * last (or back) only on a fresh press, not while a key is held. Moves the highlight bar and
     * plays the cursor sound (`SONG_SE_SYS_CURSOR_UD1`), and calls the old entry's switch-out and
     * the new one's switch-in callbacks.
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
     * Runs the current entry's idle callback, then on a new A press its select callback (unless
     * a menu override takes it), on a new B press the def's `onBPress` or on R the def's
     * `onRPress`. Returns the `MENU_ACT_*` bits of the last of the idle, select and B callbacks
     * that ran, 0 when none did; the R callback's result is discarded, as in the decomp.
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
     * Stores in [out] (x, y) the pixel position where the hand points: the current entry's tile
     * position times 8, with x moved 4 pixels left when the def's frame style is not 0.
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
     * For a menu of more than 9 entries, which does not fit on the screen, scrolls the menu's two
     * BGs up by `(itemCount * 16 - 9 * 16) * itemCurrent / 9` pixels, so the lower entries come
     * into view as the hand moves down, and lowers the hand's y in [out] by the same amount. A
     * menu has at most `MENU_ITEM_MAX` (11) entries.
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
     * Availability that a menu override forces on an item (`AddMenuOverride` with
     * `MENU_OVERRIDE_ISAVAILABLE`, matched on the item def's `overrideId`), or 0 for no override.
     *
     * Transcribed from src/uimenu.c: OverriddenMenuAvailability (not verified: the override
     * table is empty in the captures and AddMenuOverride is not ported)
     */
    private fun overriddenMenuAvailability(): Int = 0

    /**
     * The select result of an overridden item (`MENU_OVERRIDE_ONSELECT`), or `0xFF` when no
     * override replaces the item's own select callback.
     *
     * Transcribed from src/uimenu.c: OverriddenMenuSelected (not verified: see
     * [overriddenMenuAvailability])
     */
    private fun overriddenMenuSelected(): Int = 0xFF

    /**
     * Availability callback for an item that is always usable: `MENU_ENABLED`. [itemDef] and
     * [number] are unused.
     *
     * Ported from src/uimenu.c: MenuAlwaysEnabled
     */
    public fun menuAlwaysEnabled(itemDef: Int, number: Int): Int = MENU_ENABLED

    /**
     * Select and B-press callback that cancels: returns the actions to skip the hand this
     * frame, blank the BGs, end the menu and play the cancel sound (`SONG_SE_SYS_WINDOW_CANSEL1`).
     * The unit and map menu defs use it as their B-press callback.
     *
     * Ported from src/uimenu.c: MenuCancelSelect
     */
    public fun menuCancelSelect(menu: MenuProc, item: MenuItemProc): Int =
        MENU_ACT_SKIPCURSOR or MENU_ACT_CLEAR or MENU_ACT_END or MENU_ACT_SND6B

    /** Menu constants (`include/uimenu.h`) and ROM addresses. */
    public companion object {
        /** `MENU_ITEM_MAX`: most entries a menu can have. */
        public const val MENU_ITEM_MAX: Int = 11

        /** `MENU_ENABLED`: item shown and selectable. */
        public const val MENU_ENABLED: Int = 1

        /** `MENU_DISABLED`: item shown in grey; the menu code itself still passes A through. */
        public const val MENU_DISABLED: Int = 2

        /** `MENU_NOTSHOWN`: item left out of the menu altogether. */
        public const val MENU_NOTSHOWN: Int = 3

        /** `MENU_STATE_GAMELOCKING`: the menu called `LockGame` and unlocks when it ends. */
        public const val MENU_STATE_GAMELOCKING: Int = 1 shl 0

        /** `MENU_STATE_ENDING`: set by [endMenu]; nothing in `uimenu.c` reads it. */
        public const val MENU_STATE_ENDING: Int = 1 shl 2

        /** `MENU_STATE_NOTSHOWN`: invisible menu (`rect.h` < 0): not drawn, no text objects. */
        public const val MENU_STATE_NOTSHOWN: Int = 1 shl 3

        /** `MENU_STATE_FLAT`: no highlight bar under the current entry. */
        public const val MENU_STATE_FLAT: Int = 1 shl 4

        /** `MENU_STATE_NOCURSOR`: the hand is not drawn. */
        public const val MENU_STATE_NOCURSOR: Int = 1 shl 5

        /** `MENU_STATE_FROZEN`: input ignored, the hand still drawn (`FreezeMenu`). */
        public const val MENU_STATE_FROZEN: Int = 1 shl 6

        /** `MENU_STATE_DOOMED`: the menu ends on its next idle frame. */
        public const val MENU_STATE_DOOMED: Int = 1 shl 7

        /** `MENU_ACT_SKIPCURSOR`: do not draw the hand this frame. */
        public const val MENU_ACT_SKIPCURSOR: Int = 1 shl 0

        /** `MENU_ACT_END`: end the menu now. */
        public const val MENU_ACT_END: Int = 1 shl 1

        /** `MENU_ACT_SND6A`: play `SONG_SE_SYS_WINDOW_SELECT1` (0x6A). */
        public const val MENU_ACT_SND6A: Int = 1 shl 2

        /** `MENU_ACT_SND6B`: play `SONG_SE_SYS_WINDOW_CANSEL1` (0x6B). */
        public const val MENU_ACT_SND6B: Int = 1 shl 3

        /** `MENU_ACT_CLEAR`: blank the menu's two BG tilemaps. */
        public const val MENU_ACT_CLEAR: Int = 1 shl 4

        /** `MENU_ACT_ENDFACE`: remove the portrait in slot 0. */
        public const val MENU_ACT_ENDFACE: Int = 1 shl 5

        /** `MENU_ACT_DOOM`: set `MENU_STATE_DOOMED`, ending the menu next frame. */
        public const val MENU_ACT_DOOM: Int = 1 shl 7

        /**
         * ROM address of `gUnitActionMenuDef` (src/menu_def.c): rect (1, 3, 7, 0), style 0, B
         * press `MenuCancelSelect`; the command menu of a player unit that has moved.
         */
        public const val UNIT_ACTION_MENU_DEF: Int = 0x0859D1F0

        /**
         * ROM address of `gWeaponSelectMenuDef` (src/menu_def.c): rect (1, 1, 14, 0), the
         * weapon-choice list of the attack command, with a help box (`ConvoyMenu_HelpBox`).
         */
        public const val WEAPON_SELECT_MENU_DEF: Int = 0x0859D1CC

        /**
         * ROM address of `gMapMenuDef` (src/menu_def.c): rect (1, 2, 7, 0), style 0, B press
         * `MenuCancelSelect`; the map menu.
         */
        public const val MAP_MENU_DEF: Int = 0x0859D214

        /** ROM address of `MenuAlwaysEnabled` (thumb: the address has bit 0 set). */
        public const val MENU_ALWAYS_ENABLED: Int = 0x0804F449

        /** ROM address of `MenuCancelSelect` (thumb: the address has bit 0 set). */
        public const val MENU_CANCEL_SELECT: Int = 0x0804F455

        /** `sizeof(struct MenuItemDef)`: last field `onSwitchOut` at +0x20, 4 bytes. */
        private const val ITEM_DEF_SIZE = 0x24

        /** `TEXT_COLOR_SYSTEM_GRAY` (= `TEXT_COLOR_0456`): the text colour of disabled items. */
        private const val TEXT_COLOR_SYSTEM_GRAY = 1

        // Key bits of include/gba/io_reg.h, as in `gKeyStatusPtr`.
        private const val DPAD_UP = 0x0040
        private const val DPAD_DOWN = 0x0080
        private const val A_BUTTON = 0x0001
        private const val B_BUTTON = 0x0002
        private const val R_BUTTON = 0x0100

        // Sound ids of include/constants/songs.h: cursor move, the unnamed `SONG_68` played when a
        // menu starts, select, cancel.
        private const val SONG_SE_SYS_CURSOR_UD1 = 0x66
        private const val SONG_68 = 0x68
        private const val SONG_SE_SYS_WINDOW_SELECT1 = 0x6A
        private const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B

        /**
         * `sItemCountYOffsetLookup`: tiles to move a menu up by, indexed by its entry count; 0 up
         * to 6 entries, then 1, 2, 3, 3, 3 for 7 to 11.
         */
        private val ITEM_COUNT_Y_OFFSET = intArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 3)
    }
}
