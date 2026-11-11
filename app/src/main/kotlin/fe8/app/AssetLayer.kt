/**
 * Android APK asset virtual file system layer.
 *
 * Implements a read-only [gba.vfs.VfsLayer] on top of Android's [AssetManager], exposing bundled
 * files under a specified prefix as a virtual file hierarchy.
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
 * @property assets Android [AssetManager] used to open and list asset streams.
 * @property prefix Subdirectory within the APK asset tree (defaults to "extracted").
 * @property origin Identifier for this layer in the virtual file system (defaults to "base").
 */
public class AssetLayer(
    private val assets: AssetManager,
    private val prefix: String = "extracted",
    override val origin: String = "base",
) : VfsLayer {
    /**
     * Checks whether a file exists at the normalized [path] within this asset layer.
     *
     * @param path The virtual file system path to probe.
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
     * Reads all bytes of the asset located at [path].
     *
     * @param path The virtual file system path of the asset to read.
     * @return Raw content bytes of the asset.
     */
    override fun read(path: String): ByteArray =
        assets.open("$prefix/${VirtualFileSystem.normalize(path)}").use { it.readBytes() }

    /**
     * Recursively lists all file paths under the given virtual [dir].
     *
     * In Android APK assets, directories exist only as prefix paths of files; leaf entries
     * that list no children are treated as file leaves.
     *
     * @param dir The root virtual directory to list.
     * @return Sorted list of relative paths for all files below [dir].
     */
    override fun list(dir: String): List<String> {
        val out = ArrayList<String>()

        /** Recursively walks an asset subpath [relativeSubPath]. */
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
