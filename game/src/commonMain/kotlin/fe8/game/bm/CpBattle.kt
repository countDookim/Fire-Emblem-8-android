package fe8.game.bm

import fe8.game.RomView
import kotlin.math.abs

/** `struct AiCombatSimulationSt` (cp_common.h, 0xC bytes): one candidate attack of the AI. */
public class AiCombatSimulationSt {
    /** `xMove` (u8, 0x00): the tile the attacker stands on. */
    public var xMove: Int = 0

    /** `yMove` (u8, 0x01). */
    public var yMove: Int = 0

    /** `targetId` (u8, 0x02): the `index` of the target unit. */
    public var targetId: Int = 0

    /** `itemSlot` (u16, 0x04): the attacker's weapon slot, 0xFFFF for a ballista. */
    public var itemSlot: Int = 0

    /** `score` (u32, 0x08), as its 32 bits. */
    public var score: Int = 0

    /** Copies every field of [other]. */
    public fun set(other: AiCombatSimulationSt) {
        xMove = other.xMove
        yMove = other.yMove
        targetId = other.targetId
        itemSlot = other.itemSlot
        score = other.score
    }
}


/** `BattleGenerateSimulation(actor, target, x, y, slot)` on unit ids. */
public fun interface AiBattleSimulation {
    /** Simulates unit [actor] on ([x], [y]) with item slot [slot] against [target]. */
    public fun run(actor: Int, target: Int, x: Int, y: Int, slot: Int)
}

/** `BattleGenerateBallistaSimulation(actor, target, x, y)` on unit ids. */
public fun interface AiBallistaSimulation {
    /** Simulates the ballista battle of unit [actor] on ([x], [y]) against [target]. */
    public fun run(actor: Int, target: Int, x: Int, y: Int)
}

/** `AiAttemptBallistaCombat(isEnemy, st)`. */
public fun interface AiBallistaCombat {
    /** Fills [st] with the best ballista attack on a unit passing [test]; true when found. */
    public fun run(test: AiUnitTest, st: AiCombatSimulationSt): Boolean
}

/** A staff of the active unit: `sAiStaffFuncLut[GetAiStaffFuncIndex(item)].func(slot, isEnemy)`. */
public fun interface AiStaffStep {
    /** Runs the AI function of [item] in [slot]; false when the item has none (index -1). */
    public fun run(item: Int, slot: Int, test: AiUnitTest): Boolean
}

/**
 * The AI's attack search of src/cp_battle.c, and `AiTryDoStaff` of src/cp_staff.c: for each weapon
 * of the active unit and each enemy in reach, find the best tile to attack from, simulate that
 * battle and keep the best-scoring attack as the decision ([CpDecide.aiSetDecision]).
 *
 * Written: [aiAttemptOffensiveAction] and the functions the Prologue's attackers run. The score
 * of a simulated battle is written ([aiComputeCombatScore]) with its 8 combat score components
 * and the 3 tile position components. The movement and reach ([CpUtility], EP-7) run for real.
 * The branches the capture never enters (`AiAttemptCombatWithinMovement` and the ballista, steal,
 * magic seal and staff code) are EP-12 stand-ins. All stand-ins are [Hooks].
 *
 * The score formulas contain literals that have no name in the decomp (the caps 40, 20 and 10,
 * the 50 for a kill, the factor 40, the divisor 8); they are kept as literals here too.
 */
