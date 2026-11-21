package fe8.testing

import fe8.game.RomView
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitLoader.UnitDef
import fe8.game.bm.UnitTable
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression test for `LoadUnit` (`src/bmunit.c:618-641`): `SetUnitLeaderCharId` is called only
 * in the non-player (`UNIT_FACTION(unit) != FACTION_BLUE`) autolevel branch, never in the
 * `FACTION_BLUE` autolevel branch.
 */
class UnitLoaderBlueLeaderTest {
    private fun put32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
        buf[offset + 1] = (value shr 8).toByte()
        buf[offset + 2] = (value shr 16).toByte()
        buf[offset + 3] = (value shr 24).toByte()
    }

    private fun syntheticRom(): RomView {
        val chars = ByteArray(0x80 * CHARACTER_DATA_SIZE)
        // Blue test character (id 0x02): base level 1, 5 supports (< 7, supports[6] unused).
        val blueOff = (0x02 - 1) * CHARACTER_DATA_SIZE
        chars[blueOff + 0x04] = 0x02
        chars[blueOff + 0x05] = 0x01
        chars[blueOff + 0x0B] = 1
        chars[blueOff + 0x0C] = 20
        for (i in 0x0D..0x12) chars[blueOff + i] = 5
        for (i in 0x1C..0x22) chars[blueOff + i] = 50
        put32(chars, blueOff + 0x2C, SUPPORT_DATA_ADDR)

        // Non-blue test character (id 0x80 >= 0x40, not a boss).
        val enemyOff = (0x80 - 1) * CHARACTER_DATA_SIZE
        chars[enemyOff + 0x04] = 0x80.toByte()
        chars[enemyOff + 0x05] = 0x01
        chars[enemyOff + 0x0B] = 1
        chars[enemyOff + 0x0C] = 20
        for (i in 0x0D..0x12) chars[enemyOff + i] = 5
        for (i in 0x1C..0x22) chars[enemyOff + i] = 50

        val classes = ByteArray(CLASS_DATA_SIZE)
        classes[0x04] = 0x01
        for (i in 0x14..0x19) classes[i] = 20
        for (i in 0x1B..0x21) classes[i] = 50

        val supports = ByteArray(0x20)
        val initialSupportExp = intArrayOf(15, 25, 10, 5, 0)
        for (i in initialSupportExp.indices) {
            supports[0x07 + i] = initialSupportExp[i].toByte()
        }
        supports[0x15] = initialSupportExp.size.toByte()

        return RomView(
            listOf(
                CHARACTER_DATA to chars,
                CLASS_DATA to classes,
                SUPPORT_DATA_ADDR to supports,
            ),
        )
    }

    private fun def(allegiance: Int, autolevel: Boolean, charIndex: Int): UnitDef = UnitDef(
        charIndex = charIndex,
        classIndex = 0x01,
        leaderCharIndex = LEADER_CHAR_ID,
        autolevel = autolevel,
        allegiance = allegiance,
        level = 3,
        x = 2,
        y = 3,
        genMonster = false,
        itemDrop = false,
        sumFlag = false,
        redaCount = 0,
        redas = 0,
        items = intArrayOf(0, 0, 0, 0),
        ai = intArrayOf(0, 0, 0, 0),
    )

    @Test
    fun `blue autolevel load leaves supports 6 zero even when leaderCharIndex is non-zero`() {
        val rom = syntheticRom()
        val table = UnitTable(rom).also { it.initUnits() }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })

        val id = loader.loadUnit(def(allegiance = 0, autolevel = true, charIndex = 0x02)) { _, _ ->
            0
        }
        assertEquals(1, id)
        val offset = table.unitOffset(id)
        assertEquals(0, table.leaderCharId(id), "supports[6] must not receive leaderCharIndex")
        assertEquals(0, table.bytes[offset + UnitTable.OFF_LEADER].toInt() and 0xFF)
        assertEquals(0, table.getUnitLeaderCharId(id))
        assertEquals(15, table.bytes[offset + OFF_SUPPORTS].toInt() and 0xFF)
        assertEquals(25, table.bytes[offset + OFF_SUPPORTS + 1].toInt() and 0xFF)
        assertEquals(0, table.bytes[offset + OFF_SUPPORTS + 5].toInt() and 0xFF)
    }

    @Test
    fun `non-blue autolevel load writes leaderCharIndex into supports 6`() {
        val rom = syntheticRom()
        val table = UnitTable(rom).also { it.initUnits() }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })

        val redId = loader.loadUnit(
            def(allegiance = 2, autolevel = true, charIndex = 0x80),
        ) { _, _ -> 0 }
        assertEquals(0x81, redId)
        assertEquals(LEADER_CHAR_ID, table.leaderCharId(redId))
        assertEquals(LEADER_CHAR_ID, table.getUnitLeaderCharId(redId))

        val greenId = loader.loadUnit(
            def(allegiance = 1, autolevel = true, charIndex = 0x80),
        ) { _, _ -> 0 }
        assertEquals(0x41, greenId)
        assertEquals(LEADER_CHAR_ID, table.leaderCharId(greenId))
        assertEquals(LEADER_CHAR_ID, table.getUnitLeaderCharId(greenId))
    }

    @Test
    fun `non-autolevel red load leaves supports 6 zero even when leaderCharIndex is non-zero`() {
        val rom = syntheticRom()
        val table = UnitTable(rom).also { it.initUnits() }
        val loader = UnitLoader(table, rom, { 0 }, RngService(), { false })

        val redId = loader.loadUnit(
            def(allegiance = 2, autolevel = false, charIndex = 0x80),
        ) { _, _ -> 0 }
        assertEquals(0x81, redId)
        assertEquals(0, table.leaderCharId(redId))
        assertEquals(0, table.getUnitLeaderCharId(redId))
    }

    private companion object {
        const val CHARACTER_DATA = 0x08803D64
        const val CHARACTER_DATA_SIZE = 0x34
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val SUPPORT_DATA_ADDR = 0x088AE000
        const val OFF_SUPPORTS = 0x32
        const val LEADER_CHAR_ID = 0x14
    }
}
