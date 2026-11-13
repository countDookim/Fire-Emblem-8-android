package fe8.game.bm

import fe8.game.bm.StructCodec.bits
import fe8.game.bm.StructCodec.field
import fe8.game.bm.StructCodec.put16
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * Mutable model of the decomp's `struct PlaySt` (`gPlaySt`, include/types.h, 0x4C bytes) with
 * every field as a named property: the chapter and settings state that lives in the save files
 * (the chapter progress, turn, gold, rankings and the options screen settings). [decode] and
 * [encode] round-trip the 0x4C bytes exactly.
 *
 * The struct is `packed`, so GCC lays its bitfields out contiguously, least significant bit first;
 * every bit of every bitfield word is a named property here, so no reserved bits need preserving.
 * Signed fields hold their sign-extended value, unsigned ones their zero-extended value (a `u32`
 * holds its 32 bits in the [Int]). Bitfield properties are the field's value (masked to its width
 * on [encode]); a `unk_XX_YY` name keeps the decomp's suffix.
 */
public class PlaySt {
    /**
     * `time_saved` (u32, 0x00): the game clock (`GetGameClock`, frames) written at save time;
     * `SetGameTime` restores the clock from it on load, and the save menus show it as play time.
     */
    public var timeSaved: Int = 0

    /**
     * `time_chapter_started` (u32, 0x04): the game clock (frames) when the chapter started
     * (src/bmio.c); the chapter's play time is the clock minus this (src/bmsave-xmap.c,
     * src/bmdifficulty.c).
     */
    public var timeChapterStarted: Int = 0

    /** `partyGoldAmount` (u32, 0x08): the party's gold; `SetPartyGoldAmount` caps it at 999999. */
    public var partyGoldAmount: Int = 0

    /**
     * `gameSaveSlot` (u8, 0x0C): index of the save slot this game was started in or loaded from.
     */
    public var gameSaveSlot: Int = 0

    /** `chapterVisionRange` (u8, 0x0D): the chapter's fog of war vision range; 0 means no fog. */
    public var chapterVisionRange: Int = 0

    /** `chapterIndex` (s8, 0x0E): the current chapter, an index into `gChapterDataTable`. */
    public var chapterIndex: Int = 0

    /**
     * `faction` (u8, 0x0F): whose phase it is: 0x00 player (`FACTION_BLUE`), 0x40 NPC
     * (`FACTION_GREEN`), 0x80 enemy (`FACTION_RED`); the decomp notes 0xC0 as a possible link
     * arena fourth team.
     */
    public var faction: Int = 0

    /** `chapterTurnNumber` (u16, 0x10): the current turn of the chapter; the first turn is 1. */
    public var chapterTurnNumber: Int = 0

    /**
     * `xCursor` (u8, 0x12): map cursor tile column, copied from `gBmSt.playerCursor` (player
     * phase).
     */
    public var xCursor: Int = 0

    /** `yCursor` (u8, 0x13): map cursor tile row; see [xCursor]. */
    public var yCursor: Int = 0

    /**
     * `chapterStateBits` (u8, 0x14): `PLAY_FLAG_*` bits (include/types.h): 0x01 and 0x02 the
     * stat screen page, 0x04 `POSTGAME`, 0x08 `TUTORIAL`, 0x10 `PREPSCREEN` (in the preparations
     * screen), 0x20 `COMPLETE`, 0x40 `HARD` ([PLAY_FLAG_HARD], the difficult mode), 0x80
     * `EXTRA_MAP`.
     */
    public var chapterStateBits: Int = 0

    /** `chapterWeatherId` (u8, 0x15): the map's weather (`WEATHER_*`: rain, snow, clouds, ...). */
    public var chapterWeatherId: Int = 0

    /**
     * `chapterTotalSupportGain` (u16, 0x16): support points gained this chapter (src/bmreliance.c
     * adds to it, src/bmio.c clears it when a chapter starts).
     */
    public var chapterTotalSupportGain: Int = 0

