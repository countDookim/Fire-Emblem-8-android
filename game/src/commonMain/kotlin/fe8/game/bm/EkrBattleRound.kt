package fe8.game.bm

import gbafe.anim.ANIM_BIT2_STOP
import gbafe.anim.Anim

/**
 * The idle chain of the `ekrBattle` proc from `ekrBattleMain` to the round start
 * (src/banim-ekrbattle.c): `ekrBattleMain`, `ekrBattlePrepareDragonIntro`,
 * `ekrBattleExecDragonIntro`, `ekrBattlePostEkrDragonIntro`, `ekrBattleSetFlashingEffect`,
 * `ekrBattleExecTriangleAtk` and `ekrBattleTriggerNewRoundStart`. The proc itself (script,
 * scheduling) is the caller's; [runIdle] runs the current callback once, the callback is
 * modelled by [idleCb] and the proc fields by [procTimer], [procSide], [procCounter] and
 * [procAnim].
 *
 * @property anims `gAnims[0..3]`: left front/back, right front/back; null is an empty slot.
 * @property valid `gBanimValid[2]`.
 * @property doneFlag `gBanimDoneFlag[2]`, shared with the owner of the command executor.
 * @property env the variables and queries of the callbacks before the round start; only
 *   [runIdle] on those callbacks needs it.
 * @property hooks the effects the callbacks start, not ported here.
 * @property dragonType `gEkrDragonStatusLeft/Right.type` (shared with [EkrBattleInit]).
 */
