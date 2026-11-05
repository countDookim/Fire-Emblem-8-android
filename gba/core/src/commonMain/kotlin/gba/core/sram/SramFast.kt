package gba.core.sram

/** Number of write+verify attempts `WriteAndVerifySramFast` makes before giving up. */
public const val SRAM_WRITE_ATTEMPTS: Int = 3

/**
 * Ported from src/agb_sram.c: WriteSramFast
 *
 * Copies [size] bytes from [src] at [srcOffset] into [sram] at [destOffset]. The WAITCNT write of
 * the original (SRAM wait states) has no effect in this model and is omitted. A [size] of 0 copies
 * nothing (the C loop `while (--size != -1)` exits at once).
 */
public fun writeSramFast(
    src: ByteArray,
    srcOffset: Int,
    sram: CartSram,
    destOffset: Int,
    size: Int,
) {
    sram.write(destOffset, src, srcOffset, size)
}

/**
 * Ported from src/agb_sram.c: ReadSramFast_Core
 *
 * Copies [size] bytes from [sram] at [srcOffset] into [dest] at [destOffset]. This is what the
 * `ReadSramFast` function pointer runs (the original copies the routine into WRAM; that copy is
 * `SetSramFastFunc` and has no equivalent here).
 */
public fun readSramFast(
    sram: CartSram,
    srcOffset: Int,
    dest: ByteArray,
    destOffset: Int,
    size: Int,
) {
    sram.read(srcOffset, dest, destOffset, size)
}

/**
 * Ported from src/agb_sram.c: VerifySramFast_Core
 *
 * Compares [size] bytes of [src] at [srcOffset] with [sram] at [destOffset]. Returns 0 when equal,
 * else the GBA bus address of the first differing SRAM byte (`CartSram.BASE_ADDRESS + offset`),
 * exactly as the original returns the destination pointer, so a result of 0 still means success.
 */
public fun verifySramFast(
    src: ByteArray,
    srcOffset: Int,
    sram: CartSram,
    destOffset: Int,
    size: Int,
): Int {
    val i = sram.firstMismatch(destOffset, src, srcOffset, size)
    return if (i < 0) 0 else CartSram.BASE_ADDRESS + destOffset + i
}

/**
 * Ported from src/agb_sram.c: WriteAndVerifySramFast
 *
 * Writes [size] bytes of [src] to [sram] and verifies them, repeating the pair up to
 * [SRAM_WRITE_ATTEMPTS] (3) times until the verify succeeds. Returns the last verify result: 0 on
 * success, otherwise the bus address of the first byte that still differed after the final attempt.
 * With in-memory SRAM the first attempt always succeeds, so the retries only matter to code that
 * wraps real hardware.
 */
public fun writeAndVerifySramFast(
    src: ByteArray,
    srcOffset: Int,
    sram: CartSram,
    destOffset: Int,
    size: Int,
): Int {
    var errorAddr = 0
    for (attempt in 0 until SRAM_WRITE_ATTEMPTS) {
        writeSramFast(src, srcOffset, sram, destOffset, size)
        errorAddr = verifySramFast(src, srcOffset, sram, destOffset, size)
        if (errorAddr == 0) break
    }
    return errorAddr
}
