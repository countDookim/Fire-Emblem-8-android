package fe8.game.ui

import fe8.game.bm.BMapMain.Companion.FACTION_BLUE
import fe8.game.bm.BMapMain.Companion.FACTION_RED
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.yield

/**
 * Hosts `ProcScr_GotItem` (decomp `src/popup.c`) on a [ProcEngine]: the got-item popup (or the
 * pilfered popup for a unit that is not a player unit), followed by the actual hand-over of the
 * item to the unit, which opens the inventory-full menu when the unit cannot carry it. Used when
 * a unit is given an item, for example from a chest, a village or an event. The proc's user data
 * ([Proc.state]) is a [GotItemPopupProc].
 */
public class GotItemPopup(
    /** The proc engine the proc runs on. */
    public val engine: ProcEngine,
    /** What the proc reads and writes outside itself. */
    public val deps: Dependencies,
) {
    /** The external state and services used by the proc. */
    public class Dependencies(
        /**
         * `NewPopup_Simple(definition, clock, winStyle, parent)`; the usual implementation is
         * `PopupScript.start(parent, definition, clock, winStyle)`.
         */
        public val newPopupSimple:
            (parent: Proc, definition: Int, clock: Int, winStyle: Int) -> Unit,
        /** `SetPopupItem(item)`. */
        public val setPopupItem: (item: Int) -> Unit,
        /** `UNIT_FACTION(unit)`: 0x00 player, 0x40 ally NPC, 0x80 enemy (`FACTION_*`). */
        public val unitFaction: (unit: Int) -> Int,
        /**
         * `unit->state |= US_DROP_ITEM`: marks the unit so that its last item drops when it is
         * defeated (the same bit `LoadUnit` sets for units with an `itemDrop` entry).
         */
        public val setUnitDropItem: (unit: Int) -> Unit,
        /**
         * `MakeNewItem(item)`: the item word for a fresh item, its maximum uses in the high byte
         * (0 for unbreakable items) and the item index in the low byte.
         */
        public val makeNewItem: (item: Int) -> Int,
        /**
         * `HandleNewItemGetFromDrop(unit, item, proc)` (src/convoymenu.c): adds the item to the
         * unit; when the inventory is full it starts the menu to send an item to the supply or
         * discard one, blocking [proc].
         */
        public val handleNewItemGetFromDrop: (unit: Int, item: Int, proc: Proc) -> Unit,
    )

    /** `struct GotItemPopupProc` user data (`unit` at +0x54, `item` at +0x58 in the decomp). */
    public class GotItemPopupProc(
        /** The unit receiving the item (a `struct Unit` in the decomp). */
        public var unit: Int = 0,
        /** The item id (index only; `MakeNewItem` adds the uses). */
        public var item: Int = 0,
    )

    /** The [GotItemPopupProc] user data of [proc]. */
    private fun stateOf(proc: Proc): GotItemPopupProc = proc.state as GotItemPopupProc

    /**
     * `ProcScr_GotItem`: yield, show the popup, yield, hand over the item, yield, end.
     *
     * Transcribed from src/popup.c: ProcScr_GotItem (not verified: no @VerifiesPort test names it)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GotItem",
        listOf(
            yield(),
            call { displayLePopup(it) },
            yield(),
            call { gotLeItem(it) },
            yield(),
            end(),
        ),
        newState = { GotItemPopupProc() },
    )

    /**
     * Sets the popup item to [item], then shows the got-item popup (`PopupScr_GotItem`) for a
     * player unit (`FACTION_BLUE`), or the pilfered popup (`PopupScr_ItemWasPilfered`) for any
     * other, blocking [parent]; the clock is 0x60 frames, window style 0. The decomp's name has
     * `_unused` although `ItemGot_DisplayLePopup` calls it.
     *
     * Transcribed from src/popup.c: NewPopup_ItemGot_unused (not verified: no @VerifiesPort test
     * names it)
     */
    public fun newPopupItemGotUnused(unit: Int, item: Int, parent: Proc) {
        deps.setPopupItem(item)
        val definition =
            if (deps.unitFaction(unit) == FACTION_BLUE) POPUP_SCR_GOT_ITEM
            else POPUP_SCR_ITEM_WAS_PILFERED
        deps.newPopupSimple(parent, definition, 0x60, 0x0)
    }

    /**
     * The first call of [script]: shows the got-item popup for the proc's unit and item.
     *
     * Ported from src/popup.c: ItemGot_DisplayLePopup
     */
    public fun displayLePopup(proc: Proc) {
        val state = stateOf(proc)
        newPopupItemGotUnused(state.unit, state.item, proc)
    }

    /**
     * The second call of [script]: makes the item word for the proc's item (`MakeNewItem`) and
     * hands it to the unit through `HandleNewItemGetFromDrop`.
     *
     * Ported from src/popup.c: ItemGot_GotLeItem
     */
    public fun gotLeItem(proc: Proc) {
        val state = stateOf(proc)
        deps.handleNewItemGetFromDrop(state.unit, deps.makeNewItem(state.item), proc)
    }

    /**
     * Starts the proc for [unit] getting [item]: under [parent] blocking it, or, when [parent] is
     * null (the original's `PROC_IS_ROOT(parent)`, a root-tree number instead of a proc), as a root
     * proc of tree [rootIndex]. An enemy (`FACTION_RED`) unit is marked `US_DROP_ITEM`, so it
     * drops its last item when defeated; this runs also when the proc could not start. Returns
     * the proc, or null if a blocking proc ended while starting.
     *
     * Transcribed from src/popup.c: NewPopup_ItemGot (not verified: no @VerifiesPort test names it)
     * (blocking start and fields match item_popup; red-faction drop flag and root path unexercised)
     */
    public fun start(parent: Proc?, unit: Int, item: Int, rootIndex: Int = 3): Proc? {
        val proc =
            if (parent == null) engine.start(script, rootIndex)
            else engine.startBlocking(script, parent)
        if (proc != null) {
            val state = stateOf(proc)
            state.item = item
            state.unit = unit
        }
        if (deps.unitFaction(unit) == FACTION_RED) deps.setUnitDropItem(unit)
        return proc
    }

    /** ROM addresses of the popup and proc scripts used here (decomp labels). */
    public companion object {
        /** `PopupScr_GotItem` (ROM address): the instruction list shown for a player unit. */
        public const val POPUP_SCR_GOT_ITEM: Int = 0x08592230

        /** `PopupScr_ItemWasPilfered` (ROM address): the list shown for a non-player unit. */
        public const val POPUP_SCR_ITEM_WAS_PILFERED: Int = 0x08592288

        /**
         * `PopupScr_GotGold` (ROM address): the gold-received list (`NewPopup_GoldGot`); not used
         * by this class.
         */
        public const val POPUP_SCR_GOT_GOLD: Int = 0x08592300

        /** `ProcScr_GotItem` (ROM address): the proc script [script] mirrors. */
        public const val PROC_SCR_GOT_ITEM: Int = 0x085922D0
    }
}
