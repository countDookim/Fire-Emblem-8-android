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
 * every field as a named property. [decode] and [encode] round-trip the 0x4C bytes exactly.
 *
 * The struct is `packed`, so GCC lays its bitfields out contiguously, least significant bit first;
 * every bit of every bitfield word is a named property here, so no reserved bits need preserving.
 * Signed fields hold their sign-extended value, unsigned ones their zero-extended value (a `u32`
 * holds its 32 bits in the [Int]). Bitfield properties are the field's value (masked to its width
 * on [encode]); a `unk_XX_YY` name keeps the decomp's suffix.
 */
public class PlaySt {
    /** `time_saved` (u32, 0x00). */
    public var timeSaved: Int = 0

    /** `time_chapter_started` (u32, 0x04). */
    public var timeChapterStarted: Int = 0

    /** `partyGoldAmount` (u32, 0x08). */
    public var partyGoldAmount: Int = 0

    /** `gameSaveSlot` (u8, 0x0C). */
    public var gameSaveSlot: Int = 0

    /** `chapterVisionRange` (u8, 0x0D): 0 means no fog. */
    public var chapterVisionRange: Int = 0

    /** `chapterIndex` (s8, 0x0E). */
    public var chapterIndex: Int = 0

    /** `faction` (u8, 0x0F): 0x00 player phase, 0x40 NPC phase, 0x80 enemy phase. */
    public var faction: Int = 0

    /** `chapterTurnNumber` (u16, 0x10). */
    public var chapterTurnNumber: Int = 0

    /** `xCursor` (u8, 0x12). */
    public var xCursor: Int = 0

    /** `yCursor` (u8, 0x13). */
    public var yCursor: Int = 0

    /** `chapterStateBits` (u8, 0x14), the `PLAY_FLAG_*` bits. */
    public var chapterStateBits: Int = 0

    /** `chapterWeatherId` (u8, 0x15). */
    public var chapterWeatherId: Int = 0

    /** `chapterTotalSupportGain` (u16, 0x16). */
    public var chapterTotalSupportGain: Int = 0

    /** `playthroughIdentifier` (u8, 0x18). */
    public var playthroughIdentifier: Int = 0

    /** `unk19` (u8, 0x19). */
    public var unk19: Int = 0

    /** `lastUnitSortType` (u8, 0x1A). */
    public var lastUnitSortType: Int = 0

    /** `chapterModeIndex` (u8, 0x1B): 1 common, 2 Eirika route, 3 Ephraim route. */
    public var chapterModeIndex: Int = 0

    /** `unk1C[0]` (u8, 0x1C). */
    public var unk1C0: Int = 0

    /** `unk1C[1]` (u8, 0x1D). */
    public var unk1C1: Int = 0

    /** `unk1E` (u8, 0x1E). */
    public var unk1E: Int = 0

    /** `unk1F` (u8, 0x1F). */
    public var unk1F: Int = 0

    /** `playerName` (char[11], 0x20..0x2A), raw bytes, copied by [decode] and [encode]. */
    public val playerName: ByteArray = ByteArray(PLAYER_NAME_SIZE)

    /** `unk_2B_00` (u8 bit 0 at 0x2B). */
    public var unk2B00: Int = 0

    /** `unk_2B_01` (u8 bits 1..7 at 0x2B). */
    public var unk2B01: Int = 0

    /** `unk_2C_1` (u32 bit 0 at 0x2C). */
    public var unk2C1: Int = 0

    /** `unk_2C_01` (u32 bits 1..3 at 0x2C). */
    public var unk2C01: Int = 0

    /** `unk_2C_04` (u32 bits 4..12 at 0x2C). */
    public var unk2C04: Int = 0

    /** `unk_2C_0D` (u32 bits 13..22 at 0x2C). */
    public var unk2C0D: Int = 0

    /** `unk_2C_2` (u32 bits 23..27 at 0x2C). */
    public var unk2C2: Int = 0

    /** `unk_2C_3` (u32 bits 28..31 at 0x2C). */
    public var unk2C3: Int = 0

    /** `unk_30.total_gold` (s32, 0x30). */
    public var totalGold: Int = 0

    /** `unk_30.unk_4_00` (u32 bits 0..19 at 0x34). */
    public var unk30Unk400: Int = 0

