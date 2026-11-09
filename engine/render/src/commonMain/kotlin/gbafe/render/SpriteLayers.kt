package gbafe.render

/**
 * An OBJ "object" (sprite composition data: a count then 3 halfwords per OAM entry) that sprites
 * point to, with [id] identifying it (for FE8, its ROM address).
 */
public class SpriteObject(
    /** Identifier (the game's ROM address of the data, for tests and debugging). */
    public val id: Int,
    /** The object's halfwords. */
    public val data: ShortArray,
)

/**
 * The per-step sprite lists of `src/ctc.c`: a pool of 0x80 entries (`sSpritePool`) linked into 16
 * layer heads (`sSpriteLayers`); heads 0..12 and 13..15 form two chains, pushed by
 * [pushLayerObjects] from head 0 and head 13.
 */
public class SpriteLayers {
    /** `struct SpriteEntry`. */
    public class Entry {
        /** `next`. */
        public var next: Entry? = null

        /** `oam1` (s16). */
        public var oam1: Int = 0

        /** `oam0` (s16). */
        public var oam0: Int = 0

        /** `oam2` (u16). */
        public var oam2: Int = 0

        /** `object` (null for layer heads). */
        public var obj: SpriteObject? = null
    }

    /** `sSpriteLayers`. */
    public val layers: Array<Entry> = Array(16) { Entry() }

    /** `sSpritePool`. */
    public val pool: Array<Entry> = Array(0x80) { Entry() }

    /** `gSpriteAllocIt`, as an index into [pool]. */
    public var allocIt: Int = 0

    /**
     * Relinks the layer heads (0..12 and 13..15 each a chain), drops their objects and rewinds the
     * pool.
     *
     * Ported from src/ctc.c: ClearSprites
     */
    public fun clear() {
        for (i in 15 downTo 0) {
            layers[i].next = if (i < 15) layers[i + 1] else null
            layers[i].obj = null
        }
        layers[15].next = null
        layers[12].next = null
        allocIt = 0
    }

    /**
     * Adds a sprite to [layer] at ([x], [y]) (masked to 9 / 8 bits).
     *
     * Transcribed from src/ctc.c: PutSprite (not verified: no test checks it against a trace yet)
     */
    public fun put(layer: Int, x: Int, y: Int, obj: SpriteObject, oam2: Int) {
        putExt(layer, x and 0x1FF, y and 0xFF, obj, oam2)
    }

    /**
     * Adds a sprite to [layer] with raw OAM attribute 1 / 0 values (flags included).
     *
     * Ported from src/ctc.c: PutSpriteExt
     */
    public fun putExt(layer: Int, xOam1: Int, yOam0: Int, obj: SpriteObject, oam2: Int) {
        val entry = pool[allocIt++]
        entry.next = layers[layer].next
        entry.oam1 = xOam1.toShort().toInt()
        entry.oam0 = yOam0.toShort().toInt()
        entry.oam2 = oam2 and 0xFFFF
        entry.obj = obj
        layers[layer].next = entry
    }

    /**
     * Walks the chain from head [layer], passing each entry with an object to [push] (the game's
     * `CallARM_PushToSecondaryOAM`, not ported here).
     *
     * Ported from src/ctc.c: PushSpriteLayerObjects
     */
    public fun pushLayerObjects(layer: Int, push: (Entry) -> Unit) {
        var it: Entry? = layers[layer]
        while (it != null) {
            if (it.obj != null) push(it)
            it = it.next
        }
    }
}
