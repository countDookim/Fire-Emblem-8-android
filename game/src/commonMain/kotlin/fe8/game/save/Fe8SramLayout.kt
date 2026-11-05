package fe8.game.save

/**
 * Layout of FE8's 32 KiB save RAM, from include/sram-layout.h and include/bmsave.h (`struct
 * SaveBlocks`). All offsets are byte offsets from the start of SRAM (0x0E000000 on the GBA) and
 * all values are little-endian.
 */
public object Fe8SramLayout {
    /** Total save RAM size in bytes (`CART_SRAM_SIZE`). */
    public const val SRAM_SIZE: Int = 0x8000

    /** Offset of the `struct GlobalSaveInfo` header (`SRAM_OFFSET_HEADER`). */
    public const val HEADER_OFFSET: Int = 0x0

    /** Size of `struct GlobalSaveInfo` (`SRAM_SIZE_HEADER`). */
    public const val HEADER_SIZE: Int = 0x64

    /** Header bytes covered by the header checksum (`GLOBALSIZEINFO_SIZE_FOR_CHECKSUM`). */
    public const val HEADER_CHECKSUM_SIZE: Int = 0x50

    /** Offset of the `SaveBlockInfo` table (`SRAM_OFFSET_BLOCKINFO`). */
    public const val BLOCK_INFO_OFFSET: Int = 0x64

    /** Size of one `struct SaveBlockInfo` entry. */
    public const val BLOCK_INFO_SIZE: Int = 0x10

    /** Number of block info entries (`SAVE_ID_MAX`). */
    public const val BLOCK_INFO_COUNT: Int = 7

    /** Size of the whole block info table (`SRAM_SIZE_BLOCKINFO`). */
    public const val BLOCK_INFO_TABLE_SIZE: Int = BLOCK_INFO_COUNT * BLOCK_INFO_SIZE

    /** Offset of the first suspend block (`SRAM_OFFSET_SUS0`). */
    public const val SUSPEND0_OFFSET: Int = 0xD4

    /** Size of one suspend block (`SRAM_SIZE_SUSPEND` = `SUSPENDSAVE_OFFSET_MAX`). */
    public const val SUSPEND_SIZE: Int = 0x1F78

    /** Offset of the second suspend block (`SRAM_OFFSET_SUS1`). */
    public const val SUSPEND1_OFFSET: Int = SUSPEND0_OFFSET + SUSPEND_SIZE

    /** Offset of game save slot 0 (`SRAM_OFFSET_SAV0`). */
    public const val GAME0_OFFSET: Int = SUSPEND1_OFFSET + SUSPEND_SIZE

    /** Size of one game save slot (`SRAM_SIZE_GAMESAVE` = `GAMESAVE_OFFSET_MAX`). */
    public const val GAME_SIZE: Int = 0xDC8

    /** Offset of game save slot 1 (`SRAM_OFFSET_SAV1`). */
    public const val GAME1_OFFSET: Int = GAME0_OFFSET + GAME_SIZE

    /** Offset of game save slot 2 (`SRAM_OFFSET_SAV2`). */
    public const val GAME2_OFFSET: Int = GAME1_OFFSET + GAME_SIZE

    /** Offset of the multiplayer arena block (`SRAM_OFFSET_MARENA`). */
    public const val ARENA_OFFSET: Int = GAME2_OFFSET + GAME_SIZE

    /** Size of the multiplayer arena block (`sizeof(struct MultiArenaSaveBlock)`). */
    public const val ARENA_SIZE: Int = 0x874

    /** End of the blocks above (`SRAM_OFFSET_END`). */
    public const val END_OFFSET: Int = ARENA_OFFSET + ARENA_SIZE

    /** Offset of the ranking data (`gSram->gameRankSave`, `struct GameRankSaveDataPacks`). */
    public const val RANK_OFFSET: Int = END_OFFSET

    /** Size of the ranking data; its `u16 magic0` checksum is at [RANK_CHECKSUM]. */
    public const val RANK_SIZE: Int = 0x94

