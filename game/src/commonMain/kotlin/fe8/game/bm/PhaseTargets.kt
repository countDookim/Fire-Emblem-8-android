package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService

/**
 * The target lists the start-of-phase displays work from (`src/bmtarget.c`: terrain heal,
 * poison damage, Gorgon egg hatch, and `sub_8026414`), with the two terrain table lookups of
 * `src/bmmap.c` they use. Every list is made into [targets] (`InitTargets` first, so each call
 * replaces the previous list). The tables are read from the ROM; nothing allocates after
 * construction.
 */
public class PhaseTargets(
    private val rom: RomView,
    private val units: UnitTable,
    private val targets: SelectTargets,
    private val map: BmMap,
    private val traps: MapTraps,
    /** `NextRN_N`. */
    private val rng: RngService,
    /** `GetUnitMaxHp` for the unit with the given id. */
    private val maxHp: (id: Int) -> Int,
    /** The calls this class makes that are not ported; every one does nothing by default. */
    public val hooks: Hooks = Hooks(),
) {
    /** The save statistics calls of [sub8026414]. */
    public class Hooks {
        /**
         * `PidStatsRecordDefeatInfo(pid, 0, cause)` (bmsave-bwl.c): wired in `Fe8Boot` to
         * `PidStats`; default does nothing.
         */
        public var recordDefeatInfo: (pid: Int, cause: Int) -> Unit = { _, _ -> }

        /** `PidStatsRecordLoseData(pid)` (bmsave-bwl.c); default does nothing. */
        public var recordLoseData: (pid: Int) -> Unit = { }
    }

    /**
     * Transcribed from src/bmmap.c: GetTerrainHealAmount (not verified: no unit in a capture
     * stands on a terrain that heals, and the map of the Prologue has none)
     *
     * The percentage of its maximum HP a unit on [terrainId] heals at the start of the phase.
     */
    public fun getTerrainHealAmount(terrainId: Int): Int =
        rom.u8(TERRAIN_TABLE_HEAL_AMOUNT + terrainId).toByte().toInt()

    /**
     * Transcribed from src/bmmap.c: GetTerrainHealsStatus (not verified: no unit in a capture
     * stands on a terrain that heals a status, and the map of the Prologue has none)
     *
     * Whether [terrainId] heals the status of a unit on it (non-zero), as a signed byte.
     */
    public fun getTerrainHealsStatus(terrainId: Int): Int =
        rom.u8(TERRAIN_TABLE_HEALS_STATUS + terrainId).toByte().toInt()

    /**
     * Transcribed from src/bmtarget.c: MakeTerrainHealTargetList (not verified: the captured
     * lists are empty, the Prologue map has no terrain that heals and no unit has a status)
     *
     * For every valid unit of [faction] that is not dead, undeployed, rescued or `US_BIT16`:
     * adds a target with the HP it heals (the terrain percentage of its maximum HP, rounded down)
     * if its terrain heals and it is hurt; then, if the terrain heals statuses and the unit has
     * one, turns `UNIT_STATUS_13` into `UNIT_STATUS_PETRIFY` and adds a target with extra -1.
     */
    public fun makeTerrainHealTargetList(faction: Int) {
        targets.initTargets(0, 0)
        for (id in faction + 1 until faction + 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and SKIPPED_STATES) != 0) continue
            val terrainId = map.terrain[units.yPos(id), units.xPos(id)]
            if (getTerrainHealAmount(terrainId) != 0 && units.currentHp(id) != maxHp(id)) {
                val amount = (getTerrainHealAmount(terrainId) * maxHp(id)) / 100
                targets.addTarget(units.xPos(id), units.yPos(id), units.index(id), amount)
            }
            if (getTerrainHealsStatus(terrainId) == 0) continue
            if (units.statusIndex(id) == UNIT_STATUS_NONE) continue
            if (units.statusIndex(id) == UNIT_STATUS_13) {
                val at = units.unitOffset(id) + OFF_STATUS
                units.bytes[at] = ((units.bytes[at].toInt() and 0xF0) or UNIT_STATUS_PETRIFY).toByte()
            }
            targets.addTarget(units.xPos(id), units.yPos(id), units.index(id), -1)
        }
    }

    /**
     * Transcribed from src/bmtarget.c: MakePoisonDamageTargetList (not verified: no unit in a
     * capture is poisoned, so the damage roll never runs)
     *
     * For every valid, available, not rescued unit of [faction] with `UNIT_STATUS_POISON`: adds a
     * target with `NextRN_N(3) + 1` damage.
     */
    public fun makePoisonDamageTargetList(faction: Int) {
        targets.initTargets(0, 0)
        for (id in faction + 1 until faction + 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and SKIPPED_STATES) != 0) continue
            if (units.statusIndex(id) != UNIT_STATUS_POISON) continue
            targets.addTarget(units.xPos(id), units.yPos(id), units.index(id), rng.nextN(3) + 1)
        }
    }

    /**
     * Transcribed from src/bmtarget.c: MakeGorgonEggHatchTargetList (not verified: no
     * Gorgon egg exists in the Prologue)
     *
     * For every valid, available, not rescued unit of [faction] with `UNIT_STATUS_RECOVER` that
     * stands on a `TRAP_GORGON_EGG`: counts the egg's first-turn delay down (the egg waits while
     * it is above 0), then counts its interval up and, when it is reached, resets it and adds a
     * target with 5 as the HP healed.
     */
    public fun makeGorgonEggHatchTargetList(faction: Int) {
        targets.initTargets(0, 0)
        val damage = 5
        for (id in faction + 1 until faction + 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and SKIPPED_STATES) != 0) continue
            if (units.statusIndex(id) != UNIT_STATUS_RECOVER) continue
            val trap = traps.getTypedTrapAt(
                units.xPos(id), units.yPos(id), MapTraps.TRAP_GORGON_EGG,
            )
            if (trap < 0) continue
            val first = MapTraps.TRAP_EXTDATA_TRAP_TURNFIRST
            val next = MapTraps.TRAP_EXTDATA_TRAP_TURNNEXT
            val counter = MapTraps.TRAP_EXTDATA_TRAP_COUNTER
            val delay = (traps.data(trap, first) and 0xFF) - 1
            if (delay > 0) {
                traps.setData(trap, first, delay)
                traps.setData(trap, counter, traps.data(trap, next))
                continue
            }
            traps.setData(trap, first, 0)
            traps.setData(trap, counter, traps.data(trap, counter) + 1)
            if (traps.data(trap, counter) < traps.data(trap, next)) continue
            traps.setData(trap, counter, 0)
            targets.addTarget(units.xPos(id), units.yPos(id), units.index(id), damage)
        }
    }

    /**
     * Transcribed from src/bmtarget.c: sub_8026414 (not verified: the captured lists are empty,
     * so no target is ever recorded)
     *
     * For every target of the current list whose unit has no more HP than the target's `extra`
     * (the damage): records its defeat with [cause] and its loss in the save statistics.
     */
    public fun sub8026414(cause: Int) {
        val count = targets.getSelectTargetCount()
        for (i in 0 until count) {
            val id = targets.targetUid(i)
            if (units.currentHp(id) <= targets.targetExtra(i)) {
                hooks.recordDefeatInfo(units.charId(id), cause)
                hooks.recordLoseData(units.charId(id))
            }
        }
    }

    private companion object {
        /** `TerrainTable_HealAmount` (src/data_terrains.c). */
        const val TERRAIN_TABLE_HEAL_AMOUNT = 0x0880C744

        /** `TerrainTable_HealsStatus` (src/data_terrains.c). */
        const val TERRAIN_TABLE_HEALS_STATUS = 0x0880C785

        /** `US_DEAD | US_NOT_DEPLOYED | US_RESCUED | US_BIT16`. */
        const val SKIPPED_STATES = UnitTable.US_UNAVAILABLE or (1 shl 5)

        /** `Unit.statusIndex` shares byte 0x30 with the status duration. */
        const val OFF_STATUS = 0x30

        const val UNIT_STATUS_NONE = 0
        const val UNIT_STATUS_POISON = 1
        const val UNIT_STATUS_RECOVER = 10
        const val UNIT_STATUS_PETRIFY = 11
        const val UNIT_STATUS_13 = 13
    }
}
