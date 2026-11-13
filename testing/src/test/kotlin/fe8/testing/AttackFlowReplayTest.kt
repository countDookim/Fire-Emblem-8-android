package fe8.testing

import gba.testkit.ReferenceData
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

    @Test
    fun `the attack flow through the boot ends in the captured state`() {
        assumeTrue(ref.available && File(assets, "baserom.gba").isFile, "reference data / ROM")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file")
        val replay = AttackReplay(ref, assets, "ram_trace_prologue_attack_noanim", END_FRAME)
        replay.runToCombatEnd()
        replay.assertUnitsEqual(END_FRAME)
        replay.assertRngEqual(END_FRAME)
    }

    private companion object {
        const val END_FRAME = 2124
    }
}
