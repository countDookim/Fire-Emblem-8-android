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
 * `EnsureCameraOntoPosition`, `sub_8015D84` and `CameraMove_8015EDC`.
 *
 * `CamMove_OnInit` builds the ease-in table `sCameraAnimTable` (EWRAM 0x0202BD44, `s8[0x100]`)
 * at runtime; here it is the preallocated [animTable]. Only one CamMove proc exists at a time
 * (every starter checks `Proc_Find`), so its fields are one preallocated instance, [fields].
 * Nothing allocates per frame.
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
        /** `to.x` (0x2C): target camera horizontal pixel coordinate. */
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

        /** `calibration` (s16, 0x38): total pixel distance along the longer movement axis. */
        public var calibration: Int = 0

        /** `distance` (s16, 0x3A): remaining pixel distance still to move along the calibrated axis. */
        public var distance: Int = 0

        /** `frame` (0x3C): step index into the ease-in animation table [animTable]. */
        public var frame: Int = 0

        /** `xCalibrated` (s8, 0x40): 1 if the X axis delta is greater than the Y axis delta; 0 otherwise. */
        public var xCalibrated: Int = 0
    }

    /** The fields of the single CamMove proc. */
    public val fields: Fields = Fields()

    /** `sCameraAnimTable`: per-frame pixel steps (`s8`) built by [onInit]. */
    public val animTable: ByteArray = ByteArray(ANIM_TABLE_SIZE)

    private val onInitFn = ProcFunc { onInit() }
    private val onLoopFn = ProcFunc { onLoop(it) }

    /**
     * `ProcScr_CamMove` (0x0859A548): NAME "GENS", SLEEP(0), CALL [onInit], REPEAT [onLoop], END.
     * All procs share [fields].
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
     * Picks the longer axis as the calibration and fills [animTable] with the steps of an eased
     * move over it (step `(speed >> 1)` with speed rising 1..16, a final remainder step).
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
     * One step of the move: sets the camera on the line from `from` to `to` according to the
     * remaining distance; at frame 0 sets `to` to the camera and ends [proc].
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
        bm.cameraX = s16(camFields.toX + (camFields.fromX - camFields.toX) * camFields.distance / camFields.calibration)
        bm.cameraY = s16(camFields.toY + (camFields.fromY - camFields.toY) * camFields.distance / camFields.calibration)
    }

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
     * ([x], [y]) in view, unless the camera is already there or a CamMove exists.
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
     * to the map of [mapSizeX] x [mapSizeY] tiles).
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
     * Starts a CamMove back up to `cameraMax.y` when the camera is below it.
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
        /** `PROC_TREE_3`: the root tree index the unparented CamMove starts in. */
        public const val PROC_TREE_3: Int = 3

        /** Size of `sCameraAnimTable` easing table in bytes. */
        public const val ANIM_TABLE_SIZE: Int = 0x100
    }
}
