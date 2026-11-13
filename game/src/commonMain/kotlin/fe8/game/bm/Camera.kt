package fe8.game.bm

/**
 * Where the chapter map's camera (the top-left pixel of the 240x160 view, `gBmSt.camera`) has to
 * be so that a cursor or unit stays on screen, from src/bm.c. The camera scrolls only when the
 * watched point comes within a margin of the screen edge ([MARGIN_LEFT] and the others), and
 * never past the map (`gBmSt.cameraMax`). Positions are pixels unless a function says "map
 * position" (tiles). Results follow the C integer semantics: `int` math is 32-bit, `u16`
 * returns are truncated to 16 bits and `s16` stores sign-truncate. None allocates.
 */
public object Camera {
    /**
     * `CAMERA_MARGIN_LEFT` (bm.h): the camera scrolls left when the cursor is less than 48
     * pixels (3 tiles) from the left screen edge.
     */
    public const val MARGIN_LEFT: Int = 16 * 3

    /**
     * `CAMERA_MARGIN_RIGHT` (bm.h): the camera scrolls right when the cursor is more than 176
     * pixels (11 tiles) right of the camera x, leaving 4 tiles (its own included) to the edge.
     */
    public const val MARGIN_RIGHT: Int = 16 * 11

    /**
     * `CAMERA_MARGIN_TOP` (bm.h): the camera scrolls up when the cursor is less than 32 pixels
     * (2 tiles) from the top screen edge.
     */
    public const val MARGIN_TOP: Int = 16 * 2

    /**
     * `CAMERA_MARGIN_BOTTOM` (bm.h): the camera scrolls down when the cursor is more than 112
     * pixels (7 tiles) below the camera y, leaving 3 tiles (its own included) to the edge.
     */
    public const val MARGIN_BOTTOM: Int = 16 * 7

    /** `DISPLAY_WIDTH` (gba/defines.h): the GBA screen is 240 pixels (15 tiles) wide. */
    public const val DISPLAY_WIDTH: Int = 240

    /** `DISPLAY_HEIGHT` (gba/defines.h): the GBA screen is 160 pixels (10 tiles) high. */
    public const val DISPLAY_HEIGHT: Int = 160

    /** Truncates [value] to a signed 16-bit integer sign-extended to 32 bits. */
    private fun s16(value: Int): Int = value.toShort().toInt()

    /** Truncates [value] to an unsigned 16-bit integer. */
    private fun u16(value: Int): Int = value and 0xFFFF

    /**
     * Ported from src/bm.c: GetCameraAdjustedX
     *
     * The camera x that brings pixel column [x] back inside the horizontal scroll margins with
     * the least scrolling: the current `bm.camera.x` if [x] is already inside them, otherwise
     * the camera that puts [x] exactly on the nearer margin, limited to `[0, bm.cameraMax.x]`.
     * This is where a camera move onto a unit or event target ends up (see
     * [CamMove.ensureCameraOntoPosition]).
     */
    public fun getCameraAdjustedX(bm: BmSt, x: Int): Int {
        var adjustedX = bm.cameraX
        if (bm.cameraX + MARGIN_LEFT > x) {
            adjustedX = if (x - MARGIN_LEFT < 0) 0 else x - MARGIN_LEFT
        }
        if (bm.cameraX + MARGIN_RIGHT < x) {
            adjustedX = if (x - MARGIN_RIGHT > bm.cameraMaxX) bm.cameraMaxX else x - MARGIN_RIGHT
        }
        return u16(adjustedX)
    }

    /**
     * Ported from src/bm.c: GetCameraAdjustedY
     *
     * The camera y that brings pixel row [y] back inside the vertical scroll margins with the
     * least scrolling: the current `bm.camera.y` if [y] is already inside them, otherwise the
     * camera that puts [y] exactly on the nearer margin, limited to `[0, bm.cameraMax.y]`.
     */
    public fun getCameraAdjustedY(bm: BmSt, y: Int): Int {
        var adjustedY = bm.cameraY
        if (bm.cameraY + MARGIN_TOP > y) {
            adjustedY = if (y - MARGIN_TOP < 0) 0 else y - MARGIN_TOP
        }
        if (bm.cameraY + MARGIN_BOTTOM < y) {
            adjustedY = if (y - MARGIN_BOTTOM > bm.cameraMaxY) bm.cameraMaxY else y - MARGIN_BOTTOM
        }
        return u16(adjustedY)
    }

