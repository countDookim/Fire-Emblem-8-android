package fe8.game.bm

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.yield

/**
 * The phase-start cursor reset of src/bmcamadjust.c: `gProcScr_ResetCursorPosition` and the
 * functions it calls. At the start of each phase it puts the map cursor on the active faction's
 * start position (the player's leader or last cursor spot; for the enemy and NPC phases the boss
 * or last unit) and starts a [CamMove] (blocking the reset proc) if the camera has to follow, so
 * the player sees the map scroll there. `gProc_BMapMain` (src/bm.c, label 9) starts it twice per
 * phase: first as a plain child before the phase banner, then blocking after the status, terrain
 * heal, poison and egg-hatch displays.
 *
 * Unit access goes through the raw unit table [units]. Nothing allocates per frame.
 */
public class ResetCursorPosition(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val units: UnitTable,
    private val camMove: CamMove,
    private val nodes: BattleMapNodes,
) {
    /**
     * Output x (map tile column) of the last `Get*StartCursorPosition` call (C `*px`, a local of
     * `ProcFun_ResetCursorPosition` there; -1 if it was not set).
     */
    public var x: Int = -1
        private set

    /** Output y (map tile row) of the last `Get*StartCursorPosition` call (C `*py`; -1 unset). */
    public var y: Int = -1
        private set

    /**
     * `gProcScr_ResetCursorPosition` (0x0859AE00): CALL [procFunResetCursorPosition], YIELD, END.
     * A camera move started by the call blocks the proc until the camera has arrived.
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
     * Sets [x], [y] to where the player phase cursor starts: the saved cursor position
     * (`gPlaySt.xCursor`/`yCursor`, where the cursor was last left in the player phase) when the
     * game option "auto cursor" (`config.autoCursor`) is 1, otherwise the leader's position (the
     * main lord of the chosen route, from `GetPlayerLeaderPid`, or the first available unit when
     * that lord is not deployed). On turn 1 the leader's position is first stored as the saved
     * cursor, so the first turn always starts on the leader. Where the leader is missing (C
     * dereferences NULL) [x] and [y] are left as they were.
     */
    public fun getPlayerStartCursorPosition() {
        val leaderUnitId = units.getUnitFromCharId(units.getPlayerLeaderPid(playSt, nodes))
        if (playSt.chapterTurnNumber == 1 && leaderUnitId >= 0) {
            playSt.xCursor = units.xPos(leaderUnitId)
            playSt.yCursor = units.yPos(leaderUnitId)
        }
        if (playSt.autoCursor != 1) {
            if (leaderUnitId >= 0) {
                x = units.xPos(leaderUnitId)
                y = units.yPos(leaderUnitId)
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
     * Sets [x], [y] to where the cursor goes at the start of an enemy or NPC phase: the first
     * boss (`CA_BOSS`) of the active faction that is not hidden, or the last such unit if there
     * is no boss. Walks the faction's unit ids in order and keeps overwriting until a boss.
     */
    public fun getEnemyStartCursorPosition() {
        val faction = playSt.faction
        for (unitId in faction + 1 until faction + 0x40) {
            if (!units.isValid(unitId)) continue
            if ((units.state(unitId) and (US_HIDDEN or US_BIT9)) != 0) continue
            x = units.xPos(unitId)
            y = units.yPos(unitId)
            if ((units.cAttributes(unitId) and CA_BOSS) != 0) break
        }
    }

    /**
     * Ported from src/bmcamadjust.c: ProcFun_ResetCursorPosition
     * (the non-blue faction branch is not verified, see [getEnemyStartCursorPosition])
     *
     * Ends [proc] if no unit of the active faction can act (`GetPhaseAbleUnitCount` is 0, for
     * example an NPC phase with no NPC left), leaving cursor and camera alone; otherwise moves
     * the cursor to the faction's start position, with a camera move (blocking [proc]) if the
     * camera must follow. A faction value other than the three below leaves [x], [y] unset.
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
        /** `FACTION_BLUE` (bmunit.h): player units; unit ids 0x01..0x3F follow it. */
        const val FACTION_BLUE = 0x00

        /** `FACTION_GREEN` (bmunit.h): ally NPC units; unit ids 0x41..0x7F follow it. */
        const val FACTION_GREEN = 0x40

        /** `FACTION_RED` (bmunit.h): enemy units; unit ids 0x81..0xBF follow it. */
        const val FACTION_RED = 0x80

        /** `US_HIDDEN` (bmunit.h, `1 << 0`): the unit is hidden, not drawn on the map. */
        const val US_HIDDEN = 1 shl 0

        /**
         * `US_BIT9` (bmunit.h, `1 << 9`): the decomp's comment guesses "hidden by fog?"; its
         * real meaning is not confirmed.
         */
        const val US_BIT9 = 1 shl 9

        /** `CA_BOSS` (bmunit.h, `1 << 15`): the character/class attribute that marks a boss. */
        const val CA_BOSS = 1 shl 15
    }
}
