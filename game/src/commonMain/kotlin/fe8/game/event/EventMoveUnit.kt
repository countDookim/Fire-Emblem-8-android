package fe8.game.event

import fe8.game.bm.BmMap
import fe8.game.bm.Mu
import fe8.game.bm.MuCtrl
import fe8.game.bm.Reda
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_0040
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventConst.EV_STATE_UNITCAM

/**
 * What the unit move commands reach outside the MU (movement unit: the walking map sprite) and
 * unit systems: the camera scroll proc and the map redraw calls. Defaults are no-ops (no camera
 * move exists, nothing is started).
 */
public interface EventMoveUnitHost {
    /** `Proc_Find(ProcScr_CamMove) != NULL`: a camera scroll is already running. */
    public fun camMoveExists(): Boolean = false

    /**
     * `EnsureCameraOntoPosition(proc, x, y)` (src/bm.c:1044): true if a (blocking) camera
     * scroll to the tile was started, false if the camera is already there or a scroll runs.
     */
    public fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int): Boolean = false

    /** `RefreshEntityBmMaps`: rebuilds the unit and hidden-unit maps from the unit arrays. */
    public fun refreshEntityBmMaps() {}

    /** `RefreshUnitSprites`: redraws the standing map sprites of the units. */
    public fun refreshUnitSprites() {}

    /** `RenderBmMap`: redraws the map tiles. */
    public fun renderBmMap() {}
}

/**
 * The event commands 0x2F (MOVEUNIT: walk a unit across the map in a scene) and 0x30 (ENUN: wait
 * until every scripted walk has finished) of `src/eventscr.c` and their helpers
 * `TryPrepareEventUnitMovement` and `ModifyMoveUnitFlag`.
 *
 * Verification: `MoveUnitDifferentialTest` compares the blue units' end tiles after the opening
 * moves with `ram_trace_first_phase` r19400 (not the per-frame walk). The helpers and [MuCtrl]
 * are untagged.
 *
 * @param state the event slots (`gEventSlots[0xD]` and the slot queue hold the defined moves).
 * @param memory script words (the command arguments).
 * @param table the unit arrays (positions and state bits).
 * @param map the map layers (`gBmMapOther` is cleared before and after moves).
 * @param mu the movement units (map sprites that walk).
 * @param muCtrl starts and queries the walks.
 * @param unitFromParameter `GetUnitStructFromEventParameter`: unit id of an event character
 *   parameter, or -1.
 * @param host the callees outside the MU and unit systems.
 */
