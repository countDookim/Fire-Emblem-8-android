package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import gbafe.proc.Proc

/**
 * The give-item command of `src/eventscr.c` (0x37).
 *
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
    /** Registers 0x37 on [engine]. */
    fun registerOn(engine: EventEngine) {
        engine.register(0x37, event37GiveItem)
    }

    /**
     * Transcribed from src/eventscr.c: Event37_GiveItem
     * (not verified: inventory/gold effects and popups not trace-compared)
     *
     * Host stand-ins: the "gold got" popup (`NewPopup_GoldGot`) is not shown and the gold add
     * happens at once. For items, [newPopupItemGot] (when given) runs `NewPopup_ItemGot` on the
     * event proc; otherwise the "item got" popup is skipped and the item added at once (a full
     * inventory, the item-drop menu, throws [NotImplementedError]). The proc yields one frame.
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
