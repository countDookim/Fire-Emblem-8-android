package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.put16
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * The unit loader of `src/bmunit.c` on the raw unit arrays of a [UnitTable]: [loadUnit] fills the
 * first free unit slot from a ROM `struct UnitDefinition`.
 *
 * Only the plain case is ported: no `genMonster`, no `autolevel`, no gorgon egg and no summon
 * (`sumFlag`) position; those throw [UnsupportedOperationException]. Event-layer side effects
 * (`LoadUnit_800F704`) are not part of this class.
 *
 * @param table the unit arrays that are filled.
 * @param rom the ROM holding `gCharacterData`, `gClassData`, `gItemData`, the support data and
 *     the definitions.
 * @param unsealedCharacter `gPlaySt.unk1C[weaponType]`, the character the weapon type is unsealed
 *     for (only consulted for `IA_UNUSABLE` weapons).
 */
public class UnitLoader(
    private val table: UnitTable,
    private val rom: RomView,
    private val unsealedCharacter: (weaponType: Int) -> Int = { 0 },
) {
    private val items = ItemInfo(rom)

    /**
     * Ported from src/bmunit.c: LoadUnit
     *
     * partial: `genMonster`, `autolevel`, gorgon eggs and `sumFlag` positions throw
     * [UnsupportedOperationException].
     *
     * Loads the `struct UnitDefinition` at ROM address [defAddress] into a free slot of its
     * allegiance. [terrainAt] is `gBmMapTerrain[y][x]`. Returns the new unit's id, or 0 where the
     * C returns NULL (no free slot, or an allegiance other than 0 blue, 1 green, 2 red).
     */
    public fun loadUnit(defAddress: Int, terrainAt: (x: Int, y: Int) -> Int): Int {
        val def = UnitDef(rom, defAddress)
        if (def.genMonster) {
            throw UnsupportedOperationException("LoadUnit: genMonster is not ported")
        }
        if (def.autolevel) {
            throw UnsupportedOperationException("LoadUnit: autolevel is not ported")
        }
        val id = when (def.allegiance) {
            0 -> getFreeBlueUnit()
            2 -> getFreeUnit(FACTION_RED)
            1 -> getFreeUnit(FACTION_GREEN)
            else -> 0
        }
        if (id == 0) return 0
        val o = table.unitOffset(id)
        clearUnit(o)
        unitInitFromDefinition(o, def)
        unitLoadStatsFromChracter(o)
        unitHideIfUnderRoof(o, terrainAt)
        if (isGorgonEgg(o)) {
            throw UnsupportedOperationException("LoadUnit: gorgon eggs are not ported")
        }
        fixROMUnitStructPtr(o)
        unitLoadSupports(o)
        if (def.itemDrop) {
            put32(table.bytes, o + OFF_STATE, u32(table.bytes, o + OFF_STATE) or US_DROP_ITEM)
        }
        unitCheckStatCaps(o)
        put8(table.bytes, o + OFF_CUR_HP, getUnitMaxHp(o))
        return id
    }

    /**
     * Ported from src/bmunit.c: GetUnitMaxHp
     *
     * `maxHP` of the unit at [o] plus the HP bonus of its equipped weapon.
     */
    internal fun getUnitMaxHp(o: Int): Int =
        s8(table.bytes, o + OFF_MAX_HP) + items.getItemHpBonus(getUnitEquippedWeapon(o))

    /**
     * Ported from src/bmitem.c: GetUnitEquippedWeapon
     *
     * The first item of the unit at [o] it can use as a weapon, or 0.
     */
    internal fun getUnitEquippedWeapon(o: Int): Int {
        for (i in 0 until UNIT_ITEM_COUNT) {
            val item = unitItem(o, i)
            if (canUnitUseWeapon(o, item)) return item
        }
        return 0
    }

    /**
     * Ported from src/bmitem.c: CanUnitUseWeapon
     * Ported from src/bmitem.c: IsItemUnsealedForUnit
     *
     * Whether the unit at [o] can wield [item]: a weapon, with the unit meeting the item's lock
     * attributes, not silenced for magic, and with enough weapon rank.
     */
    internal fun canUnitUseWeapon(o: Int, item: Int): Boolean {
        if (item == 0) return false
        val ia = items.getItemAttributes(item)
        if ((ia and ItemInfo.IA_WEAPON) == 0) return false
        val b = table.bytes
        val ch = u32(b, o + OFF_CHARACTER)
        val ca = rom.u32(ch + 0x28) or rom.u32(u32(b, o + OFF_CLASS) + 0x28)
        if ((ia and ItemInfo.IA_LOCK_ANY) != 0) {
            if ((ia and ItemInfo.IA_LOCK_1) != 0 && (ca and CA_LOCK_1) == 0) return false
            if ((ia and ItemInfo.IA_LOCK_4) != 0 && (ca and CA_LOCK_4) == 0) return false
            if ((ia and ItemInfo.IA_LOCK_5) != 0 && (ca and CA_LOCK_5) == 0) return false
            if ((ia and ItemInfo.IA_LOCK_6) != 0 && (ca and CA_LOCK_6) == 0) return false
            if ((ia and ItemInfo.IA_LOCK_7) != 0 && (ca and CA_LOCK_7) == 0) return false
            if ((ia and ItemInfo.IA_LOCK_2) != 0 && (ca and CA_LOCK_2) == 0) return false
            if ((ia and ItemInfo.IA_LOCK_3) != 0) return (ca and CA_LOCK_3) != 0
            if ((ia and ItemInfo.IA_UNUSABLE) != 0 &&
                unsealedCharacter(items.getItemType(item)) != rom.u8(ch + 4)
            ) {
                return false
            }
        }
        if ((u8(b, o + OFF_STATUS) and 0xF) == UNIT_STATUS_SILENCED &&
            (ia and ItemInfo.IA_MAGIC) != 0
        ) {
            return false
        }
        val rank = u8(b, o + OFF_RANKS + items.getItemType(item))
        return rank >= items.getItemRequiredExp(item)
    }

    /**
     * Ported from src/bmunit.c: UnitAddItem
     *
     * Puts [item] into the first empty slot of the unit at [o]; false if the inventory is full.
     */
    internal fun unitAddItem(o: Int, item: Int): Boolean {
        for (i in 0 until UNIT_ITEM_COUNT) {
            if (unitItem(o, i) == 0) {
                put16(table.bytes, o + OFF_ITEMS + 2 * i, item)
                return true
            }
        }
        return false
    }

    private fun unitItem(o: Int, slot: Int): Int =
        StructCodec.u16(table.bytes, o + OFF_ITEMS + 2 * slot)

    /**
     * Ported from src/bmunit.c: ClearUnit
     *
     * Zeroes the unit at byte offset [o] of the unit arrays but keeps its `index`.
     */
    internal fun clearUnit(o: Int) {
        val index = table.bytes[o + OFF_INDEX]
        table.bytes.fill(0, o, o + UnitTable.UNIT_SIZE)
        table.bytes[o + OFF_INDEX] = index
    }

    /**
     * Ported from src/bmunit.c: GetFreeUnit
     *
     * partial: ids without a unit slot (the C dereferences NULL there) end the search.
     *
     * The id of the first unit of [faction] (0x00, 0x40 or 0x80) without a character, or 0.
     */
    internal fun getFreeUnit(faction: Int): Int {
        for (i in faction + 1 until faction + 0x40) {
            val o = table.unitOffset(i)
            if (o < 0) return 0
            if (u32(table.bytes, o + OFF_CHARACTER) == 0) return i
        }
        return 0
    }

    /**
     * Ported from src/bmunit.c: GetFreeBlueUnit
     *
     * The id of the first free blue unit, or 0. The C also calls `GetPlayerLeaderPid` and
     * discards the result ("useless but required to produce matching asm"); that is omitted.
     */
    internal fun getFreeBlueUnit(): Int = getFreeUnit(0)

    /**
     * Ported from src/bmunit.c: UnitInitFromDefinition
     *
     * partial: gorgon eggs throw [UnsupportedOperationException].
     */
    internal fun unitInitFromDefinition(o: Int, def: UnitDef) {
        val b = table.bytes
        val character = characterData(def.charIndex)
        put32(b, o + OFF_CHARACTER, character)
        val classData = if (def.classIndex != 0) {
            classData(def.classIndex)
        } else {
            classData(rom.u8(character + 5))
        }
        put32(b, o + OFF_CLASS, classData)
        put8(b, o + OFF_LEVEL, def.level)
        genUnitDefinitionFinalPosition(def, o)
        if (isGorgonEgg(o)) {
            throw UnsupportedOperationException("UnitInitFromDefinition: gorgon eggs not ported")
        }
        var i = 0
        while (i < 4 && def.items[i] != 0) {
            unitAddItem(o, items.makeNewItem(def.items[i]))
            i++
        }
        charStoreAI(o, def)
    }

    /**
     * Ported from src/muctrl.c: GenUnitDefinitionFinalPosition
     *
     * partial: called with `findNearest == FALSE`. With `redaCount != 0` the position is the last
     * REDA's x/y (`AdjustNewUnitPosition` with flags 0 does nothing); a `sumFlag` definition
     * (flags & 2, a random free neighbouring tile) throws [UnsupportedOperationException].
     * Otherwise the definition's own x/y; both are written to the unit at [o].
     */
    internal fun genUnitDefinitionFinalPosition(def: UnitDef, o: Int) {
        var x = def.x
        var y = def.y
        if (def.redaCount != 0) {
            if (def.sumFlag) {
                throw UnsupportedOperationException(
                    "GenUnitDefinitionFinalPosition: sumFlag (AdjustNewUnitPosition) not ported",
                )
            }
            val reda = rom.u32(def.redas + 8 * (def.redaCount - 1))
            x = reda and 0x3F
            y = (reda shr 6) and 0x3F
        }
        put8(table.bytes, o + OFF_X, x)
        put8(table.bytes, o + OFF_Y, y)
    }

    /**
     * Ported from src/cp_utility.c: CharStoreAI
     *
     * Copies the definition's AI bytes into `ai1`, `ai2` and `ai_config` of the unit at [o].
     */
    internal fun charStoreAI(o: Int, def: UnitDef) {
        val b = table.bytes
        put8(b, o + OFF_AI1, def.ai[0])
        put8(b, o + OFF_AI2, def.ai[1])
        var config = u8(b, o + OFF_AI_CONFIG) or (u8(b, o + OFF_AI_CONFIG + 1) shl 8)
        config = config and AI_HEAL_THRESHOLD_MASK.inv()
        config = config or def.ai[2]
        config = config or (def.ai[3] shl 8)
        put16(b, o + OFF_AI_CONFIG, config)
    }

    /**
     * Ported from src/bmunit.c: UnitLoadStatsFromChracter
     *
     * Sets the stats, con bonus, weapon ranks and exp of the unit at [o] from its character and
     * class (already stored in the unit).
     */
    internal fun unitLoadStatsFromChracter(o: Int) {
        val b = table.bytes
        val ch = u32(b, o + OFF_CHARACTER)
        val cl = u32(b, o + OFF_CLASS)
        put8(b, o + OFF_MAX_HP, rom.s8(ch + 0x0C) + rom.s8(cl + 0x0B))
        put8(b, o + 0x14, rom.s8(ch + 0x0D) + rom.s8(cl + 0x0C))
        put8(b, o + 0x15, rom.s8(ch + 0x0E) + rom.s8(cl + 0x0D))
        put8(b, o + 0x16, rom.s8(ch + 0x0F) + rom.s8(cl + 0x0E))
        put8(b, o + 0x17, rom.s8(ch + 0x10) + rom.s8(cl + 0x0F))
        put8(b, o + 0x18, rom.s8(ch + 0x11) + rom.s8(cl + 0x10))
        put8(b, o + 0x19, rom.s8(ch + 0x12))
        put8(b, o + OFF_CON_BONUS, 0)
        for (i in 0 until 8) {
            put8(b, o + OFF_RANKS + i, rom.u8(cl + 0x2C + i))
            val charRank = rom.u8(ch + 0x14 + i)
            if (charRank != 0) put8(b, o + OFF_RANKS + i, charRank)
        }
        val blue = (s8(b, o + OFF_INDEX) and 0xC0) == FACTION_BLUE
        put8(b, o + OFF_EXP, if (blue && s8(b, o + OFF_LEVEL) != UNIT_LEVEL_MAX) 0 else 0xFF)
    }

    /**
     * Ported from src/bmtrick.c: UnitHideIfUnderRoof
     *
     * Marks the unit at [o] hidden and under a roof where [terrainAt] (`gBmMapTerrain[y][x]`) is
     * `TERRAIN_ROOF` at its position.
     */
    internal fun unitHideIfUnderRoof(o: Int, terrainAt: (x: Int, y: Int) -> Int) {
        val b = table.bytes
        if (terrainAt(s8(b, o + OFF_X), s8(b, o + OFF_Y)) == TERRAIN_ROOF) {
            put32(b, o + OFF_STATE, u32(b, o + OFF_STATE) or US_HIDDEN or US_UNDER_A_ROOF)
        }
    }

    /**
     * Ported from src/bmunit.c: FixROMUnitStructPtr
     *
     * Where the combined character and class attributes have `CA_BIT_23`, repoints the unit at
     * [o] to the character data one entry before the one its `number` names.
     */
    internal fun fixROMUnitStructPtr(o: Int) {
        val b = table.bytes
        val ch = u32(b, o + OFF_CHARACTER)
        val attributes = rom.u32(ch + 0x28) or rom.u32(u32(b, o + OFF_CLASS) + 0x28)
        if ((attributes and CA_BIT_23) != 0) {
            put32(b, o + OFF_CHARACTER, characterData(rom.u8(ch + 4) - 1))
        }
    }

    /**
     * Ported from src/bmunit.c: UnitLoadSupports
     *
     * Ported from src/bmreliance.c: GetUnitSupporterCount, GetUnitSupporterInitialExp (inlined:
     * `pSupportData->supportCount` and `->supportExpBase[i]`, no support data meaning none).
     *
     * Fills `supports[]` of the unit at [o] with the initial support exp of its character.
     */
    internal fun unitLoadSupports(o: Int) {
        val b = table.bytes
        val support = rom.u32(u32(b, o + OFF_CHARACTER) + 0x2C)
        if (support == 0) return
        val count = rom.u8(support + 0x15)
        for (i in 0 until count) {
            put8(b, o + OFF_SUPPORTS + i, rom.u8(support + 0x07 + i))
        }
    }

    /**
     * Ported from src/bmunit.c: UnitCheckStatCaps
     *
     * Clamps the stats, con bonus and mov bonus of the unit at [o] to the class limits.
     */
    internal fun unitCheckStatCaps(o: Int) {
        val b = table.bytes
        val ch = u32(b, o + OFF_CHARACTER)
        val cl = u32(b, o + OFF_CLASS)
        val red = (s8(b, o + OFF_INDEX) and 0xC0) == FACTION_RED
        fun cap(off: Int, max: Int) {
            if (s8(b, o + off) > max) put8(b, o + off, max)
        }
        cap(OFF_MAX_HP, if (red) 120 else 60)
        cap(0x14, rom.s8(cl + 0x14))
        cap(0x15, rom.s8(cl + 0x15))
        cap(0x16, rom.s8(cl + 0x16))
        cap(0x17, rom.s8(cl + 0x17))
        cap(0x18, rom.s8(cl + 0x18))
        cap(0x19, 30)
        cap(OFF_CON_BONUS, rom.s8(cl + 0x19) - (rom.s8(cl + 0x11) + rom.s8(ch + 0x13)))
        cap(OFF_MOV_BONUS, 15 - rom.s8(cl + 0x12))
    }

    private fun isGorgonEgg(o: Int): Boolean {
        val number = rom.u8(u32(table.bytes, o + OFF_CLASS) + 4)
        return number == CLASS_GORGONEGG || number == CLASS_GORGONEGG2
    }

    private fun RomView.s8(at: Int): Int = u8(at).toByte().toInt()

    /**
     * Ported from src/bmunit.c: GetCharacterData
     *
     * ROM address of `gCharacterData[charId - 1]`, or 0 (NULL) where [charId] < 1.
     */
    internal fun characterData(charId: Int): Int =
        if (charId < 1) 0 else CHARACTER_DATA + (charId - 1) * CHARACTER_DATA_SIZE

    /**
     * Ported from src/bmunit.c: GetClassData
     *
     * ROM address of `gClassData[classId - 1]`, or 0 (NULL) where [classId] < 1.
     */
    internal fun classData(classId: Int): Int =
        if (classId < 1) 0 else CLASS_DATA + (classId - 1) * CLASS_DATA_SIZE

    /** A ROM `struct UnitDefinition` (20 bytes) decoded from [rom] at [address]. */
    internal class UnitDef(rom: RomView, address: Int) {
        val charIndex = rom.u8(address)
        val classIndex = rom.u8(address + 1)
        val autolevel = (rom.u8(address + 3) and 1) != 0
        val allegiance = (rom.u8(address + 3) shr 1) and 3
        val level = rom.u8(address + 3) shr 3
        private val pos = rom.u16(address + 4)
        val x = pos and 0x3F
        val y = (pos shr 6) and 0x3F
        val genMonster = (pos and (1 shl 12)) != 0
        val itemDrop = (pos and (1 shl 13)) != 0
        val sumFlag = (pos and (1 shl 14)) != 0
        val redaCount = rom.u8(address + 7)
        val redas = rom.u32(address + 8)
        val items = IntArray(4) { rom.u8(address + 12 + it) }
        val ai = IntArray(4) { rom.u8(address + 16 + it) }
    }

    private companion object {
        const val CHARACTER_DATA = 0x08803D64
        const val CHARACTER_DATA_SIZE = 0x34
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54

        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80
        const val UNIT_LEVEL_MAX = 20
        const val TERRAIN_ROOF = 0x22
        const val US_HIDDEN = 1 shl 0
        const val US_UNDER_A_ROOF = 1 shl 7
        const val US_DROP_ITEM = 1 shl 12
        const val CA_BIT_23 = 1 shl 23
        const val CA_LOCK_1 = 1 shl 16
        const val CA_LOCK_2 = 1 shl 17
        const val CA_LOCK_3 = 1 shl 18
        const val CA_LOCK_4 = 1 shl 28
        const val CA_LOCK_5 = 1 shl 29
        const val CA_LOCK_6 = 1 shl 30
        const val CA_LOCK_7 = 1 shl 31
        const val UNIT_STATUS_SILENCED = 3
        const val UNIT_ITEM_COUNT = 5
        const val CLASS_GORGONEGG = 0x62
        const val CLASS_GORGONEGG2 = 0x34
        const val AI_HEAL_THRESHOLD_MASK = 0x7

        const val OFF_CHARACTER = 0x00
        const val OFF_CLASS = 0x04
        const val OFF_LEVEL = 0x08
        const val OFF_EXP = 0x09
        const val OFF_INDEX = 0x0B
        const val OFF_STATE = 0x0C
        const val OFF_X = 0x10
        const val OFF_Y = 0x11
        const val OFF_MAX_HP = 0x12
        const val OFF_CUR_HP = 0x13
        const val OFF_ITEMS = 0x1E
        const val OFF_STATUS = 0x30
        const val OFF_CON_BONUS = 0x1A
        const val OFF_MOV_BONUS = 0x1D
        const val OFF_RANKS = 0x28
        const val OFF_SUPPORTS = 0x32
        const val OFF_AI_CONFIG = 0x40
        const val OFF_AI1 = 0x42
        const val OFF_AI2 = 0x44
    }
}
