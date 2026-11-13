/**
 * The save RAM layer under the Save / Resume / Extras menus: whether the cartridge SRAM works, the
 * global save header, the block descriptors that say which save slots are valid, the small
 * checksummed blocks (rankings, sound room, bonus claim) and the boot-time repair of all of them.
 * Ported from `src/bmsave-lib.c`, with the descriptor and suspend-slot helpers of `src/bmsave.c`.
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
 * The save RAM routines of src/bmsave-lib.c and the descriptor helpers of src/bmsave.c, working on
 * a [CartSram] instead of the memory mapped at 0x0E000000 (`gSram`). The slots the player sees
 * (three chapter saves, one suspend save kept in two alternating copies) are each valid only if
 * their `struct SaveBlockInfo` descriptor and its payload checksum are intact; a slot that fails
 * shows as empty. `struct GlobalSaveInfo` and the small blocks are handled as raw little-endian
 * bytes at the [Fe8SramLayout] offsets. The C code's `gGenericBuffer` and stack locals are
 * replaced by buffers allocated once here, so no call allocates.
 *
 * At start-up src/main.c `AgbMain` runs `SramInit`, ..., `EraseInvalidSaveData`,
 * `EraseSramDataIfInvalid`; see [boot]. The foreign-magic conversion branch of
 * `EraseInvalidSaveData` (src/bmsave-misc.c) and `LoadAndVerfySuspendSave`
 * (src/bmsave-xmap.c) are also here.
 *
 * @property sram the save RAM this instance reads and writes (`gSram`).
 */
public class BmSaveLib(public val sram: CartSram) {
    /**
     * `gBoolSramWorking`: set by [sramInit] when the probe write read back correctly. While it is
     * false every load and validity check below reports "invalid", as if there were no save.
     */
    public var isSramWorkingFlag: Boolean = false
        private set

    /**
     * `gSuspendSaveIdOffset` (`gSaveSuBaseSlot`): which of the two suspend save copies
     * ([Fe8SramLayout.SAVE_ID_SUSPEND] + 0 or + 1) [isValidSuspendSave] last found valid; 0x7F
     * when neither was (there is nothing to resume).
     */
    public var suspendSaveIdOffset: Int = 0
        private set

    /**
     * Buffer for `gGenericBuffer` (0x2000 bytes in C): the aux block loaders/erasers and the block
     * checksum reads go through it. Sized as one suspend block, the largest block moved here.
     */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /**
     * Buffer for the `struct GlobalSaveInfo` locals (`local_info`, `info`, `header`) of the
     * header readers and writers.
     */
    private val infoBuffer = ByteArray(L.HEADER_SIZE)

    /**
     * Buffer for the `struct SaveBlockInfo tmp` local of `ReadSaveBlockInfo` (used when the caller
     * passes no buffer) and the `chunk` local of `EraseSaveBlockInfo`.
     */
    private val blockInfoTmp = ByteArray(L.BLOCK_INFO_SIZE)

    /** Buffer for the `struct SaveBlockInfo chunk` locals of `GetSaveReadAddr` and the like. */
    private val scratchInfo = ByteArray(L.BLOCK_INFO_SIZE)

    /**
     * Buffer for the `struct PlaySt playSt` local of `LoadAndVerfySuspendSave`; only the first
     * [PLAY_ST_READ_SIZE] bytes (through `unk_2C_*`) are read.
     */
    private val playStBuffer = ByteArray(PLAY_ST_READ_SIZE)

    /** Buffer for `gExtraMapSaveHead` (`struct ExtraMapSaveHead`, read up to `unk0F`). */
    private val xmapHeadBuffer = ByteArray(XMAP_HEAD_READ_SIZE)

    /** Buffer for the `struct PlaySt` bytes read from SRAM. */
    private val playStFull = ByteArray(PlaySt.SIZE)

    /** Buffer for the `struct PlaySt ram_ch` local of `IsPlaythroughIdUnique`. */
    private val scratchPlaySt = PlaySt()

    /**
     * `gBonusContentClaimFlags`: bit i is set once bonus claim entry i has been claimed (the
     * bonus claim screen shows a claimed entry as not claimable again). Each chapter save slot
     * keeps its own copy (`bonusClaimFlags`).
     */
    public var bonusContentClaimFlags: Int = 0
        private set

