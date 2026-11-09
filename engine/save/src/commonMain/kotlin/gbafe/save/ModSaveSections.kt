package gbafe.save

import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.VarRegistry

/**
 * Serializes and deserializes a mod's persistent state into a mod save section (`mod:<id>`).
 *
 * ## Payload Format (Version 1)
 * ```
 * +0  4  flags length fl (i32 little-endian)
 * +4  fl flags binary blob (empty when fl == 0)
 * ... 4  variables length vl (i32 little-endian)
 * ... vl variables binary blob (empty when vl == 0)
 * ```
 */
public object ModSaveSections {
    /** Current mod section format version. */
    public const val VERSION: Int = 1

    /** Size in bytes of a 32-bit length prefix. */
    private const val LENGTH_PREFIX_BYTES: Int = 4

    /**
     * Serializes [modId]'s registered flags and variables from [flags] and [vars], updates or appends
     * the section in [container], and returns the new container. Null registries produce empty blobs.
     */
    public fun write(
        container: SaveContainer,
        modId: String,
        flags: FlagRegistry?,
        vars: VarRegistry?,
    ): SaveContainer {
        val flagBlob = flags?.serializeMod(modId) ?: EMPTY
        val varBlob = vars?.serializeMod(modId) ?: EMPTY
        val payload = ByteArray(LENGTH_PREFIX_BYTES * 2 + flagBlob.size + varBlob.size)
        putI32(payload, 0, flagBlob.size)
        flagBlob.copyInto(payload, LENGTH_PREFIX_BYTES)
        val varOffset = LENGTH_PREFIX_BYTES + flagBlob.size
        putI32(payload, varOffset, varBlob.size)
        varBlob.copyInto(payload, varOffset + LENGTH_PREFIX_BYTES)
        return container.withSection(SaveSection(SaveFormat.modSectionId(modId), VERSION, payload))
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
        val payload = section.data
        try {
            val flagLen = getI32(payload, 0)
            require(flagLen >= 0 && LENGTH_PREFIX_BYTES + flagLen <= payload.size - LENGTH_PREFIX_BYTES) {
                "bad flag blob length"
            }
            val varLenOffset = LENGTH_PREFIX_BYTES + flagLen
            val varLen = getI32(payload, varLenOffset)
            require(varLen >= 0 && varLenOffset + LENGTH_PREFIX_BYTES + varLen == payload.size) {
                "bad variable blob length"
            }
            val flagBlob = payload.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + flagLen)
            val varBlob = payload.copyOfRange(varLenOffset + LENGTH_PREFIX_BYTES, payload.size)
            if (flags != null) flags.deserializeMod(modId, if (flagLen == 0) EMPTY else flagBlob)
            if (vars != null) vars.deserializeMod(modId, if (varLen == 0) EMPTY else varBlob)
        } catch (e: IllegalArgumentException) {
            throw SaveException.Corrupt("${section.id}: ${e.message}")
        }
    }

    /** An empty 4-byte buffer representing zero registered entries. */
    private val EMPTY = ByteArray(4)

    /** Writes a 32-bit little-endian integer into [bytes] at [offset]. */
    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value shr (8 * i)).toByte()
    }

    /** Reads a 32-bit little-endian integer from [bytes] at [offset]. */
    private fun getI32(bytes: ByteArray, offset: Int): Int {
        require(offset + LENGTH_PREFIX_BYTES <= bytes.size) { "truncated" }
        var value = 0
        for (i in 0..3) value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }
}
