package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32

/**
 * The flood fill behind `GenerateMovementMap` (the ARM `MapFloodCore`), shared by [MovementMap]
 * and the unit loader's `AiGetUnitClosestValidPosition` search. Owns the preallocated frontier,
 * so a flood allocates nothing.
 */
internal class MovementFlood(val rom: RomView) {
    /** Ring buffer of tiles to expand, as `(y + 2) * width + x`; the C's `gMovMapFillStPool*`. */
    internal val queue = IntArray(BmMap.MAP_POOL_SIZE)

    /**
     * Whether a tile (same index as in [queue]) is waiting there. A tile is queued at most once
     * at a time, so the map pool size bounds the ring buffer.
     */
    internal val queued = BooleanArray(BmMap.MAP_POOL_SIZE)

    /**
     * The flood `GenerateMovementMap` starts (src/bmidoten.c; `MapFloodCore` and
     * `MapFloodCoreStep` in asm/arm.s) into [pool]: the cheapest movement cost to each tile
     * (0xFF, the C's -1, = not reached), rows `-2 until height + 2`, from ([x0], [y0]) with the
     * s8 terrain cost table at ROM address [costTable] (indexed by terrain id, `TERRAIN_*`; an
     * impassable terrain costs -1 = 0xFF, which always exceeds [movement]) and the most a path
     * may cost, [movement]. With a non-zero [unitId], tiles of `map.unit` held by a unit on the
     * other side (bit 0x80 of the unit id, set for enemy units, differs) are not entered. A
     * reusable frontier revisits tiles when a cheaper path is found; the resulting minima match
     * the repeated relaxation of every tile (the ARM's wave order gives the same minima).
     *
     * Deviation: the ARM step has no bounds check (it relies on terrain-0 cost and the pool's
     * row aliasing, which [BmMap.Pool] models); a start outside `0 until width` reaches nothing
     * and no tile outside the map columns is entered.
     */
    inline fun flood(
        map: BmMap,
        pool: BmMap.Pool,
        x0: Int,
        y0: Int,
        movement: Int,
        unitId: Int,
        costTable: Int,
        terrainAt: (x: Int, y: Int) -> Int,
    ) {
        val w = map.sizeX
        val h = map.sizeY
        map.bmMapFill(pool, 0xFF)
        if (x0 !in 0 until w || y0 !in -2 until h + 2) return
        val tileCount = w * (h + 4)
        queued.fill(false, 0, tileCount)
        var head = 0
        var tail = 0
        var count = 0
        val start = (y0 + 2) * w + x0
        pool[y0, x0] = 0
        queue[tail++] = start
        queued[start] = true
        count++
        while (count > 0) {
            val tile = queue[head]
            head = (head + 1) % queue.size
            count--
            queued[tile] = false
            val cx = tile % w
            val cy = tile / w - 2
            val base = pool[cy, cx]
            for (d in 0 until 4) {
                val nx = cx + DX[d]
                val ny = cy + DY[d]
                if (nx !in 0 until w || ny !in -2 until h + 2) continue
                val cost = base + rom.u8(costTable + terrainAt(nx, ny))
                if (cost >= pool[ny, nx]) continue
                if (unitId != 0) {
                    val occupant = map.unit[ny, nx]
                    if (occupant != 0 && ((occupant xor unitId) and 0x80) != 0) continue
                }
                if (cost > movement) continue
                pool[ny, nx] = cost
                val next = (ny + 2) * w + nx
                if (!queued[next]) {
                    queue[tail] = next
                    tail = (tail + 1) % queue.size
                    count++
                    queued[next] = true
                }
            }
        }
    }

    companion object {
        /** Column offsets of the four neighbours: up, down, left, right. */
        val DX = intArrayOf(0, 0, -1, 1)

        /** Row offsets of the four neighbours, in the order of [DX]. */
        val DY = intArrayOf(-1, 1, 0, 0)
    }
}