    /** `playthroughIdentifier` (u8, 0x18): id from `GetNewPlaythroughId` taken at new game. */
    public var playthroughIdentifier: Int = 0

    /**
     * `unk19` (u8, 0x19): the high nibble holds a unit list screen page (src/unitlistscreen.c
     * reads `unk19 / 16`, writes the page into the high nibble). The meaning of the low nibble is
     * unknown.
     */
    public var unk19: Int = 0

    /**
     * `lastUnitSortType` (u8, 0x1A): the unit list screen's last sort setting
     * (`(unk_34 << 7) + unk_32` of that screen's proc, src/unitlistscreen.c).
     */
    public var lastUnitSortType: Int = 0

    /**
     * `chapterModeIndex` (u8, 0x1B): `CHAPTER_MODE_COMMON` (1, the shared prologue chapters
     * 0..8, which the decomp comment calls the tutorial), `CHAPTER_MODE_EIRIKA` (2) or
     * `CHAPTER_MODE_EPHRAIM` (3).
     */
    public var chapterModeIndex: Int = 0

    /**
     * `unk1C[0]` (u8, 0x1C): a character id (the decomp comment: "indexed by weapon type; has to do
     * with allowing unusable weapons to be used"); src/msg.c prints that character's name from
     * it. Set and read by `SetChapterUnk1C`/`GetChapterUnk1C` (src/bmitem.c).
     */
    public var unk1C0: Int = 0

    /** `unk1C[1]` (u8, 0x1D): the second entry of `unk1C`; see [unk1C0]. */
    public var unk1C1: Int = 0

    /** `unk1E` (u8, 0x1E): unknown; no read or write of it was found in src/. */
    public var unk1E: Int = 0

    /** `unk1F` (u8, 0x1F): unknown; no read or write of it was found in src/. */
    public var unk1F: Int = 0

    /**
     * `playerName` (char[11], 0x20..0x2A): the decomp notes it is unused outside the link arena
     * (it was the tactician name in FE7) and its size is unknown; a new game sets its first byte
     * to 0. Raw bytes, copied by [decode] and [encode].
     */
    public val playerName: ByteArray = ByteArray(PLAYER_NAME_SIZE)

    /**
     * `unk_2B_00` (u8 bit 0 at 0x2B): set to 1 by a new game (src/bmsave.c), copied by
     * src/bmsave-lib.c and tested by src/bonusclaim.c; meaning unknown.
     */
    public var unk2B00: Int = 0

    /** `unk_2B_01` (u8 bits 1..7 at 0x2B): unknown bitfield (`unk_2B_01` in decomp `PlaySt`). */
    public var unk2B01: Int = 0

    /** `unk_2C_1` (u32 bit 0 at 0x2C): cleared by a new game (src/bmsave.c); meaning unknown. */
    public var unk2C1: Int = 0

    /** `unk_2C_01` (u32 bits 1..3 at 0x2C): unknown bitfield (`unk_2C_01` in decomp `PlaySt`). */
    public var unk2C01: Int = 0

    /**
     * `unk_2C_04` (u32 bits 4..12 at 0x2C): a score carried into the overall chapter ranking:
     * `ComputeChapterRankings` (src/gamerankings.c) adds it to the overall rank (capped at
     * 0xFF); cleared by `ClearPidStats_ret`.
     */
    public var unk2C04: Int = 0

    /**
     * `unk_2C_0D` (u32 bits 13..22 at 0x2C): copy of `gExtraMapSaveHead.unk0F`, a value of the
     * extra map save (src/bmsave-xmap.c); meaning unknown.
     */
    public var unk2C0D: Int = 0

    /** `unk_2C_2` (u32 bits 23..27 at 0x2C): unknown bitfield (`unk_2C_2` in decomp `PlaySt`). */
    public var unk2C2: Int = 0