    /** Buffer for the `unsigned char buf[176]` of `WriteSupplyItems`. */
    private val supplyBuffer = ByteArray(SUPPLY_SIZE)

    /** Buffer for the `struct GMapSaveInfo info` of `ClearWorldMapStuff`. */
    private val gmapBuffer = ByteArray(GMAP_SAVE_INFO_SIZE)

    /**
     * The 0x40 bytes of 0xFF (`u32 buf[0x10]` set to 0xFFFFFFFF) that `WipeSram` writes 0x200
     * times to blank the 0x8000 bytes of SRAM.
     */
    private val wipeBuffer = ByteArray(WIPE_CHUNK)

    /** Buffer for the `u32 buf[2]` of `SramInit`: the probe word and its read-back. */
    private val probeBuffer = ByteArray(8)

    /** The EWRAM copy (`EWRAM_ENTRY`) `EraseInvalidSaveData` reads the whole SRAM into. */
    private val ewramCopy = ByteArray(L.SRAM_SIZE)

    /**
     * Runs the save RAM part of `AgbMain` (src/main.c) in its original order: [sramInit], then
     * [eraseInvalidSaveData], then [eraseSramDataIfInvalid]. (Between them the C code starts
     * procs, the sprite system and the RNG, none of which touches SRAM.) Afterwards the title
     * screen sees either the player's saves or, if the cartridge was new or corrupt, empty slots.
     */
    public fun boot() {
        sramInit()
        eraseInvalidSaveData()
        eraseSramDataIfInvalid()
    }

    /**
     * Ported from src/bmsave-lib.c: SramInit
     *
     * Writes the probe word 0x12345678 to the 4 `reserved` bytes at
     * [Fe8SramLayout.RESERVED_OFFSET], reads it back and sets the working flag when it matches
     * (a failing cartridge makes every save check report "invalid"). `SetSramFastFunc` (copying
     * the SRAM routines to WRAM) and the `REG_IE |= INTR_FLAG_GAMEPAK` write (enabling the game
     * pak interrupt) have no equivalent here. The second probe word 0x87654321 only seeds the
     * read-back target. The probe stays in SRAM until a [wipeSram] erases it.
     */
    public fun sramInit() {
        putU32(probeBuffer, 0, PROBE_WORD0)
        putU32(probeBuffer, 4, PROBE_WORD1)
        writeSramFast(probeBuffer, 0, sram, L.RESERVED_OFFSET, L.RESERVED_SIZE)
        readSramFast(sram, L.RESERVED_OFFSET, probeBuffer, 4, L.RESERVED_SIZE)
        isSramWorkingFlag = u32(probeBuffer, 4) == u32(probeBuffer, 0)
    }

    /** Ported from src/bmsave-lib.c: IsSramWorking. The result of the last [sramInit] probe. */
    public fun isSramWorking(): Boolean = isSramWorkingFlag

    /**
     * Ported from src/bmsave-lib.c: WipeSram
     *
     * Overwrites all 0x8000 bytes of SRAM with 0xFF, in 0x200 verified writes of 0x40 bytes. All
     * saves are lost: every descriptor now reads as invalid, so every slot shows as empty. Only
     * [initGlobalSaveInfodata] calls it.
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
     * Reads the 0x64-byte `struct GlobalSaveInfo` into [buf] (a private scratch buffer when null,
     * as the C code uses a local) and returns true when it is valid: SRAM works, the marker
     * string is "AGB-FE9" (`sSaveMarker`), `magic32` is `SAVEMAGIC32`, `magic16` is
     * `SAVEMAGIC16` and the `Checksum16` over the first `GLOBALSIZEINFO_SIZE_FOR_CHECKSUM` = 0x50
     * bytes equals `checksum` (0x60). When it is not, [eraseSramDataIfInvalid] starts the
     * cartridge afresh. [buf] is filled even when the result is false.
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
     * Stores the `Checksum16` of the first 0x50 bytes of [header] into its `checksum` field
     * (0x60), then writes the 0x64 bytes to SRAM with verification.
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
     * Fine for the two bytes that follow the checksum (`last_game_save_id`, `last_suspend_slot`,
     * at 0x62 and 0x63): they lie outside the 0x50 checksummed bytes.
     */
    public fun writeGlobalSaveInfoNoChecksum(header: ByteArray) {
        writeAndVerifySramFast(header, 0, sram, L.HEADER_OFFSET, L.HEADER_SIZE)
    }

