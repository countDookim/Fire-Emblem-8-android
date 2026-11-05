package gbafe.save

import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.VarRegistry

/**
 * Saves and restores one mod's [FlagRegistry] and [VarRegistry] state in its container section
 * `mod:<id>` ([SaveFormat.modSectionId]). Entries are keyed by name (the registries' own
 * serialization), never by handle, so the order mods register in does not matter. The core
 * section is not touched.
 *
 * Payload (version [VERSION]): i32 length + flag blob, i32 length + variable blob, little-endian;
 * a length of 0 means the registry was absent when saving.
 */
public object ModSaveSections {
    /** Version of the section payload. */
    public const val VERSION: Int = 1

    /**
     * Returns [container] with [modId]'s section replaced (or appended) by the current state of
     * [flags] and [vars] (either may be null).
     */
    public fun write(
        container: SaveContainer,
        modId: String,
        flags: FlagRegistry?,
        vars: VarRegistry?,
    ): SaveContainer {
        val f = flags?.serializeMod(modId) ?: ByteArray(0)
        val v = vars?.serializeMod(modId) ?: ByteArray(0)
        val out = ByteArray(8 + f.size + v.size)
        putI32(out, 0, f.size)
        f.copyInto(out, 4)
        putI32(out, 4 + f.size, v.size)
        v.copyInto(out, 8 + f.size)
        return container.withSection(SaveSection(SaveFormat.modSectionId(modId), VERSION, out))
    }

    /**
     * Restores [modId]'s flags and variables from [container]. A container without the section
     * resets them (a fresh save). Registered names missing from the save are reset, saved names
     * the mod has not registered are ignored. A section newer than [VERSION] throws
     * [SaveException.NewerVersion]; malformed payloads throw [SaveException.Corrupt].
     */
    public fun read(
        container: SaveContainer,
        modId: String,
        flags: FlagRegistry?,
        vars: VarRegistry?,
    ) {
        val section = container.section(SaveFormat.modSectionId(modId))
        if (section == null) {
            flags?.deserializeMod(modId, EMPTY)
            vars?.deserializeMod(modId, EMPTY)
            return
        }
        if (section.version > VERSION) {
            throw SaveException.NewerVersion(section.id, section.version, VERSION)
        }
        val d = section.data
        try {
            val fl = getI32(d, 0)
            require(fl >= 0 && 4 + fl <= d.size - 4) { "bad flag blob length" }
            val vl = getI32(d, 4 + fl)
            require(vl >= 0 && 8 + fl + vl == d.size) { "bad variable blob length" }
            val fb = d.copyOfRange(4, 4 + fl)
            val vb = d.copyOfRange(8 + fl, 8 + fl + vl)
            if (flags != null) flags.deserializeMod(modId, if (fl == 0) EMPTY else fb)
            if (vars != null) vars.deserializeMod(modId, if (vl == 0) EMPTY else vb)
        } catch (e: IllegalArgumentException) {
            throw SaveException.Corrupt("${section.id}: ${e.message}")
        }
    }

    private val EMPTY = ByteArray(4)

    private fun putI32(b: ByteArray, at: Int, v: Int) {
        for (i in 0..3) b[at + i] = (v shr (8 * i)).toByte()
    }

    private fun getI32(b: ByteArray, at: Int): Int {
        require(at + 4 <= b.size) { "truncated" }
        var v = 0
        for (i in 0..3) v = v or ((b[at + i].toInt() and 0xFF) shl (8 * i))
        return v
    }
}
