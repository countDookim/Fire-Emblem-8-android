/**
 * Read-only view of the APK's `assets/` folder as a virtual-file-system layer. Since no APK bundles
 * game data (decision 0011) it is the empty fallback used before the player's ROM is imported.
 */

package fe8.app

import android.content.res.AssetManager
import gba.vfs.VfsLayer
import gba.vfs.VirtualFileSystem

/**
 * A [VfsLayer] over the APK's assets under [prefix].
 *
 * No APK bundles game data, debug builds included (decision 0011; `tools/check_no_game_content.py`
 * guards it), so on a device this layer is empty: the game reads the player's imported ROM through
 * a `DirectoryLayer` instead and uses this layer only as the fallback before an import.
 *
 * @property assets Android [AssetManager] used to open and list assets.
 * @property prefix Asset folder treated as the layer root ("extracted" by default).
 * @property origin Name of this layer in diagnostics ("base" by default).
 */
public class AssetLayer(
    private val assets: AssetManager,
    private val prefix: String = "extracted",
    override val origin: String = "base",
) : VfsLayer {
    /**
     * Whether [path] names an asset: its parent folder is listed and must contain the file name.
     * Costs one `AssetManager.list` call per probe.
     *
     * @param path Layer-relative path.
     * @return `true` if the asset exists, `false` otherwise.
     */
    override fun exists(path: String): Boolean {
        val normalizedPath = VirtualFileSystem.normalize(path)
        val lastSlashIndex = normalizedPath.lastIndexOf('/')
        val dir = if (lastSlashIndex < 0) prefix else "$prefix/${normalizedPath.substring(0, lastSlashIndex)}"
        val fileName = normalizedPath.substring(lastSlashIndex + 1)
        return assets.list(dir)?.contains(fileName) == true
    }

    /**
     * Reads the whole asset at [path].
     *
     * @param path Layer-relative path.
     * @return The file's bytes. Throws if it does not exist (`AssetManager.open` failing).
     */
    override fun read(path: String): ByteArray =
        assets.open("$prefix/${VirtualFileSystem.normalize(path)}").use { it.readBytes() }

    /**
     * Lists every file below [dir] (all depths) as layer-relative paths, sorted.
     *
     * An entry that lists no children is taken to be a file, because APK assets keep no empty
     * folders (Android behaviour, not checked here). So an empty or missing [dir] gives an empty
     * list, which is how callers see "nothing imported".
     *
     * @param dir Layer-relative folder, `""` for the whole layer.
     * @return Sorted relative paths of all files below [dir].
     */
    override fun list(dir: String): List<String> {
        val out = ArrayList<String>()

        /** Adds every file below [relativeSubPath] (relative to [prefix]) to [out]. */
        fun walk(relativeSubPath: String) {
            val fullAssetPath = if (relativeSubPath.isEmpty()) prefix else "$prefix/$relativeSubPath"
            val childNames = assets.list(fullAssetPath) ?: return
            if (childNames.isEmpty()) {
                // A leaf: APKs store no empty directories, so it is a file (no per-file exists()).
                if (relativeSubPath.isNotEmpty()) out += relativeSubPath
                return
            }
            for (childName in childNames) {
                walk(if (relativeSubPath.isEmpty()) childName else "$relativeSubPath/$childName")
            }
        }

        walk(VirtualFileSystem.normalize(dir).trimEnd('/'))
        return out.sorted()
    }
}
