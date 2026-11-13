/**
 * Where everything lives in FE8's 32 KiB battery-backed save RAM (mapped at 0x0E000000 on the
 * GBA): the global header, the block descriptor table, the two suspend slots, the three chapter
 * save slots, the Link Arena block, the small aux blocks (ranking, sound room, bonus claim) and
 * the extra map block, plus the magic numbers that mark a block valid.
 *
 * Offsets, sizes and magics come from `include/sram-layout.h` (the memory map enums) and
 * `include/bmsave.h` (`struct SaveBlocks` and the structs inside it).
 */

package fe8.game.save

/**
 * Layout of FE8's 32 KiB save RAM (`struct SaveBlocks`).
 *
 * All offsets are byte distances from the base of SRAM (GBA address `0x0E000000`); multi-byte
 * values are little endian. The block offsets are derived exactly as the `SRAM_OFFSET_*` enum
 * in `sram-layout.h` chains them, so the table at the start of that file is the reference.
 * Field offsets (`HEADER_*`, `INFO_*`) are the offsets of the members of `struct GlobalSaveInfo`
 * and `struct SaveBlockInfo`.
 */
public object Fe8SramLayout {
    /** Total save RAM capacity in bytes (`CART_SRAM_SIZE` = 32 KiB). */
    public const val SRAM_SIZE: Int = 0x8000

    /** Offset of the global cartridge header (`struct GlobalSaveInfo`, `SRAM_OFFSET_HEADER`). */
    public const val HEADER_OFFSET: Int = 0x0

    /** Size of the global header structure in bytes (`SRAM_SIZE_HEADER` = 0x64). */
    public const val HEADER_SIZE: Int = 0x64

    /**
     * Number of leading header bytes covered by its 16-bit checksum
     * (`GLOBALSIZEINFO_SIZE_FOR_CHECKSUM`). `Checksum16` adds up and xors the 16-bit words and
     * returns the sum of the two; `last_game_save_id` and `last_suspend_slot` are outside it.
     */
    public const val HEADER_CHECKSUM_SIZE: Int = 0x50

    /**
     * Offset of the table of `struct SaveBlockInfo` descriptors (`SRAM_OFFSET_BLOCKINFO`), one
     * per save block, indexed by the `SAVE_ID_*` values. A block counts as valid only if its
     * descriptor has the right magics, kind and payload checksum.
     */
    public const val BLOCK_INFO_OFFSET: Int = 0x64

    /** Size in bytes of one `struct SaveBlockInfo` descriptor (0x10, with 1 padding byte at 7). */
    public const val BLOCK_INFO_SIZE: Int = 0x10

    /** Total number of save block descriptors in the table (`SAVE_ID_MAX` = 7). */
    public const val BLOCK_INFO_COUNT: Int = 7

    /** Total byte size of the entire block info descriptor table (`SRAM_SIZE_BLOCKINFO` = 0x70). */
    public const val BLOCK_INFO_TABLE_SIZE: Int = BLOCK_INFO_COUNT * BLOCK_INFO_SIZE

    /**
     * Offset of the first of the two suspend save blocks (`SRAM_OFFSET_SUS0`, descriptor
     * [SAVE_ID_SUSPEND]). The two blocks are used alternately: each suspend writes the one the
     * header's `last_suspend_slot` does not name (`GetNextSuspendSaveId`).
     */
    public const val SUSPEND0_OFFSET: Int = 0xD4

    /**
     * Size in bytes of one suspend save block (`SRAM_SIZE_SUSPEND` = `SUSPENDSAVE_OFFSET_MAX` =
     * 0x1F78).
     */
    public const val SUSPEND_SIZE: Int = 0x1F78

    /**
     * Offset of the second suspend save block (`SRAM_OFFSET_SUS1`, descriptor
     * [SAVE_ID_SUSPEND_ALT]).
     */
    public const val SUSPEND1_OFFSET: Int = SUSPEND0_OFFSET + SUSPEND_SIZE

    /** Offset of chapter save slot 0 (`SRAM_OFFSET_SAV0`), a `struct GameSaveBlock`. */
    public const val GAME0_OFFSET: Int = SUSPEND1_OFFSET + SUSPEND_SIZE

    /**
     * Size in bytes of one chapter save slot (`SRAM_SIZE_GAMESAVE` = `GAMESAVE_OFFSET_MAX` =
     * 0xDC8).
     */
    public const val GAME_SIZE: Int = 0xDC8

