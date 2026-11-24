package fe8.game.bm

import gbafe.anim.ANIM_BIT2_COMMAND
import gbafe.anim.ANIM_BIT2_FRAME
import gbafe.anim.ANIM_BIT2_STOP
import gbafe.anim.Anim

/**
 * What `BattleAIS_ExecCommands` (src/banim-main.c) leaves to code that is not ported yet or that
 * touches systems outside the command loop (effects, sound, spell animations, graphics
 * registration). Every member of the default implementation fails loudly, so a capture that
 * reaches a gap shows exactly which one. Later tasks (BA-p3c/d/e) replace the stand-ins with
 * ports, and a loaded mod may replace any of them.
 */
public interface BattleAisHooks {
    /** `GetBattleAnimRoundType(index)`: the round type word of `gAnimRoundData`, -1 if none. */
    public fun battleAnimRoundType(index: Int): Int = unported("GetBattleAnimRoundType")

    /** `GetAnimNextRoundType(anim)`. */
    public fun animNextRoundType(anim: Anim): Int = unported("GetAnimNextRoundType")

    /** `SwitchAISFrameDataFromBARoundType(anim, type)`. */
    public fun switchFrameData(anim: Anim, type: Int): Unit =
        unported("SwitchAISFrameDataFromBARoundType")

    /** `AnimScrAdvance(anim)`. */
    public fun animScrAdvance(anim: Anim): Unit = unported("AnimScrAdvance")

    /** `RegisterAISSheetGraphics(anim)`. */
    public fun registerSheetGraphics(anim: Anim): Unit = unported("RegisterAISSheetGraphics")

    /** `CheckEkrHitDone()`. */
    public fun checkEkrHitDone(): Boolean = unported("CheckEkrHitDone")

    /** `Get0201FAC8()`. */
    public fun get0201FAC8(): Int = unported("Get0201FAC8")

    /** `Set0201FAC8(value)`. */
    public fun set0201FAC8(value: Int): Unit = unported("Set0201FAC8")

    /** `GetUnitEfxDebuff(anim)`. */
    public fun unitEfxDebuff(anim: Anim): Int = unported("GetUnitEfxDebuff")

    /** `SetUnitEfxDebuff(anim, value)`. */
    public fun setUnitEfxDebuff(anim: Anim, value: Int): Unit = unported("SetUnitEfxDebuff")

    /** `NewEfxSpecalEffect(anim)` (sic). */
    public fun newEfxSpecialEffect(anim: Anim): Unit = unported("NewEfxSpecalEffect")

    /** `StartBattleAnimHitEffectsDefault(anim, miss)`. */
    public fun startHitEffectsDefault(anim: Anim, miss: Boolean): Unit =
        unported("StartBattleAnimHitEffectsDefault")

    /** `GetRoundFlagByAnim(anim)` (`ANIM_ROUND_*` flag bits). */
    public fun roundFlag(anim: Anim): Int = unported("GetRoundFlagByAnim")

    /** `NewEfxSkillType01BG(anim)`. */
    public fun newEfxSkillType01Bg(anim: Anim): Unit = unported("NewEfxSkillType01BG")

    /** `NewEfxSkillCommonBG(anim, kind)`. */
    public fun newEfxSkillCommonBg(anim: Anim, kind: Int): Unit = unported("NewEfxSkillCommonBG")

    /**
     * Every command id the loop does not port (`C02`, `C05`, `C06`, `C08..C0D`, `C13`, the
     * sound and effect ids, ...), called with the id at the top of the queue. It may change
     * [state] (the `type` local and the `goto end_exec_loop` request); the loop pops the id
     * afterwards unless [ExecState.endLoop] is set.
     */
    public fun command(anim: Anim, id: Int, state: ExecState): Unit = unported("command $id")

    /** The `type` local of the C function and the `goto end_exec_loop` request of a hook. */
    public class ExecState {
        /** The C local `type`; the hook may overwrite it as the C arm does. */
        public var type: Int = 0

        /** True when the arm ends with `goto end_exec_loop` (no pop of the queue). */
        public var endLoop: Boolean = false
    }

