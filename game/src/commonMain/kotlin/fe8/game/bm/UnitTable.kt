package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * Raw-unit table (the real Unit model of M6 is not written yet): the bytes of the four
 * contiguous EWRAM unit arrays `gUnitArrayBlue` (0x0202BE4C, 62 units), `gUnitArrayRed`
 * (0x0202CFBC, 50), `gUnitArrayGreen` (0x0202DDCC, 20) and `gUnitArrayPurple` (0x0202E36C, 5),
 * each `struct Unit` being [UNIT_SIZE] bytes, plus typed accessors for the few fields the
 * ported functions read.
 *
 * Units are addressed by the id `GetUnit` takes (`faction + 1 + slot`, the value of the `index`
 * field), not by pointer: [unitOffset] is the `gUnitLookup` replacement. The character and class
 * data pointers stored in a unit point into the ROM; they are resolved through [rom].
 */
public class UnitTable(
    /** The ROM that `pCharacterData` / `pClassData` point into. */
    private val rom: RomView,
) {
    /** The raw unit arrays, blue then red, green and purple, as laid out in EWRAM. */
    public val bytes: ByteArray = ByteArray(TOTAL_SIZE)

    /** Copies the unit arrays out of the EWRAM image [ewram] (the 256 KiB at 0x02000000). */
    public fun loadFrom(ewram: ByteArray) {
        ewram.copyInto(bytes, 0, BLUE_ADDRESS - EWRAM_BASE, BLUE_ADDRESS - EWRAM_BASE + TOTAL_SIZE)
    }

    /** Copies the unit arrays into the EWRAM image [ewram]. */
    public fun storeTo(ewram: ByteArray) {
        bytes.copyInto(ewram, BLUE_ADDRESS - EWRAM_BASE)
    }

    /**
     * Ported from src/bmunit.c: GetUnit
     *
     * Byte offset in [bytes] of the unit with id [id] (`gUnitLookup[id & 0xFF]`), or -1 where the
     * lookup table holds NULL.
     */
    public fun unitOffset(id: Int): Int {
        val lookupId = id and 0xFF
        // Ids are faction (blue 0x00, green 0x40, red 0x80, purple 0xC0) + 1 + slot; the arrays are
        // stored blue, red, green, purple, so green sits after red in [bytes].
        val slot = when (lookupId) {
            in 0x01..0x3E -> lookupId - 0x01
            in 0x81..0xB2 -> BLUE_COUNT + lookupId - 0x81
            in 0x41..0x54 -> BLUE_COUNT + RED_COUNT + lookupId - 0x41
            in 0xC1..0xC5 -> BLUE_COUNT + RED_COUNT + GREEN_COUNT + lookupId - 0xC1
            else -> return -1
        }
        return slot * UNIT_SIZE
    }

    /** `UNIT_IS_VALID(GetUnit(id))`: the unit exists and has a character (`pCharacterData`). */
    public fun isValid(id: Int): Boolean {
        val offset = unitOffset(id)
        return offset >= 0 && u32(bytes, offset + OFF_CHARACTER) != 0
    }

    /**
     * Transcribed from src/bmphase.c: CountUnitsInState (not verified:
     *   only reached by the victory-song check; the enemy-phase windows call it, but the result
     *   only shows as the song id, which no capture records)
     *
     * Counts the valid units with ids `faction + 1` until `faction + 0x40` that have none of the
     * `stateMask` bits.
     */
    public fun countUnitsInState(faction: Int, stateMask: Int): Int {
        var count = 0
        for (id in faction + 1 until faction + 0x40) {
            if (isValid(id) && (state(id) and stateMask) == 0) count++
        }
        return count
    }

    /** `curHP` (s8, 0x13) of unit [id]: its hit points left. */
    public fun currentHp(id: Int): Int = s8(bytes, unitOffset(id) + 0x13)

    /** `items[slot]` (u16 at 0x1E): the item word (id low byte, uses high byte) of unit [id]. */
    public fun item(id: Int, slot: Int): Int {
        val at = unitOffset(id) + 0x1E + 2 * slot
        return u8(bytes, at) or (u8(bytes, at + 1) shl 8)
    }

    /**
     * Ported from src/bmunit.c: CountAvailableBlueUnits
     *
     * Verified for "some unit is available" (the Prologue after a Wait).
     *
     * Number of valid units with ids 1 to 0x3F that are not dead, undeployed or `US_BIT16`.
     */
    public fun countAvailableBlueUnits(): Int {
        var count = 0
        for (id in 1 until 0x40) {
            if (isValid(id) && (state(id) and US_UNAVAILABLE) == 0) count++
        }
        return count
    }

    /**
     * Ported from src/bmunit.c: CountRedUnits
     *
     * Number of valid, living units with ids 0x81 to 0xBF.
     */
    public fun countRedUnits(): Int {
        var count = 0
        for (id in 0x81 until 0xC0) {
            if (isValid(id) && (state(id) and US_DEAD) == 0) count++
        }
        return count
    }

    /**
     * Transcribed from src/bmunit.c: CountGreenUnits (not verified: no capture reads it)
     *
     * Number of valid, living units with ids 0x41 to 0x7F.
     */
    public fun countGreenUnits(): Int {
        var count = 0
        for (id in 0x41 until 0x80) {
            if (isValid(id) && (state(id) and US_DEAD) == 0) count++
        }
        return count
    }

    /**
     * Ported from src/uidebug.c: AreAnyEnemyUnitDead
     *
     * Despite its name the C returns 1 when a living enemy (ids 0x81 to 0xBF) is left, as this
     * does.
     */
    public fun areAnyEnemyUnitAlive(): Boolean {
        for (id in 0x81 until 0xC0) {
            if (isValid(id) && (state(id) and US_DEAD) == 0) return true
        }
        return false
    }

    /**
     * Ported from src/bmunit.c: InitUnits
     *
     * Clears every unit that `GetUnit` can return for ids 0..0xFF (`ClearUnit`: all 0x48 bytes
     * zeroed, `index` kept) and stores its id in `index` (0x0B).
     */
    public fun initUnits() {
        for (id in 0 until 0x100) {
            val offset = unitOffset(id)
            if (offset < 0) continue
            bytes.fill(0, offset, offset + UNIT_SIZE)
            bytes[offset + 0x0B] = id.toByte()
        }
    }

    /** `pCharacterData->number` of unit [id], read from the ROM (the unit must be valid). */
    public fun charId(id: Int): Int = rom.u8(u32(bytes, unitOffset(id) + OFF_CHARACTER) + 4)

    /**
     * Zeroes all 0x48 bytes of unit [id] while preserving its `index` byte (offset 0x0B), matching
     * `ClearUnit` (`src/bmunit.c`).
     */
    public fun clearUnit(id: Int) {
        val offset = unitOffset(id)
        if (offset >= 0) clearUnit(bytes, offset)
    }

    /**
     * Ported from src/bmunit.c: UnitKill
     *
     * Removes unit [id] from play. A non-blue unit (and a blue phantom) loses its character
     * (`pCharacterData = NULL`), so [isValid] is false for it from then on; any other blue unit
     * is marked `US_DEAD | US_HIDDEN`. The blue branch is transcribed (not verified: no capture
     * kills a blue unit). `InitUnitsupports` (the supports of a dead blue unit) is a stand-in
     * until the supports lane (M6-J).
     */
    public fun unitKill(id: Int) {
        val offset = unitOffset(id)
        if ((s8(bytes, offset + 0x0B) and 0xC0) == 0 && classId(id) != CLASS_PHANTOM) {
            StructCodec.put32(
                bytes,
                offset + 0x0C,
                u32(bytes, offset + 0x0C) or US_DEAD or US_HIDDEN,
            )
        } else {
            StructCodec.put32(bytes, offset + OFF_CHARACTER, 0)
        }
    }

    /** Byte [offset] of the `CharacterData` of unit [id] in the ROM (the unit must be valid). */
    public fun characterByte(id: Int, offset: Int): Int =
        rom.u8(u32(bytes, unitOffset(id) + OFF_CHARACTER) + offset)

    /** `pClassData->number` of unit [id], read from the ROM (the unit must be valid). */
    public fun classId(id: Int): Int = rom.u8(u32(bytes, unitOffset(id) + OFF_CLASS) + 4)

    /** `index` (s8, 0x0B) of unit [id]. */
    public fun index(id: Int): Int = s8(bytes, unitOffset(id) + 0x0B)

    /** `state` (u32, 0x0C) of unit [id]. */
    public fun state(id: Int): Int = u32(bytes, unitOffset(id) + 0x0C)

    /** Sets `Unit.state` of unit [id] (the unit must exist). */
    public fun setState(id: Int, value: Int) {
        StructCodec.put32(bytes, unitOffset(id) + 0x0C, value)
    }

    /**
     * Hides every valid blue unit (`US_HIDDEN`) and clears the blue phantoms, then clears every
     * valid red (0x81..0xBF) and green (0x41..0x7F) unit. The C ends with `RefreshEntityBmMaps`
     * and `RefreshUnitSprites`, which stay with the caller (`Fe8Boot` hook).
     *
     * Transcribed from src/bmunit.c: ClearTemporaryUnits (not verified: the Prologue at f9863
     *   has no phantom, green or red unit left to clear; the blue `US_HIDDEN` part is checked
     *   against `f10300` of `ram_trace_prologue_end` by `PrologueEndingWireTest`)
     */
    public fun clearTemporaryUnits() {
        for (i in 1 until 0x40) {
            if (!isValid(i)) continue
            setState(i, state(i) or US_HIDDEN)
            if (classId(i) == CLASS_PHANTOM) clearUnit(i)
        }
        for (i in 0x81 until 0xC0) {
            if (isValid(i)) clearUnit(i)
        }
        for (i in 0x41 until 0x80) {
            if (isValid(i)) clearUnit(i)
        }
    }

    /** `xPos` (s8, 0x10) of unit [id]. */
    public fun xPos(id: Int): Int = s8(bytes, unitOffset(id) + 0x10)

    /** `yPos` (s8, 0x11) of unit [id]. */
    public fun yPos(id: Int): Int = s8(bytes, unitOffset(id) + 0x11)

    /** Sets `xPos` and `yPos` of unit [id] (the unit must exist). */
    public fun setPosition(id: Int, x: Int, y: Int) {
        bytes[unitOffset(id) + 0x10] = x.toByte()
        bytes[unitOffset(id) + 0x11] = y.toByte()
    }

    /** `aiFlags` (u8, 0x0A) of unit [id]: the `AI_UNIT_FLAG_*` bits of `include/cp_common.h`. */
    public fun aiFlags(id: Int): Int = u8(bytes, unitOffset(id) + 0x0A)

    /** Sets `aiFlags` (u8, 0x0A) of unit [id] (the unit must exist). */
    public fun setAiFlags(id: Int, value: Int) {
        bytes[unitOffset(id) + 0x0A] = value.toByte()
    }

    /**
     * `ai_config` (u16, 0x40) of unit [id]: the AI bitmask (`AI_UNIT_CONFIG_*`: heal threshold in
     * bits 0-2, combat weight table in bits 3-7, stay flag at bit 13).
     */
    public fun aiConfig(id: Int): Int = StructCodec.u16(bytes, unitOffset(id) + 0x40)

    /** `ai1` (u8, 0x42) of unit [id]: the index into `gAi1ScriptTable`. */
    public fun ai1(id: Int): Int = u8(bytes, unitOffset(id) + 0x42)

    /** `ai_a_pc` (u8, 0x43) of unit [id]: the index of its current command in script A. */
    public fun aiAPc(id: Int): Int = u8(bytes, unitOffset(id) + 0x43)

    /** Sets `ai_a_pc` (u8, 0x43) of unit [id] (the unit must exist). */
    public fun setAiAPc(id: Int, value: Int) {
        bytes[unitOffset(id) + 0x43] = value.toByte()
    }

    /** `ai2` (u8, 0x44) of unit [id]: the index into `gAi2ScriptTable`. */
    public fun ai2(id: Int): Int = u8(bytes, unitOffset(id) + 0x44)

    /** `ai_b_pc` (u8, 0x45) of unit [id]: the index of its current command in script B. */
    public fun aiBPc(id: Int): Int = u8(bytes, unitOffset(id) + 0x45)

    /** Sets `ai_b_pc` (u8, 0x45) of unit [id] (the unit must exist). */
    public fun setAiBPc(id: Int, value: Int) {
        bytes[unitOffset(id) + 0x45] = value.toByte()
    }

    /**
     * `ai_counter` (u8, 0x46) of unit [id]: how many times the current script command has run, for
     * commands that repeat a set number of times.
     */
    public fun aiCounter(id: Int): Int = u8(bytes, unitOffset(id) + 0x46)

    /** Sets `ai_counter` (u8, 0x46) of unit [id] (the unit must exist). */
    public fun setAiCounter(id: Int, value: Int) {
        bytes[unitOffset(id) + 0x46] = value.toByte()
    }

    /**
     * `UNIT_LEADER_CHARACTER`: `supports[6]` (u8, 0x38) of unit [id], the character id of the
     * leader it follows. (`GetUnitLeaderCharId` returns 0 for blue units; this does not.)
     */
    public fun leaderCharId(id: Int): Int = u8(bytes, unitOffset(id) + OFF_LEADER)

    /**
     * Ported from src/bmunit.c: GetUnitLeaderCharId
     *
     * The character id of the leader of unit [id]. Blue units (player faction, `index & 0xC0 == 0`)
     * have no leader and return 0; non-player units return `UNIT_LEADER_CHARACTER` (`supports[6]`).
     *
     * Exercised in captures: player unit check (`index & 0xC0 == 0`) returning 0, and non-player
     *   unit check (`index & 0xC0 != 0`) returning `supports[6]` (0 for O'Neill at `r04274`).
     * Unexercised in captures: non-player unit with non-zero leader character id.
     */
    public fun getUnitLeaderCharId(id: Int): Int = getUnitLeaderCharId(bytes, unitOffset(id))

    /**
     * Ported from src/bmunit.c: GetUnitLeaderCharId
     *
     * [getUnitLeaderCharId] for the unit struct at [offset] in [b].
     */
    public fun getUnitLeaderCharId(b: ByteArray, offset: Int): Int {
        if ((s8(b, offset + OFF_INDEX) and 0xC0) == 0) return 0
        return u8(b, offset + OFF_LEADER)
    }

    /** `rescue` (u8, 0x1B) of unit [id]: deployment index of rescued partner, or 0. */
    public fun rescue(id: Int): Int = u8(bytes, unitOffset(id) + OFF_RESCUE)

    /** Sets `rescue` (u8, 0x1B) of unit [id]. */
    public fun setRescue(id: Int, value: Int) {
        bytes[unitOffset(id) + OFF_RESCUE] = value.toByte()
    }

    /** `statusIndex` (low nibble of byte 0x30) of unit [id]. */
    public fun statusIndex(id: Int): Int = u8(bytes, unitOffset(id) + OFF_STATUS) and 0xF

    /**
     * Ported from src/bmunit.c: SetUnitStatus
     *
     * Sets status [status] on unit [id]. If [status] is 0, both status index and duration are
     * cleared to 0. Otherwise, sets the status index to [status] and duration to 5 turns.
     *
     * Exercised in captures: status == 0 clearing in [refreshAllies] at chapter win (`f4894`).
     * Unexercised in captures: status != 0 application (no non-zero status is applied in
     *   Prologue captures).
     */
    public fun setUnitStatus(id: Int, status: Int) {
        setUnitStatus(bytes, unitOffset(id), status)
    }

    /**
     * Transcribed from src/bmunit.c: SetUnitStatusExt (not verified: no Prologue capture applies
     * non-zero duration status via SetUnitStatusExt)
     *
     * Sets status [status] and turn duration [duration] on unit [id].
     */
    public fun setUnitStatusExt(id: Int, status: Int, duration: Int) {
        setUnitStatusExt(bytes, unitOffset(id), status, duration)
    }

    /**
     * `UNIT_CATTRIBUTES`: `pCharacterData->attributes | pClassData->attributes` of unit [id],
     * both read from the ROM.
     */
    public fun cAttributes(id: Int): Int = cAttributes(bytes, unitOffset(id))

    /**
     * `UNIT_CATTRIBUTES`: `pCharacterData->attributes | pClassData->attributes` of the unit struct
     * at [offset] in [b].
     */
    public fun cAttributes(b: ByteArray, offset: Int): Int {
        val charPtr = u32(b, offset + OFF_CHARACTER)
        val classPtr = u32(b, offset + OFF_CLASS)
        val charAttr = if (charPtr != 0) rom.u32(charPtr + OFF_ATTRIBUTES) else 0
        val classAttr = if (classPtr != 0) rom.u32(classPtr + OFF_ATTRIBUTES) else 0
        return charAttr or classAttr
    }

    /**
     * `UNIT_MOV`: `movBonus` (s8, 0x1D) of unit [id] plus the `baseMov` (s8, 0x12) of its class,
     * read from the ROM.
     */
    public fun movement(id: Int): Int {
        val offset = unitOffset(id)
        val baseMov = rom.u8(u32(bytes, offset + OFF_CLASS) + 0x12).toByte().toInt()
        return s8(bytes, offset + 0x1D) + baseMov
    }

    /**
     * Transcribed from src/bmunit.c: UnitHasMagicRank (not verified: no unit in the captures has a
     * magic rank, and no captured decision or output depends on it)
     *
     * Whether unit [id] has a non-zero weapon rank in any magic type (Staff, Anima, Light, Dark).
     */
    public fun unitHasMagicRank(id: Int): Boolean {
        val o = unitOffset(id) + OFF_RANKS
        val combined = (bytes[o + ITYPE_STAFF].toInt() and 0xFF) or
            (bytes[o + ITYPE_ANIMA].toInt() and 0xFF) or
            (bytes[o + ITYPE_LIGHT].toInt() and 0xFF) or
            (bytes[o + ITYPE_DARK].toInt() and 0xFF)
        return combined != 0
    }

    /**
     * Transcribed from src/bmunit.c: SetAllUnitNotBackSprite (not verified: it runs in the select
     * window, but no unit has `US_BIT8` set there, so the clearing is unobserved)
     *
     * Clears `US_BIT8` in the state of every valid unit with id 1 to 0xBF.
     */
    public fun setAllUnitNotBackSprite() {
        for (id in 1 until 0xC0) {
            if (isValid(id)) setState(id, state(id) and US_BIT8.inv())
        }
    }

    /**
     * Transcribed from src/bmphase.c: GetPhaseAbleUnitCount (not verified: no trace has a unit
     * that the asleep/berserk/unselectable/NOT_ABLE exclusions would skip; the enemy-phase
     * windows only show that the count is 0 exactly when the phase has no banner)
     *
     * Number of units of [faction] (0x00 blue, 0x40 green, 0x80 red) that can act this phase:
     * valid, not unselectable / dead / undeployed / rescued / under a roof / bit 16, not asleep
     * or berserk, and without the unselectable class or character attribute.
     */
    public fun getPhaseAbleUnitCount(faction: Int): Int {
        var count = 0
        for (id in faction + 1 until faction + 0x40) {
            if (!isValid(id)) continue
            if ((state(id) and NOT_ABLE) != 0) continue
            val status = statusIndex(id)
            if (status == STATUS_SLEEP || status == STATUS_BERSERK) continue
            if ((cAttributes(id) and CA_UNSELECTABLE) != 0) continue
            count += 1
        }
        return count
    }

    /**
     * Ported from src/bmunit.c: GetUnitFromCharId
     *
     * The id (as taken by [unitOffset]) of the first valid unit, in id order from 1 to 0xFF,
     * whose character is [targetCharId]; -1 where the C returns NULL.
     */
    public fun getUnitFromCharId(targetCharId: Int): Int {
        for (id in 1 until 0x100) {
            if (isValid(id) && charId(id) == targetCharId) return id
        }
        return -1
    }

    /**
     * Transcribed from src/prep_sallycursor.c: GetPlayerLeaderPid (not verified: no test checks it
     *   against a trace yet)
     *
     * The character id of the player leader for the current route and chapter in [playSt]:
     * Eirika (Ephraim on his route or in chapter 5x), unless the battle map is not a story map
     * and the leader is missing or undeployed, in which case the first available blue unit.
     * [nodes] feeds [BattleMapKind.getBattleMapKind].
     *
     * NOT verified: the reference trace only covers the story-map early return, so it carries
     * no `@VerifiesPort` test and does not count as ported.
     */
    public fun getPlayerLeaderPid(playSt: PlaySt, nodes: BattleMapNodes): Int {
        val ephraimRoute = playSt.chapterModeIndex == CHAPTER_MODE_EPHRAIM
        val characterId = if (ephraimRoute) CHARACTER_EPHRAIM else CHARACTER_EIRIKA
        if ((playSt.chapterIndex and 0xFF) == CHAPTER_L_5X) return CHARACTER_EPHRAIM
        if (BattleMapKind.getBattleMapKind(playSt.chapterIndex, nodes) == BattleMapKind.STORY) {
            return characterId
        }
        val leaderId = getUnitFromCharId(characterId)
        if (leaderId >= 0 && (state(leaderId) and US_NOT_DEPLOYED) == 0) return characterId
        for (id in 1 until 0x40) {
            if (!isValid(id)) continue
            if ((state(id) and US_UNAVAILABLE) != 0) continue
            return charId(id)
        }
        return characterId
    }

    /**
     * Transcribed from src/bmitem.c: GetUnitItemSlot (not verified: captured calls have one
     * return value; Prologue captures have no matching key/item slot; EP-29a tracks positive
     * matches under EP-29cap)
     *
     * Returns the first inventory slot (0 until [UNIT_ITEM_COUNT]) containing [itemIndex],
     * or -1 if absent.
     */
    public fun getUnitItemSlot(id: Int, itemIndex: Int): Int =
        getUnitItemSlot(bytes, unitOffset(id), itemIndex)

    /**
     * Ported from src/bmunit.c: GetUnitKeyItemSlotForTerrain
     *
     * Finds an inventory slot with an item capable of unlocking [terrain] (doors or chests).
     * If the unit has the thief attribute (`CA_THIEF`) and holds a lockpick (`ITEM_LOCKPICK`),
     * returns the lockpick's slot.
     * Otherwise:
     * - For `TERRAIN_CHEST_FULL`: returns the slot of `ITEM_CHESTKEY`, or if absent,
     *   `ITEM_CHESTKEY_BUNDLE`.
     * - For `TERRAIN_DOOR`: returns the slot of `ITEM_DOORKEY`.
     * - For other terrain: returns the first slot holding item 0 (empty slot), or -1 if none.
     *
     * Exercised in captures: Seth and Eirika terrain 0 (first empty slot) and missing door/chest
     *   key (-1) in Prologue clear (`f4181`).
     * Unexercised in captures: positive door/chest key matches and CA_THIEF lockpick branch
     *   (Prologue units carry no keys and have no CA_THIEF).
     */
    public fun getUnitKeyItemSlotForTerrain(id: Int, terrain: Int): Int =
        getUnitKeyItemSlotForTerrain(bytes, unitOffset(id), terrain)

    /**
     * Ported from src/bmunit.c: GetUnitKeyItemSlotForTerrain
     *
     * [getUnitKeyItemSlotForTerrain] for the unit struct at [offset] in [b].
     */
    public fun getUnitKeyItemSlotForTerrain(b: ByteArray, offset: Int, terrain: Int): Int {
        if ((cAttributes(b, offset) and CA_THIEF) != 0) {
            val slot = getUnitItemSlot(b, offset, ITEM_LOCKPICK)
            if (slot >= 0) return slot
        }
        return when (terrain) {
            TERRAIN_CHEST_FULL -> {
                val slot = getUnitItemSlot(b, offset, ITEM_CHESTKEY)
                if (slot < 0) getUnitItemSlot(b, offset, ITEM_CHESTKEY_BUNDLE) else slot
            }
            TERRAIN_DOOR -> getUnitItemSlot(b, offset, ITEM_DOORKEY)
            else -> getUnitItemSlot(b, offset, 0)
        }
    }

    /**
     * Ported from src/bmunit.c: GetUnitSMSId
     *
     * Returns the standing map sprite id of unit [id]: its class's `SMSId` (class byte 6), or,
     * for a unit in a ballista (`US_IN_BALLISTA`), the rider-on-ballista sprite id for the
     * ballista's item (0x58 regular, 0x59 long, 0x5A killer, 0 for any other item).
     *
     * Exercised in captures: non-ballista class SMSId branch for all valid units in Prologue clear
     *   (`f4181..4274` and `f4894`).
     * Unexercised in captures: ballista rider SMS branches (no ballistas in Prologue).
     */
    public fun getUnitSMSId(id: Int, traps: ByteArray? = null): Int {
        val offset = unitOffset(id)
        if ((state(id) and US_IN_BALLISTA) == 0) {
            val classPtr = u32(bytes, offset + OFF_CLASS)
            return rom.u8(classPtr + 6)
        }
        if (traps == null) return 0
        val trapOffset = u8(bytes, offset + OFF_BALLISTA) * TRAP_SIZE
        if (trapOffset + 3 >= traps.size) return 0
        return when (u8(traps, trapOffset + 3)) {
            BALLISTA_REGULAR -> 0x58
            BALLISTA_LONG -> 0x59
            BALLISTA_KILLER -> 0x5A
            else -> 0
        }
    }

    /**
     * Ported from src/bmunit.c: RefreshAllies
     *
     * After a won chapter, refreshes all blue units (ids 1 until 0x40):
     * - Clears `US_UNSELECTABLE`, `US_RESCUING`, and `US_RESCUED` from unit state.
     * - Clears `rescue` (partner unit id) to 0.
     * - Calls [setUnitStatus] to clear the status to 0.
     * Finally invokes [refreshEntityBmMaps] and [refreshUnitSprites].
     *
     * Exercised in captures: blue unit iteration, status 0 clear, rescue 0 reset, and matching
     *   `r04901` ground truth EWRAM bytes at chapter win (`f4894`).
     * Unexercised in captures: `US_RESCUING` and `US_RESCUED` clearing (no units rescued in
     *   Prologue).
     */
    public fun refreshAllies(
        refreshEntityBmMaps: () -> Unit = {},
        refreshUnitSprites: () -> Unit = {},
    ) {
        for (i in 1 until 0x40) {
            if (!isValid(i)) continue
            setState(i, state(i) and (US_UNSELECTABLE or US_RESCUING or US_RESCUED).inv())
            setRescue(i, 0)
            setUnitStatus(i, 0)
        }
        refreshEntityBmMaps()
        refreshUnitSprites()
    }

    /**
     * Transcribed from src/bmio.c: ChapterChangeUnitCleanup (not verified: f9443 -> f9444 in
     * `ram_trace_prologue_end` only exercises healing Seth/Eirika to maxHP and setting
     * `US_HIDDEN | US_NOT_DEPLOYED`; unexercised branches: phantom clearing, non-empty green/red
     * unit clearing, non-zero status/torch/barrier/rescue/supportBits clearing, `US_NOT_DEPLOYED`
     * -> `US_BIT21` preservation, keeping `US_DEAD | US_GROWTH_BOOST | US_SOLOANIM_* | US_BIT16 |
     * US_BIT20 | US_BIT21 | US_BIT25 | US_BIT26`, `CA_SUPPLY` dead-bit clear, and clearing
     * `PLAY_FLAG_PREPSCREEN`)
     *
     * Cleans up unit state across all factions at chapter transition:
     * 1. Clears blue phantom units (`CLASS_PHANTOM`).
     * 2. Clears all green and red units (`0x41 until 0xC0`).
     * 3. For each valid blue unit (`1 until 0x40`): restores `curHP` to [getUnitMaxHp], clears
     *    status, torch, barrier, rescue and `supportBits`, copies `US_NOT_DEPLOYED` into
     *    `US_BIT21`, keeps persistent state bits (clearing `US_DEAD` when `CA_SUPPLY`), and sets
     *    `US_HIDDEN | US_NOT_DEPLOYED`.
     * 4. Clears `PLAY_FLAG_PREPSCREEN` in [playSt].
     */
    public fun chapterChangeUnitCleanup(
        playSt: PlaySt,
        getUnitMaxHp: (id: Int) -> Int,
    ) {
        // Clear phantoms
        for (i in 1 until 0x40) {
            if (isValid(i) && classId(i) == CLASS_PHANTOM) {
                clearUnit(i)
            }
        }

        // Clear all non player units (green & red units)
        for (i in 0x41 until 0xC0) {
            if (isValid(i)) {
                clearUnit(i)
            }
        }

        // Reset player unit "temporary" states (HP, status, some state flags, etc)
        for (j in 1 until 0x40) {
            if (!isValid(j)) continue
            val offset = unitOffset(j)
            bytes[offset + OFF_CUR_HP] = getUnitMaxHp(j).toByte()
            setUnitStatus(j, 0)
            bytes[offset + OFF_TORCH_BARRIER] = 0

            var s = state(j)
            s = if ((s and US_NOT_DEPLOYED) != 0) s or US_BIT21 else s and US_BIT21.inv()
            s = s and CHAPTER_CLEANUP_KEEP_STATE_MASK
            if ((cAttributes(j) and CA_SUPPLY) != 0) {
                s = s and US_DEAD.inv()
            }
            s = s or US_HIDDEN or US_NOT_DEPLOYED
            setState(j, s)

            setRescue(j, 0)
            bytes[offset + OFF_SUPPORT_BITS] = 0
        }

        playSt.chapterStateBits = playSt.chapterStateBits and
            BattleMapStart.PLAY_FLAG_PREPSCREEN.inv()
    }

    /** Layout and flag constants. */
    public companion object {
        /** `sizeof(struct Unit)`. */
        public const val UNIT_SIZE: Int = 0x48

        /** Base address of EWRAM. */
        public const val EWRAM_BASE: Int = 0x02000000

        /** `gUnitArrayBlue`, the start of the contiguous unit arrays. */
        public const val BLUE_ADDRESS: Int = 0x0202BE4C

        /** Units in `gUnitArrayBlue`. */
        public const val BLUE_COUNT: Int = 62

        /** Units in `gUnitArrayRed`. */
        public const val RED_COUNT: Int = 50

        /** Units in `gUnitArrayGreen`. */
        public const val GREEN_COUNT: Int = 20

        /** Units in `gUnitArrayPurple`. */
        public const val PURPLE_COUNT: Int = 5

        /** Total bytes of the four arrays (0x2688). */
        public const val TOTAL_SIZE: Int =
            (BLUE_COUNT + RED_COUNT + GREEN_COUNT + PURPLE_COUNT) * UNIT_SIZE

        /** `pCharacterData` in `struct Unit`. */
        private const val OFF_CHARACTER = 0x00

        /** `pClassData` in `struct Unit`. */
        public const val OFF_CLASS: Int = 0x04

        /** `index` (s8, 0x0B) in `struct Unit`. */
        public const val OFF_INDEX: Int = 0x0B

        /** `rescue` (u8, 0x1B) in `struct Unit`. */
        public const val OFF_RESCUE: Int = 0x1B

        /** `ballistaIndex` (u8, 0x1C) in `struct Unit`. */
        public const val OFF_BALLISTA: Int = 0x1C

        /** `items[UNIT_ITEM_COUNT]` (u16[5], 0x1E) in `struct Unit`. */
        public const val OFF_ITEMS: Int = 0x1E

        /** `ranks[8]` in `struct Unit`. */
        private const val OFF_RANKS = 0x28

        /** `attributes` (u32) in both `struct CharacterData` and `struct ClassData`. */
        private const val OFF_ATTRIBUTES = 0x28

        /** `statusIndex` and `statusDuration` (u8, 0x30) in `struct Unit`. */
        public const val OFF_STATUS: Int = 0x30

        /** `curHP` (s8, 0x13) in `struct Unit`. */
        private const val OFF_CUR_HP = 0x13

        /** `torchDuration` and `barrierDuration` (u8, 0x31) in `struct Unit`. */
        private const val OFF_TORCH_BARRIER = 0x31

        /** `supports[6]` (u8, 0x38, `UNIT_LEADER_CHARACTER`) in `struct Unit`. */
        public const val OFF_LEADER: Int = 0x38

        /** `supportBits` (s8, 0x39) in `struct Unit`. */
        private const val OFF_SUPPORT_BITS = 0x39

        /** `UNIT_ITEM_COUNT`: 5 item slots per unit. */
        public const val UNIT_ITEM_COUNT: Int = 5

        /** `ITYPE_STAFF`, `ITYPE_ANIMA`, `ITYPE_LIGHT`, `ITYPE_DARK` (bmitem.h). */
        private const val ITYPE_STAFF = 4
        private const val ITYPE_ANIMA = 5
        private const val ITYPE_LIGHT = 6
        private const val ITYPE_DARK = 7

        /** `CLASS_PHANTOM`. */
        private const val CLASS_PHANTOM = 0x51

        /** `US_HIDDEN`. */
        private const val US_HIDDEN = 1 shl 0

        /** `US_UNSELECTABLE`. */
        public const val US_UNSELECTABLE: Int = 1 shl 1

        /** `US_DEAD`. */
        private const val US_DEAD = 1 shl 2

        /** `US_NOT_DEPLOYED`. */
        private const val US_NOT_DEPLOYED = 1 shl 3

        /** `US_RESCUING`. */
        public const val US_RESCUING: Int = 1 shl 4

        /** `US_RESCUED`. */
        public const val US_RESCUED: Int = 1 shl 5

        /** `US_UNDER_A_ROOF`. */
        private const val US_UNDER_A_ROOF = 1 shl 7

        /** `US_BIT8`; `SetAllUnitNotBackSprite` clears it. */
        private const val US_BIT8 = 1 shl 8

        /** `US_IN_BALLISTA`. */
        public const val US_IN_BALLISTA: Int = 1 shl 11

        /** `US_GROWTH_BOOST`. */
        private const val US_GROWTH_BOOST = 1 shl 13

        /** `US_SOLOANIM_1`. */
        private const val US_SOLOANIM_1 = 1 shl 14

        /** `US_SOLOANIM_2`. */
        private const val US_SOLOANIM_2 = 1 shl 15

        /** `US_BIT16`, an unnamed state bit that `US_UNAVAILABLE` includes. */
        private const val US_BIT16 = 1 shl 16

        /** `US_BIT20`. */
        private const val US_BIT20 = 1 shl 20

        /** `US_BIT21`. */
        private const val US_BIT21 = 1 shl 21

        /** `US_BIT25`. */
        private const val US_BIT25 = 1 shl 25

        /** `US_BIT26`. */
        private const val US_BIT26 = 1 shl 26

        /** State bits preserved across chapter transitions by [chapterChangeUnitCleanup]. */
        private const val CHAPTER_CLEANUP_KEEP_STATE_MASK =
            US_DEAD or US_GROWTH_BOOST or US_SOLOANIM_1 or US_SOLOANIM_2 or
                US_BIT16 or US_BIT20 or US_BIT21 or US_BIT25 or US_BIT26

        /** `CA_SUPPLY` (bmunit.h, bit 9). */
        private const val CA_SUPPLY = 1 shl 9

        /** `US_UNAVAILABLE`. */
        public const val US_UNAVAILABLE: Int = US_DEAD or US_NOT_DEPLOYED or US_BIT16

        /** The state mask `GetPhaseAbleUnitCount` tests (a local in the C). */
        private const val NOT_ABLE =
            US_UNSELECTABLE or US_DEAD or US_NOT_DEPLOYED or US_RESCUED or US_UNDER_A_ROOF or
                US_BIT16

        /** `UNIT_STATUS_SLEEP`. */
        public const val STATUS_SLEEP: Int = 2

        /** `UNIT_STATUS_BERSERK`. */
        public const val STATUS_BERSERK: Int = 4

        /** `CA_THIEF` (bmunit.h, bit 3). */
        public const val CA_THIEF: Int = 1 shl 3

        /** `CA_UNSELECTABLE`. */
        public const val CA_UNSELECTABLE: Int = 1 shl 20

        /** `ITEM_CHESTKEY` (0x69). */
        public const val ITEM_CHESTKEY: Int = 0x69

        /** `ITEM_DOORKEY` (0x6A). */
        public const val ITEM_DOORKEY: Int = 0x6A

        /** `ITEM_LOCKPICK` (0x6B). */
        public const val ITEM_LOCKPICK: Int = 0x6B

        /** `ITEM_CHESTKEY_BUNDLE` (0x79). */
        public const val ITEM_CHESTKEY_BUNDLE: Int = 0x79

        /** `TERRAIN_DOOR` (0x1E). */
        public const val TERRAIN_DOOR: Int = 0x1E

        /** `TERRAIN_CHEST_FULL` (0x21). */
        public const val TERRAIN_CHEST_FULL: Int = 0x21

        /** `ITEM_BALLISTA_REGULAR` (0x35). */
        public const val BALLISTA_REGULAR: Int = 0x35

        /** `ITEM_BALLISTA_LONG` (0x36). */
        public const val BALLISTA_LONG: Int = 0x36

        /** `ITEM_BALLISTA_KILLER` (0x37). */
        public const val BALLISTA_KILLER: Int = 0x37

        /** `sizeof(struct Trap)`: 8 bytes. */
        public const val TRAP_SIZE: Int = 8

        /** `CHARACTER_EIRIKA`. */
        private const val CHARACTER_EIRIKA = 0x01

        /** `CHARACTER_EPHRAIM`. */
        private const val CHARACTER_EPHRAIM = 0x0F

        /** `CHAPTER_L_5X`. */
        private const val CHAPTER_L_5X = 0x05

        /** `CHAPTER_MODE_EPHRAIM`. */
        private const val CHAPTER_MODE_EPHRAIM = 3

        /**
         * Zeroes all 0x48 bytes of the unit at byte [offset] in [b], preserving its `index` byte
         * (`OFF_INDEX`, offset 0x0B). Matches `ClearUnit` (`src/bmunit.c`).
         */
        internal fun clearUnit(b: ByteArray, offset: Int) {
            val index = b[offset + OFF_INDEX]
            b.fill(0, offset, offset + UNIT_SIZE)
            b[offset + OFF_INDEX] = index
        }

        /**
         * Transcribed from src/bmitem.c: GetUnitItemSlot (not verified: captured calls have one
         * return value; Prologue captures have no matching key/item slot; EP-29a tracks positive
         * matches under EP-29cap)
         *
         * Returns the first inventory slot (0 until [UNIT_ITEM_COUNT]) containing [itemIndex],
         * or -1 if absent.
         */
        public fun getUnitItemSlot(b: ByteArray, offset: Int, itemIndex: Int): Int {
            for (i in 0 until UNIT_ITEM_COUNT) {
                val item = StructCodec.u16(b, offset + OFF_ITEMS + 2 * i)
                if ((item and 0xFF) == itemIndex) return i
            }
            return -1
        }

        /**
         * Ported from src/bmunit.c: SetUnitStatus
         *
         * Sets status [status] in byte 0x30 (`statusIndex` in low nibble, `statusDuration` in
         * high nibble) of unit at [offset] in [b]. Duration is 5 turns if status != 0, or 0
         * if status == 0.
         *
         * Exercised in captures: status == 0 clearing in [refreshAllies] at chapter win (`f4894`).
         * Unexercised in captures: status != 0 application (no non-zero status is applied in
         *   Prologue captures).
         */
        public fun setUnitStatus(b: ByteArray, offset: Int, status: Int) {
            b[offset + OFF_STATUS] =
                if (status == 0) 0 else ((status and 0xF) or (5 shl 4)).toByte()
        }

        /**
         * Transcribed from src/bmunit.c: SetUnitStatusExt (not verified: no Prologue capture
         * applies non-zero duration status via SetUnitStatusExt)
         *
         * Sets status [status] and turn duration [duration] in byte 0x30 of unit at [offset]
         * in [b].
         */
        public fun setUnitStatusExt(b: ByteArray, offset: Int, status: Int, duration: Int) {
            b[offset + OFF_STATUS] =
                ((status and 0xF) or ((duration and 0xF) shl 4)).toByte()
        }
    }
}
