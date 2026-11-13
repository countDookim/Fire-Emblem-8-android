package fe8.game.bm

import fe8.game.RomView

/**
 * The AI unit flag setup of src/cp_utility.c and src/cp_0803E2F4.c that `AiPhaseInit` and
 * `AiPhaseBerserkInit` (cp_phase.c) call: the heal-threshold flag of each unit of the current
 * faction and the item based flags and ally count of each player unit. They write `aiFlags`
 * (0x0A) and `ai_counter` (0x46) of [units] and `bestBlueMov` of [aiState]. The ally count reads
 * `gBmMapUnit` ([map]) as it stands, like the C.
 */
public class CpUtility(
    private val rom: RomView,
    private val units: UnitTable,
    private val playSt: PlaySt,
    private val aiState: AiState,
    private val loader: UnitLoader,
    private val map: BmMap,
    private val entityMaps: EntityBmMaps,
) {
    private val items = ItemInfo(rom)

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
     * below the enter threshold of its `ai_config` row in `gAI3HealingThresholdTable`, clears it
     * once the percentage reaches the exit threshold. Verified: entering (a unit at 43%) and not
     * entering (full HP); the branch of a unit that already has the flag is not seen.
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
     * Sets [AiState.bestBlueMov] to the highest Mov of the player units (ids 1 to 0x3F) that are
     * valid and not hidden, dead or `US_BIT16`; for each such unit's usable weapon or staff sets
     * `AI_UNIT_FLAG_0` for a magic item and runs the staff flag functions, then saves its ally
     * count. Verified: the Mov, the skip of an unusable item (a Vulnerary adds no flag), the ally
     * counts; no magic item or staff is in the capture.
     */
    public fun setupUnitInventoryAiFlags() {
        aiState.bestBlueMov = 0
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
     * For a staff ORs `AI_UNIT_FLAG_3` (silence), `_4` (sleep), `_5` (berserk) or else
     * `AI_UNIT_FLAG_1` into the flags of unit [id].
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
     * the healing use effects (1 to 5, 0x21, 0x22) into the flags of unit [id].
     */
    public fun setupUnitHealStaffAiFlags(id: Int, item: Int) {
        var flags = 0
        if (items.getItemAttributes(item) and ItemInfo.IA_WEAPON != 0 &&
            items.getItemMaxRange(item) > 1
        ) {
            flags = AI_UNIT_FLAG_6
        }
        when (rom.u8(items.getItemData(items.getItemIndex(item)) + 0x1E)) {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x21, 0x22 -> flags = flags or AI_UNIT_FLAG_2
        }
        units.setAiFlags(id, units.aiFlags(id) or flags)
    }

    /**
     * Ported from src/cp_utility.c: SaveNumberOfAlliedUnitsIn0To8Range
     *
     * Stores in `ai_counter` of unit [id] the number of tiles at distance 1 to 8 from it that
     * hold an allied unit on `gBmMapUnit` (the movement map is used as scratch). Verified: the
     * two player units at distance 1; the skip of a non-allied unit is not seen.
     */
    public fun saveNumberOfAlliedUnitsIn0To8Range(id: Int) {
        var count = 0
        map.bmMapFill(map.movement, 0)
        // MapAddInBoundedRange(x, y, 1, 8): +1 within 8, -1 within 0
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

    private companion object {
        const val UNIT_ITEM_COUNT = 5
        const val AI_UNIT_FLAG_0 = 1 shl 0
        const val AI_UNIT_FLAG_1 = 1 shl 1
        const val AI_UNIT_FLAG_2 = 1 shl 2
        const val AI_UNIT_FLAG_3 = 1 shl 3
        const val AI_UNIT_FLAG_4 = 1 shl 4
        const val AI_UNIT_FLAG_5 = 1 shl 5
        const val AI_UNIT_FLAG_6 = 1 shl 6
        const val AI_CONFIG_HEAL_MASK = 0x7
        const val ITEM_STAFF_SILENCE = 0x51
        const val ITEM_STAFF_SLEEP = 0x52
        const val ITEM_STAFF_BERSERK = 0x53

        /** `gAI3HealingThresholdTable`: `{exitThreshold, enterThreshold}` pairs. */
        const val HEAL_THRESHOLD_TABLE = 0x085A8390

        /** `US_HIDDEN | US_DEAD | US_BIT16`. */
        const val SKIP_STATES = 0x1 or 0x4 or (1 shl 16)

        val FACTION_UNIT_COUNT = intArrayOf(62, 20, 50)
    }
}
