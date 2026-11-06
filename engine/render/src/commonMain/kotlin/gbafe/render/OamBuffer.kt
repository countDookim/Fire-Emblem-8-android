package gbafe.render

/**
 * The OAM shadow buffer of `src/hardware.c` (`gOam`, split by `InitOam` into a "lo" part of
 * [loCount] objects and a "hi" part of the rest) and the hardware OAM it is synced to in VBlank.
 * Objects are 4 halfwords (attr0, attr1, attr2, affine parameter slot).
 *
 * Not modelled: `gOamAffinePutIt`/`gOamAffinePutId` (reset by [syncHi] in the original) and
 * `PutOamLo`.
 */
public class OamBuffer {
    /** `gOam`: the shadow buffer, 128 objects. */
    public val buffer: ShortArray = ShortArray(OBJ_COUNT * 4)

    /** Hardware OAM (`OAM`, 0x07000000), written by [syncHi] and [syncLo]. */
    public val oam: ShortArray = ShortArray(OBJ_COUNT * 4)

    /** `sOamLo.count` (the `InitOam` argument); the hi part holds the other `128 - loCount` objects. */
    public var loCount: Int = 0

    /** `gOamHiPutIt`, as a halfword index into [buffer]. */
    public var hiPut: Int = 0

    /**
     * Appends the objects of [data] (a count, then attr0, attr1, attr2 per object) at [hiPut],
     * moved by ([x], [y]) and with [oam2] added to attr2. The original writes past the buffer's end
     * if the count overflows it; those writes are dropped here.
     *
     * Ported from asm/arm.s: PutOamHi
     */
    public fun putHi(x: Int, y: Int, data: ShortArray, oam2: Int) {
        val count = data[0].toInt() and 0xFFFF
        if (count == 0) return
        var dst = hiPut
        hiPut += count * 4
        val px = x and 0xFFFF
        val py = y and 0xFFFF
        var src = 1
        repeat(count) {
            val s0 = data[src].toInt() and 0xFFFF
            val s1 = data[src + 1].toInt() and 0xFFFF
            val s2 = data[src + 2].toInt() and 0xFFFF
            if (dst + 2 < buffer.size) {
                buffer[dst] = (((s0 or py) and 0xFF00) or ((s0 + py) and 0xFF)).toShort()
                buffer[dst + 1] = (((s1 or px) and 0xFE00) or ((s1 + px) and 0x1FF)).toShort()
                buffer[dst + 2] = (s2 + oam2).toShort()
            }
            src += 3
            dst += 4
        }
    }

    /**
     * Copies the hi part to [oam], clears it in [buffer] and rewinds [hiPut].
     *
     * Ported from src/hardware.c: SyncHiOam
     */
    public fun syncHi() {
        val from = loCount * 4
        buffer.copyInto(oam, from, from, buffer.size)
        clear(loCount, OBJ_COUNT - loCount)
        hiPut = from
    }

    /**
     * Copies the lo part to [oam] and clears it in [buffer], if it is not empty.
     *
     * Transcribed from src/hardware.c: SyncLoOam (not verified: no test checks it against a trace
     *   yet)
     */
    public fun syncLo() {
        if (loCount == 0) return
        buffer.copyInto(oam, 0, 0, loCount * 4)
        clear(0, loCount)
    }

    /**
     * Moves objects off screen: attr0 = 160 and attr1 = 0 for `count / 16` blocks of 16 objects
     * from [first] (at least one block).
     *
     * Ported from asm/arm.s: ClearOam
     */
    public fun clear(first: Int, count: Int) {
        val blocks = maxOf(1, count shr 4)
        for (i in first until minOf(OBJ_COUNT, first + 16 * blocks)) {
            buffer[i * 4] = OFFSCREEN_Y.toShort()
            buffer[i * 4 + 1] = 0
        }
    }

    /**
     * Sets OBJ affine matrix [index]: its four parameters go to the fourth halfword of objects
     * `4 * index` .. `4 * index + 3`.
     *
     * Transcribed from src/hardware.c: SetObjAffine (not verified: no test checks it against a
     *   trace yet)
     */
    public fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {
        buffer[index * 16 + 3] = pa.toShort()
        buffer[index * 16 + 7] = pb.toShort()
        buffer[index * 16 + 11] = pc.toShort()
        buffer[index * 16 + 15] = pd.toShort()
    }

    /** Sizes. */
    public companion object {
        /** Objects in OAM. */
        public const val OBJ_COUNT: Int = 128

        /** The attr0 value `ClearOam` writes (y = 160, below the screen). */
        public const val OFFSCREEN_Y: Int = 160
    }
}
