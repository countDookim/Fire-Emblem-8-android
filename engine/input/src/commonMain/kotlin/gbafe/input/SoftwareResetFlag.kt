package gbafe.input

/**
 * `gSoftwareResetFlag` (decomp `src/hardware.c`): set at boot when the game starts after a soft
 * reset (`AgbMain`: `REG_WAITCNT != 0`), which lets the boot logos be skipped.
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
