package gba.data

/** `Register2dChrMove` (src/bmlib.c): the tile block copy that goes through the data move queue. */
public object ChrMove {
    /** `CHR_SIZE`: bytes in one 4bpp tile. */
    public const val CHR_SIZE: Int = 0x20

    /** Tiles in one row of a 2D-mapped OBJ or BG character area (`CHR_SIZE * 0x20` bytes). */
    private const val ROW_TILES: Int = 0x20

    /**
     * `Register2dChrMove(src, dst, width, height)`: queues [height] rows of [width] tiles by
     * calling [registerDataMove] (`RegisterDataMove(src, dst, size)`) once per row. The source
     * rows follow each other; the destination rows are 0x20 tiles apart.
     *
     * Ported from src/bmlib.c: Register2dChrMove
     */
    public fun register2dChrMove(
        src: Int,
        dst: Int,
        width: Int,
        height: Int,
        registerDataMove: (src: Int, dst: Int, size: Int) -> Unit,
    ) {
        val lineSize = width * CHR_SIZE
        if (height <= 0) return
        var s = src
        var d = dst
        for (i in height downTo 1) {
            registerDataMove(s, d, lineSize)
            s += lineSize
            d += CHR_SIZE * ROW_TILES
        }
    }
}
