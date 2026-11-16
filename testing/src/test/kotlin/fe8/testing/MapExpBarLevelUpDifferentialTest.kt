package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmSt
import fe8.game.bm.MAExpBarProc
import fe8.game.bm.ManimSt
import fe8.game.bm.MapAnimExpBar
import fe8.game.bm.MapAnimInfoBox
import fe8.game.bm.UnitTable
import fe8.game.mapanim.MapLevelUpAnnouncement
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The map battle exp bar of Eirika's level-up in `ram_trace_prologue_levelup` (exp 87 to 167,
 * f6276..6747) with the real `ProcScr_ManimLevelUp`: `ProcMAExpBar_LevelUpIfPossible` starts it
 * as a blocking child at f6413, and the bar resumes and ends at f6747. Each step compares the
 * bar's cursor, sleep timer, shown exp (which wraps 99 to 0 at f6317) and lock count, and the
 * level-up's presence, cursor and lock count; the bar's BG0 until the display starts. The
 * display itself is [MapLevelUpAnnouncementDifferentialTest]'s.
 */
class MapExpBarLevelUpDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("ProcMAExpBar_LevelUpIfPossible")
    fun `exp bar starts the level-up at f6413 and resumes at f6747`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available && romFile.isFile && names.isNotEmpty(), "data not present")
        val run = ref.run(RUN)
        val trace = run.watch("procs")
        val symbols = RamSymbols.fromEnvironment()
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)

        val seed = run.region("r06412", "ewram")
        val seedIwram = run.region("r06412", "iwram")
        val bm = BmSt().also { it.decode(seed, symbols["gBmSt"].address - EWRAM) }
        val manim = ManimSt()
        val unitPointer = GbaMem.u32(seed, GbaMem.EWRAM, symbols["gManimSt"].address)
        manim.unitBytes[0] = seed
        manim.unitOffset[0] = unitPointer - EWRAM

        val engine = ProcEngine().apply { init() }
        val hw = BgHardware()
        hw.lcd.readFrom(seedIwram, symbols["gLCDControlBuffer"].address - GbaMem.IWRAM)
        val paletteAddress = symbols["gPaletteBuffer"].address - EWRAM
        for (i in 0 until 512) {
            hw.palette[i] = GbaMem.u16(seed, 0, paletteAddress + 2 * i).toShort()
        }
        val sound = SoundWrapper(engine, null) { 0 }
        val root = GbaMem.u32(romBytes, 0, 0x0815D488 - 0x08000000) - 0x08000000
        val messages = MsgStrings(
            romBytes, MsgHuffman(romBytes, 0x0815A72C - 0x08000000, root),
            0x0815D48C - 0x08000000,
        )
        val text = TextSystem(romBytes, TextVram(), Fe8Text.CONFIG)
        text.initTextFont(null, 0x06001000, 0x80, 0)
        text.resetTextFont()
        val units = UnitTable(rom).also { it.loadFrom(seed) }
        val battle = BattleUnit()
        val battleActor = symbols["gBattleActor"].address - EWRAM
        seed.copyInto(battle.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
        val hooks = MapLevelUpAnnouncement.Hooks()
        // The stand-in for `ResetDialogueScreen`, as in the display's own test.
        hooks.resetDialogueScreen = {
            hw.fill(hw.tilemaps[0], 0)
            hw.fill(hw.tilemaps[1], 0)
            hw.lcd.setWinEnable(0, 0, 0)
        }
        val rig = MapLevelUpRig(
            engine, rom, hw, sound, bm, manim, units, battle, text, messages, hooks,
        )
        val infoBox = MapAnimInfoBox(engine, ManimSt(), rom, text, messages, hw)
        val expBar = MapAnimExpBar(engine, rom, infoBox, sound, hw, rig.announcement)

        fun at(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        fun s16(v: Int) = v.toShort().toInt()
        val first = at(FIRST).find("ProcScr_MapAnimExpBar")
        val captured = checkNotNull(first) { "captured exp bar at $FIRST" }
        assertEquals(87, s16(captured.u16(0x64)))
        assertEquals(167, s16(captured.u16(0x66)))
        var blocked = false
        engine.start(
            ProcScript(
                "parent (test)",
                listOf(
                    ProcScripts.call(
                        ProcFunc {
                            expBar.start(
                                it, s16(captured.u16(0x64)), s16(captured.u16(0x66)),
                                s16(captured.u16(0x68)),
                            )
                        },
                    ),
                    ProcScripts.sleep(1),
                    ProcScripts.call(ProcFunc { blocked = true }),
                    ProcScripts.end(),
                ),
            ),
            2,
        )

        val bg0Address = symbols["gBG0TilemapBuffer"].address - EWRAM
        var levelUpSteps = 0
        var bg0Compared = 0
        var wrapped = false
        for (step in FIRST..LAST) {
            rig.layers.clear()
            for (tree in 0 until 8) engine.run(engine.root(tree))
            val original = at(step)
            val bar = original.find("ProcScr_MapAnimExpBar")
            val mine = engine.find(expBar.script)
            val at = "f$step"
            if (bar == null) {
                assertNull(mine, "$at bar ended")
                assertNull(engine.find(rig.announcement.script), "$at level-up ended")
                assertEquals(LAST, step, "the capture's bar ends at f$LAST")
                break
            }
            val proc = checkNotNull(mine) { "$at my bar" }
            val state = proc.state as MAExpBarProc
            assertEquals(bar.scrCur, proc.scrCur, "$at bar cursor")
            assertEquals(bar.u8(0x24), proc.sleepTime, "$at bar sleep timer")
            assertEquals(bar.u8(0x28), proc.lockCnt, "$at bar lock count")
            assertEquals(s16(bar.u16(0x64)), state.expFrom, "$at expFrom")
            if (state.expFrom == 0) wrapped = true
            assertTrue(!blocked, "$at the parent waits")

            val level = original.find("ProcScr_ManimLevelUp")
            val own = engine.find(rig.announcement.script)
            assertEquals(level != null, own != null, "$at level-up present")
            if (level != null && own != null) {
                levelUpSteps++
                assertEquals(level.scrCur, own.scrCur, "$at level-up cursor")
                assertEquals(level.u8(0x28), own.lockCnt, "$at level-up lock count")
            }
            if (step < 6413) {
                val dump = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
                val index = run.dumps[dump] ?: continue
                if (index != step) continue
                val ewram = run.region(dump, "ewram")
                for (i in 0 until 1024) {
                    assertEquals(
                        GbaMem.u16(ewram, 0, bg0Address + 2 * i),
                        hw.tilemaps[0][i].toInt() and 0xFFFF, "$at BG0[$i]",
                    )
                }
                bg0Compared++
            }
        }
        assertEquals(6745 - 6413 + 1, levelUpSteps, "level-up frames f6413..6745")
        assertTrue(wrapped, "the shown exp wrapped past 100")
        assertTrue(bg0Compared >= 10, "BG0 dumps compared: $bg0Compared")
        for (tree in 0 until 8) engine.run(engine.root(tree))
        for (tree in 0 until 8) engine.run(engine.root(tree))
        assertTrue(blocked, "the parent resumes once the bar has ended")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_levelup"
        const val EWRAM = 0x02000000
        const val FIRST = 6276
        const val LAST = 6747
    }
}
