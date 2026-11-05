package fe8.game.save

import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.CartSram
import gba.core.sram.readSramFast
import gba.core.sram.writeAndVerifySramFast
import gba.core.sram.writeSramFast
import gbafe.save.SaveChecksums

/**
 * The boot-time save RAM routines of src/bmsave-lib.c (plus the early-return path of
 * `EraseInvalidSaveData` from src/bmsave-misc.c), working on a [CartSram] instead of the memory
 * mapped at 0x0E000000. `struct GlobalSaveInfo` and the small aux blocks are handled as raw
 * little-endian bytes at the [Fe8SramLayout] offsets. The C code's `gGenericBuffer` and stack
 * locals are replaced by buffers allocated once here, so no call allocates.
 *
 * The boot order in src/main.c `AgbMain` is `SramInit`, ..., `EraseInvalidSaveData`,
 * `EraseSramDataIfInvalid`; see [boot].
 *
 * @property sram the save RAM this instance reads and writes (`gSram`).
 */
public class BmSaveLib(public val sram: CartSram) {
    /** `gBoolSramWorking`: set by [sramInit] when the probe write read back correctly. */
    public var isSramWorkingFlag: Boolean = false
        private set

    /** `gSuspendSaveIdOffset`: the suspend slot [isValidSuspendSave] last chose (0x7F = none). */
    public var suspendSaveIdOffset: Int = 0
        private set

    /** Stand-in for `gGenericBuffer` (aux block loaders/erasers); big enough for every block. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /** Stand-in for the `struct GlobalSaveInfo local_info` / `info` locals. */
    private val infoBuffer = ByteArray(L.HEADER_SIZE)

    /** Stand-in for the `struct SaveBlockInfo tmp` local of `ReadSaveBlockInfo`. */
    private val blockInfoBuffer = ByteArray(L.BLOCK_INFO_SIZE)

    /** Stand-in for the `struct SaveBlockInfo chunk` locals of the callers of the above. */
    private val scratchInfo = ByteArray(L.BLOCK_INFO_SIZE)

    /** The 0x40 bytes of 0xFF that `WipeSram` writes 0x200 times (`u32 buf[0x10]`). */
    private val wipeBuffer = ByteArray(WIPE_CHUNK)

    /** Stand-in for the `u32 buf[2]` of `SramInit`. */
    private val probeBuffer = ByteArray(8)

    /** Stand-in for the EWRAM buffer `EraseInvalidSaveData` reads the whole SRAM into. */
    private val ewramCopy = ByteArray(L.SRAM_SIZE)

    /**
     * Runs the save RAM part of `AgbMain` in its original order: [sramInit], then
     * [eraseInvalidSaveData], then [eraseSramDataIfInvalid]. (Between them the C code starts
     * procs, the sprite system and the RNG, none of which touches SRAM.)
     */
    public fun boot() {
        sramInit()
        eraseInvalidSaveData()
        eraseSramDataIfInvalid()
    }

    /**
     * Ported from src/bmsave-lib.c: SramInit
     *
     * Writes the probe word 0x12345678 to [Fe8SramLayout.RESERVED_OFFSET], reads it back and sets
     * the working flag when it matches. `SetSramFastFunc` (copying the SRAM routines to WRAM) and
     * the `REG_IE` write have no equivalent here. Note the probe stays in SRAM until a
     * [wipeSram] erases it.
     */
    public fun sramInit() {
        putU32(probeBuffer, 0, PROBE_WORD0)
        putU32(probeBuffer, 4, PROBE_WORD1)
        writeSramFast(probeBuffer, 0, sram, L.RESERVED_OFFSET, L.RESERVED_SIZE)
        readSramFast(sram, L.RESERVED_OFFSET, probeBuffer, 4, L.RESERVED_SIZE)
        isSramWorkingFlag = u32(probeBuffer, 4) == u32(probeBuffer, 0)
    }

    /** Ported from src/bmsave-lib.c: IsSramWorking */
    public fun isSramWorking(): Boolean = isSramWorkingFlag

    /**
     * Ported from src/bmsave-lib.c: WipeSram
     *
     * Overwrites all 0x8000 bytes of SRAM with 0xFF, in 0x200 verified writes of 0x40 bytes.
     */
    public fun wipeSram() {
        wipeBuffer.fill(0xFF.toByte())
        for (i in 0 until L.SRAM_SIZE / WIPE_CHUNK) {
            writeAndVerifySramFast(wipeBuffer, 0, sram, i * WIPE_CHUNK, WIPE_CHUNK)
        }
    }

