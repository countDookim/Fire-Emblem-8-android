package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService

/** An AI test of one unit (`s8 (*)(struct Unit *)`, e.g. `AiIsUnitEnemy`). */
public fun interface AiUnitTest {
    /** True when unit [id] passes. */
    public fun run(id: Int): Boolean
}

/** An AI step that takes a unit test (`void (*)(AiUnitFunc)`). */
public fun interface AiTestStep {
    /** Runs the step with [test]. */
    public fun run(test: AiUnitTest)
}

/** An AI step that takes a unit test and reports success (`s8 (*)(AiUnitFunc)`). */
public fun interface AiTestTry {
    /** Runs the step with [test]; true when it did something. */
    public fun run(test: AiUnitTest): Boolean
}

/**
 * The AI script interpreter of src/cp_script.c. A script is a table of 0x10-byte `struct AiScr`
 * entries in ROM, found through `gpAi1Table` / `gpAi2Table` by the unit's `ai1` / `ai2`; the unit's
 * `ai_a_pc` / `ai_b_pc` is the next entry. Each try runs one command.
 *
 * Written: [tryExecScriptA], [tryExecScriptB], [scriptExec], [aiIsUnitEnemy] and the commands 05,
 * 06 and 0E (the ones the Prologue's enemies run). Every other command index goes to
 * [Hooks.unportedCommand].
 */
