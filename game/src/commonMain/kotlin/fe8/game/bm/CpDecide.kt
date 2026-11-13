package fe8.game.bm

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.yield

/** `struct AiDecision` (cp_common.h, `gAiDecision` at 0x0203AA94, 0xC bytes): the AI's choice. */
public class AiDecision {
    /** `actionId` (u8, 0x00): `AI_ACTION_*`. */
    public var actionId: Int = 0

    /** `unitId` (u8, 0x01): `gActiveUnitId` when the decision was set. */
    public var unitId: Int = 0

    /** `xMove` (u8, 0x02). */
    public var xMove: Int = 0

    /** `yMove` (u8, 0x03). */
    public var yMove: Int = 0

    /** `unk04` (u8, 0x04). */
    public var unk04: Int = 0

    /** `unk05` (u8, 0x05). */
    public var unk05: Int = 0

    /** `targetId` (u8, 0x06). */
    public var targetId: Int = 0

    /** `itemSlot` (u8, 0x07). */
    public var itemSlot: Int = 0

    /** `xTarget` (u8, 0x08). */
    public var xTarget: Int = 0

    /** `yTarget` (u8, 0x09). */
    public var yTarget: Int = 0

    /** `actionPerformed` (s8, 0x0A): true once a decision is set. */
    public var actionPerformed: Boolean = false
}

/** A parameterless AI step (`void (*)(void)`). */
public fun interface AiStep {
    /** Runs the step. */
    public fun run()
}

/** An AI step that reports success (`s8 (*)(void)` returning TRUE or FALSE). */
public fun interface AiTry {
    /** Runs the step; true when it succeeded. */
    public fun run(): Boolean
}

/** An AI step on a unit id (`void (*)(struct Unit *)`). */
public fun interface AiUnitStep {
    /** Runs the step on unit [id]. */
    public fun run(id: Int)
}

/** An AI step with one integer argument. */
public fun interface AiIntStep {
    /** Runs the step with [value]. */
    public fun run(value: Int)
}

/** An AI step on a unit and an item (`void (*)(struct Unit *, int item)`). */
public fun interface AiUnitItemStep {
    /** Runs the step on unit [id] with [item]. */
    public fun run(id: Int, item: Int)
}

/** An AI test of an active unit, another unit and an item. */
public fun interface AiReachTest {
    /** True when unit [other] could reach unit [active] with [item]. */
    public fun run(active: Int, other: Int, item: Int): Boolean
}

/** An AI step with two integer arguments. */
public fun interface AiIntPairStep {
    /** Runs the step with [first] and [second]. */
    public fun run(first: Int, second: Int)
}

/** A step that starts a proc blocking [parent]. */
public fun interface AiProcStep {
    /** Starts the proc under [parent]. */
    public fun run(parent: Proc)
}

/**
 * The AI decision loop of src/cp_decide.c: `gProcScr_CpDecide` (ROM 0x085A7FD4) and the decision
 * functions it runs for each unit of [AiState.units]. What later tasks own is reached through
 * [Hooks].
 */
