package fe8.game.savemenu

/**
 * The option bitfields of the save menu (`AddMainMenuOption`, `AddExtraMenuOption`,
 * `InitSaveMenuChoice`, `src/savedraw.c`) and `SaveMenuGetBitfile` (`src/savemenu.c`).
 *
 * @param hooks the `IsExtra*Enabled` queries of `src/bmsave-lib.c`
 */
public class SaveMenuChoice(
    /** The Extras availability queries. */
    public val hooks: Hooks = Hooks(),
) {
    /**
     * `IsExtra*Enabled` queries from `src/bmsave-lib.c`. Stand-ins (not ported): each answers
     * false until its owning task is ported.
     */
    public class Hooks(
        /** `IsExtraLinkArenaEnabled(0)`. */
        public var linkArena: () -> Boolean = { false },
        /** `IsExtraSoundRoomEnabled()` (the original always answers 1). */
        public var soundRoom: () -> Boolean = { true },
        /** `IsExtraSupportViewerEnabled()`. */
        public var supportViewer: () -> Boolean = { false },
        /** `IsExtraFreeMapEnabled()`. */
        public var freeMap: () -> Boolean = { false },
        /** `IsExtraBonusClaimEnabled()`. */
        public var bonusClaim: () -> Boolean = { false },
    )

    /**
     * Sets [option] in `main_options` and counts it in `unk_31`.
     *
     * Ported from src/savedraw.c: AddMainMenuOption
     */
    public fun addMainMenuOption(state: SaveMenuState, option: Int) {
        state.mainOptions = (state.mainOptions or option) and 0xFF
        state.unk31 = (state.unk31 + 1) and 0xFF
    }

    /**
     * Sets [option] in `extra_options` and counts it in `max_choice`.
     *
     * Ported from src/savedraw.c: AddExtraMenuOption
     */
    public fun addExtraMenuOption(state: SaveMenuState, option: Int) {
        state.extraOptions = (state.extraOptions or option) and 0xFF
        state.maxChoice = (state.maxChoice + 1) and 0xFF
    }

    /**
     * Rebuilds `main_options` / `unk_31` from the slot metadata (`unk_44`, `chapter_idx`) and
     * `extra_options` / `max_choice` from the [hooks] queries; a non-empty Extras list adds
     * [PostSaveMenu.MAIN_MENU_OPTION_EXTRAS] to the main options.
     *
     * Ported from src/savedraw.c: InitSaveMenuChoice. Checked at f9844 with three used slots,
     * no `RESUME` and two Extras; the `count` 1..2 / 0 and `RESUME` branches are not observed and
     * the `IsExtra*Enabled` queries are [hooks] stand-ins.
     */
    public fun initSaveMenuChoice(state: SaveMenuState) {
        var count = 0
        state.unk31 = 0
        state.mainOptions = 0

        if (state.slots.unk44 == 0x100) {
            addMainMenuOption(state, PostSaveMenu.MAIN_MENU_OPTION_RESUME)
        }

        for (i in 0 until 3) {
            if (state.slots.chapterIdx[i] != 0xFF) count++
        }

        if (count > 0) {
            addMainMenuOption(state, PostSaveMenu.MAIN_MENU_OPTION_RESTART)
            if (count < 3) addMainMenuOption(state, MAIN_MENU_OPTION_COPY)
            addMainMenuOption(state, MAIN_MENU_OPTION_ERASE)
        }

        if (count < 3) addMainMenuOption(state, PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME)

        state.extraOptions = 0
        state.maxChoice = 0

        if (hooks.linkArena()) addExtraMenuOption(state, PostSaveMenu.EXTRA_MENU_OPTION_LINK_ARENA)
        if (hooks.soundRoom()) addExtraMenuOption(state, EXTRA_MENU_OPTION_SOUND_ROOM)
        if (hooks.supportViewer()) addExtraMenuOption(state, EXTRA_MENU_OPTION_SUPPORT)
        if (hooks.freeMap()) addExtraMenuOption(state, PostSaveMenu.EXTRA_MENU_OPTION_MAP)
        if (hooks.bonusClaim()) addExtraMenuOption(state, EXTRA_MENU_OPTION_BONUS_CLAIM)

        if (state.extraOptions != 0) {
            state.mainOptions = (state.mainOptions or PostSaveMenu.MAIN_MENU_OPTION_EXTRAS) and 0xFF
            state.unk31 = (state.unk31 + 1) and 0xFF
        }
    }

    /** Bit values and helpers shared with [SaveMenuSlotFlip]. */
    public companion object {
        /** `MAIN_MENU_OPTION_COPY` (1 << 2). */
        public const val MAIN_MENU_OPTION_COPY: Int = 1 shl 2

        /** `MAIN_MENU_OPTION_ERASE` (1 << 3). */
        public const val MAIN_MENU_OPTION_ERASE: Int = 1 shl 3

        /** `EXTRA_MENU_OPTION_SOUND_ROOM` (1 << 1). */
        public const val EXTRA_MENU_OPTION_SOUND_ROOM: Int = 1 shl 1

        /** `EXTRA_MENU_OPTION_SUPPORT` (1 << 2). */
        public const val EXTRA_MENU_OPTION_SUPPORT: Int = 1 shl 2

        /** `EXTRA_MENU_OPTION_BONUS_CLAIM` (1 << 5). */
        public const val EXTRA_MENU_OPTION_BONUS_CLAIM: Int = 1 shl 5

        /**
         * The [number]th set bit of the low 8 bits of [bitfile] as a mask, or `0xFF` (the
         * original returns `-1` as `u8`).
         *
         * Partially ported from src/savemenu.c: SaveMenuGetBitfile (verified: items 0 and 1 of
         * options `0x2B` against the title runs; not verified: the `-1` result when [number] is
         * past the last set bit, which no run reaches)
         */
        public fun saveMenuGetBitfile(bitfile: Int, number: Int): Int {
            var count = 0
            for (i in 0 until 8) {
                if ((bitfile shr i) and 1 != 0) {
                    if (number == count) return (1 shl i) and 0xFF
                    count++
                }
            }
            return 0xFF
        }
    }
}
