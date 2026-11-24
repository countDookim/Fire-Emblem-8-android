package fe8.game.bm

import gbafe.anim.ANIM_BIT2_STOP
import gbafe.anim.Anim

/**
 * The round start of the `ekrBattle` proc (src/banim-ekrbattle.c): `ekrBattleTriggerNewRoundStart`.
 * The proc itself (script, scheduling, the idle callback dispatch) is the caller's; the callback
 * is modelled by [idleCb] and the proc timer by [procTimer].
 *
 * @property anims `gAnims[0..3]`: left front/back, right front/back; null is an empty slot.
 * @property valid `gBanimValid[2]`.
 * @property doneFlag `gBanimDoneFlag[2]`, shared with the owner of the command executor.
 */
public class EkrBattleRound(
    private val anims: Array<Anim?>,
    private val valid: BooleanArray,
    private val doneFlag: IntArray,
) {
    /** The `proc_idleCb` values this part of the proc moves between. */
    public enum class IdleCb {
        /** `ekrBattleTriggerNewRoundStart`. */
        TRIGGER_NEW_ROUND_START,

        /** `ekrBattle_80503EC` (not ported here; the idle chain after it is BA-p4d). */
        ROUND_START_80503EC,
    }

    /** `ProcEkrBattle.timer` (0x2C). */
    public var procTimer: Int = 0

    /** `ProcEkrBattle.proc_idleCb`. */
    public var idleCb: IdleCb = IdleCb.TRIGGER_NEW_ROUND_START

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
    }
}
