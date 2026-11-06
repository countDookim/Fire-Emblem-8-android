package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService
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
 * Only part of `LoadUnit` is ported: `genMonster`, gorgon eggs, summon (`sumFlag`) positions and
 * autolevel of blue units or of bosses / characters below 0x40 (these need
 * `UnitAutolevelRealistic`, the battle level-up code) throw
 * [UnsupportedOperationException]. Event-layer side effects
 * (`LoadUnit_800F704`) are not part of this class.
 *
 * @param table the unit arrays that are filled.
 * @param rom the ROM holding `gCharacterData`, `gClassData`, `gItemData`, the support data and
 *     the definitions.
 * @param unsealedCharacter `gPlaySt.unk1C[weaponType]`, the character the weapon type is unsealed
 *     for (only consulted for `IA_UNUSABLE` weapons).
 * @param rng the game's random number service (`NextRN_N`, `Roll1RN`), used by autolevel.
 * @param isHardMode `gPlaySt.chapterStateBits & PLAY_FLAG_HARD`, for the promoted level bonus.
 */
public class UnitLoader(
    private val table: UnitTable,
    private val rom: RomView,
    private val unsealedCharacter: (weaponType: Int) -> Int,
    private val rng: RngService,
    private val isHardMode: () -> Boolean,
) {
    private val items = ItemInfo(rom)

    /**
     * The signed level count `LoadUnit_800F704` passes to `UnitApplyBonusLevels` for red
     * characters from 0x3C on: the chapter's `difficultModeLevelBonus`, or minus its
     * `easyModeLevelMalus` / `normalModeLevelMalus`, by `config.controller` and `PLAY_FLAG_HARD`.
     * The default throws [UnsupportedOperationException].
     */
    public var chapterLevelBonus: () -> Int = {
        throw UnsupportedOperationException("LoadUnit_800F704: chapter level bonus not wired")
    }

    /**
     * `gBmMapUnit[y][x] | gBmMapOther[y][x] | gBmMapHidden[y][x]`, for the free-tile shortcut of
     * `AiGetUnitClosestValidPosition`; null treats every tile as occupied.
     */
    public var occupiedAt: ((x: Int, y: Int) -> Int)? = null

    /**
     * Ported from src/cp_utility.c: AiGetUnitClosestValidPosition
     *
     * partial: only the free-tile case (the position is kept); true where the search over the
     * movement map would run, which is not ported.
     */
    private fun occupied(x: Int, y: Int): Boolean = (occupiedAt?.invoke(x, y) ?: 1) != 0

    /**
     * Ported from src/bmunit.c: LoadUnit
     *
     * partial: `genMonster`, gorgon eggs, `sumFlag` positions and autolevel of bosses and
     * characters below 0x40 throws [UnsupportedOperationException].
     *
     * Loads the `struct UnitDefinition` at ROM address [defAddress] into a free slot of its
     * allegiance. [terrainAt] is `gBmMapTerrain[y][x]`. Returns the new unit's id, or 0 where the
     * C returns NULL (no free slot, or an allegiance other than 0 blue, 1 green, 2 red).
     */
    public fun loadUnit(defAddress: Int, terrainAt: (x: Int, y: Int) -> Int): Int =
        loadUnit(UnitDef(rom, defAddress), terrainAt)

    /** [loadUnit] for a definition value (e.g. an entry of the event `gLoadUnitBuffer`). */
    public fun loadUnit(def: UnitDef, terrainAt: (x: Int, y: Int) -> Int): Int {
        if (def.genMonster) {
            throw UnsupportedOperationException("LoadUnit: genMonster is not ported")
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
        if (def.autolevel) {
            if ((s8(table.bytes, o + OFF_INDEX) and 0xC0) == FACTION_BLUE) {
                unitAutolevelRealistic(o)
                unitAutolevelWExp(o, def)
                put8(table.bytes, o + OFF_SUPPORTS + 6, def.leaderCharIndex)
            } else {
            val number = rom.u8(u32(table.bytes, o + OFF_CHARACTER) + 4)
            if ((cAttributes(o) and CA_BOSS) != 0 || number < 0x40) {
                throw UnsupportedOperationException(
                    "LoadUnit: autolevel of bosses/characters < 0x40 (UnitAutolevelRealistic) " +
                        "is not ported",
                )
            }
            unitAutolevel(o)
            unitAutolevelWExp(o, def)
            // SetUnitLeaderCharId
            put8(table.bytes, o + OFF_SUPPORTS + 6, def.leaderCharIndex)
            }
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

    private fun cAttributes(o: Int): Int =
        rom.u32(u32(table.bytes, o + OFF_CHARACTER) + 0x28) or
            rom.u32(u32(table.bytes, o + OFF_CLASS) + 0x28)

    /**
     * Ported from src/bmunit.c: UnitAutolevelRealistic
     * Ported from src/bmbattle.c: CheckBattleUnitLevelUp, CanBattleUnitGainLevels,
     * CheckBattleUnitStatCaps
     * Ported from src/bmbattle.c: UpdateUnitFromBattle
     *
     * partial: works on the unit bytes without a `BattleUnit`; `gBmSt.gameStateBits & 0x40` is
     * taken as clear; of `UpdateUnitFromBattle` only level, exp, the stat changes and
     * `UnitCheckStatCaps` apply (curHP, state, items are unchanged, there is no status, weapon
     * exp or `expGain`).
     *
     * Levels the unit at [o] from its character's base level up to its level by its level difference.
     */
    internal fun unitAutolevelRealistic(o: Int) {
        val b = table.bytes
        val ch = u32(b, o + OFF_CHARACTER)
        val cl = u32(b, o + OFF_CLASS)
        var levelsLeft = s8(b, o + OFF_LEVEL) - rom.s8(ch + 0x0B)
        if (levelsLeft == 0) return
        put8(b, o + OFF_LEVEL, s8(b, o + OFF_LEVEL) - levelsLeft)
        val change = IntArray(7)
        while (levelsLeft > 0) {
            levelsLeft--
            // InitBattleUnit: the changes start at 0.
            change.fill(0)
            var exp = u8(b, o + OFF_EXP) + 100
            var level = s8(b, o + OFF_LEVEL)
            // CanBattleUnitGainLevels: exp is not disabled and the unit is blue.
            val canGain = exp != UNIT_EXP_DISABLED &&
                (s8(b, o + OFF_INDEX) and 0xC0) == FACTION_BLUE
            if (canGain && exp >= 100) {
                exp -= 100
                level++
                if ((cAttributes(o) and CA_MAXLEVEL10) != 0) {
                    if (level == 10) exp = UNIT_EXP_DISABLED
                } else if (level == 20) {
                    exp = UNIT_EXP_DISABLED
                }
                val bonus = if ((u32(b, o + OFF_STATE) and US_GROWTH_BOOST) != 0) 5 else 0
                var total = 0
                for (i in 0 until 7) {
                    change[i] = getStatIncrease(rom.u8(ch + 0x1C + i) + bonus)
                    total += change[i]
                }
                if (total == 0) {
                    for (pass in 0 until 2) {
                        var gained = false
                        for (i in 0 until 7) {
                            change[i] = getStatIncrease(rom.u8(ch + 0x1C + i))
                            if (change[i] != 0) {
                                gained = true
                                break
                            }
                        }
                        if (gained) break
                    }
                }
                // CheckBattleUnitStatCaps
                val red = (s8(b, o + OFF_INDEX) and 0xC0) == FACTION_RED
                for (i in 0 until 7) {
                    val at = if (i == 0) OFF_MAX_HP else 0x13 + i
                    val max = when (i) {
                        0 -> if (red) 120 else 60
                        6 -> 30
                        else -> rom.s8(cl + 0x13 + i)
                    }
                    if (s8(b, o + at) + change[i] > max) change[i] = max - s8(b, o + at)
                }
            }
            // UpdateUnitFromBattle
            put8(b, o + OFF_LEVEL, level)
            put8(b, o + OFF_EXP, exp)
            for (i in 0 until 7) {
                val at = if (i == 0) OFF_MAX_HP else 0x13 + i
                put8(b, o + at, s8(b, o + at) + change[i])
            }
            unitCheckStatCaps(o)
        }
    }

    /**
     * Ported from src/bmunit.c: UnitAutolevel
     * Ported from src/bmdifficulty.c: GetCurrentPromotedLevelBonus
     *
     * Raises the stats of the unit at [o] by its class growths for `level - 1` levels, after 9
     * (19 in hard mode) levels first if its class is promoted.
     */
    internal fun unitAutolevel(o: Int) {
        if ((cAttributes(o) and CA_PROMOTED) != 0) {
            unitAutolevelCore(o, if (isHardMode()) 19 else 9)
        }
        unitAutolevelCore(o, s8(table.bytes, o + OFF_LEVEL) - 1)
    }

    /**
     * Ported from src/bmunit.c: UnitApplyBonusLevels
     *
     * Raises ([levelCount] > 0) or lowers (< 0) the stats of the unit at [o] by that many levels
     * unless it is a gorgon egg, caps them and heals the unit.
     */
    internal fun unitApplyBonusLevels(o: Int, levelCount: Int) {
        if (levelCount == 0 || isGorgonEgg(o)) return
        if (levelCount > 0) {
            unitAutolevelCore(o, levelCount)
        } else {
            unitAutolevelPenalty(o, -levelCount)
        }
        unitCheckStatCaps(o)
        put8(table.bytes, o + OFF_CUR_HP, getUnitMaxHp(o))
    }

    /**
     * Ported from src/bmunit.c: UnitAutolevelPenalty
     *
     * Resets the stats of the unit at [o] to its bases and autolevels them again for its level
     * minus [levelCount].
     */
    internal fun unitAutolevelPenalty(o: Int, levelCount: Int) {
        val b = table.bytes
        val level = s8(b, o + OFF_LEVEL)
        val ch = u32(b, o + OFF_CHARACTER)
        val baseLevel = rom.s8(ch + 0x0B)
        if (levelCount == 0 || level <= baseLevel) return
        val reduced = level - levelCount
        val cl = u32(b, o + OFF_CLASS)
        put8(b, o + OFF_MAX_HP, rom.s8(ch + 0x0C) + rom.s8(cl + 0x0B))
        put8(b, o + 0x14, rom.s8(ch + 0x0D) + rom.s8(cl + 0x0C))
        put8(b, o + 0x15, rom.s8(ch + 0x0E) + rom.s8(cl + 0x0D))
        put8(b, o + 0x16, rom.s8(ch + 0x0F) + rom.s8(cl + 0x0E))
        put8(b, o + 0x17, rom.s8(ch + 0x10) + rom.s8(cl + 0x0F))
        put8(b, o + 0x18, rom.s8(ch + 0x11) + rom.s8(cl + 0x10))
        put8(b, o + 0x19, rom.s8(ch + 0x12))
        if (reduced > baseLevel) {
            put8(b, o + OFF_LEVEL, reduced)
            unitAutolevel(o)
            put8(b, o + OFF_LEVEL, level)
        }
    }

    /**
     * Ported from src/bmunit.c: UnitAutolevelCore
     *
     * Adds [levelCount] levels of autoleveled growth to the stats of the unit at [o].
     */
    internal fun unitAutolevelCore(o: Int, levelCount: Int) {
        if (levelCount == 0) return
        val b = table.bytes
        val cl = u32(b, o + OFF_CLASS)
        // maxHP (0x12), pow..lck (0x14..0x19): class growths at 0x1B.. in the same order.
        for (i in 0 until 7) {
            val growth = rom.u8(cl + 0x1B + i).toByte().toInt()
            val at = o + (if (i == 0) OFF_MAX_HP else 0x13 + i)
            put8(b, at, s8(b, at) + getAutoleveledStatIncrease(growth, levelCount))
        }
    }

    /**
     * Ported from src/bmbattle.c: GetAutoleveledStatIncrease
     *
     * The stat gain of [levelCount] levels of [growth], with +-1/8 of random spread.
     */
    internal fun getAutoleveledStatIncrease(growth: Int, levelCount: Int): Int {
        val total = growth * levelCount
        return getStatIncrease(total + (rng.nextN(total / 4) - total / 8))
    }

    /**
     * Ported from src/bmbattle.c: GetStatIncrease
     *
     * One point per full 100 of [growth], plus one more with the remainder as percent chance.
     */
    internal fun getStatIncrease(growth: Int): Int {
        var result = 0
        var g = growth
        while (g > 100) {
            result++
            g -= 100
        }
        if (rng.roll1(g)) result++
        return result
    }

    /**
     * Ported from src/bmunit.c: UnitAutolevelWExp
     * Ported from src/bmitem.c: CanUnitUseStaff
     *
     * For an autoleveled definition: raises the weapon ranks of the unit at [o] to what its
     * usable-restricted weapons and staves need.
     */
    internal fun unitAutolevelWExp(o: Int, def: UnitDef) {
        if (!def.autolevel) return
        val b = table.bytes
        var i = 0
        while (i < getUnitItemCount(o)) {
            var item = unitItem(o, i)
            i++
            val ia = items.getItemAttributes(item)
            if ((ia and (ItemInfo.IA_WEAPON or ItemInfo.IA_STAFF)) == 0) continue
            if ((ia and ItemInfo.IA_WEAPON) != 0 && canUnitUseWeapon(o, item)) continue
            if ((ia and ItemInfo.IA_STAFF) != 0 && canUnitUseStaff(o, item)) continue
            if ((ia and ItemInfo.IA_LOCK_ANY) != 0) continue
            val wType = items.getItemType(item)
            if (u8(b, o + OFF_RANKS + wType) == 0) item = 0
            put8(b, o + OFF_RANKS + wType, items.getItemRequiredExp(item))
        }
    }

    /**
     * Ported from src/bmunit.c: GetUnitItemCount
     *
     * The number of item slots of the unit at [o] up to its last non-empty one.
     */
    internal fun getUnitItemCount(o: Int): Int {
        for (i in UNIT_ITEM_COUNT - 1 downTo 0) {
            if (unitItem(o, i) != 0) return i + 1
        }
        return 0
    }

    /**
     * Ported from src/bmitem.c: CanUnitUseStaff
     *
     * Whether the unit at [o] can use [item] as a staff: not asleep, berserk or silenced, with
     * enough staff rank.
     */
    internal fun canUnitUseStaff(o: Int, item: Int): Boolean {
        if (item == 0) return false
        if ((items.getItemAttributes(item) and ItemInfo.IA_STAFF) == 0) return false
        val status = u8(table.bytes, o + OFF_STATUS) and 0xF
        if (status == UNIT_STATUS_SLEEP || status == UNIT_STATUS_BERSERK ||
            status == UNIT_STATUS_SILENCED
        ) {
            return false
        }
        val rank = u8(table.bytes, o + OFF_RANKS + items.getItemType(item))
        return rank >= items.getItemRequiredExp(item)
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
     * Ported from src/eventscr.c: LoadUnit_800F704
     * Ported from src/eventscr.c: sub_800F8A8
     * Ported from src/muctrl.c: MoveUnit_, MoveUnitExt
     * Ported from src/bmunit.c: GetUnitFromCharIdAndFaction, UnitChangeFaction, CopyUnit
     *
     * partial: the event engine's unit load. Finds the definition's character among the blue
     * units (moving it to the definition's faction if that is not blue), loads a new unit
     * otherwise, and sets its state bits and position. [b] are the move flags, [quiet] the
     * silent-load flag, [d] the `load_unit2` flag. Not ported (throw
     * [UnsupportedOperationException]): red characters from 0x3C on (`UnitApplyBonusLevels`
     * with chapter data), `sumFlag` definitions, a non-zero terrain under a [b] bit 0 target
     * (`AiGetUnitClosestValidPosition`), units rescuing or in a ballista (`UnitFinalizeMovement`)
     * and, for a definition with REDAs, the non-quiet walk (`MuCtr_StartDefinedMove`). The
     * `gActiveUnit` update of `UnitChangeFaction`, and the `RefreshEntityBmMaps` /
     * `RefreshUnitSprites` after the move, are left to the caller. Returns the unit's id.
     */
    public fun loadUnitForEvent(
        defAddress: Int,
        b: Int,
        quiet: Boolean,
        d: Boolean,
        terrainAt: (x: Int, y: Int) -> Int,
    ): Int = loadUnitForEvent(UnitDef(rom, defAddress), b, quiet, d, terrainAt)

    /** [loadUnitForEvent] for a definition value (e.g. an entry of `gLoadUnitBuffer`). */
    public fun loadUnitForEvent(
        def: UnitDef,
        b: Int,
        quiet: Boolean,
        d: Boolean,
        terrainAt: (x: Int, y: Int) -> Int,
    ): Int {
        var flags = b
        var id = getUnitFromCharIdAndFaction(def.charIndex, FACTION_BLUE)
        if (def.allegiance != 0 && id >= 0) {
            val faction = if (def.allegiance == 1) FACTION_GREEN else FACTION_RED
            unitChangeFaction(id, faction)
            id = table.getUnitFromCharId(def.charIndex)
        }
        if (id < 0) {
            id = loadUnit(def, terrainAt)
            if (id == 0) throw NullPointerException("LoadUnit_800F704: no free unit slot")
            if (d && def.allegiance == 0) table.setState(id, table.state(id) or US_BIT22)
        } else if (def.allegiance == 0) {
            var state = table.state(id) and US_UNSELECTABLE.inv()
            state = if (d) {
                if ((state and US_DEAD) != 0) state or US_BIT22 else state
            } else {
                state and US_BIT22.inv()
            }
            table.setState(id, state)
            val o = table.unitOffset(id)
            val x = if (def.redaCount != 0) lastReda(def) and 0x3F else def.x
            val y = if (def.redaCount != 0) (lastReda(def) shr 6) and 0x3F else def.y
            if (s8(table.bytes, o + OFF_X) == x && s8(table.bytes, o + OFF_Y) == y) {
                flags = flags and 1.inv()
            }
        }
        val o = table.unitOffset(id)
        put8(table.bytes, o + OFF_X, def.x)
        put8(table.bytes, o + OFF_Y, def.y)
        val number = rom.u8(u32(table.bytes, o + OFF_CHARACTER) + 4)
        if (def.allegiance == 2 && number >= 0x3C) {
            unitApplyBonusLevels(o, chapterLevelBonus())
        }
        moveLoadedUnit(id, def, flags, quiet, terrainAt)
        return id
    }

    private fun lastReda(def: UnitDef): Int = rom.u32(def.redas + 8 * (def.redaCount - 1))

    /** sub_800F8A8, MoveUnit_ and MoveUnitExt without the map and sprite refresh. */
    private fun moveLoadedUnit(
        id: Int,
        def: UnitDef,
        flagsIn: Int,
        quiet: Boolean,
        terrainAt: (x: Int, y: Int) -> Int,
    ) {
        var flags = flagsIn
        if (def.sumFlag) flags = flags or 2
        val o = table.unitOffset(id)
        val b = table.bytes
        if (def.redaCount != 0 && !quiet && (u32(b, o + OFF_STATE) and US_UNDER_A_ROOF) == 0) {
            throw UnsupportedOperationException(
                "LoadUnit_800F704: MuCtr_StartDefinedMove (MU walk) not ported",
            )
        }
        var x = def.x
        var y = def.y
        if (def.redaCount != 0) {
            x = lastReda(def) and 0x3F
            y = (lastReda(def) shr 6) and 0x3F
        }
        if ((flags and 2) != 0) {
            throw UnsupportedOperationException("AdjustNewUnitPosition: flags & 2 not ported")
        }
        if ((flags and 1) != 0 && terrainAt(x, y) != 0 && occupied(x, y)) {
            throw UnsupportedOperationException("AdjustNewUnitPosition: closest position")
        }
        put8(b, o + OFF_X, x)
        put8(b, o + OFF_Y, y)
        val state = u32(b, o + OFF_STATE)
        if ((state and (US_RESCUING or US_IN_BALLISTA)) != 0) {
            throw UnsupportedOperationException("UnitFinalizeMovement: rescue/ballista not ported")
        }
        if ((state and US_UNDER_A_ROOF) == 0) put32(b, o + OFF_STATE, state and US_HIDDEN.inv())
    }

    /**
     * Ported from src/bmunit.c: GetUnitFromCharIdAndFaction
     *
     * The id of the first valid unit of [faction] whose character is [charId], or -1.
     */
    internal fun getUnitFromCharIdAndFaction(charId: Int, faction: Int): Int {
        for (i in faction + 1 until faction + 0x40) {
            if (table.unitOffset(i) >= 0 && table.isValid(i) && table.charId(i) == charId) return i
        }
        return -1
    }

    /**
     * Ported from src/bmunit.c: UnitChangeFaction, CopyUnit
     *
     * partial: `gActiveUnit` is not updated. Moves unit [id] into a free slot of [faction].
     */
    internal fun unitChangeFaction(id: Int, faction: Int) {
        val newId = getFreeUnit(faction)
        if (newId == 0) throw NullPointerException("UnitChangeFaction: no free unit slot")
        val from = table.unitOffset(id)
        val to = table.unitOffset(newId)
        val b = table.bytes
        val index = b[to + OFF_INDEX]
        b.copyInto(b, to, from, from + UnitTable.UNIT_SIZE)
        b[to + OFF_INDEX] = index
        clearUnit(from)
        if (u8(b, to + OFF_EXP) == 0xFF) {
            val blue = faction == FACTION_BLUE && s8(b, to + OFF_LEVEL) != UNIT_LEVEL_MAX
            put8(b, to + OFF_EXP, if (blue) 0 else 0xFF)
        }
        put32(b, to + OFF_STATE, u32(b, to + OFF_STATE) and US_DROP_ITEM.inv())
        val rescue = u8(b, to + OFF_RESCUE)
        if (rescue != 0) b[table.unitOffset(rescue) + OFF_RESCUE] = b[to + OFF_INDEX]
    }

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

    /**
     * A `struct UnitDefinition` (20 bytes), decoded from ROM or copied with changes.
     *
     * @property charIndex `charIndex`.
     * @property classIndex `classIndex`.
     * @property leaderCharIndex `leaderCharIndex`.
     * @property autolevel `autolevel`.
     * @property allegiance `allegiance` (0 blue, 1 green, 2 red).
     * @property level `level`.
     * @property x `xPosition`.
     * @property y `yPosition`.
     * @property genMonster `genMonster`.
     * @property itemDrop `itemDrop`.
     * @property sumFlag `sumFlag`.
     * @property redaCount `redaCount`.
     * @property redas ROM address of the REDA list (0 for none).
     * @property items `items[4]`.
     * @property ai `ai[4]`.
     */
    public data class UnitDef(
        val charIndex: Int,
        val classIndex: Int,
        val leaderCharIndex: Int,
        val autolevel: Boolean,
        val allegiance: Int,
        val level: Int,
        val x: Int,
        val y: Int,
        val genMonster: Boolean,
        val itemDrop: Boolean,
        val sumFlag: Boolean,
        val redaCount: Int,
        val redas: Int,
        val items: IntArray,
        val ai: IntArray,
    ) {
        /** Decodes the definition at [address] of [rom]. */
        public constructor(rom: RomView, address: Int) : this(
            charIndex = rom.u8(address),
            classIndex = rom.u8(address + 1),
            leaderCharIndex = rom.u8(address + 2),
            autolevel = (rom.u8(address + 3) and 1) != 0,
            allegiance = (rom.u8(address + 3) shr 1) and 3,
            level = rom.u8(address + 3) shr 3,
            x = rom.u16(address + 4) and 0x3F,
            y = (rom.u16(address + 4) shr 6) and 0x3F,
            genMonster = (rom.u16(address + 4) and (1 shl 12)) != 0,
            itemDrop = (rom.u16(address + 4) and (1 shl 13)) != 0,
            sumFlag = (rom.u16(address + 4) and (1 shl 14)) != 0,
            redaCount = rom.u8(address + 7),
            redas = rom.u32(address + 8),
            items = IntArray(4) { rom.u8(address + 12 + it) },
            ai = IntArray(4) { rom.u8(address + 16 + it) },
        )
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
        const val US_UNSELECTABLE = 1 shl 1
        const val US_DEAD = 1 shl 2
        const val US_RESCUING = 1 shl 4
        const val US_IN_BALLISTA = 1 shl 11
        const val US_BIT22 = 1 shl 22
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
        const val UNIT_STATUS_SLEEP = 2
        const val UNIT_STATUS_SILENCED = 3
        const val UNIT_STATUS_BERSERK = 4
        const val CA_PROMOTED = 1 shl 8
        const val CA_BOSS = 1 shl 15
        const val CA_MAXLEVEL10 = 1 shl 19
        const val US_GROWTH_BOOST = 1 shl 13
        const val UNIT_EXP_DISABLED = 0xFF
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
        const val OFF_RESCUE = 0x1B
        const val OFF_CON_BONUS = 0x1A
        const val OFF_MOV_BONUS = 0x1D
        const val OFF_RANKS = 0x28
        const val OFF_SUPPORTS = 0x32
        const val OFF_AI_CONFIG = 0x40
        const val OFF_AI1 = 0x42
        const val OFF_AI2 = 0x44
    }
}
