package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleUnit
import fe8.game.bm.MapLevelUpStats
import fe8.game.bm.UnitTable
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.extract.Symbols
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Original map level-up stat redraws, seeded from the preceding capture. */
class MapLevelUpStatsDifferentialTest {
    @Test
    @VerifiesPort("PutManimLevelUpStat", "GetManimLevelUpStatGain", "GetManimLevelUpBaseStat")
    fun `gain redraws match captured buffers`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val run = ref.run("ram_trace_prologue_levelup")
        val nm = requireNotNull(ProcArrayDump.nmFile())
        assertEquals(0x089A3ED4, Symbols(nm.readLines())["gManimLevelUpLabelInfoList"].address)
        val symbols = RamSymbols.fromEnvironment()
        val bg0Address = symbols["gBG0TilemapBuffer"].address - EWRAM
        val bg1Address = symbols["gBG1TilemapBuffer"].address - EWRAM
        val activeFont = symbols["gActiveFont"].address - EWRAM
        val specialChars = symbols["sSpecialCharStList"].address - EWRAM
        val battleActor = symbols["gBattleActor"].address - EWRAM
        // The initial panel already contains all nine pre-gain values. Replaying each draw
        // over that capture checks the result against the original font's tile assignments.
        val initial = run.region("r06519", "ewram")
        val initialText = TextSystem(romBytes, TextVram(), Fe8Text.CONFIG)
        initialText.initTextFont(null, 0x06001000, 0x80, 0)
        val initialFont = u32(initial, activeFont) - EWRAM
        initialText.activeFont.chrCounter = u16(initial, initialFont + 0x12)
        initialText.loadSpecialCharList(initial, specialChars)
        val initialBg = ShortArray(1024) { u16(initial, bg0Address + 2 * it).toShort() }
        val initialUnits = UnitTable(rom).also { it.loadFrom(initial) }
        val initialBattle = BattleUnit()
        initial.copyInto(initialBattle.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
        val initialStats = MapLevelUpStats(
            rom, initialUnits, arrayOf(initialBattle), initialText, initialBg,
        )
        for (stat in 0..8) initialStats.putManimLevelUpStat(0, 1, 1, stat, false)
        assertArrayEquals(
            ShortArray(1024) { u16(initial, bg0Address + 2 * it).toShort() }, initialBg,
            "initial BG0",
        )
        for ((frame, stat) in listOf(6563 to 0, 6584 to 1, 6605 to 3, 6626 to 5, 6647 to 6)) {
            fun dump(step: Int) = run.region(
                "%s%05d".format(if (step % 10 == 0) "f" else "r", step), "ewram",
            )
            val before = dump(frame - 1)
            val after = dump(frame)
            val host = TextVram()
            val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
            text.initTextFont(null, 0x06001000, 0x80, 0)
            val font = u32(before, activeFont) - EWRAM
            text.activeFont.chrCounter = u16(before, font + 0x12)
            text.loadSpecialCharList(before, specialChars)
            val bg0 = ShortArray(1024) { u16(before, bg0Address + 2 * it).toShort() }
            val units = UnitTable(rom).also { it.loadFrom(before) }
            val battle = BattleUnit()
            before.copyInto(battle.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
            val stats = MapLevelUpStats(rom, units, arrayOf(battle), text, bg0)
            val gain = when (stat) {
                0 -> 1
                1 -> battle.changeHP
                3 -> battle.changeSkl
                5 -> battle.changeLck
                else -> battle.changeDef
            }
            assertEquals(gain, stats.getManimLevelUpStatGain(0, stat), "gain at f$frame")
            stats.putManimLevelUpStat(0, 1, 1, stat, true)
            assertArrayEquals(
                ShortArray(1024) { u16(after, bg0Address + 2 * it).toShort() }, bg0, "BG0 f$frame",
            )
            assertArrayEquals(
                before.copyOfRange(bg1Address, bg1Address + 0x800),
                after.copyOfRange(bg1Address, bg1Address + 0x800), "BG1 f$frame",
            )
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int = u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    private companion object {
        const val EWRAM = 0x02000000
    }
}