    /** Offset of chapter save slot 1 (`SRAM_OFFSET_SAV1`). */
    public const val GAME1_OFFSET: Int = GAME0_OFFSET + GAME_SIZE

    /** Offset of chapter save slot 2 (`SRAM_OFFSET_SAV2`). */
    public const val GAME2_OFFSET: Int = GAME1_OFFSET + GAME_SIZE

    /**
     * Offset of the Link Arena block (`SRAM_OFFSET_MARENA`, `struct MultiArenaSaveBlock`): the
     * saved teams (10 teams of 5 packed units), the arena config word and the 10 rankings.
     */
    public const val ARENA_OFFSET: Int = GAME2_OFFSET + GAME_SIZE

    /**
     * Size in bytes of the Link Arena block (`SRAM_SIZE_MARENA` = `sizeof(struct
     * MultiArenaSaveBlock)` = 0x874).
     */
    public const val ARENA_SIZE: Int = 0x874

    /**
     * End of the Link Arena block (`SRAM_OFFSET_END`); the small aux blocks that follow are laid
     * out in `struct SaveBlocks` and have no descriptor of their own.
     */
    public const val END_OFFSET: Int = ARENA_OFFSET + ARENA_SIZE

    /**
     * Offset of the end-of-game rankings (`gSram->gameRankSave`, `struct GameRankSaveDataPacks`):
     * 6 packed records (`struct GameRankSaveData`: tactics, survival, funds, exp and combat
     * ranks, play time, gold, tactician name) followed by a checksum word.
     */
    public const val RANK_OFFSET: Int = END_OFFSET

    /** Size in bytes of the ranking block (0x94: 6 records of 0x18 bytes, `magic0`, `unk92`). */
    public const val RANK_SIZE: Int = 0x94

    /**
     * Offset in the ranking block of its `magic0` word, which holds the `Checksum16` of the
     * records.
     */
    public const val RANK_CHECKSUM: Int = 0x90

    /** Number of bytes covered by the ranking checksum: the 6 records (0x90). */
    public const val RANK_SUM_SIZE: Int = 0x90

    /**
     * Offset of the sound room unlock data (`gSram->soundRoomSave`, `struct SoundRoomSaveData`):
     * 8 words of unlock flags, then a checksum word and `magic2`.
     */
    public const val SOUND_ROOM_OFFSET: Int = RANK_OFFSET + RANK_SIZE

    /** Size in bytes of the sound room block (`sizeof(struct SoundRoomSaveData)` = 0x24). */
    public const val SOUND_ROOM_SIZE: Int = 0x24

    /**
     * Offset in the sound room block of its `magic1` word, the `Checksum16` of the 0x20 flag
     * bytes.
     */
    public const val SOUND_ROOM_CHECKSUM: Int = 0x20

    /**
     * Offset of `gSram->unkstruct2` (`struct bmsave_unkstruct2`: 4 words, `magic1` checksum,
     * `magic2`). Its meaning is unknown: the decomp only names its accessors "LinkArenaStruct2"
     * and guesses "flags for viewing CGs?" in `src/bmsave-misc.c`.
     */
    public const val LINK_ARENA2_OFFSET: Int = SOUND_ROOM_OFFSET + SOUND_ROOM_SIZE

    /** Size in bytes of `bmsave_unkstruct2` (0x14). */
    public const val LINK_ARENA2_SIZE: Int = 0x14

    /** Offset in `bmsave_unkstruct2` of `magic1`, the `Checksum16` of the first 0x10 bytes. */
    public const val LINK_ARENA2_CHECKSUM: Int = 0x10

    /**
     * Offset of the bonus content claim data (`gSram->bonusClaim`, `struct BonusClaimSaveData`):
     * 16 `struct BonusClaimEnt` entries (an unseen flag, kind, item id and a 0x11-byte string of
     * 0x14 bytes each) and a checksum. Not the same as the `bonusClaimFlags` word of each chapter
     * save slot.
     */
    public const val BONUS_OFFSET: Int = LINK_ARENA2_OFFSET + LINK_ARENA2_SIZE

    /**
     * Size in bytes of the bonus claim block (0x144: 16 entries of 0x14 bytes, the checksum,
     * padding).
     */
    public const val BONUS_SIZE: Int = 0x144

    /**
     * Offset in the bonus claim block of `cksum16`, the `Checksum16` of the 16 entries (0x140
     * bytes).
     */
    public const val BONUS_CHECKSUM: Int = 0x140

