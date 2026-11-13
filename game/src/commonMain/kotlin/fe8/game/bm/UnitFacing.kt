package fe8.game.bm

/**
 * The active unit's facing of src/unit_facing.c: [changeActiveUnitFacing] turns the map sprite
 * of `gActiveUnit` towards a tile by giving the first MU a one-command script.
 *
 * [script] is `gWorkingMovementScript` (the one [PathArrow] fills); [setAutoMuMoveScript] is
 * `SetAutoMuMoveScript` ([Mu.setAutoMuMoveScript], wired by the game).
 */
public class UnitFacing(
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val script: IntArray,
) {
    /** `SetAutoMuMoveScript(script)`. */
    public var setAutoMuMoveScript: (script: IntArray) -> Unit = {}

    /**
     * Ported from src/unit_facing.c: ChangeActiveUnitFacing
     *
     * Writes the face command towards ([xLook], [yLook]) and a HALT into the working movement
     * script and hands it to the first MU.
     */
    public fun changeActiveUnitFacing(xLook: Int, yLook: Int) {
        val direction = getFacingDirection(
            units.xPos(active.unit), units.yPos(active.unit), xLook, yLook,
        ) + Mu.MOVE_CMD_FACE_BASE
        script[0] = direction
        script[1] = Mu.MOVE_CMD_HALT
        setAutoMuMoveScript(script)
    }

    /** Facing ids of `include/types.h`. */
    public companion object {
        /** `FACING_LEFT`. */
        public const val FACING_LEFT: Int = 0

        /** `FACING_RIGHT`. */
        public const val FACING_RIGHT: Int = 1

        /** `FACING_DOWN`. */
        public const val FACING_DOWN: Int = 2

        /** `FACING_UP`. */
        public const val FACING_UP: Int = 3

        /**
         * Ported from src/mapanim_api.c: GetFacingDirection
         *
         * The side of the tile ([xFrom], [yFrom]) that ([xTo], [yTo]) lies on: up or down when
         * it is more than twice as far vertically as horizontally, else left or right.
         */
        public fun getFacingDirection(xFrom: Int, yFrom: Int, xTo: Int, yTo: Int): Int {
            if (Math.abs(xTo - xFrom) * 2 < Math.abs(yTo - yFrom)) {
                return if (yFrom < yTo) FACING_DOWN else FACING_UP
            }
            return if (xFrom < xTo) FACING_RIGHT else FACING_LEFT
        }
    }
}
