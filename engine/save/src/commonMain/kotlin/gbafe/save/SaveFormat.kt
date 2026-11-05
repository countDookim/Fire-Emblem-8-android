package gbafe.save

/**
 * Binary save container, version [CURRENT_VERSION]. All integers are little-endian.
 *
 * ```
 * +0   4  magic "GBFS"
 * +4   2  container version (u16)
 * +6   2  section count (u16)
 * +8   1  game id length n (u8), then n ASCII bytes
 * ...     section table, one entry per section:
 *           1 id length m (u8), m ASCII bytes, 2 section version (u16), 4 length (u32),
 *           4 crc32 (u32)
 * ...  4  crc32 of everything above (header + table)
 * ...     section payloads, back to back, in table order
 * ```
 *
 * The section `core` ([CORE_SECTION]) is the raw 32 KiB cartridge SRAM image; sections named
 * `mod:<id>` ([modSectionId]) are opaque to the engine and preserved verbatim on rewrite. Older
 * section versions are upgraded by [MigrationRegistry]; newer-than-supported versions raise
 * [SaveException.NewerVersion]. Reading and writing is done by [SaveContainer].
 */
public object SaveFormat {
    /** Current container layout version. Bump when the layout above changes. */
    public const val CURRENT_VERSION: Int = 1

    /** Current version of the `core` section payload. Bump and register a [Migration] on change. */
    public const val CORE_VERSION: Int = 1

    /** Id of the section holding the raw cartridge SRAM image. */
    public const val CORE_SECTION: String = "core"

    /** Prefix of every mod section id. */
    public const val MOD_PREFIX: String = "mod:"

    /** Size in bytes of the cartridge SRAM image stored in the `core` section. */
    public const val CORE_SIZE: Int = 0x8000

    /** Returns the section id for the mod [modId]. */
    public fun modSectionId(modId: String): String = MOD_PREFIX + modId
}
