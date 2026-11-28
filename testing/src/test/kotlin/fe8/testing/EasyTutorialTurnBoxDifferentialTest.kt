package fe8.testing

import gba.core.VerifiesPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `PC-10n` against `ram_trace_newgame_easy`: the tutorial boxes that turn events open (durability
 * box `f19543..19888`, Turn1 box, objective box). The box procs are the ones of
 * [EasyTutorialBoxDialogueDifferentialTest]; its per-frame comparison is reused with the new
 * windows.
 */
@Timeout(300)
class EasyTutorialTurnBoxDifferentialTest {
    private val rig = EasyTutorialBoxDialogueDifferentialTest()

    /**
     * Capture evidence for the lag frames in all three windows: the proc tree did not run one
     * frame after an A press (the call drew past the vblank, as at the opening's f18569), so the
     * snapshots keep the script position of the press frame one frame longer (text script 7
     * where one run later gives 9, the page wait proc one frame longer in its sleep). Lags at
     * press + 1 for text, press + 2 for a page wait.
     */
    @Test
    @VerifiesPort(
        "gProcScr_BoxDialogue", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        "ProcScr_TalkBoxIdle", "BoxDialogueInterpreter_Main",
    )
    fun `durability box proc tree matches f19560 to f19888`() = rig.window(
        19560, 19888, 19550, lagFrames = setOf(19561, 19620, 19800),
        midCallFrames = emptySet(), scanBack = 40,
    )

    /** f20640 is the overrun snapshot (the A press of f20639 caught half done, 35 chars). */
    @Test
    @VerifiesPort(
        "gProcScr_BoxDialogue", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        "ProcScr_TalkBoxIdle", "BoxDialogueInterpreter_Main",
    )
    fun `turn 1 box proc tree matches f20481 to f20725`() = rig.window(
        20481, 20725, 20490, lagFrames = setOf(20550, 20671), midCallFrames = setOf(20640),
        scriptBehindFrames = setOf(20640), scanBack = 10,
    )

    /** f21750 is an overrun snapshot (the page is 38 chars in where the finished call has 66). */
    @Test
    @VerifiesPort(
        "gProcScr_BoxDialogue", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        "ProcScr_TalkBoxIdle", "BoxDialogueInterpreter_Main",
    )
    fun `objective box proc tree matches f21741 to f22075`() = rig.window(
        21741, 22075, 21750, lagFrames = setOf(21810, 21900, 21931, 21990, 22021),
        midCallFrames = setOf(21750), scanBack = 10,
    )
}
