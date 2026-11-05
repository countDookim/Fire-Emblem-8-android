package fe8.game.bm

import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * Refreshes the entity-dependent battle map pools of [map] (`gBmMapUnit`, `gBmMapFog`,
 * `gBmMapHidden`) from the units in [units] and the fog setting of [playSt].
 *
 * Stand-ins: the trap-dependent parts of src/bmmap.c, `RefreshTorchlightsOnBmMap` and
 * `RefreshMinesOnBmMap` (they read the bmtrick.c trap pool, not ported yet), are the hooks
 * [refreshTorchlightsOnBmMap] and [refreshMinesOnBmMap]; they do nothing until traps are ported,
 * so torchlights never light the fog map and mines never mark the hidden map.
 */
public class EntityBmMaps(
    /** The map whose pools are refreshed. */
    private val map: BmMap,
    /** The unit arrays read, and whose `state` bits 8 and 9 are updated. */
    private val units: UnitTable,
    /** Source of `chapterVisionRange` (fog) and `faction`. */
    private val playSt: PlaySt,
    /** Stand-in for `RefreshTorchlightsOnBmMap`, called between the clears and the unit pass. */
    public var refreshTorchlightsOnBmMap: () -> Unit = {},
    /** Stand-in for `RefreshMinesOnBmMap`, called after the unit pass. */
    public var refreshMinesOnBmMap: () -> Unit = {},
) {
    /**
     * Partially ported from src/bmmap.c: RefreshEntityBmMaps. Missing: the torchlight and mine
     * passes are the [refreshTorchlightsOnBmMap] / [refreshMinesOnBmMap] hooks (traps).
     *
     * Clears the unit and hidden maps with 0 and the fog map with 1 (no fog) or 0 (fog), then
     * runs the torchlight hook, [refreshUnitsOnBmMap] and the mine hook.
     */
    public fun refreshEntityBmMaps() {        map.bmMapFill(map.unit, 0)
        map.bmMapFill(map.hidden, 0)
        map.bmMapFill(map.fog, if (playSt.chapterVisionRange == 0) 1 else 0)

        refreshTorchlightsOnBmMap()
        refreshUnitsOnBmMap()
        refreshMinesOnBmMap()
    }

    /**
     * Transcribed from src/bmmap.c: RefreshUnitsOnBmMap (not verified: no
     * trace has fog, so the vision and hidden-unit branches never run)
     *
     * Puts every visible blue/green unit on the unit map and, with fog, lights the fog map around
     * it; then handles the red/purple units: magic-seal units darken the fog map, and fogged
     * units are kept off the unit map (marked hidden) unless it is the red phase. Updates the
     * unit `state` bits 8 (seen) and 9 (hidden by fog). Adds to the working map, which must be
     * the fog map (the last [BmMap.bmMapFill] of [refreshEntityBmMaps]).
     */
    public fun refreshUnitsOnBmMap() {
        val fog = playSt.chapterVisionRange != 0
        for (i in 1 until FACTION_RED) {
            val o = units.unitOffset(i)
            if (!isValid(o)) continue
            if ((state(o) and US_HIDDEN) != 0) continue

            map.unit[ySpot(o), xSpot(o)] = i
            if (fog) mapAddInRange(xSpot(o), ySpot(o), fogViewRange(i, o), 1)
        }

        val redPhase = playSt.faction == FACTION_RED
        for (i in FACTION_RED + 1 until FACTION_PURPLE + 6) {
            val o = units.unitOffset(i)
            if (!isValid(o)) continue
            if ((state(o) and US_HIDDEN) != 0) continue

            val x = xSpot(o)
            val y = ySpot(o)
            if ((units.cAttributes(i) and CA_MAGICSEAL) != 0) mapAddInRange(x, y, 10, -1)

            if (!redPhase) {
                if (fog && map.fog[y, x] == 0) {
                    map.hidden[y, x] = map.hidden[y, x] or HIDDEN_BIT_UNIT
                    put32(units.bytes, o + OFF_STATE, state(o) or US_BIT9)
                } else {
                    map.unit[y, x] = i
                    if ((state(o) and US_BIT9) != 0) {
                        put32(units.bytes, o + OFF_STATE, (state(o) and US_BIT9.inv()) or US_BIT8)
                    }
                }
            } else {
                if (fog) {
                    val hidden = map.fog[y, x] == 0
                    val s = if (hidden) state(o) or US_BIT9 else state(o) and US_BIT9.inv()
                    put32(units.bytes, o + OFF_STATE, s)
                }
                map.unit[y, x] = i
            }
        }
    }

    /**
     * Ported from src/bmidoten.c: MapAddInRange
     *
     * Adds [value] to every tile of the working map ([BmMap.workingMap]) within manhattan
     * distance [range] of ([x], [y]), clipped to the map (bytes wrap).
     */
    public fun mapAddInRange(x: Int, y: Int, range: Int, value: Int) {
        val pool = checkNotNull(map.workingMap) { "no working map" }
        var iRange = range
        var iy = y
        while (iy <= y + range && iy < map.sizeY) {
            addRow(pool, iy, x, iRange, value)
            iRange--
            iy++
        }
        iRange = range - 1
        iy = y - 1
        while (iy >= y - range && iy >= 0) {
            addRow(pool, iy, x, iRange, value)
            iRange--
            iy--
        }
    }

    private fun addRow(pool: BmMap.Pool, iy: Int, x: Int, iRange: Int, value: Int) {
        var xMin = x - iRange
        var xRange = 2 * iRange + 1
        if (xMin < 0) {
            xRange += xMin
            xMin = 0
        }
        var xMax = xMin + xRange
        if (xMax > map.sizeX) xMax = map.sizeX
        for (ix in xMin until xMax) pool[iy, ix] = pool[iy, ix] + value
    }

    /** `GetUnitFogViewRange` (src/bmunit.c): vision, +5 for thieves, plus the torch duration. */
    private fun fogViewRange(id: Int, o: Int): Int {
        var result = playSt.chapterVisionRange
        if ((units.cAttributes(id) and CA_THIEF) != 0) result += 5
        return result + (u8(units.bytes, o + OFF_TORCH) and 0xF)
    }

    private fun isValid(o: Int): Boolean = o >= 0 && u32(units.bytes, o) != 0

    private fun state(o: Int): Int = u32(units.bytes, o + OFF_STATE)

    private fun xSpot(o: Int): Int = units.bytes[o + OFF_X].toInt()

    private fun ySpot(o: Int): Int = units.bytes[o + OFF_Y].toInt()

    private companion object {
        const val FACTION_RED = 0x80
        const val FACTION_PURPLE = 0xC0
        const val OFF_STATE = 0x0C
        const val OFF_X = 0x10
        const val OFF_Y = 0x11
        const val OFF_TORCH = 0x31
        const val US_HIDDEN = 1 shl 0
        const val US_BIT8 = 1 shl 8
        const val US_BIT9 = 1 shl 9
        const val CA_THIEF = 1 shl 3
        const val CA_MAGICSEAL = 1 shl 26
        const val HIDDEN_BIT_UNIT = 1 shl 0
    }
}
