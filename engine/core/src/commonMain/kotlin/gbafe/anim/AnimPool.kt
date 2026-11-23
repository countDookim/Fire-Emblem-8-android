package gbafe.anim

/**
 * One `struct Anim` of the battle-animation driver (anime.h), 0x48 bytes on the GBA. Only the
 * fields `AnimCreate` and `AnimInsert` write or read are modelled so far; pointers into ROM or
 * RAM are plain GBA addresses and the list links are references into the same [AnimPool].
 */
public class Anim {
    /** `state` (offset 0x00): 0 is a free slot, [AnimPool.STATE_ENABLED] a live one. */
    public var state: Int = 0

    /** `timer` (0x06). */
    public var timer: Int = 0

    /** `oam2Base` (0x08). */
    public var oam2Base: Int = 0

    /** `drawLayerPriority` (0x0A): the sort key of the list. */
    public var drawLayerPriority: Int = 0

    /** `state2` (0x0C). */
    public var state2: Int = 0

    /** `state3` (0x10). */
    public var state3: Int = 0

    /** `commandQueueSize` (0x14). */
    public var commandQueueSize: Int = 0

    /** `oamBase` (0x1C). */
    public var oamBase: Int = 0

    /** `pScrCurrent` (0x20), address of the animation script. */
    public var pScrCurrent: Int = 0

    /** `pScrStart` (0x24). */
    public var pScrStart: Int = 0

    /** `pImgSheetBuf` (0x2C). */
    public var pImgSheetBuf: Int = 0

    /** `pSpriteDataPool` (0x30). */
    public var pSpriteDataPool: Int = 0

    /** `pPrev` (0x34), previous entry of the priority list. */
    public var pPrev: Anim? = null

    /** `pNext` (0x38), next entry of the priority list. */
    public var pNext: Anim? = null

    /** `pUnk40` (0x40). */
    public var pUnk40: Int = 0

    /** `pUnk44` (0x44). */
    public var pUnk44: Int = 0
}

/**
 * The `sAnimPool[ANIM_MAX_COUNT]` array and `sFirstAnim` head of animedrv.c. Slots are indexed in
 * pool order, so slot `i` is at `0x02028F78 + 0x48 * i` on the GBA.
 */
public class AnimPool {
    /** `sAnimPool`. */
    public val slots: List<Anim> = List(ANIM_MAX_COUNT) { Anim() }

    /** `sFirstAnim`. */
    public var first: Anim? = null

    /**
     * Ported from src/animedrv.c: AnimCreate
     *
     * Takes the first free slot (state 0), resets it and links it into the priority list.
     * Returns null when all [ANIM_MAX_COUNT] slots are in use. Fields the original leaves alone
     * (position, `nextRoundId`, `currentRoundType`, `pSpriteData`) are not touched here either.
     */
    public fun create(frameData: Int, displayPriority: Int): Anim? {
        val anim = slots.firstOrNull { it.state == 0 } ?: return null

        anim.state = STATE_ENABLED
        anim.pScrCurrent = frameData
        anim.pScrStart = frameData
        anim.timer = 0
        anim.oam2Base = 0
        anim.drawLayerPriority = displayPriority and 0xFFFF
        anim.state2 = 0
        anim.state3 = 0
        anim.oamBase = 0
        anim.commandQueueSize = 0
        anim.pImgSheetBuf = 0
        anim.pSpriteDataPool = 0
        anim.pUnk40 = 0
        anim.pUnk44 = 0

        insert(anim)
        return anim
    }

    /**
     * Ported from src/animedrv.c: AnimInsert
     *
     * Inserts [anim] before the first entry of strictly lower priority, else at the tail.
     */
    internal fun insert(anim: Anim) {
        var it = first
        if (it == null) {
            first = anim
            return
        }
        while (true) {
            if (anim.drawLayerPriority > it!!.drawLayerPriority) {
                val prev = it.pPrev
                anim.pPrev = prev
                anim.pNext = it
                it.pPrev = anim
                if (anim.pPrev == null) first = anim else prev!!.pNext = anim
                break
            }
            if (it.pNext == null) {
                anim.pNext = it.pNext
                anim.pPrev = it
                it.pNext = anim
                return
            }
            it = it.pNext
        }
    }

    public companion object {
        /** `ANIM_MAX_COUNT`. */
        public const val ANIM_MAX_COUNT: Int = 50

        /** `ANIM_BIT_ENABLED`. */
        public const val STATE_ENABLED: Int = 1
    }
}
