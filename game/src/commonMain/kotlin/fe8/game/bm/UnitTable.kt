package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * Raw-unit stand-in until the real Unit model (M6): the bytes of the four contiguous EWRAM unit
 * arrays `gUnitArrayBlue` (0x0202BE4C, 62 units), `gUnitArrayRed` (0x0202CFBC, 50),
 * `gUnitArrayGreen` (0x0202DDCC, 20) and `gUnitArrayPurple` (0x0202E36C, 5), each `struct Unit`
 * being [UNIT_SIZE] bytes, plus typed accessors for the few fields the ported functions read.
 *
 * Units are addressed by the id `GetUnit` takes (`faction + 1 + slot`, the value of the `index`
 * field), not by pointer: [unitOffset] is the `gUnitLookup` replacement. The character and class
 * data pointers stored in a unit point into the ROM; they are resolved through [rom].
 */
public class UnitTable(
    /** The ROM that `pCharacterData` / `pClassData` point into. */
    private val rom: RomView,
) {
    /** The raw unit arrays, blue then red, green and purple, as laid out in EWRAM. */
    public val bytes: ByteArray = ByteArray(TOTAL_SIZE)

    /** Copies the unit arrays out of the EWRAM image [ewram] (the 256 KiB at 0x02000000). */
    public fun loadFrom(ewram: ByteArray) {
        ewram.copyInto(bytes, 0, BLUE_ADDRESS - EWRAM_BASE, BLUE_ADDRESS - EWRAM_BASE + TOTAL_SIZE)
    }

    /** Copies the unit arrays into the EWRAM image [ewram]. */
    public fun storeTo(ewram: ByteArray) {
        bytes.copyInto(ewram, BLUE_ADDRESS - EWRAM_BASE)
    }

    /**
     * Ported from src/bmunit.c: GetUnit
     *
     * Byte offset in [bytes] of the unit with id [id] (`gUnitLookup[id & 0xFF]`), or -1 where the
     * lookup table holds NULL.
     */
    public fun unitOffset(id: Int): Int {
        val i = id and 0xFF
        val slot = when (i) {
            in 0x01..0x3E -> i - 0x01
            in 0x81..0xB2 -> BLUE_COUNT + i - 0x81
            in 0x41..0x54 -> BLUE_COUNT + RED_COUNT + i - 0x41
            in 0xC1..0xC5 -> BLUE_COUNT + RED_COUNT + GREEN_COUNT + i - 0xC1
            else -> return -1
        }
        return slot * UNIT_SIZE
    }

    /** `UNIT_IS_VALID(GetUnit(id))`: the unit exists and has a character (`pCharacterData`). */
    public fun isValid(id: Int): Boolean {
        val o = unitOffset(id)
        return o >= 0 && u32(bytes, o + OFF_CHARACTER) != 0
    }

    /**
     * Ported from src/bmunit.c: InitUnits
     *
     * Clears every unit that `GetUnit` can return for ids 0..0xFF (`ClearUnit`: all 0x48 bytes
     * zeroed, `index` kept) and stores its id in `index` (0x0B).
     */
    public fun initUnits() {
        for (id in 0 until 0x100) {
            val o = unitOffset(id)
            if (o < 0) continue
            bytes.fill(0, o, o + UNIT_SIZE)
            bytes[o + 0x0B] = id.toByte()
        }
    }

    /** `pCharacterData->number` of unit [id], read from the ROM (the unit must be valid). */
    public fun charId(id: Int): Int = rom.u8(u32(bytes, unitOffset(id) + OFF_CHARACTER) + 4)

    /** `index` (s8, 0x0B) of unit [id]. */
    public fun index(id: Int): Int = s8(bytes, unitOffset(id) + 0x0B)

    /** `state` (u32, 0x0C) of unit [id]. */
    public fun state(id: Int): Int = u32(bytes, unitOffset(id) + 0x0C)

    /** Sets `Unit.state` of unit [id] (the unit must exist). */
    public fun setState(id: Int, value: Int) {
        StructCodec.put32(bytes, unitOffset(id) + 0x0C, value)
    }

    /** `xPos` (s8, 0x10) of unit [id]. */
    public fun xPos(id: Int): Int = s8(bytes, unitOffset(id) + 0x10)

    /** `yPos` (s8, 0x11) of unit [id]. */
    public fun yPos(id: Int): Int = s8(bytes, unitOffset(id) + 0x11)

    /** `statusIndex` (low nibble of byte 0x30) of unit [id]. */
    public fun statusIndex(id: Int): Int = u8(bytes, unitOffset(id) + 0x30) and 0xF

    /**
     * `UNIT_CATTRIBUTES`: `pCharacterData->attributes | pClassData->attributes` of unit [id],
     * both read from the ROM.
     */
    public fun cAttributes(id: Int): Int {
        val o = unitOffset(id)
        return rom.u32(u32(bytes, o + OFF_CHARACTER) + OFF_ATTRIBUTES) or
            rom.u32(u32(bytes, o + OFF_CLASS) + OFF_ATTRIBUTES)
    }

    /**
     * Transcribed from src/bmphase.c: GetPhaseAbleUnitCount (not verified: no trace has a unit
     * that the asleep/berserk/unselectable/NOT_ABLE exclusions would skip)
     *
     * Number of units of [faction] (0x00 blue, 0x40 green, 0x80 red) that can act this phase:
     * valid, not unselectable / dead / undeployed / rescued / under a roof / bit 16, not asleep
     * or berserk, and without the unselectable class or character attribute.
     */
    public fun getPhaseAbleUnitCount(faction: Int): Int {
        var count = 0
        for (id in faction + 1 until faction + 0x40) {
            if (!isValid(id)) continue
            if ((state(id) and NOT_ABLE) != 0) continue
            val status = statusIndex(id)
            if (status == STATUS_SLEEP || status == STATUS_BERSERK) continue
            if ((cAttributes(id) and CA_UNSELECTABLE) != 0) continue
            count += 1
        }
        return count
    }

    /**
     * Ported from src/bmunit.c: GetUnitFromCharId
     *
     * The id (as taken by [unitOffset]) of the first valid unit, in id order from 1 to 0xFF,
     * whose character is [charId]; -1 where the C returns NULL.
     */
    public fun getUnitFromCharId(charId: Int): Int {
        for (id in 1 until 0x100) {
            if (isValid(id) && charId(id) == charId) return id
        }
        return -1
    }

    /**
     * Ported from src/prep_sallycursor.c: GetPlayerLeaderPid
     *
     * The character id of the player leader for the current route and chapter in [playSt]:
     * Eirika (Ephraim on his route or in chapter 5x), unless the battle map is not a story map
     * and the leader is missing or undeployed, in which case the first available blue unit.
     * [nodes] feeds [BattleMapKind.getBattleMapKind].
     *
     * NOT verified: the reference trace only covers the story-map early return, so it carries
     * no `@VerifiesPort` test and does not count as ported.
     */
    public fun getPlayerLeaderPid(playSt: PlaySt, nodes: BattleMapNodes): Int {
        val ephraim = playSt.chapterModeIndex == CHAPTER_MODE_EPHRAIM
        val pid = if (ephraim) CHARACTER_EPHRAIM else CHARACTER_EIRIKA
        if ((playSt.chapterIndex and 0xFF) == CHAPTER_L_5X) return CHARACTER_EPHRAIM
        if (BattleMapKind.getBattleMapKind(playSt.chapterIndex, nodes) == BattleMapKind.STORY) {
            return pid
        }
        val leader = getUnitFromCharId(pid)
        if (leader >= 0 && (state(leader) and US_NOT_DEPLOYED) == 0) return pid
        for (id in 1 until 0x40) {
            if (!isValid(id)) continue
            if ((state(id) and US_UNAVAILABLE) != 0) continue
            return charId(id)
        }
        return pid
    }

    /** Layout and flag constants. */
    public companion object {
        /** `sizeof(struct Unit)`. */
        public const val UNIT_SIZE: Int = 0x48

        /** Base address of EWRAM. */
        public const val EWRAM_BASE: Int = 0x02000000

        /** `gUnitArrayBlue`, the start of the contiguous unit arrays. */
        public const val BLUE_ADDRESS: Int = 0x0202BE4C

        /** Units in `gUnitArrayBlue`. */
        public const val BLUE_COUNT: Int = 62

        /** Units in `gUnitArrayRed`. */
        public const val RED_COUNT: Int = 50

        /** Units in `gUnitArrayGreen`. */
        public const val GREEN_COUNT: Int = 20

        /** Units in `gUnitArrayPurple`. */
        public const val PURPLE_COUNT: Int = 5

        /** Total bytes of the four arrays (0x2688). */
        public const val TOTAL_SIZE: Int =
            (BLUE_COUNT + RED_COUNT + GREEN_COUNT + PURPLE_COUNT) * UNIT_SIZE

        private const val OFF_CHARACTER = 0x00
        private const val OFF_CLASS = 0x04
        private const val OFF_ATTRIBUTES = 0x28

        private const val US_UNSELECTABLE = 1 shl 1
        private const val US_DEAD = 1 shl 2
        private const val US_NOT_DEPLOYED = 1 shl 3
        private const val US_RESCUED = 1 shl 5
        private const val US_UNDER_A_ROOF = 1 shl 7
        private const val US_BIT16 = 1 shl 16
        private const val US_UNAVAILABLE = US_DEAD or US_NOT_DEPLOYED or US_BIT16
        private const val NOT_ABLE =
            US_UNSELECTABLE or US_DEAD or US_NOT_DEPLOYED or US_RESCUED or US_UNDER_A_ROOF or
                US_BIT16
        private const val STATUS_SLEEP = 2
        private const val STATUS_BERSERK = 4
        private const val CA_UNSELECTABLE = 1 shl 20
        private const val CHARACTER_EIRIKA = 0x01
        private const val CHARACTER_EPHRAIM = 0x0F
        private const val CHAPTER_L_5X = 0x05
        private const val CHAPTER_MODE_EPHRAIM = 3
    }
}
