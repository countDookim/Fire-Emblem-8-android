package fe8.testing

import fe8.game.bm.MapTraps
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The trap defeat wiring of the real boot (EP1b-trapwire): `sub_802EA1C` (bmtrick.c) calls
 * `sub_8026414(3)` (bmtarget.c), and both work on the one `gSelectTargets`. A synthetic fire tile
 * under Eirika at the idle Prologue player phase makes her a trap damage target; whether her HP
 * is at most the damage decides if the defeat info (cause 3) is recorded, as in the C. Synthetic
 * only: no capture has a trap (the real list is empty in every window), so nothing here is a
 * verification claim.
 */
class TrapDefeatWiringTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `trap damage targets reach sub8026414 and record the defeat info`() {
        assumeTrue(ReferenceData.fromEnvironment().available && romFile.isFile, "inputs missing")
        val pb = PrologueBoot(romFile)
        assertTrue(pb.reachIdle(), "idle player phase")
        val boot = pb.boot
        val units = boot.units
        val traps = checkNotNull(boot.mapTraps)
        val eirika = 1
        val pid = units.charId(eirika)
        val hp = units.currentHp(eirika)
        val x = units.xPos(eirika)
        val y = units.yPos(eirika)
        assertTrue(hp > 1, "Eirika has HP")
        assertEquals(0, boot.pidStats.deathCause(pid), "no defeat recorded yet")

        // Damage below her HP: she is a target, but not defeated.
        traps.clearTraps()
        traps.addDamagingTrap(x, y, MapTraps.TRAP_FIRETILE, 0, 0, 1, hp - 1)
        traps.generateTrapDamageTargets()
        traps.sub802EA1C()
        assertEquals(0, boot.pidStats.deathCause(pid), "damage below HP")

        // Damage equal to her HP: the defeat is recorded with cause 3.
        traps.clearTraps()
        traps.addDamagingTrap(x, y, MapTraps.TRAP_FIRETILE, 0, 0, 1, hp)
        traps.generateTrapDamageTargets()
        traps.sub802EA1C()
        assertEquals(3, boot.pidStats.deathCause(pid), "damage equal to HP")
        assertEquals(boot.control.playSt.chapterTurnNumber, boot.pidStats.deathTurn(pid), "turn")
    }
}
