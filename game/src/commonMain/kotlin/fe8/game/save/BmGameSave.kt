package fe8.game.save

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
import gba.core.sram.readSramFast
import gbafe.core.flags.EventFlags
import gbafe.core.time.GameClock

/**
 * The RAM `ReadGameSave` fills, as the objects that own it elsewhere, plus stand-ins for what has
 * no owner yet.
 *
 * @property playSt `gPlaySt`.
 * @property bmSt `gBmSt` (`gameStateBits` is read).
 * @property units the raw unit arrays (`gUnitArrayBlue/Red/Green`).
 * @property flags the event flag memory (`gPermanentFlagBits`).
 * @property clock the game's one [GameClock] (`gGameClock`).
 * @property convoy `gConvoyItemArray` as 100 raw little-endian u16 values.
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
     * Stand-in for `gGMData`'s monster RN state (`unk_ce`, three halfwords), the target of
     * `sub_80A71F8`.
     */
    public val gmMonsterRnState: IntArray = IntArray(3)

    /**
     * Stand-in for what `ReadWorldMapStuff(&src->wmStuff, &gGMData)` decodes (the world map state
     * is not ported): the raw 0x24 bytes of `struct GMapSaveInfo`.
     */
    public val worldMapStuff: ByteArray = ByteArray(Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE)

    /**
     * Stand-in for `gDungeonState.dungeon` (`LoadDungeonRecords`): the raw `struct Dungeon[2]`.
     */
    public val dungeons: ByteArray = ByteArray(DUNGEONS_SIZE)
}

/**
 * The game (chapter start) save reader of src/bmsave.c, on top of a [BmSaveLib].
 *
 * @property lib the save RAM routines (and the owner of `gPidStatsData` / `gChapterStats`).
 * @property loader the unit routines (`GetUnitMaxHp`) over the same unit arrays as the RAM read.
 */
public class BmGameSave(private val lib: BmSaveLib, private val loader: UnitLoader) {
    /** Stand-in for the `struct GameSavePackedUnit unitp` local. */
    private val packed = ByteArray(PACKED_UNIT_SIZE)

    /** Stand-in for the `u32 buf[2]` / `u32 buf` locals. */
    private val scalar = ByteArray(8)

    /** The `gPlaySt` bytes read. */
    private val playStBytes = ByteArray(PlaySt.SIZE)

    /** `gPermanentFlagBits` as read. */
    private val permanentBuf = ByteArray(Fe8EventFlags.PERMANENT_SAVE_BYTES)

    /** Stand-in for the `struct Dungeon dungeon[2]` local. */
    private val dungeon = ByteArray(DUNGEONS_SIZE)

