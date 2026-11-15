package fe8.game.bm

import fe8.game.RomView

/**
 * The AI's weapon choice after a move without an action: `CpPerform_EquipBest` of
 * src/cp_perform.c and its helpers of src/cp_0803E2F4.c. A unit that moved only (and is not
 * berserk or cantoing) puts the weapon first that suits the damage the player units could do
 * on its tile: a melee weapon when the melee danger is at least the ranged one, else a ranged
 * weapon.
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

    private var rangeDanger = 0
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
     * weapon of range 1 only, 2 for one with a minimum range above 1, 4 for one worn down to 10
     * percent of its uses, 8 for a usable staff (which also sets the unit's AI flag for healing
     * or repair staves), each with the item's might in the high byte; 0 for a slot that is no
     * usable weapon or staff. Returns false for an empty inventory. Deviation: the C ends without
     * a return, so the value for a non-empty inventory is whatever the register held; here it is
     * true (the captured call of the boss, which has one weapon, goes on to the danger query).
     * Verified: the true result for a one-weapon inventory. Not seen: the staff branch, the
     * worn-down flag and the weapon range flags (they show only through the equip, which needs
     * two items).
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
                sub803EC18(item)
                out[i] = out[i] or 8
            }
            out[i] = out[i] or (items.getItemMight(item) shl 8)
        }
        return true
    }

    /**
     * Transcribed from src/cp_0803E2F4.c: sub_803EC18 (not verified: no staff in the capture)
     *
     * Marks the active unit as a healer (`AI_UNIT_FLAG_2`) for the heal, mend, recover, physic
     * and fortify staves, and as a repairer (`AI_UNIT_FLAG_4`) for the repair staff.
     */
    private fun sub803EC18(item: Int) {
        val unit = active.unit
        when (items.getItemIndex(item)) {
            0x4B, 0x4C, 0x4D, 0x4E, 0x4F -> units.setAiFlags(unit, units.aiFlags(unit) or (1 shl 2))
            0x57 -> units.setAiFlags(unit, units.aiFlags(unit) or (1 shl 4))
        }
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiEquipGetDanger
     *
     * Clears `gBmMapOther`, then for each valid shown enemy of the active unit (ids 1 to 0xBF
     * that are not hidden or rescued) within its flying distance of ([x], [y]) floods its
     * movement map and, when it can reach the tile, adds its attack (power plus the might of its
     * equipped weapon) to the ranged ([rangeDanger]: minimum range above 1) or melee
     * ([meleeDanger]: maximum range 1) total and to every tile it can reach, saturating at 255.
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
     * Whether the unit's movement is at least its rectangular distance to ([x], [y]).
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
     * With any danger, equips the best melee weapon (flag 1) when the melee danger is at least
     * the ranged one, else the best ranged weapon (flag 2): [aiEquipBestMatch].
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
     * Equips the slot (after the first) whose flags hold [equipFlag] and have the highest might.
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

    private companion object {
        const val UNIT_ITEM_COUNT = 5
        const val AI_ACTION_COMBAT = 1
        const val US_HIDDEN = 1
        const val US_RESCUED = 1 shl 5
        const val US_CANTOING = 1 shl 6
        const val MAP_MOVEMENT_MAX = 120
    }
}
