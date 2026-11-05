package gbafe.core.flags

/**
 * The event-flag operations a script interpreter needs, addressed by the signed 16-bit flag
 * argument of the original's script commands. [EventFlags] is the vanilla implementation;
 * [RoutedFlagStore] adds mod flags. Calls allocate nothing.
 */
public interface FlagStore {
    /** True if flag [id] is set. */
    public fun checkFlag(id: Int): Boolean

    /** Sets flag [id]. */
    public fun setFlag(id: Int)

    /** Clears flag [id]. */
    public fun clearFlag(id: Int)
}

/**
 * A [FlagStore] that sends mod handles to a [FlagRegistry] and everything else to
 * [FlagRegistry.vanilla] unchanged, so vanilla behaviour (including its range quirks) is
 * identical to using [EventFlags] directly.
 *
 * A script flag argument is a u16 that the interpreter sign-extends for vanilla flags; a mod
 * handle (`MOD_BASE..0xFFFF`) therefore arrives either as itself or as a negative short, and both
 * forms are routed to the registry. Other negative ids still go to vanilla.
 *
 * @property registry the flag registry; mod handles used here must have been registered.
 */
public class RoutedFlagStore(public val registry: FlagRegistry) : FlagStore {
    override fun checkFlag(id: Int): Boolean {
        val h = modHandle(id)
        return if (h == UNRESOLVED) registry.vanilla.checkFlag(id) else registry.check(h)
    }

    override fun setFlag(id: Int) {
        val h = modHandle(id)
        if (h == UNRESOLVED) registry.vanilla.setFlag(id) else registry.set(h)
    }

    override fun clearFlag(id: Int) {
        val h = modHandle(id)
        if (h == UNRESOLVED) registry.vanilla.clearFlag(id) else registry.clear(h)
    }

    /**
     * The mod handle [id] stands for, or [UNRESOLVED]. [MOD_BASE] is 0x8000, so as a signed short
     * every mod handle is negative and every negative short is a mod handle: no vanilla id is
     * affected. Ids outside both the positive handle range and the short range stay vanilla.
     */
    private fun modHandle(id: Int): Int =
        if (id in MOD_BASE..MAX_HANDLE || id in -0x8000..-1) id and MAX_HANDLE else UNRESOLVED
}