    /**
     * Ported from src/bm.c: GetCameraCenteredX
     *
     * The camera x that puts pixel column [x] in the middle of the screen (`DISPLAY_WIDTH / 2`
     * = 120 pixels left of it), limited to `[0, bm.cameraMax.x]` and rounded down to a whole
     * 16-pixel map tile so the map is not drawn half-shifted.
     */
    public fun getCameraCenteredX(bm: BmSt, x: Int): Int {
        var centeredX = x - DISPLAY_WIDTH / 2
        if (centeredX < 0) centeredX = 0
        if (centeredX > bm.cameraMaxX) centeredX = bm.cameraMaxX
        return u16(centeredX and 0xF.inv())
    }

    /**
     * Ported from src/bm.c: GetCameraCenteredY
     *
     * The camera y that puts pixel row [y] in the middle of the screen (`DISPLAY_HEIGHT / 2`
     * = 80 pixels above it), limited to `[0, bm.cameraMax.y]` and rounded down to a whole
     * 16-pixel map tile.
     */
    public fun getCameraCenteredY(bm: BmSt, y: Int): Int {
        var centeredY = y - DISPLAY_HEIGHT / 2
        if (centeredY < 0) centeredY = 0
        if (centeredY > bm.cameraMaxY) centeredY = bm.cameraMaxY
        return u16(centeredY and 0xF.inv())
    }

    /**
     * Transcribed from src/bm.c: StoreAdjustedCameraPositions (not verified: needs a scripted
     *   camera or unit menu after scrolling, which no trace has). This is the `*xOut` half; the C
     *   function returns both through pointers, split here to avoid allocating.
     *
     * The camera column that frames [xIn] in a screen-sized window of 15 tiles: 7 tiles to the
     * left of it, 7 to the right (a hard-coded 15x10 tile window, not derived from
     * [DISPLAY_WIDTH]), never left of column 0 and never past the map's right edge. Used by the
     * event camera command (`Event26_CameraControl`, src/eventscr.c) when its flag byte is
     * nonzero, through `sub_8015D84` and `SetSomeRealCamPos`.
     *
     * Not verified: needs a run with a scripted camera move (sub_8015D84 / SetSomeRealCamPos,
     * e.g. an event camera command); no reference run reaches it, so the CamMove start that
     * uses it ([CamMove.startCameraOntoPositionCentred]) is untagged too.
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
     * The camera row that frames [yIn] in a 10-tile-high window: 5 tiles above it, 4 below,
     * never above row 0 and never past the map's bottom edge.
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
     * Puts the player cursor on map tile position ([x], [y]) and snaps its target and display
     * positions (pixels, `s16`, 16 per tile) to it, so the cursor jumps there instead of gliding
     * (the glide is the display position catching up with `cursorTarget`). Called when a phase
     * starts, by event camera commands, and after the camera is moved onto a unit.
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
     * True (C `1`) if the camera would have to move to show map tile position ([x], [y]), that
     * is, if [getCameraAdjustedX]/[getCameraAdjustedY] differ from the camera now.
     *
     * Not verified: callers are the player action menu (bmmenu.c) and item use (bmitemuse.c);
     * a run that opens a unit's menu after the camera has scrolled would reach it.
     */
    public fun isCameraNotWatchingPosition(bm: BmSt, x: Int, y: Int): Boolean {
        val targetCameraX = getCameraAdjustedX(bm, x * 16)
        val targetCameraY = getCameraAdjustedY(bm, y * 16)
        return !(targetCameraX == bm.cameraX && targetCameraY == bm.cameraY)
    }
}
