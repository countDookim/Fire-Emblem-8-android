package gba.vfs

import gba.data.Crc32

/**
 * One replacement texture found for an original asset (`docs/decisions/0008-render-model.md`,
 * "Texture packs").
 *
 * @property path VFS path of the replacement image.
 * @property scale integer scale relative to the original (1..[TextureOverrides.MAX_SCALE]).
 * @property indexed true for an indexed (palette-colour-type PNG) replacement that keeps palette
 *   animation; false for a true-colour one.
 * @property paletteVariants for true-colour replacements: palette hash
 *   ([TextureOverrides.paletteHash]) → VFS path of the image to use while the asset's palette has
 *   that content.
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
    /**
     * The image to draw while the asset's palette hashes to [paletteHash]: a matching variant,
     * else [path].
     */
    public fun imageFor(paletteHash: String): String = paletteVariants[paletteHash] ?: path
}

/**
 * Asset override layer (mod groundwork): finds replacement / hi-res textures that
 * mods put next to an original asset path in the [VirtualFileSystem]. The base game ships
 * originals only, so without mods every lookup returns null and the vanilla asset is used.
 *
 * Naming convention for an original `gfx/ui/cursors.png`:
 * - `gfx/ui/cursors@2x.png`: replacement at 2× (any `@<N>x` with N in 1..[MAX_SCALE]; 1 = same
 *   size). Indexed PNG (colour type 3) = indexed replacement; any other colour type = true colour.
 * - `gfx/ui/cursors@2x.<hash>.png`: true-colour variant for the palette whose [paletteHash] is
 *   `<hash>`.
 *
 * When several candidates exist, the one in the highest-priority VFS layer wins (later mods
 * override earlier ones, as for every other file); within one layer the larger scale wins.
 * Load-time only; the software renderer never uses overrides (it is the vanilla reference; texture
 * packs are mod-only, `docs/decisions/0008-render-model.md`).
 */
public class TextureOverrides(private val vfs: VirtualFileSystem) {
    /** The override for original asset [assetPath] (e.g. `gfx/ui/cursors.png`), or null if none. */
    public fun resolve(assetPath: String): TextureOverride? {
        val normalizedPath = VirtualFileSystem.normalize(assetPath)
        require(normalizedPath.endsWith(".png")) {
            "texture overrides are looked up for .png assets: $normalizedPath"
        }
        val stem = normalizedPath.removeSuffix(".png")
        var bestPath: String? = null
        var bestScale = 0
        var bestLayerRank = -1
        // Highest layer wins; within the same layer the larger scale wins.
        for (scale in 1..MAX_SCALE) {
            val candidate = "$stem@${scale}x.png"
            val layer = vfs.resolve(candidate) ?: continue
            val layerRank = vfs.stack.indexOf(layer)
            if (layerRank > bestLayerRank || (layerRank == bestLayerRank && scale > bestScale)) {
                bestPath = candidate; bestScale = scale; bestLayerRank = layerRank
            }
        }
        val path = bestPath ?: return null
        val bytes = vfs.read(path)
        // Only the signature + IHDR (first 26 bytes) are needed to read the colour type.
        val indexed = pngColourType(if (bytes.size > 26) bytes.copyOf(26) else bytes) == PNG_INDEXED
        val dir = path.substringBeforeLast('/', "")
        val prefix = "${stem.substringAfterLast('/')}@${bestScale}x."
        val variants = HashMap<String, String>()
        for (variantPath in vfs.list(dir)) {
            if (variantPath.substringBeforeLast('/', "") != dir) continue // list() is recursive
            val fileName = variantPath.substringAfterLast('/')
            if (!fileName.startsWith(prefix) || !fileName.endsWith(".png")) continue
            val hash = fileName.removePrefix(prefix).removeSuffix(".png")
            if (HASH_RX.matches(hash)) variants[hash] = variantPath
        }
        return TextureOverride(path, bestScale, indexed, variants)
    }

    /** Constants and the palette hash used in variant file names. */
    public companion object {
        /** Largest supported replacement scale. */
        public const val MAX_SCALE: Int = 8

        /** PNG colour type 3 = indexed (palette) image. */
        private const val PNG_INDEXED = 3

        /** A palette hash as written by [paletteHash]: 8 lowercase hex digits. */
        private val HASH_RX = Regex("[0-9a-f]{8}")

        /**
         * Palette content hash for variant names: CRC-32 of the colours as little-endian BGR555
         * halfwords, 8 lowercase hex digits. [colors] holds `count` colours from [offset] (e.g. one
         * 16-colour bank).
         */
        public fun paletteHash(
            colors: ShortArray,
            offset: Int = 0,
            count: Int = colors.size - offset,
        ): String {
            val crc = Crc32()
            for (i in offset until offset + count) {
                val bgr555 = colors[i].toInt() and 0x7FFF
                crc.update(bgr555 and 0xFF)
                crc.update(bgr555 ushr 8)
            }
            return crc.value.toString(16).padStart(8, '0')
        }

        /** PNG colour type from the first 26 bytes (signature + IHDR), or -1 if not a PNG. */
        internal fun pngColourType(head: ByteArray): Int {
            val tooShort = head.size < 26
            val hasPngMagic = !tooShort && head[1] == 'P'.code.toByte() &&
                head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
            if (!hasPngMagic) return -1
            return head[25].toInt() and 0xFF
        }
    }
}
