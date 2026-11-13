/**
 * The chapter save slots (`struct GameSaveBlock`, 0xDC8 bytes each, the three "Save 1/2/3" files)
 * of `src/bmsave.c`: `ReadGameSave`, `WriteGameSave` and the packed unit record they use.
 *
 * A game save keeps `gPlaySt`, the 51 blue (player) units as 0x24-byte bit-packed
 * `struct GameSavePackedUnit` records, the convoy (supply) items, the permanent event flags, the
 * per-unit battle records (`gPidStatsData`) and per-chapter records (`gChapterStats`), the bonus
 * claim flags, the world map state and the tower / ruins dungeon records. The mid-chapter suspend
 * save is in [BmSuspendSave].
 */

package fe8.game.save

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.StructCodec
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib.Companion.DUNGEONS_SIZE
import fe8.game.save.BmSaveLib.Companion.GAME_BONUS_CLAIM
import fe8.game.save.BmSaveLib.Companion.GAME_CHAPTER_STATS
import fe8.game.save.BmSaveLib.Companion.GAME_DUNGEONS
import fe8.game.save.BmSaveLib.Companion.GAME_GM_UNIT
import fe8.game.save.BmSaveLib.Companion.GAME_PERMANENT_FLAGS
import fe8.game.save.BmSaveLib.Companion.GAME_PID_STATS
import fe8.game.save.BmSaveLib.Companion.GAME_SUPPLY
import fe8.game.save.BmSaveLib.Companion.GAME_UNITS
import fe8.game.save.BmSaveLib.Companion.GAME_WM_STUFF
import fe8.game.save.BmSaveLib.Companion.PACKED_UNIT_SIZE
import fe8.game.save.BmSaveLib.Companion.UNIT_SAVE_AMOUNT_BLUE
import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.readSramFast
import gba.core.sram.writeAndVerifySramFast
import gbafe.core.flags.EventFlags
import gbafe.core.time.GameClock

/**
 * The RAM `ReadGameSave` fills and `WriteGameSave` reads, as the objects that own it elsewhere,
 * plus stand-ins for what has no owner yet (the world map and dungeon data, M6-I).
 *
 * @property playSt `gPlaySt`, the chapter and settings state stored at the start of the block.
 * @property bmSt `gBmSt`; only `gameStateBits` is read, to decide whether the suspend save is
 *   invalidated (see [BmGameSave.readGameSave]).
 * @property units the unit arrays (`gUnitArrayBlue/Red/Green`); only the 51 blue (player) units
 *   are saved.
 * @property flags the event flag memory (`gPermanentFlagBits`, the flags kept across chapters).
 * @property clock the game's one [GameClock] (`gGameClock`, restored from `gPlaySt.time_saved`).
 * @property convoy `gConvoyItemArray` (the supply convoy) as 100 raw little-endian u16 item words.
 */
public class GameSaveRam(
    public val playSt: PlaySt,
    public val bmSt: BmSt,
    public val units: UnitTable,
    public val flags: EventFlags,
    public val clock: GameClock,
    public val convoy: ByteArray = ByteArray(SuspendRam.CONVOY_BYTES),
) {
    /**
     * Stand-in until M6-I for the random-number state of the world map's wandering monsters in
     * `gGMData` (`unk_ce`, three halfwords), the target of `sub_80A71F8`.
     */
    public val gmMonsterRnState: IntArray = IntArray(3)

    /**
     * Stand-in until M6-I for what `ReadWorldMapStuff(&src->wmStuff, &gGMData)` decodes (the
     * world map state is not ported): the raw 0x24 bytes of `struct GMapSaveInfo` (world map
     * nodes, paths, units, cursor position and skirmish state).
     */
    public val worldMapStuff: ByteArray = ByteArray(Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE)

    /**
     * Stand-in until M6-I for `gDungeonState.dungeon` (`LoadDungeonRecords`): the raw
     * `struct Dungeon[2]`, the saved records of the two dungeons (tower and ruins).
     */
    public val dungeons: ByteArray = ByteArray(DUNGEONS_SIZE)
}

/**
 * The chapter save slot (`struct GameSaveBlock`) reader and writer of src/bmsave.c, on top of a
 * [BmSaveLib].
 *
 * @property lib the save RAM routines (and the owner of `gPidStatsData` / `gChapterStats`).
 * @property loader the unit routines (`GetUnitMaxHp`) over the same unit arrays as the RAM read.
 * @property rom the ROM that unit `pCharacterData` / `pClassData` pointers point into.
 */