/**
 * The unit movement map of src/bmidoten.c over [map]: `gBmMapMovement` (`map.movement`) flooded
 * from a unit with its class's terrain movement costs. Reads the `unit` and `terrain` pools of
 * [map], [units] and the chapter weather (`chapterWeatherId`) of [playSt].
 *
 * Deviation: the C copies the cost table into `gWorkingTerrainMoveCosts` and the flood reads
 * that copy; here [workingMoveCosts] holds the copy and the flood reads the same ROM table.
 */
public class MovementMap(
    private val rom: RomView,
    private val map: BmMap,
    private val units: UnitTable,
    private val playSt: PlaySt,
) {
    private val flood = MovementFlood(rom)

    /** The terrain id of a tile, `gBmMapTerrain[y][x]`, for the flood. */
    private val terrainAt: (Int, Int) -> Int = { x, y -> map.terrain[y, x] }

    /** ROM address of the cost table the flood reads, set with [workingMoveCosts]. */
    private var costTable = 0

    /** `gWorkingTerrainMoveCosts`: the cost table [setWorkingMoveCosts] copied last, by terrain
     * id (what `GetWorkingMoveCosts` returns; the path arrow reads it). */
    public val workingMoveCosts: ByteArray = ByteArray(TERRAIN_COUNT)

    /**
     * Ported from src/bmidoten.c: GenerateUnitMovementMap
     *
     * [generateUnitMovementMapExt] with the unit's own movement (`UNIT_MOV`) as the budget.
     */
    public fun generateUnitMovementMap(unitId: Int) {
        generateUnitMovementMapExt(unitId, units.movement(unitId))
    }

    /**
     * Ported from src/bmidoten.c: GenerateUnitMovementMapExt
     *
     * Floods `map.movement` from the unit with id [unitId] (its position, its `index` as the
     * blocking id, its cost table from [getUnitMovementCost]) with at most [movement] cost. The
     * capture ran it from `DisplayUnitEffectRange` with `UNIT_MOV - moveCount`.
     */
    public fun generateUnitMovementMapExt(unitId: Int, movement: Int) {
        setWorkingMoveCosts(getUnitMovementCost(units.unitOffset(unitId)))
        setWorkingBmMap(map.movement)
        generateMovementMap(units.xPos(unitId), units.yPos(unitId), movement, units.index(unitId))
    }

    /**
     * Transcribed from src/bmidoten.c: GenerateMovementMapOnWorkingMap (not verified: only
     * `ResetPathArrow` and the path arrow's re-route call it, and the capture runs neither)
     *
     * Sets the working move costs of the unit with id [unitId] and floods the current working map
     * (`gWorkingBmMap`, chosen by [setWorkingBmMap]) from ([x], [y]) with at most [movement]
     * cost.
     */
    public fun generateMovementMapOnWorkingMap(unitId: Int, x: Int, y: Int, movement: Int) {
        setWorkingMoveCosts(getUnitMovementCost(units.unitOffset(unitId)))
        generateMovementMap(x, y, movement, units.index(unitId))
    }

    /**
     * Ported from src/bmidoten.c: GenerateExtendedMovementMapOnRange
     *
     * Sets working move costs to [costTable] and floods `map.range` (`gBmMapRange`) from ([x],
     * [y]) with [MAP_MOVEMENT_EXTENDED] (124) movement budget and unit id 0 (no blocking units).
     */
    public fun generateExtendedMovementMapOnRange(x: Int, y: Int, costTable: Int) {
        setWorkingMoveCosts(costTable)
        setWorkingBmMap(map.range)
        generateMovementMap(x, y, MAP_MOVEMENT_EXTENDED, 0)
    }

    /**
     * The ROM address of the terrain cost table of unit [unitId] for current weather (see
     * [getUnitMovementCost]).
     */
    public fun unitMovementCostTable(unitId: Int): Int =
        getUnitMovementCost(units.unitOffset(unitId))

    /**
     * Ported from src/bmunit.c: GetUnitMovementCost
     *
     * The ROM address of the terrain cost table of the unit at unit-array offset [unitOffset]:
     * the ballista table (`Unk_TerrainTable_0880BC18`) for a unit in a ballista
     * (`US_IN_BALLISTA`), else the class's `pMovCostTable` entry for the chapter weather (0
     * standard, 1 rain, 2 snow and snowstorm). The ballista and the rain/snow tables are
     * transcribed (not verified: no capture has a unit in a ballista or weather).
     */
    internal fun getUnitMovementCost(unitOffset: Int): Int {
        if ((u32(units.bytes, unitOffset + OFF_STATE) and US_IN_BALLISTA) != 0) {
            return BALLISTA_COST_TABLE
        }
        val index = when (playSt.chapterWeatherId) {
            WEATHER_RAIN -> 1
            WEATHER_SNOW, WEATHER_SNOWSTORM -> 2
            else -> 0
        }
        return rom.u32(u32(units.bytes, unitOffset + OFF_CLASS) + OFF_MOV_COST_TABLES + 4 * index)
    }

    /**
     * Ported from src/bmidoten.c: SetWorkingBmMap (inline)
     *
     * Makes [pool] the working map (`gWorkingBmMap`) that the flood and the path search use.
     */
    internal fun setWorkingBmMap(pool: BmMap.Pool) {
        map.workingMap = pool
    }

    /**
     * Ported from src/bmidoten.c: SetWorkingMoveCosts
     *
     * Copies the [TERRAIN_COUNT] cost bytes at ROM address [costTableAddress] into
     * [workingMoveCosts].
     */
    internal fun setWorkingMoveCosts(costTableAddress: Int) {
        costTable = costTableAddress
        for (i in 0 until TERRAIN_COUNT) {
            workingMoveCosts[i] = rom.u8(costTableAddress + i).toByte()
        }
    }

    /**
     * Ported from src/bmidoten.c: GenerateMovementMap
     *
     * Fills the working map with 0xFF (not reached) and floods it from ([x], [y]) with the
     * working move costs (see [MovementFlood.flood]; [unitId] 0 means no unit blocks). The C
     * also sets `maxMovementValue` to `MAP_MOVEMENT_MAX`; only the range-extension passes read
     * it, so the flood has no use for it.
     */
    internal fun generateMovementMap(x: Int, y: Int, movement: Int, unitId: Int) {
        val pool = checkNotNull(map.workingMap) { "no working map" }
        flood.flood(map, pool, x, y, movement and 0xFF, unitId and 0xFF, costTable, terrainAt)
    }

    /** Constants and limits of [MovementMap]. */
    public companion object {
        /** `MAP_MOVEMENT_EXTENDED` (bmmap.h): extended movement map flood budget. */
        public const val MAP_MOVEMENT_EXTENDED: Int = 124

        /** `TERRAIN_COUNT` (include/constants/terrains.h). */
        private const val TERRAIN_COUNT = 0x41

        /** `Unit.pClassData`. */
        private const val OFF_CLASS = 0x04

        /** `Unit.state`. */
        private const val OFF_STATE = 0x0C

        /** `ClassData.pMovCostTable` (standard, rain, snow). */
        private const val OFF_MOV_COST_TABLES = 0x38

        /** `US_IN_BALLISTA` (include/bmunit.h). */
        private const val US_IN_BALLISTA = 1 shl 11

        /** `Unk_TerrainTable_0880BC18` (src/data_terrains.c). */
        private const val BALLISTA_COST_TABLE = 0x0880BC18

        /** `WEATHER_RAIN`, `WEATHER_SNOW`, `WEATHER_SNOWSTORM` (include/types.h). */
        private const val WEATHER_RAIN = 4
        private const val WEATHER_SNOW = 1
        private const val WEATHER_SNOWSTORM = 2
    }
}
