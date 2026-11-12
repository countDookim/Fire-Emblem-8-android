package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.UnitTable
import gba.core.sram.SaveStorage
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The player phase wired into the real boot (PP-17): resume the Prologue turn 1 from
 * `prologue.sav`, press the keys of `ram_trace_prologue_select_move` and
 * `ram_trace_prologue_cancel_end` and compare the game with those captures.
 *
 * The boot reaches the idle player phase long before the capture (the capture's boot is slower),
 * so the keys are replayed relative to the first idle frame: capture frame 1172 (the first frame
 * of the idle `PlayerPhase` command 16) is the frame the boot reaches it, every later capture
 * frame is the same number of boot frames after it. The resumed idle phase is entered the same
 * way in both, so the frames line up exactly up to the Wait (f1356); the differences after it
 * are in the tests below.
 */
class PlayerPhaseBootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val names = ProcArrayDump.romNames()

    private class Image(private val image: ByteArray) : SaveStorage {
        override fun load(dest: ByteArray): Boolean {
            image.copyInto(dest)
            return true
        }

        override fun store(src: ByteArray) {}
    }

    /** One boot driven to the idle phase, then by the captured keys. */
    private inner class Rig(runName: String) {
        val run = ref.run(runName)
        val trace = run.watch("procs")
        private val captured = Array<Any>(2400) { if (it < run.inputs.size) run.inputs[it] else 0 }
        private val pulse = arrayOf<Any>(0, 1)
        var frame = 0
        private var idleAt = -1
        val boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else captured[(frame - idleAt + IDLE_FRAME).coerceIn(0, 2399)]
            } as () -> Int),
            RngService(),
            saveStorage = Image(ref.run("ram_trace_prologue_resume").sram("s01001")),
        )

        /** The capture frame the boot is at, or -1 before the idle phase. */
        val captureFrame: Int get() = if (idleAt < 0) -1 else frame - idleAt + IDLE_FRAME

        /** Runs one boot frame; true once the replay has started. */
        fun step() {
            boot.frame()
            frame++
            if (idleAt < 0 && playerPhasePosition() == IDLE_POSITION) idleAt = frame
        }

        fun playerPhasePosition(): Int =
            boot.engine.find(boot.playerPhaseScript)?.scrCur ?: -1

        fun runToCapture(frame: Int) {
            while (captureFrame < frame) step()
        }

        fun has(name: String): Boolean = boot.engine.procs.any { it.script?.name == name }

        fun capture(f: Int) = ProcArrayDump.ofWatch(trace, f, names)

        fun ewram(f: Int): ByteArray = run.region("f%05d".format(f), "ewram")
    }

    private fun assumeInputs() {
        assumeTrue(ref.available && File(assets, "baserom.gba").isFile, "reference data / ROM")
        assumeTrue(names.isNotEmpty(), "decomp nm file")
    }

    @Test
    fun `select move and Wait in the boot follow select_move`() {
        assumeInputs()
        val rig = Rig("ram_trace_prologue_select_move")
        while (rig.captureFrame < 0) rig.step()
        val eirika = rig.boot.units.getUnitFromCharId(1)
        var cursorSeen = false
        var rangeSeen = false
        var menuSeen = false
        while (rig.captureFrame < 1420) {
            rig.step()
            val f = rig.captureFrame
            val want = rig.capture(f).find("gProcScr_PlayerPhase")?.scrCur ?: -1
            // The Wait leaves the action at f1356; the original then writes the suspend save
            // (frames of SRAM writes), the boot does it within the frame: 5 frames earlier.
            if (f <= 1356 || f >= 1364) {
                assertEquals(want, rig.playerPhasePosition(), "f$f PlayerPhase position")
            }
            if (f < 1356) {
                assertEquals(
                    rig.capture(f).find("sProcScr_MoveLimitView") != null,
                    rig.has("sProcScr_MoveLimitView"), "f$f MoveLimitView",
                )
            }
            cursorSeen = cursorSeen || rig.boot.loop.bmSt.playerCursorX == 6
            rangeSeen = rangeSeen || rig.has("sProcScr_MoveLimitView")
            menuSeen = menuSeen || rig.has("sProc_Menu")
            if (f >= 1364) {
                for (name in listOf("sProcScr_MoveLimitView", "sProc_Menu")) {
                    assertEquals(rig.capture(f).find(name) != null, rig.has(name), "f$f $name")
                }
            }
        }
        assertTrue(cursorSeen && rangeSeen && menuSeen, "$cursorSeen $rangeSeen $menuSeen")
        assertEquals(6, rig.boot.units.xPos(eirika), "Eirika x")
        assertEquals(5, rig.boot.units.yPos(eirika), "Eirika y")
        assertEquals(0x42, rig.boot.units.state(eirika), "Eirika state (moved, unselectable)")
        val dump = rig.ewram(1420)
        val base = UnitTable.BLUE_ADDRESS - GbaMem.EWRAM
        val o = base + rig.boot.units.unitOffset(eirika)
        for (k in 0 until UnitTable.UNIT_SIZE) {
            if (k in 0x3C..0x3F) continue // pMapSpriteHandle, a runtime pointer
            assertEquals(
                dump[o + k], rig.boot.units.bytes[rig.boot.units.unitOffset(eirika) + k],
                "Eirika +0x${k.toString(16)}",
            )
        }
    }

    @Test
    fun `cancels and End turn in the boot follow cancel_end then the turn 2 player phase`() {
        assumeInputs()
        val rig = Rig("ram_trace_prologue_cancel_end")
        while (rig.captureFrame < 0) rig.step()
        val eirika = rig.boot.units.getUnitFromCharId(1)
        val x0 = rig.boot.units.xPos(eirika)
        val y0 = rig.boot.units.yPos(eirika)
        while (rig.captureFrame < 1510) {
            rig.step()
            val f = rig.captureFrame
            val want = rig.capture(f).find("gProcScr_PlayerPhase")?.scrCur ?: -1
            // f1401..f1404: the menu cancel's fade (BMXFADE, a stand-in that never exists)
            // takes one frame in the original.
            if (f !in 1401..1404) {
                assertEquals(want, rig.playerPhasePosition(), "f$f PlayerPhase position")
            }
        }
        // B in the range display and B in the unit menu put Eirika back where she stood.
        assertEquals(x0, rig.boot.units.xPos(eirika))
        assertEquals(y0, rig.boot.units.yPos(eirika))
        assertEquals(0, rig.boot.units.state(eirika))
        // End turn: the phase proc is gone; the enemy phase banner shows up as in the capture.
        var enemyBanner = 0
        var playerBanner = 0
        var maxFaction = 0
        var turnEventSeen = false
        val mySeq = ArrayList<Int>()
        val capSeq = ArrayList<Int>()
        val unitsBefore = rig.boot.units.bytes.copyOf()
        while (rig.captureFrame < 4000 && !(rig.has("gProcScr_PlayerPhase") && rig.captureFrame > 1520)) {
            rig.step()
            val f = rig.captureFrame
            val faction = rig.boot.control.playSt.faction
            if (rig.boot.eventEngine.eventEngineExists()) turnEventSeen = true
            maxFaction = maxOf(maxFaction, faction)
            if (f <= 1650) {
                val mine = rig.boot.engine.procs.firstOrNull { it.script?.name == "gProc_BMapMain" }
                mySeq.addIfChanged(mine?.scrCur ?: -1)
                capSeq.addIfChanged(rig.capture(f).find("gProc_BMapMain")?.scrCur ?: -1)
            }
            if (rig.has("ProcScr_PhaseIntro")) {
                if (faction == 0x80) enemyBanner++ else if (faction == 0) playerBanner++
            }
        }
        // The original takes extra frames in the suspend save and the turn event of the enemy
        // phase (positions 18 and 20), the boot does those calls within one frame: every
        // position of ours up to the enemy banner's end is one of the capture's, in order. The
        // capture ends inside the enemy phase, ours goes on to turn 2 (position 32 again).
        val prefix = mySeq.take(9)
        assertTrue(isSubsequence(prefix, capSeq), "BMapMain positions $prefix vs capture $capSeq")
        assertEquals(listOf(32, 17, 21, 24, 25, 26, 27, 28, 31), prefix)
        assertTrue(turnEventSeen, "the enemy phase turn event of the Prologue ran")
        assertTrue(enemyBanner > 60, "enemy phase banner frames $enemyBanner")
        assertEquals(0x80, maxFaction, "the enemy phase ran")
        assertTrue(playerBanner > 60, "turn 2 player phase banner frames $playerBanner")
        assertEquals(2, rig.boot.control.playSt.chapterTurnNumber, "turn 2")
        assertEquals(0, rig.boot.control.playSt.faction, "player phase")
        assertEquals(0, rig.boot.units.state(eirika) and 0x42, "units are not greyed again")
        // The enemies did not act.
        for (id in 0x81..0xB2) {
            if (!rig.boot.units.isValid(id)) continue
            assertEquals(
                unitsBefore[rig.boot.units.unitOffset(id) + 0x10],
                rig.boot.units.bytes[rig.boot.units.unitOffset(id) + 0x10], "enemy $id x",
            )
        }
    }

    private fun isSubsequence(part: List<Int>, whole: List<Int>): Boolean {
        var at = 0
        for (v in whole) if (at < part.size && part[at] == v) at++
        return at == part.size
    }

    private fun MutableList<Int>.addIfChanged(v: Int) {
        if (isEmpty() || last() != v) add(v)
    }

    private companion object {
        /** The first frame of the idle `PlayerPhase_MainIdle` (command 16) in the captures. */
        const val IDLE_FRAME = 1172
        const val IDLE_POSITION = 16
    }
}
