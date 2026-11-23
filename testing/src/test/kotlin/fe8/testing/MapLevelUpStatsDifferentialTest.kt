package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleUnit
import fe8.game.bm.MapLevelUpFrame
import fe8.game.bm.MapLevelUpStats
import fe8.game.bm.UnitTable
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.extract.Symbols
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    @VerifiesPort("PutManimLevelUpFrame")
    fun `frame and initial panel match captured buffers`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile)
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val run = ref.run("ram_trace_prologue_levelup")
        val symbols = RamSymbols.fromEnvironment()
        val bg0Address = symbols["gBG0TilemapBuffer"].address - EWRAM
        val bg1Address = symbols["gBG1TilemapBuffer"].address - EWRAM
        val paletteAddress = symbols["gPaletteBuffer"].address - EWRAM
        val battleActor = symbols["gBattleActor"].address - EWRAM
        val before = run.region("r06518", "ewram")
        val after = run.region("r06519", "ewram")
        val root = u32(romBytes, 0x0815D488 - 0x08000000) - 0x08000000
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - 0x08000000, root),
            0x0815D48C - 0x08000000,
        )
        val textVram = TextVram()
        val text = TextSystem(romBytes, textVram, Fe8Text.CONFIG)
        text.initTextFont(null, 0x06001000, 0x80, 0)
        text.resetTextFont()
        val units = UnitTable(rom).also { it.loadFrom(before) }
        val battle = BattleUnit()
        before.copyInto(battle.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
        val bg0 = ShortArray(1024)
        val bg1 = ShortArray(1024) { u16(before, bg1Address + 2 * it).toShort() }
        val palette = ShortArray(512) { u16(before, paletteAddress + 2 * it).toShort() }
        val vram = ByteArray(0x18000)
        val io = run.region("f06520", "io")
        val bg1Control = u16(io, 0x0A)
        val tileBase = ((bg1Control shr 2) and 3) * 0x4000
        val hardware = BgHardware()
        val frame = MapLevelUpFrame(
            rom, units, arrayOf(battle), text, messages, bg0, bg1, palette, vram, tileBase,
            hardware,
        )
        val stats = MapLevelUpStats(rom, units, arrayOf(battle), text, bg0)
        frame.putManimLevelUpFrame(0, 1, 1)
        // BG_EnableSyncByMask(BG0 + BG1) and ApplyPalette's EnablePaletteSync: the original's
        // BG1 map and palette reach VRAM at the next VBlank (captured below at f6520).
        assertSyncRequested(hardware, bgMask = 3, palette = true, message = "level-up frame")
        for (stat in 0..8) stats.putManimLevelUpStat(0, 1, 1, stat, false)
        assertArrayEquals(
            ShortArray(1024) { u16(after, bg0Address + 2 * it).toShort() }, bg0, "BG0",
        )
        assertArrayEquals(
            ShortArray(1024) { u16(after, bg1Address + 2 * it).toShort() }, bg1, "BG1",
        )
        assertArrayEquals(
            ShortArray(16) { u16(after, paletteAddress + 5 * 32 + 2 * it).toShort() },
            palette.copyOfRange(5 * 16, 6 * 16), "palette bank 5",
        )
        val captured = run.region("f06520", "vram")
        // The original's maps are still empty in VRAM at f6520 and flushed from its buffers by
        // f6530 (the sync request above is served at a later VBlank).
        val mapAt = ((bg1Control shr 8) and 0x1F) * 0x800
        assertTrue(captured.copyOfRange(mapAt, mapAt + 0x800).all { it.toInt() == 0 })
        val later = run.region("f06530", "vram")
        val laterBuffer = run.region("f06530", "ewram")
        assertArrayEquals(
            laterBuffer.copyOfRange(bg1Address, bg1Address + 0x800),
            later.copyOfRange(mapAt, mapAt + 0x800), "BG1 map in VRAM at f6530",
        )
        // Glyph pixels carry the text colors, which the tilemaps do not.
        val glyphBytes = text.activeFont.chrCounter * 64
        assertArrayEquals(
            captured.copyOfRange(0x1000, 0x1000 + glyphBytes),
            textVram.vram.copyOfRange(0x1000, 0x1000 + glyphBytes), "text tiles",
        )
        val tiles = tileBase + 0x200 * 32
        val length = (bg1.maxOf { (it.toInt() and 0x3FF) } - 0x200 + 1) * 32
        assertArrayEquals(
            captured.copyOfRange(tiles, tiles + length), vram.copyOfRange(tiles, tiles + length),
            "BG1 frame tiles",
        )
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int = u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    private companion object {
        const val EWRAM = 0x02000000
    }
}
