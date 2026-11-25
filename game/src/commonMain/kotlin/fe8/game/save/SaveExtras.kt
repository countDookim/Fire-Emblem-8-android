package fe8.game.save

import fe8.game.save.Fe8SramLayout as L

/**
 * The `IsExtra*Enabled` queries of `src/bmsave-lib.c` (`PC-11b`) that decide which entries the
 * title save menu's Extras list has, read from the save RAM of [saveLib].
 */
public class SaveExtras(
    private val saveLib: BmSaveLib,
    /** Callbacks for parts owned by other tasks. */
    public val hooks: Hooks = Hooks(),
) {
    /** Parts owned by other tasks. */
    public class Hooks(
        /**
         * `IsMultiArenaSaveReady()` (`src/bmsave-multiarena.c`), the Link Arena fallback when no
         * game save is past the first chapter; a stand-in (false) until the Link Arena save is
         * ported, which no title run reaches.
         */
        public var multiArenaSaveReady: () -> Boolean = { false },
    )

    private val bonus = ByteArray(L.BONUS_SIZE)

    /**
     * Whether the Link Arena is offered: SRAM works and a game save slot is past the first
     * chapter, else [Hooks.multiArenaSaveReady].
     *
     * Partially ported from src/bmsave-lib.c: IsExtraLinkArenaEnabled (verified: the true result
     * through a slot past chapter 0 in both title runs; not verified: the SRAM-not-working arm
     * and the multi-arena fallback)
     */
    public fun isExtraLinkArenaEnabled(index: Int): Boolean {
        if (!saveLib.isSramWorking()) return false
        for (i in 0 until SLOT_COUNT) {
            if (saveLib.isGameSaveNotFirstChapter(i)) return true
        }
        return hooks.multiArenaSaveReady()
    }

    /**
     * Always true (the original returns 1).
     *
     * Ported from src/bmsave-lib.c: IsExtraSoundRoomEnabled
     */
    public fun isExtraSoundRoomEnabled(): Boolean = true

    /**
     * Whether the Support Viewer is offered: the global info knows a character and the game was
     * played through (both queries run, as in the original).
     *
     * Partially ported from src/bmsave-lib.c: IsExtraSupportViewerEnabled (verified: false in
     * both title runs; not verified: the true result)
     */
    public fun isExtraSupportViewerEnabled(): Boolean {
        val known = saveLib.ggmIsAnyCharacterKnown()
        val playedThrough = saveLib.isGamePlayedThrough()
        return playedThrough and known
    }

    /**
     * Whether the free map is offered: SRAM works and some game save slot is complete.
     *
     * Partially ported from src/bmsave-lib.c: IsExtraFreeMapEnabled (verified: false in both
     * title runs; not verified: the true result and the SRAM-not-working arm)
     */
    public fun isExtraFreeMapEnabled(): Boolean {
        if (!saveLib.isSramWorking()) return false
        for (i in 0 until SLOT_COUNT) {
            if (saveLib.isGameSaveComplete(i)) return true
        }
        return false
    }

    /**
     * Whether the bonus claim is offered: the bonus block is valid and an unseen entry of kind
     * `BONUSKIND_ITEM0` or `BONUSKIND_MONEY` exists. (The original reads the block through
     * `gGenericBuffer`; here a private buffer.)
     *
     * Partially ported from src/bmsave-lib.c: IsExtraBonusClaimEnabled (verified: false for the
     * all-zero bonus block of both title runs; not verified: an unseen entry, and an invalid
     * block)
     */
    public fun isExtraBonusClaimEnabled(): Boolean {
        if (!saveLib.loadBonusContentData(bonus)) return false
        var found = false
        for (i in 0 until BONUS_ENTRIES) {
            val base = i * BONUS_ENTRY_SIZE
            if (bonus[base].toInt() == 0) continue
            val kind = bonus[base + 1].toInt()
            if (kind == BONUSKIND_ITEM0 || kind == BONUSKIND_MONEY) found = true
        }
        return found
    }

    private companion object {
        const val SLOT_COUNT = 3
        const val BONUS_ENTRIES = 0x10
        const val BONUS_ENTRY_SIZE = 0x14
        const val BONUSKIND_ITEM0 = 0
        const val BONUSKIND_MONEY = 2
    }
}
