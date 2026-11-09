package gbafe.extract.fe8

import gba.extract.Json
import gba.extract.Symbols
import gbafe.extract.DecompSources
import gbafe.extract.ExtractArgs
import gbafe.extract.FileOutput
import gbafe.extract.ManifestSources
import gbafe.extract.RecordingSources

/**
 * Executes all Fire Emblem: The Sacred Stones (FE8) extraction pipelines enabled by the supplied
 * CLI inputs and returns the relative file paths written to [ExtractArgs.out].
 *
 * Supported extraction outputs:
 * - Data tables (`data/items.json`, `data/classes.json`, `data/characters.json`, `data/terrains.json`,
 *   `data/chapters.json`)
 * - Map tilemaps and data (`maps/`)
 * - Scripted and system text messages (`text/en/messages.json`)
 * - System, dialogue, and special font sheets and metrics (`fonts/{system,talk,special}.{png,json}`)
 * - Boot screens and intro graphics (`boot/`)
 * - Standing and moving unit map sprites (`mapsprites/`)
 * - Character face sheets, mini mugs, mouths, eye frames, and cards (`portraits/` and `portraits.json`)
 *
 * Input resolution precedence:
 * 1. If `--manifest` is provided, sources are read from the pre-recorded [ManifestSources].
 * 2. Otherwise, if `--symbols` is provided, sources are loaded from the `nm` symbol list and the
 *    optional `--decomp` checkout via [DecompSources].
 * 3. If neither is provided, no extraction steps can execute and an empty list is returned.
 *
 * When `--write-manifest` is specified, all queried symbols and enum mappings are intercepted by
 * a [RecordingSources] wrapper and written out as a standalone manifest JSON file for headless /
 * device extraction runs.
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
