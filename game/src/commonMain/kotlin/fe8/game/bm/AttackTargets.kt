package fe8.game.bm

/**
 * The attack target list of src/bmtarget.c: the units (and damaged walls and snags) a unit can
 * attack with a weapon from where it stands, collected into [targets] for the target selection
 * ([AttackMapSelect]). The range map is `gBmMapRange` of [map], filled through [attackRange].
 * Allocates nothing.
 */
public class AttackTargets(
    @PublishedApi internal val map: BmMap,
    private val units: UnitTable,
    private val items: ItemInfo,
    private val attackRange: AttackRange,
    /** `gTrapArray` (src/bmtrick.c): 8 bytes per `struct Trap`, ended by a type 0 entry. */
    private val traps: ByteArray,
    /** The list `InitTargets` and `AddTarget` of src/uiselecttarget.c fill. */
    public val targets: SelectTargets,
) {
    /** `gSubjectUnit`: the id of the unit the list is made for. */
    private var subjectUnit: Int = 0

    /**
     * Ported from src/bmtarget.c: ForEachUnitInRange
     *
     * Calls [func] with the id of every unit standing on a tile where `gBmMapRange` is not 0,
     * from the bottom-right tile to the top-left.
     */
    public inline fun forEachUnitInRange(func: (unitId: Int) -> Unit) {
        for (iy in map.sizeY - 1 downTo 0) {
            for (ix in map.sizeX - 1 downTo 0) {
                if (map.range[iy, ix] == 0) continue

                val unitId = map.unit[iy, ix]

                if (unitId == 0) continue

                func(unitId)
            }
        }
    }

    /**
     * Ported from src/bmtarget.c: AddUnitToTargetListIfNotAllied
     *
     * Adds the unit [unitId] to the list when it is not on the side of the subject unit.
     */
    public fun addUnitToTargetListIfNotAllied(unitId: Int) {
        if (!BmPhase.areUnitsAllied(units.index(subjectUnit), units.index(unitId))) {
            targets.addTarget(units.xPos(unitId), units.yPos(unitId), units.index(unitId), 0)
        }
    }

    /**
     * Ported from src/bmtarget.c: MakeTargetListForWeapon
     *
     * Makes the target list for the unit with id [unit] using [item] from where it stands: starts
     * a list at its tile, marks the ring of [item]'s minimum to maximum range on a cleared
     * `gBmMapRange`, adds every non-allied unit in it and then the obstacle traps.
     */
    public fun makeTargetListForWeapon(unit: Int, item: Int) {
        val x = units.xPos(unit)
        val y = units.yPos(unit)

        subjectUnit = unit

        targets.initTargets(x, y)

        map.bmMapFill(map.range, 0)

        attackRange.mapAddInBoundedRange(
            x, y, items.getItemMinRange(item), items.getItemMaxRange(item),
        )

        forEachUnitInRange { addUnitToTargetListIfNotAllied(it) }

        tryAddTrapsToTargetList()
    }

    /**
     * Ported from src/bmtarget.c: TryAddTrapsToTargetList
     *
     * Adds the obstacle traps (`TRAP_OBSTACLE`: damaged walls, `TERRAIN_WALL_DAMAGED`, and snags,
     * `TERRAIN_SNAG`) whose tile is in the range map, as targets with unit id 0 and the trap's
     * `extra` byte. A damaged wall is also checked one tile below the trap. The Prologue has no
     * traps, so the loop ends at the first entry (the obstacle branches are not verified).
     */
    public fun tryAddTrapsToTargetList() {
        var t = 0
        while (t + TRAP_SIZE <= traps.size && trapByte(t + 2) != TRAP_NONE) {
            if (trapByte(t + 2) != TRAP_OBSTACLE) {
                t += TRAP_SIZE
                continue
            }
            val x = trapByte(t)
            val y = trapByte(t + 1)
            val extra = trapByte(t + 3)

            if (map.terrain[y, x] == TERRAIN_WALL_DAMAGED && map.range[y, x] != 0) {
                targets.addTarget(x, y, 0, extra)
            }

            if (map.terrain[y + 1, x] == TERRAIN_WALL_DAMAGED && map.range[y + 1, x] != 0) {
                targets.addTarget(x, y + 1, 0, extra)
            }

            if (map.terrain[y, x] == TERRAIN_SNAG && map.range[y, x] != 0) {
                targets.addTarget(x, y, 0, extra)
            }
            t += TRAP_SIZE
        }
    }

    /** Byte [at] of [traps] (a `struct Trap`: x, y, type, extra, ...), unsigned. */
    private fun trapByte(at: Int): Int = traps[at].toInt() and 0xFF

    private companion object {
        /** `sizeof(struct Trap)`. */
        const val TRAP_SIZE = 8
        const val TRAP_NONE = 0
        const val TRAP_OBSTACLE = 2
        const val TERRAIN_WALL_DAMAGED = 0x1B
        const val TERRAIN_SNAG = 0x33
    }
}
