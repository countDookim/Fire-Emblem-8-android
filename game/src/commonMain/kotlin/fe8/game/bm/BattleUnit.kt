package fe8.game.bm

import fe8.game.bm.StructCodec.put16
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s16
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * `struct BattleUnit` (include/bmbattle.h, [SIZE] bytes): the combatant of one battle, an
 * embedded `struct Unit` copy at 0 (with battle-time stats, see `BmBattle.initBattleUnit`)
 * followed by the battle fields (weapon, terrain, computed rates, results to write back). The
 * bytes are kept in the C layout in [bytes] (the embedded unit can therefore be handed to any
 * helper that takes a unit as byte array plus offset 0), so serialising for a comparison with a
 * RAM dump is a plain copy of [bytes].
 *
 * Instances are allocated once (`gBattleActor`, `gBattleTarget`) and rewritten in place.
 */
public class BattleUnit {
    /** The struct bytes in the C layout. */
    public val bytes: ByteArray = ByteArray(SIZE)

    /**
     * `weapon` (u16, 0x48): the item word (`uses << 8 | item index`) fought with; 0 for none. It
     * loses a use per hit during the battle.
     */
    public var weapon: Int
        get() = u16(bytes, 0x48)
        set(value) = put16(bytes, 0x48, value)

    /** `weaponBefore` (u16, 0x4A): the weapon at battle start; weight and animation use it. */
    public var weaponBefore: Int
        get() = u16(bytes, 0x4A)
        set(value) = put16(bytes, 0x4A, value)

    /** `weaponAttributes` (u32, 0x4C): the `IA_*` flags of [weapon], possibly adjusted. */
    public var weaponAttributes: Int
        get() = u32(bytes, 0x4C)
        set(value) = put32(bytes, 0x4C, value)

    /** `weaponType` (u8, 0x50): `ITYPE_*` of [weapon] (sword, lance, ..., 0xFF for none). */
    public var weaponType: Int
        get() = u8(bytes, 0x50)
        set(value) = put8(bytes, 0x50, value)

    /** `weaponSlotIndex` (u8, 0x51): inventory slot (0..4) of [weapon], 0xFF if not in it. */
    public var weaponSlotIndex: Int
        get() = u8(bytes, 0x51)
        set(value) = put8(bytes, 0x51, value)

    /** `canCounter` (s8, 0x52): non-zero while the unit can strike back with a weapon. */
    public var canCounter: Int
        get() = s8(bytes, 0x52)
        set(value) = put8(bytes, 0x52, value)

    /** `wTriangleHitBonus` (s8, 0x53): weapon triangle bonus (or penalty) to hit. */
    public var wTriangleHitBonus: Int
        get() = s8(bytes, 0x53)
        set(value) = put8(bytes, 0x53, value)

    /** `wTriangleDmgBonus` (s8, 0x54): weapon triangle bonus (or penalty) to damage. */
    public var wTriangleDmgBonus: Int
        get() = s8(bytes, 0x54)
        set(value) = put8(bytes, 0x54, value)

    /** `terrainId` (u8, 0x55): terrain type of the tile the unit stands on. */
    public var terrainId: Int
        get() = u8(bytes, 0x55)
        set(value) = put8(bytes, 0x55, value)

    /** `terrainDefense` (s8, 0x56): the class's defense bonus on that terrain. */
    public var terrainDefense: Int
        get() = s8(bytes, 0x56)
        set(value) = put8(bytes, 0x56, value)

    /** `terrainAvoid` (s8, 0x57): the class's avoid bonus on that terrain. */
    public var terrainAvoid: Int
        get() = s8(bytes, 0x57)
        set(value) = put8(bytes, 0x57, value)

    /** `terrainResistance` (s8, 0x58): the class's resistance bonus on that terrain. */
    public var terrainResistance: Int
        get() = s8(bytes, 0x58)
        set(value) = put8(bytes, 0x58, value)

    /** `battleAttack` (s16, 0x5A): Atk, the damage before the opponent's defense. */
    public var battleAttack: Int
        get() = s16(bytes, 0x5A)
        set(value) = put16(bytes, 0x5A, value)

    /** `battleDefense` (s16, 0x5C): Def (or Res against magic) with terrain, damage reduction. */
    public var battleDefense: Int
        get() = s16(bytes, 0x5C)
        set(value) = put16(bytes, 0x5C, value)

    /** `battleSpeed` (s16, 0x5E): attack speed (Spd less excess weapon weight). */
    public var battleSpeed: Int
        get() = s16(bytes, 0x5E)
        set(value) = put16(bytes, 0x5E, value)

    /** `battleHitRate` (s16, 0x60): Hit, before the opponent's avoid. */
    public var battleHitRate: Int
        get() = s16(bytes, 0x60)
        set(value) = put16(bytes, 0x60, value)

    /** `battleAvoidRate` (s16, 0x62): Avoid, subtracted from the opponent's Hit. */
    public var battleAvoidRate: Int
        get() = s16(bytes, 0x62)
        set(value) = put16(bytes, 0x62, value)

