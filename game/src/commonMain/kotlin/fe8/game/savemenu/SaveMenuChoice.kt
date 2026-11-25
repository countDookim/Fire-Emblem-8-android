package fe8.game.savemenu

import fe8.game.save.SaveExtras

/**
 * The option bitfields of the save menu (`AddMainMenuOption`, `AddExtraMenuOption`,
 * `InitSaveMenuChoice`, `src/savedraw.c`) and `SaveMenuGetBitfile` (`src/savemenu.c`).
 *
 * @param hooks overrides of the Extras queries (mods, tests); an unset one falls back to [extras]
 * @param extras the `IsExtra*Enabled` queries of `src/bmsave-lib.c` on the save RAM; null keeps the
 *   fixed answers of [Hooks]' defaults
 */
public class SaveMenuChoice(
    /** Overrides of the Extras availability queries. */
    public val hooks: Hooks = Hooks(),
    /** The save-RAM backed queries. */
    private val extras: SaveExtras? = null,
) {
    /**
     * Overrides of the `IsExtra*Enabled` queries of `src/bmsave-lib.c`. A null entry uses
     * [SaveExtras] when given; without it the fixed stand-in answers apply (link arena, support
     * viewer, free map and bonus claim false, the sound room true).
     */
    public class Hooks(
        /** `IsExtraLinkArenaEnabled(0)`. */
        public var linkArena: (() -> Boolean)? = null,
        /** `IsExtraSoundRoomEnabled()` (the original always answers 1). */
        public var soundRoom: (() -> Boolean)? = null,
        /** `IsExtraSupportViewerEnabled()`. */
        public var supportViewer: (() -> Boolean)? = null,
        /** `IsExtraFreeMapEnabled()`. */
        public var freeMap: (() -> Boolean)? = null,
        /** `IsExtraBonusClaimEnabled()`. */
        public var bonusClaim: (() -> Boolean)? = null,
    )

    private fun linkArenaEnabled() =
        hooks.linkArena?.invoke() ?: extras?.isExtraLinkArenaEnabled(0) ?: false

    private fun soundRoomEnabled() =
        hooks.soundRoom?.invoke() ?: extras?.isExtraSoundRoomEnabled() ?: true

    private fun supportViewerEnabled() =
        hooks.supportViewer?.invoke() ?: extras?.isExtraSupportViewerEnabled() ?: false

    private fun freeMapEnabled() =
        hooks.freeMap?.invoke() ?: extras?.isExtraFreeMapEnabled() ?: false

    private fun bonusClaimEnabled() =
        hooks.bonusClaim?.invoke() ?: extras?.isExtraBonusClaimEnabled() ?: false

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
     * the `IsExtra*Enabled` queries come from [extras], checked in `TitleSaveMenuExtrasDifferentialTest`.
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

        if (linkArenaEnabled()) addExtraMenuOption(state, PostSaveMenu.EXTRA_MENU_OPTION_LINK_ARENA)
        if (soundRoomEnabled()) addExtraMenuOption(state, EXTRA_MENU_OPTION_SOUND_ROOM)
        if (supportViewerEnabled()) addExtraMenuOption(state, EXTRA_MENU_OPTION_SUPPORT)
        if (freeMapEnabled()) addExtraMenuOption(state, PostSaveMenu.EXTRA_MENU_OPTION_MAP)
        if (bonusClaimEnabled()) addExtraMenuOption(state, EXTRA_MENU_OPTION_BONUS_CLAIM)

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
