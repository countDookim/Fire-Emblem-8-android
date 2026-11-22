package fe8.game

import fe8.game.bm.UnitLoader
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class Fe8BootWiringTest {
    private fun boot(): Fe8Boot {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        return Fe8Boot(RomView.ofRom(file.readBytes()), { 0 }, RngService())
    }

    @Test
    fun `event engine runs on the game's proc engine and starts idle`() {
        val boot = boot()
        assertTrue(!boot.eventEngine.eventEngineExists())
        assertEquals(boot.engine, boot.eventEngine.procs)
    }

    @Test
    fun `battle map path builds and the wired call event reaches the engine`() {
        val boot = boot()
        val main = boot.bmapMain
        assertTrue(main.hooks.playerPhase.name == "gProcScr_PlayerPhase")
        assertTrue(main.hooks.playerPhase.cmds.size > 2)
        assertTrue(!boot.eventEngine.eventEngineExists())
        main.hooks.callEvent(0x08000000, 0, boot.engine.start(main.script, 0))
        assertTrue(boot.eventEngine.eventEngineExists())
    }

    @Test
    fun `game control chapter stats and unit cleanup hooks are wired to shared state`() {
        val boot = boot()
        assertSame(boot.saveLib.chapterStatsData, boot.chapterStats.data)

        boot.control.playSt.chapterIndex = 0
        boot.control.playSt.chapterTurnNumber = 3
        boot.control.playSt.timeChapterStarted = 100
        boot.clock.set(640L)

        val proc = boot.engine.start(boot.control.script, 3)
        boot.control.hooks.registerChapterTimeAndTurnCount.invoke(proc)
        assertEquals(3, boot.chapterStats.chapterTurn(0))
        assertEquals(3, boot.chapterStats.chapterTime(0))

        // Load Eirika in blue slot 0 (id = 1) with damage, then invoke wired cleanup hook.
        val rom = RomView.ofRom(
            File(
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
                "baserom.gba",
            ).readBytes(),
        )
        val loader = UnitLoader(boot.units, rom, { 0 }, RngService(), { false })
        boot.units.initUnits()
        loader.loadUnit(0x088B3D18) { _, _ -> 0 } // UnitDef_Event_PrologueThroneRoomUnits[0]
        val off = boot.units.unitOffset(1)
        boot.units.bytes[off + 0x13] = 1
        boot.control.hooks.chapterChangeUnitCleanup.invoke(proc)
        assertEquals(0x09, boot.units.state(1) and 0x09)
        assertTrue(boot.units.currentHp(1) > 1)
    }
}