    /** `battleEffectiveHitRate` (s16, 0x64): hit chance in percent against this opponent. */
    public var battleEffectiveHitRate: Int
        get() = s16(bytes, 0x64)
        set(value) = put16(bytes, 0x64, value)

    /** `battleCritRate` (s16, 0x66): Crit, before the opponent's dodge. */
    public var battleCritRate: Int
        get() = s16(bytes, 0x66)
        set(value) = put16(bytes, 0x66, value)

    /** `battleDodgeRate` (s16, 0x68): Dodge, subtracted from the opponent's Crit. */
    public var battleDodgeRate: Int
        get() = s16(bytes, 0x68)
        set(value) = put16(bytes, 0x68, value)

    /** `battleEffectiveCritRate` (s16, 0x6A): crit chance in percent against this opponent. */
    public var battleEffectiveCritRate: Int
        get() = s16(bytes, 0x6A)
        set(value) = put16(bytes, 0x6A, value)

    /** `battleSilencerRate` (s16, 0x6C): chance in percent of the Silencer instant kill. */
    public var battleSilencerRate: Int
        get() = s16(bytes, 0x6C)
        set(value) = put16(bytes, 0x6C, value)

    /** `expGain` (s8, 0x6E): exp earned in this battle. */
    public var expGain: Int
        get() = s8(bytes, 0x6E)
        set(value) = put8(bytes, 0x6E, value)

    /** `statusOut` (s8, 0x6F): `UNIT_STATUS_*` inflicted on this unit by the battle, -1 none. */
    public var statusOut: Int
        get() = s8(bytes, 0x6F)
        set(value) = put8(bytes, 0x6F, value)

    /** `levelPrevious` (s8, 0x70): level before the battle (for the level-up screen). */
    public var levelPrevious: Int
        get() = s8(bytes, 0x70)
        set(value) = put8(bytes, 0x70, value)

    /** `expPrevious` (s8, 0x71): exp before the battle (for the exp bar). */
    public var expPrevious: Int
        get() = s8(bytes, 0x71)
        set(value) = put8(bytes, 0x71, value)

    /** `hpInitial` (s8, 0x72): HP before the battle. */
    public var hpInitial: Int
        get() = s8(bytes, 0x72)
        set(value) = put8(bytes, 0x72, value)

    /** `changeHP` (s8, 0x73): max HP gained by a level-up in this battle. */
    public var changeHP: Int
        get() = s8(bytes, 0x73)
        set(value) = put8(bytes, 0x73, value)

    /** `changePow` (s8, 0x74): Str / Mag gained by a level-up in this battle. */
    public var changePow: Int
        get() = s8(bytes, 0x74)
        set(value) = put8(bytes, 0x74, value)

    /** `changeSkl` (s8, 0x75): Skl gained by a level-up in this battle. */
    public var changeSkl: Int
        get() = s8(bytes, 0x75)
        set(value) = put8(bytes, 0x75, value)

    /** `changeSpd` (s8, 0x76): Spd gained by a level-up in this battle. */
    public var changeSpd: Int
        get() = s8(bytes, 0x76)
        set(value) = put8(bytes, 0x76, value)

    /** `changeDef` (s8, 0x77): Def gained by a level-up in this battle. */
    public var changeDef: Int
        get() = s8(bytes, 0x77)
        set(value) = put8(bytes, 0x77, value)

    /** `changeRes` (s8, 0x78): Res gained by a level-up in this battle. */
    public var changeRes: Int
        get() = s8(bytes, 0x78)
        set(value) = put8(bytes, 0x78, value)

    /** `changeLck` (s8, 0x79): Lck gained by a level-up in this battle. */
    public var changeLck: Int
        get() = s8(bytes, 0x79)
        set(value) = put8(bytes, 0x79, value)

    /** `changeCon` (s8, 0x7A): Con bonus change from a promotion in this battle. */
    public var changeCon: Int
        get() = s8(bytes, 0x7A)
        set(value) = put8(bytes, 0x7A, value)

    /** `wexpMultiplier` (s8, 0x7B): weapon exp units earned: one per attack, one more to finish. */
    public var wexpMultiplier: Int
        get() = s8(bytes, 0x7B)
        set(value) = put8(bytes, 0x7B, value)

    /** `nonZeroDamage` (s8, 0x7C): non-zero once the unit dealt damage (exp is 1 otherwise). */
    public var nonZeroDamage: Int
        get() = s8(bytes, 0x7C)
        set(value) = put8(bytes, 0x7C, value)

    /** `weaponBroke` (s8, 0x7D): non-zero when the weapon ran out of uses in this battle. */
    public var weaponBroke: Int
        get() = s8(bytes, 0x7D)
        set(value) = put8(bytes, 0x7D, value)

    /** `hasItemEffectTarget` (s8, 0x7E): set once an item / staff effect has a target unit. */
    public var hasItemEffectTarget: Int
        get() = s8(bytes, 0x7E)
        set(value) = put8(bytes, 0x7E, value)

