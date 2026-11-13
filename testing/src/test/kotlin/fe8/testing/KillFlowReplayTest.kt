package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Seth's kill through the real boot (MC-w2): the replay of [AttackFlowReplayTest] for
 * `ram_trace_prologue_kill`, aligned the same way (inputs relative to the first idle
 * `PlayerPhase` frame, end at the first end of `sProcScr_CombatAction`, not at a frame number).
 *
 * The capture has two attacks (Seth kills from f1713, Eirika attacks from f2293) and no idle
 * UI after the second. The replay stops after the first combat action, at the unit menu's
 * return to the idle phase, and is compared with the dump f2020: the combat has ended (write-back
 * f1999), the dead unit has been removed and the next input (the cursor move at f2029) has not
 * come. The boot's stand-in battle takes far fewer frames than f1713 to f2005, so the inputs
 * after the confirm (f1709) are all zero until the end point.
 *
 * Compared: every byte of every unit (Seth's HP, kill exp, weapon uses and state bits, the dead
 * enemy's struct with no character, all others) and the next RNG draws. `gBmMapUnit` is not
 * compared: the boot has no public access to its map.
 */
class KillFlowReplayTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    @Test
    fun `the kill flow through the boot ends in the captured state`() {
        assumeTrue(ref.available && File(assets, "baserom.gba").isFile, "reference data / ROM")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file")
        val replay = AttackReplay(ref, assets, "ram_trace_prologue_kill", END_FRAME)
        replay.runToCombatEnd()
        replay.assertUnitsEqual(END_FRAME)
        replay.assertRngEqual(END_FRAME)
    }

    private companion object {
        const val END_FRAME = 2020
    }
}
