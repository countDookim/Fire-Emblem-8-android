package fe8.core.flags

/**
 * A single switchable non-original behaviour (QoL, widescreen, alternate formulas, ...).
 *
 * Instances are created only through [FeatureFlags.register], which assigns a dense [index] so checks
 * are a single `BooleanArray` read in hot code.
 *
 * @property id stable dotted identifier, e.g. `qol.fast_cursor` or `mod.example.double_exp`.
 * @property index dense registry index.
 * @property description one-line, user-facing explanation (shown in settings).
 * @property defaultEnabled value used when not in vanilla mode and the user has not chosen.
 */
public class FeatureFlag internal constructor(
    public val id: String,
    public val index: Int,
    public val description: String,
    public val defaultEnabled: Boolean,
) {
    override fun toString(): String = "FeatureFlag($id)"
}

/**
 * Registry and current values of all [FeatureFlag]s.
 *
 * **Vanilla mode** ([vanilla] = true) forces every flag off regardless of stored values. This is the
 * single switch that keeps behaviour bit-exact for differential tests against mGBA: anything that is
 * not original behaviour must be behind a flag, and every flag is off in vanilla.
 */
public class FeatureFlags {
    private val byId = LinkedHashMap<String, FeatureFlag>()
    private var values = BooleanArray(16)

    /** When true, [isEnabled] always returns false. Defaults to true: vanilla unless the user opts in. */
    public var vanilla: Boolean = true

    /** All registered flags in registration order. */
    public val all: Collection<FeatureFlag> get() = byId.values

    /** Registers a flag (idempotent for the same [id]). Called at startup by systems and mods. */
    public fun register(id: String, description: String, defaultEnabled: Boolean = false): FeatureFlag {
        byId[id]?.let { return it }
        val flag = FeatureFlag(id, byId.size, description, defaultEnabled)
        if (flag.index >= values.size) values = values.copyOf(values.size * 2)
        values[flag.index] = defaultEnabled
        byId[id] = flag
        return flag
    }

    /** Looks a flag up by id, or null. */
    public operator fun get(id: String): FeatureFlag? = byId[id]

    /** Hot-path check. Allocation-free. */
    public fun isEnabled(flag: FeatureFlag): Boolean = !vanilla && values[flag.index]

    /** Sets a flag's stored value (takes effect only outside vanilla mode). */
    public fun set(flag: FeatureFlag, enabled: Boolean) {
        values[flag.index] = enabled
    }
}
