package gbafe.save

/**
 * One section of a [SaveContainer].
 *
 * The [id] is 1 to 255 printable ASCII characters and the [version] fits an unsigned 16-bit value.
 */
public class SaveSection(
    /** Section id: `core`, or `mod:<id>` for mod data. */
    public val id: String,
    /** Version of the payload layout of this section. */
    public val version: Int,
    /** Opaque payload; not copied, callers must not mutate it after construction. */
    public val data: ByteArray,
) {
    init {
        require(id.length in 1..MAX_ID && id.all { it.code in 0x20..0x7E }) {
            "bad section id: $id"
        }
        require(version in 1..0xFFFF) { "bad section version: $version" }
    }

    internal companion object {
        const val MAX_ID: Int = 255
    }
}

/**
 * A save file in memory: a game id and an ordered list of [SaveSection]s (layout in [SaveFormat]).
 *
 * Section order and unknown sections are preserved by [read] followed by [writeTo], so a mod's data
 * survives being rewritten by a build that does not know the mod.
 */
public class SaveContainer(
    /** Id of the game the save belongs to (1 to 255 printable ASCII characters), e.g. `fe8`. */
    public val gameId: String,
    /** Sections in file order; ids are unique. */
    public val sections: List<SaveSection>,
) {
    init {
        require(gameId.length in 1..SaveSection.MAX_ID && gameId.all { it.code in 0x20..0x7E }) {
            "bad game id: $gameId"
        }
        require(sections.size <= 0xFFFF) { "too many sections" }
        require(sections.map { it.id }.toSet().size == sections.size) { "duplicate section ids" }
    }

    /** The section with the given [id], or null. */
    public fun section(id: String): SaveSection? {
        for (i in sections.indices) if (sections[i].id == id) return sections[i]
        return null
    }

    /** The raw SRAM image of the `core` section, or null if absent. */
    public val core: ByteArray? get() = section(SaveFormat.CORE_SECTION)?.data

    /** A copy of this container with [section] replacing the one with the same id, or appended. */
    public fun withSection(section: SaveSection): SaveContainer {
        val list = ArrayList<SaveSection>(sections.size + 1)
        var replaced = false
        for (s in sections) {
            if (s.id == section.id) {
                list.add(section)
                replaced = true
            } else {
                list.add(s)
            }
        }
        if (!replaced) list.add(section)
        return SaveContainer(gameId, list)
    }

    /** Number of bytes [writeTo] produces. */
    public fun encodedSize(): Int {
        var size = headerSize()
        for (i in sections.indices) size += sections[i].data.size
        return size
    }

    private fun headerSize(): Int {
        var size = FIXED_HEADER + 1 + gameId.length + CRC
        for (i in sections.indices) size += 1 + sections[i].id.length + ENTRY_FIXED
        return size
    }

    /**
     * Writes the container into [dst] at [offset] and returns the number of bytes written
     * ([encodedSize]). Allocates nothing.
     *
     * @throws IllegalArgumentException if [dst] is too small
     */
    public fun writeTo(dst: ByteArray, offset: Int = 0): Int {
        val total = encodedSize()
        require(offset >= 0 && offset + total <= dst.size) { "buffer too small: need $total" }
        var p = offset
        dst[p++] = 'G'.code.toByte()
        dst[p++] = 'B'.code.toByte()
        dst[p++] = 'F'.code.toByte()
        dst[p++] = 'S'.code.toByte()
        p = putU16(dst, p, SaveFormat.CURRENT_VERSION)
        p = putU16(dst, p, sections.size)
        p = putString(dst, p, gameId)
        for (i in sections.indices) {
            val s = sections[i]
            p = putString(dst, p, s.id)
            p = putU16(dst, p, s.version)
            p = putU32(dst, p, s.data.size)
            p = putU32(dst, p, Crc32.compute(s.data))
        }
        p = putU32(dst, p, Crc32.compute(dst, offset, p - offset))
        for (i in sections.indices) {
            val data = sections[i].data
            data.copyInto(dst, p)
            p += data.size
        }
        return p - offset
    }

    /** Returns the encoded container as a new array. */
    public fun toByteArray(): ByteArray = ByteArray(encodedSize()).also { writeTo(it) }

    public companion object {
        private const val FIXED_HEADER = 8
        private const val ENTRY_FIXED = 10
        private const val CRC = 4

        /**
         * Builds a container holding [sram] (the 32 KiB cartridge image) plus the given mod
         * sections.
         */
        public fun fromSram(
            gameId: String,
            sram: ByteArray,
            mods: List<SaveSection> = emptyList(),
        ): SaveContainer {
            require(sram.size == SaveFormat.CORE_SIZE) {
                "SRAM image must be ${SaveFormat.CORE_SIZE} bytes"
            }
            return SaveContainer(
                gameId,
                listOf(SaveSection(SaveFormat.CORE_SECTION, SaveFormat.CORE_VERSION, sram)) + mods,
            )
        }

        /**
         * Parses `src[offset, offset + length)` and upgrades every section known to [registry] to
         * its current version. Sections unknown to the registry are kept verbatim.
         *
         * @throws SaveException.Corrupt on bad magic, truncation, bad checksums or duplicate ids
         * @throws SaveException.NewerVersion if the container or a known section is newer than
         *     supported
         * @throws SaveException.MissingMigration if an upgrade step is not registered
         */
        public fun read(
            src: ByteArray,
            offset: Int = 0,
            length: Int = src.size - offset,
            registry: MigrationRegistry = MigrationRegistry(),
        ): SaveContainer {
            val table = parseTable(src, offset, length)
            val list = ArrayList<SaveSection>(table.count)
            var dataPos = table.dataStart
            val ids = HashSet<String>()
            for (i in 0 until table.count) {
                val e = table.entry(i)
                if (!ids.add(e.id)) throw SaveException.Corrupt("duplicate section ${e.id}")
                val data = src.copyOfRange(dataPos, dataPos + e.length)
                if (Crc32.compute(data) != e.crc) {
                    throw SaveException.Corrupt("section ${e.id} checksum")
                }
                dataPos += e.length
                list.add(registry.upgrade(SaveSection(e.id, e.version, data)))
            }
            return SaveContainer(table.gameId, list)
        }

        /**
         * Copies the payload of section [id] from the encoded container
         * `src[offset, offset + length)` into [dst] at [dstOffset] without allocating a payload
         * array, and returns its length, or -1 if the section is absent. Checksums of the header
         * and the section are verified; no migration is applied.
         *
         * @throws SaveException.Corrupt on invalid or truncated data
         * @throws IllegalArgumentException if [dst] is too small
         */
        public fun readSectionInto(
            src: ByteArray,
            id: String,
            dst: ByteArray,
            dstOffset: Int = 0,
            offset: Int = 0,
            length: Int = src.size - offset,
        ): Int {
            val table = parseTable(src, offset, length)
            var dataPos = table.dataStart
            for (i in 0 until table.count) {
                val e = table.entry(i)
                if (e.id == id) {
                    require(dstOffset >= 0 && dstOffset + e.length <= dst.size) {
                        "buffer too small"
                    }
                    if (Crc32.compute(src, dataPos, e.length) != e.crc) {
                        throw SaveException.Corrupt("section $id checksum")
                    }
                    src.copyInto(dst, dstOffset, dataPos, dataPos + e.length)
                    return e.length
                }
                dataPos += e.length
            }
            return -1
        }

        private class Entry(val id: String, val version: Int, val length: Int, val crc: Int)

        private class Table(
            val gameId: String,
            val count: Int,
            val dataStart: Int,
            private val entries: List<Entry>,
        ) {
            fun entry(i: Int): Entry = entries[i]
        }

        private fun parseTable(src: ByteArray, offset: Int, length: Int): Table {
            if (offset < 0 || length < 0 || offset + length > src.size) {
                throw SaveException.Corrupt("bad range")
            }
            val end = offset + length
            var p = offset
            fun need(n: Int) {
                if (p + n > end) throw SaveException.Corrupt("truncated")
            }
            need(FIXED_HEADER + 1)
            if (src[p] != 'G'.code.toByte() || src[p + 1] != 'B'.code.toByte() ||
                src[p + 2] != 'F'.code.toByte() || src[p + 3] != 'S'.code.toByte()
            ) {
                throw SaveException.Corrupt("bad magic")
            }
            val version = getU16(src, p + 4)
            if (version > SaveFormat.CURRENT_VERSION) {
                throw SaveException.NewerVersion("container", version, SaveFormat.CURRENT_VERSION)
            }
            if (version < 1) throw SaveException.Corrupt("bad container version")
            val count = getU16(src, p + 6)
            p += 8
            fun string(): String {
                need(1)
                val n = src[p].toInt() and 0xFF
                need(1 + n)
                val chars = CharArray(n) { (src[p + 1 + it].toInt() and 0xFF).toChar() }
                p += 1 + n
                return chars.concatToString()
            }
            val gameId = string()
            val entries = ArrayList<Entry>(count)
            var total = 0L
            for (i in 0 until count) {
                val id = string()
                need(ENTRY_FIXED)
                val v = getU16(src, p)
                val len = getU32(src, p + 2)
                val crc = getU32(src, p + 6)
                p += ENTRY_FIXED
                if (len < 0) throw SaveException.Corrupt("section $id too large")
                total += len
                entries.add(Entry(id, v, len, crc))
            }
            need(CRC)
            if (Crc32.compute(src, offset, p - offset) != getU32(src, p)) {
                throw SaveException.Corrupt("header checksum")
            }
            p += CRC
            if (end - p < total) throw SaveException.Corrupt("truncated payload")
            return Table(gameId, count, p, entries)
        }

        private fun putU16(b: ByteArray, p: Int, v: Int): Int {
            b[p] = v.toByte()
            b[p + 1] = (v ushr 8).toByte()
            return p + 2
        }

        private fun putU32(b: ByteArray, p: Int, v: Int): Int {
            b[p] = v.toByte()
            b[p + 1] = (v ushr 8).toByte()
            b[p + 2] = (v ushr 16).toByte()
            b[p + 3] = (v ushr 24).toByte()
            return p + 4
        }

        private fun putString(b: ByteArray, p: Int, s: String): Int {
            b[p] = s.length.toByte()
            for (i in 0 until s.length) b[p + 1 + i] = s[i].code.toByte()
            return p + 1 + s.length
        }

        private fun getU16(b: ByteArray, p: Int): Int =
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

        private fun getU32(b: ByteArray, p: Int): Int =
            getU16(b, p) or (getU16(b, p + 2) shl 16)
    }
}