    /** `unk_2C_3` (u32 bits 28..31 at 0x2C): unknown bitfield (`unk_2C_3` in decomp `PlaySt`). */
    public var unk2C3: Int = 0

    /**
     * `unk_30.total_gold` (s32, 0x30): the party's total gold value (`GetPartyTotalGoldValue`) at
     * the last chapter ranking; `GetChapterFundsRank` (src/gamerankings.c) ranks the difference
     * to now and stores the new value.
     */
    public var totalGold: Int = 0

    /** `unk_30.unk_4_00` (u32 bits 0..19 at 0x34): unknown; only cleared by `ClearPidStats`. */
    public var unk30Unk400: Int = 0

    /** `unk_30.unk_4_14` (u32 bits 20..31 at 0x34): unknown; only cleared by `ClearPidStats`. */
    public var unk30Unk414: Int = 0

    /** `unk_30.unk_8_1` (u32 bits 0..7 at 0x38): unknown; only cleared by `ClearPidStats`. */
    public var unk30Unk81: Int = 0

    /**
     * `unk_30.unk_8_2` (u32 bits 8..27 at 0x38): the total experience gained
     * (`PidStatsGetTotalExpGain`, capped at 0xFFFFF) at the last chapter ranking; the exp rank
     * uses the difference to now, and `src/bmdifficulty.c` (the Valni and Lagdou dungeon score)
     * adds the same difference.
     */
    public var unk30Unk82: Int = 0

    /** `unk_30.unk_8_3` (u32 bits 28..31 at 0x38): unknown. */
    public var unk30Unk83: Int = 0

    /** `unk_30.unk_C_00` (u32 bits 0..5 at 0x3C): unknown. */
    public var unk30UnkC00: Int = 0

    /**
     * `unk_30.combatRank` (u32 bits 6..8 at 0x3C): the combat rank of the last chapter, set by
     * `ComputeChapterRankings` (src/gamerankings.c) with the other ranks; 3 bits.
     */
    public var combatRank: Int = 0

    /**
     * `unk_30.expRank` (u32 bits 9..11 at 0x3C): the experience rank (`GetChapterExpRank`, 0..4).
     */
    public var expRank: Int = 0

    /** `unk_30.unk_3D_04` (u32 bits 12..14 at 0x3C): unknown. */
    public var unk30Unk3D04: Int = 0

    /** `unk_30.fundsRank` (u32 bits 15..17 at 0x3C): the funds rank (`GetChapterFundsRank`). */
    public var fundsRank: Int = 0

    /**
     * `unk_30.tacticsRank` (u32 bits 18..20 at 0x3C): the tactics rank (`GetChapterTacticsRank`).
     */
    public var tacticsRank: Int = 0

    /**
     * `unk_30.survivalRank` (u32 bits 21..23 at 0x3C): the survival rank
     * (`GetChapterSurvivalRank`).
     */
    public var survivalRank: Int = 0

    /** `unk_30.unk_F_00` (u32 bits 24..31 at 0x3C): unknown. */
    public var unk30UnkF00: Int = 0

    /**
     * `config.unitColor` (1 bit, word 0x40 bit 0): when set, battle animations use the generic
     * palettes instead of each character's own (`gBanimUniquePaletteDisabled`,
     * src/banim-ekrbattleintro.c). The link arena sets it to 1.
     */
    public var unitColor: Int = 0

    /**
     * `config.disableTerrainDisplay` (1 bit, bit 1): the options screen's terrain window setting; 1
     * hides it.
     */
    public var disableTerrainDisplay: Int = 0

    /**
     * `config.unitDisplayType` (2 bits, bits 2..3): the unit window shown on the map:
     * 0 is the minimug box (`gProcScr_UnitDisplay_MinimugBox`), 1 the burst window
     * (`gProcScr_UnitDisplay_Burst`), other values show none (src/player_interface.c).
     */
    public var unitDisplayType: Int = 0

