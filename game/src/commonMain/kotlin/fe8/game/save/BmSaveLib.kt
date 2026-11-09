/**
 * Low-level cartridge SRAM management and block validation routines.
 *
 * Implements hardware probing, header management, block descriptors, checksum calculations,
 * and aux block persistence ported from `src/bmsave-lib.c`, `src/bmsave-bwl.c`, and `src/bmsave.c`.
 */

package fe8.game.save

import fe8.game.bm.PlaySt
import fe8.game.bm.initPlayConfig
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.CartSram
import gba.core.sram.readSramFast
import gba.core.sram.writeAndVerifySramFast
import gba.core.sram.writeSramFast
import gbafe.core.flags.EventFlags
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

    /** Stand-in for the `struct PlaySt playSt` local of `LoadAndVerfySuspendSave` (head only). */
    private val playStBuffer = ByteArray(PLAY_ST_READ_SIZE)

    /** Stand-in for `gExtraMapSaveHead` (the part read: up to `unk0F`). */
    private val xmapHeadBuffer = ByteArray(XMAP_HEAD_READ_SIZE)

    /** Stand-in for the `struct PlaySt` bytes read from SRAM. */
    private val playStFull = ByteArray(PlaySt.SIZE)

    /** Stand-in for the `struct PlaySt ram_ch` local of `IsPlaythroughIdUnique`. */
    private val scratchPlaySt = PlaySt()

    /** `gBonusContentClaimFlags`. */
    public var bonusContentClaimFlags: Int = 0
        private set

    /** Stand-in for the `unsigned char buf[176]` of `WriteSupplyItems`. */
    private val supplyBuffer = ByteArray(SUPPLY_SIZE)

    /** Stand-in for the `struct GMapSaveInfo info` of `ClearWorldMapStuff`. */
    private val gmapBuffer = ByteArray(GMAP_SAVE_INFO_SIZE)

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
     * Transcribed from src/bmsave-lib.c: SaveBonusContentData (not verified: every reference
     * run holds an all-zero bonus block, so no run shows its checksum or data)
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
     * Transcribed from src/bmsave-lib.c: SaveRankings (not verified: every reference run holds
     * an all-zero ranking block, so no run shows its checksum or data)
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
     * Transcribed from src/bmsave-lib.c: WriteLinkArenaStruct2 (not verified: every reference
     * run holds an all-zero link arena block, so no run shows its checksum or data)
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
     * Partially ported from src/bmsave-xmap.c: LoadAndVerfySuspendSave
     *
     * Does nothing unless [isValidSuspendSave] finds a suspend save. Then it reads the
     * `chapterIndex` (byte 0x0E) and the `unk_2C_0D` bit field (bits 13..22 of the word at 0x2C)
     * of the saved `PlaySt`, as `ReadSuspendSavePlaySt` does, and for the extra-map chapter 0x7F
     * compares it with `unk0F` of the extra-map save head at SRAM 0x7400, invalidating the
     * suspend save on a mismatch. The C code reads the whole `PlaySt` into a local and uses only
     * those two fields, so the non-extra-map path is complete: on the owner's chapter 2 save
     * (`sram_chapter2_boot`) the suspend save is kept and SRAM and `gSuspendSaveIdOffset` match
     * the dumps. It stays untagged because the extra-map branch (chapter 0x7F, including
     * `gExtraMapSaveHead`) is transcribed but no reference run has an extra-map suspend save.
     */
    public fun loadAndVerifySuspendSave() {
        if (!isValidSuspendSave(L.SAVE_ID_SUSPEND)) return
        val base = getSaveReadAddr(L.SAVE_ID_SUSPEND + suspendSaveIdOffset)
        readSramFast(sram, base, playStBuffer, 0, PLAY_ST_READ_SIZE)
        if (playStBuffer[PLAY_ST_CHAPTER_INDEX].toInt() == 0x7F) {
            readSramFast(sram, L.XMAP_OFFSET, xmapHeadBuffer, 0, XMAP_HEAD_READ_SIZE)
            val unk2C0D = (u32(playStBuffer, PLAY_ST_BITS_2C) ushr 13) and 0x3FF
            if (unk2C0D != (xmapHeadBuffer[XMAP_HEAD_UNK0F].toInt() and 0xFF)) {
                invalidateSuspendSave(L.SAVE_ID_SUSPEND)
            }
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

    /**
     * Transcribed from src/bmsave.c: WriteLastGameSaveId (not verified: the reference image
     * already holds the written id, so a no-op survives)
     */
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

    /**
     * Transcribed from src/bmsave.c: ReadGameSavePlaySt (not verified: no test checks it against a
     *   trace yet)
     *
     * Decodes the `PlaySt` at the start of the block [slot] reads from into [buf].
     */
    public fun readGameSavePlaySt(slot: Int, buf: PlaySt) {
        readSramFast(sram, getSaveReadAddr(slot), playStFull, 0, PlaySt.SIZE)
        buf.decode(playStFull, 0)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: IsPlaythroughIdUnique (verified by M5-32's full-image
     * check; not yet)
     *
     * False when [index] is among the global info's cleared playthroughs or the playthrough id
     * of a valid game save slot.
     */
    public fun isPlaythroughIdUnique(index: Int): Boolean {
        readGlobalSaveInfo(infoBuffer)
        for (i in 0 until CLEARED_PLAYTHROUGHS) {
            if ((infoBuffer[L.HEADER_CLEARED_PLAYTHROUGHS + i].toInt() and 0xFF) == index) {
                return false
            }
        }
        for (i in 0 until 3) {
            if (!isSaveValid(i)) continue
            readGameSavePlaySt(i, scratchPlaySt)
            if (scratchPlaySt.playthroughIdentifier == index) return false
        }
        return true
    }

    /**
     * Transcribed from src/bmsave-bwl.c: GetNewPlaythroughId (verified by M5-32's full-image
     * check; not yet)
     *
     * The smallest id in 1..0xFF that [isPlaythroughIdUnique] (the C code falls off the end
     * without a value when none is; 0 here).
     */
    public fun getNewPlaythroughId(): Int {
        for (i in 1 until 0x100) if (isPlaythroughIdUnique(i)) return i
        return 0
    }

    /**
     * Transcribed from src/bmsave-bwl.c: GetGlobalCompletionCntByInfo (not verified: no test checks
     *   it against a trace yet)
     *
     * Number of non-zero `cleared_playthroughs` entries of the global info [info].
     */
    public fun getGlobalCompletionCntByInfo(info: ByteArray): Int {
        var ret = 0
        for (i in 0 until CLEARED_PLAYTHROUGHS) {
            if (info[L.HEADER_CLEARED_PLAYTHROUGHS + i].toInt() != 0) ret++
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: GetGlobalCompletionCount (verified by M5-32's full-image
     * check; not yet)
     *
     * 0 when the global save info is invalid, else [getGlobalCompletionCntByInfo] of it.
     */
    public fun getGlobalCompletionCount(): Int {
        if (!readGlobalSaveInfo(infoBuffer)) return 0
        return getGlobalCompletionCntByInfo(infoBuffer)
    }

    /**
     * Transcribed from src/bmsave-lib.c: WriteSupplyItems (verified by M5-32's full-image check;
     * not yet)
     *
     * Packs the 100 convoy [items] (u16 each: item id in the low byte, uses in the high byte)
     * into the 0xB0-byte supply block at SRAM [destOffset]: the ids in bytes 0..99, then the
     * 6-bit uses back to back from byte 100. The C `buf` is an uninitialised stack array whose
     * unwritten bits are masked, not cleared; here it starts as zeros.
     */
    public fun writeSupplyItems(destOffset: Int, items: IntArray) {
        val buf = supplyBuffer
        buf.fill(0)
        var cur = CONVOY_ITEM_COUNT
        var var1 = 0
        for (i in 0 until CONVOY_ITEM_COUNT) {
            buf[i] = items[i].toByte()
            val itemUse = (items[i] shr 8) and 0x3F
            val var0 = var1 and 0x7
            buf[cur] = ((buf[cur].toInt() and SUPPLY_MASK1[var0]) or (itemUse shl var0)).toByte()
            if (var0 > 1) {
                cur++
                if (var0 > 2) {
                    buf[cur] = ((buf[cur].toInt() and SUPPLY_MASK2[var0]) or
                        (itemUse shr (8 - var0))).toByte()
                }
            }
            var1 += 6
        }
        writeAndVerifySramFast(buf, 0, sram, destOffset, SUPPLY_SIZE)
    }

    /**
     * Stand-in for src/bmcontainer.c: ClearSupplyItems (zeroes the 100 convoy items) over a raw
     * little-endian u16 array [convoy] (verified by M5-32's full-image check; not yet).
     */
    public fun clearSupplyItems(convoy: ByteArray) {
        convoy.fill(0, 0, CONVOY_ITEM_COUNT * 2)
    }

    /**
     * Stand-in for src/bmunit.c: InitUnits over the raw unit array [units]: zeroes all of it. The
     * C code also stores each unit's `index`, which needs the `Unit` layout and is not done here.
     */
    public fun initUnits(units: ByteArray) {
        units.fill(0)
    }

    /**
     * Transcribed from src/bmsave-gmap.c: ClearWorldMapStuff (not verified: NewGameSaveReferenceTest
     * covers its write but no break-check has been run on it yet).
     *
     * Writes a zeroed `GMapSaveInfo` (0x24 bytes) with `unk_1c_2` = 1 and `unk_1c_3` = 3 (byte
     * 0x1C = 0x0E) to SRAM [destOffset].
     */
    public fun clearWorldMapStuff(destOffset: Int) {
        gmapBuffer.fill(0)
        gmapBuffer[GMAP_BITS_1C] = 0x0E
        writeAndVerifySramFast(gmapBuffer, 0, sram, destOffset, GMAP_SAVE_INFO_SIZE)
    }

    /**
     * Transcribed from src/bmsave.c: SetBonusContentClaimFlags (not verified: no trace sets a
     * non-zero value).
     */
    public fun setBonusContentClaimFlags(num: Int) {
        bonusContentClaimFlags = num
    }

    /**
     * Ported from src/bmsave-lib.c: WritePermanentFlags
     *
     * Writes the 0x19 bytes of [permanentFlags] (`gPermanentFlagBits`) to SRAM [destOffset].
     */
    public fun writePermanentFlags(destOffset: Int, permanentFlags: ByteArray) {
        writeAndVerifySramFast(permanentFlags, 0, sram, destOffset, PERMANENT_FLAGS_SIZE)
    }


    /** `gPidStatsData` (0x46 entries of 16 bytes). */
    public val pidStatsData: ByteArray = ByteArray(PID_STATS_SIZE)

    /** `gChapterStats` (0x30 entries of 4 bytes). */
    public val chapterStatsData: ByteArray = ByteArray(CHAPTER_STATS_SIZE)

    /** `gPidStatsSaveLoc` as an SRAM offset (-1 = NULL). */
    public var pidStatsSaveLoc: Int = -1
        private set

    /**
     * Ported from src/bmsave-bwl.c: WritePidStats
     *
     * Writes `gPidStatsData` to SRAM [destOffset] and remembers it as `gPidStatsSaveLoc`.
     */
    public fun writePidStats(destOffset: Int) {
        writeAndVerifySramFast(pidStatsData, 0, sram, destOffset, PID_STATS_SIZE)
        pidStatsSaveLoc = destOffset
    }

    /**
     * Ported from src/bmsave-bwl.c: WriteChapterStats
     *
     * Writes `gChapterStats` to SRAM [destOffset].
     */
    public fun writeChapterStats(destOffset: Int) {
        writeAndVerifySramFast(chapterStatsData, 0, sram, destOffset, CHAPTER_STATS_SIZE)
    }

    /**
     * Ported from src/bmsave-lib.c: WriteChapterFlags
     *
     * Writes the [Fe8EventFlags.getChapterFlagBitsSize] (5) bytes of `gChapterFlagBits`
     * ([chapterFlags]) to SRAM [destOffset].
     */
    public fun writeChapterFlags(destOffset: Int, chapterFlags: ByteArray) {
        writeAndVerifySramFast(
            chapterFlags, 0, sram, destOffset, Fe8EventFlags.getChapterFlagBitsSize(),
        )
    }

    /**
     * Ported from src/bmsave-bwl.c: ReadPidStats
     *
     * Reads `gPidStatsData` from SRAM [srcOffset] and remembers it as `gPidStatsSaveLoc`.
     */
    public fun readPidStats(srcOffset: Int) {
        readSramFast(sram, srcOffset, pidStatsData, 0, PID_STATS_SIZE)
        pidStatsSaveLoc = srcOffset
    }

    /**
     * Ported from src/bmsave-bwl.c: ReadChapterStats
     *
     * Reads `gChapterStats` from SRAM [srcOffset].
     */
    public fun readChapterStats(srcOffset: Int) {
        readSramFast(sram, srcOffset, chapterStatsData, 0, CHAPTER_STATS_SIZE)
    }

    /**
     * Ported from src/bmsave-lib.c: ReadPermanentFlags
     *
     * Reads the 0x19 saved bytes of `gPermanentFlagBits` from SRAM [srcOffset] into [dest].
     */
    public fun readPermanentFlags(srcOffset: Int, dest: ByteArray) {
        readSramFast(sram, srcOffset, dest, 0, Fe8EventFlags.getPermanentFlagBitsSize())
    }

    /**
     * Ported from src/bmsave-lib.c: ReadChapterFlags
     *
     * Reads the 5 saved bytes of `gChapterFlagBits` from SRAM [srcOffset] into [dest].
     */
    public fun readChapterFlags(srcOffset: Int, dest: ByteArray) {
        readSramFast(sram, srcOffset, dest, 0, Fe8EventFlags.getChapterFlagBitsSize())
    }

    /**
     * Ported from src/bmsave-lib.c: ReadSupplyItems
     *
     * Unpacks the 0xB0-byte supply block at SRAM [srcOffset] (see [writeSupplyItems]) into the
     * 100 raw little-endian u16 convoy items of [convoy].
     */
    public fun readSupplyItems(srcOffset: Int, convoy: ByteArray) {
        val buf = supplyBuffer
        readSramFast(sram, srcOffset, buf, 0, SUPPLY_SIZE)
        var cur = CONVOY_ITEM_COUNT
        var var1 = 0
        for (i in 0 until CONVOY_ITEM_COUNT) {
            val var0 = var1 and 0x7
            var itemUse = ((buf[cur].toInt() and SUPPLY_MASK1[var0].inv()) and 0xFF) shr var0
            if (var0 > 1) {
                cur++
                if (var0 > 2) {
                    itemUse = (itemUse or
                        ((buf[cur].toInt() and SUPPLY_MASK2[var0].inv()) shl (8 - var0))) and 0xFF
                }
            }
            putU16(convoy, i * 2, (buf[i].toInt() and 0xFF) or (itemUse shl 8))
            var1 += 6
        }
    }

    /**
     * Transcribed from src/bmsave.c: LoadSavedBonusClaimFlags (not verified: the claim flags are
     * zero in every reference save)
     *
     * `bonusClaimFlags` of the game block of [slot].
     */
    public fun loadSavedBonusClaimFlags(slot: Int): Int {
        val b = scratchInfo
        readSramFast(sram, getSaveReadAddr(slot) + GAME_BONUS_CLAIM, b, 0, 4)
        return u32(b, 0)
    }

    /**
     * Transcribed from src/bmsave.c: WriteBonusContentClaimFlags (not verified: the New Game
     * trace copies only zeros).
     *
     * Writes `gBonusContentClaimFlags` to the game block at SRAM [blockOffset].
     */
    public fun writeBonusContentClaimFlags(blockOffset: Int) {
        val b = scratchInfo
        putU32(b, 0, bonusContentClaimFlags)
        writeAndVerifySramFast(b, 0, sram, blockOffset + GAME_BONUS_CLAIM, 4)
    }

    /**
     * Ported from src/bmsave-bwl.c: ClearPidChStatsSaveData
     *
     * Zeroes `gPidStatsData` and `gChapterStats`, sets `favval` (0x2000) of every unit entry
     * and writes entry 0 (the C code writes `gPidStatsData`, not `gPidStatsData[i]`) to each of
     * the 0x46 pid stats slots, then the first chapter stats entry to each of the 0x30 slots, of
     * the game block at SRAM [blockOffset].
     */
    public fun clearPidChStatsSaveData(blockOffset: Int) {
        pidStatsData.fill(0)
        chapterStatsData.fill(0)
        for (i in 0 until BWL_ARRAY_NUM) {
            // favval: u16 at bit 8 of the packed entry.
            pidStatsData[i * PID_STATS_ENTRY + 1] = 0
            pidStatsData[i * PID_STATS_ENTRY + 2] = 0x20
            writeAndVerifySramFast(
                pidStatsData, 0, sram, blockOffset + GAME_PID_STATS + i * PID_STATS_ENTRY,
                PID_STATS_ENTRY,
            )
        }
        for (i in 0 until WIN_ARRAY_NUM) {
            writeAndVerifySramFast(
                chapterStatsData, 0, sram, blockOffset + GAME_CHAPTER_STATS + i * CHAPTER_ENTRY,
                CHAPTER_ENTRY,
            )
        }
        pidStatsSaveLoc = blockOffset + GAME_PID_STATS
    }

    /**
     * Ported from src/bmsave.c: WriteNewGameSave
     *
     * Initialises a fresh game in [ram] and writes it to game slot [index]. [mode] 0 keeps
     * `chapterModeIndex`; a negative [isTutorial] keeps `config.controller`. The C calls to
     * `SetGameTime`, `InitUnits` and `ClearSupplyItems` act on [ram] ([NewGameRam.gameClock],
     * [NewGameRam.initUnits], [NewGameRam.convoy]); the unit array is not modelled here.
     */
    public fun writeNewGameSave(
        ram: NewGameRam,
        index: Int,
        isDifficult: Boolean,
        mode: Int,
        isTutorial: Int,
    ) {
        val playSt = ram.playSt
        val dest = getSaveWriteAddr(index)
        val newMode = if (mode == 0) playSt.chapterModeIndex else mode
        val tutorial = if (isTutorial < 0) playSt.controller else isTutorial

        ram.gameClock = 0
        initPlayConfig(playSt, isDifficult, tutorial)
        ram.initUnits()
        clearSupplyItems(ram.convoy)
        ram.flags.resetPermanentFlags()
        invalidateSuspendSave(L.SAVE_ID_SUSPEND)

        playSt.unk2C1 = 0
        playSt.unk2C04 = 0
        playSt.totalGold = 0
        playSt.unk30Unk400 = 0
        playSt.unk30Unk414 = 0
        playSt.unk30Unk81 = 0
        playSt.unk30Unk82 = 0
        playSt.unk30Unk83 = 0
        playSt.unk30UnkC00 = 0
        playSt.combatRank = 0
        playSt.expRank = 0
        playSt.unk30Unk3D04 = 0
        playSt.fundsRank = 0
        playSt.tacticsRank = 0
        playSt.survivalRank = 0
        playSt.unk30UnkF00 = 0
        playSt.unk2C0D = 0
        playSt.chapterModeIndex = newMode
        playSt.unk2B00 = 1
        playSt.playerName[0] = 0
        playSt.chapterIndex = 0
        playSt.playthroughIdentifier = getNewPlaythroughId()
        playSt.gameSaveSlot = index
        playSt.unk2C2 = getGlobalCompletionCount()

        playSt.encode(playStFull, 0)
        writeAndVerifySramFast(playStFull, 0, sram, dest, PlaySt.SIZE)
        setBonusContentClaimFlags(0)
        writeBonusContentClaimFlags(dest)

        val unit = unitBuffer
        unit.fill(0)
        for (i in 0 until UNIT_SAVE_AMOUNT_BLUE) {
            writeAndVerifySramFast(
                unit, 0, sram, dest + GAME_UNITS + i * PACKED_UNIT_SIZE, PACKED_UNIT_SIZE,
            )
        }
        writeAndVerifySramFast(unit, 0, sram, dest + GAME_GM_UNIT, PACKED_UNIT_SIZE)

        for (i in 0 until CONVOY_ITEM_COUNT) ram.supplyScratch[i] = u16(ram.convoy, i * 2)
        writeSupplyItems(dest + GAME_SUPPLY, ram.supplyScratch)
        clearPidChStatsSaveData(dest)
        writePermanentFlags(
            dest + GAME_PERMANENT_FLAGS,
            Fe8EventFlags.getPermanentFlagBits(ram.flags),
        )
        clearWorldMapStuff(dest + GAME_WM_STUFF)

        unit.fill(0)
        writeAndVerifySramFast(unit, 0, sram, dest + GAME_DUNGEONS, DUNGEONS_SIZE)

        scratchInfo.fill(0)
        putU32(scratchInfo, L.INFO_MAGIC32, L.SAVEMAGIC32)
        scratchInfo[L.INFO_KIND] = L.SAVEBLOCK_KIND_GAME.toByte()
        writeSaveBlockInfo(scratchInfo, index)
        writeLastGameSaveId(index)
    }

    /** Stand-in for the `struct GameSavePackedUnit unitp` / `Dungeon dungeon[2]` locals. */
    private val unitBuffer = ByteArray(PACKED_UNIT_SIZE.coerceAtLeast(DUNGEONS_SIZE))

    /**
     * Shared helper for loading and verifying checksummed auxiliary save records.
     *
     * @param offset SRAM byte offset of the record.
     * @param size Total record size in bytes.
     * @param checksumAt Offset within the record where the stored 16-bit checksum is located.
     * @param sumSize Number of bytes at the start of the record covered by the checksum.
     * @param buf Optional destination buffer (uses [genericBuffer] if null).
     * @return `true` if SRAM is functional and the checksum matches, `false` otherwise.
     */
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

    /**
     * Shared helper for populating the 16-bit additive checksum and committing an auxiliary record to SRAM.
     *
     * @param buf Source buffer containing record data.
     * @param offset SRAM destination byte offset.
     * @param size Total record size in bytes.
     * @param checksumAt Offset within the record where the 16-bit checksum is written.
     * @param sumSize Number of bytes covered by the checksum calculation.
     */
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

    /** Layout constants, bit masks, and buffer size definitions for SRAM blocks. */
    internal companion object {
        /** `gGenericBuffer` is 0x2000 bytes in C; the largest block used here is 0x144. */
        const val GENERIC_BUFFER_SIZE = L.SUSPEND_SIZE

        /** Bytes `WipeSram` writes per call (0x200 calls cover the 0x8000 bytes). */
        const val WIPE_CHUNK = 0x40

        /** `CONVOY_ITEM_COUNT`. */
        const val CONVOY_ITEM_COUNT = 100

        /** `GAMESAVE_SIZE_SUPPLY`. */
        const val SUPPLY_SIZE = 0xB0

        /** `GetPermanentFlagBitsSize()`. */
        const val PERMANENT_FLAGS_SIZE = 0x19

        /** `sizeof(struct GMapSaveInfo)`: [Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE]. */
        const val GMAP_SAVE_INFO_SIZE = Fe8SuspendLayout.GMAP_SAVE_INFO_SIZE

        /** Offset of the `unk_1c_*` bit field byte in `GMapSaveInfo`. */
        const val GMAP_BITS_1C = 0x1C

        /** `sConvySavePackMask1`. */
        val SUPPLY_MASK1 = intArrayOf(0xC0, 0x81, 0x03, 0x07, 0x0F, 0x1F, 0x3F, 0x7F)

        /** `sConvySavePackMask2`. */
        val SUPPLY_MASK2 = intArrayOf(0x00, 0x00, 0x00, 0xF7, 0xFC, 0xF8, 0xF0, 0xE0)

        /** `BWL_ARRAY_NUM`. */
        const val BWL_ARRAY_NUM = 0x46

        /** `WIN_ARRAY_NUM`. */
        const val WIN_ARRAY_NUM = 0x30

        /** `sizeof(struct UnitUsageStats)` (0x460 / 0x46). */
        const val PID_STATS_ENTRY = 16

        /** `sizeof(gPidStatsData)`. */
        const val PID_STATS_SIZE = BWL_ARRAY_NUM * PID_STATS_ENTRY

        /** `sizeof(struct ChapterStats)`. */
        const val CHAPTER_ENTRY = 4

        /** `sizeof(gChapterStats)`. */
        const val CHAPTER_STATS_SIZE = WIN_ARRAY_NUM * CHAPTER_ENTRY

        /** `UNIT_SAVE_AMOUNT_BLUE`. */
        const val UNIT_SAVE_AMOUNT_BLUE = 51

        /** `sizeof(struct GameSavePackedUnit)`. */
        const val PACKED_UNIT_SIZE = 0x24

        /** `sizeof(struct Dungeon[2])`. */
        const val DUNGEONS_SIZE = 0x18

        /** Offsets into `struct GameSaveBlock`. */
        const val GAME_UNITS = 0x4C
        const val GAME_GM_UNIT = GAME_UNITS + UNIT_SAVE_AMOUNT_BLUE * PACKED_UNIT_SIZE
        const val GAME_SUPPLY = GAME_GM_UNIT + PACKED_UNIT_SIZE
        const val GAME_PID_STATS = GAME_SUPPLY + SUPPLY_SIZE
        const val GAME_CHAPTER_STATS = GAME_PID_STATS + PID_STATS_SIZE
        const val GAME_PERMANENT_FLAGS = GAME_CHAPTER_STATS + CHAPTER_STATS_SIZE
        const val GAME_BONUS_CLAIM = GAME_PERMANENT_FLAGS + 0x1C
        const val GAME_WM_STUFF = GAME_BONUS_CLAIM + 4
        const val GAME_DUNGEONS = GAME_WM_STUFF + GMAP_SAVE_INFO_SIZE

        /** `MAX_SAVED_GAME_CLEARS`. */
        const val CLEARED_PLAYTHROUGHS = 12

        /** Bytes of `PlaySt` read by `loadAndVerifySuspendSave` (through `unk_2C_*`). */
        const val PLAY_ST_READ_SIZE = 0x30

        /** Offset of `PlaySt.chapterIndex`. */
        const val PLAY_ST_CHAPTER_INDEX = 0x0E

        /** Offset of the word holding `PlaySt.unk_2C_0D` (bits 13..22). */
        const val PLAY_ST_BITS_2C = 0x2C

        /** Bytes of `ExtraMapSaveHead` read (through `unk0F`). */
        const val XMAP_HEAD_READ_SIZE = 0x10

        /** Offset of `ExtraMapSaveHead.unk0F`. */
        const val XMAP_HEAD_UNK0F = 0x0F

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

        /** Ported from src/bmlib.c: StringCompare (as used on the header marker; true = equal). */
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
