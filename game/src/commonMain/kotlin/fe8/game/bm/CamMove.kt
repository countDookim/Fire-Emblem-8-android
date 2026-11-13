package fe8.game.bm

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/**
 * The battle map camera move proc `ProcScr_CamMove` (src/bm.c) and the functions that start it:
 * `EnsureCameraOntoPosition`, `sub_8015D84` and `CameraMove_8015EDC`. What the player sees is
 * the map sliding to a new place (to a unit that is about to act, to the target of an attack
 * animation or event, back from the unit menu): fast at first (at most 8 pixels a frame), then
 * slowing down to 1 pixel a frame as it arrives. The proc that asked for the move waits until
 * the camera has arrived, if it passed itself as the blocked parent.
 *
 * `CamMove_OnInit` builds the step table `sCameraAnimTable` (EWRAM 0x0202BD44, `s8[0x100]`)
 * at runtime, once for the distance to cover; here it is the preallocated [animTable]. The
 * table is built speeding up and played from its end, which is why the move slows down. Only
 * one CamMove proc exists at a time (every starter checks `Proc_Find`), so its fields are one
 * preallocated instance, [fields]. Nothing allocates per frame.
 */
public class CamMove(
    private val engine: ProcEngine,
    private val bm: BmSt,
) {
    /**
     * `struct CamMoveProc` past `PROC_HEADER` (0x2C..0x40); Vec2 and `s16` members hold `s16`
     * values.
     */
    public class Fields {
        /** `to.x` (0x2C): target camera horizontal pixel coordinate (the camera `x` to end on). */
        public var toX: Int = 0

        /** `to.y` (0x2E): target camera vertical pixel coordinate. */
        public var toY: Int = 0

        /** `from.x` (0x30): starting camera horizontal pixel coordinate. */
        public var fromX: Int = 0

        /** `from.y` (0x32): starting camera vertical pixel coordinate. */
        public var fromY: Int = 0

        /** `watchedCoordinate.x` (0x34): watched map tile column. */
        public var watchedX: Int = 0

        /** `watchedCoordinate.y` (0x36): watched map tile row. */
        public var watchedY: Int = 0

        /**
         * `calibration` (s16, 0x38): total pixel distance along the longer movement axis; the
         * other axis moves by the same fraction of its own distance.
         */
        public var calibration: Int = 0

        /**
         * `distance` (s16, 0x3A): pixel distance still to move along the calibrated axis; counts
         * down from [calibration] to 0.
         */
        public var distance: Int = 0

        /**
         * `frame` (0x3C): index of the next step to play in [animTable]; it starts at the last
         * entry and counts down, the move ends when it reaches 0 (entry 0 is never played).
         */
        public var frame: Int = 0

        /**
         * `xCalibrated` (s8, 0x40): 1 if the X axis delta is greater than the Y axis delta, else
         * 0. Only written by `CamMove_OnInit`; nothing in the decomp reads it.
         */
        public var xCalibrated: Int = 0
    }

    /** The fields of the single CamMove proc. */
    public val fields: Fields = Fields()

    /**
     * `sCameraAnimTable`: per-frame pixel steps (`s8`) built by [onInit]: 0, 1, 1, 2, 2, ... up
     * to 8 and then 8 again, ending in the remainder. [onLoop] plays it backwards.
     */
    public val animTable: ByteArray = ByteArray(ANIM_TABLE_SIZE)

    private val onInitFn = ProcFunc { onInit() }
    private val onLoopFn = ProcFunc { onLoop(it) }

    /**
     * `ProcScr_CamMove` (0x0859A548): NAME "GENS" (the meaning of the name is unknown),
     * SLEEP(0) (one frame), CALL [onInit], REPEAT [onLoop], END. All procs share [fields].
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_CamMove",
        listOf(name("GENS"), yield(), call(onInitFn), repeat(onLoopFn), end()),
    ) { fields }

    /** Truncates [value] to a signed 16-bit integer sign-extended to 32 bits. */
    private fun s16(value: Int): Int = value.toShort().toInt()

    /** Returns the absolute value of [value]. */
    private fun abs(value: Int): Int = if (value < 0) -value else value

    /**
     * Ported from src/bm.c: CamMove_OnInit
     *
     * Picks the longer axis as the calibration and fills [animTable] with the steps that cover
     * it: step `speed >> 1` with `speed` rising by 1 a step up to 16 (so 0, 1, 1, 2, 2, ... 8,
     * 8, ...), and a last entry holding what is left over. [Fields.frame] becomes the index of
     * that last entry, which [onLoop] plays first, then the others down to index 1.
     */
    public fun onInit() {
        val camFields = fields
        var speed = 1
        val xDiff = abs(camFields.toX - camFields.fromX)
        val yDiff = abs(camFields.toY - camFields.fromY)
        if (xDiff > yDiff) {
            camFields.xCalibrated = 1
            camFields.calibration = s16(xDiff)
        } else {
            camFields.xCalibrated = 0
            camFields.calibration = s16(yDiff)
        }
        var remainingDistance = camFields.calibration
        var stepIndex = 0
        while (true) {
            if (remainingDistance - (speed shr 1) < 0) {
                animTable[stepIndex] = remainingDistance.toByte()
                break
            }
            remainingDistance -= speed shr 1
            animTable[stepIndex] = (speed shr 1).toByte()
            if (speed < 16) speed++
            stepIndex++
        }
        camFields.frame = stepIndex
        camFields.distance = camFields.calibration
    }

    /**
     * Ported from src/bm.c: CamMove_OnLoop
     *
     * One frame of the move: takes the next step off [Fields.distance] and puts the camera on
     * the straight line from `from` to `to` at that remaining fraction, so it slides in a
     * straight line and slows as the steps shrink. When [Fields.frame] is 0 it stores the
     * camera as `to` and ends [proc], which lets a blocked parent go on.
     */
    public fun onLoop(proc: Proc) {
        val camFields = fields
        if (camFields.frame == 0) {
            camFields.toX = bm.cameraX
            camFields.toY = bm.cameraY
            engine.end(proc)
            return
        }
        camFields.distance = s16(camFields.distance - animTable[camFields.frame--])
        val distance = camFields.distance
        val calibration = camFields.calibration
        bm.cameraX = s16(camFields.toX + (camFields.fromX - camFields.toX) * distance / calibration)
        bm.cameraY = s16(camFields.toY + (camFields.fromY - camFields.toY) * distance / calibration)
    }

    /**
     * The start shared by `EnsureCameraOntoPosition`, `sub_8015D84` and `CameraMove_8015EDC`
     * (the same lines in each C function): starts [script], blocking [parent] until it ends or,
     * without a parent, as a free proc in tree 3, and records the move from the current camera
     * to ([toX], [toY]) pixels. [onInit] runs on the next frame, after this call returns.
     */
    private fun begin(parent: Proc?, toX: Int, toY: Int) {
        if (parent != null) {
            engine.startBlocking(script, parent)
        } else {
            engine.start(script, PROC_TREE_3)
        }
        val camFields = fields
        camFields.fromX = bm.cameraX
        camFields.fromY = bm.cameraY
        camFields.toX = toX
        camFields.toY = toY
    }

    /**
     * Ported from src/bm.c: EnsureCameraOntoPosition
     *
     * Starts a CamMove (blocking [parent] unless null) onto the camera that keeps map position
     * ([x], [y]) in view (see [Camera.getCameraAdjustedX]), unless the camera is already there
     * or a CamMove exists. Called whenever the game wants the map to follow a unit: the start
     * of a phase, a unit about to act or be attacked, the unit menu, item use and the camera
     * event command.
     *
     * @return true (C `1`) if a move was started.
     */
    public fun ensureCameraOntoPosition(parent: Proc?, x: Int, y: Int): Boolean {
        val targetCameraX = Camera.getCameraAdjustedX(bm, x * 16)
        val targetCameraY = Camera.getCameraAdjustedY(bm, y * 16)
        if (targetCameraX == bm.cameraX && targetCameraY == bm.cameraY) return false
        if (engine.find(script) != null) return false
        begin(parent, targetCameraX, targetCameraY)
        fields.watchedX = s16(x)
        fields.watchedY = s16(y)
        return true
    }

    /**
     * Transcribed from src/bm.c: sub_8015D84 (not verified: no reference run starts it)
     *
     * Like [ensureCameraOntoPosition] but targets the camera
     * [Camera.storeAdjustedCameraPositionX]/Y (a 15x10 tile window around the position, clamped
     * to the map of [mapSizeX] x [mapSizeY] tiles), so the position is centred rather than
     * just brought inside the scroll margins. In the decomp only the event camera command
     * (`Event26_CameraControl`, src/eventscr.c) calls it, when the command's flag byte is
     * nonzero; the map size is passed in by the port, the C reads `gBmMapSize`.
     *
     * @return true (C `1`) if a move was started.
     */
    public fun startCameraOntoPositionCentred(
        parent: Proc?,
        x: Int,
        y: Int,
        mapSizeX: Int,
        mapSizeY: Int,
    ): Boolean {
        val targetCameraX = Camera.storeAdjustedCameraPositionX(x, mapSizeX) * 16
        val targetCameraY = Camera.storeAdjustedCameraPositionY(y, mapSizeY) * 16
        if (targetCameraX == bm.cameraX && targetCameraY == bm.cameraY) return false
        if (engine.find(script) != null) return false
        begin(parent, targetCameraX, targetCameraY)
        fields.watchedX = s16(x)
        fields.watchedY = s16(y)
        return true
    }

    /**
     * Transcribed from src/bm.c: CameraMove_8015EDC (not verified: no reference run starts it)
     *
     * Starts a CamMove back up to `cameraMax.y` when the camera is below it (to its own x, so
     * only vertically). The decomp calls it with no parent from `SubtitleHelp_OnEnd`
     * (src/bb.c) when the subtitle help bar goes away: `StartSubtitleHelp` had raised
     * `cameraMax.y` by 16 pixels (the C does not say why), and the end callback lowers it
     * again, so a camera in that extra strip is moved back up to the limit.
     *
     * @return true (C `1`) if a move was started.
     */
    public fun moveCameraWithinMax(parent: Proc?): Boolean {
        if (bm.cameraY <= bm.cameraMaxY) return false
        if (engine.find(script) != null) return false
        begin(parent, bm.cameraX, bm.cameraMaxY)
        return true
    }

    /** Constants for the camera movement process. */
    public companion object {
        /**
         * `PROC_TREE_3` (include/proc.h): the root proc tree the unparented CamMove starts in
         * (what distinguishes tree 3 from the others is unknown here).
         */
        public const val PROC_TREE_3: Int = 3

        /**
         * Size of `sCameraAnimTable` in bytes (0x100 entries). Entries are at most 8 pixels, so
         * it only overflows for a move of roughly 2000 pixels, more than a map is wide.
         */
        public const val ANIM_TABLE_SIZE: Int = 0x100
    }
}
