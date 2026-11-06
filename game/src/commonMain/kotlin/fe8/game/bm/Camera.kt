package fe8.game.bm

/**
 * Battle map camera and cursor placement helpers over [BmSt]. Positions are pixels unless a
 * function says "map position" (tiles). Results follow the C integer semantics: `int` math is
 * 32-bit, `u16` returns are truncated to 16 bits and `s16` stores sign-truncate. None allocates.
 */
public object Camera {
    /** `CAMERA_MARGIN_LEFT` (bm.h): pixels the cursor may be from the left screen edge. */
    public const val MARGIN_LEFT: Int = 16 * 3

    /** `CAMERA_MARGIN_RIGHT` (bm.h): offset from the camera x of the right scroll margin. */
    public const val MARGIN_RIGHT: Int = 16 * 11

    /** `CAMERA_MARGIN_TOP` (bm.h): pixels the cursor may be from the top screen edge. */
    public const val MARGIN_TOP: Int = 16 * 2

    /** `CAMERA_MARGIN_BOTTOM` (bm.h): offset from the camera y of the bottom scroll margin. */
    public const val MARGIN_BOTTOM: Int = 16 * 7

    /** `DISPLAY_WIDTH` (gba/defines.h). */
    public const val DISPLAY_WIDTH: Int = 240

    /** `DISPLAY_HEIGHT` (gba/defines.h). */
    public const val DISPLAY_HEIGHT: Int = 160

    private fun s16(v: Int): Int = v.toShort().toInt()

    private fun u16(v: Int): Int = v and 0xFFFF

    /**
     * Ported from src/bm.c: GetCameraAdjustedX
     *
     * The camera x (`u16`) that keeps pixel column [x] inside the scroll margins, starting from
     * `bm.camera.x` and limited to `[0, bm.cameraMax.x]`.
     */
    public fun getCameraAdjustedX(bm: BmSt, x: Int): Int {
        var result = bm.cameraX
        if (bm.cameraX + MARGIN_LEFT > x) {
            result = if (x - MARGIN_LEFT < 0) 0 else x - MARGIN_LEFT
        }
        if (bm.cameraX + MARGIN_RIGHT < x) {
            result = if (x - MARGIN_RIGHT > bm.cameraMaxX) bm.cameraMaxX else x - MARGIN_RIGHT
        }
        return u16(result)
    }

    /**
     * Ported from src/bm.c: GetCameraAdjustedY
     *
     * The camera y (`u16`) that keeps pixel row [y] inside the scroll margins, starting from
     * `bm.camera.y` and limited to `[0, bm.cameraMax.y]`.
     */
    public fun getCameraAdjustedY(bm: BmSt, y: Int): Int {
        var result = bm.cameraY
        if (bm.cameraY + MARGIN_TOP > y) {
            result = if (y - MARGIN_TOP < 0) 0 else y - MARGIN_TOP
        }
        if (bm.cameraY + MARGIN_BOTTOM < y) {
            result = if (y - MARGIN_BOTTOM > bm.cameraMaxY) bm.cameraMaxY else y - MARGIN_BOTTOM
        }
        return u16(result)
    }

    /**
     * Ported from src/bm.c: GetCameraCenteredX
     *
     * The camera x (`u16`) centring pixel column [x], clamped to `[0, bm.cameraMax.x]` and
     * rounded down to a multiple of 16.
     */
    public fun getCameraCenteredX(bm: BmSt, x: Int): Int {
        var result = x - DISPLAY_WIDTH / 2
        if (result < 0) result = 0
        if (result > bm.cameraMaxX) result = bm.cameraMaxX
        return u16(result and 0xF.inv())
    }

    /**
     * Ported from src/bm.c: GetCameraCenteredY
     *
     * The camera y (`u16`) centring pixel row [y], clamped to `[0, bm.cameraMax.y]` and rounded
     * down to a multiple of 16.
     */
    public fun getCameraCenteredY(bm: BmSt, y: Int): Int {
        var result = y - DISPLAY_HEIGHT / 2
        if (result < 0) result = 0
        if (result > bm.cameraMaxY) result = bm.cameraMaxY
        return u16(result and 0xF.inv())
    }

    /**
     * Transcribed from src/bm.c: StoreAdjustedCameraPositions (not verified: needs a scripted
     *   camera or unit menu after scrolling, which no trace has). This is the `*xOut` half; the C
     *   function returns both through pointers, split here to avoid allocating.
     *
     * Not verified: needs a run with a scripted camera move (sub_8015D84 / SetSomeRealCamPos,
     * e.g. an event camera command); no reference run reaches it, so the CamMove start that
     * uses it ([CamMove.startCameraOntoPositionCentred]) is untagged too (M5-46 checked).
     *
     * @param xIn watched map column (tiles).
     * @param mapSizeX `gBmMapSize.x` (s16, tiles).
     * @return the camera column in tiles.
     */
    public fun storeAdjustedCameraPositionX(xIn: Int, mapSizeX: Int): Int {
        var xOut = xIn - 7
        if (xOut < 0) xOut = 0
        if (xOut + 8 > mapSizeX - 1) xOut = mapSizeX - 0xF
        return xOut
    }

    /**
     * Transcribed from src/bm.c: StoreAdjustedCameraPositions (not verified: needs a scripted
     *   camera or unit menu after scrolling, which no trace has). This is the `*yOut` half, see
     *   [storeAdjustedCameraPositionX]. Not verified, same reason.
     *
     * @param yIn watched map row (tiles).
     * @param mapSizeY `gBmMapSize.y` (s16, tiles).
     * @return the camera row in tiles.
     */
    public fun storeAdjustedCameraPositionY(yIn: Int, mapSizeY: Int): Int {
        var yOut = yIn - 5
        if (yOut < 0) yOut = 0
        if (yOut + 4 > mapSizeY - 1) yOut = mapSizeY - 10
        return yOut
    }

    /**
     * Ported from src/bm.c: SetCursorMapPosition
     *
     * Puts the player cursor on map position ([x], [y]) and snaps its target and display
     * positions (pixels, `s16`) to it.
     */
    public fun setCursorMapPosition(bm: BmSt, x: Int, y: Int) {
        bm.playerCursorX = s16(x)
        bm.playerCursorY = s16(y)
        bm.cursorTargetX = s16(x * 16)
        bm.cursorTargetY = s16(y * 16)
        bm.playerCursorDisplayX = s16(x * 16)
        bm.playerCursorDisplayY = s16(y * 16)
    }

    /**
     * Transcribed from src/bm.c: IsCameraNotWatchingPosition (not verified: needs a scripted camera
     *   or unit menu after scrolling, which no trace has)
     *
     * True (C `1`) if the camera would have to move to show map position ([x], [y]).
     *
     * Not verified: callers are the player action menu (bmmenu.c) and item use (bmitemuse.c);
     * a run that opens a unit's menu after the camera has scrolled would reach it.
     */
    public fun isCameraNotWatchingPosition(bm: BmSt, x: Int, y: Int): Boolean {
        val xTarget = getCameraAdjustedX(bm, x * 16)
        val yTarget = getCameraAdjustedY(bm, y * 16)
        return !(xTarget == bm.cameraX && yTarget == bm.cameraY)
    }
}