    /** `unk_30.unk_4_14` (u32 bits 20..31 at 0x34). */
    public var unk30Unk414: Int = 0

    /** `unk_30.unk_8_1` (u32 bits 0..7 at 0x38). */
    public var unk30Unk81: Int = 0

    /** `unk_30.unk_8_2` (u32 bits 8..27 at 0x38). */
    public var unk30Unk82: Int = 0

    /** `unk_30.unk_8_3` (u32 bits 28..31 at 0x38). */
    public var unk30Unk83: Int = 0

    /** `unk_30.unk_C_00` (u32 bits 0..5 at 0x3C). */
    public var unk30UnkC00: Int = 0

    /** `unk_30.combatRank` (u32 bits 6..8 at 0x3C). */
    public var combatRank: Int = 0

    /** `unk_30.expRank` (u32 bits 9..11 at 0x3C). */
    public var expRank: Int = 0

    /** `unk_30.unk_3D_04` (u32 bits 12..14 at 0x3C). */
    public var unk30Unk3D04: Int = 0

    /** `unk_30.fundsRank` (u32 bits 15..17 at 0x3C). */
    public var fundsRank: Int = 0

    /** `unk_30.tacticsRank` (u32 bits 18..20 at 0x3C). */
    public var tacticsRank: Int = 0

    /** `unk_30.survivalRank` (u32 bits 21..23 at 0x3C). */
    public var survivalRank: Int = 0

    /** `unk_30.unk_F_00` (u32 bits 24..31 at 0x3C). */
    public var unk30UnkF00: Int = 0

    /** `config.unitColor` (1 bit, word 0x40 bit 0). */
    public var unitColor: Int = 0

    /** `config.disableTerrainDisplay` (1 bit, bit 1). */
    public var disableTerrainDisplay: Int = 0

    /** `config.unitDisplayType` (2 bits, bits 2..3). */
    public var unitDisplayType: Int = 0

    /** `config.autoCursor` (1 bit, bit 4). */
    public var autoCursor: Int = 0

    /** `config.textSpeed` (2 bits, bits 5..6). */
    public var textSpeed: Int = 0

    /** `config.gameSpeed` (1 bit, bit 7). */
    public var gameSpeed: Int = 0

    /** `config.disableBgm` (1 bit, bit 8). */
    public var disableBgm: Int = 0

    /** `config.disableSoundEffects` (1 bit, bit 9). */
    public var disableSoundEffects: Int = 0

    /** `config.windowColor` (2 bits, bits 10..11). */
    public var windowColor: Int = 0

    /** `config.unk41_5` (1 bit, bit 12). */
    public var unk41x5: Int = 0

    /** `config.unk41_6` (1 bit, bit 13). */
    public var unk41x6: Int = 0

    /** `config.disableAutoEndTurns` (1 bit, bit 14). */
    public var disableAutoEndTurns: Int = 0

    /** `config.noSubtitleHelp` (1 bit, bit 15). */
    public var noSubtitleHelp: Int = 0

    /** `config.disableGoalDisplay` (1 bit, bit 16). */
    public var disableGoalDisplay: Int = 0

    /** `config.animationType` (2 bits, bits 17..18). */
    public var animationType: Int = 0

    /** `config.battleForecastType` (2 bits, bits 19..20). */
    public var battleForecastType: Int = 0

    /** `config.controller` (1 bit, bit 21). */
    public var controller: Int = 0

    /** `config.rankDisplay` (1 bit, bit 22). */
    public var rankDisplay: Int = 0

    /** `config.debugControlRed` (2 bits, bits 23..24). */
    public var debugControlRed: Int = 0

    /** `config.debugControlGreen` (2 bits, bits 25..26). */
    public var debugControlGreen: Int = 0

    /** `config.unk43_4` (5 bits, bits 27..31). */
    public var unk43x4: Int = 0

    /** `config.unk44[0]` (u8, 0x44). */
    public var configUnk440: Int = 0

    /** `config.unk44[1]` (u8, 0x45). */
    public var configUnk441: Int = 0

    /** `config.unk44[2]` (u8, 0x46). */
    public var configUnk442: Int = 0

    /** `config.unk44[3]` (u8, 0x47). */
    public var configUnk443: Int = 0

