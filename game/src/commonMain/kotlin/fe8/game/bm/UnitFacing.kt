package fe8.game.bm

/**
 * The active unit's facing of src/unit_facing.c: [changeActiveUnitFacing] turns the map sprite
 * of `gActiveUnit` towards a tile (for example towards the target of an item or staff use) by
 * giving the first MU a one-command script, a face command and a halt.
 *
 * @param units the unit table (the active unit's position)
 * @param active `gActiveUnit`
 * @param script `gWorkingMovementScript` (the one [PathArrow] fills)
 */
public class UnitFacing(
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val script: IntArray,
) {
    /**
     * `SetAutoMuMoveScript(script)` ([Mu.setAutoMuMoveScript], wired by the game): hands the
     * script to the first MU; the C does nothing when there is no MU.
     */
    public var setAutoMuMoveScript: (script: IntArray) -> Unit = {}

    /**
     * Ported from src/unit_facing.c: ChangeActiveUnitFacing
     *
     * Writes the face command (`MOVE_CMD_FACE_BASE` plus the facing towards ([xLook], [yLook]))
     * and `MOVE_CMD_HALT` into the working movement script and hands it to the first MU.
     */
    public fun changeActiveUnitFacing(xLook: Int, yLook: Int) {
        val direction = getFacingDirection(
            units.xPos(active.unit), units.yPos(active.unit), xLook, yLook,
        ) + Mu.MOVE_CMD_FACE_BASE
        script[0] = direction
        script[1] = Mu.MOVE_CMD_HALT
        setAutoMuMoveScript(script)
    }

    /** Facing ids (`FACING_*`, include/types.h; the `MU_FACING_*` ids are the same). */
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
         * The `FACING_*` side of the tile ([xFrom], [yFrom]) that ([xTo], [yTo]) lies on: up or
         * down when it is more than twice as far vertically as horizontally, else left or right
         * (left when the two tiles are the same).
         */
        public fun getFacingDirection(xFrom: Int, yFrom: Int, xTo: Int, yTo: Int): Int {
            if (Math.abs(xTo - xFrom) * 2 < Math.abs(yTo - yFrom)) {
                return if (yFrom < yTo) FACING_DOWN else FACING_UP
            }
            return if (xFrom < xTo) FACING_RIGHT else FACING_LEFT
        }
    }
}
