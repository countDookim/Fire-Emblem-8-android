package fe8.game.bm

import gbafe.anim.Anim

/**
 * The HP-bar change effect of the battle animation (src/banim-efxhpbar.c): `NewEfxHpBar`, the
 * non-death arm of `EfxHpBar_DeclineToDeath`, `CheckEkrHitDone` and `CheckEkrHitNow`.
 *
 * The `efxHPBar` proc is a [ProcEfxHpBar] value; [newEfxHpBar] returns it (the `Proc_Start`) and
 * [declineToDeath] is its `PROC_REPEAT` callback, returning true where C calls `Proc_Break`. The
 * later steps of the proc script (`EfxHpBar_MoveCameraOnEnd`, `EfxHpBar_WaitCameraMove`) are
 * BA-p4b and not here.
 *
 * @param env the queries and the death arm outside this file.
 * @param anims `gAnims[0..3]`: left front/back, right front/back.
 * @param gaugeHp `gEkrGaugeHp[2]` (s16), shared with the HP-bar drawing.
 * @param efxHp `GetEfxHp(index)`: the `gEfxHpLut` entry, see [BanimSetup.getEfxHp].
 */
public class EfxHpBar(
    private val env: Env,
    private val anims: Array<Anim?>,
    private val gaugeHp: IntArray,
    private val efxHp: (Int) -> Int,
) {
    /**
     * Queries and arms [EfxHpBar] does not port.
     *
     * Stand-ins: [roundFlag] (`GetRoundFlagByAnim`, reads `gAnimRoundData`) and [onDeath] (the
     * `next == 0` arm of `EfxHpBar_DeclineToDeath`: `GetBanimLinkArenaFlag`,
     * `CheckBattleDefeatTalk`, `NewEfxDeadEvent`, `NewEfxDead`, `PlayDeathSoundForArena`,
     * `gBanimValid`). The capture never reaches the death arm (BA-i4), so [onDeath] throws
     * until it is ported.
     */
    public interface Env {
        /** `GetRoundFlagByAnim(anim)`: the `ANIM_ROUND_*` flag bits of the anim's round. */
        public fun roundFlag(anim: Anim): Int

        /** The `proc->next == 0` arm of `EfxHpBar_DeclineToDeath`. */
        public fun onDeath(proc: ProcEfxHpBar): Unit =
            throw UnsupportedOperationException("not ported: EfxHpBar death arms")
    }

    /** `struct ProcEfxHpBar` (efxbattle.h): the fields the HP-bar steps use. */
    public class ProcEfxHpBar {
        /** `anim_this` (0x64): the anim that created the bar. */
        public var animThis: Anim? = null

        /** `anim_main_other` (0x5C). */
        public var animMainOther: Anim? = null

        /** `anim_main_this` (0x60): the front anim of the side taking the hit. */
        public var animMainThis: Anim? = null

        /** `timer` (0x2C, s16): counts to 2 between HP steps. */
        public var timer: Int = 0

        /** `cur` (0x2E, s16): the HP shown now. */
        public var cur: Int = 0

        /** `diff` (0x48): -1 or +1 per step. */
        public var diff: Int = 0

        /** `this` (0x4C): the HP before the hit. */
        public var thisHp: Int = 0

        /** `next` (0x50): the HP after the hit. */
        public var next: Int = 0

        /** `timer2` (0x54): counts up to 0x1E. */
        public var timer2: Int = 0

        /** `finished` (0x58). */
        public var finished: Boolean = false
    }

    /** `gEkrHpBarCount`: 1 while an HP-bar proc lives. */
    public var hpBarCount: Int = 0

    /** `gEfxSpellAnimExists`. */
    public var spellAnimExists: Int = 0

    /** `gEfxHpLutOff[2]` (s16): per side, the `gEfxHpLut` round the bar is at. */
    public val hpLutOff: IntArray = IntArray(2)

    /** `gEkrHitNow[2]` (s16): 1 per side while its bar runs. */
    public val hitNow: IntArray = IntArray(2)

    private fun position(anim: Anim): Int = if (anim.state2 and POS_RIGHT == 0) 0 else 1

    /**
     * Ported from banim-efxhpbar.c: CheckEkrHitDone
     *
     * True when no HP bar and no spell animation is running.
     */
    public fun checkEkrHitDone(): Boolean = hpBarCount == 0 && spellAnimExists == 0

    /**
     * Ported from banim-efxhpbar.c: CheckEkrHitNow
     *
     * `gEkrHitNow[pos]`.
     */
    public fun checkEkrHitNow(pos: Int): Int = hitNow[pos]

    /**
     * Ported from banim-efxhpbar.c: NewEfxHpBar
     *
     * Starts the HP-bar proc for the hit [anim] deals. Returns null for the early return (the
     * round has the GREAT_SHIELD flag or a bar already runs); that arm is Transcribed only, the
     * capture never reaches it.
     */
    public fun newEfxHpBar(anim: Anim): ProcEfxHpBar? {
        if ((GREAT_SHIELD and env.roundFlag(anim)) != 0 || hpBarCount != 0) return null
        hpBarCount = 1
        val proc = ProcEfxHpBar()
        proc.animThis = anim
        val left = position(anim) == 0
        proc.animMainOther = anims[if (left) 2 else 0]
        proc.animMainThis = anims[if (left) 0 else 2]
        val side = position(proc.animMainThis!!)
        val offThis = hpLutOff[side]
        val offNext = offThis + 1
        proc.thisHp = efxHp(offThis * 2 + side)
        proc.next = efxHp(offNext * 2 + side)
        proc.diff = if (proc.thisHp > proc.next) -1 else 1
        proc.timer = 0
        proc.cur = proc.thisHp
        proc.timer2 = 0
        proc.finished = false
        hitNow[side] = 1
        return proc
    }

    /**
     * Ported from banim-efxhpbar.c: EfxHpBar_DeclineToDeath
     *
     * One run of the bar's repeat callback. Returns true where C calls `Proc_Break`. The death
     * arm (`next == 0`, through [Env.onDeath]) is Transcribed only: the capture never reaches
     * it.
     */
    public fun declineToDeath(proc: ProcEfxHpBar): Boolean {
        val side = position(proc.animMainThis!!)
        if (!proc.finished) {
            proc.timer += 1
            if (proc.timer == 2) {
                proc.timer = 0
                proc.cur += proc.diff
                gaugeHp[side] += proc.diff
                if (proc.cur == proc.next) proc.finished = true
            }
        }
        if (proc.timer2 == 0x1E && proc.finished) {
            hpLutOff[side] += 1
            hitNow[side] = 0
            if (proc.next == 0) env.onDeath(proc)
            return true
        }
        proc.timer2 += 1
        if (proc.timer2 >= 0x1E) proc.timer2 = 0x1E
        return false
    }

    private companion object {
        const val POS_RIGHT = 1 shl 9
        const val GREAT_SHIELD = 0x0400
    }
}