public class CpScript(
    private val rom: RomView,
    private val units: UnitTable,
    private val aiState: AiState,
    /** `gActiveUnit`. */
    private val active: ActiveUnit,
    /** `NextRN_N`. */
    private val rng: RngService,
) {
    /** Services outside the written commands; a null hook does nothing. */
    public class Hooks {
        /**
         * Stand-in for `AiAttemptOffensiveAction(test)` (cp_battle.c; EP-5): decides the attack
         * on a unit that passes `test` by calling [CpDecide.aiSetDecision].
         */
        public var attemptOffensiveAction: AiTestStep? = null

        /**
         * Stand-in for `AiTryDoStaff(test)` (cp_staff.c; EP-5): true when a staff was used. Null
         * counts as false (no staff).
         */
        public var tryDoStaff: AiTestTry? = null

        /**
         * Stand-in for a command this class does not write (EP-12): called with the command
         * index (`AI_CMD_*`: 00..04, 07..0D, 0F..1B, and 05 with an ignore list, whose
         * predicates `AiIsUnitEnemyOrInScrList` and `AiIsUnitEnemyAndNotInScrList` are not
         * written either). Null does nothing: the script stays on that command.
         */
        public var unportedCommand: AiIntStep? = null
    }

    /** Services outside the written commands. */
    public val hooks: Hooks = Hooks()

    /** `gAiScriptEnded`: false while a command asks for another command in the same decision. */
    private var scriptEnded = true

    /** `gAiScriptKind`: [KIND_AI1] or [KIND_AI2]. */
    private var scriptKind = KIND_AI1

    /** `gpAiScriptCurrent`: the ROM address of the current `struct AiScr`. */
    private var current = 0

    /** `AiIsUnitEnemy` as an [AiUnitTest], the value passed to the AI actions. */
    public val isUnitEnemy: AiUnitTest = AiUnitTest { aiIsUnitEnemy(it) }

    /**
     * Ported from src/cp_script.c: AiTryExecScriptA
     *
     * Points the interpreter at the active unit's script A (`gpAi1Table[0][ai1]` plus `ai_a_pc`
     * entries), runs one command and returns whether the script ended (a command that jumps
     * returns false). Verified: the table, the entry at `ai_a_pc` 0 and the result; not seen: an
     * entry after the first (every captured try starts at 0, so the 0x10 stride is not checked).
     */
    public fun tryExecScriptA(): Boolean {
        val id = active.unit
        current = rom.u32(rom.u32(GP_AI1_TABLE) + 4 * units.ai1(id)) + SCR_SIZE * units.aiAPc(id)
        scriptEnded = true
        scriptKind = KIND_AI1
        scriptExec()
        return scriptEnded
    }

    /**
     * Ported from src/cp_script.c: AiTryExecScriptB
     *
     * As [tryExecScriptA] for script B (`gpAi2Table[0][ai2]`, `ai_b_pc`). Verified: Red 0's
     * script (command 0E at `ai_b_pc` 0); not seen: an entry after the first.
     */
    public fun tryExecScriptB(): Boolean {
        val id = active.unit
        current = rom.u32(rom.u32(GP_AI2_TABLE) + 4 * units.ai2(id)) + SCR_SIZE * units.aiBPc(id)
        scriptEnded = true
        scriptKind = KIND_AI2
        scriptExec()
        return scriptEnded
    }

    /**
     * Ported from src/cp_script.c: AiScript_Exec
     *
     * Runs the command at [current] on the active unit's `ai_a_pc` or `ai_b_pc` (by the script
     * kind): an entry with a command index of 0x1D or more is replaced by the fallback script's
     * first entry, `unk7E` is set from the entry's byte 2, then the command runs. Verified: the
     * dispatch of 05, 06 and 0E, and `unk7E` holding the entry's 0xFF (a write of another value
     * fails; leaving the write out does not, `AiPhaseInit` set 0xFF already); not seen: the
     * fallback replacement.
     */
    public fun scriptExec() {
        if (rom.u8(current) >= AI_CMD_COUNT) {
            current = if (scriptKind == KIND_AI1) FALLBACK_AI1 else FALLBACK_AI2
        }
        aiState.unk7E = rom.u8(current + 2)
        when (val cmd = rom.u8(current)) {
            AI_CMD_ACTION -> cmdDoStandardAction()
            AI_CMD_NOP -> cmdDoNothing()
            AI_CMD_NOP_0E -> cmdDoNothing()
            else -> hooks.unportedCommand?.run(cmd)
        }
    }

    /**
     * Transcribed from src/cp_script.c: AiIsUnitEnemy (not verified: it is only called on the
     * captured target of each attacker, and no captured value shows its result; an allied unit
     * (false) is not seen)
     *
     * True when unit [id] is not allied with the active unit.
     */
    public fun aiIsUnitEnemy(id: Int): Boolean =
        !BmPhase.areUnitsAllied(units.index(active.unit) and 0xFF, units.index(id) and 0xFF)

    /**
     * Ported from src/cp_script.c: AiScriptCmd_05_DoStandardAction
     *
     * Draws `NextRN_N(100)`; when it is at most the entry's chance (byte 1): with no ignore list
     * (word at 8 is 0) tries a staff, then [Hooks.attemptOffensiveAction], else (the list
     * predicates, EP-12) [Hooks.unportedCommand]; otherwise sets `decideState` to 4. Then
     * advances the script. Verified: the draw (RNG state), the no-list branch and the advance;
     * not seen: a failed chance roll (every captured chance is 100) and the ignore list.
     */
    private fun cmdDoStandardAction() {
        val roll = rng.nextN(100)
        if (roll <= rom.u8(current + 1)) {
            if (rom.u32(current + 8) == 0) {
                if (hooks.tryDoStaff?.run(isUnitEnemy) != true) {
                    hooks.attemptOffensiveAction?.run(isUnitEnemy)
                }
            } else {
                hooks.unportedCommand?.run(AI_CMD_ACTION)
            }
        } else {
            aiState.decideState = 4
        }
        advancePc()
    }

    /**
     * Ported from src/cp_script.c: AiScriptCmd_06_DoNothing, AiScriptCmd_0E_DoNothing
     *
     * Advances the script by one entry (both commands are the same code).
     */
    private fun cmdDoNothing() {
        advancePc()
    }

    /** `(*pc)++`: the active unit's `ai_a_pc` or `ai_b_pc` by the script kind (u8). */
    private fun advancePc() {
        val id = active.unit
        if (scriptKind == KIND_AI1) {
            units.setAiAPc(id, units.aiAPc(id) + 1)
        } else {
            units.setAiBPc(id, units.aiBPc(id) + 1)
        }
    }

    /** Constants of cp_script.c and the ROM tables it reads. */
    public companion object {
        /** `AI_CMD_ACTION`. */
        public const val AI_CMD_ACTION: Int = 0x05

        /** `AI_CMD_NOP`. */
        public const val AI_CMD_NOP: Int = 0x06

        /** `AI_CMD_NOP_0E`. */
        public const val AI_CMD_NOP_0E: Int = 0x0E

        /** `AI_CMD_COUNT`. */
        public const val AI_CMD_COUNT: Int = 0x1D

        private const val KIND_AI1 = 0
        private const val KIND_AI2 = 1
        private const val SCR_SIZE = 0x10
        private const val GP_AI2_TABLE = 0x085A91D8
        private const val GP_AI1_TABLE = 0x085A91E4
        private const val FALLBACK_AI1 = 0x085A812C
        private const val FALLBACK_AI2 = 0x085A813C
    }
}
