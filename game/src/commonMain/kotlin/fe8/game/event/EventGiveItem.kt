package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import gbafe.proc.Proc

/**
 * The give-item command of `src/eventscr.c` (0x37): gives a unit an item (shown in a popup),
 * gives the party gold, or takes gold away.
 *
 * @param state the event slots (`gEventSlots[3]` is the item id or gold amount).
 * @param memory script words (the command arguments).
 * @param getUnitStructFromEventParameter unit id of an event character parameter, or -1.
 * @param addItem `UnitAddItem` on unit id with an item word; false if the inventory is full.
 * @param makeNewItem `MakeNewItem`: the item word for an item id.
 * @param getGold `GetPartyGoldAmount`.
 * @param setGold `SetPartyGoldAmount`.
 * @param newPopupItemGot `NewPopup_ItemGot(proc, unit, item)` with the event engine's proc as
 *   parent (it blocks that proc); when null, the item is added at once instead.
 */
internal class EventGiveItem(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val getUnitStructFromEventParameter: (Int) -> Int,
    private val addItem: (unit: Int, item: Int) -> Boolean,
    private val makeNewItem: (Int) -> Int,
    private val getGold: () -> Int,
    private val setGold: (Int) -> Unit,
    private val newPopupItemGot: ((parent: Proc, unit: Int, item: Int) -> Unit)? = null,
) {
    /** Registers the give-item command 0x37 on [engine]. */
    fun registerOn(engine: EventEngine) {
        engine.register(0x37, event37GiveItem)
    }

    /**
     * Transcribed from src/eventscr.c: Event37_GiveItem
     * (not verified: inventory/gold effects and popups not trace-compared)
     *
     * Subcommand 0 gives the item whose id is in slot 3 to the unit of the first argument, 1
     * gives slot 3 gold (the decomp names it GIVEITEMTOMAIN) and 2 takes slot 3 gold, not below
     * 0 (the decomp names it GIVETOSLOT3).
     *
     * NOTE: in C `NewPopup_GoldGot` adds the gold only when the target unit is a player unit;
     * for any other unit it shows the "gold was stolen" popup and changes nothing. Here the gold
     * is always added. Also, `Fe8Boot` wires [getGold] and [setGold] to the raw
     * `partyGoldAmount`, without the chapter-5 zero of `GetPartyGoldAmount` and the 999999 cap
     * of `SetPartyGoldAmount` (src/bmgold.c).
     *
     * Host stand-ins (D09-3): the "gold got" popup (`NewPopup_GoldGot`) is not shown and the
     * gold add happens at once. For items, [newPopupItemGot] (when given) runs
     * `NewPopup_ItemGot` on the event proc; otherwise the "item got" popup is skipped and
     * the item added at once (a full inventory, the item-drop menu, throws
     * [NotImplementedError]). The proc yields one frame.
     */
    val event37GiveItem: EventHandler = EventHandler { proc ->
        val currentCmd = proc.pEventCurrent
        val subcommand = memory.u16(currentCmd) and 0xF
        val unit = getUnitStructFromEventParameter(memory.u16(currentCmd + 2).toShort().toInt())
        if (unit < 0) return@EventHandler EVC_ERROR
        when (subcommand) {
            0 -> if (newPopupItemGot != null) {
                newPopupItemGot(proc.proc!!, unit, state.slots[3])
            } else if (!addItem(unit, makeNewItem(state.slots[3]))) {
                throw NotImplementedError(
                    "GIVEITEMTO with a full inventory (item-drop menu) is not ported",
                )
            }
            1 -> setGold(getGold() + state.slots[3])
            2 -> setGold(maxOf(getGold() - state.slots[3], 0))
        }
        EVC_ADVANCE_YIELD
    }
}
