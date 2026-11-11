package fe8.game.bm

import fe8.game.RomView
import fe8.game.ui.Menu
import gbafe.core.flags.FlagStore
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.TextSystem

/**
 * The map menu's item callbacks of src/bmmenu.c (the items of `gMapMenuItems`, src/menu_def.c:
 * Unit, Status, Guide, Records, Options, Retreat, Suspend, End) as the Prologue shows them. The
 * availability callbacks of Guide, Records, Retreat and Suspend, the Guide item's draw and the End
 * effect are written; the effects of the other items (Unit, Status, Guide, Options, Records,
 * Retreat, Suspend) are not, so selecting one fails loudly in [Menu.callbacks]. [install]
 * registers them under the ROM addresses the `MenuItemDef`s hold.
 */
public class MapMenu(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val playSt: PlaySt,
    private val flags: FlagStore,
    private val hardware: BgHardware,
    private val text: TextSystem,
    private val messages: MsgStrings,
    /** `gProcScr_PlayerPhase`, which [commandEffectEndPlayerPhase] ends. */
    private val playerPhaseScript: () -> ProcScript,
) {
    /** Services outside the ported file. */
    public class Hooks {
        /** `GetBattleMapKind()` (worldmap_path.c, transcribed in [BattleMapKind]). */
        public var battleMapKind: () -> Int = { error("GetBattleMapKind is not wired") }

        /** `IsGuideLocked()` (bmguide.c, not ported): no guide entry is displayed yet. */
        public var isGuideLocked: () -> Boolean = { error("IsGuideLocked is not ported") }

        /**
         * `BmGuideTextShowGreenOrNormal()` (bmguide.c, not ported): false when a displayed guide
         * entry is still unread.
         */
        public var guideTextShowGreenOrNormal: () -> Boolean = {
            error("BmGuideTextShowGreenOrNormal is not ported")
        }
    }

    /** Services outside the ported file. */
    public val hooks: Hooks = Hooks()

    /** Registers the map menu's callbacks in [menu]. */
    public fun install(menu: Menu, rom: RomView) {
        menu.callbacks[GUIDE_USABILITY] =
            Menu.MenuFunc { _, _, def, number -> guideCommandUsability(def, number) }
        menu.callbacks[GUIDE_DRAW] = Menu.MenuFunc { m, item, _, _ ->
            guideCommandDraw(m!!, item!!)
        }
        menu.callbacks[RECORDS_USABILITY] =
            Menu.MenuFunc { _, _, def, number -> recordsCommandUsability(def, number) }
        menu.callbacks[RETREAT_USABILITY] =
            Menu.MenuFunc { _, _, def, number -> retreatCommandUsability(def, number) }
        menu.callbacks[SUSPEND_USABILITY] =
            Menu.MenuFunc { _, _, def, number -> suspendCommandUsability(def, number) }
        menu.callbacks[COMMAND_EFFECT_END_PLAYER_PHASE] =
            Menu.MenuFunc { m, item, _, _ -> commandEffectEndPlayerPhase(m!!, item!!) }
    }

    /**
     * Ported from src/bmmenu.c: MapMenu_IsSuspendCommandAvailable
     *
     * Disabled in the tutorial, else enabled.
     */
    public fun suspendCommandUsability(def: Int, number: Int): Int {
        if (playSt.chapterStateBits and PLAY_FLAG_TUTORIAL != 0) return Menu.MENU_DISABLED

        return Menu.MENU_ENABLED
    }

    /**
     * Ported from src/bmmenu.c: MapMenu_IsGuideCommandAvailable
     *
     * Not shown while the guide is locked ([Hooks.isGuideLocked]), else enabled.
     */
    public fun guideCommandUsability(def: Int, number: Int): Int {
        if (hooks.isGuideLocked()) return Menu.MENU_NOTSHOWN

        return Menu.MENU_ENABLED
    }

    /**
     * Ported from src/bmmenu.c: MapMenu_GuideCommandDraw
     *
     * Draws the item's name in green when the guide has unread entries
     * ([Hooks.guideTextShowGreenOrNormal] false), gray when disabled, onto the front BG.
     */
    public fun guideCommandDraw(menu: Menu.MenuProc, menuItem: Menu.MenuItemProc): Int {
        if (!hooks.guideTextShowGreenOrNormal()) menuItem.text.colorId = TEXT_COLOR_SYSTEM_GREEN

        if (menuItem.availability == Menu.MENU_DISABLED) {
            menuItem.text.colorId = TEXT_COLOR_SYSTEM_GRAY
        }

        text.drawString(menuItem.text, messages.getStringFromIndex(rom.u16(menuItem.def + 4)))

        text.putText(
            menuItem.text, hardware.mapBuffer(menu.frontBg),
            menuItem.yTile * 32 + menuItem.xTile,
        )

        return 0
    }

    /**
     * Ported from src/bmmenu.c: MapMenu_IsRecordsCommandAvailable
     *
     * Only in a dungeon map: shown once the chapter is not one of the ten floors or all seven
     * record flags (0x71..0x77) are set. The dungeon branch is (not verified: the capture is a
     * story map, which returns not shown).
     */
    public fun recordsCommandUsability(def: Int, number: Int): Int {
        if (hooks.battleMapKind() != BattleMapKind.DUNGEON) return Menu.MENU_NOTSHOWN

        val chapterId = (playSt.chapterIndex - 0x24) and 0xFF

        if (chapterId > 9) return Menu.MENU_ENABLED

        for (flag in 0x71..0x77) {
            if (!flags.checkFlag(flag)) return Menu.MENU_NOTSHOWN
        }

        return Menu.MENU_ENABLED
    }

    /**
     * Ported from src/bmmenu.c: MapMenu_IsRetreatCommandAvailable
     *
     * Not shown in a story map, else enabled (the other branch is not verified: the capture is a
     * story map).
     */
    public fun retreatCommandUsability(def: Int, number: Int): Int {
        if (hooks.battleMapKind() == BattleMapKind.STORY) return Menu.MENU_NOTSHOWN

        return Menu.MENU_ENABLED
    }

    /**
     * Ported from src/bmmenu.c: CommandEffectEndPlayerPhase
     *
     * Ends every player phase proc and returns the menu actions: skip the cursor, end the menu,
     * the select sound, clear the menu BGs.
     */
    public fun commandEffectEndPlayerPhase(
        menu: Menu.MenuProc,
        menuItem: Menu.MenuItemProc,
    ): Int {
        engine.endEach(playerPhaseScript())

        return Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
            Menu.MENU_ACT_CLEAR
    }

    /** ROM addresses and constants of the map menu. */
    public companion object {
        /** ROM address of `MapMenu_IsGuideCommandAvailable` (thumb). */
        public const val GUIDE_USABILITY: Int = 0x08022661

        /** ROM address of `MapMenu_GuideCommandDraw` (thumb). */
        public const val GUIDE_DRAW: Int = 0x08022679

        /** ROM address of `MapMenu_IsRecordsCommandAvailable` (thumb). */
        public const val RECORDS_USABILITY: Int = 0x08024C41

        /** ROM address of `MapMenu_IsRetreatCommandAvailable` (thumb). */
        public const val RETREAT_USABILITY: Int = 0x08024CCD

        /** ROM address of `MapMenu_IsSuspendCommandAvailable` (thumb). */
        public const val SUSPEND_USABILITY: Int = 0x080225B1

        /** ROM address of `CommandEffectEndPlayerPhase` (thumb). */
        public const val COMMAND_EFFECT_END_PLAYER_PHASE: Int = 0x080225F9

        private const val PLAY_FLAG_TUTORIAL = 1 shl 3
        private const val TEXT_COLOR_SYSTEM_GRAY = 1
        private const val TEXT_COLOR_SYSTEM_GREEN = 4
    }
}
