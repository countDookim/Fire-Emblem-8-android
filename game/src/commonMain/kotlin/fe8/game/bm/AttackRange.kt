package fe8.game.bm

import fe8.game.bm.StructCodec.u16

/**
 * The attack range of a selected unit (src/bmidoten.c, with the weapon reach lookups of
 * src/bmitem.c): the tiles a unit can hit from any tile of its movement map. Runs right after
 * the movement map in `DisplayUnitEffectRange` (src/playerphase.c), into the pool that is the
 * working map when it starts (`gBmMapRange`, cleared by the caller). Allocates nothing.
 */
public class AttackRange(
    private val map: BmMap,
    private val units: UnitTable,
    private val items: ItemInfo,
    private val entityMaps: EntityBmMaps,
    /** Provides `CanUnitUseWeapon`. */
    private val loader: UnitLoader,
) {
    /**
     * `GetBallistaItemAt(x, y)` (bmarch.c, not ported): the item loaded in the ballista at the
     * tile, 0 when none or without uses. Returns 0 until the trap system exists.
     */
    public var ballistaItemAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

    /**
     * Ported from src/bmidoten.c: GenerateUnitCompleteAttackRange
     *
     * Adds to the working map (`gBmMapRange`) the reach of the unit with id [unitId] from every
     * tile of `gBmMapMovement` it can stand on (cost at most 120, no unit on `gBmMapUnit`, no
     * mark on `gBmMapOther`): each tile within the unit's weapon reach bits of such a tile counts
     * once per ring added. Then makes the movement map the working map. A reach-bit combination
     * not in the C `switch` adds nothing. The ballista branch is
     * (not verified: no capture has a ballista unit) and uses [ballistaItemAt].
     */
    public fun generateUnitCompleteAttackRange(unitId: Int) {
        val o = units.unitOffset(unitId)
        when (getUnitWeaponReachBits(o)) {
            REACH_RANGE1 -> addOverMovement(1, 1, 0, 0)
            REACH_RANGE1 or REACH_RANGE2 -> addOverMovement(1, 2, 0, 0)
            REACH_RANGE1 or REACH_RANGE2 or REACH_RANGE3 -> addOverMovement(1, 3, 0, 0)
            REACH_RANGE2 -> addOverMovement(2, 2, 0, 0)
            REACH_RANGE2 or REACH_RANGE3 -> addOverMovement(2, 3, 0, 0)
            REACH_RANGE3 -> addOverMovement(3, 3, 0, 0)
            REACH_RANGE3 or REACH_TO10 -> addOverMovement(3, 10, 0, 0)
            REACH_RANGE1 or REACH_RANGE3 -> addOverMovement(1, 1, 3, 3)
            REACH_RANGE1 or REACH_RANGE3 or REACH_TO10 -> addOverMovement(1, 1, 3, 10)
            REACH_RANGE1 or REACH_RANGE2 or REACH_RANGE3 or REACH_TO10 ->
                addOverMovement(1, 10, 0, 0)
            REACH_RANGE1 or REACH_TO10 -> addOverMovement(1, 4, 0, 0)
        }
        if ((units.cAttributes(unitId) and CA_BALLISTAE) != 0) {
            forEachStandableTile { x, y ->
                val item = ballistaItemAt(x, y)
                if (item != 0) {
                    mapAddInBoundedRange(x, y, items.getItemMinRange(item), items.getItemMaxRange(item))
                }
            }
        }
        map.workingMap = map.movement
    }

    /**
     * The `FOR_EACH_IN_MOVEMENT_RANGE` macro of [generateUnitCompleteAttackRange] with its
     * `MapAddInBoundedRange` calls for ([min1], [max1]) and, when [max2] is not 0, ([min2], [max2]).
     */
    private fun addOverMovement(min1: Int, max1: Int, min2: Int, max2: Int) {
        forEachStandableTile { x, y ->
            mapAddInBoundedRange(x, y, min1, max1)
            if (max2 != 0) mapAddInBoundedRange(x, y, min2, max2)
        }
    }

    /** The loop of `FOR_EACH_IN_MOVEMENT_RANGE`: bottom-right to top-left, skipping blocked tiles. */
    private inline fun forEachStandableTile(block: (x: Int, y: Int) -> Unit) {
        for (y in map.sizeY - 1 downTo 0) {
            for (x in map.sizeX - 1 downTo 0) {
                if (map.movement[y, x] > MAP_MOVEMENT_MAX) continue
                if (map.unit[y, x] != 0) continue
                if (map.other[y, x] != 0) continue
                block(x, y)
            }
        }
    }

    /**
     * Ported from src/bmidoten.c: MapAddInBoundedRange (inline)
     *
     * Adds 1 to the working map within [maxRange] of ([x], [y]) and subtracts 1 within
     * `minRange - 1`: the ring from [minRange] to [maxRange].
     */
    internal fun mapAddInBoundedRange(x: Int, y: Int, minRange: Int, maxRange: Int) {
        entityMaps.mapAddInRange(x, y, maxRange, 1)
        entityMaps.mapAddInRange(x, y, minRange - 1, -1)
    }

    /**
     * Transcribed from src/bmidoten.c: MapSetInRange (not verified: the select window never calls
     *   it)
     *
     * Sets (not adds) [value] on every working-map tile within manhattan distance [range] of
     * ([x], [y]), clipped to the map.
     */
    public fun mapSetInRange(x: Int, y: Int, range: Int, value: Int) {
        val pool = checkNotNull(map.workingMap) { "no working map" }
        var rowRange = range
        var rowY = y
        while (rowY <= y + range && rowY < map.sizeY) {
            setRow(pool, rowY, x, rowRange, value)
            rowRange--
            rowY++
        }
        rowRange = range - 1
        rowY = y - 1
        while (rowY >= y - range && rowY >= 0) {
            setRow(pool, rowY, x, rowRange, value)
            rowRange--
            rowY--
        }
    }

    private fun setRow(pool: BmMap.Pool, rowY: Int, centerX: Int, radius: Int, value: Int) {
        var xMin = centerX - radius
        var span = 2 * radius + 1
        if (xMin < 0) {
            span += xMin
            xMin = 0
        }
        var xMax = xMin + span
        if (xMax > map.sizeX) xMax = map.sizeX
        for (col in xMin until xMax) pool[rowY, col] = value
    }

    /**
     * Ported from src/bmitem.c: GetUnitWeaponReachBits
     *
     * The `REACH_*` bits of the item in slot [itemSlot] of the unit at unit-array offset [o], or
     * (an [itemSlot] below 0) the OR of those of every weapon the unit can use, walking its items
     * until the first empty slot.
     */
    internal fun getUnitWeaponReachBits(o: Int, itemSlot: Int = -1): Int {
        if (itemSlot >= 0) return getItemReachBits(u16(units.bytes, o + OFF_ITEMS + 2 * itemSlot))

        var result = 0
        for (i in 0 until ITEM_COUNT) {
            val item = u16(units.bytes, o + OFF_ITEMS + 2 * i)
            if (item == 0) break
            if (loader.canUnitUseWeapon(o, item)) result = result or getItemReachBits(item)
        }
        return result
    }

    /**
     * Ported from src/bmidoten.c: GenerateUnitStandingReachRange
     *
     * Adds to the working map the reach [reach] (`REACH_*` bits) of the unit with id [unitId]
     * from the tile it stands on. A combination not in the C `switch` adds nothing. The
     * `REACH_MAGBY2` case is not written: no item reaches by magic here and
     * `GetUnitMagBy2Range` is not ported.
     */
    public fun generateUnitStandingReachRange(unitId: Int, reach: Int) {
        val x = units.xPos(unitId)
        val y = units.yPos(unitId)
        when (reach) {
            REACH_RANGE1 -> mapAddInBoundedRange(x, y, 1, 1)
            REACH_RANGE1 or REACH_RANGE2 -> mapAddInBoundedRange(x, y, 1, 2)
            REACH_RANGE1 or REACH_RANGE2 or REACH_RANGE3 -> mapAddInBoundedRange(x, y, 1, 3)
            REACH_RANGE2 -> mapAddInBoundedRange(x, y, 2, 2)
            REACH_RANGE2 or REACH_RANGE3 -> mapAddInBoundedRange(x, y, 2, 3)
            REACH_RANGE3 -> mapAddInBoundedRange(x, y, 3, 3)
            REACH_RANGE3 or REACH_TO10 -> mapAddInBoundedRange(x, y, 3, 10)
            REACH_RANGE1 or REACH_RANGE3 -> {
                mapAddInBoundedRange(x, y, 1, 1)
                mapAddInBoundedRange(x, y, 3, 3)
            }
            REACH_RANGE1 or REACH_RANGE3 or REACH_TO10 -> {
                mapAddInBoundedRange(x, y, 1, 1)
                mapAddInBoundedRange(x, y, 3, 10)
            }
            REACH_RANGE1 or REACH_RANGE2 or REACH_RANGE3 or REACH_TO10 ->
                mapAddInBoundedRange(x, y, 1, 10)
            REACH_RANGE1 or REACH_TO10 -> mapAddInBoundedRange(x, y, 1, 4)
        }
    }

    /**
     * Ported from src/bmitem.c: GetItemReachBits
     *
     * The `REACH_*` mask of the encoded range of [item]; unlisted ranges give 0. The 0x3F range
     * (`REACH_RANGE3 | REACH_TO15`) is (not verified: no capture has such a weapon).
     */
    internal fun getItemReachBits(item: Int): Int = when (items.getItemEncodedRange(item)) {
        0x11 -> REACH_RANGE1
        0x12 -> REACH_RANGE1 or REACH_RANGE2
        0x13 -> REACH_RANGE1 or REACH_RANGE2 or REACH_RANGE3
        0x14 -> REACH_RANGE1 or REACH_TO10
        0x22 -> REACH_RANGE2
        0x23 -> REACH_RANGE2 or REACH_RANGE3
        0x33 -> REACH_RANGE3
        0x3A -> REACH_RANGE3 or REACH_TO10
        0x3F -> REACH_RANGE3 or REACH_TO15
        else -> 0
    }

    private companion object {
        const val REACH_RANGE1 = 1 shl 0
        const val REACH_RANGE2 = 1 shl 1
        const val REACH_RANGE3 = 1 shl 2
        const val REACH_TO10 = 1 shl 3
        const val REACH_TO15 = 1 shl 4
        const val MAP_MOVEMENT_MAX = 120
        const val CA_BALLISTAE = 1 shl 7
        const val ITEM_COUNT = 5
        const val OFF_ITEMS = 0x1E
    }
}
