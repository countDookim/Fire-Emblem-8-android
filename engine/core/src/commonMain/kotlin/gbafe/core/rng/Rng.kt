package gbafe.core.rng

/**
 * A source of 16-bit pseudo-random numbers. This is the swap point for mods that want a different
 * generator; game code never calls a generator directly but goes through [RngService].
 */
public interface RandomSource {
    /** Returns the next raw value in `0..0xFFFF`. */
    public fun nextRaw(): Int
}

/**
 * The original FE8 primary RNG: a 48-bit state held as three 16-bit words.
 *
 * Vanilla behaviour is bit-exact, including how a negative seed is reduced (Kotlin's `%` truncates
 * toward zero exactly like C on the GBA). State is held in three `Int`s masked to 16 bits: no boxing,
 * no arrays, allocation-free.
 */
public class Fe8Rng : RandomSource {
    private var s0: Int = 0
    private var s1: Int = 0
    private var s2: Int = 0

    /** Ported from src/rng.c: NextRN */
    override fun nextRaw(): Int {
        var rn = ((s1 shl 11) + (s0 ushr 5)) and 0xFFFF
        s2 = (s2 shl 1) and 0xFFFF
        if (s1 and 0x8000 != 0) s2 = (s2 + 1) and 0xFFFF
        rn = rn xor s2
        s2 = s1
        s1 = s0
        s0 = rn
        return rn
    }

    /**
     * Seeds the generator: three consecutive entries of a fixed table chosen by `seed % 7`, then
     * `seed % 23` warm-up draws.
     *
     * Ported from src/rng.c: InitRN
     */
    public fun init(seed: Int) {
        var mod = seed % 7
        s0 = INIT_TABLE[mod++ and 7]
        s1 = INIT_TABLE[mod++ and 7]
        s2 = INIT_TABLE[mod and 7]
        if (seed % 23 > 0) {
            var n = seed % 23
            while (n != 0) {
                nextRaw()
                n--
            }
        }
    }

    /**
     * Transcribed from src/rng.c: LoadRNState (not verified: no test checks it against a trace yet)
     */
    public fun load(w0: Int, w1: Int, w2: Int) {
        s0 = w0 and 0xFFFF
        s1 = w1 and 0xFFFF
        s2 = w2 and 0xFFFF
    }

    /**
     * Copies the three state words into [out] starting at [offset] (used by saves and suspend data).
     *
     * Transcribed from src/rng.c: StoreRNState (not verified: no test checks it against a trace
     *   yet)
     */
    public fun store(out: IntArray, offset: Int = 0) {
        out[offset] = s0
        out[offset + 1] = s1
        out[offset + 2] = s2
    }

    /** State word [i] (0..2), for tests and the debug inspector. */
    public fun word(i: Int): Int = when (i) {
        0 -> s0
        1 -> s1
        2 -> s2
        else -> throw IndexOutOfBoundsException("word $i")
    }

    private companion object {
        private val INIT_TABLE = intArrayOf(0xA36E, 0x924E, 0xB784, 0x4F67, 0x8092, 0x592D, 0x8E70, 0xA794)
    }
}

/**
 * The secondary FE8 generator: a tiny LCG used only to derive the boot seed for [Fe8Rng] (and by a few
 * presentation effects). Arithmetic is unsigned 32-bit, emulated with wrapping `Int` maths and `ushr`.
 */
public class LcgRng {
    /** Current LCG value (raw 32 bits). */
    public var value: Int = 0
        private set

    /** Ported from src/rng.c: SetLCGRNValue */
    public fun set(seed: Int) {
        value = seed
    }

    /**
     * Advances and returns the new value (as raw 32 bits; the C function returns `unsigned`).
     *
     * Ported from src/rng.c: AdvanceGetLCGRNValue
     */
    public fun advance(): Int {
        val rn = (value * 4 + 2) * (value * 4 + 3)
        value = rn ushr 2
        return value
    }
}

/**
 * Central, seedable RNG service. All gameplay randomness goes through here so that vanilla mode can be
 * reproduced exactly and mods can swap the [source] (e.g. a "fixed growths" or seeded-run mod) without
 * touching game code.
 *
 * The derived helpers keep the original integer maths, so swapping the source changes the numbers but
 * not the distribution logic (that belongs to the mechanics strategy interfaces instead).
 */
public class RngService(
    /** The primary generator; vanilla uses [Fe8Rng]. */
    public val primary: Fe8Rng = Fe8Rng(),
    /** The boot/effect LCG. */
    public val lcg: LcgRng = LcgRng(),
) {
    /** Active source for draws; defaults to [primary]. Mods may replace it. */
    public var source: RandomSource = primary

    /** Raw draw in `0..0xFFFF`. Ported from src/rng.c: NextRN (via [source]). */
    public fun next(): Int = source.nextRaw()

    /**
     * Draw in `0..99`. Note FE8 multiplies before dividing, so 100 is impossible (FE6 differs).
     *
     * Transcribed from src/rng.c: NextRN_100 (not verified: no test checks it against a trace yet)
     */
    public fun next100(): Int = next() * 100 / 0x10000

    /**
     * Draw in `0 until max`.
     * Transcribed from src/rng.c: NextRN_N (not verified: no test checks it against a trace yet)
     */
    public fun nextN(max: Int): Int = next() * max / 0x10000

    /**
     * One-RN hit check: true when `threshold > draw`.
     * Transcribed from src/rng.c: Roll1RN (not verified: no test checks it against a trace yet)
     */
    public fun roll1(threshold: Int): Boolean = threshold > next100()

    /**
     * Two-RN ("true hit") check: averages two draws, which biases displayed hit rates.
     *
     * Transcribed from src/rng.c: Roll2RN (not verified: no test checks it against a trace yet)
     */
    public fun roll2(threshold: Int): Boolean {
        val average = (next100() + next100()) / 2
        return threshold > average
    }

    /**
     * Reproduces the boot-time seeding done in `AgbMain`: LCG seeded with the constant `0x42D690E9`,
     * advanced once, and the result passed to [Fe8Rng.init].
     *
     * Partially ported from src/main.c: AgbMain (RNG initialisation only).
     */
    public fun seedLikeBoot() {
        lcg.set(BOOT_LCG_SEED)
        primary.init(lcg.advance())
    }

    /** Constants shared with the original boot sequence. */
    public companion object {
        /** Constant LCG seed used at boot by the original game. */
        public const val BOOT_LCG_SEED: Int = 0x42D690E9
    }
}
