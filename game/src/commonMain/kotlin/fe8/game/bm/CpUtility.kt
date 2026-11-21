package fe8.game.bm

import fe8.game.RomView
import kotlin.math.abs

/** `struct Vec2` (types.h): 2D integer coordinates. */
public class Vec2(
    /** X coordinate. */
    public var x: Int = 0,
    /** Y coordinate. */
    public var y: Int = 0,
)

/**
 * AI helpers of src/cp_utility.c and src/cp_0803E2F4.c.
 *
 * The flag setup that `AiPhaseInit` and `AiPhaseBerserkInit` (cp_phase.c) call: the healing flag
 * (`AI_UNIT_FLAG_0`) of each unit of the current faction, and the item based flags and ally
 * count of each blue (player) unit. They write `aiFlags` (0x0A) and `ai_counter` (0x46) of
 * [units] and `bestBlueMov` of [aiState]. The ally count reads `gBmMapUnit` ([map]) as it
 * stands, like the C. The distance tests, the item range map and the class rank are used by
 * [CpDecide] and [CpBattle].
 */
public class CpUtility(
    private val rom: RomView,
    private val units: UnitTable,
    private val playSt: PlaySt,
    private val aiState: AiState,
    private val loader: UnitLoader,
    private val map: BmMap,
    private val entityMaps: EntityBmMaps,
    private val active: ActiveUnit? = null,
    /** The decision loop ([CpDecide]); [aiTryMoveTowards] sets decisions on it when not null. */
    public var decide: CpDecide? = null,
) {
    /** Item attributes, ranges and use effects. */
    private val items = ItemInfo(rom)

    /** `GenerateUnitMovementMap` and the movement flood of the AI. */
    internal val moves = MovementMap(rom, map, units, playSt)

    /**
     * Ported from src/cp_0803E2F4.c: UpdateAllPhaseHealingAIStatus
     *
     * Runs [aiUpdateGetUnitIsHealing] on every valid unit of the current faction (62 blue, 20
     * green, 50 red). Verified: blue units at the berserk phase start (Seth enters healing),
     * red units at the enemy phase start and after each action (a dead red unit is not valid and
     * keeps its flags).
     */
    public fun updateAllPhaseHealingAiStatus() {
        val faction = playSt.faction
        val count = FACTION_UNIT_COUNT[faction shr 6]
        var i = 0
        while (i < count) {
            val id = faction + i + 1
            i++
            if (!units.isValid(id)) continue
            aiUpdateGetUnitIsHealing(id)
        }
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiUpdateGetUnitIsHealing
     *
     * Whether unit [id] is in healing mode: sets `AI_UNIT_FLAG_0` when its HP percentage falls
     * below the enter threshold of its `ai_config` row in `gAI3HealingThresholdTable` (selected
     * by `AI_UNIT_CONFIG_HEALTHRESHOLD_MASK`), clears it once the percentage reaches the exit
     * threshold. The C divides without a zero check (BIOS `Div`); a max HP of 0 gives 0% here.
     * Verified: entering (a unit at 43%) and not entering (full HP); the branch of a unit that
     * already has the flag is not seen.
     */
    public fun aiUpdateGetUnitIsHealing(id: Int): Boolean {
        val o = units.unitOffset(id)
        val maxHp = loader.getUnitMaxHp(o)
        val hpPercentage = if (maxHp == 0) 0 else (units.currentHp(id) * 100 / maxHp) and 0xFFFF
        val row = HEAL_THRESHOLD_TABLE + 2 * (units.aiConfig(id) and AI_CONFIG_HEAL_MASK)
        val flags = units.aiFlags(id)
        if (flags and AI_UNIT_FLAG_0 != 0) {
            if (rom.u8(row) > hpPercentage) return true
            units.setAiFlags(id, flags and AI_UNIT_FLAG_0.inv())
            return false
        }
        if (rom.u8(row + 1) > hpPercentage) {
            units.setAiFlags(id, flags or AI_UNIT_FLAG_0)
            return true
        }
        return false
    }

    /**
     * Ported from src/cp_utility.c: SetupUnitInventoryAIFlags
     *
     * Sets [AiState.bestBlueMov] to the highest Mov of the blue (player) units (ids 1 to 0x3F)
     * that are valid and none of `US_HIDDEN`, `US_DEAD`, `US_BIT16`. For each usable weapon or
     * staff of such a unit it sets `AI_UNIT_FLAG_0` for a magic item (`IA_MAGIC`) and runs the
     * two staff flag functions, then saves the unit's ally count.
     * Verified: the Mov, the skip of an unusable item (a Vulnerary adds no flag), the ally
     * counts; no magic item or staff is in the capture.
     */
    public fun setupUnitInventoryAiFlags() {
        aiState.bestBlueMov = 0
        // The blue faction's ids are 1..0x3F (`FACTION_BLUE` + 1..62).
        for (id in 1 until 0x40) {
            if (!units.isValid(id)) continue
            if (units.state(id) and SKIP_STATES != 0) continue
            val o = units.unitOffset(id)
            val mov = units.movement(id) and 0xFF
            if (mov > aiState.bestBlueMov) aiState.bestBlueMov = mov
            var j = 0
            while (j < UNIT_ITEM_COUNT) {
                val item = units.item(id, j)
                if (item == 0) break
                j++
                if (!loader.canUnitUseWeapon(o, item) && !loader.canUnitUseStaff(o, item)) continue
                if (items.getItemAttributes(item) and ItemInfo.IA_MAGIC != 0) {
                    units.setAiFlags(id, units.aiFlags(id) or AI_UNIT_FLAG_0)
                }
                setupUnitStatusStaffAiFlags(id, item)
                setupUnitHealStaffAiFlags(id, item)
            }
            saveNumberOfAlliedUnitsIn0To8Range(id)
        }
    }

    /**
     * Transcribed from src/cp_utility.c: SetupUnitStatusStaffAIFlags (not verified: the capture
     * has no staff, so only the early return for a weapon is run)
     *
     * For a staff [item] ORs `AI_UNIT_FLAG_3` (Silence staff), `_4` (Sleep), `_5` (Berserk) or
     * else `AI_UNIT_FLAG_1` (any other staff) into the flags of unit [id]; a weapon changes
     * nothing.
     */
    public fun setupUnitStatusStaffAiFlags(id: Int, item: Int) {
        if (items.getItemAttributes(item) and ItemInfo.IA_STAFF == 0) return
        val flags = when (items.getItemIndex(item)) {
            ITEM_STAFF_SILENCE -> AI_UNIT_FLAG_3
            ITEM_STAFF_SLEEP -> AI_UNIT_FLAG_4
            ITEM_STAFF_BERSERK -> AI_UNIT_FLAG_5
            else -> AI_UNIT_FLAG_1
        }
        units.setAiFlags(id, units.aiFlags(id) or flags)
    }

    /**
     * Transcribed from src/cp_utility.c: SetupUnitHealStaffAIFlags (not verified: the capture's
     * usable items are range 1 weapons with no use effect, so only the no-flag path is run)
     *
     * ORs `AI_UNIT_FLAG_6` for a weapon with a maximum range above 1 and `AI_UNIT_FLAG_2` for
     * a healing use effect into the flags of unit [id]. The use effect is `useEffectId` (byte
     * 0x1E of the item data, `GetItemUseEffect`): 1 to 5 are the Heal, Mend, Recover, Physic and
     * Fortify staffs, 0x21 the Vulnerary and 0x22 the Elixir.
     */
    public fun setupUnitHealStaffAiFlags(id: Int, item: Int) {
        var flags = 0
        if (items.getItemAttributes(item) and ItemInfo.IA_WEAPON != 0 &&
            items.getItemMaxRange(item) > 1
        ) {
            flags = AI_UNIT_FLAG_6
        }
        when (items.getItemUseEffect(item)) {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x21, 0x22 -> flags = flags or AI_UNIT_FLAG_2
        }
        units.setAiFlags(id, units.aiFlags(id) or flags)
    }

    /**
     * Ported from src/cp_utility.c: SaveNumberOfAlliedUnitsIn0To8Range
     *
     * Stores in `ai_counter` of unit [id] the number of tiles at distance 1 to 8 from it that
     * hold an allied unit on `gBmMapUnit` (`gBmMapMovement` is used as scratch). Despite the
     * `0To8` in the name, the unit's own tile is not counted. Verified: the two player units at
     * distance 1; the skip of a non-allied unit is not seen.
     */
    public fun saveNumberOfAlliedUnitsIn0To8Range(id: Int) {
        var count = 0
        map.bmMapFill(map.movement, 0)
        // `MapAddInBoundedRange(x, y, 1, 8)`: +1 within distance 8, then -1 within distance 0.
        entityMaps.mapAddInRange(units.xPos(id), units.yPos(id), 8, 1)
        entityMaps.mapAddInRange(units.xPos(id), units.yPos(id), 0, -1)
        var iy = map.sizeY - 1
        while (iy >= 0) {
            var ix = map.sizeX - 1
            while (ix >= 0) {
                val other = map.unit[iy, ix]
                if (map.movement[iy, ix] != 0 && other != 0 &&
                    BmPhase.areUnitsAllied(units.index(id), other)
                ) {
                    count++
                }
                ix--
            }
            iy--
        }
        units.setAiCounter(id, count)
    }

    /**
     * Transcribed from src/cp_utility.c: AiReachesByBirdsEyeDistance (not verified: the capture
     * sees only the tests that are clearly true or false; a threshold 4 tiles too wide, or a
     * distance of x only, keeps the test green)
     *
     * True when unit [other] is within the movement of unit [unit] plus the maximum range of
     * [item], counted in tiles as `|dx| + |dy|` (walls and costs are ignored).
     */
    public fun aiReachesByBirdsEyeDistance(unit: Int, other: Int, item: Int): Boolean {
        val distance = abs(units.xPos(unit) - units.xPos(other)) +
            abs(units.yPos(unit) - units.yPos(other))
        return distance <= units.movement(unit) + items.getItemMaxRange(item)
    }

    /**
     * Transcribed from src/cp_utility.c: AiCouldReachByBirdsEyeDistance (not verified: for the
     * Prologue's first enemy and the second player unit the formula says true, but the capture's
     * danger map shows no damage of that unit)
     *
     * As [aiReachesByBirdsEyeDistance], with the movement of [other] added: [other] could reach
     * [unit] with [item] after both moved.
     */
    public fun aiCouldReachByBirdsEyeDistance(unit: Int, other: Int, item: Int): Boolean {
        val distance = abs(units.xPos(unit) - units.xPos(other)) +
            abs(units.yPos(unit) - units.yPos(other))
        return distance <= units.movement(unit) + units.movement(other) +
            items.getItemMaxRange(item)
    }

    /**
     * Ported from src/cp_utility.c: FillMovementAndRangeMapForItem
     *
     * Floods `gBmMapMovement` for [unit] ([MovementMap.generateUnitMovementMap]) and fills
     * `gBmMapRange` with the number of reachable tiles (movement value at most
     * `MAP_MOVEMENT_MAX`) from which each tile is in the range of [item]: the ring between its
     * minimum and maximum range around every reachable tile.
     */
    public fun fillMovementAndRangeMapForItem(unit: Int, item: Int) {
        moves.generateUnitMovementMap(unit)
        map.bmMapFill(map.range, 0)
        val min = items.getItemMinRange(item)
        val max = items.getItemMaxRange(item)
        var iy = map.sizeY - 1
        while (iy >= 0) {
            var ix = map.sizeX - 1
            while (ix >= 0) {
                if (map.movement[iy, ix] <= MAP_MOVEMENT_MAX) {
                    // `MapAddInBoundedRange(ix, iy, min, max)`: +1 within max, -1 within min - 1.
                    entityMaps.mapAddInRange(ix, iy, max, 1)
                    entityMaps.mapAddInRange(ix, iy, min - 1, -1)
                }
                ix--
            }
            iy--
        }
    }

    /**
     * Transcribed from src/cp_0803E2F4.c: AiCheckDangerAt (not verified: the captures pass
     * threshold 0xFF so no candidate tile is filtered by danger, and no captured decision
     * depends on a threshold comparison)
     *
     * Returns true if danger at ([x], [y]) on `gBmMapOther` does not exceed [threshold].
     */
    public fun aiCheckDangerAt(x: Int, y: Int, threshold: Int): Boolean =
        map.other[y, x] <= (threshold and 0xFF)

    /**
     * Ported from src/cp_utility.c: AiGetClassRank
     *
     * The index of the first list of `gAiClassRankLists` that holds [classId], or the number of
     * lists (9) when none does. FE8's lists are all empty (leftover from FE6), so the result is
     * always 9 and the found branch is dead code in FE8 data.
     */
    public fun aiGetClassRank(classId: Int): Int {
        var num = 0
        var it = CLASS_RANK_LISTS
        while (rom.u32(it) != 0) {
            var list = rom.u32(it)
            while (rom.u8(list) != 0) {
                if (rom.u8(list) == classId) return num
                list++
            }
            num++
            it += 4
        }
        return num
    }

    /**
     * Ported from src/cp_utility.c: AiFindTargetInReachByFunc
     *
     * Floods `gBmMapRange` from the active unit with
     * [MovementMap.generateExtendedMovementMapOnRange] and finds the closest unit satisfying
     * [func] (range cost at most [MAP_MOVEMENT_MAX]). Ties broken in favour of smaller y, then
     * smaller x. Returns true when a target is found and sets [out]. Verified: finding Eirika at
     * (4, 5) for the boss at (10, 8); not seen: no target reachable (returning false).
     */
    public fun aiFindTargetInReachByFunc(func: AiUnitTest, out: Vec2): Boolean {
        val act = checkNotNull(active) { "active unit required" }
        moves.generateExtendedMovementMapOnRange(
            units.xPos(act.unit),
            units.yPos(act.unit),
            moves.unitMovementCostTable(act.unit),
        )
        var bestDistance = 0xFF
        var xOut = -1
        var yOut = -1
        var iy = map.sizeY - 1
        while (iy >= 0) {
            var ix = map.sizeX - 1
            while (ix >= 0) {
                val r = map.range[iy, ix]
                if (r > MAP_MOVEMENT_MAX) {
                    ix--
                    continue
                }
                val occupant = map.unit[iy, ix]
                if (occupant == 0 || occupant == act.unitId) {
                    ix--
                    continue
                }
                if (!func.run(occupant)) {
                    ix--
                    continue
                }
                if (r > bestDistance) {
                    ix--
                    continue
                }
                bestDistance = r
                xOut = ix
                yOut = iy
                ix--
            }
            iy--
        }
        if (xOut >= 0) {
            out.x = xOut
            out.y = yOut
            return true
        }
        return false
    }

    /**
     * Ported from src/cp_utility.c: AiTryMoveTowards
     *
     * Floods `gBmMapRange` from ([x], [y]) with the active unit's movement cost table (without
     * blocking units when [unk] is non-zero, else with enemy blocking units), floods
     * `gBmMapMovement` from the active unit, and picks the reachable tile (movement cost at most
     * [MAP_MOVEMENT_MAX], unoccupied or occupied by the active unit, danger not exceeding
     * [maxDanger]) that minimizes `gBmMapRange`. If a tile is found (or the active unit is
     * already at `([x], [y])`), calls [CpDecide.aiSetDecision] with [action] (0 for move only).
     * Verified: boss moving towards (4, 5) choosing (9, 6) with danger 23 <= 255; not seen:
     * active unit already at target, unk = 0, maxDanger = 0 filtering, or no reachable tile.
     */
    public fun aiTryMoveTowards(
        x: Int,
        y: Int,
        action: Int = 0,
        maxDanger: Int = 0xFF,
        unk: Int = 1,
    ): Boolean {
        val act = checkNotNull(active) { "active unit required" }
        if (units.xPos(act.unit) == x && units.yPos(act.unit) == y) {
            decide?.aiSetDecision(x, y, action, 0, 0, 0, 0)
            return true
        }
        val costTable = moves.unitMovementCostTable(act.unit)
        if (unk != 0) {
            moves.generateExtendedMovementMapOnRange(x, y, costTable)
        } else {
            moves.generateExtendedMovementMapOnRange(x, y, costTable, units.index(act.unit))
        }
        moves.generateUnitMovementMap(act.unit)

        var bestRange = map.range[units.yPos(act.unit), units.xPos(act.unit)]
        var xOut = -1
        var yOut = -1

        var iy = map.sizeY - 1
        while (iy >= 0) {
            var ix = map.sizeX - 1
            while (ix >= 0) {
                if (map.movement[iy, ix] > MAP_MOVEMENT_MAX) {
                    ix--
                    continue
                }
                val occupant = map.unit[iy, ix]
                if (occupant != 0 && occupant != act.unitId) {
                    ix--
                    continue
                }
                if (maxDanger == 0) {
                    if (units.movement(act.unit) < aiState.bestBlueMov && map.other[iy, ix] != 0) {
                        ix--
                        continue
                    }
                }
                if (!aiCheckDangerAt(ix, iy, maxDanger)) {
                    ix--
                    continue
                }
                val r = map.range[iy, ix]
                if (r > bestRange) {
                    ix--
                    continue
                }
                bestRange = r
                xOut = ix
                yOut = iy
                ix--
            }
            iy--
        }

        if (xOut >= 0) {
            decide?.aiSetDecision(xOut, yOut, action, 0, 0, 0, 0)
            return true
        }
        return false
    }

    /**
     * Helper for [AiScriptCmd_12_MoveTowardsEnemy] (cp_script.c).
     *
     * Finds the closest target in reach matching [test] via [aiFindTargetInReachByFunc], and if
     * found, moves towards it via [aiTryMoveTowards] with `action = 0`, [maxDanger], and `unk = 1`.
     */
    public fun aiMoveTowardsEnemy(test: AiUnitTest, maxDanger: Int): Boolean {
        val pos = Vec2()
        if (aiFindTargetInReachByFunc(test, pos)) {
            return aiTryMoveTowards(pos.x, pos.y, 0, maxDanger, 1)
        }
        return false
    }

    private companion object {
        /** `gAiClassRankLists`: a null-terminated array of zero-terminated class lists. */
        const val CLASS_RANK_LISTS = 0x085A8150

        /** `MAP_MOVEMENT_MAX` (bmmap.h): the largest movement map value of a reachable tile. */
        const val MAP_MOVEMENT_MAX = 120

        /** `UNIT_ITEM_COUNT` (bmunit.h). */
        const val UNIT_ITEM_COUNT = 5

        /** `AI_UNIT_FLAG_0..6` (cp_common.h), the bits of `Unit::aiFlags`. */
        const val AI_UNIT_FLAG_0 = 1 shl 0
        const val AI_UNIT_FLAG_1 = 1 shl 1
        const val AI_UNIT_FLAG_2 = 1 shl 2
        const val AI_UNIT_FLAG_3 = 1 shl 3
        const val AI_UNIT_FLAG_4 = 1 shl 4
        const val AI_UNIT_FLAG_5 = 1 shl 5
        const val AI_UNIT_FLAG_6 = 1 shl 6

        /** `AI_UNIT_CONFIG_HEALTHRESHOLD_MASK` (cp_common.h): bits 0 to 2 of `ai_config`. */
        const val AI_CONFIG_HEAL_MASK = 0x7

        /** `ITEM_STAFF_SILENCE`, `ITEM_STAFF_SLEEP`, `ITEM_STAFF_BERSERK` (constants/items.h). */
        const val ITEM_STAFF_SILENCE = 0x51
        const val ITEM_STAFF_SLEEP = 0x52
        const val ITEM_STAFF_BERSERK = 0x53

        /** `gAI3HealingThresholdTable`: `{exitThreshold, enterThreshold}` pairs. */
        const val HEAL_THRESHOLD_TABLE = 0x085A8390

        /** `US_HIDDEN | US_DEAD | US_BIT16`. */
        const val SKIP_STATES = 0x1 or 0x4 or (1 shl 16)

        /** The `factionUnitCountLut`, indexed by `faction shr 6`: blue, green, red. */
        val FACTION_UNIT_COUNT = intArrayOf(62, 20, 50)
    }
}
