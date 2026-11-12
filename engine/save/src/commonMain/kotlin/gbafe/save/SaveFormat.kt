package gbafe.save

/**
 * Binary save container specification, version [CURRENT_VERSION]. All integers are little-endian.
 * The container is this port's own file format (not in the original game); the original's SRAM
 * layout is what the `core` section carries.
 *
 * ## Binary Layout
 * ```
 * +0   4  magic signature "GBFS" (0x47, 0x42, 0x46, 0x53)
 * +4   2  container version (u16)
 * +6   2  section count (u16)
 * +8   1  game id length n (u8), followed by n ASCII bytes
 * ...     section table, one entry per section:
 *           1 id length m (u8), m ASCII bytes,
 *           2 section version (u16),
 *           4 payload length (u32),
 *           4 payload CRC-32 (u32)
 * ...  4  CRC-32 checksum of everything above (header + section table)
 * ...     section payloads, back to back, in section table order
 * ```
 *
 * The section `core` ([CORE_SECTION]) stores the raw 32 KiB GBA cartridge SRAM image ([CORE_SIZE]).
 * Sections named `mod:<id>` ([modSectionId]) contain mod-specific save data and are preserved verbatim
 * when rewritten. Older section versions are upgraded via [MigrationRegistry]; sections with versions
 * newer than supported raise [SaveException.NewerVersion].
 */
public object SaveFormat {
    /** Magic 4-byte signature identifying a GBFS container ("GBFS"). */
    public const val MAGIC: String = "GBFS"

    /** Current container layout version. Bumped when the container framing format changes. */
    public const val CURRENT_VERSION: Int = 1

    /** Current version of the `core` section payload layout. Bumped when registering a [Migration]. */
    public const val CORE_VERSION: Int = 1

    /** Section identifier for the cartridge SRAM image. */
    public const val CORE_SECTION: String = "core"

    /** Prefix prepended to mod section identifiers. */
    public const val MOD_PREFIX: String = "mod:"

    /**
     * Size in bytes of the GBA cartridge SRAM image stored in the `core` section (32 KiB = 0x8000;
     * the size FE8's `WipeSram` erases, 0x200 blocks of 0x40 bytes).
     */
    public const val CORE_SIZE: Int = 0x8000

    /** Returns the formatted section identifier for mod [modId] (`"mod:<modId>"`). */
    public fun modSectionId(modId: String): String = MOD_PREFIX + modId
}