    /** Offset inside the ranking data of the `u16` checksum of its first [RANK_SUM_SIZE] bytes. */
    public const val RANK_CHECKSUM: Int = 0x90

    /** Bytes of the ranking data covered by its checksum. */
    public const val RANK_SUM_SIZE: Int = 0x90

    /** Offset of the sound room data (`gSram->soundRoomSave`). */
    public const val SOUND_ROOM_OFFSET: Int = RANK_OFFSET + RANK_SIZE

    /** Size of the sound room data (`struct SoundRoomSaveData`). */
    public const val SOUND_ROOM_SIZE: Int = 0x24

    /** Offset inside the sound room data of its `u16 magic1` checksum (covers the bytes before). */
    public const val SOUND_ROOM_CHECKSUM: Int = 0x20

    /** Offset of the link arena data (`gSram->unkstruct2`, `struct bmsave_unkstruct2`). */
    public const val LINK_ARENA2_OFFSET: Int = SOUND_ROOM_OFFSET + SOUND_ROOM_SIZE

    /** Size of the link arena data. */
    public const val LINK_ARENA2_SIZE: Int = 0x14

    /** Offset inside the link arena data of its `u16 magic1` checksum (covers the bytes before). */
    public const val LINK_ARENA2_CHECKSUM: Int = 0x10

    /** Offset of the bonus claim data (`gSram->bonusClaim`, `struct BonusClaimSaveData`). */
    public const val BONUS_OFFSET: Int = LINK_ARENA2_OFFSET + LINK_ARENA2_SIZE

    /** Size of the bonus claim data. */
    public const val BONUS_SIZE: Int = 0x144

    /** Offset inside the bonus claim data of the `u16 cksum16` over its first 0x140 bytes. */
    public const val BONUS_CHECKSUM: Int = 0x140

    /** Offset of the 4 bytes `SramInit` uses to probe the save RAM (`gSram->reserved`). */
    public const val RESERVED_OFFSET: Int = BONUS_OFFSET + BONUS_SIZE

    /** Size of the SRAM probe area. */
    public const val RESERVED_SIZE: Int = 4

    /** Offset of the extra-map block (`SRAM_OFFSET_XMAP`). */
    public const val XMAP_OFFSET: Int = 0x7400

    /** Size of the extra-map block (`SRAM_SIZE_XMAP`). */
    public const val XMAP_SIZE: Int = 0xC00

    /** Block info index of game slot 0 (`SAVE_ID_GAME0`). */
    public const val SAVE_ID_GAME0: Int = 0

    /** Block info index of game slot 1 (`SAVE_ID_GAME1`). */
    public const val SAVE_ID_GAME1: Int = 1

    /** Block info index of game slot 2 (`SAVE_ID_GAME2`). */
    public const val SAVE_ID_GAME2: Int = 2

    /** Block info index of the first suspend block (`SAVE_ID_SUSPEND`). */
    public const val SAVE_ID_SUSPEND: Int = 3

    /** Block info index of the second suspend block (`SAVE_ID_SUSPEND_ALT`). */
    public const val SAVE_ID_SUSPEND_ALT: Int = 4

    /** Block info index of the arena block (`SAVE_ID_ARENA`). */
    public const val SAVE_ID_ARENA: Int = 5

    /** Block info index of the extra-map block (`SAVE_ID_XMAP`). */
    public const val SAVE_ID_XMAP: Int = 6

    /** Block kind of a game save (`SAVEBLOCK_KIND_GAME`). */
    public const val SAVEBLOCK_KIND_GAME: Int = 0

    /** Block kind of a suspend save (`SAVEBLOCK_KIND_SUSPEND`). */
    public const val SAVEBLOCK_KIND_SUSPEND: Int = 1

    /** Block kind of the arena block (`SAVEBLOCK_KIND_ARENA`). */
    public const val SAVEBLOCK_KIND_ARENA: Int = 2

    /** Block kind of the extra-map block (`SAVEBLOCK_KIND_XMAP`). */
    public const val SAVEBLOCK_KIND_XMAP: Int = 3

