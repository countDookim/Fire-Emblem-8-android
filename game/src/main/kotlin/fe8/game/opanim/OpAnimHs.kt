package fe8.game.opanim

/**
 * Boot intro screens (Nintendo / Intelligent Systems logos, health & safety), decomp
 * `src/opanim-healthsafetyscreen.c`. So far: the palette fade helper the logo and warning screens use.
 */
public object OpAnimHs {
    /**
     * Writes [lines] × 16 colours of [src] into BG palette bank [palId] onwards of [paletteBuffer] (the game's
     * `gPaletteBuffer`, 512 colours, BG banks first), each 5-bit channel scaled by `cur / max` with C integer
     * division; at `cur >= max` the colours are copied unchanged. The caller then marks the palette for upload
     * (`EnablePaletteSync`).
     *
     * Ported from src/opanim-healthsafetyscreen.c: OpAnimHS_BrightenPalette
     */
    public fun brightenPalette(src: ShortArray, paletteBuffer: ShortArray, palId: Int, lines: Int, cur: Int, max: Int) {
        val len = lines * 16
        val dst = palId * 16
        if (cur < max) {
            for (i in 0 until len) {
                val c = src[i].toInt() and 0xFFFF
                val r = (c and 0x1F) * cur / max
                val g = ((c shr 5) and 0x1F) * cur / max
                val b = ((c shr 10) and 0x1F) * cur / max
                paletteBuffer[dst + i] = (r or (g shl 5) or (b shl 10)).toShort()
            }
        } else {
            src.copyInto(paletteBuffer, dst, 0, len)
        }
    }
}
