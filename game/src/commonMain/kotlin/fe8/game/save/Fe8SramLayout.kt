/**
 * Cartridge SRAM memory map and layout definitions.
 *
 * Defines absolute byte offsets, sizes, magic signatures, and field structures for Fire Emblem:
 * The Sacred Stones 32 KiB battery-backed save RAM (mapped to 0x0E000000 on GBA hardware).
 * Corresponds to decompilation definitions in `include/sram-layout.h` and `include/bmsave.h`.
 */

package fe8.game.save

/**
 * Layout of FE8's 32 KiB save RAM (`struct SaveBlocks`).
 *
 * All offsets represent byte distances from the base of SRAM (GBA address `0x0E000000`).
 * All multi-byte numeric primitives are stored in little-endian byte order.
 */
public object Fe8SramLayout {
    /** Total save RAM capacity in bytes (`CART_SRAM_SIZE` = 32 KiB). */
    public const val SRAM_SIZE: Int = 0x8000

    /** Offset of the global cartridge header (`struct GlobalSaveInfo`, `SRAM_OFFSET_HEADER`). */
    public const val HEADER_OFFSET: Int = 0x0

    /** Size of the global header structure in bytes (`SRAM_SIZE_HEADER` = 0x64). */
    public const val HEADER_SIZE: Int = 0x64

    /** Number of bytes in the global header covered by its 16-bit additive checksum (`GLOBALSIZEINFO_SIZE_FOR_CHECKSUM`). */
    public const val HEADER_CHECKSUM_SIZE: Int = 0x50

    /** Offset of the save block metadata descriptor table (`SRAM_OFFSET_BLOCKINFO`). */
    public const val BLOCK_INFO_OFFSET: Int = 0x64

    /** Size in bytes of a single `struct SaveBlockInfo` descriptor entry (0x10). */
    public const val BLOCK_INFO_SIZE: Int = 0x10

    /** Total number of save block descriptors in the table (`SAVE_ID_MAX` = 7). */
    public const val BLOCK_INFO_COUNT: Int = 7

    /** Total byte size of the entire block info descriptor table (`SRAM_SIZE_BLOCKINFO` = 0x70). */
    public const val BLOCK_INFO_TABLE_SIZE: Int = BLOCK_INFO_COUNT * BLOCK_INFO_SIZE

    /** Offset of the primary suspend save block (`SRAM_OFFSET_SUS0`). */
    public const val SUSPEND0_OFFSET: Int = 0xD4

    /** Size in bytes of one suspend save block (`SRAM_SIZE_SUSPEND` = `SUSPENDSAVE_OFFSET_MAX` = 0x1F78). */
    public const val SUSPEND_SIZE: Int = 0x1F78

    /** Offset of the secondary (alternating) suspend save block (`SRAM_OFFSET_SUS1`). */
    public const val SUSPEND1_OFFSET: Int = SUSPEND0_OFFSET + SUSPEND_SIZE

    /** Offset of chapter save slot 0 (`SRAM_OFFSET_SAV0`). */
    public const val GAME0_OFFSET: Int = SUSPEND1_OFFSET + SUSPEND_SIZE

    /** Size in bytes of one chapter save slot (`SRAM_SIZE_GAMESAVE` = `GAMESAVE_OFFSET_MAX` = 0xDC8). */
    public const val GAME_SIZE: Int = 0xDC8

    /** Offset of chapter save slot 1 (`SRAM_OFFSET_SAV1`). */
    public const val GAME1_OFFSET: Int = GAME0_OFFSET + GAME_SIZE

    /** Offset of chapter save slot 2 (`SRAM_OFFSET_SAV2`). */
    public const val GAME2_OFFSET: Int = GAME1_OFFSET + GAME_SIZE

    /** Offset of the Link Arena multiplayer team save block (`SRAM_OFFSET_MARENA`). */
    public const val ARENA_OFFSET: Int = GAME2_OFFSET + GAME_SIZE

    /** Size in bytes of the multiplayer arena block (`sizeof(struct MultiArenaSaveBlock)` = 0x874). */
    public const val ARENA_SIZE: Int = 0x874

    /** End offset of primary game and suspend blocks (`SRAM_OFFSET_END`). */
    public const val END_OFFSET: Int = ARENA_OFFSET + ARENA_SIZE

    /** Offset of battle rankings and playthrough history (`gSram->gameRankSave`, `struct GameRankSaveDataPacks`). */
    public const val RANK_OFFSET: Int = END_OFFSET

    /** Size in bytes of the ranking data block (0x94). */
    public const val RANK_SIZE: Int = 0x94

