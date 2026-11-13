package fe8.game

import fe8.game.opanim.OpAnimHs
import gba.data.Decompress
import gbafe.render.SpriteObject

/**
 * Read access to ROM data by GBA address (0x08xxxxxx), backed by the whole ROM image or by the
 * extracted windows of it that the boot screens use ([BOOT_WINDOWS]). Lets the same screen setup
 * code run from the whole ROM (tests) or from the windows the on-device ROM import extracted
 * (`boot/` and `sound/` of the base layer; nothing comes from the APK).
 */
public class RomView(
    /** Segments as (GBA start address, bytes); an address is read from the segment holding it. */
    private val segments: List<Pair<Int, ByteArray>>,
) {
    /** The compressed blob (or raw data) starting at [at]. */
    public fun blob(at: Int): OpAnimHs.Blob {
        val (baseAddress, bytes) = segment(at)
        return OpAnimHs.Blob(bytes, at - baseAddress)
    }

    /** The unsigned byte at [at]. */
    public fun u8(at: Int): Int {
        val (baseAddress, bytes) = segment(at)
        return bytes[at - baseAddress].toInt() and 0xFF
    }

    /** The unsigned halfword at [at]. */
    public fun u16(at: Int): Int {
        val (baseAddress, bytes) = segment(at)
        val offset = at - baseAddress
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** The little-endian 32-bit word at [at] (ROM pointers are 0x08xxxxxx addresses). */
    public fun u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)

    /** [count] BGR555 colours (or halfwords) from [at]. */
    public fun pal(at: Int, count: Int): ShortArray =
        ShortArray(count) { u16(at + 2 * it).toShort() }

    /** The sprite object at [at] holding [halfwords] halfwords. */
    public fun obj(at: Int, halfwords: Int): SpriteObject = SpriteObject(at, pal(at, halfwords))

    /** The sprite object at [at]: a halfword entry count, then 3 OAM halfwords per entry. */
    public fun objN(at: Int): SpriteObject = obj(at, 1 + 3 * u16(at))

    /**
     * Decompresses the blob at [at] (raw or LZ77, see [Decompress.into]) into [dst] at [dstOffset];
     * returns the bytes written. Does not allocate.
     */
    public fun decompressInto(at: Int, dst: ByteArray, dstOffset: Int): Int {
        val (baseAddress, bytes) = segment(at)
        return Decompress.into(bytes, at - baseAddress, dst, dstOffset)
    }

    /**
     * True when this view is the whole ROM image ([ofRom]); a view over extracted windows lacks
     * the chapter, map and unit data that New Game needs.
     */
    public val isWholeRom: Boolean
        get() = segments.size == 1 && segments[0].first == ROM_BASE

    /**
     * The whole ROM image, for code that indexes it by offset (the text system). Only valid when
     * [isWholeRom].
     */
    public fun wholeRomBytes(): ByteArray {
        check(isWholeRom) { "the ROM view is not the whole ROM" }
        return segments[0].second
    }

    /** Locates the segment containing GBA address [at]. */
    private fun segment(at: Int): Pair<Int, ByteArray> {
        for (i in segments.indices) {
            val segment = segments[i]
            val inside = at.toUInt() >= segment.first.toUInt() &&
                (at - segment.first).toUInt() < segment.second.size.toUInt()
            if (inside) return segment
        }
        throw IllegalArgumentException("ROM address 0x${hex8(at)} not in this view")
    }

    /** Factories. */
    public companion object {
        /** GBA address of ROM byte 0. */
        public const val ROM_BASE: Int = 0x08000000

        /**
         * ROM ranges (start, end exclusive) holding all boot screen data (H&S, logos, op anim,
         * title): sprite objects, then the screen graphics. Extracted by the ROM import as
         * `boot/rom_<start>.bin`.
         */
        public val BOOT_WINDOWS: List<Pair<Int, Int>> = listOf(
            0x08590F00 to 0x08591000,
            0x08AA6000 to 0x08B13000,
        )

        /**
         * ROM range (start, end exclusive) of all m4a sound data: the driver tables
         * (`gScaleTable`..`gXcmdTable`), voicegroups, `gMPlayTable`, `gSongTable`, songs and
         * DirectSound samples (`gScaleTable` .. after `song998`). Extracted by the ROM import as
         * `sound/rom_<start>.bin`.
         */
        public val SOUND_WINDOWS: List<Pair<Int, Int>> = listOf(0x08207200 to 0x08587700)

        /** A view over the whole ROM image. */
        public fun ofRom(rom: ByteArray): RomView = RomView(listOf(ROM_BASE to rom))

        /** A view over the extracted [BOOT_WINDOWS]; [read] returns a file's bytes by name. */
        public fun ofBootWindows(read: (String) -> ByteArray): RomView =
            RomView(BOOT_WINDOWS.map { (start, _) -> start to read(windowFile(start)) })

        /** A view over the extracted [SOUND_WINDOWS]; [read] returns a file's bytes by name. */
        public fun ofSoundWindows(read: (String) -> ByteArray): RomView =
            RomView(SOUND_WINDOWS.map { (start, _) -> start to read(windowFile(start)) })

        /** File name of the extracted window starting at [start]. */
        public fun windowFile(start: Int): String = "rom_${hex8(start)}.bin"
    }
}

/** 8 uppercase hex digits of the 32-bit value [value], as an unsigned number. */
private fun hex8(value: Int): String = value.toUInt().toString(16).uppercase().padStart(8, '0')