public class CpDecide(
    private val engine: ProcEngine,
    private val units: UnitTable,
    private val aiState: AiState,
    /** `gActiveUnit`, `gActiveUnitId` and `gActionData`. */
    private val active: ActiveUnit,
    /** `AiUpdateGetUnitIsHealing`; null counts no unit as healing. */
    private val aiFlags: CpUtility? = null,
    /** `RefreshEntityBmMaps`; null skips it. */
    private val entityMaps: EntityBmMaps? = null,
    /** Item rules for `AiFillDangerMap` and the special items. */
    rom: RomView? = null,
    /** `CanUnitUseWeapon`, `GetUnitEquippedWeapon`; with [map] enables the danger map. */
    private val loader: UnitLoader? = null,
    /** `gBmMapOther`, `gBmMapRange`, `gBmMapSize`; null skips the danger map. */
    private val map: BmMap? = null,
) {
    private val items: ItemInfo? = if (rom != null) ItemInfo(rom) else null

    /** Services outside this file; a null hook does nothing (a script try succeeds). */
    public class Hooks {
        /** Stand-in for `RenderBmMap` (display; EP-w). */
        public var renderBmMap: AiStep? = null

        /** Stand-in for `RefreshUnitSprites` (display; EP-w). */
        public var refreshUnitSprites: AiStep? = null

        /**
         * Stand-in for `AiCouldReachByBirdsEyeDistance(gActiveUnit, unit, item)` (cp_utility.c;
         * EP-7). Null counts as false: no unit adds to the danger map.
         */
        public var couldReachByBirdsEyeDistance: AiReachTest? = null

        /**
         * Stand-in for `FillMovementAndRangeMapForItem(unit, item)` (cp_utility.c; EP-7): fills
         * `gBmMapRange` with the tiles the unit reaches and attacks. Null leaves it as it is.
         */
        public var fillMovementAndRangeMapForItem: AiUnitItemStep? = null

        /**
         * Stand-in for `sAiSpecialItemFuncLut[index].func(slot)` (cpextra_80407F0.c:
         * `AiSpecialItemDoorKey`, `Lockpick`, `Antitoxin`; EP-12: never entered).
         */
        public var specialItemFunc: AiIntPairStep? = null

        /**
         * Stand-in for the rogue part of `sub_8040C5C` (cpextra_80407F0.c, with `sub_8040B38`,
         * `sub_8040BB4`, `sub_8040B8C`; EP-12: never entered); its result is the function's.
         * Null counts as false.
         */
        public var rogueSpecialItems: AiTry? = null

        /**
         * `AiTryExecScriptA`: [CpPhase] sets it to [CpScript.tryExecScriptA]. Null succeeds
         * without a decision, as a script that ends at once.
         */
        public var tryExecScriptA: AiTry? = null

        /** `AiTryExecScriptB`: [CpPhase] sets it to [CpScript.tryExecScriptB]. Null succeeds. */
        public var tryExecScriptB: AiTry? = null

        /** Stand-in for `AiExecFallbackScriptA` (cp_script.c; EP-12: never entered). */
        public var execFallbackScriptA: AiStep? = null

        /** Stand-in for `AiExecFallbackScriptB` (cp_script.c; EP-12: never entered). */
        public var execFallbackScriptB: AiStep? = null

        /** Stand-in for `AiDoBerserkAction` (berserk script A; EP-cap2: never entered). */
        public var doBerserkAction: AiStep? = null

        /** Stand-in for `AiDoBerserkMove` (berserk script B; EP-cap2: never entered). */
        public var doBerserkMove: AiStep? = null

        /**
         * Stand-in for `GetRiddenBallistaAt(gActiveUnit->xPos, yPos) != NULL` (EP-cap2: never
         * entered). Null counts as false.
         */
        public var onRiddenBallista: AiTry? = null

        /**
         * Stand-in for the healing branch of `DecideHealOrEscape`: `AiTryHealSelf`,
         * `AiTryMoveTowardsEscape`, `AiTryDanceOrStealAfterMove`, `AiTryGetNearestHealPoint`,
         * `AiTryMoveTowards`, `AiTryActionAfterMove` (EP-cap2: never entered).
         */
        public var healBranch: AiStep? = null

        /**
         * Stand-in for the escape branch of `DecideHealOrEscape` for a unit that is not healing
         * but has `AI_UNIT_FLAG_3`: `AiTryMoveTowardsEscape`, `AiTryDanceOrStealAfterMove`
         * (EP-cap2: never entered).
         */
        public var escapeBranch: AiStep? = null

        /**
         * Stand-in for `Proc_StartBlocking(gProcScr_CpPerform, proc)` (cp_perform.c; EP-8). Null
         * starts nothing, so the decision loop goes on at once.
         */
        public var startCpPerform: AiProcStep? = null

        /**
         * Stand-in for `WriteSuspendSave(SAVE_ID_SUSPEND)` (bmsave.c; EP-w): called with the
         * slot after `gActionData.suspendPointType` is set. Null does nothing.
         */
        public var writeSuspendSave: AiIntStep? = null
    }

    /** Services outside the ported files. */
    public val hooks: Hooks = Hooks()

    /** `gAiDecision`. */
    public val decision: AiDecision = AiDecision()

    private val mainFn = ProcFunc { cpDecideMain(it) }
    private val suspendFn = ProcFunc { cpDecideSuspend() }

    /** `AiDecideMain` as an [AiStep], the value `AiDecideMainFunc` is set to. */
    public val decideMainStep: AiStep = AiStep { aiDecideMain() }

    /** `AiDecideMainFunc`: what [cpDecideMain] runs for each unit. */
    public var aiDecideMainFunc: AiStep = decideMainStep

    /** `gProcScr_CpDecide`. */
    public val script: ProcScript = ProcScript(
        "gProcScr_CpDecide",
        listOf(
            name("E_CPDECIDE"),
            label(0),
            call(mainFn),
            yield(),
            call(suspendFn),
            goto(0),
            end(),
        ),
    )

    /**
     * Ported from src/cp_decide.c: CpDecide_Suspend
     *
     * Sets `gActionData.suspendPointType` (`SUSPEND_POINT_BSKPHASE` for a blue active unit, else
     * `SUSPEND_POINT_CPPHASE`) and writes the suspend save ([Hooks.writeSuspendSave]). Verified:
     * the red case (twice); the blue case is not seen.
     */
    public fun cpDecideSuspend() {
        val type = if (units.index(active.unit) and FACTION_MASK == FACTION_BLUE) {
            SUSPEND_POINT_BSKPHASE
        } else {
            SUSPEND_POINT_CPPHASE
        }
        active.action.suspendPointType = type
        hooks.writeSuspendSave?.run(BMapMain.SAVE_ID_SUSPEND)
    }

    /**
     * Ported from src/cp_decide.c: CpDecide_Main
     *
     * Decides for the unit at [AiState.unitIt] (ends [proc] at the list's 0 terminator): sets it
     * active, refreshes the maps, clears and fills [decision] ([aiDecideMainFunc]), marks the
     * unit `US_HAS_MOVED_AI`, advances the iterator, and either loops to the next unit (no
     * action, or a move to the same tile) or starts the perform proc ([Hooks.startCpPerform])
     * blocking [proc]. Verified: the loop, both starts of the perform proc, the marking, the
     * end; not seen: a dead or unselectable unit being skipped, a move to the same tile with an
     * action, and the `combatWeightTableId` value (every captured `ai_config` is 0).
     */
    public fun cpDecideMain(proc: Proc) {
        while (true) {
            aiState.decideState = 0
            val id = aiState.units[aiState.unitIt].toInt() and 0xFF
            if (id == 0) {
                engine.end(proc)
                return
            }
            aiState.unk7C = 0
            active.unitId = id
            active.unit = id
            if (!units.isValid(id) || units.state(id) and (US_DEAD or US_UNSELECTABLE) != 0) {
                aiState.unitIt++
                continue
            }

            entityMaps?.refreshEntityBmMaps()
            hooks.renderBmMap?.run()
            hooks.refreshUnitSprites?.run()

            aiUpdateNoMoveFlag(id)

            aiState.combatWeightTableId = (units.aiConfig(id) and COMBATWEIGHT_MASK) shr
                COMBATWEIGHT_SHIFT

            aiState.dangerMapFilled = false
            aiInitDangerMap()

            aiClearDecision()
            aiDecideMainFunc.run()

            units.setState(id, units.state(id) or US_HAS_MOVED_AI)

            val d = decision
            val sameTile = units.xPos(id) == d.xMove && units.yPos(id) == d.yMove &&
                d.actionId == AI_ACTION_NONE
            aiState.unitIt++
            if (!d.actionPerformed || sameTile) {
                // Ignoring actions that are just moving to the same square
                continue
            }
            hooks.startCpPerform?.run(proc)
            return
        }
    }

    /**
     * Ported from src/cp_decide.c: AiClearDecision
     *
     * Zeroes every field of [decision].
     */
    public fun aiClearDecision() {
        val d = decision
        d.actionId = 0
        d.unitId = 0
        d.xMove = 0
        d.yMove = 0
        d.unk04 = 0
        d.unk05 = 0
        d.targetId = 0
        d.itemSlot = 0
        d.xTarget = 0
        d.yTarget = 0
        d.actionPerformed = false
    }

    /**
     * Ported from src/cp_decide.c: AiSetDecision
     *
     * Stores the arguments (each truncated to a byte) and the active unit's id in [decision] and
     * marks it performed.
     */
    public fun aiSetDecision(
        xMove: Int,
        yMove: Int,
        actionId: Int,
        targetId: Int,
        itemSlot: Int,
        xTarget: Int,
        yTarget: Int,
    ) {
        val d = decision
        d.unitId = active.unitId and 0xFF
        d.xMove = xMove and 0xFF
        d.yMove = yMove and 0xFF
        d.actionId = actionId and 0xFF
        d.targetId = targetId and 0xFF
        d.itemSlot = itemSlot and 0xFF
        d.xTarget = xTarget and 0xFF
        d.yTarget = yTarget and 0xFF
        d.actionPerformed = true
    }

    /**
     * Ported from src/cp_decide.c: AiDecideMain
     *
     * Runs the decision functions in order (0 [decideHealOrEscape], 1 [decideScriptA], 2
     * [decideScriptB], 3 [decideSpecialItems]) from [AiState.decideState], which counts up
     * before each call, until one has set [decision] or the list (4 entries) is done.
     */
    public fun aiDecideMain() {
        while (aiState.decideState < DECIDE_FUNC_COUNT && !decision.actionPerformed) {
            when (aiState.decideState++) {
                0 -> decideHealOrEscape()
                1 -> decideScriptA()
                2 -> decideScriptB()
                else -> decideSpecialItems()
            }
        }
    }

    /**
     * Ported from src/cpextra_80407F0.c: AiUpdateNoMoveFlag
     *
     * Sets `AI_FLAG_STAY` in [AiState.flags] when the unit's `ai_config` has
     * `AI_UNIT_CONFIG_FLAG_STAY`, else clears it. Verified: the clear case (no captured unit
     * stays; `flags` stays 1); the set case is not seen.
     */
    public fun aiUpdateNoMoveFlag(id: Int) {
        aiState.flags = if (units.aiConfig(id) and AI_UNIT_CONFIG_FLAG_STAY != 0) {
            aiState.flags or AI_FLAG_STAY
        } else {
            aiState.flags and AI_FLAG_STAY.inv()
        }
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiInitDangerMap
     *
     * Once per unit (while [AiState.dangerMapFilled] is clear): sets it, clears
     * `gBmMapOther` and fills it ([aiFillDangerMap]). Does nothing without a map. Verified: the
     * clear and refill for each of the three units; the already-filled return is not seen
     * ([cpDecideMain] clears the flag first).
     */
    public fun aiInitDangerMap() {
        val m = map ?: return
        if (!aiState.dangerMapFilled) {
            aiState.dangerMapFilled = true
            m.bmMapFill(m.other, 0)
            aiFillDangerMap(m)
        }
    }

    /**
     * Ported from src/cp_0803E2F4.c: AiFillDangerMap
     *
     * For each valid, shown enemy of the active unit that has a weapon it can wield and could
     * reach the active unit ([Hooks.couldReachByBirdsEyeDistance], EP-7): adds
     * `(power + might) / 2` of its strongest weapon to every tile of
     * [Hooks.fillMovementAndRangeMapForItem]'s range map (EP-7). As in the C, the best weapon
     * and its might carry over from one unit to the next. Verified (the reach test and the
     * range map are fed from the capture): the unit filters, the weapon choice, the damage
     * formula and the accumulation, for the three units; not seen: a unit with a second
     * weapon, a hidden or undeployed enemy.
     */
    private fun aiFillDangerMap(m: BmMap) {
        val loader = loader ?: return
        val items = items ?: return
        var item = 0
        var might = 0
        for (i in 1 until 0xC0) {
            if (!units.isValid(i)) continue
            if (units.state(i) and (US_HIDDEN or US_DEAD or US_NOT_DEPLOYED or US_BIT16) != 0) {
                continue
            }
            if (BmPhase.areUnitsAllied(active.unitId, units.index(i) and 0xFF)) continue

            val o = units.unitOffset(i)
            var j = 0
            while (j < UNIT_ITEM_COUNT) {
                val itemTmp = units.item(i, j)
                if (itemTmp == 0) break
                j++
                if (!loader.canUnitUseWeapon(o, itemTmp)) continue
                if (items.getItemMight(itemTmp) > might) {
                    item = itemTmp
                    might = items.getItemMight(itemTmp)
                }
            }
            if (item == 0) continue

            if (hooks.couldReachByBirdsEyeDistance?.run(active.unit, i, item) != true) continue

            hooks.fillMovementAndRangeMapForItem?.run(i, item)

            val power = units.bytes[o + OFF_POW] + items.getItemPowBonus(
                loader.getUnitEquippedWeapon(o),
            )
            val add = (power + might) shr 1
            for (iy in m.sizeY - 1 downTo 0) {
                for (ix in m.sizeX - 1 downTo 0) {
                    if (m.range[iy, ix].toByte().toInt() == 0) continue
                    m.other[iy, ix] = (m.other[iy, ix] + add) and 0xFF
                }
            }
        }
    }

    /**
     * Transcribed from src/cp_decide.c: DecideSpecialItems (not verified: it runs once, for
     * Red 0, and changes nothing; leaving out the call keeps the test green)
     *
     * Nothing when berserked, else [aiTryDoSpecialItems].
     */
    public fun decideSpecialItems() {
        if (aiState.flags and CpPhase.AI_FLAG_BERSERKED != 0) return
        aiTryDoSpecialItems()
    }

    /**
     * Transcribed from src/cpextra_80407F0.c: GetSpecialItemFuncIndex (not verified: only the
     * result -1 runs, for Red 0's items, and it shows in no captured value; a wrong hit would
     * enter a special item function, which the EP-0 trace says never runs)
     *
     * The index into the special item table (0 door key, 1 lockpick, 2 antitoxin) of [item], or
     * -1.
     */
    public fun getSpecialItemFuncIndex(item: Int): Int {
        val itemId = item and 0xFF
        for (index in SPECIAL_ITEM_IDS.indices) {
            if (itemId == SPECIAL_ITEM_IDS[index]) return index
        }
        return -1
    }

    /**
     * Transcribed from src/cpextra_80407F0.c: AiTryDoSpecialItems (not verified: it runs once,
     * for Red 0, and changes nothing; its result is discarded by the caller)
     *
     * Not when `AI_FLAG_STAY`; [sub8040C5C] first; then runs the special item function of each
     * inventory item whose type is not 0. Returns whether a decision was made (true when
     * [AiState.decideState] is 0).
     */
    public fun aiTryDoSpecialItems(): Boolean {
        if (aiState.flags and AI_FLAG_STAY != 0) return false
        if (sub8040C5C()) return true
        val items = items ?: return false
        for (i in 0 until UNIT_ITEM_COUNT) {
            val item = units.item(active.unit, i)
            if (item == 0) break
            if (items.getItemType(item) == 0) continue
            val funcIndex = getSpecialItemFuncIndex(item)
            if (funcIndex == -1) continue
            hooks.specialItemFunc?.run(funcIndex, i)
        }
        if (aiState.decideState == 0) return true
        return decision.actionPerformed
    }

    /**
     * Transcribed from src/cpextra_80407F0.c: sub_8040C5C (not verified: only the non-rogue
     * return runs, for Red 0 at f1613, and it shows in no captured value; the rogue part is a
     * stand-in, EP-12)
     *
     * Rogue-only item pickup: false for any other class ([Hooks.rogueSpecialItems] otherwise).
     */
    public fun sub8040C5C(): Boolean {
        if (units.classId(active.unit) != CLASS_ROGUE) return false
        return hooks.rogueSpecialItems?.run() == true
    }

    /**
     * Transcribed from src/cp_decide.c: DecideHealOrEscape (not verified: it runs for all three
     * enemies, but only the path of a unit that is not healing and has no `AI_UNIT_FLAG_3`; the
     * berserk return and both branches are not seen, the branches are stand-ins, EP-cap2)
     *
     * Does nothing when berserked; for a healing unit runs [Hooks.healBranch]; else, for a unit
     * with `AI_UNIT_FLAG_3`, [Hooks.escapeBranch].
     */
    public fun decideHealOrEscape() {
        if (aiState.flags and CpPhase.AI_FLAG_BERSERKED != 0) return
        val id = active.unit
        if (aiFlags != null && aiFlags.aiUpdateGetUnitIsHealing(id)) {
            hooks.healBranch?.run()
        } else if (units.aiFlags(id) and AI_UNIT_FLAG_3 != 0) {
            hooks.escapeBranch?.run()
        }
    }

    /**
     * Transcribed from src/cp_decide.c: DecideScriptA (not verified: only a script try that
     * succeeds at once runs; the gorgon egg return, the berserk action and the fallback script
     * never run)
     *
     * Nothing for a gorgon egg; the berserk action when berserked; else [Hooks.tryExecScriptA]
     * up to 0x100 times until it succeeds, then the fallback script.
     */
    public fun decideScriptA() {
        val classId = units.classId(active.unit)
        if (classId == CLASS_GORGONEGG || classId == CLASS_GORGONEGG2) return
        if (aiState.flags and CpPhase.AI_FLAG_BERSERKED != 0) {
            hooks.doBerserkAction?.run()
            return
        }
        val exec = hooks.tryExecScriptA
        var i = 0
        while (i < SCRIPT_TRIES) {
            if (exec == null || exec.run()) return
            i++
        }
        hooks.execFallbackScriptA?.run()
    }

    /**
     * Transcribed from src/cp_decide.c: DecideScriptB (not verified: only a script try that
     * succeeds at once runs, for one unit; the ballista return, the berserk move and the
     * fallback script never run)
     *
     * Nothing for a unit riding a ballista; the berserk move when berserked; else
     * [Hooks.tryExecScriptB] up to 0x100 times until it succeeds, then the fallback script.
     */
    public fun decideScriptB() {
        val rides = units.state(active.unit) and US_IN_BALLISTA != 0
        if (rides && hooks.onRiddenBallista?.run() == true) return
        if (aiState.flags and CpPhase.AI_FLAG_BERSERKED != 0) {
            hooks.doBerserkMove?.run()
            return
        }
        val exec = hooks.tryExecScriptB
        var i = 0
        while (i < SCRIPT_TRIES) {
            if (exec == null || exec.run()) return
            i++
        }
        hooks.execFallbackScriptB?.run()
    }

    /** Constants of cp_decide.c and the headers it uses. */
    public companion object {
        /** `AI_ACTION_NONE`: move only. */
        public const val AI_ACTION_NONE: Int = 0

        /** `AI_ACTION_COMBAT`. */
        public const val AI_ACTION_COMBAT: Int = 1

        /** `SUSPEND_POINT_CPPHASE`. */
        public const val SUSPEND_POINT_CPPHASE: Int = 2

        /** `SUSPEND_POINT_BSKPHASE`. */
        public const val SUSPEND_POINT_BSKPHASE: Int = 3

        private const val DECIDE_FUNC_COUNT = 4
        private const val UNIT_ITEM_COUNT = 5
        private const val OFF_POW = 0x14
        private const val AI_FLAG_STAY = 1 shl 1
        private const val AI_UNIT_CONFIG_FLAG_STAY = 1 shl 13
        private const val US_HIDDEN = 1 shl 0
        private const val US_NOT_DEPLOYED = 1 shl 3
        private const val US_BIT16 = 1 shl 16
        private const val CLASS_ROGUE = 0x33
        private val SPECIAL_ITEM_IDS = intArrayOf(0x6A, 0x6B, 0x6F)
        private const val SCRIPT_TRIES = 0x100
        private const val FACTION_MASK = 0xC0
        private const val FACTION_BLUE = 0
        private const val US_UNSELECTABLE = 1 shl 1
        private const val US_DEAD = 1 shl 2
        private const val US_HAS_MOVED_AI = 1 shl 10
        private const val US_IN_BALLISTA = 1 shl 11
        private const val AI_UNIT_FLAG_3 = 1 shl 3
        private const val COMBATWEIGHT_MASK = 0xF8
        private const val COMBATWEIGHT_SHIFT = 3
        private const val CLASS_GORGONEGG2 = 0x34
        private const val CLASS_GORGONEGG = 0x62
    }
}
