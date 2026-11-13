package fe8.game.bm

import fe8.game.RomView

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

/** An AI step on a candidate attack (`void (*)(struct AiCombatSimulationSt *)`). */
public fun interface AiScoreStep {
    /** Runs the step on [st]. */
    public fun run(st: AiCombatSimulationSt)
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
 * of a simulated battle (`AiComputeCombatScore` and its components) and of a tile (the three
 * position components) are EP-6a/6b stand-ins, the movement and reach (`GenerateUnitMovementMap`,
 * `AiReachesByBirdsEyeDistance`) EP-7 stand-ins, and the branches the capture never enters
 * (`AiAttemptCombatWithinMovement` and the ballista, steal, magic seal and staff code) EP-12
 * stand-ins, all through [Hooks].
 */
public class CpBattle(
    rom: RomView,
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
         * Stand-in for `AiComputeCombatScore(st)` and its 8 score components (EP-6a/6b): sets
         * [AiCombatSimulationSt.score]. Null leaves it as it is.
         */
        public var computeCombatScore: AiScoreStep? = null

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

    private val tmpResult = AiCombatSimulationSt()
    private val finalResult = AiCombatSimulationSt()

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
     * scores it ([Hooks.computeCombatScore]); true when scored.
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
            hooks.computeCombatScore?.run(st)
            return true
        }
        return false
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
