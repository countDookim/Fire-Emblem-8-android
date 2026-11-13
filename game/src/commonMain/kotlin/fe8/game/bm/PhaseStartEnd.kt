package fe8.game.bm

import fe8.game.bm.StructCodec.u8
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end

/**
 * The unit housekeeping at the end and the start of a phase: `ClearActiveFactionGrayedStates`
 * and `TickActiveFactionTurn` of `src/bmunit.c`, and the `_Init` functions of the four blocking
 * displays of `src/bmusailment.c` that `gProc_BMapMain` starts after the phase banner (status
 * decay, terrain heal, poison, Gorgon egg hatch).
 *
 * In the Prologue captures every display finds an empty target list and ends at once, so only
 * the `_Init` of each is here; the rest of each display script (`MassEffectDisplay_*`,
 * `*_Display`, `*_Next`) is a stand-in (M6-D) that ends the proc, as are the
 * `Make*TargetList` calls of `src/bmtarget.c` (they leave the list empty) and the pieces behind
 * [Hooks]. Nothing allocates after construction.
 */
public class PhaseStartEnd(
    private val engine: ProcEngine,
    private val units: UnitTable,
    private val playSt: PlaySt,
    private val targets: SelectTargets,
    /** The calls that are not ported; every one does nothing by default. */
    public val hooks: Hooks = Hooks(),
) {
    /** A call that takes a unit's character id. */
    public fun interface PidAction {
        /** Runs the call for [pid]. */
        public fun run(pid: Int)
    }

    /** A call that takes the faction (0x00, 0x40 or 0x80). */
    public fun interface FactionAction {
        /** Runs the call for [faction]. */
        public fun run(faction: Int)
    }

    /** The calls this class makes that are not ported yet. */
    public class Hooks {
        /**
         * `PidStatsSubFavval08(pid)` (bmsave-bwl.c, the save statistics): lowers the favour of
         * a character. Default: does nothing. `Fe8Boot` leaves the default until the
         * statistics are kept.
         */
        public var pidStatsSubFavval08: PidAction = PidAction { }

        /**
         * `RenderBmMapOnBg2`, `RefreshEntityBmMaps`, `RenderBmMap`, `NewBMXFADE(TRUE)` and
         * `RefreshUnitSprites`, the redraw after a torch ran out. Stand-in until M6-D: nothing.
         */
        public var redrawAfterTorch: Runnable = Runnable { }

        /**
         * `MakeTerrainHealTargetList(faction)` (bmtarget.c). Stand-in until M6-D: only
         * `InitTargets(0, 0)`, so the list is empty (set by [PhaseStartEnd]).
         */
        public var makeTerrainHealTargetList: FactionAction? = null

        /**
         * `MakePoisonDamageTargetList(faction)` (bmtarget.c). Stand-in until M6-D: empty list.
         */
        public var makePoisonDamageTargetList: FactionAction? = null

        /**
         * `MakeGorgonEggHatchTargetList(faction)` (bmtarget.c). Stand-in until M6-D: empty list.
         */
        public var makeGorgonEggHatchTargetList: FactionAction? = null

        /** `sub_8026414(4)` (bmtarget.c): the defeat statistics of the poison victims. */
        public var preparePoisonDefeats: Runnable = Runnable { }
    }

    private val emptyList = FactionAction { targets.initTargets(0, 0) }

    private val statusDecayInitFn = ProcFunc { statusDecayDisplayInit(it) }
    private val terrainHealInitFn = ProcFunc { terrainHealDisplayInit(it) }
    private val poisonInitFn = ProcFunc { poisonDamageDisplayInit(it) }
    private val gorgonInitFn = ProcFunc { gorgonEggHatchDisplayInit(it) }

    /**
     * `gProcScr_StatusDecayDisplay`: [statusDecayDisplayInit], then (a stand-in until M6-D for
     * the `MassEffectDisplay_*` / `_Display` / `_Next` loop that no capture runs) the proc ends.
     */
    public val statusDecayDisplay: ProcScript =
        ProcScript("gProcScr_StatusDecayDisplay", listOf(call(statusDecayInitFn), end()))

    /** `gProcScr_TerrainHealDisplay`: [terrainHealDisplayInit], then the stand-in (M6-D) end. */
    public val terrainHealDisplay: ProcScript =
        ProcScript("gProcScr_TerrainHealDisplay", listOf(call(terrainHealInitFn), end()))

    /** `gProcScr_PoisonDamageDisplay`: [poisonDamageDisplayInit], then the stand-in (M6-D) end. */
    public val poisonDamageDisplay: ProcScript =
        ProcScript("gProcScr_PoisonDamageDisplay", listOf(call(poisonInitFn), end()))

    /** `gProcScr_GorgonEggHatchDisplay`: [gorgonEggHatchDisplayInit], stand-in end (M6-D). */
    public val gorgonEggHatchDisplay: ProcScript =
        ProcScript("gProcScr_GorgonEggHatchDisplay", listOf(call(gorgonInitFn), end()))

    /**
     * Ported from src/bmunit.c: ClearActiveFactionGrayedStates
     *
     * Verified on the Prologue enemy phase: the player-faction branch (the favour of every
     * deployed unit that is not the supply convoy and not unselectable drops by 8) and the
     * state bits cleared from the red units; the green faction has no units there.
     *
     * For the blue faction, calls [Hooks.pidStatsSubFavval08] for every valid unit that is not
     * `CA_SUPPLY` and has no `US_UNAVAILABLE` / `US_UNSELECTABLE` bit. Then clears
     * `US_UNSELECTABLE`, `US_HAS_MOVED` and `US_HAS_MOVED_AI` of every valid unit of the
     * active faction.
     */
    public fun clearActiveFactionGrayedStates() {
        val faction = playSt.faction
        if (faction == FACTION_BLUE) {
            for (id in 1 until 0x40) {
                if (!units.isValid(id)) continue
                if ((units.cAttributes(id) and CA_SUPPLY) != 0) continue
                val skip = UnitTable.US_UNAVAILABLE or UnitTable.US_UNSELECTABLE
                if ((units.state(id) and skip) != 0) continue
                hooks.pidStatsSubFavval08.run(units.charId(id))
            }
        }
        for (id in faction + 1 until faction + 0x40) {
            if (!units.isValid(id)) continue
            units.setState(id, units.state(id) and CLEARED_BITS.inv())
        }
    }

    /**
     * Transcribed from src/bmunit.c: TickActiveFactionTurn (not verified: no unit in a capture
     * has a barrier, torch or status duration, so the decrements and the redraw never run; the
     * zero guards and the empty target list are seen on the Prologue enemy phase)
     *
     * Empties the target list, then for every valid unit of the active faction that is not
     * `US_UNAVAILABLE` or rescued: counts down the barrier, torch and status durations (a
     * `UNIT_STATUS_RECOVER` status does not count down), adds a target for a status that
     * reached 0, and calls [Hooks.redrawAfterTorch] if a torch ticked.
     */
    public fun tickActiveFactionTurn() {
        targets.initTargets(0, 0)
        var displayMapChange = false
        val faction = playSt.faction
        for (id in faction + 1 until faction + 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and (UnitTable.US_UNAVAILABLE or US_RESCUED)) != 0) continue
            val at = units.unitOffset(id)
            val bytes = units.bytes
            val barrier = u8(bytes, at + 0x31) ushr 4
            if (barrier != 0) setNibble(at + 0x31, 4, barrier - 1)
            val torch = u8(bytes, at + 0x31) and 0xF
            if (torch != 0) {
                setNibble(at + 0x31, 0, torch - 1)
                displayMapChange = true
            }
            val duration = u8(bytes, at + 0x30) ushr 4
            if (duration != 0) {
                val left = if (units.statusIndex(id) != STATUS_RECOVER) duration - 1 else duration
                setNibble(at + 0x30, 4, left)
                if (left == 0) {
                    targets.addTarget(units.xPos(id), units.yPos(id), units.index(id), 0)
                }
            }
        }
        if (displayMapChange) hooks.redrawAfterTorch.run()
    }

    private fun setNibble(offset: Int, shift: Int, value: Int) {
        val bytes = units.bytes
        val old = u8(bytes, offset)
        bytes[offset] = ((old and (0xF shl shift).inv()) or (value shl shift)).toByte()
    }

    /**
     * Ported from src/bmusailment.c: StatusDecayDisplay_Init
     *
     * Ends [proc] if the target list (made by [tickActiveFactionTurn]) is empty. Otherwise the
     * C zeroes two fields of the proc, which only the unported `_Display` / `_Next` read.
     */
    public fun statusDecayDisplayInit(proc: Proc) {
        if (targets.getSelectTargetCount() == 0) engine.end(proc)
    }

    /**
     * Ported from src/bmusailment.c: TerrainHealDisplay_Init
     *
     * Makes the terrain heal target list of the active faction, ends [proc] if it is empty.
     */
    public fun terrainHealDisplayInit(proc: Proc) {
        (hooks.makeTerrainHealTargetList ?: emptyList).run(playSt.faction)
        if (targets.getSelectTargetCount() == 0) engine.end(proc)
    }

    /**
     * Ported from src/bmusailment.c: PoisonDamageDisplay_Init
     *
     * Makes the poison target list of the active faction, runs [Hooks.preparePoisonDefeats],
     * ends [proc] if the list is empty.
     */
    public fun poisonDamageDisplayInit(proc: Proc) {
        (hooks.makePoisonDamageTargetList ?: emptyList).run(playSt.faction)
        hooks.preparePoisonDefeats.run()
        if (targets.getSelectTargetCount() == 0) engine.end(proc)
    }

    /**
     * Ported from src/bmusailment.c: GorgonEggHatchDisplay_Init
     *
     * Makes the Gorgon egg hatch target list of the active faction, ends [proc] if it is empty.
     */
    public fun gorgonEggHatchDisplayInit(proc: Proc) {
        (hooks.makeGorgonEggHatchTargetList ?: emptyList).run(playSt.faction)
        if (targets.getSelectTargetCount() == 0) engine.end(proc)
    }

    private companion object {
        const val FACTION_BLUE = 0x00
        const val US_RESCUED = 1 shl 5
        const val CA_SUPPLY = 1 shl 9
        const val STATUS_RECOVER = 10
        const val CLEARED_BITS = (1 shl 1) or (1 shl 6) or (1 shl 10)
    }
}
