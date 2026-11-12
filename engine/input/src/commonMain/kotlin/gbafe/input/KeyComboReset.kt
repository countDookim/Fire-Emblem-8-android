package gbafe.input

/**
 * The soft-reset key combos (L+R+A+B or A+B+Select+Start held exactly, as checked by
 * `SoftResetIfKeyComboPressed` in `src/hardware.c` once per main-loop pass), gated by the
 * `gKeyComboResetEN` switch of `src/time.c`. The game turns the gate off at boot
 * (`DisableKeyComboResetEN` in `AgbMain`) and on again when the Nintendo / Intelligent Systems
 * logo screens run (`EnableKeyComboResetEN` in `src/opanim-healthsafetyscreen.c`).
 */
public class KeyComboReset {
    /** `gKeyComboResetEN`: true while the soft-reset combos are allowed. */
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
     * Whether this frame soft-resets: [canReset] and [keys]' held keys are exactly L+R+A+B or
     * A+B+Select+Start. The caller performs the reset (`SoftReset(0)`).
     */
    public fun shouldReset(keys: KeyStatus): Boolean =
        canReset() && (keys.heldKeys == L_R_A_B || keys.heldKeys == A_B_SELECT_START)

    private companion object {
        // GBA key bits (L_BUTTON etc. in include/gba/io_reg.h): A=0x1, B=0x2, Select=0x4,
        // Start=0x8, R=0x100, L=0x200.
        const val L_R_A_B = 0x200 or 0x100 or 0x1 or 0x2
        const val A_B_SELECT_START = 0x1 or 0x2 or 0x4 or 0x8
    }
}