public class EkrBattleRound(
    private val anims: Array<Anim?>,
    private val valid: BooleanArray,
    private val doneFlag: IntArray,
    private val env: Env? = null,
    private val hooks: Hooks? = null,
    private val dragonType: IntArray = IntArray(2),
) {
    /**
     * Variables and queries the callbacks before the round start read outside the battle
     * animation code. [banimLinkArenaFlag] (`GetBanimLinkArenaFlag`) and [triangleUnitPresent]
     * (`gpEkrTriangleUnits[0] != NULL`) are stand-ins the caller supplies.
     */
    public interface Env {
        /** `gEkrInitialHitSide`. */
        public val initialHitSide: Int

        /** `gEkrInitPosReal`. */
        public val initPosReal: Int

        /** `GetBanimLinkArenaFlag` (stand-in). */
        public val banimLinkArenaFlag: Int

        /** `gpEkrTriangleUnits[0] != NULL` (stand-in). */
        public val triangleUnitPresent: Boolean

        /** `gBattleStats.config & BATTLE_CONFIG_REFRESH`. */
        public val battleConfigRefresh: Boolean
    }

    /** Effects `ekrBattleSetFlashingEffect` starts; their procs are not ported. */
    public interface Hooks {
        /** `NewEfxStatusUnit(anim)`. */
        public fun newEfxStatusUnit(anim: Anim)

        /** `NewEfxWeaponIcon(gBanimEffectiveness[0], gBanimEffectiveness[1])`. */
        public fun newEfxWeaponIcon()

        /** `DisableEfxStatusUnits(anim)`. */
        public fun disableEfxStatusUnits(anim: Anim)

        /** `NewEfxHpBarColorChange(anim)`. */
        public fun newEfxHpBarColorChange(anim: Anim)
    }

    /** The `proc_idleCb` values this part of the proc moves between. */
    public enum class IdleCb {
        /** `ekrBattleMain`. */
        MAIN,

        /** `ekrBattlePrepareDragonIntro`. */
        PREPARE_DRAGON_INTRO,

        /** `ekrBattleExecDragonIntro`. */
        EXEC_DRAGON_INTRO,

        /** `ekrBattlePostEkrDragonIntro`. */
        POST_DRAGON_INTRO,

        /** `ekrBattleSetFlashingEffect`. */
        SET_FLASHING_EFFECT,

        /** `ekrBattleExecTriangleAtk`. */
        EXEC_TRIANGLE_ATK,

        /** `ekrBattleTriggerNewRoundStart`. */
        TRIGGER_NEW_ROUND_START,

        /** `ekrBattle_80503EC` (not ported here; the idle chain after it is BA-p4d). */
        ROUND_START_80503EC,
    }

    /** `ProcEkrBattle.timer` (0x2C). */
    public var procTimer: Int = 0

    /** `ProcEkrBattle.side` (0x44). */
    public var procSide: Int = 0

    /** `ProcEkrBattle.counter` (0x48). */
    public var procCounter: Int = 0

    /** `ProcEkrBattle.is_quote` (0x54). */
    public var procIsQuote: Int = 0

    /** `ProcEkrBattle.unk58` (0x58). */
    public var procUnk58: Int = 0

    /** `ProcEkrBattle.anim` (0x5C). */
    public var procAnim: Anim? = null

    /** `ProcEkrBattle.proc_idleCb`. */
    public var idleCb: IdleCb = IdleCb.MAIN

    /** Runs the current idle callback once, as one proc run of the `ekrBattle` repeat step. */
    public fun runIdle() {
        when (idleCb) {
            IdleCb.MAIN -> main()
            IdleCb.PREPARE_DRAGON_INTRO -> prepareDragonIntro()
            IdleCb.EXEC_DRAGON_INTRO -> execDragonIntro()
            IdleCb.POST_DRAGON_INTRO -> postDragonIntro()
            IdleCb.SET_FLASHING_EFFECT -> setFlashingEffect()
            IdleCb.EXEC_TRIANGLE_ATK -> execTriangleAtk()
            IdleCb.TRIGGER_NEW_ROUND_START -> triggerNewRoundStart()
            IdleCb.ROUND_START_80503EC -> TODO("ekrBattle_80503EC: BA-p4d")
        }
    }

    /**
     * Ported from src/banim-ekrbattle.c: ekrBattleMain
     *
     * Verified (`ram_trace_prologue_attack` f1497..f1499, timer gate and the dragon intro
     * hand-over) for the arm without quote and pre-event. The quote arm
     * (`NewEkrWindowAppear`, `NewEkrNamewinAppear`, `ekrBattle_HandlePreEventMaybe`) is not
     * ported and throws.
     */
    public fun main() {
        if (++procTimer == 0x1F) {
            if (env().banimLinkArenaFlag != 1 && (procIsQuote == 1 || procUnk58 == 1)) {
                TODO("ekrBattleMain: quote arm (BA-p4c follow-up, needs the scripted window)")
            } else {
                idleCb = IdleCb.PREPARE_DRAGON_INTRO
                procTimer = 0
            }
        }
    }

    /** Ported from src/banim-ekrbattle.c: ekrBattlePrepareDragonIntro */
    public fun prepareDragonIntro() {
        procSide = env().initialHitSide
        procCounter = 0
        idleCb = IdleCb.EXEC_DRAGON_INTRO
    }

    /**
     * Ported from src/banim-ekrbattle.c: ekrBattleExecDragonIntro
     *
     * Verified for the anims without a dragon status (types 0) at counter 0, 1, 2. The draco
     * zombie, demon king and Myrrh arms start procs that are not ported and throw.
     */
    public fun execDragonIntro() {
        if (procCounter == 2) {
            idleCb = IdleCb.POST_DRAGON_INTRO
            return
        }

        val left = procSide == POS_L
        val anim = anims[(if (left) POS_L else POS_R) * 2]!!
        procAnim = anim
        val type = dragonType[if (anim.state2 and STATE2_POS_RIGHT != 0) 1 else 0]
        if (type != 0) TODO("ekrBattleExecDragonIntro: dragon arms (type $type) not ported")
        procSide = if (left) POS_R else POS_L

        procCounter++
    }

    /**
     * Ported from src/banim-ekrbattle.c: ekrBattlePostEkrDragonIntro
     *
     * Verified for `gEkrInitialHitSide == gEkrInitPosReal`; the other arm
     * (`NewEfxFarAttackWithDistance`, `ekrBattle_8050290`) is not ported and throws.
     */
    public fun postDragonIntro() {
        val env = env()
        if (env.initialHitSide != env.initPosReal) {
            TODO("ekrBattlePostEkrDragonIntro: far attack arm not ported")
        } else {
            idleCb = IdleCb.SET_FLASHING_EFFECT
        }
    }

    /**
     * Ported from src/banim-ekrbattle.c: ekrBattleSetFlashingEffect
     *
     * Verified for the no-refresh arm (`ram_trace_prologue_attack`: only the idle callback and
     * timer are visible; the effect starts go to [Hooks]). Transcribed only: the refresh arm.
     */
    public fun setFlashingEffect() {
        val hooks = checkNotNull(hooks) { "hooks required" }
        hooks.newEfxStatusUnit(anims[0]!!)
        hooks.newEfxStatusUnit(anims[2]!!)
        hooks.newEfxWeaponIcon()

        if (env().battleConfigRefresh) hooks.disableEfxStatusUnits(anims[0]!!)

        hooks.newEfxHpBarColorChange(anims[0]!!)
        idleCb = IdleCb.EXEC_TRIANGLE_ATK
    }

    /**
     * Ported from src/banim-ekrbattle.c: ekrBattleExecTriangleAtk
     *
     * Verified for the inactive arm (no triangle attack); the active arm
     * (`NewEkrTriangle`, `ekrBattleWaitTriangleIdle`) is not ported and throws.
     */
    public fun execTriangleAtk() {
        if (env().triangleUnitPresent) {
            TODO("ekrBattleExecTriangleAtk: triangle arm not ported")
        } else {
            idleCb = IdleCb.TRIGGER_NEW_ROUND_START
        }
    }

    private fun env(): Env = checkNotNull(env) { "env required" }

    /**
     * Ported from banim-ekrbattle.c: ekrBattleTriggerNewRoundStart
     *
     * Counts [procTimer] up to 0x1E, then queues NEW_ROUND_START and STOP on the anims of both
     * valid sides, clears `gBanimDoneFlag` and hands over to `ekrBattle_80503EC` (not ported here;
     * the idle chain after it is BA-p4d). Verified against `ram_trace_prologue_attack` f1507..f1537
     * (timer gate, state3 of the queued round, the done flag reset, the idle callback), with both
     * sides valid. Transcribed only: the `gBanimValid` false arms, and the STOP bit (it is not
     * visible in the dumps; the test asserts it from the decomp).
     */
    public fun triggerNewRoundStart() {
        if (++procTimer <= 0x1E) return

        if (valid[0]) startRound(anims[0]!!, anims[1]!!)
        if (valid[1]) startRound(anims[2]!!, anims[3]!!)

        doneFlag[0] = 0
        doneFlag[1] = 0
        idleCb = IdleCb.ROUND_START_80503EC
    }

    private fun startRound(front: Anim, back: Anim) {
        for (anim in arrayOf(front, back)) {
            anim.state3 = BIT3_NEW_ROUND_START
            anim.state2 = anim.state2 or ANIM_BIT2_STOP
        }
    }

    private companion object {
        const val BIT3_NEW_ROUND_START = 1 shl 15
        const val POS_L = 0
        const val POS_R = 1
        const val STATE2_POS_RIGHT = 1 shl 9
    }
}
