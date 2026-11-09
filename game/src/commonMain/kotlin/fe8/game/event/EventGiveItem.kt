package fe8.game.event

import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR

/**
 * The give-item command of `src/eventscr.c` (0x37).
 *
 * @param getUnitStructFromEventParameter unit id of an event character parameter, or -1.
 * @param addItem `UnitAddItem` on unit id with an item word; false if the inventory is full.
 * @param makeNewItem `MakeNewItem`: the item word for an item id.
 * @param getGold `GetPartyGoldAmount`.
 * @param setGold `SetPartyGoldAmount`.
 */
internal class EventGiveItem(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val getUnitStructFromEventParameter: (Int) -> Int,
    private val addItem: (unit: Int, item: Int) -> Boolean,
    private val makeNewItem: (Int) -> Int,
    private val getGold: () -> Int,
    private val setGold: (Int) -> Unit,
) {
    /** Registers 0x37 on [engine]. */
    fun registerOn(engine: EventEngine) {
        engine.register(0x37, event37GiveItem)
    }

    /**
     * Transcribed from src/eventscr.c: Event37_GiveItem
     * (not verified: inventory/gold effects and popups not trace-compared)
     *
     * Host stand-ins: the "item got" and "gold got"
     * popups (`NewPopup_ItemGot`, `NewPopup_GoldGot`, `ItemGot_DisplayLePopup`) are not shown;
     * their effects (`HandleNewItemGetFromDrop` with a free slot, the gold add) happen at once
     * and the proc yields one frame. A full inventory (the item-drop menu) throws
     * [NotImplementedError].
     */
    val event37GiveItem: EventHandler = EventHandler { proc ->
        val currentCmd = proc.pEventCurrent
        val subcommand = memory.u16(currentCmd) and 0xF
        val unit = getUnitStructFromEventParameter(memory.u16(currentCmd + 2).toShort().toInt())
        if (unit < 0) return@EventHandler EVC_ERROR
        when (subcommand) {
            0 -> if (!addItem(unit, makeNewItem(state.slots[3]))) {
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
