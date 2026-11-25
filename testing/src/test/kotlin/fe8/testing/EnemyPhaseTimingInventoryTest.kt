package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.event.EventEngineProc
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Inventory (EP-drift, no port claim): where the port's Prologue replay of
 * `ram_trace_prologue_gameover` (PC-cap2; same inputs as `ram_trace_prologue_enemy_phase`) leaves
 * the capture's timing. The boot resumes the Prologue save and replays the captured keys from the
 * first idle frame, as [GameOverWireTest] does. The test asserts the CURRENT state so regressions
 * fail on purpose:
 *
 * 1. `f1340`: the capture opens the Options screen (`ProcScr_Config_Field`, `f1340..1449`) from
 *    the map menu; the port's Options item does nothing (MANUAL_TEST known gap), so the menu
 *    stays open (`sProc_Menu`) and the idle edge comes earlier.
 * 2. `f2386` (capture `f2403`): with `EventScrHooks` wired (`EP-11-hooks`), the first kill event
 *    (`f2005`, capture `f2014`) clears its trigger flag (`7`, bit `262` / `363`) and skips
 *    dialogue (`countRedUnits == 2`), and after the second Soldier's death the port now runs the
 *    one-enemy-left event at `0x089EF0D8` (`f2386..2720`, capture `f2403..2721`), setting flag `7`
 *    (`262` / `363`). `gProcScr_CpPhase` is present `1137` frames in both capture and port.
 */
@Timeout(180)
class EnemyPhaseTimingInventoryTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    @Test
    fun `replay leaves the capture at the Options screen and runs the second kill event`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "nm file")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasWatch("flags"), "watches")
        val trace = run.watch("procs")
        val flagTrace = run.watch("flags")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        var frame = 0
        var idleAt = -1
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frame - idleAt + idleFrame) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = object : SaveStorage {
                override fun load(dest: ByteArray): Boolean {
                    save.copyInto(dest)
                    return true
                }

                override fun store(src: ByteArray) {}
            },
        )
        var firstDivergence = -1
        var divergence = ""
        var capCp = 0
        var ourCp = 0
        val capEvents = ArrayList<String>()
        val ourEvents = ArrayList<String>()
        var capEv = false
        var ourEv = false
        var capFlag262 = -1
        var ourFlag363 = -1
        while (frame < 4000) {
            boot.frame()
            frame++
            if (idleAt < 0) {
                if (boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
                continue
            }
            val step = frame - idleAt + idleFrame
            if (step > 2800) break
            val cap = ProcArrayDump.ofWatch(trace, step, names)
            val capNames = cap.live.mapNotNull { it.name }.toSortedSet()
            val ourNames = boot.engine.procs.mapNotNull { it.script?.name }.toSortedSet()
            if (firstDivergence < 0 && capNames != ourNames) {
                firstDivergence = step
                divergence = "capOnly=${capNames - ourNames} ourOnly=${ourNames - capNames}"
            }
            if (capNames.contains("gProcScr_CpPhase")) capCp++
            if (ourNames.contains("gProcScr_CpPhase")) ourCp++
            val capProc = cap.find("ProcScr_StdEventEngine")
            if ((capProc != null) != capEv) {
                capEv = capProc != null
                if (capProc != null) capEvents += "$step:0x${capProc.u32(0x30).toString(16)}"
            }
            val ourProc = boot.engine.procs.firstOrNull { it.script?.name == "ProcScr_StdEventEngine" }
            if ((ourProc != null) != ourEv) {
                ourEv = ourProc != null
                val st = ourProc?.state as? EventEngineProc
                if (st != null) ourEvents += "$step:0x${st.pEventStart.toString(16)}"
            }
            if (capFlag262 < 0 && (flagTrace.u8(step, 262 / 8) shr (262 % 8)) and 1 != 0) {
                capFlag262 = step
            }
            if (ourFlag363 < 0 && boot.flags.checkFlag(363)) ourFlag363 = step
        }
        println("EP-drift first divergence f$firstDivergence $divergence")
        println("EP-drift CpPhase frames cap $capCp our $ourCp")
        println("EP-drift events cap $capEvents our $ourEvents flag262 cap f$capFlag262 " +
            "flag363 our f$ourFlag363")
        assertEquals(1340, firstDivergence, divergence)
        assertTrue(divergence.contains("ProcScr_Config_Field"), divergence)
        // Both capture and port run the second-kill event (`0x089EF0D8`) and set chapter flag 7
        // (bit 262 in gPermanentFlagBits + gChapterFlagBits, flag 363 in EventFlags); the 17-frame
        // lead (f2386 vs f2403) comes from the earlier Options-screen divergence and minor drift.
        assertTrue(capEvents.any { it.startsWith("2403:0x89ef0d8") }, "$capEvents")
        assertTrue(ourEvents.any { it.startsWith("2386:0x89ef0d8") }, "$ourEvents")
        assertEquals(2403, capFlag262)
        assertEquals(2386, ourFlag363)
        assertEquals(1137, capCp, "capture CpPhase frames")
        assertEquals(capCp, ourCp, "CpPhase frames cap $capCp our $ourCp")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_gameover"
    }
}
