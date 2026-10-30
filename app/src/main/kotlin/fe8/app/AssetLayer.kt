package fe8.app

import android.content.res.AssetManager
import gbafe.vfs.VfsLayer
import gbafe.vfs.VirtualFileSystem
import java.io.InputStream

/**
 * A [VfsLayer] over the APK's assets under [prefix]. Debug/test builds bundle the ROM repo's
 * `extracted/` there (amendment 2026-10-28, task M4-09) as the `base` layer; release builds bundle
 * no game content (decision 0011), so this layer is then empty.
 */
public class AssetLayer(
    private val assets: AssetManager,
    private val prefix: String = "extracted",
    override val origin: String = "base",
) : VfsLayer {
    override fun exists(path: String): Boolean {
        val p = VirtualFileSystem.normalize(path)
        val slash = p.lastIndexOf('/')
        val dir = if (slash < 0) prefix else "$prefix/${p.substring(0, slash)}"
        return assets.list(dir)?.contains(p.substring(slash + 1)) == true
    }

    override fun open(path: String): InputStream =
        assets.open("$prefix/${VirtualFileSystem.normalize(path)}")

    override fun list(dir: String): List<String> {
        val out = ArrayList<String>()
        fun walk(rel: String) {
            val full = if (rel.isEmpty()) prefix else "$prefix/$rel"
            val names = assets.list(full) ?: return
            if (names.isEmpty()) {
                // A leaf: APKs store no empty directories, so it is a file (no per-file exists()).
                if (rel.isNotEmpty()) out += rel
                return
            }
            for (n in names) walk(if (rel.isEmpty()) n else "$rel/$n")
        }
        walk(VirtualFileSystem.normalize(dir).trimEnd('/'))
        return out.sorted()
    }
}
