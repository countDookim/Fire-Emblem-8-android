package fe8.game.ui

import fe8.game.RomView
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The pointing-hand cursor of the menus (decomp `src/uiutils.c`), shown next to the selected
 * entry of the command menus (`src/uimenu.c`), the shop and trade screens, the stat screen, the
 * unit list and the config screen. Holds the hand's sprite and the position and clock frame of its
 * previous display: the hand glides to a new target by moving half the distance each frame, and
 * bobs sideways while it waits.
 */
public class UiHand(rom: RomView, private val sprites: SpriteLayers) {
    private val handSprite: SpriteObject = rom.objN(SPRITE_HAND)
    private val hOffsets = IntArray(H_OFFSET_COUNT) { rom.u8(H_OFFSET_LOOKUP + it) }

    /** `sPrevHandScreenPosition.x`: where the hand was drawn last (after smoothing), in pixels. */
    public var prevX: Int = 0

    /** `sPrevHandScreenPosition.y`: where the hand was drawn last (after smoothing), in pixels. */
    public var prevY: Int = 0

    /**
     * `sPrevHandClockFrame`: the game clock frame of the last display; smoothing applies only when
     * the hand was also shown on the frame before, so a hand that reappears jumps to its target.
     */
    public var prevClockFrame: Int = 0

    /**
     * Puts the hand at ([x], [y]) on sprite layer 2 (`PutSprite(2, ...)`): halfway from the
     * previous position when it was shown on the previous [clock] frame (the decomp reads
     * `GetGameClock()` itself; here it is passed in), then moved by the bobbing offset minus 14
     * pixels, so the hand sits left of the target. The hand is one 16x16 sprite object.
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
        /** ROM address of `sSprite_Hand`: a sprite with one 16x16 object, tile 0. */
        const val SPRITE_HAND = 0x085B6490

        /**
         * ROM address of `sHandHOffsetLookup` (u8 entries): the hand's x bob, 0 to 4 pixels and
         * back over 32 frames.
         */
        const val H_OFFSET_LOOKUP = 0x085B6498

        /** `ARRAY_COUNT(sHandHOffsetLookup)`: frames in one bob cycle. */
        const val H_OFFSET_COUNT = 0x20
    }
}
