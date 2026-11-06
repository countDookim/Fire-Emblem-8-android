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

    private val lut: Array<SpriteObject> by lazy {
        Array(LUT_SIZE) { rom.objN(rom.u32(LUT_ADDRESS + 4 * it)) }
    }
    private val stretched: SpriteObject by lazy { rom.objN(STRETCHED_ADDRESS) }

    /** `sLastTimeMapCursorDrawn`. */
    public var lastTimeDrawn: Int = 0

    /** `sLastCoordMapCursorDrawn.x`. */
    public var lastCoordX: Int = 0

    /** `sLastCoordMapCursorDrawn.y`. */
    public var lastCoordY: Int = 0

    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /**
     * Puts the map cursor at pixel ([x], [y]) (map space) with sprite [kind].
     *
     * Ported from src/bm.c: PutMapCursor
     *
     * Kinds 0 and 1 (plain), 2 (smoothed with the previous frame's position), 3 (stretched) and
     * 4 (frame 0, oam2 0x24) are all ported; kinds 1 to 4 are not observed in the ground-truth
     * traces yet. Any other kind puts nothing (the original would pass a null sprite).
     */
    public fun put(x: Int, y: Int, kind: Int) {
        var px = x
        var py = y
        val clock = now()
        val frame = (clock / 2) % 16
        val oam2: Int
        val sprite: SpriteObject
        when (kind) {
            0, 1 -> {
                oam2 = 2
                sprite = lut[frame]
            }
            2 -> {
                if (clock - 1 == lastTimeDrawn) {
                    px = (px + lastCoordX) shr 1
                    py = (py + lastCoordY) shr 1
                }
                oam2 = 0x24
                sprite = lut[frame]
                lastCoordX = px
                lastCoordY = py
                lastTimeDrawn = clock
            }
            3 -> {
                oam2 = 2
                sprite = stretched
            }
            4 -> {
                oam2 = 0x24
                sprite = lut[0]
            }
            else -> return
        }
        sprites.put(4, px - bm.cameraX, py - bm.cameraY, sprite, oam2)
    }

    private companion object {
        /** `sMapCursorSpriteLut` (fireemblem8.map). */
        const val LUT_ADDRESS = 0x0859A4C0
        const val LUT_SIZE = 16

        /** `sSprite_MapCursorStretched` (fireemblem8.map). */
        const val STRETCHED_ADDRESS = 0x0859A4A6
    }
}
