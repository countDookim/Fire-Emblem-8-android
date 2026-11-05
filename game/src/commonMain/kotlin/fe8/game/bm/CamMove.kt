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
        /** `to.x` (0x2C). */
        public var toX: Int = 0

        /** `to.y` (0x2E). */
        public var toY: Int = 0

        /** `from.x` (0x30). */
        public var fromX: Int = 0

        /** `from.y` (0x32). */
        public var fromY: Int = 0

        /** `watchedCoordinate.x` (0x34). */
        public var watchedX: Int = 0

        /** `watchedCoordinate.y` (0x36). */
        public var watchedY: Int = 0

        /** `calibration` (s16, 0x38): the longer axis distance in pixels. */
        public var calibration: Int = 0

        /** `distance` (s16, 0x3A): pixels still to move. */
        public var distance: Int = 0

        /** `frame` (0x3C): index into the table of the next step. */
        public var frame: Int = 0

        /** `xCalibrated` (s8, 0x40). */
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

    private fun s16(v: Int): Int = v.toShort().toInt()

    private fun abs(v: Int): Int = if (v < 0) -v else v

    /**
     * Ported from src/bm.c: CamMove_OnInit
     *
     * Picks the longer axis as the calibration and fills [animTable] with the steps of an eased
     * move over it (step `(speed >> 1)` with speed rising 1..16, a final remainder step).
     */
    public fun onInit() {
        val f = fields
        var speed = 1
        val xDiff = abs(f.toX - f.fromX)
        val yDiff = abs(f.toY - f.fromY)
        if (xDiff > yDiff) {
            f.xCalibrated = 1
            f.calibration = s16(xDiff)
        } else {
            f.xCalibrated = 0
            f.calibration = s16(yDiff)
        }
        var dist = f.calibration
        var i = 0
        while (true) {
            if (dist - (speed shr 1) < 0) {
                animTable[i] = dist.toByte()
                break
            }
            dist -= speed shr 1
            animTable[i] = (speed shr 1).toByte()
            if (speed < 16) speed++
            i++
        }
        f.frame = i
        f.distance = f.calibration
    }

    /**
     * Ported from src/bm.c: CamMove_OnLoop
     *
     * One step of the move: sets the camera on the line from `from` to `to` according to the
     * remaining distance; at frame 0 sets `to` to the camera and ends [proc].
     */
    public fun onLoop(proc: Proc) {
        val f = fields
        if (f.frame == 0) {
            f.toX = bm.cameraX
            f.toY = bm.cameraY
            engine.end(proc)
            return
        }
        f.distance = s16(f.distance - animTable[f.frame--])
        bm.cameraX = s16(f.toX + (f.fromX - f.toX) * f.distance / f.calibration)
        bm.cameraY = s16(f.toY + (f.fromY - f.toY) * f.distance / f.calibration)
    }

    private fun begin(parent: Proc?, toX: Int, toY: Int) {
        if (parent != null) {
            engine.startBlocking(script, parent)
        } else {
            engine.start(script, PROC_TREE_3)
        }
        val f = fields
        f.fromX = bm.cameraX
        f.fromY = bm.cameraY
        f.toX = toX
        f.toY = toY
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
        val xTarget = Camera.getCameraAdjustedX(bm, x * 16)
        val yTarget = Camera.getCameraAdjustedY(bm, y * 16)
        if (xTarget == bm.cameraX && yTarget == bm.cameraY) return false
        if (engine.find(script) != null) return false
        begin(parent, xTarget, yTarget)
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
        val xTarget = Camera.storeAdjustedCameraPositionX(x, mapSizeX) * 16
        val yTarget = Camera.storeAdjustedCameraPositionY(y, mapSizeY) * 16
        if (xTarget == bm.cameraX && yTarget == bm.cameraY) return false
        if (engine.find(script) != null) return false
        begin(parent, xTarget, yTarget)
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

    /** Constants. */
    public companion object {
        /** `PROC_TREE_3`: the root tree index the unparented CamMove starts in. */
        public const val PROC_TREE_3: Int = 3

        /** Size of `sCameraAnimTable`. */
        public const val ANIM_TABLE_SIZE: Int = 0x100
    }
}