    /**
     * `config.autoCursor` (1 bit, bit 4): the options screen's auto cursor setting; the link arena
     * sets it to 1.
     */
    public var autoCursor: Int = 0

    /**
     * `config.textSpeed` (2 bits, bits 5..6): the dialogue text speed; `GetTextDisplaySpeed`
     * (src/bmio.c) maps 0, 1, 2, 3 to 8, 4, 1, 0 (the wait per character; 0 is instant). The
     * new game default is 1.
     */
    public var textSpeed: Int = 0

    /**
     * `config.gameSpeed` (1 bit, bit 7): when set, map units walk fast (src/mu.c; holding A does
     * the same).
     */
    public var gameSpeed: Int = 0

    /**
     * `config.disableBgm` (1 bit, bit 8): the options screen's music setting; 1 turns the music
     * off.
     */
    public var disableBgm: Int = 0

    /**
     * `config.disableSoundEffects` (1 bit, bit 9): the options screen's sound effect setting; 1
     * turns them off.
     */
    public var disableSoundEffects: Int = 0

    /**
     * `config.windowColor` (2 bits, bits 10..11): which of four window palettes the menus use
     * (src/prepscreen.c).
     */
    public var windowColor: Int = 0

    /** `config.unk41_5` (1 bit, bit 12): unknown; `InitPlayConfig` clears it. */
    public var unk41x5: Int = 0

    /** `config.unk41_6` (1 bit, bit 13): unknown; no read or write of it was found in src/. */
    public var unk41x6: Int = 0

    /**
     * `config.disableAutoEndTurns` (1 bit, bit 14): when 0, the player phase ends by itself once
     * no unit can act (`GetPhaseAbleUnitCount` is 0, src/playerphase.c).
     */
    public var disableAutoEndTurns: Int = 0

    /**
     * `config.noSubtitleHelp` (1 bit, bit 15): when 1, `StartSubtitleHelp` (src/bb.c) shows no
     * subtitle help.
     */
    public var noSubtitleHelp: Int = 0

    /**
     * `config.disableGoalDisplay` (1 bit, bit 16): when 1, the chapter goal window is not shown on
     * the map.
     */
    public var disableGoalDisplay: Int = 0

    /**
     * `config.animationType` (2 bits, bits 17..18): the battle animation setting
     * (`PLAY_ANIMCONF_ON` 0, `_OFF` 1, `_SOLO_ANIM` 2, `_ON_UNIQUE_BG` 3).
     */
    public var animationType: Int = 0

    /**
     * `config.battleForecastType` (2 bits, bits 19..20): 2 shows no battle forecast window;
     * 0 and 1 choose its frame style (`NewBattleForecast`, src/bksel.c).
     */
    public var battleForecastType: Int = 0

    /**
     * `config.controller` (1 bit, bit 21): set from the `unk` argument of `InitPlayConfig`; the
     * name is a guess. It is read as a tutorial flag (`TUTORIAL_MODE()` needs it != 1 and not
     * hard mode; src/bmsave-bwl.c stores it as `isTutorial`), so the real meaning is unknown.
     */
    public var controller: Int = 0

    /**
     * `config.rankDisplay` (1 bit, bit 22): an options screen setting (src/uiconfig.c); its effect
     * is unknown.
     */
    public var rankDisplay: Int = 0

    /**
     * `config.debugControlRed` (2 bits, bits 23..24): debug menu setting for the enemy units
     * (src/bmdebug.c).
     */
    public var debugControlRed: Int = 0

    /**
     * `config.debugControlGreen` (2 bits, bits 25..26): debug menu setting for the NPC units
     * (src/bmdebug.c).
     */
    public var debugControlGreen: Int = 0

    /**
     * `config.unk43_4` (5 bits, bits 27..31): unknown; no read or write of it was found in src/.
     */
    public var unk43x4: Int = 0

