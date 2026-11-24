package fe8.game.bm

/**
 * The game state outside the unit arrays that [UnitDeployState] reaches. The defaults do
 * nothing.
 */
public interface UnitDeployStateHooks {
    /** `RefreshEntityBmMaps`: rebuilds the unit and hidden maps from the unit arrays. */
    public fun refreshEntityBmMaps() {}

    /** `EndAllMus`: ends every running MU (map unit, the walking sprite). */
    public fun endAllMus() {}

    /** `StoreUnitWordStructs` (eventscr3.c): saves pid and position of the player units. */
    public fun storeUnitWordStructs() {}
}

/**
 * The player unit state resets that start a battle map chapter: `UpdatePrevDeployStates`
 * (`BMapMain` label 1) and `ResetAllPlayerUnitState`.
 *
 * @param table the unit arrays.
 * @param loader the unit loader (`GetUnitMaxHp`).
 * @param playSt `gPlaySt`.
 * @param hooks the game state around the unit arrays.
 */
public class UnitDeployState(
    private val table: UnitTable,
    private val loader: UnitLoader,
    private val playSt: PlaySt,
    private val hooks: UnitDeployStateHooks = object : UnitDeployStateHooks {},
) {
    /**
     * Partially ported from src/bmunit.c: UpdatePrevDeployStates
     *
     * Verified: `US_BIT21` cleared for undeployed-free units, current HP, status and rescue at
     * `f10297 -> f10298` of PC-cap1 (Chapter1BeginningTailDifferentialTest, Seth and Eirika).
     * Transcribed (not verified: no capture has them): the `US_NOT_DEPLOYED -> US_BIT21` set
     * branch, `US_BIT16 -> US_BIT26`, and the `PLAY_FLAG_PREPSCREEN` branch
     * ([UnitDeployStateHooks.storeUnitWordStructs]).
     *
     * Copies `US_NOT_DEPLOYED` into `US_BIT21` and `US_BIT16` into `US_BIT26` for every valid
     * player unit, saves the unit word structs during the prep screen and resets the player
     * units.
     */
    public fun updatePrevDeployStates() {
        for (id in 1 until FACTION_GREEN) {
            if (!table.isValid(id)) continue
            var state = table.state(id)
            state = if (state and US_NOT_DEPLOYED != 0) {
                state or US_BIT21
            } else {
                state and US_BIT21.inv()
            }
            state = if (state and US_BIT16 != 0) state or US_BIT26 else state and US_BIT26.inv()
            table.setState(id, state)
        }
        if (playSt.chapterStateBits and PLAY_FLAG_PREPSCREEN != 0) hooks.storeUnitWordStructs()
        resetAllPlayerUnitState()
    }

    /**
     * Partially ported from src/eventcall.c: ResetAllPlayerUnitState
     *
     * Verified with [updatePrevDeployStates]: full HP, status cleared, `xPos` -1, hidden and
     * no rescue. Not verified: the kept state bits (the units have none set; an inverted mask
     * survives) and the torch / barrier byte (already 0). The hooks only record in the test.
     *
     * Full HP, no status, no torch or barrier, only the persistent state bits kept, hidden and
     * off the map (`xPos` -1), no rescue.
     */
    public fun resetAllPlayerUnitState() {
        for (id in 1 until FACTION_GREEN) {
            if (!table.isValid(id)) continue
            val o = table.unitOffset(id)
            val max = loader.getUnitMaxHp(o)
            table.bytes[o + OFF_CUR_HP] = max.toByte()
            table.setUnitStatus(id, 0)
            table.bytes[o + OFF_TORCH_BARRIER] = 0
            table.setState(id, (table.state(id) and KEPT_STATE) or US_HIDDEN)
            table.bytes[o + OFF_X_POS] = (-1).toByte()
            table.setRescue(id, 0)
        }
        hooks.refreshEntityBmMaps()
        hooks.endAllMus()
    }

    private companion object {
        const val FACTION_GREEN = 0x40
        const val PLAY_FLAG_PREPSCREEN = 1 shl 4
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val US_NOT_DEPLOYED = 1 shl 3
        const val US_GROWTH_BOOST = 1 shl 13
        const val US_SOLOANIM_1 = 1 shl 14
        const val US_SOLOANIM_2 = 1 shl 15
        const val US_BIT16 = 1 shl 16
        const val US_BIT20 = 1 shl 20
        const val US_BIT21 = 1 shl 21
        const val US_BIT22 = 1 shl 22
        const val US_BIT25 = 1 shl 25
        const val US_BIT26 = 1 shl 26

        /** The state bits `ResetAllPlayerUnitState` keeps. */
        const val KEPT_STATE = US_DEAD or US_NOT_DEPLOYED or US_GROWTH_BOOST or US_SOLOANIM_1 or
            US_SOLOANIM_2 or US_BIT16 or US_BIT20 or US_BIT21 or US_BIT22 or US_BIT25 or US_BIT26

        const val OFF_X_POS = 0x10
        const val OFF_CUR_HP = 0x13
        const val OFF_TORCH_BARRIER = 0x31
    }
}
