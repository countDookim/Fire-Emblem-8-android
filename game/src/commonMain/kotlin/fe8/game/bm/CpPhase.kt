package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/**
 * `struct AiState` / `gAiState` (cp_common.h, defined in cp_phase.c): the state shared by the AI
 * phase, order and decision procs. The decomp's `unk7F` (0x7F) is not modelled: nothing in the
 * decomp reads or writes it.
 */
public class AiState {
    /** `units[116]`: the ids of the units to decide for, in decision order, 0 terminated. */
    public val units: ByteArray = ByteArray(UNITS_SIZE)

    /** `unitIt` (a pointer into `units` in the decomp): index of the unit being decided. */
    public var unitIt: Int = 0

    /** `orderState` (u8, 0x78): the step of `sCpOrderFuncList` that `CpOrderMain` runs next. */
    public var orderState: Int = 0

    /** `decideState` (u8, 0x79): the index into `sDecideFuncList` of [CpDecide.aiDecideMain]. */
    public var decideState: Int = 0

    /** `dangerMapFilled` (s8, 0x7A): set by `AiInitDangerMap` (EP-3b), cleared per unit. */
    public var dangerMapFilled: Boolean = false

    /** `flags` (u8, 0x7B): the `AI_FLAG_*` of the phase being run (cp_common.h). */
    public var flags: Int = 0

    /**
     * `unk7C` (u8, 0x7C): cleared per unit by [CpDecide.cpDecideMain]. The staff AI (cp_staff.c)
     * reads it as a lowest-HP-percentage threshold; its meaning is otherwise unknown.
     */
    public var unk7C: Int = 0

    /**
     * `combatWeightTableId` (u8, 0x7D): the row of `gAiCombatScoreCoefficientTable`, from the
     * `AI_UNIT_CONFIG_COMBATWEIGHT` bits of the unit's `ai_config`.
     */
    public var combatWeightTableId: Int = 0

    /**
     * `unk7E` (u8, 0x7E): 0xFF at the phase start, later set by an AI script command; passed as
     * the fourth argument of `AiTryMoveTowards`. Meaning of that argument unknown.
     */
    public var unk7E: Int = 0

    /**
     * `specialItemFlags` (u32, 0x80): the chapter's entry of `gAiItemConfigTable`; the special
     * item AI (cpextra_80407F0.c) tests its bits.
     */
    public var specialItemFlags: Int = 0

    /** `unk84` (u8, 0x84): only cleared, by [CpPhase.aiPhaseInit]; meaning unknown. */
    public var unk84: Int = 0

    /** `bestBlueMov` (u8, 0x85): the highest Mov of the player units, set at the phase start. */
    public var bestBlueMov: Int = 0

    /** `cmd_result[8]` (0x86): the results of AI script commands, cleared at the phase start. */
    public val cmdResult: ByteArray = ByteArray(8)

    /** Layout constants. */
    public companion object {
        /** Size of `units`. */
        public const val UNITS_SIZE: Int = 116
    }
}

/**
 * The AI phase procs of src/cp_phase.c and src/cp_order.c, and the sub-systems they start.
 *
 * `gProcScr_CpPhase` (ROM 0x085A7F08) runs the enemy and ally phase (`BmMain_StartPhase` starts
 * it); `gProcScr_BerserkCpPhase` (0x085A7F30) is started by BMapMain after each phase for the
 * berserked units. Each starts an order proc (`gProcScr_CpOrder` 0x085A7F5C,
 * `gProcScr_BerserkCpOrder` 0x085A7F74) that picks the units and starts the decision loop
 * [CpDecide]. The attack search ([CpBattle]), the script interpreter ([CpScript]) and the AI
 * flag setup ([CpUtility]) are built here and wired into the decision loop; the flag setup and
 * the attack search exist only when the loader, the map and the entity maps are given.
 */
