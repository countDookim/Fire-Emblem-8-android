package fe8.game.bm

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.yield

/**
 * The phase-start cursor reset of src/bmcamadjust.c: `gProcScr_ResetCursorPosition` and the
 * functions it calls. It puts the cursor on the active faction's start position and starts a
 * [CamMove] (blocking the reset proc) if the camera has to follow.
 *
 * Unit access goes through the raw-unit stand-in [units]. Nothing allocates per frame.
 */
public class ResetCursorPosition(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val units: UnitTable,
    private val camMove: CamMove,
    private val nodes: BattleMapNodes,
) {
    /** Output x of the last `Get*StartCursorPosition` call (C `*px`; -1 if it was not set). */
    public var x: Int = -1
        private set

    /** Output y of the last `Get*StartCursorPosition` call (C `*py`; -1 if it was not set). */
    public var y: Int = -1
        private set

    /**
     * `gProcScr_ResetCursorPosition` (0x0859AE00): CALL [procFunResetCursorPosition], YIELD, END.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_ResetCursorPosition",
        listOf(call { procFunResetCursorPosition(it) }, yield(), end()),
    )

    /** Starts [script] as a child of [parent] (the way `gProcScr_BMapMain` does). */
    public fun start(parent: Proc): Proc = engine.start(script, parent)

    /**
     * Ported from src/bmcamadjust.c: GetPlayerStartCursorPosition
     *
     * Sets [x], [y] to the player leader's position (or the saved cursor with `autoCursor`); on
     * turn 1 first stores the leader's position as the saved cursor. Where the leader is missing
     * (C dereferences NULL) [x] and [y] are left as they were.
     */
    public fun getPlayerStartCursorPosition() {
        val leader = units.getUnitFromCharId(units.getPlayerLeaderPid(playSt, nodes))
        if (playSt.chapterTurnNumber == 1 && leader >= 0) {
            playSt.xCursor = units.xPos(leader)
            playSt.yCursor = units.yPos(leader)
        }
        if (playSt.autoCursor != 1) {
            if (leader >= 0) {
                x = units.xPos(leader)
                y = units.yPos(leader)
            }
        } else {
            x = playSt.xCursor
            y = playSt.yCursor
        }
    }

    /**
     * Transcribed from src/bmcamadjust.c: GetEnemyStartCursorPosition (not verified: the
     * reference runs only reset the cursor in the player phase)
     *
     * Sets [x], [y] to the last visible unit of the active faction before (and including) the
     * first boss, if any.
     */
    public fun getEnemyStartCursorPosition() {
        val faction = playSt.faction
        for (id in faction + 1 until faction + 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and (US_HIDDEN or US_BIT9)) != 0) continue
            x = units.xPos(id)
            y = units.yPos(id)
            if ((units.cAttributes(id) and CA_BOSS) != 0) break
        }
    }

    /**
     * Ported from src/bmcamadjust.c: ProcFun_ResetCursorPosition
     * (the non-blue faction branch is not verified, see [getEnemyStartCursorPosition])
     *
     * Ends [proc] if no unit of the active faction can act; otherwise moves the cursor to the
     * faction's start position, with a camera move (blocking [proc]) if the camera must follow.
     */
    public fun procFunResetCursorPosition(proc: Proc) {
        x = -1
        y = -1
        if (units.getPhaseAbleUnitCount(playSt.faction) == 0) {
            engine.end(proc)
            return
        }
        when (playSt.faction) {
            FACTION_BLUE -> getPlayerStartCursorPosition()
            FACTION_GREEN, FACTION_RED -> getEnemyStartCursorPosition()
            else -> {}
        }
        if (x >= 0 && y >= 0) {
            camMove.ensureCameraOntoPosition(proc, x, y)
            Camera.setCursorMapPosition(bm, x, y)
        }
    }

    private companion object {
        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80
        const val US_HIDDEN = 1 shl 0
        const val US_BIT9 = 1 shl 9
        const val CA_BOSS = 1 shl 15
    }
}
