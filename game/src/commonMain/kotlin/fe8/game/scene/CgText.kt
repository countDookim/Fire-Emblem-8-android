package fe8.game.scene

import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end

/**
 * Fullscreen CG text scene (`src/cgtext.c`).
 */
public class CgText(
    private val procs: ProcEngine,
) {
    /**
     * `gProcScr_CgTextMain`: the CG text controller proc.
     */
    public val script: ProcScript = ProcScript("gProcScr_CgTextMain", listOf(end()))

    /**
     * Ends the active CG text proc (`Proc_End(Proc_Find(gProcScr_CgTextMain))`).
     *
     * Transcribed from src/cgtext.c: EndCgText (not verified: in the captured text ends no CG text
     * proc is active, so the find returns null)
     */
    public fun endCgText() {
        procs.find(script)?.let { procs.end(it) }
    }

    /**
     * Whether a CG text proc is active (`Proc_Find(gProcScr_CgTextMain) != NULL`).
     *
     * Transcribed from src/cgtext.c: CgTextExists (not verified: only the event wait calls it
     * while no CG text is running)
     */
    public fun cgTextExists(): Boolean = procs.find(script) != null
}
