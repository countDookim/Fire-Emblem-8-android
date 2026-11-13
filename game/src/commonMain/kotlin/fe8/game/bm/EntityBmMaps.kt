package fe8.game.bm

import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * Refreshes the entity-dependent battle map pools of [map] (`gBmMapUnit`, `gBmMapFog`,
 * `gBmMapHidden`) from the units in [units] and the fog setting of [playSt]: who stands where,
 * which tiles the player's units can see, and which enemy units are hidden by fog. The game
 * redoes this after anything that moves or removes units or changes the fog (the end of a unit
 * action, an event that places units or sets the fog vision).
 *
 * Stand-ins until M6-H: the trap-dependent parts of src/bmmap.c, `RefreshTorchlightsOnBmMap` and
 * `RefreshMinesOnBmMap` (they read the bmtrick.c trap pool, not ported yet), are the hooks
 * [refreshTorchlightsOnBmMap] and [refreshMinesOnBmMap]; they do nothing until traps are ported,
 * so torchlights never light the fog map and mines never mark the hidden map.
 */
public class EntityBmMaps(
    /** The map whose pools are refreshed. */
    private val map: BmMap,
    /** The unit arrays read, and whose `state` bits 8 (seen) and 9 (in fog) are updated. */
    private val units: UnitTable,
    /**
     * Source of `chapterVisionRange` (the base vision radius in tiles, 0 for a chapter without
     * fog) and `faction` (the phase running).
     */
    private val playSt: PlaySt,
    /** Stand-in (M6-H) for `RefreshTorchlightsOnBmMap`, called between clears and unit pass. */
    public var refreshTorchlightsOnBmMap: () -> Unit = {},
    /** Stand-in (M6-H) for `RefreshMinesOnBmMap`, called after the unit pass. */
    public var refreshMinesOnBmMap: () -> Unit = {},
) {
    /**
     * Partially ported from src/bmmap.c: RefreshEntityBmMaps. Missing: the torchlight and mine
     * passes are the [refreshTorchlightsOnBmMap] / [refreshMinesOnBmMap] hooks (traps).
     *
     * Clears the unit and hidden maps with 0 and the fog map with 1 (visible, no fog in the
     * chapter) or 0 (hidden, fog), then runs the torchlight hook (which lights fog around
     * torch traps), [refreshUnitsOnBmMap] and the mine hook (which marks hidden mines).
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
     * Puts every blue/green unit that is not `US_HIDDEN` on the unit map and, with fog, lights
     * the fog map around it (adds 1 within its vision radius). Then it handles the red units
     * (ids 0x81..0xBF) and the link arena (purple) units 0xC1..0xC5: a unit with `CA_MAGICSEAL`
     * subtracts 1 from the fog map within 10 tiles (the decomp comment: this probably only
     * exists for the alternate map palette), and outside the enemy phase a red unit in fog is
     * not put on the unit map; it is marked in the hidden map (`HIDDEN_BIT_UNIT`) and gets state
     * bit 9, while a visible one is put on the unit map and swaps bit 9 for bit 8 if it had it
     * (it came into view). During the enemy phase all of them go on the unit map and bit 9
     * follows the fog (bit 8 is never set then). Updates the unit `state` bits 8 and 9 (named
     * only `US_BIT8`/`US_BIT9` in the decomp). Adds to the working map, which must be the fog
     * map (the last [BmMap.bmMapFill] of [refreshEntityBmMaps]).
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
     * distance [range] of ([x], [y]), clipped to the map (bytes wrap): a diamond, used here for
     * unit vision (+1) and magic seal fields (-1).
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

    /**
     * Adds [value] across one row of the diamond of [mapAddInRange]: the span centered at
     * [centerX] with half-width [rowRadius] (`2 * rowRadius + 1` tiles), clipped to the map.
     */
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
     * `GetUnitFogViewRange` (src/bmunit.c): how many tiles the unit [unitId] at [unitOffset]
     * sees in fog: the chapter's base vision, +5 for a unit with [CA_THIEF], plus the turns left
     * on its torch (`torchDuration`, the low 4 bits of the byte at 0x31).
     */
    private fun fogViewRange(unitId: Int, unitOffset: Int): Int {
        var result = playSt.chapterVisionRange
        if ((units.cAttributes(unitId) and CA_THIEF) != 0) result += 5
        return result + (u8(units.bytes, unitOffset + OFF_TORCH) and 0xF)
    }

    /**
     * `UNIT_IS_VALID`: the unit struct at byte offset [unitOffset] exists and has a character
     * (a non-null `pCharacterData`, the first word), that is, the slot is in use.
     */
    private fun isValid(unitOffset: Int): Boolean = unitOffset >= 0 && u32(units.bytes, unitOffset) != 0

    /** Reads the 32-bit unit state word (`struct Unit.state`) at [unitOffset]. */
    private fun state(unitOffset: Int): Int = u32(units.bytes, unitOffset + OFF_STATE)

    /** Reads the map tile X coordinate (`struct Unit.xPos`) at [unitOffset]. */
    private fun xSpot(unitOffset: Int): Int = units.bytes[unitOffset + OFF_X].toInt()

    /** Reads the map tile Y coordinate (`struct Unit.yPos`) at [unitOffset]. */
    private fun ySpot(unitOffset: Int): Int = units.bytes[unitOffset + OFF_Y].toInt()

    private companion object {
        /** `FACTION_RED` (bmunit.h): enemy units; their slot numbers follow it (0x81..). */
        const val FACTION_RED = 0x80

        /** `FACTION_PURPLE` (bmunit.h): link arena units; their slot numbers follow it (0xC1..). */
        const val FACTION_PURPLE = 0xC0

        /** Byte offset of `state` field in `struct Unit` (0x0C). */
        const val OFF_STATE = 0x0C

        /** Byte offset of `xPos` field in `struct Unit` (0x10). */
        const val OFF_X = 0x10

        /** Byte offset of `yPos` field in `struct Unit` (0x11). */
        const val OFF_Y = 0x11

        /** Byte offset of the 4-bit `torchDuration` (torch turns left) in `Unit` (0x31). */
        const val OFF_TORCH = 0x31

        /** `US_HIDDEN` (bmunit.h, bit 0): the unit is not shown on the map. */
        const val US_HIDDEN = 1 shl 0

        /**
         * `US_BIT8` (bit 8): the decomp guesses "has been seen?" (in this file it is set when a
         * fogged unit comes into view; the C comment calls it the "spotted" bit).
         */
        const val US_BIT8 = 1 shl 8

        /**
         * `US_BIT9` (bit 9): the decomp guesses "hidden by fog?"; this file sets it while the
         * unit stands on a fogged tile and clears it when it is seen.
         */
        const val US_BIT9 = 1 shl 9

        /** `CA_THIEF` (bmunit.h, bit 3): the thief attribute, which adds 5 to fog vision. */
        const val CA_THIEF = 1 shl 3

        /** `CA_MAGICSEAL` (bmunit.h, bit 26): the magic seal attribute. */
        const val CA_MAGICSEAL = 1 shl 26

        /**
         * `HIDDEN_BIT_UNIT` (bmmap.h, bit 0) of `gBmMapHidden`: a unit hidden in fog stands on the
         * tile. (`HIDDEN_BIT_TRAP`, bit 1, belongs to the mine pass, which is a hook here.)
         */
        const val HIDDEN_BIT_UNIT = 1 shl 0
    }
}
