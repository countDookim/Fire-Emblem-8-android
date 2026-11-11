/**
 * Mid-chapter suspend save serializer and deserializer.
 *
 * Implements the battlefield suspend save reading and writing routines from `src/bmsave.c`,
 * serializing volatile in-combat state for player, enemy, and allied factions (using the 0x34-byte
 * `struct SuspendSavePackedUnit`), active map traps, current turn actions, RNG seeds, event flags,
 * and alternating suspend slot selection.
 */

package fe8.game.save

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.bm.StructCodec
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.Fe8SuspendLayout as S
import gba.core.sram.readSramFast
import gba.core.sram.writeAndVerifySramFast
import gba.core.sram.writeSramFast

/**
 * The suspend (mid-chapter) save writer of src/bmsave.c, on top of a [BmSaveLib]. The C code's
 * `gGenericBuffer` and stack locals are buffers allocated once here.
 *
 * @property lib the save RAM routines (and the owner of `gPidStatsData` / `gChapterStats`).
 * @property rom the ROM that unit `pCharacterData` / `pClassData` pointers point into.
 */
public class BmSuspendSave(private val lib: BmSaveLib, private val rom: RomView) {
    /**
     * Stand-in for `gGenericBuffer`: the unit records are encoded here. Like the C buffer it is
     * not cleared between uses, so the bytes of the record of a unit without a character, which
     * `EncodeSuspendSavePackedUnit` leaves alone, are stale (a caller can pre-fill them).
     */
    public val genericBuffer: ByteArray =
        ByteArray((S.UNIT_SAVE_AMOUNT_RED + S.UNIT_SAVE_AMOUNT_GREEN) * S.PACKED_UNIT_SIZE)

    /** Stand-in for the `struct Dungeon dungeon[2]` local. */
    private val dungeon = ByteArray(2 * S.DUNGEON_SIZE)

    /** Stand-in for the `int val` / `u32 buf[2]` locals. */
    private val scalar = ByteArray(8)

    /** Stand-in for the `struct SaveBlockInfo chunk` local. */
    private val chunk = ByteArray(L.BLOCK_INFO_SIZE)

    /** Stand-in for the `struct GlobalSaveInfo header` local. */
    private val header = ByteArray(L.HEADER_SIZE)

    /** The `gPlaySt` bytes written. */
    private val playStBytes = ByteArray(PlaySt.SIZE)

    /** The convoy as ints (for `WriteSupplyItems`). */
    private val supply = IntArray(SuspendRam.CONVOY_BYTES / 2)

    /** `gPermanentFlagBits` as written (copied from the flags, no per-save allocation). */
    private val permanentBuf = ByteArray(Fe8EventFlags.PERMANENT_SAVE_BYTES)

    /** `gChapterFlagBits` as written. */
    private val chapterBuf = ByteArray(Fe8EventFlags.CHAPTER_SAVE_BYTES)

    /** Stand-in for the `struct SuspendSavePackedUnit unit_su` local of the reader. */
    private val packed = ByteArray(S.PACKED_UNIT_SIZE)

    /** The RN state words `StoreRNState` copies. */
    private val rnWords = IntArray(3)

