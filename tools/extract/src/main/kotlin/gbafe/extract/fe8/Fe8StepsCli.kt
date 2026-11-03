package gbafe.extract.fe8

import gbafe.extract.DecompSources
import gbafe.extract.ExtractArgs
import gbafe.extract.FileOutput
import gbafe.extract.Json
import gbafe.extract.ManifestSources
import gbafe.extract.RecordingSources
import gbafe.extract.Symbols

/**
 * Runs the FE8 extraction steps the inputs allow and returns the paths written (relative to `args.out`).
 * Currently `data/items.json`, `data/classes.json`, `data/characters.json`, `data/terrains.json`, `data/chapters.json`, `maps/`,
 * `text/en/messages.json` and `fonts/{system,talk,special}.{png,json}`,
 * `boot/` (boot screen ROM windows), `mapsprites/` (standing + moving), `portraits/` (sheets, chibis, mouths, cards + `portraits.json`) (need `--symbols`; ids and face names need `--decomp`).
 */
public fun runFe8Steps(args: ExtractArgs): List<String> {
    val decomp = args.manifest?.let { ManifestSources(it.readText()) }
        ?: args.symbols?.let { DecompSources(Symbols(it.readLines()), args.decomp) } ?: return emptyList()
    val recording = args.writeManifest?.let { RecordingSources(decomp) }
    val written = runFe8Steps(args.rom.readBytes(), recording ?: decomp, FileOutput(args.out))
    if (recording != null) args.writeManifest.writeText(Json.write(recording.manifest()))
    return written
}
