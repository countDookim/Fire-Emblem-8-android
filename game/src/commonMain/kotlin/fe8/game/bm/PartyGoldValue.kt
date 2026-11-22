package fe8.game.bm

/**
 * The party's total gold value of `src/bmitem.c`: gold plus the cost of the convoy and unit items.
 *
 * @property items the item data (`GetItemCost`).
 * @property units the unit arrays (`GetUnit`).
 * @property convoy the supply convoy (`gConvoyItemArray`).
 * @property playSt the play state (`gPlaySt`, for `GetPartyGoldAmount`).
 */
public class PartyGoldValue(
    private val items: ItemInfo,
    private val units: UnitTable,
    private val convoy: BmContainer,
    private val playSt: PlaySt,
) {
    /**
     * Transcribed from src/bmitem.c: GetConvoyItemCostSum (not verified: the convoy is empty
     *   at f9443, so the sum is 0 whatever this does)
     *
     * The summed cost of the convoy items up to the first empty slot.
     */
    public fun getConvoyItemCostSum(): Int {
        val array = convoy.getConvoyItemArray()
        var result = 0
        for (i in 0 until CONVOY_ITEM_COUNT) {
            val item = (array[2 * i].toInt() and 0xFF) or ((array[2 * i + 1].toInt() and 0xFF) shl 8)
            if (item == 0) break
            result += items.getItemCost(item)
        }
        return result
    }

    /**
     * Transcribed from src/bmitem.c: GetUnitItemCostSum (not verified: the sum 7910 at f9444 checks the
     *   unit walk and the uses factor, but dropping the `US_DEAD`/`US_BIT16` skip or the fifth
     *   slot survives mutation: no such unit or item occurs)
     *
     * The summed cost of the items of the valid units with ids 1..0x3F that are neither dead nor
     * `US_BIT16`, each unit up to its first empty slot.
     */
    public fun getUnitItemCostSum(): Int {
        var result = 0
        for (id in 1 until 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and (US_DEAD or US_BIT16)) != 0) continue
            for (slot in 0 until UNIT_ITEM_COUNT) {
                val item = units.item(id, slot)
                if (item == 0) break
                result += items.getItemCost(item)
            }
        }
        return result
    }

    /**
     * Transcribed from src/bmitem.c: GetPartyTotalGoldValue (not verified: party gold and convoy
     *   are 0 at f9443, so the chapter-5 zero and the convoy term are unobserved; the 9999999 cap
     *   was mutation-checked only as far as the 7910 total)
     *
     * The convoy and unit item values plus `GetPartyGoldAmount` (0 in chapter index 5), capped at
     * 9999999.
     */
    public fun getPartyTotalGoldValue(): Int {
        var result = getConvoyItemCostSum() + getUnitItemCostSum()
        result += if (playSt.chapterIndex == CHAPTER_NO_GOLD) 0 else playSt.partyGoldAmount
        return minOf(result, MAX_VALUE)
    }

    private companion object {
        const val CONVOY_ITEM_COUNT = 100
        const val UNIT_ITEM_COUNT = 5
        const val US_DEAD = 1 shl 2
        const val US_BIT16 = 1 shl 16
        const val CHAPTER_NO_GOLD = 5
        const val MAX_VALUE = 9999999
    }
}
