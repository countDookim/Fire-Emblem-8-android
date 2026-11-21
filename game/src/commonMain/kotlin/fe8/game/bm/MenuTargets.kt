package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32

/**
 * The adjacent-unit target lists of src/bmtarget.c that the unit menu's availability checks make:
 * the units next to the acting unit it can rescue ([makeRescueTargetList]) and the rescued units
 * next to it it can take ([makeTakeTargetList]). They fill the list of [attack] (the one the
 * target selection walks) and use its range map. Allocates nothing.
 */
public class MenuTargets(
    /** The weapon target list, whose list, range map and unit iteration these lists share. */
    @PublishedApi internal val attack: AttackTargets,
    /** `MapAddInRange` (src/bmidoten.c) over the range map. */
    private val entityMaps: EntityBmMaps,
    private val units: UnitTable,
    /** The ROM that the unit's character and class data point into. */
    private val rom: RomView,
    /** `GetUnitSupporterCount` and `GetUnitSupporterUnit` of src/bmreliance.c. */
    private val support: SupportReliance,
) {
    /** The checks of other systems that the lists call. */
    public class Hooks {
        /**
         * `CheckForCharacterEvents(pidA, pidB)` (src/eventinfo.c): whether the chapter has a
         * character-based event (a conversation) for the pair. The game wires
         * `EventInfos.checkForCharacterEvents`.
         */
        public var checkForCharacterEvents: (pidA: Int, pidB: Int) -> Boolean = { _, _ -> false }

        /**
         * `CanUnitSupportNow(unit, num)` (src/bmreliance.c): whether unit [unit] can have the
         * support conversation with its supporter [num] now.
         * Stand-in until EP-10c ports it with a support capture (EP10c-cap); no capture reaches it.
         */
        public var canUnitSupportNow: (unit: Int, num: Int) -> Boolean = { _, _ -> false }
    }

    /** The hooks of this class. */
    public val hooks: Hooks = Hooks()

    /** The list `InitTargets` and `AddTarget` fill. */
    public val targets: SelectTargets get() = attack.targets

    /** `gSubjectUnit`: the id of the unit the list is made for. */
    private var subjectUnit: Int = 0

    /**
     * Ported from src/bmtarget.c: ForEachAdjacentUnit
     *
     * Starts a list rooted at ([x], [y]), marks the four tiles next to it on the working map
     * (`gBmMapRange` after the caller cleared it) and calls [func] with the id of every unit
     * standing on one of them, from the bottom-right tile to the top-left.
     */
    public inline fun forEachAdjacentUnit(x: Int, y: Int, func: (unitId: Int) -> Unit) {
        markAdjacentTiles(x, y)
        attack.forEachUnitInRange(func)
    }

    /** The map part of [forEachAdjacentUnit]: the list's root and the ring of range 1. */
    @PublishedApi
    internal fun markAdjacentTiles(x: Int, y: Int) {
        targets.initTargets(x, y)
        entityMaps.mapAddInRange(x, y, 1, 1)
        entityMaps.mapAddInRange(x, y, 0, -1)
    }

    /**
     * Ported from src/bmtarget.c: TryAddUnitToRescueTargetList
     *
     * Adds the unit [unitId] to the list when the subject unit can rescue it: on the same side,
     * neither of them a phantom, [unitId] not berserk, neither rescuing nor rescued, and
     * [canUnitRescue]. Verified at f4181 only for the first guard (an enemy of the subject
     * unit is skipped); the later guards are covered by synthetic tests.
     */
    public fun tryAddUnitToRescueTargetList(unitId: Int) {
        if (!BmPhase.areUnitsAllied(units.index(subjectUnit), units.index(unitId))) return
        if (units.classId(subjectUnit) == CLASS_PHANTOM || units.classId(unitId) == CLASS_PHANTOM) {
            return
        }
        if (units.statusIndex(unitId) == UnitTable.STATUS_BERSERK) return
        if (units.state(unitId) and (UnitTable.US_RESCUING or UnitTable.US_RESCUED) != 0) return
        if (!canUnitRescue(subjectUnit, unitId)) return

        attack.targets.addTarget(units.xPos(unitId), units.yPos(unitId), units.index(unitId), 0)
    }

    /**
     * Ported from src/bmtarget.c: MakeRescueTargetList
     *
     * Makes the list of the units next to [unit] that it can rescue, from a cleared range map.
     */
    public fun makeRescueTargetList(unit: Int) {
        val x = units.xPos(unit)
        val y = units.yPos(unit)

        subjectUnit = unit

        attack.map.bmMapFill(attack.map.range, 0)

        forEachAdjacentUnit(x, y) { tryAddUnitToRescueTargetList(it) }
    }

    /**
     * Ported from src/bmtarget.c: TryAddRescuedUnitToTakeTargetList
     *
     * Adds the unit [unitId] to the list when it is on the subject unit's side, is rescuing
     * someone, is not `CA_SUPPLY` and the subject unit can rescue the unit it holds. Verified at
     * f4181 only that an enemy that is not rescuing is skipped (the allegiance and the rescuing
     * guards each reject it alone, so the capture does not tell them apart); the `CA_SUPPLY` and
     * [canUnitRescue] guards are covered by synthetic tests.
     */
    public fun tryAddRescuedUnitToTakeTargetList(unitId: Int) {
        if (!BmPhase.isSameAllegiance(units.index(subjectUnit), units.index(unitId))) return
        if (units.state(unitId) and UnitTable.US_RESCUING == 0) return
        if (units.cAttributes(unitId) and CA_SUPPLY != 0) return
        if (!canUnitRescue(subjectUnit, units.rescue(unitId))) return

        attack.targets.addTarget(units.xPos(unitId), units.yPos(unitId), units.index(unitId), 0)
    }

    /**
     * Ported from src/bmtarget.c: MakeTakeTargetList
     *
     * Makes the list of the rescuing units next to [unit] whose rescued unit it can take, from a
     * cleared range map.
     */
    public fun makeTakeTargetList(unit: Int) {
        val x = units.xPos(unit)
        val y = units.yPos(unit)

        subjectUnit = unit

        attack.map.bmMapFill(attack.map.range, 0)

        forEachAdjacentUnit(x, y) { tryAddRescuedUnitToTakeTargetList(it) }
    }

    /**
     * Ported from src/bmtarget.c: TryAddUnitToTradeTargetList
     *
     * Adds the unit [unitId] when it is on the subject unit's side (neither a phantom), is not
     * berserk, is not `CA_SUPPLY` and either of them holds an item. Then, if it is rescuing a
     * blue unit and either holds an item, the rescued unit is added too (at the rescuer's
     * tile, as the rescued unit's id). Verified at f4181 only for an enemy, which the allegiance
     * guard skips; the other branches are covered by synthetic tests.
     */
    public fun tryAddUnitToTradeTargetList(unitId: Int) {
        if (!BmPhase.isSameAllegiance(units.index(subjectUnit), units.index(unitId))) return
        if (units.classId(subjectUnit) == CLASS_PHANTOM || units.classId(unitId) == CLASS_PHANTOM) {
            return
        }

        if (units.statusIndex(unitId) != UnitTable.STATUS_BERSERK) {
            if (units.item(subjectUnit, 0) != 0 || units.item(unitId, 0) != 0) {
                if (units.cAttributes(unitId) and CA_SUPPLY == 0) {
                    attack.targets.addTarget(
                        units.xPos(unitId), units.yPos(unitId), units.index(unitId), 0,
                    )
                }
            }
        }

        if (units.state(unitId) and UnitTable.US_RESCUING != 0) {
            val rescue = units.rescue(unitId)

            if (units.index(rescue) and FACTION_MASK != FACTION_BLUE) return
            if (units.item(subjectUnit, 0) == 0 && units.item(rescue, 0) == 0) return

            attack.targets.addTarget(
                units.xPos(unitId), units.yPos(unitId), units.index(rescue), 0,
            )
        }
    }

    /**
     * Ported from src/bmtarget.c: MakeTradeTargetList
     *
     * Makes the list of the units next to [unit] it can trade with, from a cleared range map.
     * When [unit] is rescuing, the unit it holds is tried too and, if added, is placed on
     * [unit]'s own tile. Verified at f4181 only for the empty list of a unit that is not
     * rescuing.
     */
    public fun makeTradeTargetList(unit: Int) {
        val x = units.xPos(unit)
        val y = units.yPos(unit)

        subjectUnit = unit

        attack.map.bmMapFill(attack.map.range, 0)
        forEachAdjacentUnit(x, y) { tryAddUnitToTradeTargetList(it) }

        if (units.state(subjectUnit) and UnitTable.US_RESCUING != 0) {
            val count = attack.targets.getSelectTargetCount()
            tryAddUnitToTradeTargetList(units.rescue(subjectUnit))

            if (count != attack.targets.getSelectTargetCount()) {
                attack.targets.setTargetPosition(
                    count, units.xPos(subjectUnit), units.yPos(subjectUnit),
                )
            }
        }
    }

    /**
     * Ported from src/bmtarget.c: TryAddUnitToTalkTargetList
     *
     * Adds the unit [unitId], with its character id as the target's `extra`, when it is not
     * berserk or asleep and the chapter has a conversation between the subject unit's character
     * and its ([Hooks.checkForCharacterEvents]). Verified at f4181 only for the enemy next to
     * the unit, which has no conversation; the status guard is covered by a synthetic test.
     */
    public fun tryAddUnitToTalkTargetList(unitId: Int) {
        val status = units.statusIndex(unitId)
        if (status == UnitTable.STATUS_BERSERK || status == UnitTable.STATUS_SLEEP) return

        if (!hooks.checkForCharacterEvents(units.charId(subjectUnit), units.charId(unitId))) return

        attack.targets.addTarget(
            units.xPos(unitId), units.yPos(unitId), units.index(unitId), units.charId(unitId),
        )
    }

    /**
     * Ported from src/bmtarget.c: MakeTalkTargetList
     *
     * Makes the list of the units next to [unit] it can talk to, from a cleared range map.
     */
    public fun makeTalkTargetList(unit: Int) {
        val x = units.xPos(unit)
        val y = units.yPos(unit)

        subjectUnit = unit

        attack.map.bmMapFill(attack.map.range, 0)

        forEachAdjacentUnit(x, y) { tryAddUnitToTalkTargetList(it) }
    }

    /**
     * Ported from src/bmtarget.c: MakeTargetListForSupport
     *
     * Makes the list of [unit]'s supporters that stand next to it, can support with it now
     * ([Hooks.canUnitSupportNow]) and are available (not dead, undeployed, rescued or
     * `US_BIT16`, not berserk or asleep), with the supporter's slot as the target's `extra`.
     * The range map is not touched. Verified at f4181 for the empty list: Eirika's supporters
     * are all too far, so the distance guard ends each; the rest is covered by synthetic tests.
     */
    public fun makeTargetListForSupport(unit: Int) {
        subjectUnit = unit

        targets.initTargets(units.xPos(unit), units.yPos(unit))

        val count = support.getUnitSupporterCount(unit)

        for (i in 0 until count) {
            val offset = support.getUnitSupporterUnit(unit, i)
            if (offset < 0) continue
            val other = s8(units.bytes, offset + UnitTable.OFF_INDEX) and 0xFF

            val distance = Math.abs(units.xPos(unit) - units.xPos(other)) +
                Math.abs(units.yPos(unit) - units.yPos(other))
            if (distance != 1) continue

            if (!hooks.canUnitSupportNow(unit, i)) continue

            if (units.state(other) and (UnitTable.US_UNAVAILABLE or UnitTable.US_RESCUED) != 0) {
                continue
            }

            val status = units.statusIndex(other)
            if (status == UnitTable.STATUS_BERSERK || status == UnitTable.STATUS_SLEEP) continue

            attack.targets.addTarget(units.xPos(other), units.yPos(other), units.index(other), i)
        }
    }

    /**
     * Transcribed from src/bmunit.c: CanUnitRescue (not verified: f4181 never reaches it, every
     * adjacent unit is an enemy)
     *
     * Whether the unit [actor]'s aid ([getUnitAid]) is at least the Con of the unit [target].
     */
    public fun canUnitRescue(actor: Int, target: Int): Boolean =
        getUnitAid(actor) >= unitCon(target)

    /**
     * Transcribed from src/bmunit.c: GetUnitAid (not verified: no capture reaches it)
     *
     * The Con a unit can carry: its Con less 1 on foot, 20 less its Con when mounted (a
     * `CA_MOUNTEDAID` class) and female, 25 less its Con when mounted and not.
     */
    public fun getUnitAid(id: Int): Int {
        val attributes = units.cAttributes(id)
        if (attributes and CA_MOUNTEDAID == 0) return unitCon(id) - 1
        return if (attributes and CA_FEMALE != 0) 20 - unitCon(id) else 25 - unitCon(id)
    }

    /** `UNIT_CON`: the class and character base Con plus the unit's `conBonus` (s8, 0x1A). */
    private fun unitCon(id: Int): Int {
        val offset = units.unitOffset(id)
        return rom.u8(u32(units.bytes, offset + UnitTable.OFF_CLASS) + 0x11).toByte() +
            rom.u8(u32(units.bytes, offset) + 0x13).toByte() + s8(units.bytes, offset + 0x1A)
    }

    private companion object {
        const val CLASS_PHANTOM = 0x51
        const val FACTION_MASK = 0xC0
        const val FACTION_BLUE = 0x00
        const val CA_MOUNTEDAID = 1 shl 0
        const val CA_SUPPLY = 1 shl 9
        const val CA_FEMALE = 1 shl 14
    }
}