    /** Offset within the ranking data block of the 16-bit checksum word. */
    public const val RANK_CHECKSUM: Int = 0x90

    /** Number of bytes of ranking data covered by its checksum (0x90). */
    public const val RANK_SUM_SIZE: Int = 0x90

    /** Offset of sound room unlocked tracks data (`gSram->soundRoomSave`). */
    public const val SOUND_ROOM_OFFSET: Int = RANK_OFFSET + RANK_SIZE

    /** Size in bytes of sound room save data (`struct SoundRoomSaveData` = 0x24). */
    public const val SOUND_ROOM_SIZE: Int = 0x24

    /** Offset within sound room data of its 16-bit checksum word (`magic1` = 0x20). */
    public const val SOUND_ROOM_CHECKSUM: Int = 0x20

    /** Offset of Link Arena secondary configuration (`gSram->unkstruct2`, `struct bmsave_unkstruct2`). */
    public const val LINK_ARENA2_OFFSET: Int = SOUND_ROOM_OFFSET + SOUND_ROOM_SIZE

    /** Size in bytes of Link Arena secondary configuration block (0x14). */
    public const val LINK_ARENA2_SIZE: Int = 0x14

    /** Offset within Link Arena secondary data of its 16-bit checksum word (0x10). */
    public const val LINK_ARENA2_CHECKSUM: Int = 0x10

    /** Offset of downloaded bonus content claim flags (`gSram->bonusClaim`, `struct BonusClaimSaveData`). */
    public const val BONUS_OFFSET: Int = LINK_ARENA2_OFFSET + LINK_ARENA2_SIZE

    /** Size in bytes of the bonus claim save data block (0x144). */
    public const val BONUS_SIZE: Int = 0x144

    /** Offset within bonus claim data of the 16-bit checksum covering the first 0x140 bytes. */
    public const val BONUS_CHECKSUM: Int = 0x140

    /** Offset of the 4-byte test region probed by `SramInit` to verify SRAM readability/writability. */
    public const val RESERVED_OFFSET: Int = BONUS_OFFSET + BONUS_SIZE

    /** Size in bytes of the SRAM probe test area (4). */
    public const val RESERVED_SIZE: Int = 4

    /** Offset of downloaded extra map data (`SRAM_OFFSET_XMAP` = 0x7400). */
    public const val XMAP_OFFSET: Int = 0x7400

    /** Size in bytes of the extra map block (`SRAM_SIZE_XMAP` = 0xC00). */
    public const val XMAP_SIZE: Int = 0xC00

    /** Descriptor index for chapter save slot 0 (`SAVE_ID_GAME0` = 0). */
    public const val SAVE_ID_GAME0: Int = 0

    /** Descriptor index for chapter save slot 1 (`SAVE_ID_GAME1` = 1). */
    public const val SAVE_ID_GAME1: Int = 1

    /** Descriptor index for chapter save slot 2 (`SAVE_ID_GAME2` = 2). */
    public const val SAVE_ID_GAME2: Int = 2

    /** Descriptor index for primary suspend save slot (`SAVE_ID_SUSPEND` = 3). */
    public const val SAVE_ID_SUSPEND: Int = 3

    /** Descriptor index for secondary suspend save slot (`SAVE_ID_SUSPEND_ALT` = 4). */
    public const val SAVE_ID_SUSPEND_ALT: Int = 4

    /** Descriptor index for the Link Arena save block (`SAVE_ID_ARENA` = 5). */
    public const val SAVE_ID_ARENA: Int = 5

    /** Descriptor index for the extra map save block (`SAVE_ID_XMAP` = 6). */
    public const val SAVE_ID_XMAP: Int = 6

    /** Block kind identifier for chapter game saves (`SAVEBLOCK_KIND_GAME` = 0). */
    public const val SAVEBLOCK_KIND_GAME: Int = 0

    /** Block kind identifier for mid-chapter suspend saves (`SAVEBLOCK_KIND_SUSPEND` = 1). */
    public const val SAVEBLOCK_KIND_SUSPEND: Int = 1

    /** Block kind identifier for multiplayer Link Arena blocks (`SAVEBLOCK_KIND_ARENA` = 2). */
    public const val SAVEBLOCK_KIND_ARENA: Int = 2

    /** Block kind identifier for downloaded extra map blocks (`SAVEBLOCK_KIND_XMAP` = 3). */
    public const val SAVEBLOCK_KIND_XMAP: Int = 3

    /** Block kind identifier indicating an unallocated or erased block (0xFF stored as byte, -1 as int). */
    public const val SAVEBLOCK_KIND_INVALID: Int = -1

