package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8
import kotlin.math.abs

/**
 * Support reliance data, level, growth and turn-based support gain of `src/bmreliance.c`.
 *
 * In Fire Emblem: The Sacred Stones, units who have support data in ROM gain support points
 * when standing next to each other (or while rescuing each other) at the start of each player
 * turn ([processTurnSupportExp]). Total support levels per unit are capped at
 * [MAX_SIMULTANEOUS_SUPPORT_COUNT] (5), and support points are capped by the upper bound of the
 * unit's current support level ([SUPPORT_MAX_EXP_LOOKUP]) until the level-up is recorded
 * (`supportBits`).
 *
 * Support levels grant stat bonuses during battle ([getUnitSupportBonuses]) based on the affinities
 * of both units.
 *
 * @param rom the ROM holding character and support data tables.
 * @param table the active unit arrays (`gUnitArrayBlue`, `gUnitArrayRed`, etc.).
 * @param playSt `gPlaySt` (`chapterTurnNumber`, `chapterStateBits`, `chapterTotalSupportGain`).
 * @param gameStateBits bitfield for game state (`gBmSt.gameStateBits`); bit 0x40 is link arena.
 */
public class SupportReliance(
    private val rom: RomView,
    private val table: UnitTable,
    private val playSt: PlaySt,
    private val gameStateBits: () -> Int = { 0 },
) {
    /** `pSupportData` (`CharacterData` 0x2C) of the unit's character, 0 for none. */
    public fun supportData(bytes: ByteArray, offset: Int): Int =
        rom.u32(u32(bytes, offset + OFF_CHARACTER) + 0x2C)

    /**
     * `GetUnitSupporterCount`: `supportCount` (`SupportData` 0x15), 0 without support data.
     */
    public fun getUnitSupporterCount(bytes: ByteArray, offset: Int): Int {
        val d = supportData(bytes, offset)
        return if (d == 0) 0 else rom.u8(d + 0x15)
    }

    /** Helper taking unit [id]. */
    public fun getUnitSupporterCount(id: Int): Int =
        getUnitSupporterCount(table.bytes, table.unitOffset(id))

    /**
     * Ported from src/bmreliance.c: GetUnitSupporterCharacter
     *
     * Character ID of supporter [num] (`characters[num]` of `SupportData`), 0 without support data.
     */
    public fun getUnitSupporterCharacter(bytes: ByteArray, offset: Int, num: Int): Int {
        val d = supportData(bytes, offset)
        return if (d == 0) 0 else rom.u8(d + num)
    }

    /** Helper taking unit [id]. */
    public fun getUnitSupporterCharacter(id: Int, num: Int): Int =
        getUnitSupporterCharacter(table.bytes, table.unitOffset(id), num)

    /**
     * Ported from src/bmreliance.c: GetUnitSupporterUnit
     *
     * The table byte offset of the valid unit of the same faction that is the unit's supporter
     * [num], or -1 if not found.
     */
    public fun getUnitSupporterUnit(bytes: ByteArray, offset: Int, num: Int): Int {
        val charId = getUnitSupporterCharacter(bytes, offset, num)
        val faction = s8(bytes, offset + OFF_INDEX) and 0xC0
        for (id in faction + 1 until faction + 0x40) {
            if (!table.isValid(id)) continue
            if (table.charId(id) == charId) return table.unitOffset(id)
        }
        return -1
    }

    /** Helper taking unit [id]. */
    public fun getUnitSupporterUnit(id: Int, num: Int): Int =
        getUnitSupporterUnit(table.bytes, table.unitOffset(id), num)

    /** `GetUnitSupporterNum`: the support slot of the unit that holds [charId], or -1. */
    public fun getUnitSupporterNum(bytes: ByteArray, offset: Int, charId: Int): Int {
        val count = getUnitSupporterCount(bytes, offset)
        for (i in 0 until count) {
            if (getUnitSupporterCharacter(bytes, offset, i) == charId) return i
        }
        return -1
    }

    /** Helper taking unit [id]. */
    public fun getUnitSupporterNum(id: Int, charId: Int): Int =
        getUnitSupporterNum(table.bytes, table.unitOffset(id), charId)

    /**
     * Transcribed from src/bmreliance.c: GetUnitSupportLevel (not verified: every traced support
     * exp is 80 or less, so only level 0 is compared)
     *
     * The support level of the unit's support slot [num] from its support points (a [num] of -1
     * reads the byte before `supports`, as the C does):
     * 0 none (80 or less), 1 C (above 80), 2 B (above 160), 3 A (above 240).
     */
    public fun getUnitSupportLevel(bytes: ByteArray, offset: Int, num: Int): Int {
        val supportExp = u8(bytes, offset + OFF_SUPPORTS + num)
        return when {
            supportExp > 240 -> SUPPORT_LEVEL_A
            supportExp > 160 -> SUPPORT_LEVEL_B
            supportExp > 80 -> SUPPORT_LEVEL_C
            else -> SUPPORT_LEVEL_NONE
        }
    }

    /** Helper taking unit [id]. */
    public fun getUnitSupportLevel(id: Int, num: Int): Int =
        getUnitSupportLevel(table.bytes, table.unitOffset(id), num)

    /**
     * Transcribed from src/bmreliance.c: GetUnitTotalSupportLevel (not verified: every traced
     * support level is 0, so only the empty sum is compared)
     *
     * The sum of the support levels across all supporter slots of the unit.
     */
    public fun getUnitTotalSupportLevel(bytes: ByteArray, offset: Int): Int {
        val count = getUnitSupporterCount(bytes, offset)
        var result = 0
        for (i in 0 until count) {
            result += getUnitSupportLevel(bytes, offset, i)
        }
        return result
    }

    /** Helper taking unit [id]. */
    public fun getUnitTotalSupportLevel(id: Int): Int =
        getUnitTotalSupportLevel(table.bytes, table.unitOffset(id))

    /**
     * Ported from src/bmreliance.c: UnitGainSupportExp
     *
     * Increases the unit's support exp with supporter [num] by its growth rate, capped at the
     * ceiling of the current support level, and adds the actual gain to
     * `gPlaySt.chapterTotalSupportGain`. The captures compare the uncapped gain only; the cap
     * branch is checked by synthetic tests.
     */
    public fun unitGainSupportExp(bytes: ByteArray, offset: Int, num: Int) {
        val d = supportData(bytes, offset)
        if (d != 0) {
            var gain = rom.u8(d + 0x0E + num)
            val currentExp = u8(bytes, offset + OFF_SUPPORTS + num)
            val maxExp = SUPPORT_MAX_EXP_LOOKUP[getUnitSupportLevel(bytes, offset, num)]

            if (currentExp + gain > maxExp) {
                gain = maxExp - currentExp
            }

            bytes[offset + OFF_SUPPORTS + num] = (currentExp + gain).toByte()
            playSt.chapterTotalSupportGain += gain
        }
    }

    /** Helper taking unit [id]. */
    public fun unitGainSupportExp(id: Int, num: Int) {
        unitGainSupportExp(table.bytes, table.unitOffset(id), num)
    }

    /**
     * Ported from src/bmreliance.c: InitSupportBonuses
     *
     * Clears the six battle stat bonuses (attack, defense, hit, avoid, crit, dodge) to zero.
     */
    public fun initSupportBonuses(bonuses: IntArray) {
        bonuses.fill(0, 0, 6)
    }

    /**
     * Ported from src/bmreliance.c: ProcessTurnSupportExp
     *
     * Awards support experience at the start of each player turn (skipped on turn 1 and on extra
     * maps) to pairs of deployed player units within 1 tile of each other (or where one rescues
     * the other) whose total support level is below the maximum of 5.
     */
    public fun processTurnSupportExp() {
        if (playSt.chapterTurnNumber == 1) return
        if ((playSt.chapterStateBits and PLAY_FLAG_EXTRA_MAP) != 0) return

        val tableBytes = table.bytes
        for (i in 1 until 0x40) {
            if (!table.isValid(i)) continue
            val unitOffset = table.unitOffset(i)
            val unitState = u32(tableBytes, unitOffset + OFF_STATE)
            if ((unitState and UnitTable.US_UNAVAILABLE) != 0) continue

            if (getUnitTotalSupportLevel(tableBytes, unitOffset) >=
                MAX_SIMULTANEOUS_SUPPORT_COUNT
            ) {
                continue
            }

            val jMax = getUnitSupporterCount(tableBytes, unitOffset)
            for (j in 0 until jMax) {
                val otherOffset = getUnitSupporterUnit(tableBytes, unitOffset, j)
                if (otherOffset < 0) continue

                val otherState = u32(tableBytes, otherOffset + OFF_STATE)
                if ((otherState and UnitTable.US_UNAVAILABLE) != 0) continue

                val otherFaction = s8(tableBytes, otherOffset + OFF_INDEX) and 0xC0
                if (otherFaction != FACTION_BLUE) continue

                val uX = s8(tableBytes, unitOffset + OFF_X)
                val uY = s8(tableBytes, unitOffset + OFF_Y)
                val oX = s8(tableBytes, otherOffset + OFF_X)
                val oY = s8(tableBytes, otherOffset + OFF_Y)
                val distance = abs(uX - oX) + abs(uY - oY)

                when (distance) {
                    0 -> {
                        val unitRescue = u8(tableBytes, unitOffset + OFF_RESCUE)
                        val otherIndex = u8(tableBytes, otherOffset + OFF_INDEX)
                        if (unitRescue != otherIndex) continue
                    }
                    1 -> {
                        if ((unitState and US_RESCUED) != 0 ||
                            (otherState and US_RESCUED) != 0
                        ) {
                            continue
                        }
                    }
                    else -> continue
                }

                val otherTotal = getUnitTotalSupportLevel(tableBytes, otherOffset)
                if (otherTotal < MAX_SIMULTANEOUS_SUPPORT_COUNT) {
                    unitGainSupportExp(tableBytes, unitOffset, j)
                }
            }
        }
    }

    /**
     * Transcribed from src/bmreliance.c: GetUnitSupportBonuses (not verified: the traced units
     * have no supporter on the map, so only the empty sum is compared). Unlike the C, an affinity
     * missing from `gAffinityBonuses` adds nothing (the C reads past the table).
     *
     * Writes the halved support bonuses (attack, defense, hit, avoid, crit, dodge) of the unit at
     * [offset] of [bytes] into [bonusOut] (6 ints): for each supporter within 3 tiles that is
     * not unavailable or rescued, both characters' affinity bonuses at their support level.
     * Returns the C's bit mask: bit `i` set when both sides have a support level above 0 with
     * supporter `i`.
     */
    public fun getUnitSupportBonuses(bytes: ByteArray, offset: Int, bonusOut: IntArray): Int {
        initSupportBonuses(bonusOut)
        var result = 0
        val count = getUnitSupporterCount(bytes, offset)
        val myAffinity = rom.u8(u32(bytes, offset + OFF_CHARACTER) + 9)
        val myChar = rom.u8(u32(bytes, offset + OFF_CHARACTER) + 4)

        for (i in 0 until count) {
            result = result shr 1
            val other = getUnitSupporterUnit(bytes, offset, i)
            if (other < 0) continue
            val tableBytes = table.bytes

            // 0x40 is BM_FLAG_LINKARENA: the 3-tile support range is not enforced in link arena.
            if ((gameStateBits() and 0x40) == 0) {
                val distance = abs(s8(bytes, offset + OFF_X) - s8(tableBytes, other + OFF_X)) +
                    abs(s8(bytes, offset + OFF_Y) - s8(tableBytes, other + OFF_Y))
                if (distance > SUPPORT_BONUSES_MAX_DISTANCE) continue
            }

            val unavailable = UnitTable.US_UNAVAILABLE or US_RESCUED
            if ((u32(tableBytes, other + OFF_STATE) and unavailable) != 0) continue

            val otherNum = getUnitSupporterNum(tableBytes, other, myChar)
            val otherSupportLevel = getUnitSupportLevel(tableBytes, other, otherNum)
            applyAffinitySupportBonuses(
                bonusOut,
                rom.u8(u32(tableBytes, other + OFF_CHARACTER) + 9),
                otherSupportLevel,
            )

            val mySupportLevel = getUnitSupportLevel(bytes, offset, i)
            applyAffinitySupportBonuses(bonusOut, myAffinity, mySupportLevel)

            if (otherSupportLevel != 0 && mySupportLevel != 0) result += 1 shl (count - 1)
        }

        for (i in 0 until 6) bonusOut[i] = (bonusOut[i] and 0xFF) / 2
        return result
    }

    /**
     * Adds the affinity table row of [affinity] (`gAffinityBonuses`, `struct SupportBonuses`: the
     * affinity byte, then the attack, defense, hit, avoid, crit, dodge bonus per support level)
     * times the support [level] to [bonusOut]; nothing for an affinity without a row.
     */
    public fun applyAffinitySupportBonuses(bonusOut: IntArray, affinity: Int, level: Int) {
        var row = AFFINITY_BONUSES
        while (rom.u8(row) != 0) {
            if (rom.u8(row) == affinity) {
                for (i in 0 until 6) {
                    bonusOut[i] = (bonusOut[i] + level * rom.u8(row + 1 + i)) and 0xFF
                }
                return
            }
            row += 7
        }
    }

    /** Constants for support reliance limits, levels, and thresholds. */
    public companion object {
        /** Maximum total support levels a unit can have across all partners (5). */
        public const val MAX_SIMULTANEOUS_SUPPORT_COUNT: Int = 5

        /** Maximum Manhattan distance for support bonuses to apply (3 tiles). */
        public const val SUPPORT_BONUSES_MAX_DISTANCE: Int = 3

        /** Support level 0 (none). */
        public const val SUPPORT_LEVEL_NONE: Int = 0

        /** Support level 1 (C rank). */
        public const val SUPPORT_LEVEL_C: Int = 1

        /** Support level 2 (B rank). */
        public const val SUPPORT_LEVEL_B: Int = 2

        /** Support level 3 (A rank). */
        public const val SUPPORT_LEVEL_A: Int = 3

        /** Minimum support exp required for C rank (81). */
        public const val SUPPORT_EXP_C: Int = 81

        /** Minimum support exp required for B rank (161). */
        public const val SUPPORT_EXP_B: Int = 161

        /** Minimum support exp required for A rank (241). */
        public const val SUPPORT_EXP_A: Int = 241

        /** Exp cap per support level until the level-up is recorded (`supportBits`). */
        public val SUPPORT_MAX_EXP_LOOKUP: IntArray = intArrayOf(
            SUPPORT_EXP_C - 1,
            SUPPORT_EXP_B - 1,
            SUPPORT_EXP_A - 1,
            SUPPORT_EXP_A,
        )

        private const val FACTION_BLUE = 0x00
        private const val PLAY_FLAG_EXTRA_MAP = 1 shl 7
        private const val US_RESCUED = 1 shl 5
        private const val AFFINITY_BONUSES = 0x088B05F8

        private const val OFF_CHARACTER = 0x00
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_X = 0x10
        private const val OFF_Y = 0x11
        private const val OFF_RESCUE = 0x1B
        private const val OFF_SUPPORTS = 0x32
    }
}
