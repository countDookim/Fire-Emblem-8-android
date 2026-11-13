package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.UnitTable
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.Fe8Rng
import gbafe.core.rng.RngService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The whole attack flow through the real boot (MC-w1): resume the Prologue turn 1, replay the
 * inputs of `ram_trace_prologue_attack_noanim` (the Options prefix that turns animations off,
 * the unit menu, Attack, the weapon list, a B out of the target select and out of the weapon
 * menu, then the confirm) and compare the end state with the capture's.
 *
 * Alignment: the boot reaches the idle `PlayerPhase` command 16 long before the capture does,
 * so the captured keys are replayed relative to the first idle frame (the capture's first
 * command 16 frame is the frame the boot reaches it), as in [PlayerPhaseBootTest]. The battle
 * animation is a stand-in, so frame numbers differ after the confirm (the capture battles from
 * f1823 to f2076). The end point is not a frame number: it is the first frame after the combat
 * proc `sProcScr_CombatAction` has appeared and ended with the phase back at command 16. The
 * boot is then compared with the capture's last dump (f2124, the idle phase after the battle).
 * The inputs end at f1820, so the later frames cannot press anything.
 *
 * Compared: every byte of every unit (HP, exp, level, items and uses, state bits, position;
 * only the runtime sprite pointer +0x3C..0x3F is skipped), which includes both fighters, and
 * the next draws of the RNG.
 */
class AttackFlowReplayTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private class Image(private val image: ByteArray) : SaveStorage {
        override fun load(dest: ByteArray): Boolean {
            image.copyInto(dest)
            return true
        }

        override fun store(src: ByteArray) {}
    }

    @Test
    fun `the attack flow through the boot ends in the captured state`() {
        assumeTrue(ref.available && File(assets, "baserom.gba").isFile, "reference data / ROM")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file")
        val run = ref.run(RUN)
        val trace = run.watch("procs")
        // The capture's first idle frame (PlayerPhase command 16).
        var idleFrame = FIRST_FRAME
        while (idleFrame < END_FRAME &&
            ProcArrayDump.ofWatch(trace, idleFrame, names).find("gProcScr_PlayerPhase")
                ?.scrCur != IDLE_POSITION
        ) {
            idleFrame++
        }
        assertTrue(idleFrame < END_FRAME, "no idle player phase in the capture")
        val captured = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        var frame = 0
        var idleAt = -1
        val rng = RngService()
        val boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) {
                    pulse[if (frame % 20 in 5..8) 1 else 0]
                } else {
                    captured.getOrElse(frame - idleAt + idleFrame) { 0 }
                }
            } as () -> Int),
            rng,
            saveStorage = Image(ref.run("ram_trace_prologue_resume").sram("s01001")),
        )
        fun position() = boot.engine.find(boot.playerPhaseScript)?.scrCur ?: -1
        fun hasCombat() = boot.engine.procs.any { it.script?.name == "sProcScr_CombatAction" }

        var combatSeen = false
        var done = false
        while (!done && frame < LIMIT) {
            boot.frame()
            frame++
            if (idleAt < 0 && position() == IDLE_POSITION) idleAt = frame
            if (idleAt < 0) continue
            if (hasCombat()) combatSeen = true
            done = combatSeen && !hasCombat() && position() == IDLE_POSITION
        }
        assertTrue(done, "the combat action did not end (frame $frame, combat $combatSeen)")

        val ewram = run.region("r%05d".format(END_FRAME), "ewram")
        val want = UnitTable(RomView.ofRom(ByteArray(0))).also { it.loadFrom(ewram) }.bytes
        val got = boot.units.bytes
        val bad = ArrayList<String>()
        for (i in want.indices) {
            val k = i % UnitTable.UNIT_SIZE
            if (k in 0x3C..0x3F || want[i] == got[i]) continue
            bad += "unit ${i / UnitTable.UNIT_SIZE} +0x${k.toString(16)}" +
                " want ${want[i]} got ${got[i]}"
        }
        assertEquals(emptyList<String>(), bad, "unit table differs from f$END_FRAME")

        val iw = run.region("r%05d".format(END_FRAME), "iwram")
        val reference = Fe8Rng().also { it.load(iw.half(0), iw.half(2), iw.half(4)) }
        repeat(4) { assertEquals(reference.nextRaw(), rng.next(), "RNG draw $it") }
    }

    private fun ByteArray.half(o: Int) =
        (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val RUN = "ram_trace_prologue_attack_noanim"
        const val IDLE_POSITION = 16
        const val FIRST_FRAME = 1100
        const val END_FRAME = 2124
        const val LIMIT = 6000
    }
}