    private fun unported(what: String): Nothing =
        throw UnsupportedOperationException("not ported: $what")
}

/**
 * The command executor of the battle animations (src/banim-main.c): `BattleAIS_ExecCommands`.
 *
 * Ported arms (verified at the pre-battle loop and the first melee round of
 * `ram_trace_prologue_attack`, see [execCommands] for the exact coverage): the queue drain loop,
 * `end_exec_loop`, the FRAME arm, `ANIM_CMD_WAIT_01`, `0x03`, `0x04`, `0x07` and the round-start
 * tail. Everything else goes through [hooks]. Script pointers are GBA
 * addresses, so the C `pScrCurrent + 1` (a `u32 *`) is `+ 4` here.
 *
 * @property hooks the stand-ins of the parts that are not ported.
 * @property anims `gAnims[0..3]`: left front/back, right front/back; null is an empty slot.
 */
public class BattleAisCommands(
    private val hooks: BattleAisHooks,
    public val anims: Array<Anim?> = arrayOfNulls(4),
) {
    /** `gCtrlC01Blocking`: 1 while the pre-battle `C01` loop runs. */
    public var ctrlC01Blocking: Int = 0

    /** `gBanimDoneFlag[2]`. */
    public val banimDoneFlag: IntArray = IntArray(2)

    /** `gpImgSheet[2]`: the sheet last registered per side. */
    public val imgSheet: IntArray = IntArray(2)

    /** `gBanimForceUnitChgDebug[2]` (a pointer, 0 is NULL). */
    public val forceUnitChg: IntArray = IntArray(2)

    private val state = BattleAisHooks.ExecState()

    private fun position(anim: Anim): Int = if (anim.state2 and POS_RIGHT == 0) 0 else 1

    private fun layerId(anim: Anim): Int = if (anim.state2 and FRONT_FRAME == 0) 0 else 1

    private fun otherSide(anim: Anim): Anim? = anims[(1 xor position(anim)) * 2]

    private fun isMiss(type: Int): Boolean =
        type == ROUND_TAKING_MISS_CLOSE || type == ROUND_TAKING_MISS_FAR

    /**
     * Ported from banim-main.c: BattleAIS_ExecCommands
     *
     * Runs the queued commands and the FRAME / round-start follow-ups of the four anims.
     * Break-checked against the capture: the drain loop, both `end_exec_loop` clears of the
     * COMMAND and FRAME bits, `ANIM_CMD_WAIT_01` (pre-battle and in-battle release), `0x03`,
     * `0x04` (non-miss) and the NEW_ROUND_START arm. Transcribed only (no capture changes a
     * compared field there): the `0x07` arm (the plain arm does nothing and its mutation
     * survives), its SURE_SHOT, GREAT_SHIELD and PIERCE sub-arms, the `ANIM_CMD_WAIT_04` miss
     * branch, the FRAME arm sheet registration, the STOP gate before the round-start arms, the
     * NEXT_ROUND_START arm, the `gBanimDoneFlag` arm and the 0x08FF low `state2` bits.
     */
    public fun execCommands() {
        for (i in 0 until 4) {
            val anim = anims[i] ?: continue
            var type = anim.state2 and CMD_MASK
            if (type == 0) continue

            if (type and ANIM_BIT2_COMMAND != 0) {
                state.type = type
                exec@ while (anim.commandQueueSize != 0) {
                    val id = anim.commandQueue[anim.commandQueueSize - 1]
                    state.endLoop = false
                    when (id) {
                        0 -> Unit
                        CMD_WAIT_01 -> wait01(anim)
                        CMD_WAIT_03 -> wait03(anim)
                        CMD_WAIT_04 -> wait04(anim)
                        CMD_ATTACK_START -> attackStart(anim)
                        else -> hooks.command(anim, id, state)
                    }
                    if (state.endLoop) break@exec
                    anim.commandQueueSize--
                }
                type = state.type
                anim.state2 = anim.state2 and (ANIM_BIT2_COMMAND or LOW_BITS).inv()
            }

            if (type and ANIM_BIT2_FRAME != 0) {
                if (layerId(anim) == 0) {
                    val pos = position(anim)
                    if (forceUnitChg[pos] == 0 && anim.state3 and BIT3_4000 == 0 &&
                        imgSheet[pos] != anim.pImgSheet
                    ) {
                        hooks.registerSheetGraphics(anim)
                        imgSheet[pos] = anim.pImgSheet
                    }
                }
                anim.state2 = anim.state2 and (ANIM_BIT2_FRAME or LOW_BITS).inv()
            }

            if (type and ANIM_BIT2_STOP == 0 && ctrlC01Blocking != 1) continue

            if (anim.state3 and BIT3_NEXT_ROUND_START != 0) {
                nextRoundStart(anim)
            } else if (anim.state3 and BIT3_NEW_ROUND_START != 0) {
                newRoundStart(anim)
            } else if (layerId(anim) == 0) {
                val pos = position(anim)
                if (hooks.battleAnimRoundType(anim.nextRoundId * 2 + pos) == -1) {
                    banimDoneFlag[pos] = 1
                }
            }
        }
    }

    /** `ANIM_CMD_WAIT_01`: end of anim; the pre-battle loop restarts, the in-battle one holds. */
    private fun wait01(anim: Anim) {
        if (ctrlC01Blocking == 1) {
            anim.pScrCurrent = anim.pScrStart
        } else if (anim.state3 and BIT3_C01_BLOCKING_IN_BATTLE == 0) {
            anim.pScrCurrent += 4
        } else if (anim.state3 and BIT3_HIT_EFFECT_APPLIED != 0) {
            if (hooks.checkEkrHitDone()) {
                anim.state3 = anim.state3 and
                    (BIT3_TAKE_BACK_ENABLE or BIT3_C01_BLOCKING_IN_BATTLE or
                        BIT3_HIT_EFFECT_APPLIED).inv()
                anim.pScrCurrent += 4
                if (hooks.get0201FAC8() != 0) hooks.set0201FAC8(0)
            }
        }
    }

    /** `ANIM_CMD_WAIT_03`: clear debuff flashing, start the legend-weapon shine, wait for it. */
    private fun wait03(anim: Anim) {
        for (a in anims) {
            if (a != null && hooks.unitEfxDebuff(a) and 0xC != 0) hooks.setUnitEfxDebuff(a, 0)
        }
        if (anim.state3 and BIT3_BLOCKING == 0) {
            anim.state3 = anim.state3 or BIT3_BLOCKING
            if (layerId(anim) == 0) hooks.newEfxSpecialEffect(anim)
        }
        if (anim.state3 and BIT3_BLOCKEND != 0) {
            anim.state3 = anim.state3 and (BIT3_BLOCKING or BIT3_BLOCKEND).inv()
            anim.pScrCurrent += 4
        }
    }

    /** `ANIM_CMD_WAIT_04`: the hit effect of a non-miss attack on the other side. */
    private fun wait04(anim: Anim) {
        if (anim.state3 and BIT3_BLOCKING == 0) {
            anim.state3 = anim.state3 or BIT3_BLOCKING or BIT3_BLOCKEND
        }
        if (anim.state3 and BIT3_BLOCKEND != 0) {
            anim.state3 = anim.state3 and (BIT3_BLOCKING or BIT3_BLOCKEND).inv()
            anim.pScrCurrent += 4
            anim.state3 = anim.state3 or BIT3_TAKE_BACK_ENABLE or BIT3_HIT_EFFECT_APPLIED

            val other = otherSide(anim)
            val type = hooks.battleAnimRoundType(
                (anim.nextRoundId - 1) * 2 + (1 xor position(anim)),
            )
            state.type = type
            // Sic: the original starts the default hit effects when the round IS a miss.
            if (isMiss(type) && other != null) {
                other.state3 = other.state3 or BIT3_TAKE_BACK_ENABLE or BIT3_HIT_EFFECT_APPLIED
                if (layerId(anim) == 0) hooks.startHitEffectsDefault(other, isMiss(type))
            }
        }
    }

    /** `0x07`: start of the attack animation, with the skill backgrounds of three round flags. */
    private fun attackStart(anim: Anim) {
        val flag = hooks.roundFlag(anim)
        if (flag and ROUND_SURE_SHOT != 0) {
            if (anim.state3 and BIT3_BLOCKING == 0) {
                anim.state3 = anim.state3 or BIT3_BLOCKING
                if (layerId(anim) == 0) hooks.newEfxSkillType01Bg(anim)
            }
        } else if (flag and ROUND_GREAT_SHIELD != 0) {
            if (anim.state3 and BIT3_BLOCKING == 0) {
                anim.state3 = anim.state3 or BIT3_BLOCKING
                if (layerId(anim) == 0) hooks.newEfxSkillCommonBg(anim, 1)
            }
        } else if (flag and ROUND_PIERCE != 0) {
            if (anim.state3 and BIT3_BLOCKING == 0) {
                anim.state3 = anim.state3 or BIT3_BLOCKING
                if (layerId(anim) == 0) {
                    hooks.newEfxSkillType01Bg(anim)
                    // Sic: `&=` (not `&= ~`), and `state` loses ANIM_BIT_FROZEN.
                    for (k in 2..3) {
                        val a = anims[k]!!
                        a.state3 = a.state3 and BIT3_BLOCKING
                        a.state = a.state and FROZEN.inv()
                    }
                }
            }
        }
        if (anim.state3 and BIT3_BLOCKING == 0) return
        if (anim.state3 and BIT3_BLOCKEND != 0) {
            anim.state3 = anim.state3 and (BIT3_BLOCKING or BIT3_BLOCKEND).inv()
            anim.pScrCurrent += 4
        }
    }

    /** Both anims of [anim]'s side: the switch to [type] and the round counter step. */
    private fun startRound(anim: Anim, type: Int, clear: Int) {
        val pos = position(anim)
        for (k in 0..1) {
            val a = anims[pos * 2 + k]!!
            hooks.switchFrameData(a, type)
            a.state3 = (a.state3 and clear.inv()) or BIT3_C01_BLOCKING_IN_BATTLE
            a.nextRoundId += 1
        }
        for (k in 0..1) hooks.animScrAdvance(anims[pos * 2 + k]!!)
    }

    private fun nextRoundStart(anim: Anim) {
        val type = hooks.animNextRoundType(anim)
        if (type != -1) {
            startRound(anim, type, BIT3_NEXT_ROUND_START)
        } else {
            val pos = position(anim)
            for (k in 0..1) {
                val a = anims[pos * 2 + k]!!
                a.state3 = a.state3 and BIT3_NEXT_ROUND_START.inv()
            }
        }
    }

    private fun newRoundStart(anim: Anim) {
        val type = hooks.animNextRoundType(anim)
        if (type != -1) startRound(anim, type, BIT3_NEW_ROUND_START)
    }

    private companion object {
        const val CMD_MASK = 0xF000
        const val LOW_BITS = 0x08FF
        const val FRONT_FRAME = 1 shl 8
        const val POS_RIGHT = 1 shl 9
        const val FROZEN = 1 shl 3

        const val CMD_WAIT_01 = 0x01
        const val CMD_WAIT_03 = 0x03
        const val CMD_WAIT_04 = 0x04
        const val CMD_ATTACK_START = 0x07

        const val BIT3_TAKE_BACK_ENABLE = 1 shl 0
        const val BIT3_NEXT_ROUND_START = 1 shl 1
        const val BIT3_C01_BLOCKING_IN_BATTLE = 1 shl 2
        const val BIT3_HIT_EFFECT_APPLIED = 1 shl 3
        const val BIT3_BLOCKING = 1 shl 5
        const val BIT3_BLOCKEND = 1 shl 6
        const val BIT3_4000 = 1 shl 14
        const val BIT3_NEW_ROUND_START = 1 shl 15

        const val ROUND_TAKING_MISS_CLOSE = 4
        const val ROUND_TAKING_MISS_FAR = 5
        const val ROUND_PIERCE = 0x0200
        const val ROUND_GREAT_SHIELD = 0x0400
        const val ROUND_SURE_SHOT = 0x0800
    }
}