    /**
     * Ported from src/bmsave.c: EncodeSuspendSavePackedUnit
     *
     * Packs the unit at byte offset [unitOffset] of [units] (`struct Unit`, 0x48 bytes) into the
     * 0x34-byte `struct SuspendSavePackedUnit` at [buf] + [bufOffset]. A unit without a character
     * only gets `pid` = 0; the other bytes of the record are left as they were. The `BITPACKED`
     * bit fields are LSB first and, as in C, a value is truncated to its field width.
     */
    public fun encodeSuspendSavePackedUnit(
        units: UnitTable,
        unitOffset: Int,
        buf: ByteArray,
        bufOffset: Int,
    ) {
        val u = units.bytes
        val o = unitOffset
        val b = bufOffset
        val character = StructCodec.u32(u, o + U_CHARACTER)
        if (character == 0) {
            buf[b + P_PID] = 0
            return
        }
        val klass = StructCodec.u32(u, o + U_CLASS)
        val supportBits = StructCodec.s8(u, o + U_SUPPORT_BITS)
        val statusIndex = StructCodec.u8(u, o + U_STATUS) and 0xF
        val statusDuration = StructCodec.u8(u, o + U_STATUS) ushr 4
        val torch = StructCodec.u8(u, o + U_TORCH) and 0xF
        val barrier = StructCodec.u8(u, o + U_TORCH) ushr 4

        buf[b + P_PID] = rom.u8(character + CHARACTER_NUMBER).toByte()
        buf[b + P_JID] = rom.u8(klass + CLASS_NUMBER).toByte()
        buf[b + P_RESCUE] = u[o + U_RESCUE]
        StructCodec.put32(buf, b + P_STATE, StructCodec.u32(u, o + U_STATE))
        buf[b + P_MAX_HP] = u[o + U_MAX_HP]
        buf[b + P_CUR_HP] = u[o + U_CUR_HP]
        buf[b + P_EXP] = u[o + U_EXP]
        buf[b + P_AI_FLAGS] = u[o + U_AI_FLAGS]
        buf[b + P_BALLISTA] =
            ((0x7F and StructCodec.u8(u, o + U_BALLISTA)) or ((0x01 and supportBits) shl 7))
                .toByte()

        val item0 = StructCodec.u16(u, o + U_ITEMS)
        val item1 = StructCodec.u16(u, o + U_ITEMS + 2)
        val item2 = StructCodec.u16(u, o + U_ITEMS + 4)
        StructCodec.put16(buf, b + P_ITEM1, (0x3FFF and item0) or ((0x06 and supportBits) shl 0x0D))
        StructCodec.put16(buf, b + P_ITEM2, (0x3FFF and item1) or ((0x18 and supportBits) shl 0x0B))
        StructCodec.put16(buf, b + P_ITEM3, (0x3FFF and item2) or ((0x60 and supportBits) shl 0x09))

        for (i in 0 until 8) buf[b + P_RANKS + i] = u[o + U_RANKS + i]
        for (i in 0 until UNIT_SUPPORT_MAX_COUNT) buf[b + P_SUPPORTS + i] = u[o + U_SUPPORTS + i]

        var ai1 = StructCodec.u8(u, o + U_AI1) and 0x7F
        if ((statusIndex and 8) != 0) ai1 = ai1 or 0x80
        buf[b + P_AI1] = ai1.toByte()
        buf[b + P_AI_A_PC] = u[o + U_AI_A_PC]
        buf[b + P_AI2] = u[o + U_AI2]
        buf[b + P_AI_B_PC] = u[o + U_AI_B_PC]
        StructCodec.put16(buf, b + P_AI_CONFIG, StructCodec.u16(u, o + U_AI_CONFIG))
        buf[b + P_AI_COUNTER] = u[o + U_AI_COUNTER]

        // Bit fields (each is truncated to its width, as GCC does on assignment).
        StructCodec.put32(
            buf, b + P_BITS0,
            StructCodec.field(u[o + U_LEVEL].toInt(), 0, 5) or
                StructCodec.field(u[o + U_X_POS].toInt(), 5, 6) or
                StructCodec.field(u[o + U_Y_POS].toInt(), 11, 6) or
                StructCodec.field(u[o + U_POW].toInt(), 17, 5) or
                StructCodec.field(u[o + U_SKL].toInt(), 22, 5) or
                StructCodec.field(u[o + U_SPD].toInt(), 27, 5),
        )
        StructCodec.put32(
            buf, b + P_BITS1,
            StructCodec.field(u[o + U_DEF].toInt(), 0, 5) or
                StructCodec.field(u[o + U_RES].toInt(), 5, 5) or
                StructCodec.field(u[o + U_LCK].toInt(), 10, 5) or
                StructCodec.field(u[o + U_CON_BONUS].toInt(), 15, 5) or
                StructCodec.field(statusIndex, 20, 3) or
                StructCodec.field(statusDuration, 23, 3) or
                StructCodec.field(torch, 26, 3) or
                StructCodec.field(barrier, 29, 3),
        )
        StructCodec.put32(
            buf, b + P_BITS2,
            StructCodec.field(u[o + U_MOV_BONUS].toInt(), 0, 4) or
                StructCodec.field(StructCodec.u16(u, o + U_ITEMS + 6), 4, 14) or
                StructCodec.field(StructCodec.u16(u, o + U_ITEMS + 8), 18, 14),
        )
    }

