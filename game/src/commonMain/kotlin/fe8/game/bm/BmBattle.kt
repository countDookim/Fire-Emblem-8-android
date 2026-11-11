package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.put16
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8
import kotlin.math.abs

/**
 * The battle unit setup and stat computes of `src/bmbattle.c`: `InitBattleUnit`, the terrain
 * bonuses, `SetBattleUnitWeapon` and the `ComputeBattleUnit*` family, on the two globals
 * `gBattleActor` / `gBattleTarget` ([actor], [target]) and `gBattleStats` ([stats]).
 *
 * Units are addressed as a byte array plus the offset of the `struct Unit` in it (a [UnitTable]
 * array, or the embedded unit at offset 0 of a [BattleUnit]). Hit generation (rolls, unwind) is
 * not part of this class.
 *
 * @param rom the ROM holding the item, class, character, support and rule data.
 * @param table the unit arrays (`GetUnit`).
 * @param loader the unit stat helpers (equipped weapon, max HP).
 * @param playSt `gPlaySt` (`chapterWeatherId`).
 * @param bmSt `gBmSt` (`gameStateBits`).
 * @param terrainAt `gBmMapTerrain[y][x]`.
 */
public class BmBattle(
    private val rom: RomView,
    private val table: UnitTable,
    private val loader: UnitLoader,
    private val playSt: PlaySt,
    private val bmSt: BmSt,
    private val terrainAt: (y: Int, x: Int) -> Int,
) {
    private val items = ItemInfo(rom)

    /** `gBattleActor`. */
    public val actor: BattleUnit = BattleUnit()

    /** `gBattleTarget`. */
    public val target: BattleUnit = BattleUnit()

    /** `gBattleStats`. */
    public val stats: BattleStats = BattleStats()

    /** `gArenaState.playerWeapon` (arena battles only). */
    public var arenaPlayerWeapon: Int = 0

    /** `gArenaState.opponentWeapon` (arena battles only). */
    public var arenaOpponentWeapon: Int = 0

    /**
     * `GetBallistaItemAt(x, y)` (bmtrick.c, not ported): the item of the ballista at the tile.
     * Returns 0 until the trap system exists.
     */
    public var ballistaItemAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

    /**
     * `IsPositionMagicSealed(x, y)` (bmtrick.c, not ported): whether a magic seal trap covers the
     * tile. False until the trap system exists.
     */
    public var isPositionMagicSealed: (x: Int, y: Int) -> Boolean = { _, _ -> false }

    private val supportBonuses = IntArray(6)

    /**
     * Ported from src/bmbattle.c: InitBattleUnit
     *
     * Copies the unit at [srcOffset] of [src] into [unit] and sets its battle bonus stats,
     * previous level / exp, initial HP and clears its change fields; `-1` for [srcOffset] is the
     * C `NULL` and does nothing. As in the C, the `wexpMultiplier`, `weaponBroke` and `expGain`
     * resets hit [actor] and [target], not [unit].
     */
    public fun initBattleUnit(unit: BattleUnit, src: ByteArray, srcOffset: Int) {
        if (srcOffset < 0) return
        val bytes = unit.bytes
        src.copyInto(bytes, 0, srcOffset, srcOffset + UnitTable.UNIT_SIZE)

        put8(bytes, OFF_MAX_HP, loader.getUnitMaxHp(src, srcOffset))
        put8(bytes, OFF_POW, getUnitPower(src, srcOffset))
        put8(bytes, OFF_SKL, getUnitSkill(src, srcOffset))
        put8(bytes, OFF_SPD, getUnitSpeed(src, srcOffset))
        put8(bytes, OFF_DEF, getUnitDefense(src, srcOffset))
        put8(bytes, OFF_LCK, getUnitLuck(src, srcOffset))
        put8(bytes, OFF_RES, getUnitResistance(src, srcOffset))
        put8(bytes, OFF_CON_BONUS, unitCon(src, srcOffset))
        put8(bytes, OFF_MOV_BONUS, unitMov(src, srcOffset))

        unit.levelPrevious = s8(bytes, OFF_LEVEL)
        unit.expPrevious = u8(bytes, OFF_EXP)

        unit.hpInitial = s8(bytes, OFF_CUR_HP)
        unit.statusOut = -1

        unit.changeHP = 0
        unit.changePow = 0
        unit.changeSkl = 0
        unit.changeSpd = 0
        unit.changeDef = 0
        unit.changeRes = 0
        unit.changeLck = 0
        unit.changeCon = 0

        actor.wexpMultiplier = 0
        target.wexpMultiplier = 0

        unit.wTriangleHitBonus = 0
        unit.wTriangleDmgBonus = 0

        unit.nonZeroDamage = 0

        actor.weaponBroke = 0
        target.weaponBroke = 0

        actor.expGain = 0
        target.expGain = 0
    }

    /**
     * Transcribed from src/bmbattle.c: InitBattleUnitWithoutBonuses
     *   (not verified: not called by the traced battle)
     *
     * [initBattleUnit], then the unit's own stats (no item bonuses) and base constitution.
     */
    public fun initBattleUnitWithoutBonuses(unit: BattleUnit, src: ByteArray, srcOffset: Int) {
        initBattleUnit(unit, src, srcOffset)
        val bytes = unit.bytes
        put8(bytes, OFF_MAX_HP, s8(src, srcOffset + OFF_MAX_HP))
        put8(bytes, OFF_POW, s8(src, srcOffset + OFF_POW))
        put8(bytes, OFF_SKL, s8(src, srcOffset + OFF_SKL))
        put8(bytes, OFF_SPD, s8(src, srcOffset + OFF_SPD))
        put8(bytes, OFF_DEF, s8(src, srcOffset + OFF_DEF))
        put8(bytes, OFF_LCK, s8(src, srcOffset + OFF_LCK))
        put8(bytes, OFF_RES, s8(src, srcOffset + OFF_RES))
        put8(bytes, OFF_CON_BONUS, unitConBase(src, srcOffset))
    }

    /**
     * Ported from src/bmbattle.c: SetBattleUnitTerrainBonuses
     *
     * Sets [BattleUnit.terrainId] to [terrain] and the avoid, defense and resistance bonuses from
     * the unit's class lookup tables.
     */
    public fun setBattleUnitTerrainBonuses(unit: BattleUnit, terrain: Int) {
        unit.terrainId = terrain
        val cls = u32(unit.bytes, OFF_CLASS)
        unit.terrainAvoid = rom.u8(rom.u32(cls + 0x44) + unit.terrainId)
        unit.terrainDefense = rom.u8(rom.u32(cls + 0x48) + unit.terrainId)
        unit.terrainResistance = rom.u8(rom.u32(cls + 0x4C) + unit.terrainId)
    }

    /**
     * Ported from src/bmbattle.c: SetBattleUnitTerrainBonusesAuto
     *
     * [setBattleUnitTerrainBonuses] with the terrain under the unit.
     */
    public fun setBattleUnitTerrainBonusesAuto(unit: BattleUnit) {
        setBattleUnitTerrainBonuses(unit, terrainAt(unit.unitY, unit.unitX))
    }

    /**
     * Ported from src/bmbattle.c: SetBattleUnitWeapon
     *
     * Picks the weapon of [unit] from [itemSlot] ([BU_ISLOT_AUTO] for the equipped one), then
     * applies the range, magic-sword and status restrictions unless `BATTLE_CONFIG_BIT2` is set in
     * [stats].
     */
    public fun setBattleUnitWeapon(unit: BattleUnit, itemSlot: Int) {
        val bytes = unit.bytes
        var slot = itemSlot
        if (slot == BU_ISLOT_AUTO) slot = getUnitEquippedWeaponSlot(bytes, 0)

        if ((StructCodec.u32(bytes, OFF_STATE) and US_IN_BALLISTA) != 0) slot = BU_ISLOT_BALLISTA

        unit.canCounter = 1

        when (slot) {
            in 0..4 -> {
                unit.weaponSlotIndex = slot
                unit.weapon = u16(bytes, OFF_ITEMS + 2 * slot)
            }
            BU_ISLOT_5 -> {
                unit.weaponSlotIndex = 0xFF
                unit.weapon = bmSt.umTmpItem
            }
            BU_ISLOT_ARENA_PLAYER -> {
                unit.weaponSlotIndex = 0
                unit.weapon = arenaPlayerWeapon
                unit.canCounter = 0
            }
            BU_ISLOT_ARENA_OPPONENT -> {
                unit.weaponSlotIndex = 0
                unit.weapon = arenaOpponentWeapon
                unit.canCounter = 0
            }
            BU_ISLOT_BALLISTA -> {
                unit.weaponSlotIndex = 0xFF
                unit.weapon = ballistaItemAt(unit.unitX, unit.unitY)
                unit.canCounter = 0
            }
            else -> {
                unit.weaponSlotIndex = 0xFF
                unit.weapon = 0
                unit.canCounter = 0
            }
        }

        unit.weaponBefore = unit.weapon
        unit.weaponAttributes = items.getItemAttributes(unit.weapon)
        unit.weaponType = items.getItemType(unit.weapon)

        if ((stats.config and BattleStats.CONFIG_BIT2) == 0) {
            if ((unit.weaponAttributes and ItemInfo.IA_MAGICDAMAGE) != 0) {
                when (items.getItemIndex(unit.weapon)) {
                    ITEM_SWORD_WINDSWORD ->
                        if (stats.range == 2) {
                            unit.weaponType = ITYPE_ANIMA
                        } else {
                            unit.weaponAttributes = unit.weaponAttributes and
                                ItemInfo.IA_MAGICDAMAGE.inv()
                        }
                    ITEM_SWORD_LIGHTBRAND ->
                        if (stats.range == 2) {
                            unit.weaponType = ITYPE_LIGHT
                        } else {
                            unit.weaponAttributes = unit.weaponAttributes and
                                ItemInfo.IA_MAGICDAMAGE.inv()
                        }
                    ITEM_SWORD_RUNESWORD -> unit.weaponType = ITYPE_DARK
                }
            }

            if (!isItemCoveringRange(unit.weapon, stats.range) || unit.weaponSlotIndex == 0xFF) {
                unit.weapon = 0
                unit.canCounter = 0
            }

            when (unit.unitStatusIndex) {
                STATUS_SLEEP, STATUS_PETRIFY, STATUS_13 -> {
                    unit.weapon = 0
                    unit.canCounter = 0
                }
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: SetBattleUnitWeaponBallista
     *   (not verified: not called by the traced battle)
     *
     * Gives [unit] the ballista item of its tile and no counter.
     */
    public fun setBattleUnitWeaponBallista(unit: BattleUnit) {
        unit.weapon = ballistaItemAt(unit.unitX, unit.unitY)
        unit.weaponBefore = unit.weapon
        unit.weaponAttributes = items.getItemAttributes(unit.weapon)
        unit.weaponType = items.getItemType(unit.weapon)
        unit.canCounter = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitStats
     *
     * Computes all base battle stats of [attacker] against [defender].
     */
    public fun computeBattleUnitStats(attacker: BattleUnit, defender: BattleUnit) {
        computeBattleUnitDefense(attacker, defender)
        computeBattleUnitAttack(attacker, defender)
        computeBattleUnitSpeed(attacker)
        computeBattleUnitHitRate(attacker)
        computeBattleUnitAvoidRate(attacker)
        computeBattleUnitCritRate(attacker)
        computeBattleUnitDodgeRate(attacker)
        computeBattleUnitSupportBonuses(attacker, defender)
        computeBattleUnitWeaponRankBonuses(attacker)
        computeBattleUnitStatusBonuses(attacker)
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitEffectiveStats
     *
     * Computes the effective hit / crit / silencer rates and the special weapon adjustments.
     */
    public fun computeBattleUnitEffectiveStats(attacker: BattleUnit, defender: BattleUnit) {
        computeBattleUnitEffectiveHitRate(attacker, defender)
        computeBattleUnitEffectiveCritRate(attacker, defender)
        computeBattleUnitSilencerRate(attacker, defender)
        computeBattleUnitSpecialWeaponStats(attacker, defender)
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitSupportBonuses
     *
     * Adds the support bonuses of [attacker] unless this is a clear-weather arena battle.
     */
    public fun computeBattleUnitSupportBonuses(attacker: BattleUnit, defender: BattleUnit) {
        if ((stats.config and BattleStats.CONFIG_ARENA) == 0 || playSt.chapterWeatherId != 0) {
            val bonuses = supportBonuses
            getUnitSupportBonuses(attacker.bytes, 0, bonuses)
            attacker.battleAttack += bonuses[0]
            attacker.battleDefense += bonuses[1]
            attacker.battleHitRate += bonuses[2]
            attacker.battleAvoidRate += bonuses[3]
            attacker.battleCritRate += bonuses[4]
            attacker.battleDodgeRate += bonuses[5]
        }
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitDefense
     *
     * Defense (against magic: resistance) of [attacker] with its terrain bonus, by the damage
     * type of the [defender]'s weapon.
     */
    public fun computeBattleUnitDefense(attacker: BattleUnit, defender: BattleUnit) {
        val attrs = items.getItemAttributes(defender.weapon)
        attacker.battleDefense = if ((attrs and ItemInfo.IA_MAGICDAMAGE) != 0 ||
            (attrs and ItemInfo.IA_MAGIC) != 0
        ) {
            attacker.terrainResistance + s8(attacker.bytes, OFF_RES)
        } else {
            attacker.terrainDefense + s8(attacker.bytes, OFF_DEF)
        }
    }

    /**
     * Transcribed from src/bmbattle.c: ComputeBattleUnitBaseDefense
     *   (not verified: not called by the traced battle)
     *
     * Terrain defense plus defense, ignoring the opposing weapon.
     */
    public fun computeBattleUnitBaseDefense(unit: BattleUnit) {
        unit.battleDefense = unit.terrainDefense + s8(unit.bytes, OFF_DEF)
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitAttack
     *
     * Weapon might with the triangle bonus, multiplied against effective targets, plus strength.
     */
    public fun computeBattleUnitAttack(attacker: BattleUnit, defender: BattleUnit) {
        attacker.battleAttack = items.getItemMight(attacker.weapon) + attacker.wTriangleDmgBonus
        var attack = attacker.battleAttack

        if (isUnitEffectiveAgainst(attacker.bytes, 0, defender.bytes, 0)) {
            attack = (attacker.battleAttack * 3).toShort().toInt()
        }

        if (isItemEffectiveAgainst(attacker.weapon, defender.bytes, 0)) {
            attack = attacker.battleAttack
            attack = when (items.getItemIndex(attacker.weapon)) {
                ITEM_SWORD_AUDHULMA, ITEM_LANCE_VIDOFNIR, ITEM_AXE_GARM, ITEM_BOW_NIDHOGG,
                ITEM_ANIMA_EXCALIBUR, ITEM_LIGHT_IVALDI, ITEM_SWORD_SIEGLINDE,
                ITEM_LANCE_SIEGMUND,
                -> (attack * 2).toShort().toInt()
                else -> (attack * 3).toShort().toInt()
            }
        }

        attacker.battleAttack = attack
        attacker.battleAttack += s8(attacker.bytes, OFF_POW)

        if (items.getItemIndex(attacker.weapon) == ITEM_MONSTER_STONE) attacker.battleAttack = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitSpeed
     *
     * Speed minus the weight of the weapon beyond the unit's constitution, at least 0.
     */
    public fun computeBattleUnitSpeed(unit: BattleUnit) {
        var effectiveWeight = items.getItemWeight(unit.weaponBefore)
        effectiveWeight -= s8(unit.bytes, OFF_CON_BONUS)
        if (effectiveWeight < 0) effectiveWeight = 0
        unit.battleSpeed = s8(unit.bytes, OFF_SPD) - effectiveWeight
        if (unit.battleSpeed < 0) unit.battleSpeed = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitHitRate
     *
     * `skill * 2 + weapon hit + luck / 2 + triangle bonus`.
     */
    public fun computeBattleUnitHitRate(unit: BattleUnit) {
        unit.battleHitRate = s8(unit.bytes, OFF_SKL) * 2 + items.getItemHit(unit.weapon) +
            s8(unit.bytes, OFF_LCK) / 2 + unit.wTriangleHitBonus
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitAvoidRate
     *
     * `speed * 2 + terrain avoid + luck`, at least 0.
     */
    public fun computeBattleUnitAvoidRate(unit: BattleUnit) {
        unit.battleAvoidRate = unit.battleSpeed * 2 + unit.terrainAvoid + s8(unit.bytes, OFF_LCK)
        if (unit.battleAvoidRate < 0) unit.battleAvoidRate = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitCritRate
     *
     * Weapon crit plus skill / 2, plus 15 for crit-bonus classes.
     */
    public fun computeBattleUnitCritRate(unit: BattleUnit) {
        unit.battleCritRate = items.getItemCrit(unit.weapon) + s8(unit.bytes, OFF_SKL) / 2
        if ((unitCAttributes(unit.bytes, 0) and CA_CRITBONUS) != 0) unit.battleCritRate += 15
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitDodgeRate
     *
     * The unit's luck.
     */
    public fun computeBattleUnitDodgeRate(unit: BattleUnit) {
        unit.battleDodgeRate = s8(unit.bytes, OFF_LCK)
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitEffectiveHitRate
     *
     * Hit rate minus the defender's avoid rate, clamped to 0..100.
     */
    public fun computeBattleUnitEffectiveHitRate(attacker: BattleUnit, defender: BattleUnit) {
        attacker.battleEffectiveHitRate = attacker.battleHitRate - defender.battleAvoidRate
        if (attacker.battleEffectiveHitRate > 100) attacker.battleEffectiveHitRate = 100
        if (attacker.battleEffectiveHitRate < 0) attacker.battleEffectiveHitRate = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitEffectiveCritRate
     *
     * Crit rate minus the defender's dodge rate, 0 for a Monster Stone attacker and 0 against
     * a defender carrying a crit-negating item.
     */
    public fun computeBattleUnitEffectiveCritRate(attacker: BattleUnit, defender: BattleUnit) {
        attacker.battleEffectiveCritRate = attacker.battleCritRate - defender.battleDodgeRate
        if (items.getItemIndex(attacker.weapon) == ITEM_MONSTER_STONE) {
            attacker.battleEffectiveCritRate = 0
        }
        if (attacker.battleEffectiveCritRate < 0) attacker.battleEffectiveCritRate = 0

        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            val item = u16(defender.bytes, OFF_ITEMS + 2 * i)
            if (item == 0) break
            if ((items.getItemAttributes(item) and ItemInfo.IA_NEGATE_CRIT) != 0) {
                attacker.battleEffectiveCritRate = 0
                break
            }
        }
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitSilencerRate
     *
     * 50 for assassins, 25 against bosses, 0 against lethality-negating units.
     */
    public fun computeBattleUnitSilencerRate(attacker: BattleUnit, defender: BattleUnit) {
        if ((unitCAttributes(attacker.bytes, 0) and CA_ASSASSIN) == 0) {
            attacker.battleSilencerRate = 0
        } else {
            attacker.battleSilencerRate = 50
            val defenderAttrs = unitCAttributes(defender.bytes, 0)
            if ((defenderAttrs and CA_BOSS) != 0) attacker.battleSilencerRate = 25
            if ((defenderAttrs and CA_NEGATE_LETHALITY) != 0) attacker.battleSilencerRate = 0
        }
    }

    /**
     * Transcribed from src/bmbattle.c: ComputeBattleUnitWeaponRankBonuses
     *   (not verified: no traced unit has an S rank weapon)
     *
     * +5 hit and crit for an S rank in the weapon's type.
     */
    public fun computeBattleUnitWeaponRankBonuses(unit: BattleUnit) {
        if (unit.weapon != 0) {
            val wType = items.getItemType(unit.weapon)
            if (wType < 8 && u8(unit.bytes, OFF_RANKS + wType) >= WPN_EXP_S) {
                unit.battleHitRate += 5
                unit.battleCritRate += 5
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: ComputeBattleUnitStatusBonuses
     *   (not verified: no traced unit has a status effect)
     *
     * The +10 of the attack, defense, crit and avoid status effects.
     */
    public fun computeBattleUnitStatusBonuses(unit: BattleUnit) {
        when (unit.unitStatusIndex) {
            STATUS_ATTACK -> unit.battleAttack += 10
            STATUS_DEFENSE -> unit.battleDefense += 10
            STATUS_CRIT -> unit.battleCritRate += 10
            STATUS_AVOID -> unit.battleAvoidRate += 10
        }
    }

    /**
     * Transcribed from src/bmbattle.c: ComputeBattleUnitSpecialWeaponStats
     *   (not verified: no special weapon or petrified target in the trace)
     *
     * Magic swords halve strength and lose crit; half-HP weapons, defense-negating weapons and
     * petrified targets override attack, defense and rates.
     */
    public fun computeBattleUnitSpecialWeaponStats(attacker: BattleUnit, defender: BattleUnit) {
        if ((attacker.weaponAttributes and ItemInfo.IA_MAGICDAMAGE) != 0) {
            when (items.getItemIndex(attacker.weapon)) {
                ITEM_SWORD_LIGHTBRAND, ITEM_SWORD_RUNESWORD, ITEM_SWORD_WINDSWORD -> {
                    attacker.battleAttack -= s8(attacker.bytes, OFF_POW) / 2
                    attacker.battleCritRate = 0
                    attacker.battleEffectiveCritRate = 0
                }
            }
        } else {
            if (items.getItemWeaponEffect(attacker.weaponBefore) == WPN_EFFECT_HPHALVE) {
                attacker.battleAttack = (s8(defender.bytes, OFF_CUR_HP) + 1) shr 1
                if (attacker.battleAttack == 0) attacker.battleAttack = 1
                defender.battleDefense = 0
                attacker.battleCritRate = 0
                attacker.battleEffectiveCritRate = 0
            }

            if ((attacker.weaponAttributes and ItemInfo.IA_NEGATE_DEFENSE) != 0) {
                defender.battleDefense = 0
            }

            if (defender.unitStatusIndex == STATUS_PETRIFY ||
                defender.unitStatusIndex == STATUS_13
            ) {
                attacker.battleEffectiveHitRate = 100
                attacker.battleEffectiveCritRate += 30
                if (attacker.battleEffectiveCritRate > 100) attacker.battleEffectiveCritRate = 100
            }
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleApplyWeaponTriangleEffect
     *
     * Sets the weapon triangle hit / damage bonuses of both units from the ROM rule table, with
     * the reaver inversion.
     */
    public fun battleApplyWeaponTriangleEffect(attacker: BattleUnit, defender: BattleUnit) {
        var rule = WEAPON_TRIANGLE_RULES
        while (rom.u8(rule).toByte() >= 0) {
            if (attacker.weaponType == rom.u8(rule) &&
                defender.weaponType == rom.u8(rule + 1)
            ) {
                val hitBonus = rom.u8(rule + 2).toByte().toInt()
                val damageBonus = rom.u8(rule + 3).toByte().toInt()
                attacker.wTriangleHitBonus = hitBonus
                attacker.wTriangleDmgBonus = damageBonus
                defender.wTriangleHitBonus = -hitBonus
                defender.wTriangleDmgBonus = -damageBonus
                break
            }
            rule += 4
        }

        if ((attacker.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE) != 0) {
            battleApplyReaverEffect(attacker, defender)
        }
        if ((defender.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE) != 0) {
            battleApplyReaverEffect(attacker, defender)
        }
    }

    /**
     * Transcribed from src/bmbattle.c: BattleApplyReaverEffect
     *   (not verified: no reaver weapon in the trace)
     *
     * Inverts and doubles the triangle bonuses unless both weapons are reavers.
     */
    public fun battleApplyReaverEffect(attacker: BattleUnit, defender: BattleUnit) {
        if ((attacker.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE) == 0 ||
            (defender.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE) == 0
        ) {
            attacker.wTriangleHitBonus = -(attacker.wTriangleHitBonus * 2)
            attacker.wTriangleDmgBonus = -(attacker.wTriangleDmgBonus * 2)
            defender.wTriangleHitBonus = -(defender.wTriangleHitBonus * 2)
            defender.wTriangleDmgBonus = -(defender.wTriangleDmgBonus * 2)
        }
    }

    /**
     * Transcribed from src/bmbattle.c: BattleInitTargetCanCounter
     *   (not verified: no gorgon egg, uncounterable weapon or berserk actor in the trace)
     *
     * Clears the target's weapon and counter for gorgon eggs, uncounterable weapons and a berserk
     * blue actor attacking a blue target.
     */
    public fun battleInitTargetCanCounter() {
        val cls = rom.u8(u32(target.bytes, OFF_CLASS) + 4)
        if (cls == UnitLoader.CLASS_GORGONEGG || cls == UnitLoader.CLASS_GORGONEGG2) {
            target.weapon = 0
            target.canCounter = 0
        }

        if (((actor.weaponAttributes or target.weaponAttributes) and
                ItemInfo.IA_UNCOUNTERABLE) != 0
        ) {
            target.weapon = 0
            target.canCounter = 0
        }

        if (actor.unitStatusIndex == STATUS_BERSERK) {
            if ((s8(actor.bytes, OFF_INDEX) and 0xC0) == UnitLoader.FACTION_BLUE &&
                (s8(target.bytes, OFF_INDEX) and 0xC0) == UnitLoader.FACTION_BLUE
            ) {
                target.weapon = 0
                target.canCounter = 0
            }
        }
    }

    /**
     * Ported from src/bmbattle.c: BattleUnitTargetCheckCanCounter
     *
     * A target that cannot counter gets 0xFF attack and rates.
     */
    public fun battleUnitTargetCheckCanCounter(unit: BattleUnit) {
        if (unit.canCounter == 0) {
            unit.battleAttack = 0xFF
            unit.battleHitRate = 0xFF
            unit.battleEffectiveHitRate = 0xFF
            unit.battleCritRate = 0xFF
            unit.battleEffectiveCritRate = 0xFF
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitSupportBonuses via src/bmreliance.c (not verified:
     *   the traced units have no supporter on the map, srcOffset only the empty sum is compared)
     *
     * Writes the halved support bonuses (attack, defense, hit, avoid, crit, dodge) of the unit at
     * [offset] of [bytes] into [bonusOut] (6 ints); returns the support bit mask of the C. Unlike
     * the C, an
     * affinity missing from `gAffinityBonuses` adds nothing (the C reads past the table).
     */
    internal fun getUnitSupportBonuses(bytes: ByteArray, offset: Int, bonusOut: IntArray): Int {
        bonusOut.fill(0)
        var result = 0
        val count = supporterCount(bytes, offset)
        val faction = s8(bytes, offset + OFF_INDEX) and 0xC0
        val myAffinity = rom.u8(u32(bytes, offset + OFF_CHARACTER) + 9)
        val myChar = rom.u8(u32(bytes, offset + OFF_CHARACTER) + 4)

        for (i in 0 until count) {
            result = result shr 1
            val other = supporterUnit(bytes, offset, i, faction)
            if (other < 0) continue
            val tableBytes = table.bytes

            // The 3-tile support range is not enforced while game-state bit 0x40 is set.
            if ((bmSt.gameStateBits and 0x40) == 0) {
                val distance = abs(s8(bytes, offset + OFF_X) - s8(tableBytes, other + OFF_X)) +
                    abs(s8(bytes, offset + OFF_Y) - s8(tableBytes, other + OFF_Y))
                if (distance > SUPPORT_BONUSES_MAX_DISTANCE) continue
            }

            val unavailable = UnitTable.US_UNAVAILABLE or US_RESCUED
            if ((StructCodec.u32(tableBytes, other + OFF_STATE) and unavailable) != 0) continue

            val otherSupportLevel =
                supportLevel(tableBytes, other, supporterNum(tableBytes, other, myChar))
            applyAffinitySupportBonuses(
                bonusOut, rom.u8(u32(tableBytes, other + OFF_CHARACTER) + 9), otherSupportLevel,
            )

            val mySupportLevel = supportLevel(bytes, offset, i)
            applyAffinitySupportBonuses(bonusOut, myAffinity, mySupportLevel)

            if (otherSupportLevel != 0 && mySupportLevel != 0) result += 1 shl (count - 1)
        }

        for (i in 0 until 6) bonusOut[i] = (bonusOut[i] and 0xFF) / 2
        return result
    }

    /** The support data table pointer of the unit's character, 0 for none. */
    private fun supportData(bytes: ByteArray, offset: Int): Int =
        rom.u32(u32(bytes, offset + OFF_CHARACTER) + 0x2C)

    /** The number of supporters the unit's character has in its support data. */
    private fun supporterCount(bytes: ByteArray, offset: Int): Int {
        val d = supportData(bytes, offset)
        return if (d == 0) 0 else rom.u8(d + 0x15)
    }

    /** The character id of supporter [num] of the unit, 0 when there is no support data. */
    private fun supporterCharacter(bytes: ByteArray, offset: Int, num: Int): Int {
        val d = supportData(bytes, offset)
        return if (d == 0) 0 else rom.u8(d + num)
    }

    /** `GetUnitSupporterUnit`: the table offset of the supporter, or -1. */
    private fun supporterUnit(bytes: ByteArray, offset: Int, num: Int, faction: Int): Int {
        val charId = supporterCharacter(bytes, offset, num)
        for (id in faction + 1 until faction + 0x40) {
            if (!table.isValid(id)) continue
            if (table.charId(id) == charId) return table.unitOffset(id)
        }
        return -1
    }

    /** The support slot of the unit that holds character [charId], or -1 when none does. */
    private fun supporterNum(bytes: ByteArray, offset: Int, charId: Int): Int {
        val count = supporterCount(bytes, offset)
        for (i in 0 until count) {
            if (supporterCharacter(bytes, offset, i) == charId) return i
        }
        return -1
    }

    /**
     * The support level of the unit's support slot [num] from its support points: 0 below C,
     * 1 for B (above 80), 2 for A (above 160), 3 for S (above 240).
     */
    private fun supportLevel(bytes: ByteArray, offset: Int, num: Int): Int {
        val supportExp = u8(bytes, offset + OFF_SUPPORTS + num)
        return when {
            supportExp > 240 -> 3
            supportExp > 160 -> 2
            supportExp > 80 -> 1
            else -> 0
        }
    }

    /**
     * Adds the affinity table row of [affinity] (`gAffinityBonuses`, six bonus bytes per row) at
     * [level] to [bonusOut]; nothing for an affinity without a row.
     */
    private fun applyAffinitySupportBonuses(bonusOut: IntArray, affinity: Int, level: Int) {
        var row = AFFINITY_BONUSES
        while (rom.u8(row) != 0) {
            if (rom.u8(row) == affinity) {
                for (i in 0 until 6) {
                    bonusOut[i] = (bonusOut[i] + level * rom.u8(row + 1 + i)) and 0xFF
                }
                return
            }
            row += 7
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitPower (not verified: no traced unit has a power
     *   bonus item, srcOffset the bonus term is not exercised)
     *
     * `pow` plus the power bonus of the equipped weapon of the unit at [offset] of [bytes].
     */
    internal fun getUnitPower(bytes: ByteArray, offset: Int): Int =
        s8(bytes, offset + OFF_POW) +
            items.getItemPowBonus(loader.getUnitEquippedWeapon(bytes, offset))

    /**
     * Transcribed from src/bmunit.c: GetUnitSkill (not verified: no bonus item and no rescuing
     *   unit in the trace)
     */
    internal fun getUnitSkill(bytes: ByteArray, offset: Int): Int {
        val item = loader.getUnitEquippedWeapon(bytes, offset)
        val skill = s8(bytes, offset + OFF_SKL)
        return if ((StructCodec.u32(bytes, offset + OFF_STATE) and UnitLoader.US_RESCUING) != 0) {
            skill / 2 + items.getItemSklBonus(item)
        } else {
            skill + items.getItemSklBonus(item)
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitSpeed (not verified: no bonus item and no rescuing
     *   unit in the trace)
     */
    internal fun getUnitSpeed(bytes: ByteArray, offset: Int): Int {
        val item = loader.getUnitEquippedWeapon(bytes, offset)
        val speed = s8(bytes, offset + OFF_SPD)
        return if ((StructCodec.u32(bytes, offset + OFF_STATE) and UnitLoader.US_RESCUING) != 0) {
            speed / 2 + items.getItemSpdBonus(item)
        } else {
            speed + items.getItemSpdBonus(item)
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitDefense (not verified: no bonus item in the trace)
     */
    internal fun getUnitDefense(bytes: ByteArray, offset: Int): Int =
        s8(bytes, offset + OFF_DEF) +
            items.getItemDefBonus(loader.getUnitEquippedWeapon(bytes, offset))

    /**
     * Transcribed from src/bmunit.c: GetUnitResistance (not verified: no bonus item and no
     *   barrier in the trace)
     */
    internal fun getUnitResistance(bytes: ByteArray, offset: Int): Int =
        s8(bytes, offset + OFF_RES) +
            items.getItemResBonus(loader.getUnitEquippedWeapon(bytes, offset)) +
            (u8(bytes, offset + OFF_TORCH_BARRIER) shr 4)

    /**
     * Transcribed from src/bmunit.c: GetUnitLuck (not verified: no bonus item in the trace)
     */
    internal fun getUnitLuck(bytes: ByteArray, offset: Int): Int =
        s8(bytes, offset + OFF_LCK) +
            items.getItemLckBonus(loader.getUnitEquippedWeapon(bytes, offset))

    /** `UNIT_CON_BASE`: class base con plus character base con. */
    private fun unitConBase(bytes: ByteArray, offset: Int): Int =
        rom.u8(u32(bytes, offset + OFF_CLASS) + 0x11).toByte() +
            rom.u8(u32(bytes, offset + OFF_CHARACTER) + 0x13).toByte()

    /** `UNIT_CON`. */
    private fun unitCon(bytes: ByteArray, offset: Int): Int =
        unitConBase(bytes, offset) + s8(bytes, offset + OFF_CON_BONUS)

    /** `UNIT_MOV`. */
    private fun unitMov(bytes: ByteArray, offset: Int): Int =
        s8(bytes, offset + OFF_MOV_BONUS) + rom.u8(u32(bytes, offset + OFF_CLASS) + 0x12).toByte()

    /** `UNIT_CATTRIBUTES`. */
    private fun unitCAttributes(bytes: ByteArray, offset: Int): Int =
        rom.u32(u32(bytes, offset + OFF_CHARACTER) + 0x28) or
            rom.u32(u32(bytes, offset + OFF_CLASS) + 0x28)

    /**
     * Transcribed from src/bmitem.c: GetUnitEquippedWeaponSlot (not verified: see
     *   [setBattleUnitWeapon])
     *
     * The first item slot of the unit at [offset] of [bytes] that passes
     * `CanUnitUseWeaponNow`, or -1.
     */
    internal fun getUnitEquippedWeaponSlot(bytes: ByteArray, offset: Int): Int {
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            if (canUnitUseWeaponNow(bytes, offset, u16(bytes, offset + OFF_ITEMS + 2 * i))) return i
        }
        return -1
    }

    /**
     * Whether the unit may wield [item] right now: it must be a weapon, and a magic weapon is
     * refused while the unit is silenced or on a magic-sealed tile.
     */
    private fun canUnitUseWeaponNow(bytes: ByteArray, offset: Int, item: Int): Boolean {
        if (item == 0) return false
        val itemAttributes = items.getItemAttributes(item)
        if ((itemAttributes and ItemInfo.IA_WEAPON) == 0) return false
        if ((itemAttributes and ItemInfo.IA_MAGIC) != 0 && isUnitMagicSealed(bytes, offset)) {
            return false
        }
        return loader.canUnitUseWeapon(bytes, offset, item)
    }

    /** Whether the unit is silenced or stands on a magic-sealed tile. */
    private fun isUnitMagicSealed(bytes: ByteArray, offset: Int): Boolean =
        (u8(bytes, offset + OFF_STATUS) and 0xF) == UnitLoader.UNIT_STATUS_SILENCED ||
            isPositionMagicSealed(s8(bytes, offset + OFF_X), s8(bytes, offset + OFF_Y))

    /** Whether [range] is within the item's minimum and maximum range. */
    private fun isItemCoveringRange(item: Int, range: Int): Boolean =
        items.getItemMinRange(item) <= range && range <= items.getItemMaxRange(item)

    /**
     * Whether [item] is effective against the unit at [offset] of [bytes]: its class is in the
     * item's effectiveness list. For the flier lists the match only counts when no item the unit
     * carries negates flying.
     */
    private fun isItemEffectiveAgainst(item: Int, bytes: ByteArray, offset: Int): Boolean {
        val cls = u32(bytes, offset + OFF_CLASS)
        if (cls == 0) return false
        val classId = rom.u8(cls + 4)
        val eff = items.getItemEffectiveness(item)
        if (eff == 0) return false
        var entry = eff
        while (rom.u8(entry) != 0) {
            if (rom.u8(entry) == classId) {
                if (eff != EFFECTIVENESS_FLIER && eff != EFFECTIVENESS_FLIER_AND_MONSTERS) {
                    return true
                }
                var attributes = 0
                for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
                    attributes = attributes or
                        items.getItemAttributes(u16(bytes, offset + OFF_ITEMS + 2 * i))
                }
                return (attributes and ItemInfo.IA_NEGATE_FLYING) == 0
            }
            entry++
        }
        return false
    }

    /**
     * Whether the actor's class is 0x2B or 0x2C and the target's class is in the monster
     * effectiveness list (`ItemEffectiveness_Monsters`).
     */
    private fun isUnitEffectiveAgainst(
        actorBytes: ByteArray,
        actorOffset: Int,
        targetBytes: ByteArray,
        targetOffset: Int,
    ): Boolean {
        val actorClass = rom.u8(u32(actorBytes, actorOffset + OFF_CLASS) + 4)
        val targetClass = rom.u8(u32(targetBytes, targetOffset + OFF_CLASS) + 4)
        if (actorClass != 0x2B && actorClass != 0x2C) return false
        var entry = EFFECTIVENESS_MONSTERS
        while (rom.u8(entry) != 0) {
            if (rom.u8(entry) == targetClass) return true
            entry++
        }
        return false
    }

    /** `gUnknown_03003060`: the arena level (`UNIT_ARENA_LEVEL`) of the last unit updated. */
    public var arenaLevelLatch: Int = 0

    /**
     * `PidStatsAddExpGained(pid, exp)` (bmsave-bwl.c, not ported to this class; M6-C): called by
     * [updateUnitFromBattle] for a non-zero `expGain`.
     */
    public var pidStatsAddExpGained: (pid: Int, exp: Int) -> Unit =
        { _, _ -> error("PidStatsAddExpGained is not wired (M6-C)") }

    /**
     * `GetTrap(ballistaIndex)->data[TRAP_EXTDATA_BLST_ITEMUSES] = uses` (bmtrick.c, not ported):
     * called by [battleApplyBallistaUpdates] in a ballista battle.
     */
    public var setBallistaItemUses: (ballistaIndex: Int, uses: Int) -> Unit =
        { _, _ -> error("traps are not ported (M6-H)") }

    /**
     * Transcribed from src/bmbattle.c: BattleApplyGameStateUpdates
     *   (not verified: the Prologue never runs it; the f15075 unit-table update of
     *   ram_trace_prologue_battle_end is EventBattleReloadBmStatus)
     *
     * [battleApplyUnitUpdates] and [battleApplyBallistaUpdates]. The two debug print calls are
     * no-ops and omitted. Untested: no trace runs the status, level-up, rank gain, exp gain,
     * ballista or obstacle branches.
     */
    public fun battleApplyGameStateUpdates() {
        battleApplyUnitUpdates()
        battleApplyBallistaUpdates()
    }

    /**
     * Transcribed from src/bmbattle.c: BattleApplyUnitUpdates
     *   (not verified: the Prologue never runs it; the f15075 unit-table update of
     *   ram_trace_prologue_battle_end is EventBattleReloadBmStatus)
     *
     * Writes the used weapons back into the battle units' items, then the battle units into the
     * unit table. A target without a unit (`UpdateObstacleFromBattle`) is not ported.
     */
    public fun battleApplyUnitUpdates() {
        val actorOff = table.unitOffset(s8(actor.bytes, OFF_INDEX))
        val targetOff = table.unitOffset(s8(target.bytes, OFF_INDEX))
        if (actor.canCounter != 0) {
            put16(actor.bytes, OFF_ITEMS + 2 * actor.weaponSlotIndex, actor.weapon)
        }
        if (target.canCounter != 0) {
            put16(target.bytes, OFF_ITEMS + 2 * target.weaponSlotIndex, target.weapon)
        }
        updateUnitFromBattle(actorOff, actor)
        if (targetOff >= 0) {
            updateUnitFromBattle(targetOff, target)
        } else {
            error("UpdateObstacleFromBattle is not ported")
        }
    }

    /**
     * Transcribed from src/bmbattle.c: UpdateUnitFromBattle
     *   (not verified: the Prologue never runs it; the f15075 unit-table update of
     *   ram_trace_prologue_battle_end is EventBattleReloadBmStatus)
     *
     * Copies level, exp, HP, state, stat changes, weapon exp and items of [unit] into the unit at
     * [offset] of the unit table, sets [arenaLevelLatch], the status, caps the stats and reports
     * the exp gain through [pidStatsAddExpGained].
     */
    public fun updateUnitFromBattle(offset: Int, unit: BattleUnit) {
        val tableBytes = table.bytes
        val unitBytes = unit.bytes
        put8(tableBytes, offset + OFF_LEVEL, u8(unitBytes, OFF_LEVEL))
        put8(tableBytes, offset + OFF_EXP, u8(unitBytes, OFF_EXP))
        put8(tableBytes, offset + OFF_CUR_HP, u8(unitBytes, OFF_CUR_HP))
        StructCodec.put32(tableBytes, offset + OFF_STATE, u32(unitBytes, OFF_STATE))
        arenaLevelLatch = (u32(tableBytes, offset + OFF_STATE) ushr 17) and 0x7
        if (unit.statusOut >= 0) {
            // SetUnitStatus
            val index = unit.statusOut
            val statusByte = if (index == 0) 0 else (index and 0xF) or (5 shl 4)
            put8(tableBytes, offset + OFF_STATUS, statusByte)
        }
        put8(tableBytes, offset + OFF_MAX_HP, s8(tableBytes, offset + OFF_MAX_HP) + unit.changeHP)
        put8(tableBytes, offset + OFF_POW, s8(tableBytes, offset + OFF_POW) + unit.changePow)
        put8(tableBytes, offset + OFF_SKL, s8(tableBytes, offset + OFF_SKL) + unit.changeSkl)
        put8(tableBytes, offset + OFF_SPD, s8(tableBytes, offset + OFF_SPD) + unit.changeSpd)
        put8(tableBytes, offset + OFF_DEF, s8(tableBytes, offset + OFF_DEF) + unit.changeDef)
        put8(tableBytes, offset + OFF_RES, s8(tableBytes, offset + OFF_RES) + unit.changeRes)
        put8(tableBytes, offset + OFF_LCK, s8(tableBytes, offset + OFF_LCK) + unit.changeLck)
        loader.unitCheckStatCaps(offset)
        val wexp = getBattleUnitUpdatedWeaponExp(unit)
        if (wexp > 0) put8(tableBytes, offset + OFF_RANKS + unit.weaponType, wexp)
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            put16(tableBytes, offset + OFF_ITEMS + 2 * i, u16(unitBytes, OFF_ITEMS + 2 * i))
        }
        unitRemoveInvalidItems(offset)
        if (unit.expGain != 0) {
            pidStatsAddExpGained(rom.u8(u32(tableBytes, offset + OFF_CHARACTER) + 4), unit.expGain)
        }
    }

    /**
     * Transcribed from src/bmunit.c: UnitRemoveInvalidItems (not verified: no trace runs it yet)
     *
     * Moves the items of the unit at [offset] up so that no empty slot precedes an item.
     */
    internal fun unitRemoveInvalidItems(offset: Int) {
        val tableBytes = table.bytes
        val kept = IntArray(UnitLoader.UNIT_ITEM_COUNT)
        var n = 0
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            val item = u16(tableBytes, offset + OFF_ITEMS + 2 * i)
            if (item != 0) kept[n++] = item
            put16(tableBytes, offset + OFF_ITEMS + 2 * i, 0)
        }
        for (i in 0 until n) put16(tableBytes, offset + OFF_ITEMS + 2 * i, kept[i])
    }

    /**
     * Transcribed from src/bmbattle.c: GetBattleUnitUpdatedWeaponExp (not verified: the Prologue
     *   never runs it; the f15075 update is EventBattleReloadBmStatus)
     *
     * The weapon exp [unit] has after the battle, or -1 where it gains none.
     */
    public fun getBattleUnitUpdatedWeaponExp(unit: BattleUnit): Int {
        val unitBytes = unit.bytes
        if ((s8(unitBytes, OFF_INDEX) and 0xC0) != UnitLoader.FACTION_BLUE) return -1
        if (u8(unitBytes, OFF_CUR_HP) == 0) return -1
        if ((playSt.chapterStateBits and PLAY_FLAG_EXTRA_MAP) != 0) return -1
        if ((bmSt.gameStateBits and 0x40) != 0) return -1
        if ((stats.config and BattleStats.CONFIG_ARENA) == 0) {
            if (unit.canCounter == 0) return -1
            val requiresWexp = ItemInfo.IA_WEAPON or ItemInfo.IA_STAFF
            if ((unit.weaponAttributes and requiresWexp) == 0) return -1
            if ((unit.weaponAttributes and (ItemInfo.IA_MAGICDAMAGE or ItemInfo.IA_LOCK_3)) != 0) {
                return -1
            }
        }
        var result = u8(unitBytes, OFF_RANKS + unit.weaponType)
        result += rom.u8(items.getItemData(items.getItemIndex(unit.weapon)) + 0x20) *
            unit.wexpMultiplier
        val cls = u32(unitBytes, OFF_CLASS)
        for (i in 0 until 8) {
            if (i == unit.weaponType) continue
            if (rom.u8(cls + 0x2C + i) == WPN_EXP_S) continue
            if (u8(unitBytes, OFF_RANKS + i) < WPN_EXP_S) continue
            if (result >= WPN_EXP_S) result = WPN_EXP_S - 1
            break
        }
        val attrs = unitCAttributes(unitBytes, 0)
        if ((attrs and UnitLoader.CA_PROMOTED) != 0) {
            if (result > WPN_EXP_S) result = WPN_EXP_S
        } else if ((attrs and UnitLoader.CA_MAXLEVEL10) != 0) {
            if (result > WPN_EXP_C) result = WPN_EXP_C
        } else if (result > WPN_EXP_A) {
            result = WPN_EXP_A
        }
        return result
    }

    /**
     * Transcribed from src/bmbattle.c: BattleApplyBallistaUpdates (not verified: only the
     *   no-op branch occurs in the Prologue trace)
     *
     * In a ballista battle stores the remaining uses of the actor's weapon in the ballista trap.
     */
    public fun battleApplyBallistaUpdates() {
        if ((stats.config and BattleStats.CONFIG_BALLISTA) != 0) {
            setBallistaItemUses(u8(actor.bytes, OFF_BALLISTA_INDEX), actor.weapon ushr 8)
        }
    }

    /** Battle slot ids, unit struct offsets, status and item ids, and ROM addresses. */
    public companion object {
        /** `BU_ISLOT_AUTO`. */
        public const val BU_ISLOT_AUTO: Int = -1

        /** `BU_ISLOT_5`. */
        public const val BU_ISLOT_5: Int = 5

        /** `BU_ISLOT_ARENA_PLAYER`. */
        public const val BU_ISLOT_ARENA_PLAYER: Int = 6

        /** `BU_ISLOT_ARENA_OPPONENT`. */
        public const val BU_ISLOT_ARENA_OPPONENT: Int = 7

        /** `BU_ISLOT_BALLISTA`. */
        public const val BU_ISLOT_BALLISTA: Int = 8

        private const val OFF_CHARACTER = 0x00
        private const val OFF_CLASS = 0x04
        private const val OFF_LEVEL = 0x08
        private const val OFF_EXP = 0x09
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_X = 0x10
        private const val OFF_Y = 0x11
        private const val OFF_MAX_HP = 0x12
        private const val OFF_CUR_HP = 0x13
        private const val OFF_POW = 0x14
        private const val OFF_SKL = 0x15
        private const val OFF_SPD = 0x16
        private const val OFF_DEF = 0x17
        private const val OFF_RES = 0x18
        private const val OFF_LCK = 0x19
        private const val OFF_CON_BONUS = 0x1A
        private const val OFF_MOV_BONUS = 0x1D
        private const val OFF_ITEMS = 0x1E
        private const val OFF_RANKS = 0x28
        private const val OFF_STATUS = 0x30
        private const val OFF_TORCH_BARRIER = 0x31
        private const val OFF_SUPPORTS = 0x32

        private const val US_RESCUED = 1 shl 5
        private const val US_IN_BALLISTA = 1 shl 11
        private const val CA_CRITBONUS = 1 shl 6
        private const val CA_BOSS = 1 shl 15
        private const val CA_NEGATE_LETHALITY = 1 shl 24
        private const val CA_ASSASSIN = 1 shl 25
        private const val WPN_EXP_S = 251
        private const val WPN_EXP_C = 71
        private const val WPN_EXP_A = 181
        private const val PLAY_FLAG_EXTRA_MAP = 1 shl 7
        private const val OFF_BALLISTA_INDEX = 0x1C
        private const val WPN_EFFECT_HPHALVE = 3
        private const val STATUS_SLEEP = 2
        private const val STATUS_BERSERK = 4
        private const val STATUS_ATTACK = 5
        private const val STATUS_DEFENSE = 6
        private const val STATUS_CRIT = 7
        private const val STATUS_AVOID = 8
        private const val STATUS_PETRIFY = 11
        private const val STATUS_13 = 13
        private const val ITYPE_ANIMA = 5
        private const val ITYPE_LIGHT = 6
        private const val ITYPE_DARK = 7
        private const val SUPPORT_BONUSES_MAX_DISTANCE = 3

        private const val ITEM_SWORD_LIGHTBRAND = 0x10
        private const val ITEM_SWORD_RUNESWORD = 0x11
        private const val ITEM_ANIMA_EXCALIBUR = 0x3E
        private const val ITEM_SWORD_SIEGLINDE = 0x85
        private const val ITEM_LIGHT_IVALDI = 0x87
        private const val ITEM_LANCE_VIDOFNIR = 0x8E
        private const val ITEM_SWORD_AUDHULMA = 0x91
        private const val ITEM_LANCE_SIEGMUND = 0x92
        private const val ITEM_AXE_GARM = 0x93
        private const val ITEM_BOW_NIDHOGG = 0x94
        private const val ITEM_SWORD_WINDSWORD = 0xA1
        private const val ITEM_MONSTER_STONE = 0xB5

        /** ROM address of `sWeaponTriangleRules`. */
        private const val WEAPON_TRIANGLE_RULES = 0x0859BA90

        /** ROM address of `gAffinityBonuses`. */
        private const val AFFINITY_BONUSES = 0x088B05F8

        /** ROM addresses of `ItemEffectiveness_Flier`, `_FlierAndMonsters`, `_Monsters`. */
        private const val EFFECTIVENESS_FLIER = 0x088ADF2A
        private const val EFFECTIVENESS_FLIER_AND_MONSTERS = 0x088ADEF1
        private const val EFFECTIVENESS_MONSTERS = 0x088ADF39
    }
}
