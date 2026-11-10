package fe8.game.bm

import fe8.game.RomView
import gbafe.core.time.GameClock
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The map cursor sprite of src/bm.c (`PutMapCursor`), putting it on layer 4 of [sprites].
 *
 * The sprite objects (`sMapCursorSpriteLut`, `sSprite_MapCursorStretched`) are read from [rom]
 * on first use.
 */
public class MapCursor(
    private val rom: RomView,
    private val bm: BmSt,
    private val sprites: SpriteLayers,
) {
    /** `GetGameClock` when [clockSource] is not set. */
    public var gameClock: () -> Int = { 0 }

    /** The game clock itself; when set it is read instead of [gameClock] (no boxing). */
    public var clockSource: GameClock? = null

    /** Table of 16 animated cursor sprite objects (`sMapCursorSpriteLut`). */
    private val cursorAnimationLut: Array<SpriteObject> by lazy {
        Array(LUT_SIZE) { rom.objN(rom.u32(LUT_ADDRESS + 4 * it)) }
    }

    /** Stretched map cursor sprite object (`sSprite_MapCursorStretched`). */
    private val stretchedCursorSprite: SpriteObject by lazy { rom.objN(STRETCHED_ADDRESS) }

    /** Frame timestamp when the map cursor was last rendered (`sLastTimeMapCursorDrawn`). */
    public var lastTimeDrawn: Int = 0

    /** Last rendered cursor horizontal pixel position (`sLastCoordMapCursorDrawn.x`). */
    public var lastCoordX: Int = 0

    /** Last rendered cursor vertical pixel position (`sLastCoordMapCursorDrawn.y`). */
    public var lastCoordY: Int = 0

    /** Returns the current frame clock value from [clockSource] or [gameClock]. */
    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /**
     * Puts the map cursor at pixel ([x], [y]) (map space) with sprite [kind].
     *
     * Ported from src/bm.c: PutMapCursor
     *
     * Kinds 0 and 1 (plain), 2 (smoothed with the previous frame's position), 3 (stretched) and
     * 4 (frame 0, oam2 0x24) are all ported; kinds 1 to 4 are not observed in the ground-truth
     * traces yet. Any other kind puts nothing (the original would pass a null sprite).
     *
     * @param x horizontal pixel position on the map.
     * @param y vertical pixel position on the map.
     * @param kind cursor visual variant (0/1: normal, 2: smoothed motion, 3: stretched, 4: static).
     */
    public fun put(x: Int, y: Int, kind: Int) {
        var pixelX = x
        var pixelY = y
        val clock = now()
        val animFrame = (clock / 2) % LUT_SIZE
        val oam2Priority: Int
        val cursorSprite: SpriteObject
        when (kind) {
            0, 1 -> {
                oam2Priority = 2
                cursorSprite = cursorAnimationLut[animFrame]
            }
            2 -> {
                if (clock - 1 == lastTimeDrawn) {
                    pixelX = (pixelX + lastCoordX) shr 1
                    pixelY = (pixelY + lastCoordY) shr 1
                }
                oam2Priority = 0x24
                cursorSprite = cursorAnimationLut[animFrame]
                lastCoordX = pixelX
                lastCoordY = pixelY
                lastTimeDrawn = clock
            }
            3 -> {
                oam2Priority = 2
                cursorSprite = stretchedCursorSprite
            }
            4 -> {
                oam2Priority = 0x24
                cursorSprite = cursorAnimationLut[0]
            }
            else -> return
        }
        sprites.put(4, pixelX - bm.cameraX, pixelY - bm.cameraY, cursorSprite, oam2Priority)
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