    /**
     * Ported from src/bmsave.c: ReadSuspendSavePackedUnit
     *
     * Reads the 0x34-byte `struct SuspendSavePackedUnit` at SRAM [srcOffset] and unpacks it into
     * the unit at byte offset [unitOffset] of [units]. Every field the C code assigns is set
     * (also for a record with `pid` 0, whose other bytes are whatever the writer left there);
     * `index` and the bytes the packed record has no field for (0x3A..0x3F, 0x47) are kept.
     * `exp` 0x7F and positions 0x3F become -1.
     */
    public fun readSuspendSavePackedUnit(srcOffset: Int, units: UnitTable, unitOffset: Int) {
        val p = packed
        readSramFast(lib.sram, srcOffset, p, 0, S.PACKED_UNIT_SIZE)
        val u = units.bytes
        val o = unitOffset
        StructCodec.put32(u, o + U_CHARACTER, UnitLoader.characterDataAt(StructCodec.u8(p, P_PID)))
        StructCodec.put32(u, o + U_CLASS, UnitLoader.classDataAt(StructCodec.u8(p, P_JID)))
        val bits0 = StructCodec.u32(p, P_BITS0)
        val bits1 = StructCodec.u32(p, P_BITS1)
        val bits2 = StructCodec.u32(p, P_BITS2)
        u[o + U_LEVEL] = StructCodec.bits(bits0, 0, 5).toByte()
        u[o + U_EXP] = p[P_EXP]
        StructCodec.put32(u, o + U_STATE, StructCodec.u32(p, P_STATE))
        u[o + U_X_POS] = StructCodec.bits(bits0, 5, 6).toByte()
        u[o + U_Y_POS] = StructCodec.bits(bits0, 11, 6).toByte()

        u[o + U_MAX_HP] = p[P_MAX_HP]
        u[o + U_CUR_HP] = p[P_CUR_HP]
        u[o + U_POW] = StructCodec.bits(bits0, 17, 5).toByte()
        u[o + U_SKL] = StructCodec.bits(bits0, 22, 5).toByte()
        u[o + U_SPD] = StructCodec.bits(bits0, 27, 5).toByte()
        u[o + U_DEF] = StructCodec.bits(bits1, 0, 5).toByte()
        u[o + U_RES] = StructCodec.bits(bits1, 5, 5).toByte()
        u[o + U_LCK] = StructCodec.bits(bits1, 10, 5).toByte()
        u[o + U_CON_BONUS] = StructCodec.bits(bits1, 15, 5).toByte()

        var statusIndex = StructCodec.bits(bits1, 20, 3)
        if ((StructCodec.u8(p, P_AI1) and 0x80) != 0) statusIndex = statusIndex or 8
        u[o + U_STATUS] =
            (statusIndex or (StructCodec.bits(bits1, 23, 3) shl 4)).toByte()
        u[o + U_TORCH] =
            (StructCodec.bits(bits1, 26, 3) or (StructCodec.bits(bits1, 29, 3) shl 4)).toByte()

        u[o + U_RESCUE] = p[P_RESCUE]
        u[o + U_MOV_BONUS] = StructCodec.bits(bits2, 0, 4).toByte()
        val ballista = StructCodec.u8(p, P_BALLISTA)
        u[o + U_BALLISTA] = (ballista and 0x7F).toByte()

        val item1 = StructCodec.u16(p, P_ITEM1)
        val item2 = StructCodec.u16(p, P_ITEM2)
        val item3 = StructCodec.u16(p, P_ITEM3)
        StructCodec.put16(u, o + U_ITEMS, item1 and 0x3FFF)
        StructCodec.put16(u, o + U_ITEMS + 2, item2 and 0x3FFF)
        StructCodec.put16(u, o + U_ITEMS + 4, item3 and 0x3FFF)
        StructCodec.put16(u, o + U_ITEMS + 6, StructCodec.bits(bits2, 4, 14))
        StructCodec.put16(u, o + U_ITEMS + 8, StructCodec.bits(bits2, 18, 14))

        u[o + U_SUPPORT_BITS] = (
            ((ballista and 0x80) shr 7) or ((item1 and 0xC000) shr 0x0D) or
                ((item2 and 0xC000) shr 0x0B) or ((item3 and 0xC000) shr 0x09)
            ).toByte()

        for (i in 0 until 8) u[o + U_RANKS + i] = p[P_RANKS + i]
        for (i in 0 until UNIT_SUPPORT_MAX_COUNT) u[o + U_SUPPORTS + i] = p[P_SUPPORTS + i]

        u[o + U_AI1] = (StructCodec.u8(p, P_AI1) and 0x7F).toByte()
        u[o + U_AI_A_PC] = p[P_AI_A_PC]
        u[o + U_AI2] = p[P_AI2]
        u[o + U_AI_B_PC] = p[P_AI_B_PC]
        StructCodec.put16(u, o + U_AI_CONFIG, StructCodec.u16(p, P_AI_CONFIG))
        u[o + U_AI_COUNTER] = p[P_AI_COUNTER]
        u[o + U_AI_FLAGS] = p[P_AI_FLAGS]

        if (StructCodec.u8(u, o + U_EXP) == 0x7F) u[o + U_EXP] = -1
        if (StructCodec.u8(u, o + U_X_POS) == 0x3F) u[o + U_X_POS] = -1
        if (StructCodec.u8(u, o + U_Y_POS) == 0x3F) u[o + U_Y_POS] = -1
    }