    /**
     * Ported from src/bmsave-lib.c: InitGlobalSaveInfodata
     *
     * Wipes SRAM, then writes a fresh global save info: the marker, both magics, every flag and
     * counter 0 (no playthrough cleared, no support record, no character known, and both
     * `last_suspend_slot` and `last_game_save_id` 0), and its checksum. This is the "new
     * cartridge" state; all earlier saves are gone.
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

    /**
     * Ported from src/bmsave-lib.c: EraseBonusContentData
     *
     * Zeroes the 0x144-byte bonus claim block and saves it with its checksum. All 16 entries
     * are empty, so the bonus claim screen has nothing to offer.
     */
    public fun eraseBonusContentData() {
        genericBuffer.fill(0, 0, L.BONUS_SIZE)
        saveBonusContentData(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadBonusContentData
     *
     * Reads the bonus claim block (`struct BonusClaimSaveData`, 0x144 bytes: 16 `BonusClaimEnt`
     * of 0x14 bytes, then `cksum16`) into [buf] (scratch when null); true when SRAM works and the
     * `Checksum16` over the 16 entries (0x140 bytes) matches `cksum16` at 0x140. When false,
     * [eraseSramDataIfInvalid] clears the block. Whether the Extras menu offers the bonus claim
     * screen depends on the entries read here (`IsExtraBonusClaimEnabled`).
     */
    public fun loadBonusContentData(buf: ByteArray? = null): Boolean =
        loadChecked(L.BONUS_OFFSET, L.BONUS_SIZE, L.BONUS_CHECKSUM, L.BONUS_CHECKSUM, buf)

    /**
     * Transcribed from src/bmsave-lib.c: SaveBonusContentData (not verified: every reference
     * run holds an all-zero bonus block, so no run shows its checksum or data)
     *
     * Stores the `Checksum16` of the first 0x140 bytes of [buf] in `cksum16` (0x140) and writes
     * the block.
     */
    public fun saveBonusContentData(buf: ByteArray) {
        writeChecked(buf, L.BONUS_OFFSET, L.BONUS_SIZE, L.BONUS_CHECKSUM, L.BONUS_CHECKSUM)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadAndVerfyRankData
     *
     * Reads the ranking block (`struct GameRankSaveDataPacks`, 0x94 bytes) into [buf] (scratch
     * when null); true when SRAM works and the `Checksum16` over the six `GameRankSaveData`
     * records (0x90 bytes) matches `magic0` at 0x90. The records hold the best end-of-game
     * rank per chapter mode and difficulty (`pack[chapter_mode + difficulty * 3]` in
     * `SaveNewRankData`).
     */
    public fun loadAndVerifyRankData(buf: ByteArray? = null): Boolean =
        loadChecked(L.RANK_OFFSET, L.RANK_SIZE, L.RANK_CHECKSUM, L.RANK_SUM_SIZE, buf)

    /**
     * Transcribed from src/bmsave-lib.c: SaveRankings (not verified: every reference run holds
     * an all-zero ranking block, so no run shows its checksum or data)
     *
     * Stores the `Checksum16` of the first 0x90 bytes of [buf] in `magic0` (0x90) and writes the
     * block.
     */
    public fun saveRankings(buf: ByteArray) {
        writeChecked(buf, L.RANK_OFFSET, L.RANK_SIZE, L.RANK_CHECKSUM, L.RANK_SUM_SIZE)
    }

    /**
     * Ported from src/bmsave-lib.c: EraseSaveRankData
     *
     * Zeroes the ranking block and saves it with its checksum. Every record has `valid` 0, which
     * `JudgeGameRankSaveData` treats as "no record yet", so the next clear is stored.
     */
    public fun eraseSaveRankData() {
        genericBuffer.fill(0, 0, L.RANK_SIZE)
        saveRankings(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadAndVerifySoundRoomData
     *
     * Reads the sound room block (`struct SoundRoomSaveData`, 0x24 bytes) into [buf] (scratch
     * when null); true when SRAM works and the `Checksum16` over the 8 unlock flag words (0x20
     * bytes, one bit per song, `flags[song >> 5]` bit `song & 31`) matches `magic1` at 0x20.
     */
    public fun loadAndVerifySoundRoomData(buf: ByteArray? = null): Boolean = loadChecked(
        L.SOUND_ROOM_OFFSET, L.SOUND_ROOM_SIZE, L.SOUND_ROOM_CHECKSUM, L.SOUND_ROOM_CHECKSUM, buf,
    )

    /**
     * Ported from src/bmsave-lib.c: WriteSoundRoomSaveData
     *
     * Stores the `Checksum16` of the first 0x20 bytes of [buf] in `magic1` (0x20) and writes the
     * block.
     */
    public fun writeSoundRoomSaveData(buf: ByteArray) {
        writeChecked(
            buf, L.SOUND_ROOM_OFFSET, L.SOUND_ROOM_SIZE, L.SOUND_ROOM_CHECKSUM,
            L.SOUND_ROOM_CHECKSUM,
        )
    }

    /**
     * Ported from src/bmsave-lib.c: EraseSoundRoomSaveData
     *
     * Zeroes the sound room block and saves it with its checksum: no song is unlocked.
     */
    public fun eraseSoundRoomSaveData() {
        genericBuffer.fill(0, 0, L.SOUND_ROOM_SIZE)
        writeSoundRoomSaveData(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: LoadAndVerfyLinkArenaStruct2
     *
     * Reads `struct bmsave_unkstruct2` (`gSram->unkstruct2`, 0x14 bytes: 4 flag words, `magic1`,
     * `magic2`) into [buf] (scratch when null); true when SRAM works and the `Checksum16` over
     * the first 0x10 bytes matches `magic1` at 0x10. Despite the "LinkArena" in the function
     * names, the decomp does not say what the flags mean (a comment in
     * `EraseInvalidSaveData` guesses "flags for viewing CGs?"); this is not the Link Arena
     * save block itself ([Fe8SramLayout.ARENA_OFFSET]).
     */
    public fun loadAndVerifyLinkArenaStruct2(buf: ByteArray? = null): Boolean = loadChecked(
        L.LINK_ARENA2_OFFSET, L.LINK_ARENA2_SIZE, L.LINK_ARENA2_CHECKSUM, L.LINK_ARENA2_CHECKSUM,
        buf,
    )

    /**
     * Transcribed from src/bmsave-lib.c: WriteLinkArenaStruct2 (not verified: every reference
     * run holds an all-zero link arena block, so no run shows its checksum or data)
     *
     * Stores the `Checksum16` of the first 0x10 bytes of [buf] in `magic1` (0x10) and writes the
     * block.
     */
    public fun writeLinkArenaStruct2(buf: ByteArray) {
        writeChecked(
            buf, L.LINK_ARENA2_OFFSET, L.LINK_ARENA2_SIZE, L.LINK_ARENA2_CHECKSUM,
            L.LINK_ARENA2_CHECKSUM,
        )
    }

    /**
     * Ported from src/bmsave-lib.c: EraseLinkArenaStruct2
     *
     * Zeroes `bmsave_unkstruct2` (all four flag words clear) and saves it with its checksum.
     */
    public fun eraseLinkArenaStruct2() {
        genericBuffer.fill(0, 0, L.LINK_ARENA2_SIZE)
        writeLinkArenaStruct2(genericBuffer)
    }

    /**
     * Ported from src/bmsave-lib.c: EraseSramDataIfInvalid
     *
     * Repairs whatever failed its checksum at boot, in this order: the global save info (which
     * wipes all of SRAM first, so a bad header costs every save), then the bonus claim, ranking,
     * sound room and `bmsave_unkstruct2` blocks, each reset to empty on its own, and finally the
     * suspend save check ([loadAndVerifySuspendSave]). Chapter save slots and the suspend save are
     * not touched here: a damaged one simply fails its descriptor check and shows as empty.
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
     * branch missing)
     *
     * Reads the global save info; returns at once when its `magic32` is the FE8 magic
     * (`SAVEMAGIC32`, "FE8U's magic" in the decomp). Otherwise reads the whole SRAM and returns
     * unless the magic is 0x00040603 (the decomp asks "Which game holds magic32 = 0x040603?" and
     * does not know). That last case is a conversion: the C code rebuilds the header, both
     * suspend blocks, the three chapter saves (with `chapterIndex` shifted up at 0x04, 0x15 and
     * 0x22, i.e. a different chapter numbering), the ranking, sound room, `bmsave_unkstruct2` and
     * bonus claim blocks, and all seven block descriptors in the FE8 format. It is not ported.
     *
     * @throws NotImplementedError when SRAM holds a save with the foreign magic 0x00040603.
     */
    public fun eraseInvalidSaveData() {
        readSramFast(sram, L.HEADER_OFFSET, ewramCopy, 0, L.HEADER_SIZE)
        if (u32(ewramCopy, L.HEADER_MAGIC32) == L.SAVEMAGIC32) return
        readSramFast(sram, 0, ewramCopy, 0, L.SRAM_SIZE)
        if (u32(ewramCopy, L.HEADER_MAGIC32) != FOREIGN_MAGIC32) return
        TODO("EraseInvalidSaveData: erasing a save with magic32 0x00040603")
    }

    /**
     * Partially ported from src/bmsave-xmap.c: LoadAndVerfySuspendSave
     *
     * Does nothing unless [isValidSuspendSave] finds a suspend save. Then it reads the
     * `chapterIndex` (byte 0x0E) and the `unk_2C_0D` bit field (10 bits, bits 13..22 of the word
     * at 0x2C) of the saved `PlaySt`, as `ReadSuspendSavePlaySt` does. A chapter index of 0x7F
     * (`CHAPTER_7F`, which `GameCtrlStartExtraMap` sets for an extra map) means the suspend save
     * was made inside an extra map; its `unk_2C_0D` is then compared with `unk0F` (unknown
     * meaning) of the extra map save head at SRAM 0x7400, and a mismatch invalidates the suspend
     * save, so it can no longer be resumed. The C code reads the whole `PlaySt` into a local and
     * uses only those two fields, so the non-extra-map path is complete: on the owner's chapter 2
     * save (`sram_chapter2_boot`) the suspend save is kept and SRAM and `gSuspendSaveIdOffset`
     * match the dumps. It stays untagged because the extra-map branch (chapter 0x7F, including
     * `gExtraMapSaveHead`) is transcribed but no reference run has an extra-map suspend save.
     */
    public fun loadAndVerifySuspendSave() {
        if (!isValidSuspendSave(L.SAVE_ID_SUSPEND)) return
        val base = getSaveReadAddr(L.SAVE_ID_SUSPEND + suspendSaveIdOffset)
        readSramFast(sram, base, playStBuffer, 0, PLAY_ST_READ_SIZE)
        // 0x7F = CHAPTER_7F, the chapter index of an extra map.
        if (playStBuffer[PLAY_ST_CHAPTER_INDEX].toInt() == 0x7F) {
            readSramFast(sram, L.XMAP_OFFSET, xmapHeadBuffer, 0, XMAP_HEAD_READ_SIZE)
            // `unk_2C_0D`: a 10-bit field starting at bit 13.
            val unk2C0D = (u32(playStBuffer, PLAY_ST_BITS_2C) ushr 13) and 0x3FF
            if (unk2C0D != (xmapHeadBuffer[XMAP_HEAD_UNK0F].toInt() and 0xFF)) {
                invalidateSuspendSave(L.SAVE_ID_SUSPEND)
            }
        }
    }

    /**
     * Ported from src/bmsave.c: IsValidSuspendSave
     *
     * True when [slot] is [Fe8SramLayout.SAVE_ID_SUSPEND] and one of the two suspend block
     * descriptors is valid, trying the copy written last (`last_suspend_slot`) first and the
     * other as a fallback. So a suspend save whose newest copy was damaged (for instance by
     * power loss mid-write) resumes from the previous copy. Remembers the chosen copy in
     * [suspendSaveIdOffset] (0x7F when none is valid, meaning there is nothing to resume).
     * The `+ 3` is `SAVE_ID_SUSPEND`.
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
     * The suspend copy written last: 1 when the global save info's `last_suspend_slot` is 1, else
     * 0 (the info is read even when it is invalid, as in C; with non-working SRAM nothing is
     * read, where the C local would be uninitialised).
     */
    public fun getLastSuspendSaveId(): Int {
        readGlobalSaveInfo(infoBuffer)
        return if (infoBuffer[L.HEADER_LAST_SUSPEND_SLOT].toInt() == 1) 1 else 0
    }

    /**
     * Ported from src/bmsave.c: GetNextSuspendSaveId
     *
     * The suspend copy the next suspend writes to: the one not written last (`WriteSuspendSave`
     * adds it to `SAVE_ID_SUSPEND`). The older copy is overwritten, never the newest.
     */
    public fun getNextSuspendSaveId(): Int = 1 - getLastSuspendSaveId()

    /**
     * Ported from src/bmsave-lib.c: ReadSaveBlockInfo
     *
     * Reads the `struct SaveBlockInfo` descriptor of block [index] (a `SAVE_ID_*` value) into
     * [chunk] (scratch when null); true when its `magic16` is `SAVEMAGIC16`, its `magic32` is
     * the one for that block (`SAVEMAGIC32` for chapter and suspend saves, `SAVEMAGIC32_ARENA`,
     * `SAVEMAGIC32_XMAP`) and the checksum of the payload it points at matches
     * ([verifySaveBlockChecksum]). An unknown [index] is invalid. This is what decides whether a
     * Save slot is shown as filled or empty; a payload that no longer matches its checksum
     * counts as empty. [chunk] is filled even when the result is false.
     */
    public fun readSaveBlockInfo(chunk: ByteArray?, index: Int): Boolean {
        val info = chunk ?: blockInfoTmp
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
     * Completes the descriptor [chunk] (0x10 bytes; the caller has set `kind`, and for valid kinds
     * `magic32`) and writes it as block descriptor [index], which makes that block valid: sets
     * `magic16`, `offset` (the low 16 bits of the write address, which is the SRAM offset), `size`
     * by `kind` (`SAVEBLOCK_KIND_*`: game, suspend, arena or xmap block size) and `checksum32`
     * over the payload now in SRAM. For `SAVEBLOCK_KIND_INVALID` size, offset and `magic16` are
     * zeroed instead, so the slot reads as empty afterwards. Like the C code it returns without
     * writing for `index >= SAVE_ID_MAX` (7) or an unknown kind, after having already changed
     * `magic16` and `offset` in [chunk].
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

    /**
     * Ported from src/bmsave-lib.c: EraseSaveBlockInfo
     *
     * Overwrites descriptor [index] with 0xFF bytes (a no-op for `index >= SAVE_ID_MAX`). The
     * `magic16` no longer matches, so the block shows as empty.
     */
    public fun eraseSaveBlockInfo(index: Int) {
        if (index < L.BLOCK_INFO_COUNT) {
            blockInfoTmp.fill(0xFF.toByte())
            writeAndVerifySramFast(
                blockInfoTmp, 0, sram, L.blockInfoOffset(index), L.BLOCK_INFO_SIZE,
            )
        }
    }

    /**
     * Ported from src/bmsave-lib.c: GetSaveWriteAddr
     *
     * The fixed SRAM offset a new block [index] is written at: a chapter save slot, one of the
     * two suspend copies, the Link Arena block or the extra map area. 0 (NULL in C) for an
     * unknown index. The C returns a pointer; its low 16 bits are the offset.
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
     * The SRAM offset recorded in descriptor [index], i.e. where the payload was actually
     * written (read even when the descriptor is invalid).
     */
    public fun getSaveReadAddr(index: Int): Int {
        readSaveBlockInfo(scratchInfo, index)
        return u16(scratchInfo, L.INFO_OFFSET)
    }

    /**
     * Ported from src/bmsave.c: SramChecksum32
     *
     * Copies [size] bytes at SRAM [offset] into the generic buffer and returns their
     * `ComputeChecksum32` (halfword sum in the low half, halfword xor in the high half), the
     * value kept in a descriptor's `checksum32`. Overwrites [genericBuffer].
     */
    public fun sramChecksum32(offset: Int, size: Int): Int {
        readSramFast(sram, offset, genericBuffer, 0, size)
        return SaveChecksums.computeChecksum32(genericBuffer, 0, size)
    }

    /**
     * Ported from src/bmsave.c: VerifySaveBlockChecksum
     *
     * True when the descriptor [info]'s `checksum32` equals the checksum of the `size` bytes at
     * its `offset` in SRAM now; false means the payload changed or was damaged.
     */
    public fun verifySaveBlockChecksum(info: ByteArray): Boolean =
        u32(info, L.INFO_CHECKSUM32) ==
            sramChecksum32(u16(info, L.INFO_OFFSET), u16(info, L.INFO_SIZE))

    /**
     * Ported from src/bmsave.c: PopulateSaveBlockChecksum
     *
     * Stores the checksum of the payload [info] points at (`offset`, `size`) in its `checksum32`.
     */
    public fun populateSaveBlockChecksum(info: ByteArray) {
        putU32(info, L.INFO_CHECKSUM32, sramChecksum32(u16(info, L.INFO_OFFSET), u16(info, L.INFO_SIZE)))
    }

    /**
     * Ported from src/bmsave.c: IsSaveValid
     *
     * True when block [index] has a valid descriptor ([readSaveBlockInfo]). For
     * `SAVE_ID_GAME0..2` this is what makes the Save 1/2/3 slot shown as filled instead of empty.
     */
    public fun isSaveValid(index: Int): Boolean = readSaveBlockInfo(null, index)

    /**
     * Ported from src/bmsave.c: InvalidateSuspendSave
     *
     * Marks descriptor [slot] invalid (`SAVEBLOCK_KIND_INVALID`, which zeroes its size, offset
     * and `magic16`), and for [Fe8SramLayout.SAVE_ID_SUSPEND] also the alternate copy's, so
     * neither suspend copy can be resumed any more. The C `chunk` local is uninitialised apart
     * from `kind`; the unset fields are 0 here.
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
     *
     * Remembers [num] (a chapter save slot 0..2, not range-checked here) as the slot used last,
     * in `last_game_save_id` (0x62) of the global save info, without updating its checksum (the
     * byte is outside the checksummed part). The save menu starts on this slot
     * (`ReadLastGameSaveId` in `src/savemenu.c`).
     */
    public fun writeLastGameSaveId(num: Int) {
        readGlobalSaveInfo(infoBuffer)
        infoBuffer[L.HEADER_LAST_GAME_SAVE_ID] = num.toByte()
        writeGlobalSaveInfoNoChecksum(infoBuffer)
    }

    /**
     * Transcribed from src/bmsave.c: ReadLastGameSaveId (not verified: no trace reads it).
     *
     * The chapter save slot (0..2) used last, 0 when the global save info is invalid or the
     * stored value is outside 0..2. The C compares an unsigned byte, so its `ret < 0` test never
     * fires; the Kotlin sign extension makes 0x80..0xFF reach it, and both end at slot 0.
     */
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
     * Transcribed from src/bmsave-bwl.c: IsPlaythroughIdUnique (not verified: no @VerifiesPort
     * test names it)
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
     * Transcribed from src/bmsave-bwl.c: GetNewPlaythroughId (not verified: no @VerifiesPort
     * test names it)
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
     * Transcribed from src/bmsave-bwl.c: GetGlobalCompletionCount (not verified: no @VerifiesPort
     * test names it)
     *
     * 0 when the global save info is invalid, else [getGlobalCompletionCntByInfo] of it.
     */
    public fun getGlobalCompletionCount(): Int {
        if (!readGlobalSaveInfo(infoBuffer)) return 0
        return getGlobalCompletionCntByInfo(infoBuffer)
    }

    /**
     * Transcribed from src/bmsave-lib.c: WriteSupplyItems (not verified: no @VerifiesPort test
     * names it)
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
     * Raw-array form of src/bmcontainer.c: ClearSupplyItems (zeroes the 100 convoy items) over a
     * raw little-endian u16 array [convoy] (no @VerifiesPort test names it).
     */
    public fun clearSupplyItems(convoy: ByteArray) {
        convoy.fill(0, 0, CONVOY_ITEM_COUNT * 2)
    }

    /**
     * Raw-array form of src/bmunit.c: InitUnits over the raw unit array [units]: zeroes all of it.
     * The C code also stores each unit's `index`, which needs the `Unit` layout and is not done
     * here; the saves use `UnitTable.initUnits`.
     */
    public fun initUnits(units: ByteArray) {
        units.fill(0)
    }

    /**
     * Transcribed from src/bmsave-gmap.c: ClearWorldMapStuff (not verified:
     * NewGameSaveReferenceTest covers its write but no break-check has been run on it yet).
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

    /** Buffer for the `struct GameSavePackedUnit unitp` / `Dungeon dungeon[2]` locals. */
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