    /**
     * Ported from src/bmsave-lib.c: ReadGlobalSaveInfo
     *
     * Reads the 0x64-byte global save info into [buf] (a private scratch buffer when null, as the
     * C code uses a local) and returns true when it is valid: SRAM works, the marker string is
     * "AGB-FE9", both magics match and the checksum over the first 0x50 bytes matches.
     */
    public fun readGlobalSaveInfo(buf: ByteArray? = null): Boolean {
        if (!isSramWorking()) return false
        val info = buf ?: infoBuffer
        readSramFast(sram, L.HEADER_OFFSET, info, 0, L.HEADER_SIZE)
        return stringCompare(info, MARKER) &&
            u32(info, L.HEADER_MAGIC32) == L.SAVEMAGIC32 &&
            u16(info, L.HEADER_MAGIC16) == L.SAVEMAGIC16 &&
            u16(info, L.HEADER_CHECKSUM) ==
            SaveChecksums.checksum16(info, 0, L.HEADER_CHECKSUM_SIZE)
    }

    /**
     * Ported from src/bmsave-lib.c: WriteGlobalSaveInfo
     *
     * Stores the checksum of the first 0x50 bytes of [header] into it, then writes the 0x64 bytes
     * to SRAM with verification.
     */
    public fun writeGlobalSaveInfo(header: ByteArray) {
        val sum = SaveChecksums.checksum16(header, 0, L.HEADER_CHECKSUM_SIZE)
        putU16(header, L.HEADER_CHECKSUM, sum)
        writeAndVerifySramFast(header, 0, sram, L.HEADER_OFFSET, L.HEADER_SIZE)
    }

    /**
     * Unverified port of src/bmsave-lib.c: WriteGlobalSaveInfoNoChecksum (not on the boot path,
     * so no reference test executes it).
     *
     * Writes the 0x64 bytes of [header] to SRAM with verification, leaving its checksum as is.
     */
    public fun writeGlobalSaveInfoNoChecksum(header: ByteArray) {
        writeAndVerifySramFast(header, 0, sram, L.HEADER_OFFSET, L.HEADER_SIZE)
    }

    /**
     * Ported from src/bmsave-lib.c: InitGlobalSaveInfodata
     *
     * Wipes SRAM, then writes a fresh global save info: the marker, both magics, every flag and
     * counter 0 (including `last_suspend_slot` and `last_game_save_id`), and its checksum.
     */
    public fun initGlobalSaveInfodata() {
        wipeSram()
        val info = infoBuffer
        info.fill(0)
        MARKER.copyInto(info, L.HEADER_NAME)
        putU32(info, L.HEADER_MAGIC32, L.SAVEMAGIC32)
        putU16(info, L.HEADER_MAGIC16, L.SAVEMAGIC16)
        writeGlobalSaveInfo(info)
    }

