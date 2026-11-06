package fe8.game

import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
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
}
