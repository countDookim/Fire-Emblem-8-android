package fe8.game.ui

import fe8.game.RomView
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The menu cursor hand (decomp `src/uiutils.c`): its sprite and the position and clock frame of
 * the previous display, which smooth the hand's movement.
 */
public class UiHand(rom: RomView, private val sprites: SpriteLayers) {
    private val handSprite: SpriteObject = rom.objN(SPRITE_HAND)
    private val hOffsets = IntArray(H_OFFSET_COUNT) { rom.u8(H_OFFSET_LOOKUP + it) }

    /** `sPrevHandScreenPosition.x`. */
    public var prevX: Int = 0

    /** `sPrevHandScreenPosition.y`. */
    public var prevY: Int = 0

    /** `sPrevHandClockFrame`. */
    public var prevClockFrame: Int = 0

    /**
     * Puts the hand at ([x], [y]) on sprite layer 2: halfway from the previous position when it
     * was shown on the previous [clock] frame, plus a bobbing x offset.
     *
     * Ported from src/uiutils.c: DisplayUiHand
     */
    public fun displayUiHand(x: Int, y: Int, clock: Int) {
        var handX = x
        var handY = y
        if (clock - 1 == prevClockFrame) {
            handX = (handX + prevX) shr 1
            handY = (handY + prevY) shr 1
        }

        prevX = handX
        prevY = handY
        prevClockFrame = clock

        handX += hOffsets[clock % H_OFFSET_COUNT] - 14
        sprites.put(2, handX, handY, handSprite, 0)
    }

    private companion object {
        /** ROM address of `sSprite_Hand`. */
        const val SPRITE_HAND = 0x085B6490

        /** ROM address of `sHandHOffsetLookup` (u8 entries). */
        const val H_OFFSET_LOOKUP = 0x085B6498

        /** `ARRAY_COUNT(sHandHOffsetLookup)`. */
        const val H_OFFSET_COUNT = 0x20
    }
}