    /** 16-bit signature magic for valid headers and block descriptors (`SAVEMAGIC16` = 0x200A). */
    public const val SAVEMAGIC16: Int = 0x200A

    /** 32-bit signature magic for game and suspend save block descriptors (`SAVEMAGIC32` = 0x00040624). */
    public const val SAVEMAGIC32: Int = 0x40624

    /** 32-bit signature magic for Link Arena save block descriptors (`SAVEMAGIC32_ARENA` = 0x00020112). */
    public const val SAVEMAGIC32_ARENA: Int = 0x20112

    /** 32-bit signature magic for extra map save block descriptors (`SAVEMAGIC32_XMAP` = 0x00020223). */
    public const val SAVEMAGIC32_XMAP: Int = 0x20223

    /** 32-bit signature magic at the beginning of extra map blocks ('XMAP' in ASCII = 0x50414D58). */
    public const val XMAP_MAGIC: Int = 0x50414D58

    /** Cartridge save marker string at offset 0 of `GlobalSaveInfo` (`"AGB-FE9"`, NUL-padded to 8 bytes). */
    public const val SAVE_MARKER: String = "AGB-FE9"

    /** Header field offset: ASCII identifier marker string (`name[8]`). */
    public const val HEADER_NAME: Int = 0x00

    /** Header field offset: 32-bit magic (`u32 magic32`). */
    public const val HEADER_MAGIC32: Int = 0x08

    /** Header field offset: 16-bit magic (`u16 magic16`). */
    public const val HEADER_MAGIC16: Int = 0x0C

    /**
     * Header field offset: route completion flags byte 0
     * (bits LSB first: completed, flag0E_1, Eirika easy/norm/hard, Ephraim easy/norm/hard).
     */
    public const val HEADER_FLAGS0: Int = 0x0E

    /** Header field offset: game completion flags byte 1 (bit 0 `game_end`, bits 1..7 `unk0F_1`). */
    public const val HEADER_FLAGS1: Int = 0x0F

    /** Header field offset: reserved field `u16 unk10`. */
    public const val HEADER_UNK10: Int = 0x10

    /** Header field offset: reserved field `u16 unk12`. */
    public const val HEADER_UNK12: Int = 0x12

    /** Header field offset: array of cleared playthrough IDs (`u8 cleared_playthroughs[12]`). */
    public const val HEADER_CLEARED_PLAYTHROUGHS: Int = 0x14

    /** Header field offset: unlocked support conversation records (`u8 SuppordRecord[0x20]`). */
    public const val HEADER_SUPPORT_RECORD: Int = 0x20

    /** Header field offset: character encounter / known flags bitset (`u8 charKnownFlags[0x20]`). */
    public const val HEADER_CHAR_KNOWN_FLAGS: Int = 0x40

    /** Header field offset: 16-bit additive checksum word covering bytes 0..0x50 (`u16 checksum`). */
    public const val HEADER_CHECKSUM: Int = 0x60

    /** Header field offset: ID of the last active chapter save slot (`u8 last_game_save_id`). */
    public const val HEADER_LAST_GAME_SAVE_ID: Int = 0x62

    /** Header field offset: index of the active suspend slot, 0 or 1 (`u8 last_suspend_slot`). */
    public const val HEADER_LAST_SUSPEND_SLOT: Int = 0x63

    /** Block descriptor field offset: 32-bit magic (`u32 magic32`). */
    public const val INFO_MAGIC32: Int = 0x00

    /** Block descriptor field offset: 16-bit magic (`u16 magic16`). */
    public const val INFO_MAGIC16: Int = 0x04

    /** Block descriptor field offset: block type identifier (`u8 kind`, e.g. [SAVEBLOCK_KIND_GAME]). */
    public const val INFO_KIND: Int = 0x06

    /** Block descriptor field offset: starting byte offset of the block in SRAM (`u16 offset`). */
    public const val INFO_OFFSET: Int = 0x08

    /** Block descriptor field offset: allocated byte size of the block (`u16 size`). */
    public const val INFO_SIZE: Int = 0x0A

    /** Block descriptor field offset: 32-bit block payload checksum (`u32 checksum32`). */
    public const val INFO_CHECKSUM32: Int = 0x0C

    /**
     * Computes the byte offset in SRAM for block descriptor [index] (`gSram->saveBlockInfo[index]`).
     *
     * @param index Descriptor index (0..6).
     * @return Absolute SRAM byte offset.
     */
    public fun blockInfoOffset(index: Int): Int = BLOCK_INFO_OFFSET + index * BLOCK_INFO_SIZE
}