    /** Ported from src/bmsave-lib.c: EraseBonusContentData */
    public fun eraseBonusContentData() {
        genericBuffer.fill(0, 0, L.BONUS_SIZE)
        saveBonusContentData(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadBonusContentData
     *
     * Reads the bonus claim block into [buf] (scratch when null); true when SRAM works and the
     * checksum over its first 0x140 bytes matches the one at 0x140.
     */
    public fun loadBonusContentData(buf: ByteArray? = null): Boolean =
        loadChecked(L.BONUS_OFFSET, L.BONUS_SIZE, L.BONUS_CHECKSUM, L.BONUS_CHECKSUM, buf)

    /**
     * Ported from src/bmsave-lib.c: SaveBonusContentData
     *
     * Stores the checksum of the first 0x140 bytes of [buf] at 0x140 and writes the block.
     */
    public fun saveBonusContentData(buf: ByteArray) {
        writeChecked(buf, L.BONUS_OFFSET, L.BONUS_SIZE, L.BONUS_CHECKSUM, L.BONUS_CHECKSUM)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadAndVerfyRankData
     *
     * Reads the ranking block into [buf] (scratch when null); true when SRAM works and the
     * checksum over its first 0x90 bytes matches the one at 0x90.
     */
    public fun loadAndVerifyRankData(buf: ByteArray? = null): Boolean =
        loadChecked(L.RANK_OFFSET, L.RANK_SIZE, L.RANK_CHECKSUM, L.RANK_SUM_SIZE, buf)

    /**
     * Ported from src/bmsave-lib.c: SaveRankings
     *
     * Stores the checksum of the first 0x90 bytes of [buf] at 0x90 and writes the block.
     */
    public fun saveRankings(buf: ByteArray) {
        writeChecked(buf, L.RANK_OFFSET, L.RANK_SIZE, L.RANK_CHECKSUM, L.RANK_SUM_SIZE)
    }

    /** Ported from src/bmsave-lib.c: EraseSaveRankData */
    public fun eraseSaveRankData() {
        genericBuffer.fill(0, 0, L.RANK_SIZE)
        saveRankings(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadAndVerifySoundRoomData
     *
     * Reads the sound room block into [buf] (scratch when null); true when SRAM works and the
     * checksum over its first 0x20 bytes matches the one at 0x20.
     */
    public fun loadAndVerifySoundRoomData(buf: ByteArray? = null): Boolean = loadChecked(
        L.SOUND_ROOM_OFFSET, L.SOUND_ROOM_SIZE, L.SOUND_ROOM_CHECKSUM, L.SOUND_ROOM_CHECKSUM, buf,
    )

    /**
     * Ported from src/bmsave-lib.c: WriteSoundRoomSaveData
     *
     * Stores the checksum of the first 0x20 bytes of [buf] at 0x20 and writes the block.
     */
    public fun writeSoundRoomSaveData(buf: ByteArray) {
        writeChecked(
            buf, L.SOUND_ROOM_OFFSET, L.SOUND_ROOM_SIZE, L.SOUND_ROOM_CHECKSUM,
            L.SOUND_ROOM_CHECKSUM,
        )
    }

    /** Ported from src/bmsave-lib.c: EraseSoundRoomSaveData */
    public fun eraseSoundRoomSaveData() {
        genericBuffer.fill(0, 0, L.SOUND_ROOM_SIZE)
        writeSoundRoomSaveData(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadAndVerfyLinkArenaStruct2
     *
     * Reads the link arena block into [buf] (scratch when null); true when SRAM works and the
     * checksum over its first 0x10 bytes matches the one at 0x10.
     */
    public fun loadAndVerifyLinkArenaStruct2(buf: ByteArray? = null): Boolean = loadChecked(
        L.LINK_ARENA2_OFFSET, L.LINK_ARENA2_SIZE, L.LINK_ARENA2_CHECKSUM, L.LINK_ARENA2_CHECKSUM,
        buf,
    )

    /**
     * Ported from src/bmsave-lib.c: WriteLinkArenaStruct2
     *
     * Stores the checksum of the first 0x10 bytes of [buf] at 0x10 and writes the block.
     */
    public fun writeLinkArenaStruct2(buf: ByteArray) {
        writeChecked(
            buf, L.LINK_ARENA2_OFFSET, L.LINK_ARENA2_SIZE, L.LINK_ARENA2_CHECKSUM,
            L.LINK_ARENA2_CHECKSUM,
        )
    }

    /** Ported from src/bmsave-lib.c: EraseLinkArenaStruct2 */
    public fun eraseLinkArenaStruct2() {
        genericBuffer.fill(0, 0, L.LINK_ARENA2_SIZE)
        writeLinkArenaStruct2(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: EraseSramDataIfInvalid
     *
     * Re-initialises whatever is invalid: the global save info (which wipes all of SRAM first),
     * then the bonus claim, ranking, sound room and link arena blocks, and finally checks the
     * suspend save ([loadAndVerifySuspendSave]).
     */
    public fun eraseSramDataIfInvalid() {
        if (!readGlobalSaveInfo(null)) initGlobalSaveInfodata()
        if (!loadBonusContentData(null)) eraseBonusContentData()
        if (!loadAndVerifyRankData(null)) eraseSaveRankData()
        if (!loadAndVerifySoundRoomData(null)) eraseSoundRoomSaveData()
        if (!loadAndVerifyLinkArenaStruct2(null)) eraseLinkArenaStruct2()
        loadAndVerifySuspendSave()
    }

    /**
     * Partially ported from src/bmsave-misc.c: EraseInvalidSaveData (foreign-magic conversion
     * branch missing, M5-25/M5-26)
     *
     * Reads the global save info; returns at once when its `magic32` is the FE8 magic. Otherwise
     * reads the whole SRAM and returns unless the magic is 0x00040603 (the save of another
     * game). That last case, which converts the old save by erasing the header, both suspend
     * blocks, the game saves and the arena block, is not ported.
     *
     * @throws NotImplementedError when SRAM holds a save with the foreign magic 0x00040603.
     */
    public fun eraseInvalidSaveData() {
        readSramFast(sram, L.HEADER_OFFSET, ewramCopy, 0, L.HEADER_SIZE)
        if (u32(ewramCopy, L.HEADER_MAGIC32) == L.SAVEMAGIC32) return
        readSramFast(sram, 0, ewramCopy, 0, L.SRAM_SIZE)
        if (u32(ewramCopy, L.HEADER_MAGIC32) != FOREIGN_MAGIC32) return
        TODO("EraseInvalidSaveData: erasing a save with magic32 0x00040603 (task M5-25)")
    }

    /**
     * Partially ported from src/bmsave-xmap.c: LoadAndVerfySuspendSave (valid-suspend branch
     * missing, M5-25/M5-26)
     *
     * Does nothing unless [isValidSuspendSave] finds a suspend save. The valid-save branch (read
     * its PlaySt, check the extra-map header, maybe invalidate it) is not ported.
     *
     * @throws NotImplementedError when a valid suspend save exists.
     */
    public fun loadAndVerifySuspendSave() {
        if (isValidSuspendSave(L.SAVE_ID_SUSPEND)) {
            TODO("LoadAndVerfySuspendSave: valid suspend save branch (task M5-25)")
        }
    }

    /**
     * Ported from src/bmsave.c: IsValidSuspendSave
     *
     * True when [slot] is [Fe8SramLayout.SAVE_ID_SUSPEND] and one of the two suspend block infos
     * (last-written slot first) is valid; remembers the chosen slot in [suspendSaveIdOffset]
     * (0x7F when none is valid).
     */
    public fun isValidSuspendSave(slot: Int): Boolean {
        if (!isSramWorking()) return false
        if (L.SAVE_ID_SUSPEND != slot) return false
        suspendSaveIdOffset = getLastSuspendSaveId()
        if (readSaveBlockInfo(null, suspendSaveIdOffset + 3)) return true
        suspendSaveIdOffset = getNextSuspendSaveId()
        if (readSaveBlockInfo(null, suspendSaveIdOffset + 3)) return true
        suspendSaveIdOffset = 0x7F
        return false
    }

    /**
     * Ported from src/bmsave.c: GetLastSuspendSaveId
     *
     * 1 when the global save info's `last_suspend_slot` is 1, else 0 (the info is read even when
     * it is invalid, as in C).
     */
    public fun getLastSuspendSaveId(): Int {
        readGlobalSaveInfo(infoBuffer)
        return if (infoBuffer[L.HEADER_LAST_SUSPEND_SLOT].toInt() == 1) 1 else 0
    }

    /** Ported from src/bmsave.c: GetNextSuspendSaveId */
    public fun getNextSuspendSaveId(): Int = 1 - getLastSuspendSaveId()

    /**
     * Ported from src/bmsave-lib.c: ReadSaveBlockInfo
     *
     * Reads block info [index] into [chunk] (scratch when null); true when its `magic16`, its
     * `magic32` for that block kind and its block checksum ([verifySaveBlockChecksum]) are right.
     */
    public fun readSaveBlockInfo(chunk: ByteArray?, index: Int): Boolean {
        val info = chunk ?: blockInfoBuffer
        readSramFast(sram, L.blockInfoOffset(index), info, 0, L.BLOCK_INFO_SIZE)
        if (L.SAVEMAGIC16 != u16(info, L.INFO_MAGIC16)) return false
        val magic = when (index) {
            L.SAVE_ID_GAME0, L.SAVE_ID_GAME1, L.SAVE_ID_GAME2,
            L.SAVE_ID_SUSPEND, L.SAVE_ID_SUSPEND_ALT -> L.SAVEMAGIC32
            L.SAVE_ID_ARENA -> L.SAVEMAGIC32_ARENA
            L.SAVE_ID_XMAP -> L.SAVEMAGIC32_XMAP
            else -> return false
        }
        if (u32(info, L.INFO_MAGIC32) != magic) return false
        return verifySaveBlockChecksum(info)
    }

    /**
     * Ported from src/bmsave-lib.c: WriteSaveBlockInfo
     *
     * Fills `magic16`, `offset` (the low 16 bits of the write address, which is the SRAM offset),
     * `size` by `kind` and the block checksum of [chunk] (a 0x10-byte block info the caller has set
     * `kind`, and for valid kinds `magic32`, on) and writes it to block info [index]. Like the C
     * code it returns without writing for `index >= 7` or an unknown kind.
     */
    public fun writeSaveBlockInfo(chunk: ByteArray, index: Int) {
        putU16(chunk, L.INFO_MAGIC16, L.SAVEMAGIC16)
        putU16(chunk, L.INFO_OFFSET, getSaveWriteAddr(index))
        if (index >= L.BLOCK_INFO_COUNT) return
        when (chunk[L.INFO_KIND].toInt()) {
            L.SAVEBLOCK_KIND_GAME -> putU16(chunk, L.INFO_SIZE, L.GAME_SIZE)
            L.SAVEBLOCK_KIND_SUSPEND -> putU16(chunk, L.INFO_SIZE, L.SUSPEND_SIZE)
            L.SAVEBLOCK_KIND_ARENA -> putU16(chunk, L.INFO_SIZE, L.ARENA_SIZE)
            L.SAVEBLOCK_KIND_XMAP -> putU16(chunk, L.INFO_SIZE, L.XMAP_SIZE)
            L.SAVEBLOCK_KIND_INVALID -> {
                putU16(chunk, L.INFO_SIZE, 0)
                putU16(chunk, L.INFO_OFFSET, 0)
                putU16(chunk, L.INFO_MAGIC16, 0)
            }
            else -> return
        }
        populateSaveBlockChecksum(chunk)
        writeAndVerifySramFast(chunk, 0, sram, L.blockInfoOffset(index), L.BLOCK_INFO_SIZE)
    }

    /** Ported from src/bmsave-lib.c: EraseSaveBlockInfo */
    public fun eraseSaveBlockInfo(index: Int) {
        if (index < L.BLOCK_INFO_COUNT) {
            blockInfoBuffer.fill(0xFF.toByte())
            writeAndVerifySramFast(
                blockInfoBuffer, 0, sram, L.blockInfoOffset(index), L.BLOCK_INFO_SIZE,
            )
        }
    }

    /**
     * Ported from src/bmsave-lib.c: GetSaveWriteAddr
     *
     * The SRAM offset block [index] is written at (the C pointer's low 16 bits), 0 (NULL) for an
     * unknown index.
     */
    public fun getSaveWriteAddr(index: Int): Int = when (index) {
        L.SAVE_ID_GAME0 -> L.GAME0_OFFSET
        L.SAVE_ID_GAME1 -> L.GAME1_OFFSET
        L.SAVE_ID_GAME2 -> L.GAME2_OFFSET
        L.SAVE_ID_SUSPEND -> L.SUSPEND0_OFFSET
        L.SAVE_ID_SUSPEND_ALT -> L.SUSPEND1_OFFSET
        L.SAVE_ID_ARENA -> L.ARENA_OFFSET
        L.SAVE_ID_XMAP -> L.XMAP_OFFSET
        else -> 0
    }

    /**
     * Ported from src/bmsave-lib.c: GetSaveReadAddr
     *
     * The SRAM offset stored in block info [index] (read even when the info is invalid).
     */
    public fun getSaveReadAddr(index: Int): Int {
        readSaveBlockInfo(scratchInfo, index)
        return u16(scratchInfo, L.INFO_OFFSET)
    }

    /** Ported from src/bmsave.c: SramChecksum32 (reads [size] bytes at SRAM [offset]). */
    public fun sramChecksum32(offset: Int, size: Int): Int {
        readSramFast(sram, offset, genericBuffer, 0, size)
        return SaveChecksums.computeChecksum32(genericBuffer, 0, size)
    }

    /** Ported from src/bmsave.c: VerifySaveBlockChecksum */
    public fun verifySaveBlockChecksum(info: ByteArray): Boolean =
        u32(info, L.INFO_CHECKSUM32) ==
            sramChecksum32(u16(info, L.INFO_OFFSET), u16(info, L.INFO_SIZE))

    /** Ported from src/bmsave.c: PopulateSaveBlockChecksum */
    public fun populateSaveBlockChecksum(info: ByteArray) {
        putU32(info, L.INFO_CHECKSUM32, sramChecksum32(u16(info, L.INFO_OFFSET), u16(info, L.INFO_SIZE)))
    }

    /** Ported from src/bmsave.c: IsSaveValid */
    public fun isSaveValid(index: Int): Boolean = readSaveBlockInfo(null, index)

    /**
     * Ported from src/bmsave.c: InvalidateSuspendSave
     *
     * Marks block info [slot] (and the alternate one for the first suspend slot) invalid. The
     * C `chunk` local is uninitialised apart from `kind`; the unset fields are 0 here.
     */
    public fun invalidateSuspendSave(slot: Int) {
        scratchInfo.fill(0)
        scratchInfo[L.INFO_KIND] = L.SAVEBLOCK_KIND_INVALID.toByte()
        writeSaveBlockInfo(scratchInfo, slot)
        if (L.SAVE_ID_SUSPEND == slot) writeSaveBlockInfo(scratchInfo, L.SAVE_ID_SUSPEND_ALT)
    }

    /** Transcribed from src/bmsave.c: WriteLastGameSaveId (not verified: no trace writes it). */
    public fun writeLastGameSaveId(num: Int) {
        readGlobalSaveInfo(infoBuffer)
        infoBuffer[L.HEADER_LAST_GAME_SAVE_ID] = num.toByte()
        writeGlobalSaveInfoNoChecksum(infoBuffer)
    }

    /** Transcribed from src/bmsave.c: ReadLastGameSaveId (not verified: no trace reads it). */
    public fun readLastGameSaveId(): Int {
        if (!readGlobalSaveInfo(infoBuffer)) return 0
        val ret = infoBuffer[L.HEADER_LAST_GAME_SAVE_ID].toInt()
        return if (ret > L.SAVE_ID_GAME2 || ret < 0) L.SAVE_ID_GAME0 else ret
    }

    /** Shared body of the `LoadAndVerfy*`/`LoadBonusContentData` readers: read, then checksum. */
    private fun loadChecked(
        offset: Int,
        size: Int,
        checksumAt: Int,
        sumSize: Int,
        buf: ByteArray?,
    ): Boolean {
        if (!isSramWorking()) return false
        val b = buf ?: genericBuffer
        readSramFast(sram, offset, b, 0, size)
        return u16(b, checksumAt) == SaveChecksums.checksum16(b, 0, sumSize)
    }

    /** Shared body of the `Save*`/`Write*` writers: store the checksum, then write the block. */
    private fun writeChecked(
        buf: ByteArray,
        offset: Int,
        size: Int,
        checksumAt: Int,
        sumSize: Int,
    ) {
        putU16(buf, checksumAt, SaveChecksums.checksum16(buf, 0, sumSize))
        writeAndVerifySramFast(buf, 0, sram, offset, size)
    }

    private companion object {
        /** `gGenericBuffer` is 0x2000 bytes in C; the largest block used here is 0x144. */
        const val GENERIC_BUFFER_SIZE = L.SUSPEND_SIZE

        /** Bytes `WipeSram` writes per call (0x200 calls cover the 0x8000 bytes). */
        const val WIPE_CHUNK = 0x40

        /** First probe word of `SramInit`. */
        const val PROBE_WORD0 = 0x12345678

        /** Second probe word of `SramInit` (the read-back target is initialised with it). */
        const val PROBE_WORD1 = -0x789ABCDF

        /** `magic32` of the other game's save that `EraseInvalidSaveData` converts. */
        const val FOREIGN_MAGIC32 = 0x00040603

        /** `sSaveMarker` including its NUL terminator, filling the 8-byte `name` field. */
        val MARKER = ByteArray(8).also { m ->
            L.SAVE_MARKER.forEachIndexed { i, c -> m[i] = c.code.toByte() }
        }

        /** Ported from src/bmlib.c: StringCompare, as used on the header marker (true = equal). */
        fun stringCompare(a: ByteArray, b: ByteArray): Boolean {
            var i = 0
            while (a[i].toInt() or b[i].toInt() != 0) {
                if (a[i] != b[i]) return false
                i++
            }
            return true
        }

        fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

        fun putU16(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
        }

        fun putU32(b: ByteArray, o: Int, v: Int) {
            putU16(b, o, v)
            putU16(b, o + 2, v shr 16)
        }
    }
}