    /** The embedded unit's `statusIndex`, a `UNIT_STATUS_*` id (low nibble of byte 0x30). */
    public val unitStatusIndex: Int
        get() = u8(bytes, 0x30) and 0xF

    /** The embedded unit's `xPos` (s8, 0x10). */
    public val unitX: Int
        get() = s8(bytes, 0x10)

    /** The embedded unit's `yPos` (s8, 0x11). */
    public val unitY: Int
        get() = s8(bytes, 0x11)

    /** Sets the embedded unit's `xPos` / `yPos` (the simulation battles override them). */
    public fun setUnitPos(x: Int, y: Int) {
        put8(bytes, 0x10, x)
        put8(bytes, 0x11, y)
    }

    /** Zeroes every byte (the state of a zero-initialised `EWRAM_DATA`). */
    public fun clear() {
        bytes.fill(0)
    }

    /** Layout constants. */
    public companion object {
        /** `sizeof(struct BattleUnit)`. */
        public const val SIZE: Int = 0x80
    }
}

/**
 * `struct BattleStats` (include/bmbattle.h, [SIZE] bytes): the battle configuration and the stats
 * of the hit being generated. The two `struct Unit*` fields are kept as raw 32-bit values.
 */
public class BattleStats {
    /** `config` (u16, 0x00): `BATTLE_CONFIG_*` flags. */
    public var config: Int = 0

    /** `range` (u8, 0x02): distance in tiles between the two units (sum of the x / y gaps). */
    public var range: Int = 0

    /** `damage` (s16, 0x04): damage of the hit being generated. */
    public var damage: Int = 0

    /** `attack` (s16, 0x06): the striker's attack for the hit being generated. */
    public var attack: Int = 0

    /** `defense` (s16, 0x08): the defender's defense for the hit being generated. */
    public var defense: Int = 0

    /** `hitRate` (s16, 0x0A): the striker's effective hit chance for the hit. */
    public var hitRate: Int = 0

    /** `critRate` (s16, 0x0C): the striker's effective crit chance for the hit. */
    public var critRate: Int = 0

    /** `silencerRate` (s16, 0x0E): the striker's Silencer chance for the hit. */
    public var silencerRate: Int = 0

    /** `taUnitA` (0x10), a `struct Unit*` as a raw address: a triangle attack participant. */
    public var taUnitA: Int = 0

    /** `taUnitB` (0x14), a `struct Unit*` as a raw address: a triangle attack participant. */
    public var taUnitB: Int = 0

    /** Writes the struct in the C layout to [buffer] at [offset]. */
    public fun encode(buffer: ByteArray, offset: Int) {
        put16(buffer, offset, config)
        put8(buffer, offset + 2, range)
        put8(buffer, offset + 3, 0)
        put16(buffer, offset + 4, damage)
        put16(buffer, offset + 6, attack)
        put16(buffer, offset + 8, defense)
        put16(buffer, offset + 0xA, hitRate)
        put16(buffer, offset + 0xC, critRate)
        put16(buffer, offset + 0xE, silencerRate)
        put32(buffer, offset + 0x10, taUnitA)
        put32(buffer, offset + 0x14, taUnitB)
    }

    /** Reads the struct in the C layout from [buffer] at [offset]. */
    public fun decode(buffer: ByteArray, offset: Int) {
        config = u16(buffer, offset)
        range = u8(buffer, offset + 2)
        damage = s16(buffer, offset + 4)
        attack = s16(buffer, offset + 6)
        defense = s16(buffer, offset + 8)
        hitRate = s16(buffer, offset + 0xA)
        critRate = s16(buffer, offset + 0xC)
        silencerRate = s16(buffer, offset + 0xE)
        taUnitA = u32(buffer, offset + 0x10)
        taUnitB = u32(buffer, offset + 0x14)
    }

    /** Layout and `BATTLE_CONFIG_*` constants. */
    public companion object {
        /** `sizeof(struct BattleStats)`. */
        public const val SIZE: Int = 0x18

        /** `BATTLE_CONFIG_REAL`: a real battle that will be fought, not a forecast. */
        public const val CONFIG_REAL: Int = 1 shl 0

        /** `BATTLE_CONFIG_SIMULATE`: a forecast; random rolls are skipped. */
        public const val CONFIG_SIMULATE: Int = 1 shl 1

        /**
         * `BATTLE_CONFIG_BIT2`: set by `BattleGenerateUiStats` (the stat screen / item preview);
         * `SetBattleUnitWeapon` then skips its range and status restrictions.
         */
        public const val CONFIG_BIT2: Int = 1 shl 2

        /** `BATTLE_CONFIG_BALLISTA`: the actor fires a ballista. */
        public const val CONFIG_BALLISTA: Int = 1 shl 3

        /** `BATTLE_CONFIG_ARENA`: an arena battle. */
        public const val CONFIG_ARENA: Int = 1 shl 5

        /** `BATTLE_CONFIG_MAPANIMS`: shown with map animations, not battle animations. */
        public const val CONFIG_MAPANIMS: Int = 1 shl 7
    }
}
