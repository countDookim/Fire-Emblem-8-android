package gbafe.extract.fe8

import gbafe.extract.ManifestSources
import gbafe.extract.RomImporter

/** FE8 (U) import settings (docs/decisions/0011). */
public object Fe8Import {
    /** SHA-1 of Fire Emblem: The Sacred Stones (U). */
    public const val ROM_SHA1: String = "c25b145e37456171ada4b0d440bf88a19f4d509f"

    /** Bump when extractor output changes (forces re-extraction on device). */
    public const val FORMAT_VERSION: Int = 1

    /** Importer running [runFe8Steps] from the bundled manifest ([ManifestSources.bundled]). */
    public fun importer(): RomImporter {
        val symbols = ManifestSources.bundled("fe8")
            ?: throw IllegalStateException("extraction manifest missing")
        return RomImporter(ROM_SHA1, FORMAT_VERSION) { rom, out -> runFe8Steps(rom, symbols, out) }
    }
}
