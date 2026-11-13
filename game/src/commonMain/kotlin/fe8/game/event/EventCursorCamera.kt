package fe8.game.event

import fe8.game.bm.BmSt
import fe8.game.bm.Camera
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts

/**
 * What the cursor and camera commands reach outside [BmSt]: unit positions, the map cursor proc,
 * the camera scroll procs and the map redraw. The defaults are no-ops.
 */
public interface EventCursorCameraHooks {
    /**
     * The unit parameter resolution
     * (`GetUnitStructFromEventParameter`): the map position of the unit that event character
     * parameter [pid] names, as `(x shl 16) or y`, or -1 for NULL. The default is the position
     * (0, 0) for every unit (never NULL), so that the commands advance; `Fe8Boot` overrides it
     * with the position of the resolved unit.
     */
    public fun unitPosition(pid: Int): Int = 0

    /**
     * `Proc_Start(ProcScr_EventDisplayCursor, proc)` (src/eventscr.c:3485) with the given tile
     * position, `subcmd` (0/1 plain cursor, 4/5 flashing cursor) and a zero timer. The proc's
     * per-frame body is [eventDisplayCursorLoop].
     */
    public fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {}

    /** `Proc_EndEach(ProcScr_EventDisplayCursor)`: removes every event cursor proc (hides it). */
    public fun endDisplayCursor() {}

    /**
     * `SetSomeRealCamPos` (src/eventscr_utils.c:89, not ported): puts the camera on the tile at
     * once, with no scroll. [sc2] is bit 3 of the command's sub-command nibble (`sc2` in the
     * decomp): 1 uses `StoreAdjustedCameraPositions` (the centred variant), anything else the
     * `GetCameraAdjustedX/Y` (the smallest scroll that puts the tile inside the camera margins).
     */
    public fun setSomeRealCamPos(x: Int, y: Int, sc2: Int) {}

    /**
     * `EnsureCameraOntoPosition(proc, x, y)` (src/bm.c:1044): starts a blocking camera scroll
     * proc that moves just far enough to bring the tile on screen (nothing if the camera is
     * already there or a scroll is running).
     */
    public fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) {}

    /**
     * `sub_8015D84(proc, x, y)` (src/bm.c:1005): like [ensureCameraOntoPosition] but the target
     * comes from `StoreAdjustedCameraPositions`. The decomp does not name it; the scroll style
     * the event uses when the move bit is 1 (centred on the tile).
     */
    public fun cameraOntoPositionCentred(proc: EventEngineProc, x: Int, y: Int) {}

    /** `RenderBmMap`: redraws the map after an instant camera jump. */
    public fun renderBmMap() {}
}

