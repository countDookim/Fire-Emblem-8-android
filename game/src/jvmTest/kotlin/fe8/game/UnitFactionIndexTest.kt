package fe8.game

import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The New Game path must run `InitUnits` (src/bmunit.c), which sets `Unit.index` to the
 * unit's slot id. `UNIT_FACTION` and so `GetUnitSpritePalette` (src/bmudisp.c) read that byte;
 * with index 0 every enemy was drawn in the ally palette. Ground truth: ram_trace_first_phase
 * f19430 (unit ids 0x81..0x83 are the red array).
 */
class UnitFactionIndexTest {
    @Test
    fun `new game units carry their slot id so enemies use the red sprite palette`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        var frame = 0
        val boot = Fe8Boot(
            RomView.ofRom(file.readBytes()),
            { if (frame % 20 in 5..8) 0x0001 else 0 },
            RngService(),
        )
        while (frame < 20000 && boot.engine.find(boot.playerPhaseScript) == null) {
            boot.frame()
            frame++
        }
        assertTrue(boot.engine.find(boot.playerPhaseScript) != null, "player phase not reached")
        frame = 1
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 6000) boot.frame()
        for (id in listOf(0x01, 0x02, 0x81, 0x82, 0x83)) {
            assertTrue(boot.units.isValid(id), "unit 0x${id.toString(16)}")
            assertEquals(id, boot.units.index(id) and 0xFF, "index of 0x${id.toString(16)}")
        }
        repeat(30) { boot.frame() }
        val oam = boot.loop.oam.oam
        // Drawn objects only: ClearOam parks unused ones at y = 160 (attr0 low byte) and leaves
        // their attr2 stale, so after the events' sprites they keep old palette banks.
        val banks = (0 until 128)
            .filter {
                val attr0 = oam[it * 4].toInt()
                (attr0 shr 8) and 3 != 2 && (attr0 and 0xFF) < 160
            }
            .map { (oam[it * 4 + 2].toInt() shr 12) and 0xF }
        assertEquals(2, banks.count { it == 0xC }, "ally sprites in bank 0xC: $banks")
        assertEquals(3, banks.count { it == 0xD }, "enemy sprites in bank 0xD: $banks")
    }
}
