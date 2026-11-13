package fe8.game.bm

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/** `gAiState` (cp_common.h): the fields the berserk phase touches. */
public class AiState {
    /** `units[116]`: the unit ids to decide for, 0 terminated. */
    public val units: ByteArray = ByteArray(UNITS_SIZE)

    /** `unitIt`: index into [units] of the unit being decided. */
    public var unitIt: Int = 0

    /** `orderState` (u8, 0x78). */
    public var orderState: Int = 0

    /** `flags` (u8, 0x7B): `AI_FLAG_*`. */
    public var flags: Int = 0

    /** `unk7E` (u8, 0x7E). */
    public var unk7E: Int = 0

    /** `specialItemFlags` (u32, 0x80). */
    public var specialItemFlags: Int = 0

    /** `unk84` (u8, 0x84). */
    public var unk84: Int = 0

    /** `cmd_result[8]` (0x86). */
    public val cmdResult: ByteArray = ByteArray(8)

    /** Layout constants. */
    public companion object {
        /** Size of `units`. */
        public const val UNITS_SIZE: Int = 116
    }
}

/**
 * The berserk part of the AI phase (src/cp_phase.c, src/cp_order.c): `gProcScr_BerserkCpPhase`
 * (ROM 0x085A7F30) and `gProcScr_BerserkCpOrder` (0x085A7F74), which BMapMain starts after every
 * phase. The enemy and ally phase (`gProcScr_CpPhase`, `gProcScr_CpOrder`) are here too. The AI
 * decision (`gProcScr_CpDecide`, EP-3a) is a stand-in that ends at once; [Hooks] stand in for the
 * rest.
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
) {
    /** Services outside the ported files. */
    public class Hooks {
        /** `UpdateAllPhaseHealingAIStatus()` (cp_utility.c, not ported). */
        public var updateAllPhaseHealingAiStatus: () -> Unit = {}

        /** `SetupUnitInventoryAIFlags()` (cp_utility.c, not ported). */
        public var setupUnitInventoryAiFlags: () -> Unit = {}

        /**
         * `AiDecideMainFunc = AiDecideMain; Proc_StartBlocking(gProcScr_CpDecide, proc)` (cp_decide,
         * M6-D): the decision loop for [AiState.units], blocking the given proc.
         */
        public var startCpDecide: ((parent: Proc) -> Unit)? = null
    }

    /** Stand-in until EP-3a for `gProcScr_CpDecide`: ends at once, no unit decides. */
    private val cpDecideStandIn = ProcScript("gProcScr_CpDecide", listOf(end()))

    /** Services outside the ported files. */
    public val hooks: Hooks = Hooks()

    private val priorities = IntArray(AiState.UNITS_SIZE)
    private val items = ItemInfo(rom)

    /** `gAiState`. */
    public val aiState: AiState = AiState()

    private val berserkInitFn = ProcFunc { aiPhaseBerserkInit(it) }
    private val cleanupFn = ProcFunc { aiPhaseCleanup() }
    private val orderInitFn = ProcFunc { cpOrderBerserkInit(it) }
    private val orderEndFn = ProcFunc { cpOrderFuncEnd(it) }
    private val phaseInitFn = ProcFunc { aiPhaseInit(it) }
    private val orderMainFn = ProcFunc { cpOrderMain(it) }

    /** `gProcScr_CpOrder` (cp_order.c). */
    public val orderScript: ProcScript = ProcScript(
        "gProcScr_CpOrder",
        listOf(
            name("E_CPORDER"),
            repeat(orderMainFn),
            end(),
        ),
    )

    /** `gProcScr_CpPhase` (cp_phase.c). */
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

    /** `gProcScr_BerserkCpOrder` (cp_order.c). */
    public val berserkOrderScript: ProcScript = ProcScript(
        "gProcScr_BerserkCpOrder",
        listOf(
            name("E_BSKORDER"),
            call(orderInitFn),
            repeat(orderEndFn),
            end(),
        ),
    )

    /** `gProcScr_BerserkCpPhase` (cp_phase.c). */
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
     * Resets [aiState] for a berserk phase (flags, `unk7E`, the command results, the chapter's
     * special item flags), refreshes the healing and inventory AI statuses through [Hooks] and
     * starts the berserk order blocking [proc].
     */
    public fun aiPhaseBerserkInit(proc: Proc) {
        aiState.flags = AI_FLAG_BERSERKED
        aiState.unk7E = 0xFF

        aiState.cmdResult.fill(0)

        aiState.specialItemFlags = rom.u32(AI_ITEM_CONFIG_TABLE + 4 * playSt.chapterIndex)

        hooks.updateAllPhaseHealingAiStatus()
        hooks.setupUnitInventoryAiFlags()

        engine.startBlocking(berserkOrderScript, proc)
    }

    /**
     * Ported from src/cp_phase.c: AiPhaseInit
     *
     * Resets [aiState] for an enemy or ally phase (flags `AI_FLAG_0`, `unk7E`, `orderState`, the
     * command results, the chapter's special item flags, `unk84`), refreshes the healing and
     * inventory AI statuses through [Hooks] and starts [orderScript] blocking [proc]. Verified:
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

        hooks.updateAllPhaseHealingAiStatus()
        hooks.setupUnitInventoryAiFlags()

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
     * [AiState.unitIt] at its start and starts the decision loop ([Hooks.startCpDecide]) blocking
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
     * 72 if the unit can use a staff, else 40 if it can wield a ranged weapon, else 20 for a
     * melee weapon, else 87.
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
     * Movement, minus 149 for a dancer or player, else plus the leader's character id times 256
     * and 60 for a thief, 87 for a lord or the leader itself, or the battle priority.
     */
    public fun getUnitAiPriority(id: Int): Int {
        var priority = units.movement(id)
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
     * Fills [AiState.units] with the ids of the current faction's units that can act (not asleep,
     * berserk, hidden, unselectable, dead, rescued or already moved by the AI) and the priority
     * of each; returns the count. Verified: the list and the valid check; no unit in the capture
     * is skipped by status or state, and the 0 terminator is written by [cpOrderFuncBeginDecide].
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
     * Bubble sort of [AiState.units] by the priority, unsigned, lowest first; equal priorities
     * keep their order.
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
     * Collects the current faction's units that are berserk and still able to act into
     * [AiState.units]; if there are any, starts the decision loop ([Hooks.startCpDecide]) blocking
     * [proc]. The non-empty case is (not verified: no capture has a berserk unit).
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

    private fun startCpDecide(parent: Proc) {
        val hook = hooks.startCpDecide
        if (hook != null) hook(parent) else engine.startBlocking(cpDecideStandIn, parent)
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

        /** `AI_FLAG_0`. */
        public const val AI_FLAG_0: Int = 1

        /** `AI_FLAG_BERSERKED`. */
        public const val AI_FLAG_BERSERKED: Int = 1 shl 2

        private const val UNIT_ITEM_COUNT = 5
        private const val AI_UNIT_FLAG_0 = 1
        private const val CA_STEAL = 1 shl 2
        private const val CA_DANCE = 1 shl 4
        private const val CA_PLAY = 1 shl 5
        private const val CA_LORD = 1 shl 13

        private val FACTION_UNIT_COUNT = intArrayOf(62, 20, 50)

        /** `US_HIDDEN | US_UNSELECTABLE | US_DEAD | US_RESCUED | US_HAS_MOVED_AI`. */
        private const val SKIP_STATES = 0x1 or 0x2 or 0x4 or 0x20 or 0x400
    }
}
