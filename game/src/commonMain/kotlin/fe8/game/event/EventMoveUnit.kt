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

/** What the move commands reach outside the MU and unit systems. Defaults are no-ops. */
public interface EventMoveUnitHost {
    /** `Proc_Find(ProcScr_CamMove) != NULL`. */
    public fun camMoveExists(): Boolean = false

    /** `EnsureCameraOntoPosition(proc, x, y)`: true if a camera move was started. */
    public fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int): Boolean = false

    /** `RefreshEntityBmMaps`. */
    public fun refreshEntityBmMaps() {}

    /** `RefreshUnitSprites`. */
    public fun refreshUnitSprites() {}

    /** `RenderBmMap`. */
    public fun renderBmMap() {}
}

/**
 * The event commands 0x2F (MOVEUNIT) and 0x30 (ENUN) of `src/eventscr.c` and their helpers
 * `TryPrepareEventUnitMovement` and `ModifyMoveUnitFlag`.
 *
 * Verification: `MoveUnitDifferentialTest` compares the blue units' end tiles after the opening
 * moves with `ram_trace_first_phase` r19400 (not the per-frame walk). The helpers and [MuCtrl]
 * are untagged.
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

    /** Transcribed from src/eventscr.c: TryPrepareEventUnitMovement. */
    private fun tryPrepare(proc: EventEngineProc, x: Int, y: Int): Boolean {
        if (proc.evStateBits and EV_STATE_UNITCAM != 0) {
            if (host.camMoveExists()) return false
            if (host.ensureCameraOntoPosition(proc, x, y)) return false
        }
        return mu.configs.any { it.slot == 0 }
    }

    /** Transcribed from src/eventscr.c: ModifyMoveUnitFlag. */
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
     * Subcodes MOVE, MOVEONTO, MOVE_1STEP and MOVE_DEFINED (REDAs of the slot queue). Skipping or
     * a negative speed moves the unit at once, else a [MuCtrl] walk starts.
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

    /** The REDAs of `gEventSlotQueue`: `gEventSlots[0xD] / 2` of them, two words each. */
    private fun queueRedas(): List<Reda> =
        List(state.slots[0xD] / 2) {
            Reda.fromWords(state.slotQueue[2 * it], state.slotQueue[2 * it + 1])
        }

    /**
     * Ported from src/eventscr.c: Event30_ENUN (verified: end tiles only, see
     * `MoveUnitDifferentialTest`)
     *
     * Waits until no [MuCtrl] walk exists (all at full speed while skipping), then refreshes the
     * entity maps, the unit sprites and the map and clears the other map.
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
        const val US_DEAD = 1 shl 2
        const val US_BIT22 = 1 shl 22
    }
}