    /** `config.unk44[0]` (u8, 0x44): unknown config byte 0 (`unk44[0]` in decomp `PlaySt`). */
    public var configUnk440: Int = 0

    /** `config.unk44[1]` (u8, 0x45): unknown config byte 1 (`unk44[1]` in decomp `PlaySt`). */
    public var configUnk441: Int = 0

    /** `config.unk44[2]` (u8, 0x46): unknown config byte 2 (`unk44[2]` in decomp `PlaySt`). */
    public var configUnk442: Int = 0

    /** `config.unk44[3]` (u8, 0x47): unknown config byte 3 (`unk44[3]` in decomp `PlaySt`). */
    public var configUnk443: Int = 0

    /**
     * `unk48` (u16, 0x48): the number of battles the player side won this chapter (incremented
     * when a blue unit is the winner, src/bmsave-bwl.c); the dungeon score adds it to
     * `enemiesDefeated` (src/bmdifficulty.c). Cleared at chapter start.
     */
    public var unk48: Int = 0

    /**
     * `unk4A_1` (u8 bit 0 at 0x4A): when set, `UndeployEveryone` (src/bm.c) leaves the units'
     * deployed state alone; it is kept across `StartBattleMap` by `sub_80155C4`.
     */
    public var unk4A1: Int = 0

    /**
     * `save_menu_type` (u8 bits 1..3 at 0x4A): set to 1 or 2 by the save events
     * (src/eventscr.c, src/worldmap_main.c); src/gamecontrol.c tests for 2 and 4.
     */
    public var saveMenuType: Int = 0

    /**
     * `tutorial_exec_type` (u8 bits 4..7 at 0x4A): the event enqueue type of the running tutorial
     * event (src/eventinfo.c).
     */
    public var tutorialExecType: Int = 0

    /**
     * `tutorial_counter` (u8, 0x4B): 1-based index of the running event in the chapter's
     * `tutorialEvents` list; 0 for none.
     */
    public var tutorialCounter: Int = 0