    /** `n` bits of the little-endian bit stream [b] starting at bit [start] (n <= 16). */
    private fun bitsAt(b: ByteArray, start: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) {
            val bit = start + i
            v = v or (((b[bit shr 3].toInt() shr (bit and 7)) and 1) shl i)
        }
        return v
    }

    /**
     * Ported from src/bmsave.c: LoadSavedUnit
     *
     * Reads the 0x24-byte `struct GameSavePackedUnit` (a packed bit stream, LSB first) at SRAM
     * [srcOffset] and unpacks it into the unit at byte offset [unitOffset] of [units]. Fields the
     * record has no value for (`curHP` is set to the maximum HP, `supportBits` to 0, `index` and
     * the AI fields are kept).
     */
    public fun loadSavedUnit(srcOffset: Int, units: UnitTable, unitOffset: Int) {
        val p = packed
        readSramFast(lib.sram, srcOffset, p, 0, PACKED_UNIT_SIZE)
        val u = units.bytes
        val o = unitOffset
        val jid = bitsAt(p, 0, 7)
        val level = bitsAt(p, 7, 5)
        val exp = bitsAt(p, 12, 7)
        val xPos = bitsAt(p, 19, 6)
        val yPos = bitsAt(p, 25, 6)
        val flag = bitsAt(p, 31, 13)
        val pid = StructCodec.u8(p, 0x14)

        StructCodec.put32(u, o + U_CHARACTER, UnitLoader.characterDataAt(pid))
        StructCodec.put32(u, o + U_CLASS, UnitLoader.classDataAt(jid))
        u[o + U_LEVEL] = level.toByte()
        u[o + U_EXP] = exp.toByte()
        u[o + U_X_POS] = xPos.toByte()
        u[o + U_Y_POS] = yPos.toByte()

        u[o + U_MAX_HP] = bitsAt(p, 44, 6).toByte()
        u[o + U_POW] = bitsAt(p, 50, 5).toByte()
        u[o + U_SKL] = bitsAt(p, 55, 5).toByte()
        u[o + U_SPD] = bitsAt(p, 60, 5).toByte()
        u[o + U_DEF] = bitsAt(p, 65, 5).toByte()
        u[o + U_RES] = bitsAt(p, 70, 5).toByte()
        u[o + U_LCK] = bitsAt(p, 75, 5).toByte()
        u[o + U_CON_BONUS] = bitsAt(p, 80, 5).toByte()
        u[o + U_MOV_BONUS] = bitsAt(p, 85, 5).toByte()

        for (i in 0 until 5) {
            StructCodec.put16(u, o + U_ITEMS + i * 2, bitsAt(p, 90 + i * 14, 14))
        }

        if (StructCodec.u8(u, o + U_EXP) > 99) u[o + U_EXP] = -1

        var state = 0
        if ((PACKED_US_DEAD and flag) != 0) state = US_HIDDEN or US_DEAD
        if ((PACKED_US_UNDEPLOYED and flag) != 0) state = state or US_HIDDEN or US_NOT_DEPLOYED
        if ((PACKED_US_SOLO_ANIM1 and flag) != 0) state = state or US_SOLOANIM_1
        if ((PACKED_US_SOLO_ANIM2 and flag) != 0) state = state or US_SOLOANIM_2
        if ((PACKED_US_METIS_TOME and flag) != 0) state = state or US_GROWTH_BOOST
        if ((PACKED_US_B4 and flag) != 0) state = state or US_BIT16
        if ((PACKED_US_B5 and flag) != 0) state = state or US_BIT25
        if ((PACKED_US_NEW_FRIEND and flag) != 0) state = state or US_BIT21
        StructCodec.put32(u, o + U_STATE, state)

        for (i in 0 until 8) u[o + U_RANKS + i] = p[0x15 + i]
        for (i in 0 until UNIT_SUPPORT_MAX_COUNT) u[o + U_SUPPORTS + i] = p[0x1D + i]

        // SetUnitHp(unit, GetUnitMaxHp(unit)): the clamp to the maximum is a no-op.
        u[o + U_CUR_HP] = loader.getUnitMaxHp(o).toByte()
        u[o + U_SUPPORT_BITS] = 0

        // mins zero
        if (StructCodec.u8(u, o + U_EXP) == 0x7F) u[o + U_EXP] = -1
        if (StructCodec.u8(u, o + U_X_POS) == 0x3F) u[o + U_X_POS] = -1
        if (StructCodec.u8(u, o + U_Y_POS) == 0x3F) u[o + U_Y_POS] = -1
    }

    /**
     * Transcribed from src/bmsave.c: ReadBonusContentClaimFlags (not verified: the flags are zero
     * in the reference save, which proves the target, not the offset)
     *
     * Reads `bonusClaimFlags` of the game block at SRAM [blockOffset] into
     * `gBonusContentClaimFlags`.
     */
    public fun readBonusContentClaimFlags(blockOffset: Int) {
        readSramFast(lib.sram, blockOffset + GAME_BONUS_CLAIM, scalar, 0, 4)
        lib.setBonusContentClaimFlags(StructCodec.u32(scalar, 0))
    }

    /**
     * Transcribed from src/bmsave.c: ReadGMMonsterRnState (not verified: no trace compares the
     * world map's RN state)
     *
     * Reads the `u32 buf[2]` at SRAM [srcOffset] and copies its first three halfwords to
     * `gmMonsterRnState` as `sub_80A71F8` does.
     */
    public fun readGmMonsterRnState(srcOffset: Int, ram: GameSaveRam) {
        readSramFast(lib.sram, srcOffset, scalar, 0, 8)
        for (i in 0 until 3) ram.gmMonsterRnState[i] = StructCodec.u16(scalar, i * 2)
    }

    /**
     * Transcribed from src/bmsave-gmap.c: ReadWorldMapStuff (not verified, partial: the world
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
     * Loads the world map stuff of the game block of [slot] into [dest].
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
     * stuff and dungeon records (stand-ins of [GameSaveRam]); then the last game save id.
     * Without `PLAY_FLAG_HARD` in `gBmSt.gameStateBits` (as the C code tests it) the suspend
     * save is invalidated.
     */
    public fun readGameSave(ram: GameSaveRam, slot: Int) {
        val src = lib.getSaveReadAddr(slot)

        if ((PLAY_FLAG_HARD and ram.bmSt.gameStateBits) == 0) {
            lib.invalidateSuspendSave(Fe8SramLayout.SAVE_ID_SUSPEND)
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
        lib.readPermanentFlags(src + GAME_PERMANENT_FLAGS, permanentBuf)
        ram.flags.copyInPermanent(permanentBuf)
        lib.readPidStats(src + GAME_PID_STATS)
        lib.readChapterStats(src + GAME_CHAPTER_STATS)
        readBonusContentClaimFlags(src)
        readWorldMapStuff(src + GAME_WM_STUFF, ram)
        readSramFast(lib.sram, src + GAME_DUNGEONS, dungeon, 0, DUNGEONS_SIZE)
        // LoadDungeonRecords: memcpy into gDungeonState.dungeon.
        dungeon.copyInto(ram.dungeons)
        lib.writeLastGameSaveId(slot)
    }

    private companion object {
        const val PLAY_FLAG_HARD = 1 shl 6
        const val PACKED_US_DEAD = 1 shl 0
        const val PACKED_US_UNDEPLOYED = 1 shl 1
        const val PACKED_US_SOLO_ANIM1 = 1 shl 2
        const val PACKED_US_SOLO_ANIM2 = 1 shl 3
        const val PACKED_US_METIS_TOME = 1 shl 4
        const val PACKED_US_B4 = 1 shl 5
        const val PACKED_US_B5 = 1 shl 6
        const val PACKED_US_NEW_FRIEND = 1 shl 7
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val US_NOT_DEPLOYED = 1 shl 3
        const val US_GROWTH_BOOST = 1 shl 13
        const val US_SOLOANIM_1 = 1 shl 14
        const val US_SOLOANIM_2 = 1 shl 15
        const val US_BIT16 = 1 shl 16
        const val US_BIT21 = 1 shl 21
        const val US_BIT25 = 1 shl 25
        const val UNIT_SUPPORT_MAX_COUNT = 7
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
