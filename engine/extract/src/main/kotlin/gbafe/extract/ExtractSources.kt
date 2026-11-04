package gbafe.extract

import gba.extract.Json
import gba.extract.ManifestSymbolSources
import gba.extract.RecordingSymbolSources
import gba.extract.SymbolSources

/**
 * Everything the FE extraction steps need besides the ROM: the game-neutral [SymbolSources]
 * plus decomp face names. Behind an interface so extraction can run either from the decomp
 * ([DecompSources]) or, on device, from a committed manifest of the answers
 * (docs/decisions/0011, M4-03); [RecordingSources] produces that manifest.
 */
public interface ExtractSources : SymbolSources {
    /** Face names (`FID_*`) to ids from `texts/textdefs.txt` (empty without a decomp). */
    public fun faces(): Map<String, Int>
}

/**
 * Wraps [inner] and records every answer it gives, so [manifest] holds exactly what one
 * extraction run used (ROM names: only the addresses looked up, found or not).
 */
public class RecordingSources(private val inner: ExtractSources) :
    RecordingSymbolSources(inner), ExtractSources {
    private var faceNames: Map<String, Int>? = null

    override fun faces(): Map<String, Int> = inner.faces().also { faceNames = it }

    /** The recorded answers, with the face names last, as a JSON-ready tree (see [Json.write]). */
    override fun manifest(): MutableMap<String, Any?> = super.manifest().also {
        it["faces"] = faceNames
    }
}

/**
 * [ExtractSources] answered from a manifest written by [RecordingSources] (no decomp needed): the
 * way extraction runs on device. Asking for something the manifest lacks throws, so a step that
 * changed its queries fails loudly until the manifest is regenerated.
 */
public class ManifestSources(json: String) : ManifestSymbolSources(json), ExtractSources {
    /** Bundled manifests. */
    public companion object {
        /**
         * The committed manifest of [game] (its file name, e.g. the FE8 one) bundled as the
         * resource `gbafe/extract/manifest/<game>.json`, or null if absent.
         */
        public fun bundled(game: String): ManifestSources? =
            ManifestSources::class.java.getResource("/gbafe/extract/manifest/$game.json")
                ?.readText()?.let(::ManifestSources)
    }

    override fun faces(): Map<String, Int> =
        (root["faces"] as? Map<*, *> ?: missing("faces")).entries
            .associate { (k, v) -> k as String to (v as Long).toInt() }
}