public class CpPhase(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val playSt: PlaySt,
    private val units: UnitTable,
    /**
     * The unit item rules (`CanUnitUseWeapon`, `CanUnitUseStaff`) that [getUnitBattleAiPriority]
     * needs; null counts every item as unusable.
     */
    private val loader: UnitLoader? = null,
    /** `gBmMap*` of [CpUtility], [CpDecide] and [CpBattle]; with the loader, enables them. */
    map: BmMap? = null,
    /** `MapAddInRange` for [CpUtility]. */
    entityMaps: EntityBmMaps? = null,
    /** `gActiveUnit` and `gActionData`, shared with the player phase. */
    active: ActiveUnit = ActiveUnit(),
    /** The RNG `NextRN_N` of the AI scripts draws from. */
    rng: RngService = RngService(),
) {
    /** `gAiState`. */
    public val aiState: AiState = AiState()

    /**
     * `UpdateAllPhaseHealingAIStatus` and `SetupUnitInventoryAIFlags` (and the helpers of the AI
     * decisions); null (both skipped) when the constructor got no loader, map and entity maps.
     */
    private val utility: CpUtility? =
        if (loader != null && map != null && entityMaps != null) {
            CpUtility(rom, units, playSt, aiState, loader, map, entityMaps)
        } else {
            null
        }

    /** The AI decision loop (`gProcScr_CpDecide`) and its hooks. */
    public val decide: CpDecide = CpDecide(
        engine, units, aiState, active, utility, entityMaps, rom, loader, map,
    )

    /**
     * The AI attack search (cp_battle.c, `AiTryDoStaff` of cp_staff.c) and its hooks (EP-6b and
     * EP-12 stand-ins); null (the script attacks nothing) when the constructor got no loader,
     * map and entity maps.
     */
    public val battle: CpBattle? =
        if (utility != null && loader != null && map != null && entityMaps != null) {
            CpBattle(rom, units, aiState, active, map, entityMaps, loader, decide, utility)
        } else {
            null
        }

    /**
     * The AI script interpreter (cp_script.c); its hooks are the EP-12 stand-ins. Wired here as
     * the `AiTryExecScriptA`/`B` of [decide] and, when [battle] exists, as the hooks of its
     * attack commands.
     */
    public val script: CpScript = CpScript(rom, units, aiState, active, rng).also {
        decide.hooks.tryExecScriptA = AiTry { it.tryExecScriptA() }
        decide.hooks.tryExecScriptB = AiTry { it.tryExecScriptB() }
        val b = battle
        if (b != null) {
            it.hooks.attemptOffensiveAction =
                AiTestStep { test -> b.aiAttemptOffensiveAction(test) }
            it.hooks.tryDoStaff = AiTestTry { test -> b.aiTryDoStaff(test) }
        }
    }

    /** `sUnitPriorityArray` (in `gGenericBuffer`): the sort key of each [AiState.units] entry. */
    private val priorities = IntArray(AiState.UNITS_SIZE)

    /** Item attributes and ranges for [getUnitBattleAiPriority]. */
    private val items = ItemInfo(rom)

    private val berserkInitFn = ProcFunc { aiPhaseBerserkInit(it) }
    private val cleanupFn = ProcFunc { aiPhaseCleanup() }
    private val orderInitFn = ProcFunc { cpOrderBerserkInit(it) }
    private val orderEndFn = ProcFunc { cpOrderFuncEnd(it) }
    private val phaseInitFn = ProcFunc { aiPhaseInit(it) }
    private val orderMainFn = ProcFunc { cpOrderMain(it) }

    /** `gProcScr_CpOrder` (cp_order.c): runs [cpOrderMain] until it breaks. */
    public val orderScript: ProcScript = ProcScript(
        "gProcScr_CpOrder",
        listOf(
            name("E_CPORDER"),
            repeat(orderMainFn),
            end(),
        ),
    )

    /** `gProcScr_CpPhase` (cp_phase.c): [aiPhaseInit], wait for the order proc, clean up. */
    public val phaseScript: ProcScript = ProcScript(
        "gProcScr_CpPhase",
        listOf(
            name("E_CPPHASE"),
            call(phaseInitFn),
            yield(),
            call(cleanupFn),
            end(),
        ),
    )

    /** `gProcScr_BerserkCpOrder` (cp_order.c): [cpOrderBerserkInit], then waits to end. */
    public val berserkOrderScript: ProcScript = ProcScript(
        "gProcScr_BerserkCpOrder",
        listOf(
            name("E_BSKORDER"),
            call(orderInitFn),
            repeat(orderEndFn),
            end(),
        ),
    )

    /** `gProcScr_BerserkCpPhase` (cp_phase.c): [aiPhaseBerserkInit], wait, then clean up. */
    public val berserkPhaseScript: ProcScript = ProcScript(
        "gProcScr_BerserkCpPhase",
        listOf(
            name("E_BSKPHASE"),
            call(berserkInitFn),
            yield(),
            call(cleanupFn),
            end(),
        ),
    )

    /**
     * Ported from src/cp_phase.c: AiPhaseBerserkInit
     *
     * Resets [aiState] for a berserk phase (flags `AI_FLAG_BERSERKED`, `unk7E`, the command
     * results, the chapter's special item flags), refreshes the healing and inventory AI
     * statuses ([CpUtility]) and starts the berserk order blocking [proc].
     */
    public fun aiPhaseBerserkInit(proc: Proc) {
        aiState.flags = AI_FLAG_BERSERKED
        aiState.unk7E = 0xFF

        aiState.cmdResult.fill(0)

        aiState.specialItemFlags = rom.u32(AI_ITEM_CONFIG_TABLE + 4 * playSt.chapterIndex)

        utility?.updateAllPhaseHealingAiStatus()
        utility?.setupUnitInventoryAiFlags()

        engine.startBlocking(berserkOrderScript, proc)
    }

    /**
     * Ported from src/cp_phase.c: AiPhaseInit
     *
     * Resets [aiState] for an enemy or ally phase (flags `AI_FLAG_0`, `unk7E`, `orderState`, the
     * command results, the chapter's special item flags, `unk84`), refreshes the healing and
     * inventory AI statuses ([CpUtility]) and starts [orderScript] blocking [proc]. Verified:
     * flags, `unk7E`, the special item flags, the proc; the resets of `orderState`, the command
     * results and `unk84` are not seen (the capture holds those values already).
     */
    public fun aiPhaseInit(proc: Proc) {
        aiState.flags = AI_FLAG_0
        aiState.unk7E = 0xFF
        aiState.orderState = 0
        aiState.cmdResult.fill(0)
        aiState.specialItemFlags = rom.u32(AI_ITEM_CONFIG_TABLE + 4 * playSt.chapterIndex)
        aiState.unk84 = 0

        utility?.updateAllPhaseHealingAiStatus()
        utility?.setupUnitInventoryAiFlags()

        engine.startBlocking(orderScript, proc)
    }

    /**
     * Ported from src/cp_order.c: CpOrderMain
     *
     * Runs the order step [AiState.orderState] (0 `CpOrderFunc_BeginDecide`, 1
     * `CpOrderFunc_End`) and advances it.
     */
    public fun cpOrderMain(proc: Proc) {
        val state = aiState.orderState++
        if (state == 0) cpOrderFuncBeginDecide(proc) else cpOrderFuncEnd(proc)
    }

    /**
     * Ported from src/cp_order.c: CpOrderFunc_BeginDecide
     *
     * Builds and sorts the unit list; if it is not empty, terminates it, points
     * [AiState.unitIt] at its start and starts the decision loop ([CpDecide.script]) blocking
     * [proc].
     */
    public fun cpOrderFuncBeginDecide(proc: Proc) {
        val count = buildAiUnitList()
        if (count != 0) {
            sortAiUnitList(count)
            aiState.units[count] = 0
            aiState.unitIt = 0
            startCpDecide(proc)
        }
    }

    /**
     * Transcribed from src/cp_order.c: GetUnitBattleAiPriority (not verified: the three Prologue
     * enemies all come out 20 and no mutation of it changes the captured order)
     *
     * Smaller values act first. Looks at the unit's usable items (weapons and staffs it can
     * use): 72 if one is a staff, else 40 if one is a weapon with a maximum range above 1, else
     * 20 if one is a melee weapon, else 87. The values are literals in the decomp.
     */
    public fun getUnitBattleAiPriority(id: Int): Int {
        val o = units.unitOffset(id)
        var ranged = 0
        var melee = 0
        var i = 0
        while (i < UNIT_ITEM_COUNT) {
            val item = units.item(id, i)
            if (item == 0) break
            i++
            val l = loader ?: continue
            if (!l.canUnitUseWeapon(o, item) && !l.canUnitUseStaff(o, item)) continue
            val attributes = items.getItemAttributes(item)
            if (attributes and ItemInfo.IA_STAFF != 0) return 72
            if (attributes and ItemInfo.IA_WEAPON != 0) {
                if (items.getItemMaxRange(item) > 1) ranged++ else melee++
            }
        }
        if (ranged != 0) return 40
        if (melee != 0) return 20
        return 87
    }

    /**
     * Transcribed from src/cp_order.c: GetUnitAiPriority (not verified: the three Prologue enemies
     * all come out 25, so no mutation changes the captured order)
     *
     * The sort key of [sortAiUnitList]; lower acts first. Starts from the unit's Mov. A dancer or
     * bard (`CA_DANCE`, `CA_PLAY`) returns that minus 149. Otherwise, unless the unit has
     * `AI_UNIT_FLAG_0` (healing, or carrying a magic item; see [CpUtility]), the leader's
     * character id times 256 is added, then 60 for a thief (`CA_STEAL`), 87 for a lord
     * (`CA_LORD`) or the leader itself, or else [getUnitBattleAiPriority].
     */
    public fun getUnitAiPriority(id: Int): Int {
        var priority = units.movement(id)
        // `GetUnitLeaderCharId`: player units (deployment ids below 0x40) have no leader.
        val lead = if (units.index(id) and 0xC0 == 0) 0 else units.leaderCharId(id)
        val attributes = units.cAttributes(id)
        if (attributes and (CA_DANCE or CA_PLAY) != 0) return priority - 149
        if (units.aiFlags(id) and AI_UNIT_FLAG_0 == 0) {
            priority += lead shl 8
            if (attributes and CA_STEAL != 0) return priority + 60
            if (units.charId(id) == lead || attributes and CA_LORD != 0) return priority + 87
            priority += getUnitBattleAiPriority(id)
        }
        return priority
    }

    /**
     * Ported from src/cp_order.c: BuildAiUnitList
     *
     * Fills [AiState.units] with the ids of the current faction's units that can act (not asleep
     * or berserk, and none of `US_HIDDEN`, `US_UNSELECTABLE`, `US_DEAD`, `US_RESCUED`,
     * `US_HAS_MOVED_AI`) and [priorities] with the key of each; returns the count.
     * Verified: the list and the valid check; no unit in the capture is skipped by status or
     * state, and the 0 terminator is written by [cpOrderFuncBeginDecide].
     */
    public fun buildAiUnitList(): Int {
        var aiNum = 0
        val faction = playSt.faction
        for (i in 0 until FACTION_UNIT_COUNT[faction shr 6]) {
            val id = faction + i + 1
            if (!units.isValid(id)) continue
            val status = units.statusIndex(id)
            if (status == UnitTable.STATUS_SLEEP) continue
            if (status == UnitTable.STATUS_BERSERK) continue
            if (units.state(id) and SKIP_STATES != 0) continue
            aiState.units[aiNum] = id.toByte()
            priorities[aiNum] = getUnitAiPriority(id)
            aiNum++
        }
        return aiNum
    }

    /**
     * Transcribed from src/cp_order.c: SortAiUnitList (not verified: the priorities are equal, so
     * only the stability is seen, not the direction)
     *
     * Bubble sort of the first [count] entries of [AiState.units] by [priorities] (compared as
     * unsigned, lowest first); equal priorities keep their order.
     */
    public fun sortAiUnitList(count: Int) {
        if (count <= 1) return
        for (i in 0..count - 2) {
            for (j in count - 2 downTo i) {
                if ((priorities[j] xor Int.MIN_VALUE) > (priorities[j + 1] xor Int.MIN_VALUE)) {
                    val p = priorities[j]
                    priorities[j] = priorities[j + 1]
                    priorities[j + 1] = p
                    val u = aiState.units[j]
                    aiState.units[j] = aiState.units[j + 1]
                    aiState.units[j + 1] = u
                }
            }
        }
    }

    /**
     * Ported from src/cp_phase.c: AiPhaseCleanup
     *
     * Clears [AiState.flags].
     */
    public fun aiPhaseCleanup() {
        aiState.flags = 0
    }

    /**
     * Ported from src/cp_order.c: CpOrderBerserkInit
     *
     * Collects the current faction's berserk units that can still act (none of `US_HIDDEN`,
     * `US_UNSELECTABLE`, `US_DEAD`, `US_RESCUED`, `US_HAS_MOVED_AI`) into [AiState.units], in id
     * order, unsorted; if there are any, starts the decision loop ([CpDecide.script]) blocking
     * [proc]. The non-empty case is not verified: no capture has a berserk unit.
     */
    public fun cpOrderBerserkInit(proc: Proc) {
        var aiNum = 0

        val faction = playSt.faction

        for (i in 0 until FACTION_UNIT_COUNT[faction shr 6]) {
            val id = faction + i + 1

            if (!units.isValid(id)) continue

            if (units.statusIndex(id) != UnitTable.STATUS_BERSERK) continue

            if (units.state(id) and SKIP_STATES != 0) continue

            aiState.units[aiNum++] = id.toByte()
        }

        if (aiNum != 0) {
            aiState.units[aiNum] = 0
            aiState.unitIt = 0

            startCpDecide(proc)
        }
    }

    /**
     * The tail shared by `CpOrderFunc_BeginDecide` and `CpOrderBerserkInit`: sets
     * `AiDecideMainFunc` to `AiDecideMain` and starts `gProcScr_CpDecide` blocking [parent].
     */
    private fun startCpDecide(parent: Proc) {
        decide.aiDecideMainFunc = decide.decideMainStep
        engine.startBlocking(decide.script, parent)
    }

    /**
     * Ported from src/cp_order.c: CpOrderFunc_End
     *
     * Breaks the order proc's repeat.
     */
    public fun cpOrderFuncEnd(proc: Proc) {
        engine.breakProc(proc)
    }

    /** ROM addresses and constants. */
    public companion object {
        /** `gAiItemConfigTable` in the ROM (u32 per chapter). */
        public const val AI_ITEM_CONFIG_TABLE: Int = 0x080D8538

        /** `AI_FLAG_0` (cp_common.h): the flags of a normal AI phase; its meaning is unknown. */
        public const val AI_FLAG_0: Int = 1

        /** `AI_FLAG_BERSERKED` (cp_common.h): the phase of berserked units. */
        public const val AI_FLAG_BERSERKED: Int = 1 shl 2

        /** `UNIT_ITEM_COUNT` (bmunit.h). */
        private const val UNIT_ITEM_COUNT = 5

        /** `AI_UNIT_FLAG_0` (cp_common.h), a bit of `Unit::aiFlags`. */
        private const val AI_UNIT_FLAG_0 = 1

        /** `CA_STEAL` (bmunit.h): the class can steal (thief). */
        private const val CA_STEAL = 1 shl 2

        /** `CA_DANCE` (bmunit.h): the class can dance. */
        private const val CA_DANCE = 1 shl 4

        /** `CA_PLAY` (bmunit.h): the class can play (bard). */
        private const val CA_PLAY = 1 shl 5

        /** `CA_LORD` (bmunit.h). */
        private const val CA_LORD = 1 shl 13

        /**
         * The `factionUnitCountLut` of cp_order.c, indexed by `faction shr 6`: the unit slots of
         * the blue (player), green (ally) and red (enemy) faction.
         */
        private val FACTION_UNIT_COUNT = intArrayOf(62, 20, 50)

        /** `US_HIDDEN | US_UNSELECTABLE | US_DEAD | US_RESCUED | US_HAS_MOVED_AI`. */
        private const val SKIP_STATES = 0x1 or 0x2 or 0x4 or 0x20 or 0x400
    }
}
