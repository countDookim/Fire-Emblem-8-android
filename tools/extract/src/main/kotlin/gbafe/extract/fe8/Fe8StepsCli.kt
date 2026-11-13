package gbafe.extract.fe8

import gba.extract.Json
import gba.extract.Symbols
import gbafe.extract.DecompSources
import gbafe.extract.ExtractArgs
import gbafe.extract.FileOutput
import gbafe.extract.ManifestSources
import gbafe.extract.RecordingSources

/**
 * Command-line wrapper around the on-device extraction core [runFe8Steps]: picks the decomp
 * answers source from [args], runs every FE8 step, and returns one description line per group of
 * files written under [ExtractArgs.out] (as [runFe8Steps] returns them; not always plain paths).
 *
 * The steps themselves, and so the full list of outputs (`data/`, `maps/`, `text/`, `fonts/`,
 * `portraits/`, `mapsprites/`, `scripts/`, `songs/`, `samples/`, `ui/`, `boot/`, `sound/`), are
 * in [runFe8Steps] in `engine:extract`.
 *
 * Input resolution precedence:
 * 1. If `--manifest` is provided, sources are read from the pre-recorded [ManifestSources].
 * 2. Otherwise, if `--symbols` is provided, sources are loaded from the `nm` symbol list and the
 *    optional `--decomp` checkout via [DecompSources].
 * 3. If neither is provided, no extraction steps can execute and an empty list is returned.
 *
 * When `--write-manifest` is given, a [RecordingSources] wrapper records every symbol, enum and
 * face answer the steps asked for and writes them as a manifest JSON file, which `--manifest`
 * (and the app's on-device extraction) can later use without the decomp.
 */
public fun runFe8Steps(args: ExtractArgs): List<String> {
    val decomp = args.manifest?.let { ManifestSources(it.readText()) }
        ?: args.symbols?.let { DecompSources(Symbols(it.readLines()), args.decomp) }
        ?: return emptyList()
    val recording = args.writeManifest?.let { RecordingSources(decomp) }
    val written = runFe8Steps(args.rom.readBytes(), recording ?: decomp, FileOutput(args.out))
    if (recording != null) {
        args.writeManifest.writeText(Json.write(recording.manifest()))
    }
    return written
}
