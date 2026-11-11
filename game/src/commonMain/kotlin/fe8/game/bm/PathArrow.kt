package fe8.game.bm

import fe8.game.RomView
import gbafe.core.rng.RngService
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The path arrow's data of src/bmpatharrowdisp.c: `gPathArrowProc` (`struct PathArrowProc`,
 * 0x0203A990) without its 0x29-byte proc header and the drawing (`DrawPathArrow`, PP-6). The
 * 0x43 bytes after the header are one buffer, [buffer], so the C's reach past an array end (the
 * `pathCosts[-1]` write of `CutOffPathLength(0)` lands in `pathY[19]`) is kept.
 *
 * Reads the terrain and the working map of [map], the active unit [active] and the cost table
 * [movementMap] last set. [Hooks.generateBestMovementScript] is
 * [fe8.game.bm.generateBestMovementScript] on the working map when an [rng] is given.
 */
public class PathArrow(
    private val map: BmMap,
    private val units: UnitTable,
    private val movementMap: MovementMap,
    private val active: ActiveUnit,
    private val bm: BmSt,
    /** Stand-ins this path arrow calls; see [Hooks]. */
    public val hooks: Hooks = Hooks(),
    /** `NextRN_N` for the tie break of `GenerateBestMovementScript`; null keeps the stand-in. */
    rng: RngService? = null,
    /** `sSpriteLayers` that [drawPathArrow] puts the arrow into; null skips the drawing. */
    private val sprites: SpriteLayers? = null,
    /** The VRAM and palette buffer [init] loads the arrow graphics into; null skips that. */
    private val hardware: BgHardware? = null,
    /** The ROM the graphics and the object come from; null skips the graphics. */
    private val rom: RomView? = null,
) {
    /** Stand-ins for what the path arrow calls in other files. */
    public class Hooks {
        /**
         * `GenerateBestMovementScript(x, y, script)` (bmidoten.c): writes the movement script to
         * ([x], [y]) over the working map into the array. Replaced by the port when the
         * [PathArrow] has an rng; the default (HALT) is a stand-in. Not run in the capture window
         * (the cursor stays in the range, so the path only grows or shrinks).
         */
        public var generateBestMovementScript: (x: Int, y: Int, script: IntArray) -> Unit =
            { _, _, script -> script[0] = Mu.MOVE_CMD_HALT }
    }

    init {
        if (rng != null) {
            hooks.generateBestMovementScript = { x, y, script ->
                generateBestMovementScript(
                    checkNotNull(map.workingMap), map.sizeX, map.sizeY, rng, x, y, script,
                )
            }
        }
    }

    /** `gPathArrowProc` from 0x29: lastX, lastY, maxMov, pathLen, pathX, pathY, pathCosts. */
    public val buffer: ByteArray = ByteArray(BUFFER_SIZE)

    /** `gWorkingMovementScript`. */
    public val workingMovementScript: IntArray = IntArray(0x40)

    /** `lastX` (s8). */
    public var lastX: Int
        get() = buffer[OFF_LAST_X].toInt()
        set(v) {
            buffer[OFF_LAST_X] = v.toByte()
        }

    /** `lastY` (s8). */
    public var lastY: Int
        get() = buffer[OFF_LAST_Y].toInt()
        set(v) {
            buffer[OFF_LAST_Y] = v.toByte()
        }

    /** `maxMov` (s8). */
    public var maxMov: Int
        get() = buffer[OFF_MAX_MOV].toInt()
        set(v) {
            buffer[OFF_MAX_MOV] = v.toByte()
        }

    /** `pathLen` (s8): the index of the last point. */
    public var pathLen: Int
        get() = buffer[OFF_PATH_LEN].toInt()
        set(v) {
            buffer[OFF_PATH_LEN] = v.toByte()
        }

    private fun pathX(i: Int): Int = buffer[OFF_PATH_X + i].toInt()

    private fun pathY(i: Int): Int = buffer[OFF_PATH_Y + i].toInt()

    private fun pathCost(i: Int): Int = buffer[OFF_PATH_COSTS + i].toInt()

    private fun setPathCost(i: Int, v: Int) {
        buffer[OFF_PATH_COSTS + i] = v.toByte()
    }

    private fun moveCost(terrain: Int): Int = movementMap.workingMoveCosts[terrain].toInt() and 0xFF

    /**
     * Ported from src/bmpatharrowdisp.c: PathArrowDisp_Init
     *
     * Loads the arrow tiles to OBJ VRAM 0x5E00 and its palette to palette 0x13 (both when a
     * [rom] and [hardware] are given). Then, in mode 0 (the cursor is on the active unit),
     * `maxMov` is the unit's movement less `moveCount`, the path is cut to nothing and starts at
     * the unit, and the cursor is applied. Mode 1 (the cursor off the unit) only loads the
     * graphics (the C has no data part for it; not verified: no capture runs it).
     */
    public fun init(mode: Int) {
        if (rom != null && hardware != null) {
            rom.decompressInto(ARROW_GFX, hardware.vram, OBJ_VRAM_OFFSET + 0x5E00)
            rom.pal(ARROW_PALETTE, 16).copyInto(hardware.palette, 0x13 * 16)
            hardware.enablePaletteSync()
        }
        if (mode != 0) return
        maxMov = units.movement(active.unit) - active.action.moveCount
        cutOffPathLength(0)
        addPointToPathArrowProc(units.xPos(active.unit), units.yPos(active.unit))
        setPathCost(0, maxMov)
        setLastCoords(-1, -1)
        updatePathArrowWithCursor()
    }

    /** Ported from src/bmpatharrowdisp.c: SetLastCoords */
    public fun setLastCoords(x: Int, y: Int) {
        lastX = x
        lastY = y
    }

    /**
     * Ported from src/bmpatharrowdisp.c: CutOffPathLength
     *
     * When the path reaches [newIndex], shortens it to [newIndex] - 1 points past the start and
     * recomputes the costs from `maxMov` along the kept points.
     */
    public fun cutOffPathLength(newIndex: Int) {
        val index = newIndex.toByte().toInt()
        if (pathLen >= index) {
            pathLen = index - 1
            setPathCost(pathLen, maxMov)
            var i = 1
            while (i <= pathLen) {
                setPathCost(i, pathCost(i - 1) - moveCost(map.terrain[pathY(i), pathX(i)]))
                i++
            }
        }
    }

    /**
     * Ported from src/bmpatharrowdisp.c: AddPointToPathArrowProc
     *
     * Appends ([x], [y]) with the cost left after entering its terrain.
     */
    public fun addPointToPathArrowProc(x: Int, y: Int) {
        pathLen = pathLen + 1
        buffer[OFF_PATH_X + pathLen] = x.toByte()
        buffer[OFF_PATH_Y + pathLen] = y.toByte()
        val terrain = map.terrain[y.toByte().toInt(), x.toByte().toInt()]
        setPathCost(pathLen, pathCost(pathLen - 1) - moveCost(terrain))
    }

    /** Ported from src/bmpatharrowdisp.c: GetPointAlongPath */
    public fun getPointAlongPath(x: Int, y: Int): Int {
        for (i in 0..pathLen) {
            if (pathX(i) == x && pathY(i) == y) return i
        }
        return -1
    }

    /**
     * Ported from src/bmpatharrowdisp.c: GetMovementScriptFromPath
     *
     * Writes the direction of each step of the path into [workingMovementScript] and a HALT.
     */
    public fun getMovementScriptFromPath() {
        var i = 1
        while (i <= pathLen) {
            val x = pathX(i - 1)
            workingMovementScript[i - 1] = when {
                pathX(i) < x -> Mu.MOVE_CMD_MOVE_LEFT
                pathX(i) > x -> Mu.MOVE_CMD_MOVE_RIGHT
                pathY(i) < pathY(i - 1) -> Mu.MOVE_CMD_MOVE_UP
                else -> Mu.MOVE_CMD_MOVE_DOWN
            }
            i++
        }
        workingMovementScript[i - 1] = Mu.MOVE_CMD_HALT
    }

    /**
     * Transcribed from src/bmpatharrowdisp.c: GetPathFromMovementScript (not verified: only the
     * re-route branches call it, and the capture's cursor never leaves the path's reach)
     *
     * Appends the points the [workingMovementScript] walks from the path's last point.
     */
    public fun getPathFromMovementScript() {
        var i = 0
        while (i < workingMovementScript.size) {
            val cmd = workingMovementScript[i++] and 0xFF
            val x = pathX(pathLen)
            val y = pathY(pathLen)
            when (cmd) {
                Mu.MOVE_CMD_HALT -> return
                Mu.MOVE_CMD_MOVE_LEFT -> addPointToPathArrowProc(x - 1, y)
                Mu.MOVE_CMD_MOVE_RIGHT -> addPointToPathArrowProc(x + 1, y)
                Mu.MOVE_CMD_MOVE_UP -> addPointToPathArrowProc(x, y - 1)
                Mu.MOVE_CMD_MOVE_DOWN -> addPointToPathArrowProc(x, y + 1)
                else -> Unit // faces and sleep (and END, which a u8 script never holds) skip
            }
        }
    }

    /**
     * Transcribed from src/bmpatharrowdisp.c: GenerateMovementMapForActiveUnit (not verified:
     * only the re-route branches call it)
     *
     * Floods the working map from the path's last point with the cost left there.
     */
    public fun generateMovementMapForActiveUnit() {
        movementMap.generateMovementMapOnWorkingMap(
            active.unit, pathX(pathLen), pathY(pathLen), pathCost(pathLen),
        )
    }

    /**
     * Transcribed from src/bmpatharrowdisp.c: ResetPathArrow (not verified: the capture's cursor
     * never leaves the range or crosses the path, so no re-route runs)
     *
     * Restarts the path at the unit and routes it to the cursor.
     */
    public fun resetPathArrow() {
        cutOffPathLength(1)
        generateMovementMapForActiveUnit()
        hooks.generateBestMovementScript(bm.playerCursorX, bm.playerCursorY, workingMovementScript)
        getPathFromMovementScript()
    }

    /**
     * Transcribed from src/bmpatharrowdisp.c: PathContainsNoCycle (not verified: only the
     * re-route branch calls it)
     */
    public fun pathContainsNoCycle(): Boolean {
        for (i in pathLen downTo 1) {
            for (j in i - 1 downTo 0) {
                if (pathX(i) == pathX(j) && pathY(i) == pathY(j)) return false
            }
        }
        return true
    }

    /**
     * Partially ported from src/bmpatharrowdisp.c: UpdatePathArrowWithCursor
     *
     * Follows the cursor: the path is cut back when the cursor is on it, extended by one point
     * when the cursor is a neighbour of its end within the cost left, and re-routed otherwise
     * (the re-route, from `GenerateMovementMapForActiveUnit` on, is transcribed, not verified:
     * the capture's cursor never needs it).
     */
    public fun updatePathArrowWithCursor() {
        val cx = bm.playerCursorX
        val cy = bm.playerCursorY
        if (lastX == cx && lastY == cy) return
        setLastCoords(cx, cy)
        movementMap.setWorkingBmMap(map.movement)
        if (workingPointAtCursor() == -1) return
        val point = getPointAlongPath(cx, cy)
        if (point != -1) {
            cutOffPathLength(point + 1)
            return
        }
        if (pathCost(pathLen) >= moveCost(map.terrain[cy, cx])) {
            if (abs(pathX(pathLen) - cx) + abs(pathY(pathLen) - cy) == 1) {
                addPointToPathArrowProc(cx, cy)
                return
            }
        }
        if (pathCost(pathLen) == 0) cutOffPathLength(1)
        movementMap.setWorkingBmMap(map.other)
        generateMovementMapForActiveUnit()
        if (workingPointAtCursor() == -1) {
            resetPathArrow()
            return
        }
        hooks.generateBestMovementScript(cx, cy, workingMovementScript)
        getPathFromMovementScript()
        if (!pathContainsNoCycle()) resetPathArrow()
    }

    /**
     * Ported from src/bmpatharrowdisp.c: GetDirectionOfPathBeforeIndex
     *
     * 0 for the first point, else 3, 1, 4 or 2 for the previous point being left of, right of,
     * above or below point [i].
     */
    public fun getDirectionOfPathBeforeIndex(i: Int): Int {
        if (i == 0) return 0
        return when {
            pathX(i - 1) < pathX(i) -> 3
            pathX(i - 1) > pathX(i) -> 1
            pathY(i - 1) < pathY(i) -> 4
            else -> 2
        }
    }

    /**
     * Ported from src/bmpatharrowdisp.c: GetDirectionOfPathAfterIndex
     *
     * 0 for the last point, else 1, 3, 2 or 4 for the next point being right of, left of, below
     * or above point [i].
     */
    public fun getDirectionOfPathAfterIndex(i: Int): Int {
        if (i == pathLen) return 0
        return when {
            pathX(i) < pathX(i + 1) -> 1
            pathX(i) > pathX(i + 1) -> 3
            pathY(i) < pathY(i + 1) -> 2
            else -> 4
        }
    }

    /**
     * Ported from src/bmpatharrowdisp.c: PointInCameraBounds
     *
     * True when the pixel position ([x], [y]) is on the screen widened by [xBound] and [yBound].
     */
    public fun pointInCameraBounds(x: Int, y: Int, xBound: Int, yBound: Int): Boolean =
        y - bm.cameraY > -yBound && y - bm.cameraY <= 0x9F &&
            x - bm.cameraX > -xBound && x - bm.cameraX <= 0xEF

    /**
     * Ported from src/bmpatharrowdisp.c: DrawPathArrow
     *
     * Puts one 16x16 sprite per path point on screen into sprite layer 11, last point first; its
     * tile is picked from the directions after and before the point.
     */
    public fun drawPathArrow() {
        val layers = sprites ?: return
        val obj = checkNotNull(arrowObject)
        if (pathLen == 0) return
        for (i in pathLen downTo 0) {
            val xp = 16 * pathX(i)
            val yp = 16 * pathY(i)
            if (pointInCameraBounds(xp, yp, 16, 16)) {
                val oam2 = OAM_TABLE[getDirectionOfPathAfterIndex(i)][getDirectionOfPathBeforeIndex(i)]
                layers.put(11, xp - bm.cameraX, yp - bm.cameraY, obj, oam2)
            }
        }
    }

    /**
     * Ported from src/bmpatharrowdisp.c: DrawUpdatedPathArrow
     *
     * Applies the cursor to the path ([updatePathArrowWithCursor]), then draws it.
     */
    public fun drawUpdatedPathArrow() {
        updatePathArrowWithCursor()
        drawPathArrow()
    }

    /** `gObject_16x16`, read from the ROM on first use. */
    private val arrowObject: SpriteObject? by lazy { rom?.objN(OBJECT_16X16) }

    /** `GetBmMapPointAtCursor`: the working map at the cursor as an s8. */
    private fun workingPointAtCursor(): Int =
        checkNotNull(map.workingMap)[bm.playerCursorY, bm.playerCursorX].toByte().toInt()

    private fun abs(n: Int): Int = if (n >= 0) n else -n

    private companion object {
        const val OBJECT_16X16 = 0x08590F4C
        const val ARROW_GFX = 0x08A03054
        const val ARROW_PALETTE = 0x08A0328C
        const val OBJ_VRAM_OFFSET = 0x10000

        /** `gPathArrowOAMTable`: oam2 by direction after, direction before. */
        val OAM_TABLE = arrayOf(
            intArrayOf(0x3AF0, 0x3B04, 0x3B06, 0x3B00, 0x3B02),
            intArrayOf(0x3AF0, 0x3AF0, 0x3AF8, 0x3B0C, 0x3AFC),
            intArrayOf(0x3AF2, 0x3AF8, 0x3AF2, 0x3AFA, 0x3B08),
            intArrayOf(0x3AF4, 0x3B0E, 0x3AFA, 0x3AF4, 0x3AFE),
            intArrayOf(0x3AF6, 0x3AFC, 0x3B0A, 0x3AFE, 0x3AF6),
        )
        const val BUFFER_SIZE = 0x43
        const val OFF_LAST_X = 0
        const val OFF_LAST_Y = 1
        const val OFF_MAX_MOV = 2
        const val OFF_PATH_LEN = 3
        const val OFF_PATH_X = 4
        const val OFF_PATH_Y = 0x18
        const val OFF_PATH_COSTS = 0x2C
    }
}