    /**
     * Offset of `gSram->reserved`, the 4 bytes `SramInit` writes a probe word to and reads back
     * to find out whether the cartridge SRAM works (`gBoolSramWorking`).
     */
    public const val RESERVED_OFFSET: Int = BONUS_OFFSET + BONUS_SIZE

    /** Size in bytes of `reserved` (4, one probe word). */
    public const val RESERVED_SIZE: Int = 4

    /**
     * Offset of the extra map block (`SRAM_OFFSET_XMAP` = `CART_SRAM_SIZE - SRAM_SIZE_XMAP`
     * = 0x7400), which starts with a `struct ExtraMapSaveHead` (see `src/bmsave-xmap.c`); the
     * area at the end of save RAM that an extra map is stored in.
     */
    public const val XMAP_OFFSET: Int = 0x7400

    /** Size in bytes of the extra map block (`SRAM_SIZE_XMAP` = 0xC00). */
    public const val XMAP_SIZE: Int = 0xC00

    /** Descriptor index for chapter save slot 0 (`SAVE_ID_GAME0` = 0). */
    public const val SAVE_ID_GAME0: Int = 0

    /** Descriptor index for chapter save slot 1 (`SAVE_ID_GAME1` = 1). */
    public const val SAVE_ID_GAME1: Int = 1

    /** Descriptor index for chapter save slot 2 (`SAVE_ID_GAME2` = 2). */
    public const val SAVE_ID_GAME2: Int = 2

    /**
     * Descriptor index of the first suspend save block (`SAVE_ID_SUSPEND` = 3); the id callers
     * pass for "the suspend save".
     */
    public const val SAVE_ID_SUSPEND: Int = 3

    /** Descriptor index of the second suspend save block (`SAVE_ID_SUSPEND_ALT` = 4). */
    public const val SAVE_ID_SUSPEND_ALT: Int = 4

    /** Descriptor index of the Link Arena block (`SAVE_ID_ARENA` = 5). */
    public const val SAVE_ID_ARENA: Int = 5

    /** Descriptor index of the extra map block (`SAVE_ID_XMAP` = 6; `SAVE_ID_MAX` is 7). */
    public const val SAVE_ID_XMAP: Int = 6

    /** Descriptor `kind` of a chapter save slot (`SAVEBLOCK_KIND_GAME` = 0). */
    public const val SAVEBLOCK_KIND_GAME: Int = 0

    /** Descriptor `kind` of a suspend save (`SAVEBLOCK_KIND_SUSPEND` = 1). */
    public const val SAVEBLOCK_KIND_SUSPEND: Int = 1

    /** Descriptor `kind` of the Link Arena block (`SAVEBLOCK_KIND_ARENA` = 2). */
    public const val SAVEBLOCK_KIND_ARENA: Int = 2

    /** Descriptor `kind` of the extra map block (`SAVEBLOCK_KIND_XMAP` = 3). */
    public const val SAVEBLOCK_KIND_XMAP: Int = 3

    /**
     * Descriptor `kind` of an invalidated block (`SAVEBLOCK_KIND_INVALID` = -1, stored as the
     * byte 0xFF). `InvalidateSuspendSave` writes it, and `WriteSaveBlockInfo` then zeroes the
     * descriptor's size, offset and `magic16`, so the block no longer reads as valid.
     */
    public const val SAVEBLOCK_KIND_INVALID: Int = -1

    /**
     * `magic16` of the global header and of every valid block descriptor (`SAVEMAGIC16` =
     * 0x200A).
     */
    public const val SAVEMAGIC16: Int = 0x200A

    /**
     * `magic32` of the global header and of the chapter and suspend save descriptors
     * (`SAVEMAGIC32` = 0x00040624). The decomp comments it "FE8U's magic".
     */
    public const val SAVEMAGIC32: Int = 0x40624

    /** `magic32` of the Link Arena block descriptor (`SAVEMAGIC32_ARENA` = 0x00020112). */
    public const val SAVEMAGIC32_ARENA: Int = 0x20112

    /** `magic32` of the extra map block descriptor (`SAVEMAGIC32_XMAP` = 0x00020223). */
    public const val SAVEMAGIC32_XMAP: Int = 0x20223

    /**
     * `xmap_magic`, the first word of `struct ExtraMapSaveHead` (`XMAP_MAGIC` = 0x50414D58, the
     * bytes "XMAP" read little endian).
     */
    public const val XMAP_MAGIC: Int = 0x50414D58

    /**
     * The text `GlobalSaveInfo.name` must hold (`sSaveMarker` = `"AGB-FE9"`, in an 8-byte field).
     */
    public const val SAVE_MARKER: String = "AGB-FE9"

