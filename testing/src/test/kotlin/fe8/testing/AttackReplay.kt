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
import java.io.File

/**
 * Shared by [AttackFlowReplayTest] and [KillFlowReplayTest]: one boot resumed from the Prologue
 * save, fed the inputs of capture [runName] relative to the first idle `PlayerPhase` frame
 * (see [AttackFlowReplayTest] for the alignment).
 */
internal class AttackReplay(
    ref: ReferenceData,
    assets: File,
    runName: String,
    /** The capture frame the replay is compared with (also bounds the idle frame search). */
    val endFrame: Int,
) {
    val run = ref.run(runName)
    val rng = RngService()
    private var frame = 0
    private var idleAt = -1
    private val boot: Fe8Boot
    val game: Fe8Boot get() = boot

    private class Image(private val image: ByteArray) : SaveStorage {
        override fun load(dest: ByteArray): Boolean {
            image.copyInto(dest)
            return true
        }

        override fun store(src: ByteArray) {}
    }

    init {
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        // The capture's first idle frame (PlayerPhase command 16).
        var idleFrame = FIRST_FRAME
        while (idleFrame < endFrame &&
            ProcArrayDump.ofWatch(trace, idleFrame, names).find("gProcScr_PlayerPhase")
                ?.scrCur != IDLE_POSITION
        ) {
            idleFrame++
        }
        assertTrue(idleFrame < endFrame, "no idle player phase in the capture")
        val captured = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        boot = Fe8Boot(
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
    }

    private fun position() = boot.engine.find(boot.playerPhaseScript)?.scrCur ?: -1
    private fun hasCombat() = boot.engine.procs.any { it.script?.name == "sProcScr_CombatAction" }

    /**
     * Runs until `sProcScr_CombatAction` has appeared and ended with the phase back at the idle
     * command (the first combat action of the replay).
     */
    fun runToCombatEnd() {
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
    }

    /** Asserts every unit byte (but the sprite pointer +0x3C..0x3F) equals the dump of [at]. */
    fun assertUnitsEqual(at: Int) {
        val ewram = run.region("%s%05d".format(prefix(at), at), "ewram")
        val want = UnitTable(RomView.ofRom(ByteArray(0))).also { it.loadFrom(ewram) }.bytes
        val got = boot.units.bytes
        val bad = ArrayList<String>()
        for (i in want.indices) {
            val k = i % UnitTable.UNIT_SIZE
            if (k in 0x3C..0x3F || want[i] == got[i]) continue
            bad += "unit ${i / UnitTable.UNIT_SIZE} +0x${k.toString(16)}" +
                " want ${want[i]} got ${got[i]}"
        }
        assertEquals(emptyList<String>(), bad, "unit table differs from f$at")
    }

    /** Asserts the next 4 RNG draws equal those of the generator state in the dump of [at]. */
    fun assertRngEqual(at: Int) {
        val iw = run.region("%s%05d".format(prefix(at), at), "iwram")
        fun half(o: Int) = (iw[o].toInt() and 0xFF) or ((iw[o + 1].toInt() and 0xFF) shl 8)
        val reference = Fe8Rng().also { it.load(half(0), half(2), half(4)) }
        repeat(4) { assertEquals(reference.nextRaw(), rng.next(), "RNG draw $it") }
    }

    private fun prefix(at: Int) = if (run.dumps.containsKey("f%05d".format(at))) "f" else "r"

    companion object {
        const val IDLE_POSITION = 16
        const val FIRST_FRAME = 1100
        const val LIMIT = 6000
    }
}