    /** Block kind of an erased block (`SAVEBLOCK_KIND_INVALID`, stored as the byte 0xFF). */
    public const val SAVEBLOCK_KIND_INVALID: Int = -1

    /** 16-bit magic of the header and every block info (`SAVEMAGIC16`). */
    public const val SAVEMAGIC16: Int = 0x200A

    /** 32-bit magic of the header and of game/suspend block infos (`SAVEMAGIC32`). */
    public const val SAVEMAGIC32: Int = 0x40624

    /** 32-bit magic of the arena block info (`SAVEMAGIC32_ARENA`). */
    public const val SAVEMAGIC32_ARENA: Int = 0x20112

    /** 32-bit magic of the extra-map block info (`SAVEMAGIC32_XMAP`). */
    public const val SAVEMAGIC32_XMAP: Int = 0x20223

    /** Magic at the start of the extra-map block, ASCII 'XMAP' little-endian (`XMAP_MAGIC`). */
    public const val XMAP_MAGIC: Int = 0x50414D58

    /** The marker string at the start of the header (`sSaveMarker`), NUL padded to 8 bytes. */
    public const val SAVE_MARKER: String = "AGB-FE9"

    /** Header field offset: marker `name[8]`. */
    public const val HEADER_NAME: Int = 0x00

    /** Header field offset: `u32 magic32`. */
    public const val HEADER_MAGIC32: Int = 0x08

    /** Header field offset: `u16 magic16`. */
    public const val HEADER_MAGIC16: Int = 0x0C

    /**
     * Header field offset: first flag byte (bits LSB first: completed, flag0E_1, Eirk easy/norm/
     * hard, Ephy easy/norm/hard).
     */
    public const val HEADER_FLAGS0: Int = 0x0E

    /** Header field offset: second flag byte (bit 0 `game_end`, bits 1..7 `unk0F_1`). */
    public const val HEADER_FLAGS1: Int = 0x0F

    /** Header field offset: `u16 unk10`. */
    public const val HEADER_UNK10: Int = 0x10

    /** Header field offset: `u16 unk12`. */
    public const val HEADER_UNK12: Int = 0x12

    /** Header field offset: `u8 cleared_playthroughs[12]`. */
    public const val HEADER_CLEARED_PLAYTHROUGHS: Int = 0x14

    /** Header field offset: `u8 SuppordRecord[0x20]`. */
    public const val HEADER_SUPPORT_RECORD: Int = 0x20

    /** Header field offset: `u8 charKnownFlags[0x20]`. */
    public const val HEADER_CHAR_KNOWN_FLAGS: Int = 0x40

    /** Header field offset: `u16 checksum` (covers header bytes `0..0x50`). */
    public const val HEADER_CHECKSUM: Int = 0x60

    /** Header field offset: `u8 last_game_save_id`. */
    public const val HEADER_LAST_GAME_SAVE_ID: Int = 0x62

    /** Header field offset: `u8 last_suspend_slot`. */
    public const val HEADER_LAST_SUSPEND_SLOT: Int = 0x63

    /** Block info field offset: `u32 magic32`. */
    public const val INFO_MAGIC32: Int = 0x00

    /** Block info field offset: `u16 magic16`. */
    public const val INFO_MAGIC16: Int = 0x04

    /** Block info field offset: `u8 kind` (a `SAVEBLOCK_KIND_*` value, 0xFF = invalid). */
    public const val INFO_KIND: Int = 0x06

    /** Block info field offset: `u16 offset` of the block inside SRAM. */
    public const val INFO_OFFSET: Int = 0x08

    /** Block info field offset: `u16 size` of the block in bytes. */
    public const val INFO_SIZE: Int = 0x0A

    /** Block info field offset: `u32 checksum32` of the block's bytes. */
    public const val INFO_CHECKSUM32: Int = 0x0C

    /** Offset of the block info entry [index] (`gSram->saveBlockInfo[index]`). */
    public fun blockInfoOffset(index: Int): Int = BLOCK_INFO_OFFSET + index * BLOCK_INFO_SIZE
}
