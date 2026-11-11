package fe8.game.bm

/**
 * The path arrow's data of src/bmpatharrowdisp.c: `gPathArrowProc` (`struct PathArrowProc`,
 * 0x0203A990) without its 0x29-byte proc header and the drawing (`DrawPathArrow`, PP-6). The
 * 0x43 bytes after the header are one buffer, [buffer], so the C's reach past an array end (the
 * `pathCosts[-1]` write of `CutOffPathLength(0)` lands in `pathY[19]`) is kept.
 *
 * Reads the terrain and the working map of [map], the active unit [active] and the cost table
 * [movementMap] last set. [Hooks.generateBestMovementScript] is a stand-in until PP-8.
 */
public class PathArrow(
    private val map: BmMap,
    private val units: UnitTable,
    private val movementMap: MovementMap,
    private val active: ActiveUnit,
    private val bm: BmSt,
    public val hooks: Hooks = Hooks(),
) {
    /** Stand-ins for what the path arrow calls in other files. */
    public class Hooks {
        /**
         * Stand-in until PP-8 for `GenerateBestMovementScript(x, y, script)` (bmidoten.c): writes
         * the movement script to ([x], [y]) over the working map into the array. Not run in the
         * capture window (the cursor stays in the range, so the path only grows or shrinks).
         */
        public var generateBestMovementScript: (x: Int, y: Int, script: IntArray) -> Unit =
            { _, _, script -> script[0] = Mu.MOVE_CMD_HALT }
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
     * Partially ported from src/bmpatharrowdisp.c: PathArrowDisp_Init
     *
     * Only the data part, mode 0 (the cursor is on the active unit): `maxMov` is the unit's
     * movement less `moveCount`, the path is cut to nothing and starts at the unit, and the cursor
     * is applied. The graphics (the decompression and palette of the first lines) are PP-6.
     * Mode 1 (the cursor off the unit) does nothing here (transcribed from the C: it has no
     * data part).
     */
    public fun init(mode: Int) {
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

    /** `GetBmMapPointAtCursor`: the working map at the cursor as an s8. */
    private fun workingPointAtCursor(): Int =
        checkNotNull(map.workingMap)[bm.playerCursorY, bm.playerCursorX].toByte().toInt()

    private fun abs(n: Int): Int = if (n >= 0) n else -n

    private companion object {
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