    /**
     * Transcribed from src/bmsave.c: ReadTraps (not verified: every trap in the reference is
     * zero, which proves the target, not the data)
     *
     * Reads the 64 traps of SRAM [srcOffset] into [traps] (`GetTrap(0)`).
     */
    public fun readTraps(srcOffset: Int, traps: ByteArray) {
        readSramFast(lib.sram, srcOffset, traps, 0, S.TRAP_MAX_COUNT * S.TRAP_SIZE)
    }

    /**
     * Transcribed from src/bmsave.c: ReadGMMonsterRnState (not verified: no suspend-trace state
     * covers it; its caller `ReadGameSave` is ported)
     *
     * Reads the `u32 buf[2]` at SRAM [srcOffset] and, as the world map's `sub_80A71F8` does,
     * copies its first three halfwords to [rnState] (`gGMData.unk_ce`).
     */
    public fun readGmMonsterRnState(srcOffset: Int, rnState: IntArray) {
        readSramFast(lib.sram, srcOffset, scalar, 0, 8)
        for (i in 0 until 3) rnState[i] = StructCodec.u16(scalar, i * 2)
    }

    /**
     * Ported from src/bmsave.c: ReadSuspendSave
     *
     * Loads the suspend block of [slot] (`SAVE_ID_SUSPEND`) + `gSuspendSaveIdOffset` (set by
     * [BmSaveLib.isValidSuspendSave]) into [ram]: `gPlaySt` and the game clock, `gActionData`
     * with the RN state in its first 6 bytes (`LoadRNStateFromActionStruct`), the units (after
     * `InitUnits`), pid / chapter stats, the convoy, both flag sets, the traps, and the
     * stand-ins of [SuspendRam] (menu override, world map stuff, dungeon records and state);
     * then the event slot counter and the bonus claim flags of the game slot in `gPlaySt`.
     */
    public fun readSuspendSave(ram: SuspendRam, slot: Int) {
        val src = lib.getSaveReadAddr(slot + lib.suspendSaveIdOffset)
        val sram = lib.sram
        readSramFast(sram, src + S.PLAY_ST, playStBytes, 0, PlaySt.SIZE)
        ram.playSt.decode(playStBytes, 0)
        ram.clock.set(ram.playSt.timeSaved.toLong() and 0xFFFFFFFFL)

        readSramFast(sram, src + S.ACTION, ram.actionData, 0, S.ACTION_SIZE)
        // LoadRNStateFromActionStruct: LoadRNState(gActionData._u00).
        for (i in 0 until 3) rnWords[i] = StructCodec.u16(ram.actionData, i * 2)
        ram.rng.primary.load(rnWords[0], rnWords[1], rnWords[2])
        ram.units.initUnits()

        for (i in 0 until S.UNIT_SAVE_AMOUNT_BLUE) {
            readSuspendSavePackedUnit(
                src + S.BLUE_UNITS + i * S.PACKED_UNIT_SIZE, ram.units,
                BLUE_BASE + i * UnitTable.UNIT_SIZE,
            )
        }
        for (i in 0 until S.UNIT_SAVE_AMOUNT_RED) {
            readSuspendSavePackedUnit(
                src + S.RED_UNITS + i * S.PACKED_UNIT_SIZE, ram.units,
                RED_BASE + i * UnitTable.UNIT_SIZE,
            )
        }
        for (i in 0 until S.UNIT_SAVE_AMOUNT_GREEN) {
            readSuspendSavePackedUnit(
                src + S.GREEN_UNITS + i * S.PACKED_UNIT_SIZE, ram.units,
                GREEN_BASE + i * UnitTable.UNIT_SIZE,
            )
        }

        lib.readPidStats(src + S.PID_STATS)
        lib.readChapterStats(src + S.CHAPTER_STATS)
        lib.readSupplyItems(src + S.SUPPLY, ram.convoy)
        lib.readPermanentFlags(src + S.PERMANENT_FLAGS, permanentBuf)
        ram.flags.copyInPermanent(permanentBuf)
        lib.readChapterFlags(src + S.CHAPTER_FLAGS, chapterBuf)
        ram.flags.copyInChapter(chapterBuf)
        readTraps(src + S.TRAPS, ram.traps)

        // SetForceDisabledMenuItems: the list is kept raw, see SuspendRam.menuOverride.
        readSramFast(sram, src + S.MENU_OVERRIDE, ram.menuOverride, 0, S.MENU_OVERRIDE_MAX)
        // ReadWorldMapStuff: not ported, see SuspendRam.worldMapStuff.
        readSramFast(sram, src + S.WM_STUFF, ram.worldMapStuff, 0, S.GMAP_SAVE_INFO_SIZE)

        // LoadDungeonRecords: the wmMonsterUnit slot into gDungeonState.dungeon[2].
        readSramFast(sram, src + S.WM_MONSTER_UNIT, dungeon, 0, dungeon.size)
        dungeon.copyInto(ram.dungeonState, DUNGEON_RECORDS, 0, dungeon.size)

        // LoadDungeonState: current = *saved, then type = current.unk_0B_4.
        readSramFast(sram, src + S.DUNGEON, dungeon, 0, S.DUNGEON_SIZE)
        dungeon.copyInto(ram.dungeonState, 0, 0, S.DUNGEON_SIZE)
        ram.dungeonState[DUNGEON_TYPE] =
            ((StructCodec.u8(ram.dungeonState, UNK_0B) ushr 4) and 0x0F).toByte()

        readSramFast(sram, src + S.EVENT_SLOT_CNT, scalar, 0, 4)
        ram.eventState.setEventSlotCounter(StructCodec.u32(scalar, 0))

        lib.setBonusContentClaimFlags(lib.loadSavedBonusClaimFlags(ram.playSt.gameSaveSlot))
    }

