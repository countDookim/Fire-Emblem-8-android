package fe8.game.bm

import fe8.game.RomView
import gbafe.core.time.GameClock
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The map cursor sprite of src/bm.c (`PutMapCursor`), putting it on layer 4 of [sprites]. The
 * cursor is four 8x8 corner pieces around a map tile that pulse in and out: 2 pixels outside
 * the tile for 10 of 16 animation frames, then 1 pixel, 0 pixels (4 frames) and 1 pixel again.
 * Each animation frame lasts 2 game clock ticks.
 *
 * The sprite objects (`sMapCursorSpriteLut`, `sSprite_MapCursorStretched`) are read from [rom]
 * on first use. The cursor has to be put again every frame it should show.
 */
public class MapCursor(
    private val rom: RomView,
    private val bm: BmSt,
    private val sprites: SpriteLayers,
) {
    /** `GetGameClock` (frames since boot) when [clockSource] is not set. */
    public var gameClock: () -> Int = { 0 }

    /** The game clock itself; when set it is read instead of [gameClock] (no boxing). */
    public var clockSource: GameClock? = null

    /**
     * `sMapCursorSpriteLut`: the 16 animation frames, pointing at three distinct sprites
     * (`sSprite_MapCursorA`, `B`, `C`: corners 2, 1 and 0 pixels outside the tile).
     */
    private val cursorAnimationLut: Array<SpriteObject> by lazy {
        Array(LUT_SIZE) { rom.objN(rom.u32(LUT_ADDRESS + 4 * it)) }
    }

    /**
     * `sSprite_MapCursorStretched`: the cursor drawn with its corner pieces spread wider than
     * the normal one (kind 3, used while the unit sprite hover is on; it does not animate).
     */
    private val stretchedCursorSprite: SpriteObject by lazy { rom.objN(STRETCHED_ADDRESS) }

    /**
     * Game clock value when a kind 2 cursor was last put (`sLastTimeMapCursorDrawn`); kind 2
     * smooths only if that was the previous frame.
     */
    public var lastTimeDrawn: Int = 0

    /** Map pixel x of the last kind 2 cursor (`sLastCoordMapCursorDrawn.x`). */
    public var lastCoordX: Int = 0

    /** Map pixel y of the last kind 2 cursor (`sLastCoordMapCursorDrawn.y`). */
    public var lastCoordY: Int = 0

    /** The game clock: [clockSource] if set, else [gameClock]. */
    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /**
     * Puts the map cursor at pixel ([x], [y]) (map space, the camera is subtracted here) with
     * sprite [kind].
     *
     * Ported from src/bm.c: PutMapCursor
     *
     * Kinds 0 and 1 (plain, animated), 2 (smoothed: it is drawn halfway between the given
     * position and the one of the previous frame, so it glides), 3 (stretched, not animated)
     * and 4 (animation frame 0 only, so it does not pulse) are all ported. In the decomp the
     * idle player phase cursor is kind 3 when the cursor tile has unit sprite hover enabled
     * and kind 0 otherwise (`PlayerPhase_MainIdle`), the cursor while a unit's movement range
     * is shown is kind 1 (`PlayerPhase_RangeDisplayIdle`), and target selection uses kind 2
     * (while it is not scrolling the camera) and kind 4 (while it is frozen). Kind 2 is
     * observed in the target selection (`TargetSelectionDifferentialTest`), kinds 1, 3 and 4 are
     * not observed in the ground-truth traces yet. Any other kind puts nothing (the original
     * would pass a null sprite).
     *
     * @param x horizontal pixel position on the map.
     * @param y vertical pixel position on the map.
     * @param kind cursor variant (0/1: pulsing, 2: pulsing with smoothed motion, 3: stretched,
     *   4: not pulsing). Kinds 2 and 4 pass `oam2` 0x24 and the others 2: the value goes into
     *   the OAM attribute 2 of the sprite's pieces as a tile offset; what the tiles at 2 and
     *   0x24 hold is unknown here.
     */
    public fun put(x: Int, y: Int, kind: Int) {
        var pixelX = x
        var pixelY = y
        val clock = now()
        val animFrame = (clock / 2) % LUT_SIZE
        val oam2: Int
        val cursorSprite: SpriteObject
        when (kind) {
            0, 1 -> {
                oam2 = 2
                cursorSprite = cursorAnimationLut[animFrame]
            }
            2 -> {
                if (clock - 1 == lastTimeDrawn) {
                    pixelX = (pixelX + lastCoordX) shr 1
                    pixelY = (pixelY + lastCoordY) shr 1
                }
                oam2 = 0x24
                cursorSprite = cursorAnimationLut[animFrame]
                lastCoordX = pixelX
                lastCoordY = pixelY
                lastTimeDrawn = clock
            }
            3 -> {
                oam2 = 2
                cursorSprite = stretchedCursorSprite
            }
            4 -> {
                oam2 = 0x24
                cursorSprite = cursorAnimationLut[0]
            }
            else -> return
        }
        sprites.put(4, pixelX - bm.cameraX, pixelY - bm.cameraY, cursorSprite, oam2)
    }

    private companion object {
        /** GBA address of `sMapCursorSpriteLut` in ROM (fireemblem8.map). */
        const val LUT_ADDRESS = 0x0859A4C0

        /** Number of animation frames in the cursor LUT (16 frames). */
        const val LUT_SIZE = 16

        /** GBA address of `sSprite_MapCursorStretched` in ROM (fireemblem8.map). */
        const val STRETCHED_ADDRESS = 0x0859A4A6
    }
}