    /** `unk48` (u16, 0x48). */
    public var unk48: Int = 0

    /** `unk4A_1` (u8 bit 0 at 0x4A). */
    public var unk4A1: Int = 0

    /** `save_menu_type` (u8 bits 1..3 at 0x4A). */
    public var saveMenuType: Int = 0

    /** `tutorial_exec_type` (u8 bits 4..7 at 0x4A). */
    public var tutorialExecType: Int = 0

    /** `tutorial_counter` (u8, 0x4B). */
    public var tutorialCounter: Int = 0

    /** Reads the 0x4C bytes at [offset] of [bytes] into this state. Does not allocate. */
    public fun decode(bytes: ByteArray, offset: Int) {
        val b = bytes
        val o = offset
        timeSaved = u32(b, o)
        timeChapterStarted = u32(b, o + 0x04)
        partyGoldAmount = u32(b, o + 0x08)
        gameSaveSlot = u8(b, o + 0x0C)
        chapterVisionRange = u8(b, o + 0x0D)
        chapterIndex = s8(b, o + 0x0E)
        faction = u8(b, o + 0x0F)
        chapterTurnNumber = u16(b, o + 0x10)
        xCursor = u8(b, o + 0x12)
        yCursor = u8(b, o + 0x13)
        chapterStateBits = u8(b, o + 0x14)
        chapterWeatherId = u8(b, o + 0x15)
        chapterTotalSupportGain = u16(b, o + 0x16)
        playthroughIdentifier = u8(b, o + 0x18)
        unk19 = u8(b, o + 0x19)
        lastUnitSortType = u8(b, o + 0x1A)
        chapterModeIndex = u8(b, o + 0x1B)
        unk1C0 = u8(b, o + 0x1C)
        unk1C1 = u8(b, o + 0x1D)
        unk1E = u8(b, o + 0x1E)
        unk1F = u8(b, o + 0x1F)
        for (i in 0 until PLAYER_NAME_SIZE) playerName[i] = b[o + 0x20 + i]
        val b2b = u8(b, o + 0x2B)
        unk2B00 = bits(b2b, 0, 1)
        unk2B01 = bits(b2b, 1, 7)
        val w2c = u32(b, o + 0x2C)
        unk2C1 = bits(w2c, 0, 1)
        unk2C01 = bits(w2c, 1, 3)
        unk2C04 = bits(w2c, 4, 9)
        unk2C0D = bits(w2c, 13, 10)
        unk2C2 = bits(w2c, 23, 5)
        unk2C3 = bits(w2c, 28, 4)
        totalGold = u32(b, o + 0x30)
        val w34 = u32(b, o + 0x34)
        unk30Unk400 = bits(w34, 0, 20)
        unk30Unk414 = bits(w34, 20, 12)
        val w38 = u32(b, o + 0x38)
        unk30Unk81 = bits(w38, 0, 8)
        unk30Unk82 = bits(w38, 8, 20)
        unk30Unk83 = bits(w38, 28, 4)
        val w3c = u32(b, o + 0x3C)
        unk30UnkC00 = bits(w3c, 0, 6)
        combatRank = bits(w3c, 6, 3)
        expRank = bits(w3c, 9, 3)
        unk30Unk3D04 = bits(w3c, 12, 3)
        fundsRank = bits(w3c, 15, 3)
        tacticsRank = bits(w3c, 18, 3)
        survivalRank = bits(w3c, 21, 3)
        unk30UnkF00 = bits(w3c, 24, 8)
        val c = u32(b, o + 0x40)
        unitColor = bits(c, 0, 1)
        disableTerrainDisplay = bits(c, 1, 1)
        unitDisplayType = bits(c, 2, 2)
        autoCursor = bits(c, 4, 1)
        textSpeed = bits(c, 5, 2)
        gameSpeed = bits(c, 7, 1)
        disableBgm = bits(c, 8, 1)
        disableSoundEffects = bits(c, 9, 1)
        windowColor = bits(c, 10, 2)
        unk41x5 = bits(c, 12, 1)
        unk41x6 = bits(c, 13, 1)
        disableAutoEndTurns = bits(c, 14, 1)
        noSubtitleHelp = bits(c, 15, 1)
        disableGoalDisplay = bits(c, 16, 1)
        animationType = bits(c, 17, 2)
        battleForecastType = bits(c, 19, 2)
        controller = bits(c, 21, 1)
        rankDisplay = bits(c, 22, 1)
        debugControlRed = bits(c, 23, 2)
        debugControlGreen = bits(c, 25, 2)
        unk43x4 = bits(c, 27, 5)
        configUnk440 = u8(b, o + 0x44)
        configUnk441 = u8(b, o + 0x45)
        configUnk442 = u8(b, o + 0x46)
        configUnk443 = u8(b, o + 0x47)
        unk48 = u16(b, o + 0x48)
        val b4a = u8(b, o + 0x4A)
        unk4A1 = bits(b4a, 0, 1)
        saveMenuType = bits(b4a, 1, 3)
        tutorialExecType = bits(b4a, 4, 4)
        tutorialCounter = u8(b, o + 0x4B)
    }

