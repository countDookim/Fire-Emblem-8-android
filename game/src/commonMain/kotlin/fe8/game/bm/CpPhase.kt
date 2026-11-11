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
 * phase. The AI decision (`gProcScr_CpDecide`) and the enemy phase proper are M6-D: [Hooks]
 * stand in for them.
 */
public class CpPhase(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val playSt: PlaySt,
    private val units: UnitTable,
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
        public var startCpDecide: (parent: Proc) -> Unit = {
            error("gProcScr_CpDecide is not ported (M6-D)")
        }
    }

    /** Services outside the ported files. */
    public val hooks: Hooks = Hooks()

    /** `gAiState`. */
    public val aiState: AiState = AiState()

    private val berserkInitFn = ProcFunc { aiPhaseBerserkInit(it) }
    private val cleanupFn = ProcFunc { aiPhaseCleanup() }
    private val orderInitFn = ProcFunc { cpOrderBerserkInit(it) }
    private val orderEndFn = ProcFunc { cpOrderFuncEnd(it) }

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

            hooks.startCpDecide(proc)
        }
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

        /** `AI_FLAG_BERSERKED`. */
        public const val AI_FLAG_BERSERKED: Int = 1 shl 2

        private val FACTION_UNIT_COUNT = intArrayOf(62, 20, 50)

        /** `US_HIDDEN | US_UNSELECTABLE | US_DEAD | US_RESCUED | US_HAS_MOVED_AI`. */
        private const val SKIP_STATES = 0x1 or 0x2 or 0x4 or 0x20 or 0x400
    }
}