    /**
     * Transcribed from src/bmsave.c: WriteTraps (not verified: every trap in the reference is
     * zero, which proves the target, not the data)
     *
     * Writes the 64 traps of [traps] (`GetTrap(0)`) to SRAM [destOffset].
     */
    public fun writeTraps(destOffset: Int, traps: ByteArray) {
        writeAndVerifySramFast(traps, 0, lib.sram, destOffset, S.TRAP_MAX_COUNT * S.TRAP_SIZE)
    }

    /**
     * Transcribed from src/bmsave.c: WriteGMMonsterRnState (not verified: no suspend-trace
     * state covers it; its caller `WriteGameSave` is ported)
     *
     * Writes the `u32 buf[2]` that the world map's `sub_80A71E4` fills to SRAM [destOffset]: the
     * three halfwords [rnState] (`gGMData.unk_ce`, copied by `CpuSet`) and two bytes of
     * uninitialised stack (zero here).
     */
    public fun writeGmMonsterRnState(destOffset: Int, rnState: IntArray) {
        scalar.fill(0)
        for (i in 0 until 3) StructCodec.put16(scalar, i * 2, rnState[i])
        writeAndVerifySramFast(scalar, 0, lib.sram, destOffset, 8)
    }

    /**
     * Ported from src/bmsave.c: WriteSwappedSuspendSaveId
     *
     * Reads the global save info, flips `last_suspend_slot` (0 becomes 1, anything else 0) and
     * writes it back without recomputing the header checksum.
     */
    public fun writeSwappedSuspendSaveId() {
        lib.readGlobalSaveInfo(header)
        val last = header[L.HEADER_LAST_SUSPEND_SLOT].toInt()
        header[L.HEADER_LAST_SUSPEND_SLOT] = if (last == 0) 1 else 0
        lib.writeGlobalSaveInfoNoChecksum(header)
    }

