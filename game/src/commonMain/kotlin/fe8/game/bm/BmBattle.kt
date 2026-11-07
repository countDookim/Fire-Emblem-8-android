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
     * Copies the unit at [so] of [src] into [bu] and sets its battle bonus stats, previous
     * level / exp, initial HP and clears its change fields; `-1` for [so] is the C `NULL` and does
     * nothing. As in the C, the `wexpMultiplier`, `weaponBroke` and `expGain` resets hit
     * [actor] and [target], not [bu].
     */
    public fun initBattleUnit(bu: BattleUnit, src: ByteArray, so: Int) {
        if (so < 0) return
        val b = bu.bytes
        src.copyInto(b, 0, so, so + UnitTable.UNIT_SIZE)

        put8(b, OFF_MAX_HP, loader.getUnitMaxHp(src, so))
        put8(b, OFF_POW, getUnitPower(src, so))
        put8(b, OFF_SKL, getUnitSkill(src, so))
        put8(b, OFF_SPD, getUnitSpeed(src, so))
        put8(b, OFF_DEF, getUnitDefense(src, so))
        put8(b, OFF_LCK, getUnitLuck(src, so))
        put8(b, OFF_RES, getUnitResistance(src, so))
        put8(b, OFF_CON_BONUS, unitCon(src, so))
        put8(b, OFF_MOV_BONUS, unitMov(src, so))

        bu.levelPrevious = s8(b, OFF_LEVEL)
        bu.expPrevious = u8(b, OFF_EXP)

        bu.hpInitial = s8(b, OFF_CUR_HP)
        bu.statusOut = -1

        bu.changeHP = 0
        bu.changePow = 0
        bu.changeSkl = 0
        bu.changeSpd = 0
        bu.changeDef = 0
        bu.changeRes = 0
        bu.changeLck = 0
        bu.changeCon = 0

        actor.wexpMultiplier = 0
        target.wexpMultiplier = 0

        bu.wTriangleHitBonus = 0
        bu.wTriangleDmgBonus = 0

        bu.nonZeroDamage = 0

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
    public fun initBattleUnitWithoutBonuses(bu: BattleUnit, src: ByteArray, so: Int) {
        initBattleUnit(bu, src, so)
        val b = bu.bytes
        put8(b, OFF_MAX_HP, s8(src, so + OFF_MAX_HP))
        put8(b, OFF_POW, s8(src, so + OFF_POW))
        put8(b, OFF_SKL, s8(src, so + OFF_SKL))
        put8(b, OFF_SPD, s8(src, so + OFF_SPD))
        put8(b, OFF_DEF, s8(src, so + OFF_DEF))
        put8(b, OFF_LCK, s8(src, so + OFF_LCK))
        put8(b, OFF_RES, s8(src, so + OFF_RES))
        put8(b, OFF_CON_BONUS, unitConBase(src, so))
    }

    /**
     * Ported from src/bmbattle.c: SetBattleUnitTerrainBonuses
     *
     * Sets [BattleUnit.terrainId] to [terrain] and the avoid, defense and resistance bonuses from
     * the unit's class lookup tables.
     */
    public fun setBattleUnitTerrainBonuses(bu: BattleUnit, terrain: Int) {
        bu.terrainId = terrain
        val cls = u32(bu.bytes, OFF_CLASS)
        bu.terrainAvoid = rom.u8(rom.u32(cls + 0x44) + bu.terrainId)
        bu.terrainDefense = rom.u8(rom.u32(cls + 0x48) + bu.terrainId)
        bu.terrainResistance = rom.u8(rom.u32(cls + 0x4C) + bu.terrainId)
    }

    /**
     * Ported from src/bmbattle.c: SetBattleUnitTerrainBonusesAuto
     *
     * [setBattleUnitTerrainBonuses] with the terrain under the unit.
     */
    public fun setBattleUnitTerrainBonusesAuto(bu: BattleUnit) {
        setBattleUnitTerrainBonuses(bu, terrainAt(bu.unitY, bu.unitX))
    }

    /**
     * Ported from src/bmbattle.c: SetBattleUnitWeapon
     *
     * Picks the weapon of [bu] from [itemSlot] ([BU_ISLOT_AUTO] for the equipped one), then
     * applies the range, magic-sword and status restrictions unless `BATTLE_CONFIG_BIT2` is set in
     * [stats].
     */
    public fun setBattleUnitWeapon(bu: BattleUnit, itemSlot: Int) {
        val b = bu.bytes
        var slot = itemSlot
        if (slot == BU_ISLOT_AUTO) slot = getUnitEquippedWeaponSlot(b, 0)

        if ((StructCodec.u32(b, OFF_STATE) and US_IN_BALLISTA) != 0) slot = BU_ISLOT_BALLISTA

        bu.canCounter = 1

        when (slot) {
            in 0..4 -> {
                bu.weaponSlotIndex = slot
                bu.weapon = u16(b, OFF_ITEMS + 2 * slot)
            }
            BU_ISLOT_5 -> {
                bu.weaponSlotIndex = 0xFF
                bu.weapon = bmSt.umTmpItem
            }
            BU_ISLOT_ARENA_PLAYER -> {
                bu.weaponSlotIndex = 0
                bu.weapon = arenaPlayerWeapon
                bu.canCounter = 0
            }
            BU_ISLOT_ARENA_OPPONENT -> {
                bu.weaponSlotIndex = 0
                bu.weapon = arenaOpponentWeapon
                bu.canCounter = 0
            }
            BU_ISLOT_BALLISTA -> {
                bu.weaponSlotIndex = 0xFF
                bu.weapon = ballistaItemAt(bu.unitX, bu.unitY)
                bu.canCounter = 0
            }
            else -> {
                bu.weaponSlotIndex = 0xFF
                bu.weapon = 0
                bu.canCounter = 0
            }
        }

        bu.weaponBefore = bu.weapon
        bu.weaponAttributes = items.getItemAttributes(bu.weapon)
        bu.weaponType = items.getItemType(bu.weapon)

        if ((stats.config and BattleStats.CONFIG_BIT2) == 0) {
            if ((bu.weaponAttributes and ItemInfo.IA_MAGICDAMAGE) != 0) {
                when (items.getItemIndex(bu.weapon)) {
                    ITEM_SWORD_WINDSWORD ->
                        if (stats.range == 2) {
                            bu.weaponType = ITYPE_ANIMA
                        } else {
                            bu.weaponAttributes = bu.weaponAttributes and
                                ItemInfo.IA_MAGICDAMAGE.inv()
                        }
                    ITEM_SWORD_LIGHTBRAND ->
                        if (stats.range == 2) {
                            bu.weaponType = ITYPE_LIGHT
                        } else {
                            bu.weaponAttributes = bu.weaponAttributes and
                                ItemInfo.IA_MAGICDAMAGE.inv()
                        }
                    ITEM_SWORD_RUNESWORD -> bu.weaponType = ITYPE_DARK
                }
            }

            if (!isItemCoveringRange(bu.weapon, stats.range) || bu.weaponSlotIndex == 0xFF) {
                bu.weapon = 0
                bu.canCounter = 0
            }

            when (bu.unitStatusIndex) {
                STATUS_SLEEP, STATUS_PETRIFY, STATUS_13 -> {
                    bu.weapon = 0
                    bu.canCounter = 0
                }
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: SetBattleUnitWeaponBallista
     *   (not verified: not called by the traced battle)
     *
     * Gives [bu] the ballista item of its tile and no counter.
     */
    public fun setBattleUnitWeaponBallista(bu: BattleUnit) {
        bu.weapon = ballistaItemAt(bu.unitX, bu.unitY)
        bu.weaponBefore = bu.weapon
        bu.weaponAttributes = items.getItemAttributes(bu.weapon)
        bu.weaponType = items.getItemType(bu.weapon)
        bu.canCounter = 0
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
            val t = supportBonuses
            getUnitSupportBonuses(attacker.bytes, 0, t)
            attacker.battleAttack += t[0]
            attacker.battleDefense += t[1]
            attacker.battleHitRate += t[2]
            attacker.battleAvoidRate += t[3]
            attacker.battleCritRate += t[4]
            attacker.battleDodgeRate += t[5]
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
    public fun computeBattleUnitBaseDefense(bu: BattleUnit) {
        bu.battleDefense = bu.terrainDefense + s8(bu.bytes, OFF_DEF)
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
    public fun computeBattleUnitSpeed(bu: BattleUnit) {
        var effWt = items.getItemWeight(bu.weaponBefore)
        effWt -= s8(bu.bytes, OFF_CON_BONUS)
        if (effWt < 0) effWt = 0
        bu.battleSpeed = s8(bu.bytes, OFF_SPD) - effWt
        if (bu.battleSpeed < 0) bu.battleSpeed = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitHitRate
     *
     * `skill * 2 + weapon hit + luck / 2 + triangle bonus`.
     */
    public fun computeBattleUnitHitRate(bu: BattleUnit) {
        bu.battleHitRate = s8(bu.bytes, OFF_SKL) * 2 + items.getItemHit(bu.weapon) +
            s8(bu.bytes, OFF_LCK) / 2 + bu.wTriangleHitBonus
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitAvoidRate
     *
     * `speed * 2 + terrain avoid + luck`, at least 0.
     */
    public fun computeBattleUnitAvoidRate(bu: BattleUnit) {
        bu.battleAvoidRate = bu.battleSpeed * 2 + bu.terrainAvoid + s8(bu.bytes, OFF_LCK)
        if (bu.battleAvoidRate < 0) bu.battleAvoidRate = 0
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitCritRate
     *
     * Weapon crit plus skill / 2, plus 15 for crit-bonus classes.
     */
    public fun computeBattleUnitCritRate(bu: BattleUnit) {
        bu.battleCritRate = items.getItemCrit(bu.weapon) + s8(bu.bytes, OFF_SKL) / 2
        if ((unitCAttributes(bu.bytes, 0) and CA_CRITBONUS) != 0) bu.battleCritRate += 15
    }

    /**
     * Ported from src/bmbattle.c: ComputeBattleUnitDodgeRate
     *
     * The unit's luck.
     */
    public fun computeBattleUnitDodgeRate(bu: BattleUnit) {
        bu.battleDodgeRate = s8(bu.bytes, OFF_LCK)
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
     * Crit rate minus the defender's dodge rate, 0 against crit-negating items.
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
            val d = unitCAttributes(defender.bytes, 0)
            if ((d and CA_BOSS) != 0) attacker.battleSilencerRate = 25
            if ((d and CA_NEGATE_LETHALITY) != 0) attacker.battleSilencerRate = 0
        }
    }

    /**
     * Transcribed from src/bmbattle.c: ComputeBattleUnitWeaponRankBonuses
     *   (not verified: no traced unit has an S rank weapon)
     *
     * +5 hit and crit for an S rank in the weapon's type.
     */
    public fun computeBattleUnitWeaponRankBonuses(bu: BattleUnit) {
        if (bu.weapon != 0) {
            val wType = items.getItemType(bu.weapon)
            if (wType < 8 && u8(bu.bytes, OFF_RANKS + wType) >= WPN_EXP_S) {
                bu.battleHitRate += 5
                bu.battleCritRate += 5
            }
        }
    }

    /**
     * Transcribed from src/bmbattle.c: ComputeBattleUnitStatusBonuses
     *   (not verified: no traced unit has a status effect)
     *
     * The +10 of the attack, defense, crit and avoid status effects.
     */
    public fun computeBattleUnitStatusBonuses(bu: BattleUnit) {
        when (bu.unitStatusIndex) {
            STATUS_ATTACK -> bu.battleAttack += 10
            STATUS_DEFENSE -> bu.battleDefense += 10
            STATUS_CRIT -> bu.battleCritRate += 10
            STATUS_AVOID -> bu.battleAvoidRate += 10
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
        var it = WEAPON_TRIANGLE_RULES
        while (rom.u8(it).toByte() >= 0) {
            if (attacker.weaponType == rom.u8(it) && defender.weaponType == rom.u8(it + 1)) {
                val hit = rom.u8(it + 2).toByte().toInt()
                val atk = rom.u8(it + 3).toByte().toInt()
                attacker.wTriangleHitBonus = hit
                attacker.wTriangleDmgBonus = atk
                defender.wTriangleHitBonus = -hit
                defender.wTriangleDmgBonus = -atk
                break
            }
            it += 4
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
    public fun battleUnitTargetCheckCanCounter(bu: BattleUnit) {
        if (bu.canCounter == 0) {
            bu.battleAttack = 0xFF
            bu.battleHitRate = 0xFF
            bu.battleEffectiveHitRate = 0xFF
            bu.battleCritRate = 0xFF
            bu.battleEffectiveCritRate = 0xFF
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitSupportBonuses via src/bmreliance.c (not verified:
     *   the traced units have no supporter on the map, so only the empty sum is compared)
     *
     * Writes the halved support bonuses (attack, defense, hit, avoid, crit, dodge) of the unit at
     * [o] of [b] into [out] (6 ints); returns the support bit mask of the C. Unlike the C, an
     * affinity missing from `gAffinityBonuses` adds nothing (the C reads past the table).
     */
    internal fun getUnitSupportBonuses(b: ByteArray, o: Int, out: IntArray): Int {
        out.fill(0)
        var result = 0
        val count = supporterCount(b, o)
        val faction = s8(b, o + OFF_INDEX) and 0xC0
        val myAffinity = rom.u8(u32(b, o + OFF_CHARACTER) + 9)
        val myChar = rom.u8(u32(b, o + OFF_CHARACTER) + 4)

        for (i in 0 until count) {
            result = result shr 1
            val other = supporterUnit(b, o, i, faction)
            if (other < 0) continue
            val tb = table.bytes

            if ((bmSt.gameStateBits and 0x40) == 0) {
                val d = abs(s8(b, o + OFF_X) - s8(tb, other + OFF_X)) +
                    abs(s8(b, o + OFF_Y) - s8(tb, other + OFF_Y))
                if (d > SUPPORT_BONUSES_MAX_DISTANCE) continue
            }

            val unavailable = UnitTable.US_UNAVAILABLE or US_RESCUED
            if ((StructCodec.u32(tb, other + OFF_STATE) and unavailable) != 0) continue

            val level1 = supportLevel(tb, other, supporterNum(tb, other, myChar))
            applyAffinitySupportBonuses(
                out, rom.u8(u32(tb, other + OFF_CHARACTER) + 9), level1,
            )

            val level2 = supportLevel(b, o, i)
            applyAffinitySupportBonuses(out, myAffinity, level2)

            if (level1 != 0 && level2 != 0) result += 1 shl (count - 1)
        }

        for (i in 0 until 6) out[i] = (out[i] and 0xFF) / 2
        return result
    }

    private fun supportData(b: ByteArray, o: Int): Int = rom.u32(u32(b, o + OFF_CHARACTER) + 0x2C)

    private fun supporterCount(b: ByteArray, o: Int): Int {
        val d = supportData(b, o)
        return if (d == 0) 0 else rom.u8(d + 0x15)
    }

    private fun supporterCharacter(b: ByteArray, o: Int, num: Int): Int {
        val d = supportData(b, o)
        return if (d == 0) 0 else rom.u8(d + num)
    }

    /** `GetUnitSupporterUnit`: the table offset of the supporter, or -1. */
    private fun supporterUnit(b: ByteArray, o: Int, num: Int, faction: Int): Int {
        val charId = supporterCharacter(b, o, num)
        for (id in faction + 1 until faction + 0x40) {
            if (!table.isValid(id)) continue
            if (table.charId(id) == charId) return table.unitOffset(id)
        }
        return -1
    }

    private fun supporterNum(b: ByteArray, o: Int, charId: Int): Int {
        val count = supporterCount(b, o)
        for (i in 0 until count) {
            if (supporterCharacter(b, o, i) == charId) return i
        }
        return -1
    }

    private fun supportLevel(b: ByteArray, o: Int, num: Int): Int {
        val exp = u8(b, o + OFF_SUPPORTS + num)
        return when {
            exp > 240 -> 3
            exp > 160 -> 2
            exp > 80 -> 1
            else -> 0
        }
    }

    private fun applyAffinitySupportBonuses(out: IntArray, affinity: Int, level: Int) {
        var it = AFFINITY_BONUSES
        while (rom.u8(it) != 0) {
            if (rom.u8(it) == affinity) {
                for (i in 0 until 6) out[i] = (out[i] + level * rom.u8(it + 1 + i)) and 0xFF
                return
            }
            it += 7
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitPower (not verified: no traced unit has a power
     *   bonus item, so the bonus term is not exercised)
     *
     * `pow` plus the power bonus of the equipped weapon of the unit at [o] of [b].
     */
    internal fun getUnitPower(b: ByteArray, o: Int): Int =
        s8(b, o + OFF_POW) + items.getItemPowBonus(loader.getUnitEquippedWeapon(b, o))

    /**
     * Transcribed from src/bmunit.c: GetUnitSkill (not verified: no bonus item and no rescuing
     *   unit in the trace)
     */
    internal fun getUnitSkill(b: ByteArray, o: Int): Int {
        val item = loader.getUnitEquippedWeapon(b, o)
        val skl = s8(b, o + OFF_SKL)
        return if ((StructCodec.u32(b, o + OFF_STATE) and UnitLoader.US_RESCUING) != 0) {
            skl / 2 + items.getItemSklBonus(item)
        } else {
            skl + items.getItemSklBonus(item)
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitSpeed (not verified: no bonus item and no rescuing
     *   unit in the trace)
     */
    internal fun getUnitSpeed(b: ByteArray, o: Int): Int {
        val item = loader.getUnitEquippedWeapon(b, o)
        val spd = s8(b, o + OFF_SPD)
        return if ((StructCodec.u32(b, o + OFF_STATE) and UnitLoader.US_RESCUING) != 0) {
            spd / 2 + items.getItemSpdBonus(item)
        } else {
            spd + items.getItemSpdBonus(item)
        }
    }

    /**
     * Transcribed from src/bmunit.c: GetUnitDefense (not verified: no bonus item in the trace)
     */
    internal fun getUnitDefense(b: ByteArray, o: Int): Int =
        s8(b, o + OFF_DEF) + items.getItemDefBonus(loader.getUnitEquippedWeapon(b, o))

    /**
     * Transcribed from src/bmunit.c: GetUnitResistance (not verified: no bonus item and no
     *   barrier in the trace)
     */
    internal fun getUnitResistance(b: ByteArray, o: Int): Int =
        s8(b, o + OFF_RES) + items.getItemResBonus(loader.getUnitEquippedWeapon(b, o)) +
            (u8(b, o + OFF_TORCH_BARRIER) shr 4)

    /**
     * Transcribed from src/bmunit.c: GetUnitLuck (not verified: no bonus item in the trace)
     */
    internal fun getUnitLuck(b: ByteArray, o: Int): Int =
        s8(b, o + OFF_LCK) + items.getItemLckBonus(loader.getUnitEquippedWeapon(b, o))

    /** `UNIT_CON_BASE`: class base con plus character base con. */
    private fun unitConBase(b: ByteArray, o: Int): Int =
        rom.u8(u32(b, o + OFF_CLASS) + 0x11).toByte() +
            rom.u8(u32(b, o + OFF_CHARACTER) + 0x13).toByte()

    /** `UNIT_CON`. */
    private fun unitCon(b: ByteArray, o: Int): Int = unitConBase(b, o) + s8(b, o + OFF_CON_BONUS)

    /** `UNIT_MOV`. */
    private fun unitMov(b: ByteArray, o: Int): Int =
        s8(b, o + OFF_MOV_BONUS) + rom.u8(u32(b, o + OFF_CLASS) + 0x12).toByte()

    /** `UNIT_CATTRIBUTES`. */
    private fun unitCAttributes(b: ByteArray, o: Int): Int =
        rom.u32(u32(b, o + OFF_CHARACTER) + 0x28) or rom.u32(u32(b, o + OFF_CLASS) + 0x28)

    /**
     * Transcribed from src/bmitem.c: GetUnitEquippedWeaponSlot (not verified: see
     *   [setBattleUnitWeapon])
     *
     * The first item slot of the unit at [o] of [b] that passes `CanUnitUseWeaponNow`, or -1.
     */
    internal fun getUnitEquippedWeaponSlot(b: ByteArray, o: Int): Int {
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            if (canUnitUseWeaponNow(b, o, u16(b, o + OFF_ITEMS + 2 * i))) return i
        }
        return -1
    }

    private fun canUnitUseWeaponNow(b: ByteArray, o: Int, item: Int): Boolean {
        if (item == 0) return false
        val ia = items.getItemAttributes(item)
        if ((ia and ItemInfo.IA_WEAPON) == 0) return false
        if ((ia and ItemInfo.IA_MAGIC) != 0 && isUnitMagicSealed(b, o)) return false
        return loader.canUnitUseWeapon(b, o, item)
    }

    private fun isUnitMagicSealed(b: ByteArray, o: Int): Boolean =
        (u8(b, o + OFF_STATUS) and 0xF) == UnitLoader.UNIT_STATUS_SILENCED ||
            isPositionMagicSealed(s8(b, o + OFF_X), s8(b, o + OFF_Y))

    private fun isItemCoveringRange(item: Int, range: Int): Boolean =
        items.getItemMinRange(item) <= range && range <= items.getItemMaxRange(item)

    private fun isItemEffectiveAgainst(item: Int, b: ByteArray, o: Int): Boolean {
        val cls = u32(b, o + OFF_CLASS)
        if (cls == 0) return false
        val classId = rom.u8(cls + 4)
        val eff = items.getItemEffectiveness(item)
        if (eff == 0) return false
        var p = eff
        while (rom.u8(p) != 0) {
            if (rom.u8(p) == classId) {
                if (eff != EFFECTIVENESS_FLIER && eff != EFFECTIVENESS_FLIER_AND_MONSTERS) {
                    return true
                }
                var attributes = 0
                for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
                    attributes = attributes or
                        items.getItemAttributes(u16(b, o + OFF_ITEMS + 2 * i))
                }
                return (attributes and ItemInfo.IA_NEGATE_FLYING) == 0
            }
            p++
        }
        return false
    }

    private fun isUnitEffectiveAgainst(a: ByteArray, ao: Int, t: ByteArray, to: Int): Boolean {
        val actorClass = rom.u8(u32(a, ao + OFF_CLASS) + 4)
        val targetClass = rom.u8(u32(t, to + OFF_CLASS) + 4)
        if (actorClass != 0x2B && actorClass != 0x2C) return false
        var p = EFFECTIVENESS_MONSTERS
        while (rom.u8(p) != 0) {
            if (rom.u8(p) == targetClass) return true
            p++
        }
        return false
    }

    /** `gUnknown_03003060`: the arena level (`UNIT_ARENA_LEVEL`) of the last unit updated. */
    public var arenaLevelLatch: Int = 0

    /**
     * `PidStatsAddExpGained(pid, exp)` (bmsave-bwl.c, not ported to this class): called by
     * [updateUnitFromBattle] for a non-zero `expGain`.
     */
    public var pidStatsAddExpGained: (pid: Int, exp: Int) -> Unit =
        { _, _ -> error("PidStatsAddExpGained is not wired (M6-40)") }

    /**
     * `GetTrap(ballistaIndex)->data[TRAP_EXTDATA_BLST_ITEMUSES] = uses` (bmtrick.c, not ported):
     * called by [battleApplyBallistaUpdates] in a ballista battle.
     */
    public var setBallistaItemUses: (ballistaIndex: Int, uses: Int) -> Unit =
        { _, _ -> error("traps are not ported (M6-40)") }

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
     * Copies level, exp, HP, state, stat changes, weapon exp and items of [bu] into the unit at
     * [o] of the unit table, sets [arenaLevelLatch], the status, caps the stats and reports the
     * exp gain through [pidStatsAddExpGained].
     */
    public fun updateUnitFromBattle(o: Int, bu: BattleUnit) {
        val b = table.bytes
        val bb = bu.bytes
        put8(b, o + OFF_LEVEL, u8(bb, OFF_LEVEL))
        put8(b, o + OFF_EXP, u8(bb, OFF_EXP))
        put8(b, o + OFF_CUR_HP, u8(bb, OFF_CUR_HP))
        StructCodec.put32(b, o + OFF_STATE, u32(bb, OFF_STATE))
        arenaLevelLatch = (u32(b, o + OFF_STATE) ushr 17) and 0x7
        if (bu.statusOut >= 0) {
            // SetUnitStatus
            val index = bu.statusOut
            put8(b, o + OFF_STATUS, if (index == 0) 0 else (index and 0xF) or (5 shl 4))
        }
        put8(b, o + OFF_MAX_HP, s8(b, o + OFF_MAX_HP) + bu.changeHP)
        put8(b, o + OFF_POW, s8(b, o + OFF_POW) + bu.changePow)
        put8(b, o + OFF_SKL, s8(b, o + OFF_SKL) + bu.changeSkl)
        put8(b, o + OFF_SPD, s8(b, o + OFF_SPD) + bu.changeSpd)
        put8(b, o + OFF_DEF, s8(b, o + OFF_DEF) + bu.changeDef)
        put8(b, o + OFF_RES, s8(b, o + OFF_RES) + bu.changeRes)
        put8(b, o + OFF_LCK, s8(b, o + OFF_LCK) + bu.changeLck)
        loader.unitCheckStatCaps(o)
        val wexp = getBattleUnitUpdatedWeaponExp(bu)
        if (wexp > 0) put8(b, o + OFF_RANKS + bu.weaponType, wexp)
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            put16(b, o + OFF_ITEMS + 2 * i, u16(bb, OFF_ITEMS + 2 * i))
        }
        unitRemoveInvalidItems(o)
        if (bu.expGain != 0) {
            pidStatsAddExpGained(rom.u8(u32(b, o + OFF_CHARACTER) + 4), bu.expGain)
        }
    }

    /**
     * Transcribed from src/bmunit.c: UnitRemoveInvalidItems (not verified: no trace runs it yet)
     *
     * Moves the items of the unit at [o] up so that no empty slot precedes an item.
     */
    internal fun unitRemoveInvalidItems(o: Int) {
        val b = table.bytes
        val kept = IntArray(UnitLoader.UNIT_ITEM_COUNT)
        var n = 0
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            val item = u16(b, o + OFF_ITEMS + 2 * i)
            if (item != 0) kept[n++] = item
            put16(b, o + OFF_ITEMS + 2 * i, 0)
        }
        for (i in 0 until n) put16(b, o + OFF_ITEMS + 2 * i, kept[i])
    }

    /**
     * Transcribed from src/bmbattle.c: GetBattleUnitUpdatedWeaponExp (not verified: the Prologue
     *   never runs it; the f15075 update is EventBattleReloadBmStatus)
     *
     * The weapon exp [bu] has after the battle, or -1 where it gains none.
     */
    public fun getBattleUnitUpdatedWeaponExp(bu: BattleUnit): Int {
        val bb = bu.bytes
        if ((s8(bb, OFF_INDEX) and 0xC0) != UnitLoader.FACTION_BLUE) return -1
        if (u8(bb, OFF_CUR_HP) == 0) return -1
        if ((playSt.chapterStateBits and PLAY_FLAG_EXTRA_MAP) != 0) return -1
        if ((bmSt.gameStateBits and 0x40) != 0) return -1
        if ((stats.config and BattleStats.CONFIG_ARENA) == 0) {
            if (bu.canCounter == 0) return -1
            val requiresWexp = ItemInfo.IA_WEAPON or ItemInfo.IA_STAFF
            if ((bu.weaponAttributes and requiresWexp) == 0) return -1
            if ((bu.weaponAttributes and (ItemInfo.IA_MAGICDAMAGE or ItemInfo.IA_LOCK_3)) != 0) {
                return -1
            }
        }
        var result = u8(bb, OFF_RANKS + bu.weaponType)
        result += rom.u8(items.getItemData(items.getItemIndex(bu.weapon)) + 0x20) *
            bu.wexpMultiplier
        val cls = u32(bb, OFF_CLASS)
        for (i in 0 until 8) {
            if (i == bu.weaponType) continue
            if (rom.u8(cls + 0x2C + i) == WPN_EXP_S) continue
            if (u8(bb, OFF_RANKS + i) < WPN_EXP_S) continue
            if (result >= WPN_EXP_S) result = WPN_EXP_S - 1
            break
        }
        val attrs = unitCAttributes(bb, 0)
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

    /** Constants. */
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