public class EventMoveUnit(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val table: UnitTable,
    private val map: BmMap,
    private val mu: Mu,
    private val muCtrl: MuCtrl,
    private val unitFromParameter: (Int) -> Int,
    private val host: EventMoveUnitHost,
) {
    /** Registers 0x2F and 0x30 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x2F, event2fMoveUnit)
        engine.register(0x30, event30Enun)
    }

    /**
     * Transcribed from src/eventscr.c: TryPrepareEventUnitMovement (not verified: no @VerifiesPort
     * test names it).
     *
     * Whether the walk may start now. With the unit camera on (`EV_STATE_UNITCAM`, "camera
     * follows unit movement") no camera scroll may be running, and starting one towards the
     * unit's tile ([x], [y]) counts as not ready (the command is retried next frame). Then an MU
     * slot must be free (`CanStartMu`: some `sMuConfig` entry has `slot == 0`).
     */
    private fun tryPrepare(proc: EventEngineProc, x: Int, y: Int): Boolean {
        if (proc.evStateBits and EV_STATE_UNITCAM != 0) {
            if (host.camMoveExists()) return false
            if (host.ensureCameraOntoPosition(proc, x, y)) return false
        }
        return mu.configs.any { it.slot == 0 }
    }

    /**
     * Transcribed from src/eventscr.c: ModifyMoveUnitFlag (not verified: no @VerifiesPort test
     * names it).
     *
     * The flags word passed to the MU control walk and [MuCtrl.moveUnit] (`flagsB` in
     * src/muctrl.c). Bit 0 is [unk], the command's `modify` sub-command bit: the walk may
     * relocate the destination (`AiGetUnitClosestValidPosition`, src/muctrl.c:526-532). Bit 2
     * (value 4) with `EV_STATE_UNITCAM`: the MU drags the camera along (`EnableMuCamera`,
     * src/muctrl.c:448). Bit 3 (value 8) with `EV_STATE_0040` (set by `EVBIT_MODIFY`): the MU
     * config gets `0x40` (src/muctrl.c:441; that config bit's meaning is not known).
     */
    private fun moveFlags(proc: EventEngineProc, unk: Boolean): Int {
        var result = if (unk) 1 else 0
        if (proc.evStateBits and EV_STATE_UNITCAM != 0) result = result or 4
        if (proc.evStateBits and EV_STATE_0040 != 0) result = result or 8
        return result
    }

    /**
     * Ported from src/eventscr.c: Event2F_MoveUnit (verified: end tiles only, see
     * `MoveUnitDifferentialTest`)
     *
     * Arguments: speed (`EVT_CMD_ARGV[0]`), the unit's character parameter (`[1]`) and a target
     * (`[2]`). Subcodes (`EVSUBCMD_`): MOVE (a tile, packed X/Y bytes), MOVEONTO (the tile of
     * another unit), MOVE_1STEP (one tile in direction 0 left, 1 right, 2 down, 3 up) and
     * MOVE_DEFINED (the REDAs, scripted move records, of the slot queue). Skipping the scene or a
     * negative speed moves the unit there at once, else a [MuCtrl] walk starts and the command
     * continues (use ENUN to wait). Yields while the walk cannot start ([tryPrepare]).
     *
     * Skips a missing unit and a dead one that is not a cutscene unit. An unresolved MOVEONTO
     * target is an error.
     *
     * NOTE: subcodes 4-7 (the sub-command is 3 bits) are not handled here (`else` continues
     * without moving); the decomp switch has no case for them and would use uninitialised
     * `xOut`/`yOut` (src/eventscr.c:2690-2745). No decomp script macro emits them
     * (include/eventscript.h:694-697 has only subcodes 0-3).
     *
     * NOTE: with MOVE_DEFINED while skipping, the decomp calls `MoveUnit_(unit, xOut, yOut, ...)`
     * with `xOut`/`yOut` never set on that path (src/eventscr.c:2741-2748, an uninitialised
     * read); this port instead moves the unit to the last REDA of the queue (what `MoveUnitExt`
     * does with the queue), and throws if the queue is empty.
     */
    public val event2fMoveUnit: EventHandler = EventHandler { proc ->
        val currentCmd = proc.pEventCurrent
        val header = memory.u16(currentCmd)
        val subcommand = header and 0x7
        val subFlag = (header and 0xF) shr 3
        val speed = memory.u16(currentCmd + 2).toShort().toInt()
        val unit = unitFromParameter(memory.u16(currentCmd + 4).toShort().toInt())
        if (unit < 0) return@EventHandler EVC_ADVANCE_CONTINUE
        val sourceX = table.xPos(unit)
        val sourceY = table.yPos(unit)
        // A dead unit does not move, unless it carries `US_BIT22` (see [US_BIT22]).
        if (table.state(unit) and US_BIT22 == 0 && table.state(unit) and US_DEAD != 0) {
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        var targetX = 0
        var targetY = 0
        var useQueue = false
        val positionArg = memory.u16(currentCmd + 6)
        when (subcommand) {
            0 -> {
                targetX = positionArg.toByte().toInt()
                targetY = (positionArg shr 8).toByte().toInt()
            }
            1 -> {
                var targetCharId = positionArg.toShort().toInt()
                if (targetCharId < 0) targetCharId = 0
                val target = unitFromParameter(targetCharId)
                if (target < 0) return@EventHandler EVC_ERROR
                targetX = table.xPos(target)
                targetY = table.yPos(target)
            }
            2 -> {
                targetX = sourceX
                targetY = sourceY
                when (positionArg) {
                    3 -> targetY--
                    2 -> targetY++
                    0 -> targetX--
                    1 -> targetX++
                }
            }
            3 -> useQueue = true
            else -> return@EventHandler EVC_ADVANCE_CONTINUE
        }
        val flags = moveFlags(proc, subFlag != 0)
        map.bmMapFill(map.other, 0)
        if (proc.evStateBits and EV_STATE_SKIPPING != 0 || speed < 0) {
            if (useQueue) {
                val lastReda = queueRedas().last()
                muCtrl.moveUnit(unit, lastReda.x, lastReda.y, flags)
            } else {
                muCtrl.moveUnit(unit, targetX, targetY, flags)
            }
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        if (!tryPrepare(proc, sourceX, sourceY)) return@EventHandler EVC_STOP_YIELD
        if (!useQueue) {
            muCtrl.startMoveTowards(unit, targetX, targetY, speed, flags)
        } else {
            val redas = queueRedas()
            muCtrl.startDefinedMove(unit, redas, redas.size, flags)
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * The REDAs (scripted move records) that the script queued in `gEventSlotQueue`:
     * `gEventSlots[0xD] / 2` of them, two words each.
     */
    private fun queueRedas(): List<Reda> =
        List(state.slots[0xD] / 2) {
            Reda.fromWords(state.slotQueue[2 * it], state.slotQueue[2 * it + 1])
        }

    /**
     * Ported from src/eventscr.c: Event30_ENUN (verified: end tiles only, see
     * `MoveUnitDifferentialTest`)
     *
     * Waits until no [MuCtrl] walk exists (`MuCtrExists`; walking at maximum speed while the
     * scene is skipped), then refreshes the entity maps, the unit sprites and the map and clears
     * `gBmMapOther`, which the walks used to mark their destination tiles. Yields once done.
     */
    public val event30Enun: EventHandler = EventHandler { proc ->
        if (proc.evStateBits and EV_STATE_SKIPPING != 0) muCtrl.setMuMaxWalkSpeed()
        if (muCtrl.muCtrExists()) return@EventHandler EVC_STOP_YIELD
        host.refreshEntityBmMaps()
        host.refreshUnitSprites()
        host.renderBmMap()
        map.bmMapFill(map.other, 0)
        EVC_ADVANCE_YIELD
    }

    private companion object {
        /** `US_DEAD` (include/bmunit.h:229). */
        const val US_DEAD = 1 shl 2

        /**
         * `US_BIT22` (include/bmunit.h:250): its name is unknown; the event unit loader sets it
         * on a blue unit loaded or revived for a scene (src/eventscr.c:2300-2311), and
         * `ClearCutsceneUnits` (src/bmunit.c:1590) removes such a unit, or only clears the bit
         * if the unit is dead.
         */
        const val US_BIT22 = 1 shl 22
    }
}
