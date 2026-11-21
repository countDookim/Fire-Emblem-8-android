package fe8.game.bm

import fe8.game.RomView

/**
 * The AI's weapon choice after its move: `CpPerform_EquipBest` of src/cp_perform.c and its
 * helpers of src/cp_0803E2F4.c. A unit that is not cantoing, not berserk and whose decision is
 * not an attack sums the damage the opposing units could deal on its destination tile and moves
 * the strongest weapon of the fitting kind to the first item slot: a melee weapon (range 1)
 * when the melee danger is at least the ranged one, else a ranged weapon (minimum range above
 * 1). With no danger it changes nothing. Equipping is `EquipUnitItemSlot`: the chosen item moves
 * to slot 0 and the items before it shift back by one.
 */
public class CpEquip(
    rom: RomView,
    private val units: UnitTable,
    /** `gActiveUnit`. */
    private val active: ActiveUnit,
    /** `gAiDecision`. */
    private val decision: AiDecision,
    private val map: BmMap,
    private val loader: UnitLoader,
    private val battle: BmBattle,
    /** `GenerateUnitMovementMap` ([CpUtility.moves]). */
    private val util: CpUtility,
) {
    private val items = ItemInfo(rom)

    /** `equip_flags` of `CpPerform_EquipBest`: [aiEquipGetFlags] output, one word per slot. */
    private val flags = IntArray(UNIT_ITEM_COUNT + 1)

    /**
     * Transcribed from src/cp_perform.c: CpPerform_EquipBest (not verified: the calls are seen
     * (the danger query's maps change at f3832), but swapping the x and y it passes to
     * [aiEquipGetDanger] keeps the test green, and the equip itself is not seen: the boss has
     * one item)
     *
     * Equips the best weapon for the danger on the decided tile when [aiCanEquip] and
     * [aiEquipGetFlags] allow it.
     */
    public fun cpPerformEquipBest() {
        if (aiCanEquip() && aiEquipGetFlags(flags)) {
            aiEquipGetDanger(decision.xMove, decision.yMove)
            aiEquipBestConsideringDanger(rangeDanger, meleeDanger, flags)
        }
    }

    /** `range_danger` (out-parameter of `AiEquipGetDanger`): total of the ranged attackers. */
    private var rangeDanger = 0

    /** `melee_danger`: total of the attackers with maximum range 1. */
    private var meleeDanger = 0

    /**
     * Ported from src/cp_0803E2F4.c: AiCanEquip
     *
     * False for a unit that is cantoing (`US_CANTOING`, the same bit as `US_HAS_MOVED`), whose
     * decision is an attack, or that is berserk; true otherwise. Verified: the true result (the
     * boss moved only). The three false branches are not seen.
     */
    public fun aiCanEquip(): Boolean {
        val unit = active.unit
        if (units.state(unit) and US_CANTOING != 0) return false
        if (decision.actionId == AI_ACTION_COMBAT) return false
        if (units.statusIndex(unit) == UnitTable.STATUS_BERSERK) return false
        return true
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiEquipGetFlags
     *
     * Fills [out] (6 entries) with one flag word per inventory slot of the active unit: 1 for a
     * weapon with maximum range 1, 2 for one with a minimum range above 1, 4 for one worn down to
     * 10 percent of its uses or less, 8 for a usable staff (which also sets the unit's AI flags
     * for the staves of `sub_803EC18`), each with the item's might in the high byte (the
     * value [aiEquipBestMatch] ranks by); 0 for a slot that is no usable weapon or staff or that
     * is `IA_LOCK_3`. The first empty slot ends the loop. Returns false for an empty inventory.
     * Deviation: the C ends without a return, so the value for a non-empty inventory is whatever
     * the register held; here it is true (the captured call of the boss, which has one weapon,
     * goes on to the danger query). Verified: the true result for a one-weapon inventory. Not
     * seen: the staff branch, the worn-down flag and the weapon range flags (they show only
     * through the equip, which needs two items).
     */
    public fun aiEquipGetFlags(out: IntArray): Boolean {
        val unit = active.unit
        val o = units.unitOffset(unit)
        if (loader.getUnitItemCount(o) == 0) return false
        for (i in 0 until UNIT_ITEM_COUNT) {
            out[i] = 0
            val item = units.item(unit, i)
            if (item == 0) break
            val attributes = items.getItemAttributes(item)
            if (attributes and (ItemInfo.IA_WEAPON or ItemInfo.IA_STAFF) == 0) continue
            if (attributes and ItemInfo.IA_LOCK_3 != 0) continue
            if (!loader.canUnitUseWeapon(o, item) && !loader.canUnitUseStaff(o, item)) continue
            if (attributes and ItemInfo.IA_WEAPON != 0) {
                if (items.getItemMinRange(item) > 1) out[i] = out[i] or 2
                if (items.getItemMaxRange(item) == 1) out[i] = out[i] or 1
                val percent = items.getItemUses(item) * 100 / items.getItemMaxUses(item)
                if (percent <= 10) out[i] = out[i] or 4
            } else {
                setStaffAiFlags(item)
                out[i] = out[i] or 8
            }
            out[i] = out[i] or (items.getItemMight(item) shl 8)
        }
        return true
    }

    /**
     * Transcribed from src/cp_0803E2F4.c: sub_803EC18 (not verified: no staff in the capture)
     *
     * Records which kind of staff the active unit carries in its `aiFlags`: `AI_UNIT_FLAG_2` for
     * `ITEM_STAFF_HEAL`, `_MEND`, `_RECOVER`, `_PHYSIC` and `_FORTIFY`, `AI_UNIT_FLAG_4` for
     * `ITEM_STAFF_REPAIR`. The decomp does not name the flags; src/cp_utility.c also sets them.
     * Named for what it does since `sub_803EC18` says nothing.
     */
    private fun setStaffAiFlags(item: Int) {
        val unit = active.unit
        when (items.getItemIndex(item)) {
            ITEM_STAFF_HEAL, ITEM_STAFF_MEND, ITEM_STAFF_RECOVER, ITEM_STAFF_PHYSIC,
            ITEM_STAFF_FORTIFY,
            -> units.setAiFlags(unit, units.aiFlags(unit) or AI_UNIT_FLAG_2)
            ITEM_STAFF_REPAIR -> units.setAiFlags(unit, units.aiFlags(unit) or AI_UNIT_FLAG_4)
        }
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiEquipGetDanger
     *
     * Clears `gBmMapOther`, then for each valid, shown, not rescued unit that is not allied with
     * the active unit (ids 1 to 0xBF) and is within its movement of ([x], [y]) in a straight
     * rectangular distance ([aiIsWithinFlyingDistance], a cheap pre-filter) floods its movement
     * map and, when it can reach the tile, adds its attack (power plus the might of its equipped
     * weapon, `StoreItemAndGetUnitAttack`) to the ranged ([rangeDanger]: minimum range above 1)
     * or melee ([meleeDanger]: maximum range 1) total and to every tile it can reach
     * (`gBmMapMovement` up to `MAP_MOVEMENT_MAX`) in `gBmMapOther`, saturating at 255. The C
     * also stores `combined_danger` (their sum), which nothing reads.
     * Verified: the clear, the flood of the last unit in range (it stays in `gBmMapMovement`)
     * and a total of 0 (no player unit can reach the boss's tile). Not seen: a non-zero danger.
     */
    public fun aiEquipGetDanger(x: Int, y: Int) {
        rangeDanger = 0
        meleeDanger = 0
        map.bmMapFill(map.other, 0)
        for (i in 1 until 0xC0) {
            if (!units.isValid(i)) continue
            if (units.state(i) and (US_HIDDEN or US_RESCUED) != 0) continue
            if (BmPhase.areUnitsAllied(active.unitId, units.index(i) and 0xFF)) continue
            if (!aiIsWithinFlyingDistance(i, x, y)) continue
            util.moves.generateUnitMovementMap(i)
            if (map.movement[y, x] == 0xFF) continue
            val weapon = loader.getUnitEquippedWeapon(units.unitOffset(i))
            val might = battle.getUnitPower(units.bytes, units.unitOffset(i)) +
                items.getItemMight(weapon)
            if (items.getItemMinRange(weapon) > 1) rangeDanger += might
            if (items.getItemMaxRange(weapon) == 1) meleeDanger += might
            for (iy in map.sizeY - 1 downTo 0) {
                for (ix in map.sizeX - 1 downTo 0) {
                    if (map.movement[iy, ix] > MAP_MOVEMENT_MAX) continue
                    map.other[iy, ix] = minOf(map.other[iy, ix] + might, 0xFF)
                }
            }
        }
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiIsWithinFlyingDistance
     *
     * Whether the unit's movement (`UNIT_MOV`) is at least its rectangular (Manhattan) distance
     * to ([x], [y]), ignoring terrain and blockers.
     */
    public fun aiIsWithinFlyingDistance(unit: Int, x: Int, y: Int): Boolean {
        val dx = x - units.xPos(unit)
        val dy = y - units.yPos(unit)
        return units.movement(unit) >= kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }

    /**
     * Transcribed from src/cp_0803E2F4.c: AiEquipBestConsideringDanger (not verified: with the
     * danger 0 it does nothing, and the boss has one item, so no equip happens)
     *
     * With any danger (melee plus ranged non-zero), equips the best melee weapon (flag 1) when
     * the melee danger is at least the ranged one, else the best ranged weapon (flag 2):
     * [aiEquipBestMatch]. The decomp's third danger argument (`combined_danger`) is unused and
     * left out here.
     */
    public fun aiEquipBestConsideringDanger(range: Int, melee: Int, equipFlags: IntArray) {
        if (melee + range != 0) {
            if (melee >= range) aiEquipBestMatch(1, equipFlags) else aiEquipBestMatch(2, equipFlags)
        }
    }

    /**
     * Transcribed from src/cp_0803E2F4.c: AiEquipBestMatch (not verified: never reached, see
     * [aiEquipBestConsideringDanger])
     *
     * Equips the slot whose flags hold [equipFlag] and have the highest might (the first such
     * slot on a tie); nothing happens when no slot matches or slot 0 wins (it is already first).
     */
    private fun aiEquipBestMatch(equipFlag: Int, equipFlags: IntArray) {
        var slot = -1
        var best = 0
        for (i in 0 until UNIT_ITEM_COUNT) {
            val f = equipFlags[i]
            if (f == 0) continue
            if (f and equipFlag == 0) continue
            if (f and 0xFF00 > best) {
                best = f and 0xFF00
                slot = i
            }
        }
        if (slot > 0) items.equipUnitItemSlot(units.bytes, units.unitOffset(active.unit), slot)
    }

    /** Decomp constants used above (bmunit.h, cp_common.h, bmmap.h, constants/items.h). */
    private companion object {
        /** `UNIT_ITEM_COUNT`. */
        const val UNIT_ITEM_COUNT = 5

        /** `AI_ACTION_COMBAT`. */
        const val AI_ACTION_COMBAT = 1

        /** `US_HIDDEN`. */
        const val US_HIDDEN = 1

        /** `US_RESCUED`. */
        const val US_RESCUED = 1 shl 5

        /** `US_CANTOING` (alias of `US_HAS_MOVED`). */
        const val US_CANTOING = 1 shl 6

        /** `MAP_MOVEMENT_MAX`: the largest value of `gBmMapMovement` that means "reachable". */
        const val MAP_MOVEMENT_MAX = 120

        /** `AI_UNIT_FLAG_2` (`Unit::aiFlags`). */
        const val AI_UNIT_FLAG_2 = 1 shl 2

        /** `AI_UNIT_FLAG_4`. */
        const val AI_UNIT_FLAG_4 = 1 shl 4

        /** `ITEM_STAFF_HEAL`. */
        const val ITEM_STAFF_HEAL = 0x4B

        /** `ITEM_STAFF_MEND`. */
        const val ITEM_STAFF_MEND = 0x4C

        /** `ITEM_STAFF_RECOVER`. */
        const val ITEM_STAFF_RECOVER = 0x4D

        /** `ITEM_STAFF_PHYSIC`. */
        const val ITEM_STAFF_PHYSIC = 0x4E

        /** `ITEM_STAFF_FORTIFY`. */
        const val ITEM_STAFF_FORTIFY = 0x4F

        /** `ITEM_STAFF_REPAIR`. */
        const val ITEM_STAFF_REPAIR = 0x57
    }
}
