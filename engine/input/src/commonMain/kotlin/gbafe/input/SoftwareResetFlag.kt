package gbafe.input

/**
 * `gSoftwareResetFlag` (decomp `src/hardware.c`): set at boot when the game starts after a soft
 * reset (`AgbMain`: `REG_WAITCNT != 0`; the decomp comment guesses WAITCNT is not cleared by a
 * soft reset). While set, the Health and Safety screen is skipped
 * (`PrepareHealthAndSafetyScreen` in `src/opanim-healthsafetyscreen.c`) and the Nintendo logo
 * can be skipped with B or Start (`TryTickHSScreenExit`; without it only the Intelligent Systems
 * logo can be).
 */
public class SoftwareResetFlag {
    private var flag: Boolean = false

    /**
     * Transcribed from src/hardware.c: SetSoftwareResetFlag (not verified: a plain flag
     *   accessor; tests can only feed it the dumped flag, and nothing in the boot sets it yet)
     */
    public fun set(value: Boolean) {
        flag = value
    }

    /**
     * Transcribed from src/hardware.c: IsSoftwareReset (not verified: a plain flag accessor; tests
     *   can only feed it the dumped flag, and nothing in the boot sets it yet)
     */
    public fun isSoftwareReset(): Boolean = flag
}