public class BmGameSave(
    private val lib: BmSaveLib,
    private val loader: UnitLoader,
    private val rom: RomView,
) {
    /** Scratch for the 0x24-byte `struct GameSavePackedUnit unitp` local of the C code. */
    private val packedUnitBuf = ByteArray(PACKED_UNIT_SIZE)

    /**
     * Scratch for the `u32 buf[2]` of `Read/WriteGMMonsterRnState` and the `u32 buf` of
     * `ReadBonusContentClaimFlags`.
     */
    private val wordBuf = ByteArray(8)

    /** The raw `gPlaySt` bytes of the block (`struct PlaySt`, read and written). */
    private val playStBytes = ByteArray(PlaySt.SIZE)

    /** The 0x19 permanent flag bytes read from the block (`permanentFlags`). */
    private val permanentFlagsIn = ByteArray(Fe8EventFlags.PERMANENT_SAVE_BYTES)

    /** Scratch for the `struct Dungeon dungeon[2]` local of `ReadGameSave`/`WriteGameSave`. */
    private val dungeonRecordsBuf = ByteArray(DUNGEONS_SIZE)

    /**
     * Scratch for the `struct SaveBlockInfo chunk` local that `WriteGameSave` fills with
     * `SAVEMAGIC32` and `SAVEBLOCK_KIND_GAME`.
     */
    private val blockInfoBuf = ByteArray(L.BLOCK_INFO_SIZE)

    /** Scratch for the `struct GlobalSaveInfo info` local of `WriteGameSave`. */
    private val globalInfoBuf = ByteArray(L.HEADER_SIZE)

    /** The permanent flag bytes handed to `WritePermanentFlags`. */
    private val permanentFlagsOut = ByteArray(Fe8EventFlags.PERMANENT_SAVE_BYTES)

    /** The 100 convoy item words as ints, the form `WriteSupplyItems` packs into 0xB0 bytes. */
    private val supplyWords = IntArray(SuspendRam.CONVOY_BYTES / 2)

    /** The all-zero unit stored for an empty unit slot (`ClearUnit(&tmp_unit)`). */
    private val clearUnit = ByteArray(UnitTable.UNIT_SIZE)

    /**
     * Reads one bit-field of a `struct GameSavePackedUnit`: [bitCount] bits starting at bit
     * [start] of [stream]. The C compiler lays the `u32 x : n` fields out LSB first, little endian
     * (the first field `jid` is bits 0..6 of byte 0), so this reproduces that layout.
     *
     * @param bitCount at most 16 here (the widest packed fields are the 14-bit items).
     */
    private fun readBits(stream: ByteArray, start: Int, bitCount: Int): Int {
        var value = 0
        for (i in 0 until bitCount) {
            val bit = start + i
            value = value or (((stream[bit shr 3].toInt() shr (bit and 7)) and 1) shl i)
        }
        return value
    }

    /**
     * Ported from src/bmsave.c: LoadSavedUnit
     *
     * Reads the 0x24-byte `struct GameSavePackedUnit` (a packed bit stream, LSB first) at SRAM
     * [srcOffset] and unpacks it into the unit at byte offset [unitOffset] of [units]. The record
     * keeps only what a chapter start needs: character, class, level, exp, position, stats,
     * items, weapon ranks, supports and a few state bits. The rest of the unit is not touched
     * (`InitUnits` has just cleared it), except that `curHP` is set to the maximum HP including
     * item bonuses (`GetUnitMaxHp`), so every unit starts the chapter at full health, and
     * `supportBits` is cleared.
     */
    public fun loadSavedUnit(srcOffset: Int, units: UnitTable, unitOffset: Int) {
        val packed = packedUnitBuf
        readSramFast(lib.sram, srcOffset, packed, 0, PACKED_UNIT_SIZE)
        val unitRam = units.bytes
        val base = unitOffset
        val jid = readBits(packed, 0, 7)
        val level = readBits(packed, 7, 5)
        val exp = readBits(packed, 12, 7)
        val xPos = readBits(packed, 19, 6)
        val yPos = readBits(packed, 25, 6)
        val flag = readBits(packed, 31, 13)
        val pid = StructCodec.u8(packed, 0x14)

        StructCodec.put32(unitRam, base + U_CHARACTER, UnitLoader.characterDataAt(pid))
        StructCodec.put32(unitRam, base + U_CLASS, UnitLoader.classDataAt(jid))
        unitRam[base + U_LEVEL] = level.toByte()
        unitRam[base + U_EXP] = exp.toByte()
        unitRam[base + U_X_POS] = xPos.toByte()
        unitRam[base + U_Y_POS] = yPos.toByte()

        unitRam[base + U_MAX_HP] = readBits(packed, 44, 6).toByte()
        unitRam[base + U_POW] = readBits(packed, 50, 5).toByte()
        unitRam[base + U_SKL] = readBits(packed, 55, 5).toByte()
        unitRam[base + U_SPD] = readBits(packed, 60, 5).toByte()
        unitRam[base + U_DEF] = readBits(packed, 65, 5).toByte()
        unitRam[base + U_RES] = readBits(packed, 70, 5).toByte()
        unitRam[base + U_LCK] = readBits(packed, 75, 5).toByte()
        unitRam[base + U_CON_BONUS] = readBits(packed, 80, 5).toByte()
        unitRam[base + U_MOV_BONUS] = readBits(packed, 85, 5).toByte()

        for (i in 0 until 5) {
            StructCodec.put16(unitRam, base + U_ITEMS + i * 2, readBits(packed, 90 + i * 14, 14))
        }

        // Exp above 99 cannot be a real value; the unit gets -1 (0xFF), as in the C code.
        if (StructCodec.u8(unitRam, base + U_EXP) > 99) unitRam[base + U_EXP] = -1

        // Rebuild the 32-bit `Unit.state` from the 8 packed state bits. `US_BIT16`, `US_BIT21`
        // and `US_BIT25` have no known name in the decomp: bit 16 marks a unit as unavailable
        // (part of `US_UNAVAILABLE`), bit 21 remembers that the unit was not deployed, and the
        // meaning of bit 25 is unknown. `PACKED_US_NEW_FRIEND` is the decomp's name for the
        // packed bit that maps to bit 21.
        var state = 0
        if ((PACKED_US_DEAD and flag) != 0) state = US_HIDDEN or US_DEAD
        if ((PACKED_US_UNDEPLOYED and flag) != 0) state = state or US_HIDDEN or US_NOT_DEPLOYED
        if ((PACKED_US_SOLO_ANIM1 and flag) != 0) state = state or US_SOLOANIM_1
        if ((PACKED_US_SOLO_ANIM2 and flag) != 0) state = state or US_SOLOANIM_2
        if ((PACKED_US_METIS_TOME and flag) != 0) state = state or US_GROWTH_BOOST
        if ((PACKED_US_B4 and flag) != 0) state = state or US_BIT16
        if ((PACKED_US_B5 and flag) != 0) state = state or US_BIT25
        if ((PACKED_US_NEW_FRIEND and flag) != 0) state = state or US_BIT21
        StructCodec.put32(unitRam, base + U_STATE, state)

        for (i in 0 until 8) unitRam[base + U_RANKS + i] = packed[0x15 + i]
        for (i in 0 until UNIT_SUPPORT_MAX_COUNT) unitRam[base + U_SUPPORTS + i] = packed[0x1D + i]

        // SetUnitHp(unit, GetUnitMaxHp(unit)): its clamp to the maximum is a no-op here.
        unitRam[base + U_CUR_HP] = loader.getUnitMaxHp(base).toByte()
        unitRam[base + U_SUPPORT_BITS] = 0

        // "mins zero" in the decomp: the value -1 is stored with all bits of its field set (0x7F
        // for exp, 0x3F for the coordinates) and is turned back into -1 here. The decomp does not
        // say what -1 means for these fields.
        if (StructCodec.u8(unitRam, base + U_EXP) == 0x7F) unitRam[base + U_EXP] = -1
        if (StructCodec.u8(unitRam, base + U_X_POS) == 0x3F) unitRam[base + U_X_POS] = -1
        if (StructCodec.u8(unitRam, base + U_Y_POS) == 0x3F) unitRam[base + U_Y_POS] = -1
    }

    /**
     * Transcribed from src/bmsave.c: ReadBonusContentClaimFlags (not verified: the flags are zero
     * in the reference save, which proves the target, not the offset)
     *
     * Reads `bonusClaimFlags` (which downloadable bonus rewards the player has already claimed)
     * of the game block at SRAM [blockOffset] into `gBonusContentClaimFlags`.
     */
    public fun readBonusContentClaimFlags(blockOffset: Int) {
        readSramFast(lib.sram, blockOffset + GAME_BONUS_CLAIM, wordBuf, 0, 4)
        lib.setBonusContentClaimFlags(StructCodec.u32(wordBuf, 0))
    }

    /**
     * Transcribed from src/bmsave.c: ReadGMMonsterRnState (not verified: no trace compares the
     * world map's RN state)
     *
     * Reads the 8 bytes (`u32 buf[2]`) at SRAM [srcOffset], the block's `gmUnit` slot, and
     * copies its first three halfwords to `gmMonsterRnState` (the world map monsters' RN state)
     * as `sub_80A71F8` does. The block slot is a whole `struct GameSavePackedUnit` of which only
     * these 8 bytes are used.
     */
    public fun readGmMonsterRnState(srcOffset: Int, ram: GameSaveRam) {
        readSramFast(lib.sram, srcOffset, wordBuf, 0, 8)
        for (i in 0 until 3) ram.gmMonsterRnState[i] = StructCodec.u16(wordBuf, i * 2)
    }

    /**
     * Transcribed from src/bmsave-gmap.c: ReadWorldMapStuff (not verified: partial, the world
     * map state is not ported, so the raw `struct GMapSaveInfo` is kept)
     *
     * Reads the 0x24 bytes at SRAM [srcOffset] into [ram]'s `worldMapStuff`.
     */
    public fun readWorldMapStuff(srcOffset: Int, ram: GameSaveRam) {
        readSramFast(
            lib.sram, srcOffset, ram.worldMapStuff, 0, Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE,
        )
    }

    /**
     * Transcribed from src/bmsave.c: LoadSavedWMStuff (not verified: see [readWorldMapStuff])
     *
     * Loads the world map state (`wmStuff`) of the game block of [slot] into [dest]. The save
     * menu (`SaveMenuInitSaveSlotData`) uses it to find the world map node, and so the chapter
     * title, of an unfinished slot whose `save_menu_type` is 4.
     */
    public fun loadSavedWmStuff(slot: Int, dest: GameSaveRam) {
        readWorldMapStuff(lib.getSaveReadAddr(slot) + GAME_WM_STUFF, dest)
    }

    /**
     * Ported from src/bmsave.c: ReadGameSave
     *
     * Loads the game save block of [slot] into [ram]: `gPlaySt` (with `gameSaveSlot` = [slot])
     * and the game clock, the 51 blue units (after `InitUnits`), the monster RN state, the
     * convoy, the permanent flags, pid / chapter stats, the bonus claim flags, the world map
     * stuff and dungeon records (stand-ins of [GameSaveRam], M6-I); then the last game save id.
     *
     * The C code tests `PLAY_FLAG_HARD` (a `gPlaySt.chapterStateBits` flag, 1 shl 6) against
     * `gBmSt.gameStateBits`, where the same bit is `BM_FLAG_LINKARENA`. It is kept as written: when
     * that bit is clear, which is every time outside the Link Arena, the suspend save is
     * invalidated.
     */
    public fun readGameSave(ram: GameSaveRam, slot: Int) {
        val src = lib.getSaveReadAddr(slot)

        if ((PLAY_FLAG_HARD and ram.bmSt.gameStateBits) == 0) {
            lib.invalidateSuspendSave(L.SAVE_ID_SUSPEND)
        }

        readSramFast(lib.sram, src, playStBytes, 0, PlaySt.SIZE)
        ram.playSt.decode(playStBytes, 0)
        ram.clock.set(ram.playSt.timeSaved.toLong() and 0xFFFFFFFFL)
        ram.playSt.gameSaveSlot = slot

        ram.units.initUnits()

        for (i in 0 until UNIT_SAVE_AMOUNT_BLUE) {
            loadSavedUnit(
                src + GAME_UNITS + i * PACKED_UNIT_SIZE, ram.units, i * UnitTable.UNIT_SIZE,
            )
        }

        readGmMonsterRnState(src + GAME_GM_UNIT, ram)
        lib.readSupplyItems(src + GAME_SUPPLY, ram.convoy)
        lib.readPermanentFlags(src + GAME_PERMANENT_FLAGS, permanentFlagsIn)
        ram.flags.copyInPermanent(permanentFlagsIn)
        lib.readPidStats(src + GAME_PID_STATS)
        lib.readChapterStats(src + GAME_CHAPTER_STATS)
        readBonusContentClaimFlags(src)
        readWorldMapStuff(src + GAME_WM_STUFF, ram)
        readSramFast(lib.sram, src + GAME_DUNGEONS, dungeonRecordsBuf, 0, DUNGEONS_SIZE)
        // LoadDungeonRecords: memcpy into gDungeonState.dungeon.
        dungeonRecordsBuf.copyInto(ram.dungeons)
        lib.writeLastGameSaveId(slot)
    }

    /**
     * The inverse of [readBits]: stores the lowest [bitCount] bits of [value] (extra high bits,
     * such as those of a negative -1, are dropped, as the C bit-field assignment does) at bit
     * [start] of [stream].
     */
    private fun writeBits(stream: ByteArray, start: Int, bitCount: Int, value: Int) {
        for (i in 0 until bitCount) {
            val bit = start + i
            val mask = 1 shl (bit and 7)
            val current = stream[bit shr 3].toInt()
            val set = ((value shr i) and 1) != 0
            stream[bit shr 3] = (if (set) current or mask else current and mask.inv()).toByte()
        }
    }

    /**
     * Ported from src/bmsave.c: WriteGameSavePackedUnit
     *
     * Packs the unit at byte offset [unitOffset] of [units] into a 0x24-byte
     * `struct GameSavePackedUnit` and writes it to SRAM [destOffset]. A unit without a character
     * (an empty slot) is written as a cleared unit with `pid` and `jid` 0. The character and
     * class are stored as their ids (`number` of the ROM character / class record, at +4), not as
     * ROM pointers. Values are truncated to their field width, so exp -1 becomes 0x7F and an
     * off-map coordinate -1 becomes 0x3F (see [loadSavedUnit]).
     */
    public fun writeGameSavePackedUnit(units: UnitTable, unitOffset: Int, destOffset: Int) {
        val packed = packedUnitBuf
        var unitRam = units.bytes
        var base = unitOffset
        val character = StructCodec.u32(unitRam, base + U_CHARACTER)
        val pid: Int
        val jid: Int
        if (character == 0) {
            clearUnit.fill(0)
            unitRam = clearUnit
            base = 0
            pid = 0
            jid = 0
        } else {
            pid = rom.u8(character + 4)
            jid = rom.u8(StructCodec.u32(unitRam, base + U_CLASS) + 4)
        }
        packed.fill(0)
        writeBits(packed, 0, 7, jid)
        writeBits(packed, 7, 5, unitRam[base + U_LEVEL].toInt())
        writeBits(packed, 12, 7, unitRam[base + U_EXP].toInt())
        writeBits(packed, 19, 6, unitRam[base + U_X_POS].toInt())
        writeBits(packed, 25, 6, unitRam[base + U_Y_POS].toInt())
        // Squeeze the 32-bit `Unit.state` into the 8 `PACKED_US_*` bits; all other state is lost.
        val state = StructCodec.u32(unitRam, base + U_STATE)
        var flag = 0
        if ((US_DEAD and state) != 0) flag = flag or PACKED_US_DEAD
        if ((US_NOT_DEPLOYED and state) != 0) flag = flag or PACKED_US_UNDEPLOYED
        if ((US_SOLOANIM_1 and state) != 0) flag = flag or PACKED_US_SOLO_ANIM1
        if ((US_SOLOANIM_2 and state) != 0) flag = flag or PACKED_US_SOLO_ANIM2
        if ((US_GROWTH_BOOST and state) != 0) flag = flag or PACKED_US_METIS_TOME
        if ((US_BIT16 and state) != 0) flag = flag or PACKED_US_B4
        if ((US_BIT25 and state) != 0) flag = flag or PACKED_US_B5
        if ((US_BIT21 and state) != 0) flag = flag or PACKED_US_NEW_FRIEND
        writeBits(packed, 31, 13, flag)
        writeBits(packed, 44, 6, unitRam[base + U_MAX_HP].toInt())
        writeBits(packed, 50, 5, unitRam[base + U_POW].toInt())
        writeBits(packed, 55, 5, unitRam[base + U_SKL].toInt())
        writeBits(packed, 60, 5, unitRam[base + U_SPD].toInt())
        writeBits(packed, 65, 5, unitRam[base + U_DEF].toInt())
        writeBits(packed, 70, 5, unitRam[base + U_RES].toInt())
        writeBits(packed, 75, 5, unitRam[base + U_LCK].toInt())
        writeBits(packed, 80, 5, unitRam[base + U_CON_BONUS].toInt())
        writeBits(packed, 85, 5, unitRam[base + U_MOV_BONUS].toInt())
        for (i in 0 until 5) {
            writeBits(packed, 90 + i * 14, 14, StructCodec.u16(unitRam, base + U_ITEMS + i * 2))
        }
        packed[0x14] = pid.toByte()
        for (i in 0 until 8) packed[0x15 + i] = unitRam[base + U_RANKS + i]
        for (i in 0 until UNIT_SUPPORT_MAX_COUNT) packed[0x1D + i] = unitRam[base + U_SUPPORTS + i]
        writeAndVerifySramFast(packed, 0, lib.sram, destOffset, PACKED_UNIT_SIZE)
    }

    /**
     * Transcribed from src/bmsave.c: WriteGMMonsterRnState (not verified: the world map's RN
     * state is a stand-in and the two trailing stack bytes are not derivable)
     *
     * Writes the 8 bytes (`u32 buf[2]`) that `sub_80A71E4` fills with the world map monsters'
     * RN state (the three halfwords of `gmMonsterRnState`, a stand-in until M6-I; the other two
     * bytes are uninitialised stack in the C code, zero here) to SRAM [destOffset], the block's
     * `gmUnit` slot.
     */
    public fun writeGmMonsterRnState(destOffset: Int, ram: GameSaveRam) {
        wordBuf.fill(0)
        for (i in 0 until 3) StructCodec.put16(wordBuf, i * 2, ram.gmMonsterRnState[i])
        writeAndVerifySramFast(wordBuf, 0, lib.sram, destOffset, 8)
    }

    /**
     * Ported from src/bmsave.c: WriteGameSave
     *
     * Writes the game save of [slot] from [ram] to the block `GetSaveWriteAddr(slot)` and
     * publishes it: the suspend save is invalidated (a chapter save replaces it), `gameSaveSlot`
     * and `time_saved` (the play time) are set, the characters of the blue units are marked known
     * in `charKnownFlags` of the global info (the "characters seen" record that outlives
     * saves), and the block info (the descriptor that makes the block valid) and last game save
     * id are written. The world map stuff and dungeon records are written from the stand-ins of
     * [GameSaveRam] (M6-I).
     */
    public fun writeGameSave(ram: GameSaveRam, slot: Int) {
        val dest = lib.getSaveWriteAddr(slot)
        lib.invalidateSuspendSave(L.SAVE_ID_SUSPEND)
        ram.playSt.gameSaveSlot = slot
        ram.playSt.timeSaved = ram.clock.get().toInt()
        ram.playSt.encode(playStBytes, 0)
        writeAndVerifySramFast(playStBytes, 0, lib.sram, dest, PlaySt.SIZE)

        for (i in 0 until UNIT_SAVE_AMOUNT_BLUE) {
            writeGameSavePackedUnit(
                ram.units, i * UnitTable.UNIT_SIZE, dest + GAME_UNITS + i * PACKED_UNIT_SIZE,
            )
        }

        lib.readGlobalSaveInfo(globalInfoBuf)
        for (i in 0 until UNIT_SAVE_AMOUNT_BLUE) {
            // UNIT_CHAR_ID, then SGM_SetCharacterKnown(charId, &info): set bit charId of
            // charKnownFlags. Ids above 256 are ignored; an empty slot counts as id 0.
            val character = StructCodec.u32(ram.units.bytes, i * UnitTable.UNIT_SIZE + U_CHARACTER)
            val charId = if (character == 0) 0 else rom.u8(character + 4)
            if (charId <= 256) {
                val at = L.HEADER_CHAR_KNOWN_FLAGS + (charId shr 3)
                globalInfoBuf[at] = (globalInfoBuf[at].toInt() or (1 shl (charId and 7))).toByte()
            }
        }
        lib.writeGlobalSaveInfo(globalInfoBuf)
        writeGmMonsterRnState(dest + GAME_GM_UNIT, ram)
        for (i in supplyWords.indices) supplyWords[i] = StructCodec.u16(ram.convoy, i * 2)
        lib.writeSupplyItems(dest + GAME_SUPPLY, supplyWords)
        lib.writePidStats(dest + GAME_PID_STATS)
        lib.writeChapterStats(dest + GAME_CHAPTER_STATS)
        lib.writeBonusContentClaimFlags(dest)
        Fe8EventFlags.getPermanentFlagBits(ram.flags)
            .copyInto(permanentFlagsOut, 0, 0, permanentFlagsOut.size)
        lib.writePermanentFlags(dest + GAME_PERMANENT_FLAGS, permanentFlagsOut)
        // WriteWorldMapStuff: not ported (M6-I), the raw stand-in is written.
        writeAndVerifySramFast(
            ram.worldMapStuff, 0, lib.sram, dest + GAME_WM_STUFF,
            Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE,
        )
        // SaveDungeonRecords: memcpy from gDungeonState.dungeon.
        ram.dungeons.copyInto(dungeonRecordsBuf)
        writeAndVerifySramFast(dungeonRecordsBuf, 0, lib.sram, dest + GAME_DUNGEONS, DUNGEONS_SIZE)

        blockInfoBuf.fill(0)
        StructCodec.put32(blockInfoBuf, L.INFO_MAGIC32, L.SAVEMAGIC32)
        blockInfoBuf[L.INFO_KIND] = L.SAVEBLOCK_KIND_GAME.toByte()
        lib.writeSaveBlockInfo(blockInfoBuf, slot)
        lib.writeLastGameSaveId(slot)
    }

    /**
     * Bits of `struct GameSavePackedUnit.flag` and of `Unit.state`, and the byte offsets of the
     * fields of `struct Unit` that the packing reads and writes.
     */
    private companion object {
        /**
         * `PLAY_FLAG_HARD` (hard mode, a `gPlaySt.chapterStateBits` flag). `ReadGameSave` tests it
         * against `gBmSt.gameStateBits`, where bit 6 is `BM_FLAG_LINKARENA`.
         */
        const val PLAY_FLAG_HARD = 1 shl 6

        // `enum packed_unit_state_bits`: the 8 state bits kept in the packed unit's 13-bit flag.
        const val PACKED_US_DEAD = 1 shl 0
        const val PACKED_US_UNDEPLOYED = 1 shl 1
        const val PACKED_US_SOLO_ANIM1 = 1 shl 2
        const val PACKED_US_SOLO_ANIM2 = 1 shl 3
        const val PACKED_US_METIS_TOME = 1 shl 4
        const val PACKED_US_B4 = 1 shl 5
        const val PACKED_US_B5 = 1 shl 6
        const val PACKED_US_NEW_FRIEND = 1 shl 7

        // `Unit.state` bits (`enum` in bmunit.h) that the packed flag maps to.
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val US_NOT_DEPLOYED = 1 shl 3
        const val US_GROWTH_BOOST = 1 shl 13
        const val US_SOLOANIM_1 = 1 shl 14
        const val US_SOLOANIM_2 = 1 shl 15
        const val US_BIT16 = 1 shl 16
        const val US_BIT21 = 1 shl 21
        const val US_BIT25 = 1 shl 25

        /** `UNIT_SUPPORT_MAX_COUNT`: support partners per unit, one support level byte each. */
        const val UNIT_SUPPORT_MAX_COUNT = 7

        // Byte offsets in `struct Unit` (the field names are the decomp's).
        const val U_CHARACTER = 0x00
        const val U_CLASS = 0x04
        const val U_LEVEL = 0x08
        const val U_EXP = 0x09
        const val U_STATE = 0x0C
        const val U_X_POS = 0x10
        const val U_Y_POS = 0x11
        const val U_MAX_HP = 0x12
        const val U_CUR_HP = 0x13
        const val U_POW = 0x14
        const val U_SKL = 0x15
        const val U_SPD = 0x16
        const val U_DEF = 0x17
        const val U_RES = 0x18
        const val U_LCK = 0x19
        const val U_CON_BONUS = 0x1A
        const val U_MOV_BONUS = 0x1D
        const val U_ITEMS = 0x1E
        const val U_RANKS = 0x28
        const val U_SUPPORTS = 0x32
        const val U_SUPPORT_BITS = 0x39
    }
}
