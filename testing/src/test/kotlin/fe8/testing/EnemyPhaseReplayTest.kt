package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.UnitTable
import gba.core.sram.SaveStorage
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Resumes the Prologue save and replays the captured keys from the first idle player phase.
 * At turn 2 idle, compares against f2895: the complete unit arrays except sprite pointers and
 * the RNG state. Intermediate blue AI flags are compared after the berserk phase.
 * Timing between those boundaries may differ, including the original suspend-save writes.
 */
class EnemyPhaseReplayTest {
    @Test
    fun `enemy phase through the boot reaches the captured turn 2 state`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val rom = File(assets, "baserom.gba")
        assumeTrue(ref.available && rom.isFile, "reference data / ROM")
        val names = ProcArrayDump.romNames()
        assertTrue(names.isNotEmpty(), "decomp nm file is required")
        val run = ref.run("ram_trace_prologue_enemy_phase")
        val trace = run.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        // The original finishes the second battle and returns to the turn-2 idle command.
        assertEquals(16, ProcArrayDump.ofWatch(trace, 2895, names)
            .find("gProcScr_PlayerPhase")?.scrCur)
        val playSt = run.watch("gplayst")
        assertEquals(0, playSt.u8(2895, 0x0F), "captured player faction")
        assertEquals(2, playSt.u16(2895, 0x10), "captured turn")
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }
            override fun store(src: ByteArray) {}
        }
        var frame = 0
        var idleAt = -1
        // Box once, as AttackReplay does, so the input callback does not box each frame.
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        val rng = RngService()
        val boot = Fe8Boot(
            RomView.ofRom(rom.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frame - idleAt + idleFrame) { 0 }
            } as () -> Int),
            rng,
            saveStorage = storage,
        )
        var enemySeen = false
        var combats = 0
        var inCombat = false
        var cpPhaseSeen = false
        var berserkSeen = false
        var blueBerserkSeen = false
        var blueFlagsCompared = false
        val afterBlueBerserk = UnitTable(RomView.ofRom(ByteArray(0))).also {
            it.loadFrom(run.region("r01527", "ewram"))
        }
        while (frame < 6000) {
            boot.frame()
            frame++
            val idle = boot.engine.find(boot.playerPhaseScript)?.scrCur == 16
            if (idleAt < 0 && idle) idleAt = frame
            if (boot.control.playSt.faction == 0x80) enemySeen = true
            cpPhaseSeen = cpPhaseSeen || boot.engine.procs.any {
                it.script?.name == "gProcScr_CpPhase"
            }
            val berserk = boot.engine.procs.any { it.script?.name == "gProcScr_BerserkCpPhase" }
            berserkSeen = berserkSeen || berserk
            if (idleAt >= 0 && !enemySeen) {
                if (berserk) blueBerserkSeen = true
                if (blueBerserkSeen && !berserk && !blueFlagsCompared) {
                    for (id in 1..0x3E) {
                        assertEquals(afterBlueBerserk.aiFlags(id), boot.units.aiFlags(id),
                            "blue unit $id flags after berserk phase")
                    }
                    blueFlagsCompared = true
                }
            }
            val combat = boot.engine.procs.any { it.script?.name == "sProcScr_CombatAction" }
            if (combat && !inCombat) combats++
            inCombat = combat
            if (enemySeen && boot.control.playSt.chapterTurnNumber == 2 && idle) break
        }
        assertTrue(
            frame < 6000, "turn 2 not reached: frame $frame, idleAt $idleAt, combats $combats",
        )
        assertTrue(enemySeen && cpPhaseSeen && berserkSeen, "enemy and berserk phases ran")
        assertTrue(blueFlagsCompared, "blue berserk phase flags compared")
        assertEquals(2, combats)
        val ewram = run.region("r02895", "ewram")
        val capturedUnits = UnitTable(RomView.ofRom(ByteArray(0))).also { it.loadFrom(ewram) }
        val initialUnits = UnitTable(RomView.ofRom(ByteArray(0))).also {
            it.loadFrom(run.region("f01520", "ewram"))
        }
        assertTrue(capturedUnits.currentHp(2) < initialUnits.currentHp(2), "target took damage")
        assertTrue(!capturedUnits.isValid(0x82) && !capturedUnits.isValid(0x83),
            "both attackers were removed")
        val expected = capturedUnits.bytes
        val differences = ArrayList<String>()
        for (i in expected.indices) {
            val offset = i % UnitTable.UNIT_SIZE
            if (offset in 0x3C..0x3F || expected[i] == boot.units.bytes[i]) continue
            differences += "unit ${i / UnitTable.UNIT_SIZE} +0x${offset.toString(16)} " +
                "expected ${expected[i]} got ${boot.units.bytes[i]}"
        }
        assertEquals(emptyList<String>(), differences, "unit table at turn 2")
        val seeds = RamSymbols.fromEnvironment()["gRNSeeds"]
        assertEquals(6, seeds.size)
        val iwram = run.region("r02895", "iwram")
        repeat(3) {
            assertEquals(GbaMem.u16(iwram, GbaMem.IWRAM, seeds.address + 2 * it),
                rng.primary.word(it), "RNG state word $it")
        }
    }
}