/**
 * The cursor and camera event commands of `src/eventscr.c`: 0x26 (`Event26_CameraControl`, scroll
 * the camera to a tile or unit), 0x3B (`Event3B_DisplayCursor`, show or hide the event cursor)
 * and 0x3C (`Event3C_MoveCursor`, read or set the player cursor).
 *
 * Verification: none claimed. The differential test compares the cursor fields of `gBmSt` after
 * 0x26 (one pair) and 0x3B (no cursor write); 0x3C never runs in the trace and the camera is not
 * compared. The commands are transcribed.
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

    /**
     * The signed X byte of the position argument (low byte of `EVT_CMD_ARGV[0]`, the `s8 x` of
     * the decomp) of the command at [cur]. Negative means "use the position in slot 0xB".
     */
    private fun argX(cur: Int): Int = memory.u16(cur + 2).toByte().toInt()

    /**
     * The signed Y byte of the position argument (high byte of `EVT_CMD_ARGV[0]`) of the
     * command at [cur]. Negative means "use the position in slot 0xB".
     */
    private fun argY(cur: Int): Int = (memory.u16(cur + 2) shr 8).toByte().toInt()

    /**
     * Ported from src/eventscr.c: Event26_CameraControl
     *
     * Subcode (`EVT_SUB_CMD_LO`, low 3 bits) 0 takes the tile from the argument bytes (from slot
     * 0xB, X in the low half and Y in the high half, when either byte is negative), 1 takes the
     * tile of a unit; an unresolvable unit is an error. Skipping the scene or after the screen
     * has faded in: jump the camera, set the cursor, redraw, continue; otherwise start the
     * camera scroll and yield until it ends. `SetSomeRealCamPos`, the camera scrolls and
     * `RenderBmMap` are [EventCursorCameraHooks].
     *
     * Other subcodes (2-7) leave the tile at (0, 0) here; the decomp leaves `x`/`y`
     * uninitialised in that case, and no known script uses them.
     */
    public val event26CameraControl: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val head = memory.u16(cur)
        val subcode = head and 7
        // Bit 3 of the sub-command nibble: 0 scrolls just onto the position, 1 centres on it.
        val moveStyle = (head and 0xF) shr 3
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
            hooks.setSomeRealCamPos(x, y, moveStyle)
            Camera.setCursorMapPosition(bm, x, y)
            hooks.renderBmMap()
            return@EventHandler EVC_ADVANCE_CONTINUE
        }
        if (moveStyle == 0) {
            hooks.ensureCameraOntoPosition(proc, x, y)
        } else {
            hooks.cameraOntoPositionCentred(proc, x, y)
        }
        Camera.setCursorMapPosition(bm, x, y)
        EVC_ADVANCE_YIELD
    }

    /**
     * Ported from src/eventscr.c: Event3B_DisplayCursor
     *
     * Subcommand (low nibble) `& 3` (`EVSUBCMD_CURSOR_AT`, `EVSUBCMD_CURSOR_UNIT`,
     * `EVSUBCMD_CURE`): 0 shows the cursor at a tile (slot 0xB when an argument byte is
     * negative), 1 at a unit, 2 ends the cursor procs and yields. Bit 2 of the subcommand
     * selects the flashing cursor (`EVSUBCMD_CURSOR_FLASHING_*`, see [eventDisplayCursorLoop]).
     * Skipping the scene only removes the cursor. The cursor proc is
     * [EventCursorCameraHooks.startDisplayCursor].
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
     * Transcribed from src/eventscr.c: Event3C_MoveCursor (not verified: opcode 0x3C is only
     * in tutorial scripts and never runs in available mGBA reference traces)
     *
     * Subcommand 0 (`EVSUBCMD_CHECK_CURSOR`) stores the player cursor tile in slot 0xC (X low
     * half, Y high half); 1 (`EVSUBCMD_SET_CURSOR`) moves the cursor to a tile (slot 0xB when an
     * argument byte is negative). The camera is not scrolled.
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

/**
 * State of one event cursor proc for [createProcScrEventDisplayCursor] (`struct MapCursorProc`
 * in src/eventscr.c): where it is drawn and which style.
 */
public class MapCursorProc(
    /** Tile X coordinate (`proc->x`, offset `+0x64`). */
    public var x: Int = 0,
    /** Tile Y coordinate (`proc->y`, offset `+0x66`). */
    public var y: Int = 0,
    /** Cursor sub-command (`proc->subcmd`, offset `+0x68`). */
    public var subcmd: Int = 0,
    /** Flashing cursor frame counter (`proc->timer`, offset `+0x6A`); starts at 0. */
    public var timer: Int = 0,
)

/**
 * Ported from src/eventscr.c: EventDisplayCursor_Loop
 *
 * One frame of the event cursor: draws a plain map cursor (sub-command bits `0xC` = 0) or advances
 * the flashing cursor animation (= 4, `EventShinningCursorAdvance` in the decomp) at the tile of
 * [proc]. Other bit values draw nothing.
 *
 * @param proc the cursor proc state.
 * @param putMapCursor `PutMapCursor`: draws a map cursor at a pixel position (the tile times 16);
 *   `kind` 0 is passed for the plain cursor.
 * @param eventShinningCursorAdvance draws the flashing cursor at a tile and returns the next
 *   frame counter.
 */
public fun eventDisplayCursorLoop(
    proc: MapCursorProc,
    putMapCursor: (x: Int, y: Int, kind: Int) -> Unit = { _, _, _ -> },
    eventShinningCursorAdvance: (x: Int, y: Int, counter: Int) -> Int = { _, _, c -> c },
) {
    when (proc.subcmd and 0xc) {
        0 -> putMapCursor(proc.x * 16, proc.y * 16, 0)
        4 -> proc.timer = eventShinningCursorAdvance(proc.x, proc.y, proc.timer)
    }
}

/**
 * `ProcScr_EventDisplayCursor` (`src/eventscr.c:3435`): the proc that keeps the event cursor
 * drawn every frame with [eventDisplayCursorLoop] until it is ended (`endDisplayCursor`).
 */
public fun createProcScrEventDisplayCursor(
    putMapCursor: (x: Int, y: Int, kind: Int) -> Unit = { _, _, _ -> },
    eventShinningCursorAdvance: (x: Int, y: Int, counter: Int) -> Int = { _, _, c -> c },
): ProcScript = ProcScript(
    name = "ProcScr_EventDisplayCursor",
    cmds = listOf(
        ProcScripts.repeat(ProcFunc { proc ->
            eventDisplayCursorLoop(
                proc.state as MapCursorProc,
                putMapCursor,
                eventShinningCursorAdvance,
            )
        }),
        ProcScripts.end(),
    ),
    newState = { MapCursorProc() },
)