public class CpBattle(
    private val rom: RomView,
    private val units: UnitTable,
    private val aiState: AiState,
    /** `gActiveUnit`, `gActiveUnitId`. */
    private val active: ActiveUnit,
    /** `gBmMapMovement`, `gBmMapRange`, `gBmMapUnit`, `gBmMapOther`, `gBmMapSize`. */
    private val map: BmMap,
    /** `MapAddInRange`. */
    private val entityMaps: EntityBmMaps,
    /** `CanUnitUseWeapon`. */
    private val loader: UnitLoader,
    /** `AiSetDecision`, `gAiDecision`. */
    private val decide: CpDecide,
    /** `GenerateUnitMovementMap`, `AiReachesByBirdsEyeDistance`, `AiGetClassRank`. */
    private val util: CpUtility,
    /** `gPlaySt` for chapter turn number. */
    private val playSt: PlaySt,
) {
    /** Item ranges, attributes and the required weapon rank. */
    private val items = ItemInfo(rom)

    /** Services outside the written functions; a null hook does nothing. */
    public class Hooks {
        /**
         * `BattleGenerateSimulation(gActiveUnit, target, x, y, slot)` (bmbattle.c, written in
         * [BattleGenerate]): the wiring passes it. Null simulates nothing.
         */
        public var battleGenerateSimulation: AiBattleSimulation? = null


        /**
         * Stand-in for the `US_IN_BALLISTA` branch of `AiAttemptOffensiveAction`
         * (`GetRiddenBallistaAt`, `TryRemoveUnitFromBallista`; EP-12: never entered): true when a
         * ballista is ridden, to skip the weapons and go on to the ballista combat. When false the
         * C also calls `TryRemoveUnitFromBallista` before the weapons; the port does not yet.
         * Null counts as false.
         */
        public var inBallistaBranch: AiTry? = null

        /**
         * Stand-in for the `CA_STEAL` block of `AiAttemptOffensiveAction` (movement map,
         * `MarkMovementMapEdges`, `AiAttemptStealActionWithinMovement`; EP-12: never entered):
         * true when the function returned 1. Null counts as false.
         */
        public var stealAction: AiTry? = null

        /**
         * Stand-in for `UnitHasMagicRank` and `GenerateMagicSealMap(-1)` (EP-12: never entered):
         * runs after the movement map of every unit that is not in a ballista, a thief whose
         * steal action did not decide included.
         */
        public var magicSealMap: AiStep? = null

        /**
         * Stand-in for `AiAttemptBallistaCombat` (EP-12: never entered), for a `CA_BALLISTAE`
         * unit. Null counts as false.
         */
        public var ballistaCombat: AiBallistaCombat? = null

        /**
         * Stand-in for `BattleGenerateBallistaSimulation` (EP-12: never entered). Null does
         * nothing.
         */
        public var ballistaSimulation: AiBallistaSimulation? = null

        /**
         * Stand-in for the final `TryRemoveUnitFromBallista(gActiveUnit)` of a found attack
         * (bmarch.c; EP-12: never entered). The C calls it for every unit and the function checks
         * `US_IN_BALLISTA` itself; the port calls the hook only for a unit in a ballista.
         */
        public var tryRemoveFromBallista: AiStep? = null

        /**
         * Stand-in for the staff AI functions of `sAiStaffFuncLut` (cp_staff.c; EP-12: never
         * entered). Null counts as false.
         */
        public var staffFunc: AiStaffStep? = null
    }

    /** Services outside the written functions. */
    public val hooks: Hooks = Hooks()

    /** `gBattleActor` and `gBattleTarget`; null (the score is not computed) until wired. */
    public var battleUnits: BmBattle? = null

    /** `tmpResult` of `AiAttemptOffensiveAction`: the candidate being simulated. */
    private val tmpResult = AiCombatSimulationSt()

    /** `finalResult` of `AiAttemptOffensiveAction`: the best candidate so far. */
    private val finalResult = AiCombatSimulationSt()

    /** `finalResult` of the last [aiAttemptOffensiveAction]: the best attack found. */
    public val bestResult: AiCombatSimulationSt
        get() = finalResult

    /** `sCombatScoreCoefficients`: the ROM address of the active table row. */
    private var coefficients = 0

    /**
     * Ported from src/cp_battle.c: AiAttemptOffensiveAction
     *
     * Finds the best attack of the active unit: its movement map (only the tile it stands on
     * with `AI_FLAG_STAY`), then for each usable weapon except `ITEM_NIGHTMARE` and each enemy
     * ([test]) in reach the best tile ([aiSimulateBestBattleAgainstTarget]); the highest score
     * wins (a tie goes to the later one). A found attack becomes the decision
     * (`AI_ACTION_COMBAT`, target tile 0).
     * Verified: the movement and range maps, the candidate tile, the target, the weapon slot
     * and the simulated battle (via the decision and the dump at the score call);
     * not seen: `AI_FLAG_STAY`, a tie, a second weapon, a unit in a ballista, a thief, a magic
     * unit (stand-ins, EP-12).
     */
    public fun aiAttemptOffensiveAction(test: AiUnitTest) {
        val unit = active.unit
        val tmp = tmpResult
        val best = finalResult
        best.targetId = 0
        best.score = 0
        var ballista = false

        if (units.state(unit) and US_IN_BALLISTA != 0) {
            map.bmMapFill(map.movement, -1)
            map.movement[units.yPos(unit), units.xPos(unit)] = 0
            ballista = hooks.inBallistaBranch?.run() == true
        } else {
            if (units.cAttributes(unit) and CA_STEAL != 0 &&
                loader.getUnitItemCount(units.unitOffset(unit)) < UNIT_ITEM_COUNT &&
                hooks.stealAction?.run() == true
            ) {
                return
            }
            if (aiState.flags and AI_FLAG_STAY != 0) {
                map.bmMapFill(map.movement, -1)
                map.movement[units.yPos(unit), units.xPos(unit)] = 0
            } else {
                util.moves.generateUnitMovementMap(unit)
            }
            hooks.magicSealMap?.run()
        }

        if (!ballista) {
            map.workingMap = map.range
            val o = units.unitOffset(unit)
            var i = 0
            while (i < UNIT_ITEM_COUNT) {
                val item = units.item(unit, i)
                if (item == 0) break
                if (item != ITEM_NIGHTMARE && loader.canUnitUseWeapon(o, item)) {
                    tmp.itemSlot = i
                    searchTargets(test, item, i)
                }
                i++
            }
        }

        if (units.cAttributes(unit) and CA_BALLISTAE != 0 &&
            hooks.ballistaCombat?.run(test, tmp) == true &&
            Integer.compareUnsigned(tmp.score, best.score) >= 0
        ) {
            best.set(tmp)
        }

        if (best.score != 0 || best.targetId != 0) {
            decide.aiSetDecision(
                best.xMove, best.yMove, CpDecide.AI_ACTION_COMBAT, best.targetId, best.itemSlot,
                0, 0,
            )
            if (best.itemSlot.toByte().toInt() != -1 && units.state(unit) and US_IN_BALLISTA != 0) {
                hooks.tryRemoveFromBallista?.run()
            }
        }
    }

    /**
     * The unit loop of [aiAttemptOffensiveAction] for the weapon [item] in [slot]: every unit
     * (ids 1 to 0xBF) that is valid, not hidden, dead, rescued or `US_BIT16`, passes [test] and
     * is within [CpUtility.aiReachesByBirdsEyeDistance] is simulated; the best score is kept in
     * [finalResult].
     */
    private fun searchTargets(test: AiUnitTest, item: Int, slot: Int) {
        val unit = active.unit
        var uid = 1
        while (uid < UNIT_ID_END) {
            val skip = !units.isValid(uid) ||
                units.state(uid) and (US_HIDDEN or US_DEAD or US_RESCUED or US_BIT16) != 0 ||
                !test.run(uid) ||
                !util.aiReachesByBirdsEyeDistance(unit, uid, item)
            if (!skip) {
                aiFillReversedAttackRangeMap(uid, item)
                tmpResult.targetId = units.index(uid) and 0xFF  // `unit->index`
                if (aiSimulateBestBattleAgainstTarget(tmpResult) &&
                    Integer.compareUnsigned(tmpResult.score, finalResult.score) >= 0
                ) {
                    finalResult.set(tmpResult)
                    finalResult.itemSlot = slot
                }
            }
            uid++
        }
    }

    /**
     * Ported from src/cp_battle.c: AiFillReversedAttackRangeMap
     *
     * Clears `gBmMapRange` and marks the tiles from which a unit with [item] could attack unit
     * [unit]: the ring between the item's minimum and maximum range around it.
     */
    public fun aiFillReversedAttackRangeMap(unit: Int, item: Int) {
        map.bmMapFill(map.range, 0)
        val x = units.xPos(unit)
        val y = units.yPos(unit)
        entityMaps.mapAddInRange(x, y, items.getItemMaxRange(item), 1)
        entityMaps.mapAddInRange(x, y, items.getItemMinRange(item) - 1, -1)
    }

    /**
     * Ported from src/cp_battle.c: AiSimulateBestBattleAgainstTarget
     *
     * Picks the tile for [st]'s target: of the tiles the active unit can reach (movement value
     * at most `MAP_MOVEMENT_MAX`), from which the target is in range (`gBmMapRange`) and that no
     * other unit holds, the one with the highest
     * [aiGetCombatPositionScore] (the first found, scanning from the bottom right, on a tie).
     * Sets [AiCombatSimulationSt.xMove] and `yMove` and simulates that battle
     * ([aiSimulateBattleAgainstTargetAtPosition]); false when no tile scores. Verified: the
     * reach and range filters and the chosen tile; not seen: the other-unit filter, a tie, a
     * choice between tiles.
     */
    public fun aiSimulateBestBattleAgainstTarget(st: AiCombatSimulationSt): Boolean {
        var score = 0
        var iy = map.sizeY - 1
        while (iy >= 0) {
            var ix = map.sizeX - 1
            while (ix >= 0) {
                val occupant = map.unit[iy, ix]
                val skip = map.movement[iy, ix] > MAP_MOVEMENT_MAX ||
                    map.range[iy, ix] == 0 ||
                    (occupant != 0 && occupant != active.unitId)
                if (!skip) {
                    val scoreTmp = aiGetCombatPositionScore(ix, iy, st)
                    if (Integer.compareUnsigned(scoreTmp, score) > 0) {
                        st.xMove = ix
                        st.yMove = iy
                        score = scoreTmp
                    }
                }
                ix--
            }
            iy--
        }
        if (score == 0) return false
        return aiSimulateBattleAgainstTargetAtPosition(st)
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetCombatPositionScore (not verified: the capture has
     * one candidate tile per target, so no captured value shows the score, only that it is not
     * 0; the three position components are transcribed)
     *
     * The u32 score (as its 32 bits) of standing on ([x], [y]) against [st]'s target: the
     * position components, minus the movement map value of the tile (signed, the movement cost),
     * minus an eighth of the danger map value (`gBmMapOther`), plus 0x7FFFFFFF (a literal in the
     * C); the callers compare the sum as unsigned and treat 0 as "no tile".
     */
    public fun aiGetCombatPositionScore(x: Int, y: Int, st: AiCombatSimulationSt): Int {
        var score = aiGetInRangeCombatPositionScoreComponent(x, y, st.targetId)
        score += aiGetTerrainCombatPositionScoreComponent(x, y)
        score += aiGetFriendZoneCombatPositionScoreComponent(x, y)
        score -= map.movement[y, x].toByte().toInt()
        score -= map.other[y, x] / 8
        score += 0x7FFFFFFF
        return score
    }

    /**
     * Ported from src/cp_battle.c: AiIsBadFight
     *
     * Always false (FE6 used it to check whether Eclipse is worthwhile). Verified through the
     * score call of [aiSimulateBattleAgainstTargetAtPosition]: a true result leaves no decision.
     */
    @Suppress("UNUSED_PARAMETER")
    public fun aiIsBadFight(st: AiCombatSimulationSt): Boolean = false

    /**
     * Ported from src/cp_battle.c: AiSimulateBattleAgainstTargetAtPosition
     *
     * Simulates the battle of the active unit against [st]'s target on (`xMove`, `yMove`) with
     * `itemSlot` (the ballista simulation for 0xFFFF) into the battle units, and unless it is a
     * bad fight scores it ([aiComputeCombatScore]); true when scored.
     */
    public fun aiSimulateBattleAgainstTargetAtPosition(st: AiCombatSimulationSt): Boolean {
        if (st.itemSlot != 0xFFFF) {
            hooks.battleGenerateSimulation?.run(
                active.unit, st.targetId, st.xMove, st.yMove, st.itemSlot,
            )
        } else {
            hooks.ballistaSimulation?.run(active.unit, st.targetId, st.xMove, st.yMove)
        }
        if (!aiIsBadFight(st)) {
            aiComputeCombatScore(st)
            return true
        }
        return false
    }

    /**
     * Ported from src/cp_battle.c: AiComputeCombatScore
     *
     * Selects the row of `gAiCombatScoreCoefficientTable` for [AiState.combatWeightTableId] and
     * sets [AiCombatSimulationSt.score] of [st]: the damage dealt, plus the opponent's low HP,
     * friend zone, target class and turn components, minus the damage taken, danger and own low
     * HP components, clamped to 0 and times 40; a total of 0 gives the damage dealt component
     * itself (so a fight that scores 0 overall still ranks by its damage).
     * Verified: the damage dealt value and the clamp to 0 (the total is below 0 in both captured
     * calls), the opponent's low HP term (enough to leave the clamp when raised); not seen: a
     * positive total, the other terms' signs.
     */
    public fun aiComputeCombatScore(st: AiCombatSimulationSt) {
        coefficients = COEFF_TABLE + COEFF_STRIDE * aiState.combatWeightTableId
        var score = aiGetDamageDealtCombatScoreComponent()
        val backup = score
        score += aiGetOpponentLowHpScoreComponent()
        score += aiGetFriendZoneCombatScoreComponent()
        score += aiGetTargetClassCombatScoreComponent()
        score += aiGetTurnCombatScoreComponent()
        score -= aiGetDamageTakenScoreComponent()
        score -= aiGetDangerScoreComponent()
        score -= aiGetLowHpScoreComponent()
        if (score < 0) score = 0
        st.score = if (score != 0) score * 40 else backup
    }

    /**
     * Ported from src/cp_battle.c: AiGetDamageDealtCombatScoreComponent
     *
     * 50 when the simulated battle leaves the target at 0 HP; else the actor's attack minus
     * the target's defense (not below 0) times the actor's effective hit rate, divided by 100,
     * times `coeffDamageDealt`, at most 40.
     * Verified: the value 6 (3 times 2) in both captured calls; not seen: a kill, the cap, a
     * negative damage.
     */
    public fun aiGetDamageDealtCombatScoreComponent(): Int {
        val b = battleUnits ?: return 0
        if (b.target.bytes[OFF_CUR_HP].toInt() == 0) return 50
        var score = (b.actor.battleAttack - b.target.battleDefense) * b.actor.battleEffectiveHitRate
        if (score < 0) score = 0
        score /= 100
        score = rom.u8(coefficients + COEFF_DAMAGE_DEALT) * score
        if (score > 40) score = 40
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetOpponentLowHpScoreComponent (not verified: its value
     * shows only through a total that stays below 0, and that total includes the EP-6b
     * components the test feeds with hand-computed values; a target above 20 HP is not seen)
     *
     * `coeffLowHpOpponent` times (20 minus the target's HP after the battle), not below 0.
     */
    public fun aiGetOpponentLowHpScoreComponent(): Int {
        val b = battleUnits ?: return 0
        var score = 20 - b.target.bytes[OFF_CUR_HP]
        score = rom.u8(coefficients + COEFF_LOW_HP_OPPONENT) * score
        if (score < 0) score = 0
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetFriendZoneCombatScoreComponent (not verified: no
     * allied unit stands within 3 tiles of the attacker's tile in either captured call, so it
     * is 0 and no captured value shows it)
     *
     * For each tile of `sFriendZoneRangeScoreLut` around the actor's tile that holds an ally
     * (`AreUnitsAllied`): adds the tile's score, 4 minus its distance; times `coeffFriendZone`,
     * at most 10. The table is a diamond of radius 3 except that the C lists (-3, +1) where
     * (-1, -2) belongs (a decomp "BUG!" note); that entry scores 0 and (-1, -2) is never
     * counted. `FRIEND_ZONE_X` and `FRIEND_ZONE_Y` keep the C's order.
     */
    public fun aiGetFriendZoneCombatScoreComponent(): Int {
        val b = battleUnits ?: return 0
        var score = 0
        val ax = b.actor.bytes[OFF_X_POS].toInt() and 0xFF
        val ay = b.actor.bytes[OFF_Y_POS].toInt() and 0xFF
        val actorId = b.actor.bytes[OFF_INDEX].toInt() and 0xFF
        var i = 0
        while (i < FRIEND_ZONE_X.size) {
            val x = (ax + FRIEND_ZONE_X[i]) and 0xFF
            val y = (ay + FRIEND_ZONE_Y[i]) and 0xFF
            i++
            if (x >= map.sizeX || y >= map.sizeY) continue
            val uid = map.unit[y, x]
            if (uid == 0) continue
            if (BmPhase.areUnitsAllied(actorId, uid)) {
                score += 4 - (abs(FRIEND_ZONE_X[i - 1]) + abs(FRIEND_ZONE_Y[i - 1]))
            }
        }
        score = rom.u8(coefficients + COEFF_FRIEND_ZONE) * score
        if (score > 10) score = 10
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetTargetClassCombatScoreComponent (not verified: the
     * rank is 9 for every class, one past the bonus list, so the bonus is the padding byte 0 in
     * both captured calls and no captured value shows it; the rank is [CpUtility.aiGetClassRank])
     *
     * `coeffClassRankBonus` times the entry of `classRankBonuses` for the rank of the target's
     * class, at most 20.
     */
    public fun aiGetTargetClassCombatScoreComponent(): Int {
        val b = battleUnits ?: return 0
        val classPtr = StructCodec.u32(b.target.bytes, OFF_CLASS)
        val rank = util.aiGetClassRank(rom.u8(classPtr + 4)) and 0xFF
        var score = rom.u8(coefficients + COEFF_CLASS_BONUSES + rank)
        score = rom.u8(coefficients + COEFF_CLASS_RANK_BONUS) * score
        if (score > 20) score = 20
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetTurnCombatScoreComponent (not verified: turn
     * component 1 only shifts negative score totals from -2 to -1 for Soldier 1 and -13 to
     * -12 for Soldier 2, so omitting or negating it leaves the total below 0 and returns the
     * backup score 6)
     *
     * `chapterTurnNumber` of `gPlaySt` times `coeffTurnNumber`.
     */
    public fun aiGetTurnCombatScoreComponent(): Int =
        playSt.chapterTurnNumber * rom.u8(coefficients + COEFF_TURN_NUMBER)

    /**
     * Transcribed from src/cp_battle.c: AiGetDamageTakenScoreComponent (not verified: realistic
     * off-by-one mutations leave the score total <= 0, returning backup score 6; unarmed target
     * path -10 is not seen in the capture)
     *
     * -10 when the target has no weapon; else the target's attack minus the actor's defense (not
     * below 0) times the target's effective hit rate, divided by 100, times `coeffDamageTaken`,
     * at most 40.
     */
    public fun aiGetDamageTakenScoreComponent(): Int {
        val b = battleUnits ?: return 0
        if (b.target.weapon == 0) return -10
        var score = (b.target.battleAttack - b.actor.battleDefense) *
            b.target.battleEffectiveHitRate
        if (score < 0) score = 0
        score /= 100
        score = rom.u8(coefficients + COEFF_DAMAGE_TAKEN) * score
        if (score > 40) score = 40
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetDangerScoreComponent (not verified: danger component 2
     * only shifts the score total to -1 / -12, so off-by-one mutations leave the total <= 0 and
     * return backup score 6; cap 20 is not reached)
     *
     * An eighth of the danger map value (`gBmMapOther`) at the actor's tile times `coeffDanger`,
     * at most 20.
     */
    public fun aiGetDangerScoreComponent(): Int {
        val b = battleUnits ?: return 0
        val ax = b.actor.bytes[OFF_X_POS].toInt() and 0xFF
        val ay = b.actor.bytes[OFF_Y_POS].toInt() and 0xFF
        var score = (map.other[ay, ax] / 8) * rom.u8(coefficients + COEFF_DANGER)
        if (score > 20) score = 20
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetLowHpScoreComponent (not verified: low HP component
     * (12 for Soldier 1, 20 for Soldier 2) only shifts the score total to -1 / -12, so off-by-one
     * mutations leave the total <= 0 and return backup score 6; actor HP > 20 is not seen)
     *
     * `coeffLowHpSelf` times (20 minus the actor's HP after the battle), not below 0.
     */
    public fun aiGetLowHpScoreComponent(): Int {
        val b = battleUnits ?: return 0
        var score = 20 - b.actor.bytes[OFF_CUR_HP]
        score = rom.u8(coefficients + COEFF_LOW_HP_SELF) * score
        if (score < 0) score = 0
        return score
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetInRangeCombatPositionScoreComponent (not verified:
     * target weapon range 1 covers candidate distance 1 so the component returns 0, and only one
     * candidate tile (5, 5) reaches the target)
     *
     * 50 if the target unit [target] has an equipped weapon and standing on ([x], [y]) is outside
     * its attack range; else 0.
     */
    public fun aiGetInRangeCombatPositionScoreComponent(x: Int, y: Int, target: Int): Int {
        val dist = abs(units.xPos(target) - x) + abs(units.yPos(target) - y)
        val item = loader.getUnitEquippedWeapon(units.unitOffset(target)) and 0xFFFF
        if (item == 0) return 0
        if (dist > items.getItemMaxRange(item) || dist < items.getItemMinRange(item)) {
            return 50
        }
        return 0
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetTerrainCombatPositionScoreComponent (not verified:
     * only one candidate tile (5, 5) reaches the target, so terrain score differences between
     * tiles are never tested against captured output)
     *
     * The sum of terrain avoid, defense and resistance lookup values for the active unit's class
     * on tile ([x], [y]).
     */
    public fun aiGetTerrainCombatPositionScoreComponent(x: Int, y: Int): Int {
        val terrainId = map.terrain[y, x]
        val classPtr = StructCodec.u32(
            units.bytes, units.unitOffset(active.unit) + UnitTable.OFF_CLASS,
        )
        return rom.s8(rom.u32(classPtr + 0x44) + terrainId) +
            rom.s8(rom.u32(classPtr + 0x48) + terrainId) +
            rom.s8(rom.u32(classPtr + 0x4C) + terrainId)
    }

    /**
     * Transcribed from src/cp_battle.c: AiGetFriendZoneCombatPositionScoreComponent (not verified:
     * only one candidate tile (5, 5) reaches the target, so friend zone position score differences
     * between tiles are never tested against captured output)
     *
     * Adds 5 for each allied unit and subtracts 5 for each enemy unit within a diamond of radius
     * 3 (offsets in [RANGE3_OFFSET_X] and [RANGE3_OFFSET_Y]) around ([x], [y]).
     */
    public fun aiGetFriendZoneCombatPositionScoreComponent(x: Int, y: Int): Int {
        var score = 0
        for (i in RANGE3_OFFSET_X.indices) {
            val nx = x + RANGE3_OFFSET_X[i]
            val ny = y + RANGE3_OFFSET_Y[i]
            if (nx in 0 until map.sizeX && ny in 0 until map.sizeY) {
                val uid = map.unit[ny, nx]
                if (uid != 0) {
                    if (BmPhase.areUnitsAllied(active.unit, uid)) {
                        score += 5
                    } else {
                        score -= 5
                    }
                }
            }
        }
        return score
    }

    /**
     * Ported from src/cp_staff.c: AiTryDoStaff
     *
     * A silenced unit (`UNIT_STATUS_SILENCED`) does nothing; else each staff in its inventory
     * whose required rank (`GetItemRequiredExp`) is not below that of the last staff that set a
     * decision runs its AI function ([Hooks.staffFunc], which returns false for a staff
     * without one). Returns whether a decision is set (`actionPerformed`).
     * Verified: the result for a unit without a staff (false, so the attack search runs: a true
     * result leaves no decision); not seen: a silenced unit, a staff.
     */
    public fun aiTryDoStaff(test: AiUnitTest): Boolean {
        val unit = active.unit
        if (units.statusIndex(unit) == UNIT_STATUS_SILENCED) return decide.decision.actionPerformed
        var exp = 0
        var i = 0
        while (i < UNIT_ITEM_COUNT) {
            val item = units.item(unit, i)
            if (item == 0) break
            if (items.getItemAttributes(item) and ItemInfo.IA_STAFF != 0 &&
                items.getItemRequiredExp(item) >= exp &&
                hooks.staffFunc?.run(item, i, test) == true &&
                decide.decision.actionPerformed
            ) {
                exp = items.getItemRequiredExp(item)
            }
            i++
        }
        return decide.decision.actionPerformed
    }

    private companion object {
        /** `gAiCombatScoreCoefficientTable` in the ROM: 32 rows of `COEFF_STRIDE` bytes. */
        const val COEFF_TABLE = 0x080D8178

        /** Bytes per row (0x280 for 32 rows; the struct in cp_data.h shows fewer fields). */
        const val COEFF_STRIDE = 20

        /** Offsets in a row of `struct AiCombatScoreCoefficients`: `coeffDamageDealt`. */
        const val COEFF_DAMAGE_DEALT = 0

        /** `coeffLowHpOpponent`. */
        const val COEFF_LOW_HP_OPPONENT = 1

        /** `coeffFriendZone`. */
        const val COEFF_FRIEND_ZONE = 2

        /** `coeffClassRankBonus`. */
        const val COEFF_CLASS_RANK_BONUS = 3

        /** `coeffTurnNumber`. */
        const val COEFF_TURN_NUMBER = 4

        /** `coeffDamageTaken`. */
        const val COEFF_DAMAGE_TAKEN = 5

        /** `coeffDanger`. */
        const val COEFF_DANGER = 6

        /** `coeffLowHpSelf`. */
        const val COEFF_LOW_HP_SELF = 7

        /** `classRankBonuses`, indexed by the class rank. */
        const val COEFF_CLASS_BONUSES = 8

        /** Offsets in `struct Unit` (bmunit.h): `pClassData`, `index`, `xPos`, `yPos`, `curHP`. */
        const val OFF_CLASS = 0x04
        const val OFF_INDEX = 0x0B
        const val OFF_X_POS = 0x10
        const val OFF_Y_POS = 0x11
        const val OFF_CUR_HP = 0x13

        /** `sFriendZoneRangeScoreLut`: the x offsets in the C's order (see the component). */
        val FRIEND_ZONE_X = intArrayOf(
            0, -3, 0, 1, -2, -1, 0, 1, 2, -3, -2, -1, 1, 2, 3, -2, -1, 0, 1, 2, -1, 0, 1, 0,
        )

        /** `sFriendZoneRangeScoreLut`: the y offsets, paired with `FRIEND_ZONE_X`. */
        val FRIEND_ZONE_Y = intArrayOf(
            -3, 1, -2, -2, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 3,
        )

        /** `UNIT_ITEM_COUNT` (bmunit.h). */
        const val UNIT_ITEM_COUNT = 5

        /** The `0xC0` of the `uid < 0xC0` unit loops: ids 1 to 0xBF (blue, green, red). */
        const val UNIT_ID_END = 0xC0

        /** `MAP_MOVEMENT_MAX` (bmmap.h). */
        const val MAP_MOVEMENT_MAX = 120

        /** `ITEM_NIGHTMARE` (constants/items.h). */
        const val ITEM_NIGHTMARE = 0xA6

        /** `UNIT_STATUS_SILENCED` (bmunit.h). */
        const val UNIT_STATUS_SILENCED = 3

        /** `AI_FLAG_STAY` (cp_common.h): the unit does not move. */
        const val AI_FLAG_STAY = 1 shl 1

        /** `CA_STEAL`, `CA_BALLISTAE` (bmunit.h). */
        const val CA_STEAL = 1 shl 2
        const val CA_BALLISTAE = 1 shl 7

        /** `US_*` unit state bits (bmunit.h). */
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val US_RESCUED = 1 shl 5
        const val US_IN_BALLISTA = 1 shl 11
        const val US_BIT16 = 1 shl 16

        /** `sRange3OffsetLut_` (cp_battle.c): x offsets of a radius-3 diamond without (0, 0). */
        val RANGE3_OFFSET_X = intArrayOf(
            0,
            -1, 0, 1,
            -2, -1, 0, 1, 2,
            -3, -2, -1, 1, 2, 3,
            -2, -1, 0, 1, 2,
            -1, 0, 1,
            0,
        )

        /** `sRange3OffsetLut_` (cp_battle.c): y offsets paired with [RANGE3_OFFSET_X]. */
        val RANGE3_OFFSET_Y = intArrayOf(
            -3,
            -2, -2, -2,
            -1, -1, -1, -1, -1,
            0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 1,
            2, 2, 2,
            3,
        )
    }
}

private fun RomView.s8(at: Int): Int = u8(at).toByte().toInt()
