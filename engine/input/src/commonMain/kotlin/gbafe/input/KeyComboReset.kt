package gbafe.input

/**
 * The soft-reset key combos (L+R+A+B or A+B+Select+Start held exactly), gated by `gKeyComboResetEN`
 * (decomp `src/time.c`, `src/hardware.c`).
 */
public class KeyComboReset {
    /** `gKeyComboResetEN`. */
    public var enabled: Boolean = false

    /**
     * Ported from src/time.c: EnableKeyComboResetEN
     */
    public fun enable() {
        enabled = true
    }

    /**
     * Transcribed from src/time.c: DisableKeyComboResetEN (not verified: no test checks it against
     *   a trace yet)
     */
    public fun disable() {
        enabled = false
    }

    /**
     * Ported from src/time.c: CheckCanKeyComboReset
     */
    public fun canReset(): Boolean = enabled

    /**
     * Whether this frame soft-resets: [canReset] and [keys]' held keys are exactly L+R+A+B or A+B+Select+Start.
     * The caller performs the reset (`SoftReset(0)`).
     */
    public fun shouldReset(keys: KeyStatus): Boolean =
        canReset() && (keys.heldKeys == L_R_A_B || keys.heldKeys == A_B_SELECT_START)

    private companion object {
        const val L_R_A_B = 0x200 or 0x100 or 0x1 or 0x2
        const val A_B_SELECT_START = 0x1 or 0x2 or 0x4 or 0x8
    }
}