    /**
     * Ported from src/bmsave.c: WriteSuspendSave
     *
     * Writes the suspend block for [slot] (`SAVE_ID_SUSPEND`) + the next suspend id from [ram]
     * and publishes it: block info, `gBmSt.just_resumed` cleared and the header's
     * `last_suspend_slot` flipped. Does nothing in the tutorial or when SRAM is not working.
     * `gPlaySt.time_saved` is set from [SuspendRam.clock], and the RN state is stored in
     * `gActionData` first (`StoreRNStateToActionStruct`).
     */
    public fun writeSuspendSave(ram: SuspendRam, slot: Int) {
        if ((PLAY_FLAG_TUTORIAL and ram.playSt.chapterStateBits) != 0) return
        if (!lib.isSramWorking()) return

        val index = slot + lib.getNextSuspendSaveId()
        val dest = lib.getSaveWriteAddr(index)
        val sram = lib.sram
        ram.playSt.timeSaved = ram.clock.get().toInt()
        ram.playSt.encode(playStBytes, 0)
        writeAndVerifySramFast(playStBytes, 0, sram, dest + S.PLAY_ST, PlaySt.SIZE)

        // StoreRNStateToActionStruct: StoreRNState(gActionData._u00).
        ram.rng.primary.store(rnWords)
        for (i in 0 until 3) StructCodec.put16(ram.actionData, i * 2, rnWords[i])
        writeAndVerifySramFast(ram.actionData, 0, sram, dest + S.ACTION, S.ACTION_SIZE)

        var buf = 0
        for (i in 0 until S.UNIT_SAVE_AMOUNT_BLUE) {
            val unit = BLUE_BASE + i * UnitTable.UNIT_SIZE
            encodeSuspendSavePackedUnit(ram.units, unit, genericBuffer, buf)
            buf += S.PACKED_UNIT_SIZE
        }
        writeSramFast(
            genericBuffer, 0, sram, dest + S.BLUE_UNITS,
            S.UNIT_SAVE_AMOUNT_BLUE * S.PACKED_UNIT_SIZE,
        )

        buf = 0
        for (i in 0 until S.UNIT_SAVE_AMOUNT_RED) {
            val unit = RED_BASE + i * UnitTable.UNIT_SIZE
            encodeSuspendSavePackedUnit(ram.units, unit, genericBuffer, buf)
            buf += S.PACKED_UNIT_SIZE
        }
        for (i in 0 until S.UNIT_SAVE_AMOUNT_GREEN) {
            val unit = GREEN_BASE + i * UnitTable.UNIT_SIZE
            encodeSuspendSavePackedUnit(ram.units, unit, genericBuffer, buf)
            buf += S.PACKED_UNIT_SIZE
        }
        writeSramFast(
            genericBuffer, 0, sram, dest + S.RED_UNITS,
            (S.UNIT_SAVE_AMOUNT_RED + S.UNIT_SAVE_AMOUNT_GREEN) * S.PACKED_UNIT_SIZE,
        )

        for (i in permanentBuf.indices) permanentBuf[i] = ram.flags.byteAt(i).toByte()
        lib.writePermanentFlags(dest + S.PERMANENT_FLAGS, permanentBuf)
        for (i in chapterBuf.indices) {
            chapterBuf[i] = ram.flags.byteAt(Fe8EventFlags.PERMANENT_STORAGE_BYTES + i).toByte()
        }
        lib.writeChapterFlags(dest + S.CHAPTER_FLAGS, chapterBuf)
        for (i in supply.indices) supply[i] = StructCodec.u16(ram.convoy, i * 2)
        lib.writeSupplyItems(dest + S.SUPPLY, supply)
        lib.writePidStats(dest + S.PID_STATS)
        lib.writeChapterStats(dest + S.CHAPTER_STATS)
        writeTraps(dest + S.TRAPS, ram.traps)

        writeAndVerifySramFast(
            ram.menuOverride, 0, sram, dest + S.MENU_OVERRIDE, S.MENU_OVERRIDE_MAX,
        )

        // WriteWorldMapStuff: not ported, see SuspendRam.worldMapStuff.
        writeAndVerifySramFast(
            ram.worldMapStuff, 0, sram, dest + S.WM_STUFF, S.GMAP_SAVE_INFO_SIZE,
        )

        // SaveDungeonRecords: gDungeonState.dungeon[2] into the wmMonsterUnit slot.
        ram.dungeonState.copyInto(dungeon, 0, DUNGEON_RECORDS, DUNGEON_RECORDS + dungeon.size)
        writeAndVerifySramFast(dungeon, 0, sram, dest + S.WM_MONSTER_UNIT, dungeon.size)

        // SaveDungeonState: gDungeonState.current, then current.unk_0B_4 = type.
        ram.dungeonState.copyInto(dungeon, 0, 0, S.DUNGEON_SIZE)
        val unk0b = StructCodec.u8(ram.dungeonState, UNK_0B)
        val type = StructCodec.u8(ram.dungeonState, DUNGEON_TYPE)
        ram.dungeonState[UNK_0B] = ((unk0b and 0x0F) or ((type and 0x0F) shl 4)).toByte()
        writeAndVerifySramFast(dungeon, 0, sram, dest + S.DUNGEON, S.DUNGEON_SIZE)

        StructCodec.put32(scalar, 0, ram.eventState.getEventSlotCounter())
        writeAndVerifySramFast(scalar, 0, sram, dest + S.EVENT_SLOT_CNT, 4)

        chunk.fill(0)
        StructCodec.put32(chunk, L.INFO_MAGIC32, L.SAVEMAGIC32)
        chunk[L.INFO_KIND] = L.SAVEBLOCK_KIND_SUSPEND.toByte()
        lib.writeSaveBlockInfo(chunk, index)

        ram.bmSt.justResumed = 0
        writeSwappedSuspendSaveId()
    }