    /** Header field offset: the save marker text (`char name[8]`, see [SAVE_MARKER]). */
    public const val HEADER_NAME: Int = 0x00

    /** Header field offset: `u32 magic32`, must be [SAVEMAGIC32]. */
    public const val HEADER_MAGIC32: Int = 0x08

    /** Header field offset: `u16 magic16`, must be [SAVEMAGIC16]. */
    public const val HEADER_MAGIC16: Int = 0x0C

    /**
     * Header field offset: the first flag byte. Bits from the LSB: `completed` (the game has
     * been cleared), `flag0E_1` (unknown), then `Eirk_mode_easy/norm/hard` and
     * `Ephy_mode_easy/norm/hard` (the route and difficulty that were cleared, set by
     * `SavePlayThroughData`; "easy" is the tutorial-controller setting, `config.controller` 0).
     */
    public const val HEADER_FLAGS0: Int = 0x0E

    /**
     * Header field offset: second flag byte: bit 0 `game_end` (`SetGameEndFlag`), bits 1..7
     * `unk0F_1` (unknown, cleared on init).
     */
    public const val HEADER_FLAGS1: Int = 0x0F

    /** Header field offset: `u16 unk10` (meaning unknown, 0 on a fresh save). */
    public const val HEADER_UNK10: Int = 0x10

    /** Header field offset: `u16 unk12` (meaning unknown, 0 on a fresh save). */
    public const val HEADER_UNK12: Int = 0x12

    /**
     * Header field offset: `u8 cleared_playthroughs[12]`, the `playthroughIdentifier` of each
     * finished playthrough (0 = empty entry, `MAX_SAVED_GAME_CLEARS` = 12).
     */
    public const val HEADER_CLEARED_PLAYTHROUGHS: Int = 0x14

    /**
     * Header field offset: `u8 SuppordRecord[0x20]`, the best support level reached for each
     * support pair over all playthroughs, 2 bits per pair (4 pairs per byte), in the order of the
     * support talk list.
     */
    public const val HEADER_SUPPORT_RECORD: Int = 0x20

    /**
     * Header field offset: `u8 charKnownFlags[0x20]`, one bit per character id, set by
     * `SGM_SetCharacterKnown` for every unit of the player army at a chapter save (and for the
     * partners of the support list); ids 0..256 fit.
     */
    public const val HEADER_CHAR_KNOWN_FLAGS: Int = 0x40

    /**
     * Header field offset: `u16 checksum`, the `Checksum16` of the first [HEADER_CHECKSUM_SIZE]
     * bytes.
     */
    public const val HEADER_CHECKSUM: Int = 0x60

    /**
     * Header field offset: `u8 last_game_save_id`, the chapter save slot used last (written
     * without updating the checksum).
     */
    public const val HEADER_LAST_GAME_SAVE_ID: Int = 0x62

    /**
     * Header field offset: `u8 last_suspend_slot`, 0 or 1, which suspend block was written last.
     */
    public const val HEADER_LAST_SUSPEND_SLOT: Int = 0x63

    /**
     * Descriptor field offset: `u32 magic32` (per block, [SAVEMAGIC32] or the arena / xmap
     * variant).
     */
    public const val INFO_MAGIC32: Int = 0x00

    /** Descriptor field offset: `u16 magic16`, must be [SAVEMAGIC16]. */
    public const val INFO_MAGIC16: Int = 0x04

    /**
     * Descriptor field offset: `u8 kind`, one of the `SAVEBLOCK_KIND_*` values (e.g.
     * [SAVEBLOCK_KIND_GAME]).
     */
    public const val INFO_KIND: Int = 0x06

    /** Descriptor field offset: `u16 offset`, where the block starts in SRAM. */
    public const val INFO_OFFSET: Int = 0x08

    /** Descriptor field offset: `u16 size`, the byte size of the block. */
    public const val INFO_SIZE: Int = 0x0A

    /**
     * Descriptor field offset: `u32 checksum32`, the checksum of the block's [INFO_SIZE] bytes at
     * [INFO_OFFSET].
     */
    public const val INFO_CHECKSUM32: Int = 0x0C

    /**
     * The SRAM offset of the descriptor with [index] (`gSram->saveBlockInfo[index]`).
     *
     * @param index one of the `SAVE_ID_*` values (0..6).
     */
    public fun blockInfoOffset(index: Int): Int = BLOCK_INFO_OFFSET + index * BLOCK_INFO_SIZE
}
