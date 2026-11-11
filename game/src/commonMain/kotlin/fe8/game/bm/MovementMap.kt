package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32

/**
 * The movement-map flood shared by [MovementMap] (`GenerateMovementMap`) and the loader's
 * `AiGetUnitClosestValidPosition` search. Owns the preallocated frontier, so a flood allocates
 * nothing.
 */
internal class MovementFlood(val rom: RomView) {
    // A tile can be queued only once at a time, so the map pool bounds the queue size.
    internal val queue = IntArray(BmMap.MAP_POOL_SIZE)
    internal val queued = BooleanArray(BmMap.MAP_POOL_SIZE)

    /**
     * The flood `GenerateMovementMap` starts (src/bmidoten.c, asm/arm.s `MapFloodCore` and
     * `MapFloodCoreStep`) into [pool]: the cheapest cost to each tile (0xFF = not reached), rows
     * `-2 until height + 2`, from ([x0], [y0]) with the byte cost table at ROM address
     * [costTable] and the most a path may cost, [movement]. With a non-zero [unitId], tiles of
     * `map.unit` held by a unit of the other allegiance (id bit 0x80 differs) are not entered. A
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
        val DX = intArrayOf(0, 0, -1, 1)
        val DY = intArrayOf(-1, 1, 0, 0)
    }
}

/**
 * The unit movement map of src/bmidoten.c over [map]: `gBmMapMovement` flooded from a unit with
 * its class cost table. Reads the `unit` and `terrain` pools of [map], [units] and the chapter
 * weather of [playSt].
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
    private val terrainAt: (Int, Int) -> Int = { x, y -> map.terrain[y, x] }
    private var costTable = 0

    /** `gWorkingTerrainMoveCosts`: the cost table [setWorkingMoveCosts] copied last. */
    public val workingMoveCosts: ByteArray = ByteArray(TERRAIN_COUNT)

    /**
     * Ported from src/bmidoten.c: GenerateUnitMovementMapExt
     *
     * Floods `map.movement` from the unit with id [unitId] (position, `index` as the blocking id,
     * class cost table of the chapter weather) with at most [movement] cost. The window ran it
     * from `DisplayUnitEffectRange` with `UNIT_MOV - moveCount`.
     */
    public fun generateUnitMovementMapExt(unitId: Int, movement: Int) {
        val o = units.unitOffset(unitId)
        setWorkingMoveCosts(getUnitMovementCost(o))
        setWorkingBmMap(map.movement)
        generateMovementMap(units.xPos(unitId), units.yPos(unitId), movement, units.index(unitId))
    }

    /**
     * Ported from src/bmunit.c: GetUnitMovementCost
     *
     * The ROM address of the cost table of the unit at unit-array offset [o]: the class table
     * for the chapter weather, or the ballista table. The ballista and the rain/snow tables are
     * transcribed (not verified: no capture has a unit in a ballista or weather).
     */
    internal fun getUnitMovementCost(o: Int): Int {
        if ((u32(units.bytes, o + OFF_STATE) and US_IN_BALLISTA) != 0) return BALLISTA_COST_TABLE
        val index = when (playSt.chapterWeatherId) {
            WEATHER_RAIN -> 1
            WEATHER_SNOW, WEATHER_SNOWSTORM -> 2
            else -> 0
        }
        return rom.u32(u32(units.bytes, o + OFF_CLASS) + OFF_MOV_COST_TABLES + 4 * index)
    }

    /** Ported from src/bmidoten.c: SetWorkingBmMap (inline). Makes [pool] the working map. */
    internal fun setWorkingBmMap(pool: BmMap.Pool) {
        map.workingMap = pool
    }

    /**
     * Ported from src/bmidoten.c: SetWorkingMoveCosts
     *
     * Copies the [TERRAIN_COUNT] cost bytes at ROM address [mct] into [workingMoveCosts].
     */
    internal fun setWorkingMoveCosts(mct: Int) {
        costTable = mct
        for (i in 0 until TERRAIN_COUNT) workingMoveCosts[i] = rom.u8(mct + i).toByte()
    }

    /**
     * Ported from src/bmidoten.c: GenerateMovementMap
     *
     * Fills the working map with 0xFF and floods it from ([x], [y]) with the working move costs
     * (see [MovementFlood.flood]; [unitId] 0 means no unit blocks).
     */
    internal fun generateMovementMap(x: Int, y: Int, movement: Int, unitId: Int) {
        val pool = checkNotNull(map.workingMap) { "no working map" }
        flood.flood(map, pool, x, y, movement and 0xFF, unitId and 0xFF, costTable, terrainAt)
    }

    private companion object {
        const val TERRAIN_COUNT = 0x41
        const val OFF_CLASS = 0x04
        const val OFF_STATE = 0x0C
        const val OFF_MOV_COST_TABLES = 0x38
        const val US_IN_BALLISTA = 1 shl 11
        const val BALLISTA_COST_TABLE = 0x0880BC18
        const val WEATHER_RAIN = 4
        const val WEATHER_SNOW = 1
        const val WEATHER_SNOWSTORM = 2
    }
}
