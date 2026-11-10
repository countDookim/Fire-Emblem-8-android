package fe8.game.bm

/**
 * Battle map camera and cursor placement helpers over [BmSt]. Positions are pixels unless a
 * function says "map position" (tiles). Results follow the C integer semantics: `int` math is
 * 32-bit, `u16` returns are truncated to 16 bits and `s16` stores sign-truncate. None allocates.
 */
public object Camera {
    /** `CAMERA_MARGIN_LEFT` (bm.h): pixels the cursor may be from the left screen edge (48 pixels / 3 tiles). */
    public const val MARGIN_LEFT: Int = 16 * 3

    /** `CAMERA_MARGIN_RIGHT` (bm.h): offset from the camera x of the right scroll margin (176 pixels / 11 tiles). */
    public const val MARGIN_RIGHT: Int = 16 * 11

    /** `CAMERA_MARGIN_TOP` (bm.h): pixels the cursor may be from the top screen edge (32 pixels / 2 tiles). */
    public const val MARGIN_TOP: Int = 16 * 2

    /** `CAMERA_MARGIN_BOTTOM` (bm.h): offset from the camera y of the bottom scroll margin (112 pixels / 7 tiles). */
    public const val MARGIN_BOTTOM: Int = 16 * 7

    /** `DISPLAY_WIDTH` (gba/defines.h): screen width in pixels (240). */
    public const val DISPLAY_WIDTH: Int = 240

    /** `DISPLAY_HEIGHT` (gba/defines.h): screen height in pixels (160). */
    public const val DISPLAY_HEIGHT: Int = 160

    /** Truncates [value] to a signed 16-bit integer sign-extended to 32 bits. */
    private fun s16(value: Int): Int = value.toShort().toInt()

    /** Truncates [value] to an unsigned 16-bit integer. */
    private fun u16(value: Int): Int = value and 0xFFFF

    /**
     * Ported from src/bm.c: GetCameraAdjustedX
     *
     * Computes the camera x coordinate (`u16`) that keeps pixel column [x] inside the horizontal
     * scroll margins, starting from `bm.camera.x` and clamped to `[0, bm.cameraMax.x]`.
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
     * Computes the camera y coordinate (`u16`) that keeps pixel row [y] inside the vertical
     * scroll margins, starting from `bm.camera.y` and clamped to `[0, bm.cameraMax.y]`.
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
     * Computes the camera horizontal coordinate (`u16`) that centers pixel column [x] on screen,
     * clamped to `[0, bm.cameraMax.x]` and rounded down to a 16-pixel metatile boundary.
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
     * Computes the camera vertical coordinate (`u16`) that centers pixel row [y] on screen,
     * clamped to `[0, bm.cameraMax.y]` and rounded down to a 16-pixel metatile boundary.
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
     * Puts the player cursor on map tile position ([x], [y]) and snaps its target and display
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
     * True (C `1`) if the camera would have to move to show map tile position ([x], [y]).
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