    /** Writes the 0x4C bytes of this state at [offset] of [bytes]. Does not allocate. */
    public fun encode(bytes: ByteArray, offset: Int) {
        val b = bytes
        val o = offset
        put32(b, o, timeSaved)
        put32(b, o + 0x04, timeChapterStarted)
        put32(b, o + 0x08, partyGoldAmount)
        put8(b, o + 0x0C, gameSaveSlot)
        put8(b, o + 0x0D, chapterVisionRange)
        put8(b, o + 0x0E, chapterIndex)
        put8(b, o + 0x0F, faction)
        put16(b, o + 0x10, chapterTurnNumber)
        put8(b, o + 0x12, xCursor)
        put8(b, o + 0x13, yCursor)
        put8(b, o + 0x14, chapterStateBits)
        put8(b, o + 0x15, chapterWeatherId)
        put16(b, o + 0x16, chapterTotalSupportGain)
        put8(b, o + 0x18, playthroughIdentifier)
        put8(b, o + 0x19, unk19)
        put8(b, o + 0x1A, lastUnitSortType)
        put8(b, o + 0x1B, chapterModeIndex)
        put8(b, o + 0x1C, unk1C0)
        put8(b, o + 0x1D, unk1C1)
        put8(b, o + 0x1E, unk1E)
        put8(b, o + 0x1F, unk1F)
        for (i in 0 until PLAYER_NAME_SIZE) b[o + 0x20 + i] = playerName[i]
        put8(b, o + 0x2B, field(unk2B00, 0, 1) or field(unk2B01, 1, 7))
        put32(
            b, o + 0x2C,
            field(unk2C1, 0, 1) or field(unk2C01, 1, 3) or field(unk2C04, 4, 9) or
                field(unk2C0D, 13, 10) or field(unk2C2, 23, 5) or field(unk2C3, 28, 4),
        )
        put32(b, o + 0x30, totalGold)
        put32(b, o + 0x34, field(unk30Unk400, 0, 20) or field(unk30Unk414, 20, 12))
        put32(
            b, o + 0x38,
            field(unk30Unk81, 0, 8) or field(unk30Unk82, 8, 20) or field(unk30Unk83, 28, 4),
        )
        put32(
            b, o + 0x3C,
            field(unk30UnkC00, 0, 6) or field(combatRank, 6, 3) or field(expRank, 9, 3) or
                field(unk30Unk3D04, 12, 3) or field(fundsRank, 15, 3) or
                field(tacticsRank, 18, 3) or field(survivalRank, 21, 3) or
                field(unk30UnkF00, 24, 8),
        )
        put32(
            b, o + 0x40,
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
        put8(b, o + 0x44, configUnk440)
        put8(b, o + 0x45, configUnk441)
        put8(b, o + 0x46, configUnk442)
        put8(b, o + 0x47, configUnk443)
        put16(b, o + 0x48, unk48)
        put8(
            b, o + 0x4A,
            field(unk4A1, 0, 1) or field(saveMenuType, 1, 3) or field(tutorialExecType, 4, 4),
        )
        put8(b, o + 0x4B, tutorialCounter)
    }

    /** Struct size and layout constants. */
    public companion object {
        /** `sizeof(struct PlaySt)`. */
        public const val SIZE: Int = 0x4C

        private const val PLAYER_NAME_SIZE = 0x2B - 0x20
    }
}