    /** Bit masks, struct offsets, and packed field locations for suspend save encoding. */
    private companion object {
        /** `PLAY_FLAG_TUTORIAL` of `PlaySt.chapterStateBits`. */
        const val PLAY_FLAG_TUTORIAL = 1 shl 3

        /** `UNIT_SUPPORT_MAX_COUNT`. */
        const val UNIT_SUPPORT_MAX_COUNT = 7

        /** Byte offsets of the unit arrays in [UnitTable.bytes]. */
        const val BLUE_BASE = 0
        const val RED_BASE = UnitTable.BLUE_COUNT * UnitTable.UNIT_SIZE
        const val GREEN_BASE = RED_BASE + UnitTable.RED_COUNT * UnitTable.UNIT_SIZE

        /** Offsets in `struct Struct030017A0`. */
        const val DUNGEON_RECORDS = 0x0C
        const val UNK_0B = 0x0B
        const val DUNGEON_TYPE = 0x24

        /** `CharacterData.number` / `ClassData.number`. */
        const val CHARACTER_NUMBER = 4
        const val CLASS_NUMBER = 4

        /** Offsets in `struct Unit`. */
        const val U_CHARACTER = 0x00
        const val U_CLASS = 0x04
        const val U_LEVEL = 0x08
        const val U_EXP = 0x09
        const val U_AI_FLAGS = 0x0A
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
        const val U_RESCUE = 0x1B
        const val U_BALLISTA = 0x1C
        const val U_MOV_BONUS = 0x1D
        const val U_ITEMS = 0x1E
        const val U_RANKS = 0x28
        const val U_STATUS = 0x30
        const val U_TORCH = 0x31
        const val U_SUPPORTS = 0x32
        const val U_SUPPORT_BITS = 0x39
        const val U_AI_CONFIG = 0x40
        const val U_AI1 = 0x42
        const val U_AI_A_PC = 0x43
        const val U_AI2 = 0x44
        const val U_AI_B_PC = 0x45
        const val U_AI_COUNTER = 0x46

        /** Offsets in `struct SuspendSavePackedUnit`. */
        const val P_PID = 0x00
        const val P_JID = 0x01
        const val P_AI1 = 0x02
        const val P_RESCUE = 0x03
        const val P_STATE = 0x04
        const val P_ITEM1 = 0x08
        const val P_ITEM2 = 0x0A
        const val P_ITEM3 = 0x0C
        const val P_MAX_HP = 0x0E
        const val P_CUR_HP = 0x0F
        const val P_EXP = 0x10
        const val P_AI_FLAGS = 0x11
        const val P_RANKS = 0x12
        const val P_SUPPORTS = 0x1A
        const val P_AI_A_PC = 0x21
        const val P_AI2 = 0x22
        const val P_AI_B_PC = 0x23
        const val P_BITS0 = 0x24
        const val P_BITS1 = 0x28
        const val P_BITS2 = 0x2C
        const val P_BALLISTA = 0x30
        const val P_AI_COUNTER = 0x31
        const val P_AI_CONFIG = 0x32
    }
}
