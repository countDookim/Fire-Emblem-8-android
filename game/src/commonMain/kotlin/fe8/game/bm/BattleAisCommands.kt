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

    /** `BanimDefaultModeConfig[index]` (ROM table, 4 bytes per round type). */
    public fun defaultModeConfig(index: Int): Int = unported("BanimDefaultModeConfig")

    /** `BanimDefaultStandingTypes[gEkrDistanceType]`. */
    public fun defaultStandingType(): Int = unported("BanimDefaultStandingTypes")

    /** Address of `BanimScr_DefaultAnim`. */
    public fun defaultAnimScript(): Int = unported("BanimScr_DefaultAnim")

    /** `gpBanimModesLeft[frame]` (pos 0) or `gpBanimModesRight[frame]`: offset of a mode. */
    public fun banimModeOffset(pos: Int, frame: Int): Int = unported("gpBanimModes")

    /** Address of `gBanimScrLeft` (pos 0) or `gBanimScrRight` (pos 1). */
    public fun banimScriptBase(pos: Int): Int = unported("gBanimScr")

    /** `gBanimOaml + pos * 0x5800 / 4`, as a GBA address. */
    public fun spriteDataPoolBase(pos: Int): Int = unported("gBanimOaml")

    /** The 32 bit word at a GBA address (ROM or RAM). */
    public fun scriptWord(address: Int): Int = unported("script memory")

    /** `AnimSort()`. */
    public fun sortAnims(): Unit = unported("AnimSort")

    /** `EkrDragonBodyAnimeSet54(anim)`. */
    public fun dragonBodyAnimeSet54(anim: Anim): Unit = unported("EkrDragonBodyAnimeSet54")

    /** `NewEkrChienCHR(anim)`. */
    public fun newEkrChienChr(anim: Anim): Unit = unported("NewEkrChienCHR")

    /** `GetEfxHpChangeType(anim)` (`EFX_HPT_*`). */
    public fun efxHpChangeType(anim: Anim): Int = unported("GetEfxHpChangeType")

    /** `NewEfxChillEffect(anim)`. */
    public fun newEfxChillEffect(anim: Anim): Unit = unported("NewEfxChillEffect")

    /** `NewEfxNormalEffect(anim)`. */
    public fun newEfxNormalEffect(anim: Anim): Unit = unported("NewEfxNormalEffect")

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
     * Every command id the loop does not port (`C02`, `C05`, `C08..C0C`, `C13`, the
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
     * `0x06` (NEXT_ROUND_START request), `0x1A` (defender marks) and the `0x0D` arm that starts
     * the own next round (f1630) are break-checked; the `0x0D` standing arms, the `type` local of
     * `0x06`/`0x0D` and the `NewEkrChienCHR` call are transcribed only.
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
                        CMD_NEXT_ROUND_TRIGGER -> nextRoundTrigger(anim)
                        CMD_ATTACK_START -> attackStart(anim)
                        CMD_OWN_NEXT_ROUND -> ownNextRound(anim)
                        CMD_NORMAL_HIT_EFFECT -> normalHitEffect(anim)
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
            switchAisFrameData(a, type)
            a.state3 = (a.state3 and clear.inv()) or BIT3_C01_BLOCKING_IN_BATTLE
            a.nextRoundId += 1
        }
        for (k in 0..1) animScrAdvance(anims[pos * 2 + k]!!)
    }

    private fun nextRoundStart(anim: Anim) {
        val type = animNextRoundType(anim)
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
        val type = animNextRoundType(anim)
        if (type != -1) startRound(anim, type, BIT3_NEW_ROUND_START)
    }

    /** `0x06`: the attacker tells the opponent's anims to start their next round. */
    private fun nextRoundTrigger(anim: Anim) {
        val other = otherSide(anim) ?: return
        val type = hooks.battleAnimRoundType(other.nextRoundId * 2 + (1 xor position(other)))
        state.type = type
        if (type != ROUND_INVALID) other.state3 = other.state3 or BIT3_NEXT_ROUND_START
    }

    /** `0x0D`: the anim starts its own next round (or returns to the standing frames). */
    private fun ownNextRound(anim: Anim) {
        var type = animNextRoundType(anim)
        state.type = type
        val pos = position(anim)
        val anim1 = anims[pos * 2]!!
        val anim2 = anims[pos * 2 + 1]!!
        var standing = false
        when (anim.currentRoundType) {
            ROUND_HIT_CLOSE, ROUND_CRIT_CLOSE, ROUND_NONCRIT_FAR, ROUND_CRIT_FAR,
            ROUND_MISS_CLOSE,
            -> {
                if (type != ROUND_INVALID) {
                    anim1.nextRoundId += 1
                    anim2.nextRoundId += 1
                    switchAisFrameData(anim1, type)
                    switchAisFrameData(anim2, type)
                    anim1.state3 = anim1.state3 or BIT3_C01_BLOCKING_IN_BATTLE
                    anim2.state3 = anim2.state3 or BIT3_C01_BLOCKING_IN_BATTLE
                    type = hooks.defaultStandingType()
                    state.type = type
                    val front = hooks.defaultModeConfig(type * 4)
                    val mode = hooks.banimScriptBase(pos) + hooks.banimModeOffset(pos, front)
                    anim1.pImgSheet = hooks.scriptWord(mode + 4)
                    anim1.pSpriteData = anim1.pSpriteDataPool + hooks.scriptWord(mode + 8)
                    anim2.pSpriteData = anim2.pSpriteDataPool + 0x57F0
                    if (forceUnitChg[pos] == 0 && imgSheet[position(anim1)] != anim1.pImgSheet) {
                        hooks.newEkrChienChr(anim1)
                        imgSheet[position(anim1)] = anim1.pImgSheet
                    }
                    animScrAdvance(anim1)
                    animScrAdvance(anim2)
                    state.endLoop = true
                    return
                }
                standing = true
            }
            ROUND_TAKING_MISS_CLOSE, ROUND_TAKING_MISS_FAR -> standing = true
            ROUND_TAKING_HIT_CLOSE, ROUND_STANDING, ROUND_TAKING_HIT_FAR ->
                switchAndAdvance(anim1, anim2, type)
            else -> Unit
        }
        if (standing) {
            type = hooks.defaultStandingType()
            state.type = type
            switchAndAdvance(anim1, anim2, type)
        }
    }

    private fun switchAndAdvance(anim1: Anim, anim2: Anim, type: Int) {
        switchAisFrameData(anim1, type)
        switchAisFrameData(anim2, type)
        animScrAdvance(anim1)
        animScrAdvance(anim2)
        state.endLoop = true
    }

    /** `0x1A`: the normal hit effect on the opponent, on the back layer only. */
    private fun normalHitEffect(anim: Anim) {
        if (layerId(anim) != 0) return
        val other = otherSide(anim)
        if (other != null) {
            other.state3 = other.state3 or BIT3_TAKE_BACK_ENABLE or BIT3_HIT_EFFECT_APPLIED
            val type = hooks.battleAnimRoundType(
                (anim.nextRoundId - 1) * 2 + (1 xor position(anim)),
            )
            state.type = type
            hooks.startHitEffectsDefault(other, isMiss(type))
        }
        if (hooks.efxHpChangeType(other!!) != EFX_HPT_NOT_CHANGE) {
            if (hooks.roundFlag(anim) and ROUND_SILENCER != 0) {
                hooks.newEfxChillEffect(other)
            } else {
                hooks.newEfxNormalEffect(anim)
            }
        }
    }

    /**
     * Ported from banim-ekrmain.c: GetAnimNextRoundType
     *
     * The round type of the next round of [anim]'s side (-1 when there is none).
     */
    public fun animNextRoundType(anim: Anim): Int =
        hooks.battleAnimRoundType(anim.nextRoundId * 2 + position(anim))

    /**
     * Ported from banim-ekrmain.c: SwitchAISFrameDataFromBARoundType
     *
     * Points [anim] at the script of the mode of round type [type] (front or back layer), or at
     * the default anim when the mode is 0xFF. Break-checked: script pointers, layer choice,
     * priority, `state2` mask, `currentRoundType`, queue reset and sprite pool. Mutations that
     * survive (no capture compared field changes): `oam2Base`, the `timer` reset (the next
     * `AnimInterpret` overwrites it), the 0xFF arm `state3 = 0`.
     */
    public fun switchAisFrameData(anim: Anim, type: Int) {
        val front = layerId(anim) != 0
        val frame = hooks.defaultModeConfig(4 * type + if (front) 2 else 0)
        val priority = hooks.defaultModeConfig(4 * type + if (front) 3 else 1)
        val pos = position(anim)
        if (frame != 0xFF) {
            val script = hooks.banimScriptBase(pos) + hooks.banimModeOffset(pos, frame)
            anim.pScrStart = script
            anim.pScrCurrent = script
        } else {
            anim.pScrStart = hooks.defaultAnimScript()
            anim.pScrCurrent = hooks.defaultAnimScript()
            anim.state3 = 0
        }
        anim.drawLayerPriority = priority
        anim.oam2Base = (anim.oam2Base and 0xC00.inv()) or 0x800
        anim.timer = 0
        anim.state2 = anim.state2 and (FRONT_FRAME or POS_RIGHT or 0x0400)
        anim.currentRoundType = type
        anim.commandQueueSize = 0
        anim.pSpriteDataPool = hooks.spriteDataPoolBase(pos)
        hooks.sortAnims()
        hooks.dragonBodyAnimeSet54(anim)
    }

    /**
     * Ported from banim-ekrmain.c: AnimScrAdvance
     *
     * For a standing round type, skips the leading frames of the script up to the first STOP or
     * COMMAND instruction and backs up one frame record. Break-checked: the round type gate, the
     * frame step and the back-up. The default-anim guard is transcribed only (survives).
     */
    public fun animScrAdvance(anim: Anim) {
        if (anim.currentRoundType != ROUND_TAKING_HIT_CLOSE &&
            anim.currentRoundType != ROUND_STANDING &&
            anim.currentRoundType != ROUND_TAKING_HIT_FAR
        ) {
            return
        }
        if (anim.pScrCurrent == hooks.defaultAnimScript()) return
        while (true) {
            val inst = (hooks.scriptWord(anim.pScrCurrent) ushr 24) and 0x3F
            if (inst == INS_STOP || inst == INS_COMMAND) {
                anim.pScrCurrent -= 12
                break
            }
            check(inst == INS_FRAME) { "AnimScrAdvance would not terminate on instruction $inst" }
            anim.pScrCurrent += 12
        }
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
        const val CMD_NEXT_ROUND_TRIGGER = 0x06
        const val CMD_ATTACK_START = 0x07
        const val CMD_OWN_NEXT_ROUND = 0x0D
        const val CMD_NORMAL_HIT_EFFECT = 0x1A
        const val INS_STOP = 0
        const val INS_COMMAND = 5
        const val INS_FRAME = 6
        const val ROUND_INVALID = -1
        const val ROUND_HIT_CLOSE = 0
        const val ROUND_CRIT_CLOSE = 1
        const val ROUND_NONCRIT_FAR = 2
        const val ROUND_CRIT_FAR = 3
        const val ROUND_TAKING_HIT_CLOSE = 6
        const val ROUND_STANDING = 7
        const val ROUND_TAKING_HIT_FAR = 8
        const val ROUND_MISS_CLOSE = 9
        const val ROUND_SILENCER = 0x1000
        const val EFX_HPT_NOT_CHANGE = 2

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
