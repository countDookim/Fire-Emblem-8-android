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

/** A score component of the simulated battle (`int (*)(void)`). */
public fun interface AiScoreComponent {
    /** The component's value. */
    public fun run(): Int
}

/** An AI function of one integer (`u8 (*)(u8)`). */
public fun interface AiIntFunc {
    /** The result for [value]. */
    public fun run(value: Int): Int
}

/** The position score components: unit [target], tile ([x], [y]). */
public fun interface AiPositionScore {
    /** The sum of the three components for a unit standing on ([x], [y]) against [target]. */
    public fun run(x: Int, y: Int, target: Int): Int
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
 * of a simulated battle is written ([aiComputeCombatScore]) but for four components (turn, damage
 * taken, danger, own low HP), and the three tile position components, which are EP-6b stand-ins;
 * the movement and reach (`GenerateUnitMovementMap`,
 * `AiReachesByBirdsEyeDistance`) EP-7 stand-ins, and the branches the capture never enters
 * (`AiAttemptCombatWithinMovement` and the ballista, steal, magic seal and staff code) EP-12
 * stand-ins, all through [Hooks].
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
) {
    private val items = ItemInfo(rom)

    /** Services outside the written functions; a null hook does nothing. */
    public class Hooks {
        /**
         * Stand-in for `GenerateUnitMovementMap(gActiveUnit)` (bmmap; EP-7): fills
         * `gBmMapMovement` with the active unit's movement costs.
         */
        public var generateUnitMovementMap: AiUnitStep? = null

        /**
         * Stand-in for `AiReachesByBirdsEyeDistance(active, unit, item)` (cp_utility.c; EP-7).
         * Null counts as false: no unit is in reach.
         */
        public var reachesByBirdsEyeDistance: AiReachTest? = null

        /**
         * `BattleGenerateSimulation(gActiveUnit, target, x, y, slot)` (bmbattle.c, written in
         * [BattleGenerate]): the wiring passes it. Null simulates nothing.
         */
        public var battleGenerateSimulation: AiBattleSimulation? = null

        /**
         * Stand-in for `AiGetClassRank(classId)` (cp_utility.c; EP-7): the rank of the target's
         * class number. Null counts as 0.
         */
        public var getClassRank: AiIntFunc? = null

        /**
         * Stand-in for `AiGetTurnCombatScoreComponent` (EP-6b): `chapterTurnNumber` times the
         * turn coefficient. Null counts as 0.
         */
        public var turnScore: AiScoreComponent? = null

        /**
         * Stand-in for `AiGetDamageTakenScoreComponent` (EP-6b), subtracted from the score. Null
         * counts as 0.
         */
        public var damageTakenScore: AiScoreComponent? = null

        /**
         * Stand-in for `AiGetDangerScoreComponent` (EP-6b), subtracted from the score. Null
         * counts as 0.
         */
        public var dangerScore: AiScoreComponent? = null

        /**
         * Stand-in for `AiGetLowHpScoreComponent` (EP-6b), subtracted from the score. Null counts
         * as 0.
         */
        public var lowHpScore: AiScoreComponent? = null

        /**
         * Stand-in for `AiGetInRangeCombatPositionScoreComponent`,
         * `AiGetTerrainCombatPositionScoreComponent` and
         * `AiGetFriendZoneCombatPositionScoreComponent` (EP-6a/6b): their sum. Null counts as 0.
         */
        public var positionScore: AiPositionScore? = null

        /**
         * Stand-in for the `US_IN_BALLISTA` branch of `AiAttemptOffensiveAction`
         * (`GetRiddenBallistaAt`, `TryRemoveUnitFromBallista`; EP-12: never entered): true to go
         * on to the ballista combat. Null counts as false.
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
         * runs for every unit that is not in a ballista and not stealing.
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
         * Stand-in for `TryRemoveUnitFromBallista(gActiveUnit)` (bmarch.c; EP-12: never
         * entered), called only for a unit in a ballista.
         */
        public var tryRemoveFromBallista: AiStep? = null

        /** Stand-in for the staff AI functions (cp_staff.c; EP-12: never entered). */
        public var staffFunc: AiStaffStep? = null
    }

    /** Services outside the written functions. */
    public val hooks: Hooks = Hooks()

    /** `gBattleActor` and `gBattleTarget`; null (the score is not computed) until wired. */
    public var battleUnits: BmBattle? = null

    private val tmpResult = AiCombatSimulationSt()
    private val finalResult = AiCombatSimulationSt()

    /** `finalResult` of the last [aiAttemptOffensiveAction]: the best attack found. */
    public val bestResult: AiCombatSimulationSt
        get() = finalResult

    /** `sCombatScoreCoefficients`: the ROM address of the active table row. */
    private var coefficients = 0

    /**
     * Ported from src/cp_battle.c: AiAttemptOffensiveAction
     *
     * Finds the best attack of the active unit: its movement map (the tile it stands on only
     * with `AI_FLAG_STAY`), then for each usable weapon but the Nightmare staff and each enemy
     * ([test]) in reach the best tile ([aiSimulateBestBattleAgainstTarget]); the highest score
     * wins (a tie goes to the later one). A found attack becomes the decision (combat, no
     * target tile). Verified: the movement and range maps, the candidate tile, the target, the
     * weapon slot and the simulated battle (via the decision and the dump at the score call);
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
                hooks.generateUnitMovementMap?.run(unit)
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

    /** The unit loop of [aiAttemptOffensiveAction] for the weapon [item] in [slot]. */
    private fun searchTargets(test: AiUnitTest, item: Int, slot: Int) {
        val unit = active.unit
        var uid = 1
        while (uid < UNIT_ID_END) {
            val skip = !units.isValid(uid) ||
                units.state(uid) and (US_HIDDEN or US_DEAD or US_RESCUED or US_BIT16) != 0 ||
                !test.run(uid) ||
                hooks.reachesByBirdsEyeDistance?.run(unit, uid, item) != true
            if (!skip) {
                aiFillReversedAttackRangeMap(uid, item)
                tmpResult.targetId = units.index(uid) and 0xFF
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
     * Picks the tile for [st]'s target: of the tiles the active unit can reach, from which the
     * target is in range and that no other unit holds, the one with the highest
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
     * 0; the three components are a stand-in, EP-6a/6b)
     *
     * The u32 score (as its 32 bits) of standing on ([x], [y]) against [st]'s target: the
     * position components, minus the movement cost, minus an eighth of the danger, plus
     * 0x7FFFFFFF.
     */
    public fun aiGetCombatPositionScore(x: Int, y: Int, st: AiCombatSimulationSt): Int {
        var score = hooks.positionScore?.run(x, y, st.targetId) ?: 0
        score -= map.movement[y, x].toByte().toInt()
        score -= map.other[y, x] / 8
        score += 0x7FFFFFFF
        return score
    }

    /**
     * Ported from src/cp_battle.c: AiIsBadFight
     *
     * Always false (FE6 used it for Eclipse). Verified through the score call of
     * [aiSimulateBattleAgainstTargetAtPosition]:
     * a true result leaves no decision.
     */
    @Suppress("UNUSED_PARAMETER")
    public fun aiIsBadFight(st: AiCombatSimulationSt): Boolean = false

    /**
     * Ported from src/cp_battle.c: AiSimulateBattleAgainstTargetAtPosition
     *
     * Simulates the battle of the active unit against [st]'s target on (`xMove`,
     * `yMove`) with `itemSlot` (the ballista simulation for 0xFFFF), and unless it is a bad fight
     * scores it ([aiComputeCombatScore]); true when scored.
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
     * Selects the coefficient row of `combatWeightTableId` and sets [AiCombatSimulationSt.score]
     * of [st]: the damage dealt, plus the opponent's low HP, friend zone, target class and turn
     * components, minus the damage taken, danger and own low HP components, clamped to 0 and
     * times 40; a total of 0 gives the damage dealt component itself. Verified: the damage dealt
     * value and the clamp to 0 (the total is below 0 in both captured calls: the turn, damage
     * taken, danger and low HP components are EP-6b stand-ins), the opponent's low HP term
     * (enough to leave the clamp when raised); not seen: a positive total, the other terms'
     * signs.
     */
    public fun aiComputeCombatScore(st: AiCombatSimulationSt) {
        coefficients = COEFF_TABLE + COEFF_STRIDE * aiState.combatWeightTableId
        var score = aiGetDamageDealtCombatScoreComponent()
        val backup = score
        score += aiGetOpponentLowHpScoreComponent()
        score += aiGetFriendZoneCombatScoreComponent()
        score += aiGetTargetClassCombatScoreComponent()
        score += hooks.turnScore?.run() ?: 0
        score -= hooks.damageTakenScore?.run() ?: 0
        score -= hooks.dangerScore?.run() ?: 0
        score -= hooks.lowHpScore?.run() ?: 0
        if (score < 0) score = 0
        st.score = if (score != 0) score * 40 else backup
    }

    /**
     * Ported from src/cp_battle.c: AiGetDamageDealtCombatScoreComponent
     *
     * 50 when the battle kills the target; else the actor's attack minus the target's defense
     * (not below 0) times its hit chance, divided by 100, times the coefficient, at most 40.
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
     * The coefficient times 20 minus the target's HP after the battle, not below 0.
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
     * For each tile of the friend zone around the actor (a diamond of radius 3, score 4 minus
     * the distance, as the C's table with its one off-pattern entry) that holds an ally: adds
     * the tile's score; times the coefficient, at most 10.
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
     * both captured calls and no captured value shows it; the rank is an EP-7 stand-in)
     *
     * The coefficient times the class rank bonus of the target's class, at most 20.
     */
    public fun aiGetTargetClassCombatScoreComponent(): Int {
        val b = battleUnits ?: return 0
        val classPtr = StructCodec.u32(b.target.bytes, OFF_CLASS)
        val rank = (hooks.getClassRank?.run(rom.u8(classPtr + 4)) ?: 0) and 0xFF
        var score = rom.u8(coefficients + COEFF_CLASS_BONUSES + rank)
        score = rom.u8(coefficients + COEFF_CLASS_RANK_BONUS) * score
        if (score > 20) score = 20
        return score
    }

    /**
     * Ported from src/cp_staff.c: AiTryDoStaff
     *
     * A silenced unit does nothing; else each staff the unit can use (not below the experience
     * of the last staff that decided) runs its AI function ([Hooks.staffFunc]). Returns whether
     * a decision is set. Verified: the result for a unit without a staff (false, so the attack
     * search runs: a true result leaves no decision); not seen: a silenced unit, a staff.
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
        const val COEFF_TABLE = 0x080D8178
        const val COEFF_STRIDE = 20
        const val COEFF_DAMAGE_DEALT = 0
        const val COEFF_LOW_HP_OPPONENT = 1
        const val COEFF_FRIEND_ZONE = 2
        const val COEFF_CLASS_RANK_BONUS = 3
        const val COEFF_CLASS_BONUSES = 8
        const val OFF_CLASS = 0x04
        const val OFF_INDEX = 0x0B
        const val OFF_X_POS = 0x10
        const val OFF_Y_POS = 0x11
        const val OFF_CUR_HP = 0x13

        /** `sFriendZoneRangeScoreLut`: the offsets in the C's order (the (-3, +1) entry is odd). */
        val FRIEND_ZONE_X = intArrayOf(
            0, -3, 0, 1, -2, -1, 0, 1, 2, -3, -2, -1, 1, 2, 3, -2, -1, 0, 1, 2, -1, 0, 1, 0,
        )
        val FRIEND_ZONE_Y = intArrayOf(
            -3, 1, -2, -2, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 3,
        )
        const val UNIT_ITEM_COUNT = 5
        const val UNIT_ID_END = 0xC0
        const val MAP_MOVEMENT_MAX = 120
        const val ITEM_NIGHTMARE = 0xA6
        const val UNIT_STATUS_SILENCED = 3
        const val AI_FLAG_STAY = 1 shl 1
        const val CA_STEAL = 1 shl 2
        const val CA_BALLISTAE = 1 shl 7
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val US_RESCUED = 1 shl 5
        const val US_IN_BALLISTA = 1 shl 11
        const val US_BIT16 = 1 shl 16
    }
}
