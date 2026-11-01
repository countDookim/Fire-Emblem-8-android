package fe8.game

import fe8.game.opanim.OpAnimHs
import gbafe.render.SpriteObject

/**
 * Read access to ROM data by GBA address (0x08xxxxxx), backed by the whole ROM image or by the
 * extracted windows of it that the boot screens use ([BOOT_WINDOWS]). Lets the same screen setup
 * code run from a ROM (tests, later on-device import) or from extracted files (debug APKs).
 */
public class RomView(
    /** Segments as (GBA start address, bytes); an address is read from the segment containing it. */
    private val segments: List<Pair<Int, ByteArray>>,
) {
    /** The compressed blob (or raw data) starting at [at]. */
    public fun blob(at: Int): OpAnimHs.Blob {
        val (base, b) = segment(at)
        return OpAnimHs.Blob(b, at - base)
    }

    /** The unsigned byte at [at]. */
    public fun u8(at: Int): Int {
        val (base, b) = segment(at)
        return b[at - base].toInt() and 0xFF
    }

    /** The unsigned halfword at [at]. */
    public fun u16(at: Int): Int {
        val (base, b) = segment(at)
        val o = at - base
        return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }

    /** The word at [at]. */
    public fun u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)

    /** [n] BGR555 colours (or halfwords) from [at]. */
    public fun pal(at: Int, n: Int): ShortArray = ShortArray(n) { u16(at + 2 * it).toShort() }

    /** The sprite object at [at] holding [halfwords] halfwords. */
    public fun obj(at: Int, halfwords: Int): SpriteObject = SpriteObject(at, pal(at, halfwords))

    /** The sprite object at [at] sized by its count (`1 + 3 * count` halfwords). */
    public fun objN(at: Int): SpriteObject = obj(at, 1 + 3 * u16(at))

    private fun segment(at: Int): Pair<Int, ByteArray> = segments.firstOrNull { (base, b) ->
        at.toUInt() >= base.toUInt() && (at - base).toUInt() < b.size.toUInt()
    } ?: throw IllegalArgumentException("ROM address 0x%08X not in this view".format(at))

    /** Factories. */
    public companion object {
        /** GBA address of ROM byte 0. */
        public const val ROM_BASE: Int = 0x08000000

        /**
         * ROM ranges (start, end exclusive) holding all boot screen data (H&S, logos, op anim,
         * title): sprite objects, then the screen graphics. Extracted as `boot/rom_<start>.bin`.
         */
        public val BOOT_WINDOWS: List<Pair<Int, Int>> = listOf(
            0x08590F00 to 0x08591000,
            0x08AA6000 to 0x08B13000,
        )

        /**
         * ROM range (start, end exclusive) of all m4a sound data: the driver tables
         * (`gScaleTable`..`gXcmdTable`), voicegroups, `gMPlayTable`, `gSongTable`, songs and
         * DirectSound samples (`gScaleTable` .. after `song998`). Extracted as `sound/rom_<start>.bin`
         * for debug builds' sound tests.
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
        public fun windowFile(start: Int): String = "rom_%08X.bin".format(start)
    }
}
