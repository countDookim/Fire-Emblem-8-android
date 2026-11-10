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
    public fun refreshEntityBmMaps() {
        map.bmMapFill(map.unit, 0)
        map.bmMapFill(map.hidden, 0)
        map.bmMapFill(map.fog, if (playSt.chapterVisionRange == 0) 1 else 0)

        refreshTorchlightsOnBmMap()
        refreshUnitsOnBmMap()
        refreshMinesOnBmMap()
    }

    /**
     * Ported from src/bmmap.c: RefreshUnitsOnBmMap
     *
     * Puts every visible blue/green unit on the unit map and, with fog, lights the fog map around
     * it; then handles the red/purple units: magic-seal units darken the fog map, and fogged
     * units are kept off the unit map (marked hidden) unless it is the red phase. Updates the
     * unit `state` bits 8 (seen) and 9 (hidden by fog). Adds to the working map, which must be
     * the fog map (the last [BmMap.bmMapFill] of [refreshEntityBmMaps]).
     */
    public fun refreshUnitsOnBmMap() {
        val hasFog = playSt.chapterVisionRange != 0
        for (unitId in 1 until FACTION_RED) {
            val unitOffset = units.unitOffset(unitId)
            if (!isValid(unitOffset)) continue
            if ((state(unitOffset) and US_HIDDEN) != 0) continue

            map.unit[ySpot(unitOffset), xSpot(unitOffset)] = unitId
            if (hasFog) mapAddInRange(xSpot(unitOffset), ySpot(unitOffset), fogViewRange(unitId, unitOffset), 1)
        }

        val isRedPhase = playSt.faction == FACTION_RED
        for (unitId in FACTION_RED + 1 until FACTION_PURPLE + 6) {
            val unitOffset = units.unitOffset(unitId)
            if (!isValid(unitOffset)) continue
            if ((state(unitOffset) and US_HIDDEN) != 0) continue

            val x = xSpot(unitOffset)
            val y = ySpot(unitOffset)
            if ((units.cAttributes(unitId) and CA_MAGICSEAL) != 0) mapAddInRange(x, y, 10, -1)

            if (!isRedPhase) {
                if (hasFog && map.fog[y, x] == 0) {
                    map.hidden[y, x] = map.hidden[y, x] or HIDDEN_BIT_UNIT
                    put32(units.bytes, unitOffset + OFF_STATE, state(unitOffset) or US_BIT9)
                } else {
                    map.unit[y, x] = unitId
                    if ((state(unitOffset) and US_BIT9) != 0) {
                        put32(units.bytes, unitOffset + OFF_STATE, (state(unitOffset) and US_BIT9.inv()) or US_BIT8)
                    }
                }
            } else {
                if (hasFog) {
                    val isHiddenByFog = map.fog[y, x] == 0
                    val updatedState = if (isHiddenByFog) state(unitOffset) or US_BIT9 else state(unitOffset) and US_BIT9.inv()
                    put32(units.bytes, unitOffset + OFF_STATE, updatedState)
                }
                map.unit[y, x] = unitId
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
        var rowDistance = range
        var rowY = y
        while (rowY <= y + range && rowY < map.sizeY) {
            addRow(pool, rowY, x, rowDistance, value)
            rowDistance--
            rowY++
        }
        rowDistance = range - 1
        rowY = y - 1
        while (rowY >= y - range && rowY >= 0) {
            addRow(pool, rowY, x, rowDistance, value)
            rowDistance--
            rowY--
        }
    }

    /** Adds [value] across a horizontal span centered at [centerX] with half-width [rowRadius]. */
    private fun addRow(pool: BmMap.Pool, rowY: Int, centerX: Int, rowRadius: Int, value: Int) {
        var xMin = centerX - rowRadius
        var spanWidth = 2 * rowRadius + 1
        if (xMin < 0) {
            spanWidth += xMin
            xMin = 0
        }
        var xMax = xMin + spanWidth
        if (xMax > map.sizeX) xMax = map.sizeX
        for (colX in xMin until xMax) pool[rowY, colX] = pool[rowY, colX] + value
    }

    /**
     * Computes the effective vision range for unit [unitId] at [unitOffset] (`GetUnitFogViewRange`, src/bmunit.c).
     * Includes chapter base vision, +5 vision bonus for thieves ([CA_THIEF]), and remaining torch item duration.
     */
    private fun fogViewRange(unitId: Int, unitOffset: Int): Int {
        var result = playSt.chapterVisionRange
        if ((units.cAttributes(unitId) and CA_THIEF) != 0) result += 5
        return result + (u8(units.bytes, unitOffset + OFF_TORCH) and 0xF)
    }

    /** True if the unit struct at byte offset [unitOffset] is valid and has a non-null character pointer. */
    private fun isValid(unitOffset: Int): Boolean = unitOffset >= 0 && u32(units.bytes, unitOffset) != 0

    /** Reads the 32-bit unit state word (`struct Unit.state`) at [unitOffset]. */
    private fun state(unitOffset: Int): Int = u32(units.bytes, unitOffset + OFF_STATE)

    /** Reads the map tile X coordinate (`struct Unit.xPos`) at [unitOffset]. */
    private fun xSpot(unitOffset: Int): Int = units.bytes[unitOffset + OFF_X].toInt()

    /** Reads the map tile Y coordinate (`struct Unit.yPos`) at [unitOffset]. */
    private fun ySpot(unitOffset: Int): Int = units.bytes[unitOffset + OFF_Y].toInt()

    private companion object {
        /** Red / enemy faction unit index start (0x80). */
        const val FACTION_RED = 0x80

        /** Purple / link arena faction unit index start (0xC0). */
        const val FACTION_PURPLE = 0xC0

        /** Byte offset of `state` field in `struct Unit` (0x0C). */
        const val OFF_STATE = 0x0C

        /** Byte offset of `xPos` field in `struct Unit` (0x10). */
        const val OFF_X = 0x10

        /** Byte offset of `yPos` field in `struct Unit` (0x11). */
        const val OFF_Y = 0x11

        /** Byte offset of `torch` item countdown field in `struct Unit` (0x31). */
        const val OFF_TORCH = 0x31

        /** Unit state flag: unit is hidden / absent from map (bit 0). */
        const val US_HIDDEN = 1 shl 0

        /** Unit state flag: unit has been revealed / seen this turn (bit 8). */
        const val US_BIT8 = 1 shl 8

        /** Unit state flag: unit is concealed by fog of war (bit 9). */
        const val US_BIT9 = 1 shl 9

        /** Character attribute flag: thief class vision range bonus (+5 tiles, bit 3). */
        const val CA_THIEF = 1 shl 3

        /** Character attribute flag: magic seal dampening field (bit 26). */
        const val CA_MAGICSEAL = 1 shl 26

        /** Bitmask in `gBmMapHidden` indicating a hidden unit on that tile (bit 0). */
        const val HIDDEN_BIT_UNIT = 1 shl 0
    }
}