    /** Reads the 0x4C bytes at [offset] of [bytes] into this state. Does not allocate. */
    public fun decode(bytes: ByteArray, offset: Int) {
        timeSaved = u32(bytes, offset)
        timeChapterStarted = u32(bytes, offset + 0x04)
        partyGoldAmount = u32(bytes, offset + 0x08)
        gameSaveSlot = u8(bytes, offset + 0x0C)
        chapterVisionRange = u8(bytes, offset + 0x0D)
        chapterIndex = s8(bytes, offset + 0x0E)
        faction = u8(bytes, offset + 0x0F)
        chapterTurnNumber = u16(bytes, offset + 0x10)
        xCursor = u8(bytes, offset + 0x12)
        yCursor = u8(bytes, offset + 0x13)
        chapterStateBits = u8(bytes, offset + 0x14)
        chapterWeatherId = u8(bytes, offset + 0x15)
        chapterTotalSupportGain = u16(bytes, offset + 0x16)
        playthroughIdentifier = u8(bytes, offset + 0x18)
        unk19 = u8(bytes, offset + 0x19)
        lastUnitSortType = u8(bytes, offset + 0x1A)
        chapterModeIndex = u8(bytes, offset + 0x1B)
        unk1C0 = u8(bytes, offset + 0x1C)
        unk1C1 = u8(bytes, offset + 0x1D)
        unk1E = u8(bytes, offset + 0x1E)
        unk1F = u8(bytes, offset + 0x1F)
        for (i in 0 until PLAYER_NAME_SIZE) playerName[i] = bytes[offset + 0x20 + i]
        val byte2B = u8(bytes, offset + 0x2B)
        unk2B00 = bits(byte2B, 0, 1)
        unk2B01 = bits(byte2B, 1, 7)
        val word2C = u32(bytes, offset + 0x2C)
        unk2C1 = bits(word2C, 0, 1)
        unk2C01 = bits(word2C, 1, 3)
        unk2C04 = bits(word2C, 4, 9)
        unk2C0D = bits(word2C, 13, 10)
        unk2C2 = bits(word2C, 23, 5)
        unk2C3 = bits(word2C, 28, 4)
        totalGold = u32(bytes, offset + 0x30)
        val word34 = u32(bytes, offset + 0x34)
        unk30Unk400 = bits(word34, 0, 20)
        unk30Unk414 = bits(word34, 20, 12)
        val word38 = u32(bytes, offset + 0x38)
        unk30Unk81 = bits(word38, 0, 8)
        unk30Unk82 = bits(word38, 8, 20)
        unk30Unk83 = bits(word38, 28, 4)
        val rankingWord = u32(bytes, offset + 0x3C)
        unk30UnkC00 = bits(rankingWord, 0, 6)
        combatRank = bits(rankingWord, 6, 3)
        expRank = bits(rankingWord, 9, 3)
        unk30Unk3D04 = bits(rankingWord, 12, 3)
        fundsRank = bits(rankingWord, 15, 3)
        tacticsRank = bits(rankingWord, 18, 3)
        survivalRank = bits(rankingWord, 21, 3)
        unk30UnkF00 = bits(rankingWord, 24, 8)
        val configWord = u32(bytes, offset + 0x40)
        unitColor = bits(configWord, 0, 1)
        disableTerrainDisplay = bits(configWord, 1, 1)
        unitDisplayType = bits(configWord, 2, 2)
        autoCursor = bits(configWord, 4, 1)
        textSpeed = bits(configWord, 5, 2)
        gameSpeed = bits(configWord, 7, 1)
        disableBgm = bits(configWord, 8, 1)
        disableSoundEffects = bits(configWord, 9, 1)
        windowColor = bits(configWord, 10, 2)
        unk41x5 = bits(configWord, 12, 1)
        unk41x6 = bits(configWord, 13, 1)
        disableAutoEndTurns = bits(configWord, 14, 1)
        noSubtitleHelp = bits(configWord, 15, 1)
        disableGoalDisplay = bits(configWord, 16, 1)
        animationType = bits(configWord, 17, 2)
        battleForecastType = bits(configWord, 19, 2)
        controller = bits(configWord, 21, 1)
        rankDisplay = bits(configWord, 22, 1)
        debugControlRed = bits(configWord, 23, 2)
        debugControlGreen = bits(configWord, 25, 2)
        unk43x4 = bits(configWord, 27, 5)
        configUnk440 = u8(bytes, offset + 0x44)
        configUnk441 = u8(bytes, offset + 0x45)
        configUnk442 = u8(bytes, offset + 0x46)
        configUnk443 = u8(bytes, offset + 0x47)
        unk48 = u16(bytes, offset + 0x48)
        val tutorialConfigByte = u8(bytes, offset + 0x4A)
        unk4A1 = bits(tutorialConfigByte, 0, 1)
        saveMenuType = bits(tutorialConfigByte, 1, 3)
        tutorialExecType = bits(tutorialConfigByte, 4, 4)
        tutorialCounter = u8(bytes, offset + 0x4B)
    }

