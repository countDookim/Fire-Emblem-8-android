package fe8.game.event

import fe8.game.bm.BmSt
import fe8.game.bm.Camera
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_SKIPPING

/**
 * What the cursor and camera commands reach outside [BmSt]. The defaults are no-op stand-ins.
 */
public interface EventCursorCameraHooks {
    /**
     * Stand-in for the unit parameter resolution
     * (`GetUnitStructFromEventParameter`): the map position of the unit that event character
     * parameter [pid] names, as `(x shl 16) or y`, or -1 for NULL. The default is the position
     * (0, 0) for every unit (never NULL), so that the commands advance.
     */
    public fun unitPosition(pid: Int): Int = 0

    /**
     * `Proc_Start(ProcScr_EventDisplayCursor, proc)` with the given position, `subcmd` and a
     * zero timer (`EventDisplayCursor_Loop` is not ported).
     */
    public fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {}

    /** `Proc_EndEach(ProcScr_EventDisplayCursor)`. */
    public fun endDisplayCursor() {}

    /** `SetSomeRealCamPos` (not ported). */
    public fun setSomeRealCamPos(x: Int, y: Int, sc2: Int) {}

    /** `EnsureCameraOntoPosition(proc, x, y)`. */
    public fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) {}

    /** `sub_8015D84(proc, x, y)`. */
    public fun cameraOntoPositionCentred(proc: EventEngineProc, x: Int, y: Int) {}

    /** `RenderBmMap`. */
    public fun renderBmMap() {}
}

/**
 * The cursor and camera event commands of `src/eventscr.c`: 0x26, 0x3B and 0x3C.
 *
 * Verification: none. The differential test does not compare `gBmSt` (the camera and cursor) yet;
 * the commands are transcribed.
 *
 * @param state the event slots.
 * @param memory script words (the command arguments).
 * @param bm the camera and cursor state.
 * @param hooks the callees that are not ported.
 */
public class EventCursorCamera(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val bm: BmSt,
    private val hooks: EventCursorCameraHooks = object : EventCursorCameraHooks {},
) {
    /** Registers 0x26, 0x3B and 0x3C on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x26, event26CameraControl)
        engine.register(0x3B, event3bDisplayCursor)
        engine.register(0x3C, event3cMoveCursor)
    }

    private fun argX(cur: Int): Int = memory.u16(cur + 2).toByte().toInt()

    private fun argY(cur: Int): Int = (memory.u16(cur + 2) shr 8).toByte().toInt()

    /**
     * Transcribed from src/eventscr.c: Event26_CameraControl (not verified)
     *
     * Subcode 0 takes the position (slot 0xB when an argument byte is negative), 1 a unit's
     * position. Skipping or faded in: set the camera and cursor, render, continue; otherwise
     * start the camera move and yield. `SetSomeRealCamPos`, the camera moves and `RenderBmMap`
     * are [EventCursorCameraHooks].
     */
    public val event26CameraControl: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val head = memory.u16(cur)
        val subcode = head and 7
        val sc2 = (head and 0xF) shr 3
        var x = 0
        var y = 0
        when (subcode) {
            0 -> {
                x = argX(cur)
                y = argY(cur)
                if (x < 0 || y < 0) {
                    x = state.slots[0xB] and 0xFFFF
                    y = state.slots[0xB] ushr 16
                }
            }
            1 -> {
                val pos = hooks.unitPosition(memory.u16(cur + 2).toShort().toInt())
                if (pos < 0) return@EventHandler EVC_ERROR
                x = pos ushr 16
                y = pos and 0xFFFF
            }
        }
        if ((proc.evStateBits and EV_STATE_SKIPPING) != 0 ||
            (proc.evStateBits and EV_STATE_FADEDIN) != 0
        ) {
            hooks.setSomeRealCamPos(x, y, sc2)
            Camera.setCursorMapPosition(bm, x, y)
            hooks.renderBmMap()
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        if (sc2 == 0) {
            hooks.ensureCameraOntoPosition(proc, x, y)
        } else {
            hooks.cameraOntoPositionCentred(proc, x, y)
        }
        Camera.setCursorMapPosition(bm, x, y)
        EVC_ADVANCE_YIELD
    }

    /**
     * Transcribed from src/eventscr.c: Event3B_DisplayCursor (not verified)
     *
     * Subcommand `& 3`: 0 shows the cursor at a position, 1 at a unit, 2 ends the cursor procs
     * and yields. The cursor proc is [EventCursorCameraHooks.startDisplayCursor].
     */
    public val event3bDisplayCursor: EventHandler = EventHandler { proc ->
        if ((proc.evStateBits and EV_STATE_SKIPPING) != 0) {
            hooks.endDisplayCursor()
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        val cur = proc.pEventCurrent
        val subcmd = memory.u16(cur) and 0xF
        var x = 0
        var y = 0
        when (subcmd and 3) {
            0 -> {
                x = argX(cur)
                y = argY(cur)
                if (x < 0 || y < 0) {
                    x = state.slots[0xB] and 0xFFFF
                    y = state.slots[0xB] ushr 16
                }
            }
            1 -> {
                val pos = hooks.unitPosition(memory.u16(cur + 2).toShort().toInt())
                if (pos < 0) return@EventHandler EVC_ERROR
                x = pos ushr 16
                y = pos and 0xFFFF
            }
            2 -> {
                hooks.endDisplayCursor()
                return@EventHandler EVC_ADVANCE_YIELD
            }
        }
        hooks.startDisplayCursor(x, y, subcmd)
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr.c: Event3C_MoveCursor (not verified)
     *
     * Subcommand 0 stores the player cursor position in slot 0xC; 1 sets the cursor (slot 0xB
     * when an argument byte is negative).
     */
    public val event3cMoveCursor: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        when (memory.u16(cur) and 0xF) {
            0 -> state.slots[0xC] =
                (bm.playerCursorX and 0xFFFF) or ((bm.playerCursorY and 0xFFFF) shl 16)
            1 -> {
                var x = argX(cur)
                var y = argY(cur)
                if (x < 0 || y < 0) {
                    x = state.slots[0xB] and 0xFFFF
                    y = state.slots[0xB] ushr 16
                }
                Camera.setCursorMapPosition(bm, x, y)
            }
        }
        EVC_ADVANCE_CONTINUE
    }
}
