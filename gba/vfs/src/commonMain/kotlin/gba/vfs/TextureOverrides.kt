package gba.vfs

import gba.data.Crc32

/**
 * One replacement texture found for an original asset (ADR 0008, "Texture packs vs palette animation").
 *
 * @property path VFS path of the replacement image.
 * @property scale integer scale relative to the original (1..[TextureOverrides.MAX_SCALE]).
 * @property indexed true for an indexed (palette-colour-type PNG) replacement that keeps palette animation;
 *   false for a true-colour one.
 * @property paletteVariants for true-colour replacements: palette hash ([TextureOverrides.paletteHash]) →
 *   VFS path of the image to use while the asset's palette has that content.
 */
public data class TextureOverride(
    /** VFS path of the replacement image. */
    val path: String,
    /** Integer scale relative to the original. */
    val scale: Int,
    /** True for an indexed replacement (keeps palette animation), false for true colour. */
    val indexed: Boolean,
    /** Palette hash → VFS path of the true-colour variant for that palette. */
    val paletteVariants: Map<String, String>,
) {
    /** The image to draw while the asset's palette hashes to [paletteHash]: a matching variant, else [path]. */
    public fun imageFor(paletteHash: String): String = paletteVariants[paletteHash] ?: path
}

/**
 * Asset override layer (mod groundwork, task M3-08): finds replacement / hi-res textures that mods put next to
 * an original asset path in the [VirtualFileSystem]. The base game ships originals only, so without mods
 * every lookup returns null and the vanilla asset is used.
 *
 * Naming convention for an original `gfx/ui/cursors.png`:
 * - `gfx/ui/cursors@2x.png`: replacement at 2× (any `@<N>x` with N in 1..[MAX_SCALE]; 1 = same size).
 *   Indexed PNG (colour type 3) = indexed replacement; any other colour type = true colour.
 * - `gfx/ui/cursors@2x.<hash>.png`: true-colour variant for the palette whose [paletteHash] is `<hash>`.
 *
 * When several candidates exist, the one in the highest-priority VFS layer wins (later mods override
 * earlier ones, as for every other file), then the larger scale. Load-time only; the software renderer
 * never uses overrides (it is the vanilla reference).
 */
public class TextureOverrides(private val vfs: VirtualFileSystem) {
    /** The override for original asset [assetPath] (e.g. `gfx/ui/cursors.png`), or null if none. */
    public fun resolve(assetPath: String): TextureOverride? {
        val p = VirtualFileSystem.normalize(assetPath)
        require(p.endsWith(".png")) { "texture overrides are looked up for .png assets: $p" }
        val stem = p.removeSuffix(".png")
        var best: String? = null
        var bestScale = 0
        var bestLayer = -1
        for (n in 1..MAX_SCALE) {
            val candidate = "$stem@${n}x.png"
            val layer = vfs.resolve(candidate) ?: continue
            val rank = vfs.stack.indexOf(layer)
            if (rank > bestLayer || (rank == bestLayer && n > bestScale)) {
                best = candidate; bestScale = n; bestLayer = rank
            }
        }
        val path = best ?: return null
        val bytes = vfs.read(path)
        val indexed = pngColourType(if (bytes.size > 26) bytes.copyOf(26) else bytes) == PNG_INDEXED
        val dir = path.substringBeforeLast('/', "")
        val prefix = "${stem.substringAfterLast('/')}@${bestScale}x."
        val variants = HashMap<String, String>()
        for (name in vfs.list(dir)) {
            if (name.substringBeforeLast('/', "") != dir) continue // list() is recursive
            val file = name.substringAfterLast('/')
            if (!file.startsWith(prefix) || !file.endsWith(".png")) continue
            val hash = file.removePrefix(prefix).removeSuffix(".png")
            if (HASH_RX.matches(hash)) variants[hash] = name
        }
        return TextureOverride(path, bestScale, indexed, variants)
    }

    /** Constants and the palette hash used in variant file names. */
    public companion object {
        /** Largest supported replacement scale. */
        public const val MAX_SCALE: Int = 8

        private const val PNG_INDEXED = 3
        private val HASH_RX = Regex("[0-9a-f]{8}")

        /**
         * Palette content hash for variant names: CRC-32 of the colours as little-endian BGR555 halfwords,
         * 8 lowercase hex digits. [colors] holds `count` colours from [offset] (e.g. one 16-colour bank).
         */
        public fun paletteHash(colors: ShortArray, offset: Int = 0, count: Int = colors.size - offset): String {
            val crc = Crc32()
            for (i in offset until offset + count) {
                val c = colors[i].toInt() and 0x7FFF
                crc.update(c and 0xFF)
                crc.update(c ushr 8)
            }
            return crc.value.toString(16).padStart(8, '0')
        }

        /** PNG colour type from the first 26 bytes (signature + IHDR), or -1 if not a PNG. */
        internal fun pngColourType(head: ByteArray): Int {
            if (head.size < 26 || head[1] != 'P'.code.toByte() || head[2] != 'N'.code.toByte() || head[3] != 'G'.code.toByte()) return -1
            return head[25].toInt() and 0xFF
        }
    }
}