    /** Writes the 0x4C bytes of this state at [offset] of [bytes]. Does not allocate. */
    public fun encode(bytes: ByteArray, offset: Int) {
        put32(bytes, offset, timeSaved)
        put32(bytes, offset + 0x04, timeChapterStarted)
        put32(bytes, offset + 0x08, partyGoldAmount)
        put8(bytes, offset + 0x0C, gameSaveSlot)
        put8(bytes, offset + 0x0D, chapterVisionRange)
        put8(bytes, offset + 0x0E, chapterIndex)
        put8(bytes, offset + 0x0F, faction)
        put16(bytes, offset + 0x10, chapterTurnNumber)
        put8(bytes, offset + 0x12, xCursor)
        put8(bytes, offset + 0x13, yCursor)
        put8(bytes, offset + 0x14, chapterStateBits)
        put8(bytes, offset + 0x15, chapterWeatherId)
        put16(bytes, offset + 0x16, chapterTotalSupportGain)
        put8(bytes, offset + 0x18, playthroughIdentifier)
        put8(bytes, offset + 0x19, unk19)
        put8(bytes, offset + 0x1A, lastUnitSortType)
        put8(bytes, offset + 0x1B, chapterModeIndex)
        put8(bytes, offset + 0x1C, unk1C0)
        put8(bytes, offset + 0x1D, unk1C1)
        put8(bytes, offset + 0x1E, unk1E)
        put8(bytes, offset + 0x1F, unk1F)
        for (i in 0 until PLAYER_NAME_SIZE) bytes[offset + 0x20 + i] = playerName[i]
        put8(bytes, offset + 0x2B, field(unk2B00, 0, 1) or field(unk2B01, 1, 7))
        put32(
            bytes, offset + 0x2C,
            field(unk2C1, 0, 1) or field(unk2C01, 1, 3) or field(unk2C04, 4, 9) or
                field(unk2C0D, 13, 10) or field(unk2C2, 23, 5) or field(unk2C3, 28, 4),
        )
        put32(bytes, offset + 0x30, totalGold)
        put32(bytes, offset + 0x34, field(unk30Unk400, 0, 20) or field(unk30Unk414, 20, 12))
        put32(
            bytes, offset + 0x38,
            field(unk30Unk81, 0, 8) or field(unk30Unk82, 8, 20) or field(unk30Unk83, 28, 4),
        )
        put32(
            bytes, offset + 0x3C,
            field(unk30UnkC00, 0, 6) or field(combatRank, 6, 3) or field(expRank, 9, 3) or
                field(unk30Unk3D04, 12, 3) or field(fundsRank, 15, 3) or
                field(tacticsRank, 18, 3) or field(survivalRank, 21, 3) or
                field(unk30UnkF00, 24, 8),
        )
        put32(
            bytes, offset + 0x40,
            field(unitColor, 0, 1) or field(disableTerrainDisplay, 1, 1) or
                field(unitDisplayType, 2, 2) or field(autoCursor, 4, 1) or
                field(textSpeed, 5, 2) or field(gameSpeed, 7, 1) or field(disableBgm, 8, 1) or
                field(disableSoundEffects, 9, 1) or field(windowColor, 10, 2) or
                field(unk41x5, 12, 1) or field(unk41x6, 13, 1) or
                field(disableAutoEndTurns, 14, 1) or field(noSubtitleHelp, 15, 1) or
                field(disableGoalDisplay, 16, 1) or field(animationType, 17, 2) or
                field(battleForecastType, 19, 2) or field(controller, 21, 1) or
                field(rankDisplay, 22, 1) or field(debugControlRed, 23, 2) or
                field(debugControlGreen, 25, 2) or field(unk43x4, 27, 5),
        )
        put8(bytes, offset + 0x44, configUnk440)
        put8(bytes, offset + 0x45, configUnk441)
        put8(bytes, offset + 0x46, configUnk442)
        put8(bytes, offset + 0x47, configUnk443)
        put16(bytes, offset + 0x48, unk48)
        put8(
            bytes, offset + 0x4A,
            field(unk4A1, 0, 1) or field(saveMenuType, 1, 3) or field(tutorialExecType, 4, 4),
        )
        put8(bytes, offset + 0x4B, tutorialCounter)
    }

    /** Struct size and layout constants. */
    public companion object {
        /** `sizeof(struct PlaySt)` in bytes. */
        public const val SIZE: Int = 0x4C

        /** Length in bytes of the player name character buffer (0x2B - 0x20 = 11 bytes). */
        private const val PLAYER_NAME_SIZE = 0x2B - 0x20
    }
}
