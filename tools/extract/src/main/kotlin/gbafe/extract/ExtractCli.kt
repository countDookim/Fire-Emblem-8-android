package gbafe.extract

import java.io.File
import kotlin.system.exitProcess

/**
 * Parsed command line of the extractor.
 *
 * Kept as a plain data class (no CLI library) so the tool has no dependencies beyond the engine modules
 * and runs in cloud sessions, which cannot reach the Gradle plugin portal.
 */
public data class ExtractArgs(
    /** The original ROM (`baserom.gba`, SHA1-checked by later extract steps). */
    val rom: File,
    /** Checkout of the pinned decomp (fireemblem8u), used for table layouts and names. */
    val decomp: File?,
    /** Linker map (`fireemblem8.map`) giving ROM addresses of data symbols. */
    val map: File?,
    /** Output directory; must lie outside the code repository. */
    val out: File,
    /** `nm` symbol list (`fireemblem8.nm.txt`, with sizes) locating data tables; enables table steps. */
    val symbols: File? = null,
)

/** Thrown for unusable command lines; [message] is shown to the user together with the usage text. */
public class UsageException(message: String) : IllegalArgumentException(message)

/** Help text printed by `--help` and on usage errors. */
public const val USAGE: String = """usage: extract --rom <baserom.gba> --out <dir> [--decomp <fireemblem8u>] [--map <fireemblem8.map>] [--symbols <fireemblem8.nm.txt>]

Extracts Fire Emblem 8 assets from the ROM into moddable formats (JSON, PNG, Tiled, MIDI).
  --rom     original ROM (never copied, never committed to the code repo)
  --out     output directory; must be OUTSIDE the code repository (ROM-derived data goes to the ROM repo)
  --decomp  fireemblem8u checkout (table layouts, names)
  --map     fireemblem8.map linker map (ROM addresses of data symbols)
  --symbols fireemblem8.nm.txt symbol list with sizes (enables data tables: data/items.json)
  --help    show this text"""

/**
 * Parses [argv] (`--key value` pairs). Returns null when `--help` was requested.
 *
 * @throws UsageException on unknown flags, missing values, or a missing required flag.
 */
public fun parseArgs(argv: Array<String>): ExtractArgs? {
    if ("--help" in argv || "-h" in argv) return null
    val values = HashMap<String, String>()
    var i = 0
    while (i < argv.size) {
        val key = argv[i]
        if (key !in setOf("--rom", "--decomp", "--map", "--out", "--symbols")) throw UsageException("unknown argument '$key'")
        val value = argv.getOrNull(i + 1) ?: throw UsageException("$key needs a value")
        values[key] = value
        i += 2
    }
    fun required(k: String) = File(values[k] ?: throw UsageException("missing $k"))
    return ExtractArgs(
        rom = required("--rom"),
        decomp = values["--decomp"]?.let(::File),
        map = values["--map"]?.let(::File),
        out = required("--out"),
        symbols = values["--symbols"]?.let(::File),
    )
}

/**
 * Enforces the project rule "never commit the ROM or anything extracted from it": [out] must not be
 * inside [codeRepo] (compared by canonical path, so `..` and symlinks cannot sneak around it).
 *
 * @throws UsageException if [out] is the code repo or below it.
 */
public fun checkOutsideCodeRepo(out: File, codeRepo: File) {
    val o = out.canonicalFile.toPath()
    val repo = codeRepo.canonicalFile.toPath()
    if (o.startsWith(repo)) {
        throw UsageException("--out '$out' is inside the code repository ($repo); write to the ROM repo instead")
    }
}

/**
 * Finds the code repository root: the `extract.codeRepo` system property (set by `./gradlew :tools:extract:run`),
 * else the nearest ancestor of the working directory containing `settings.gradle.kts` and `CLAUDE.md`.
 */
public fun locateCodeRepo(): File? {
    System.getProperty("extract.codeRepo")?.let { return File(it) }
    var d: File? = File("").absoluteFile
    while (d != null) {
        if (File(d, "settings.gradle.kts").isFile && File(d, "CLAUDE.md").isFile) return d
        d = d.parentFile
    }
    return null
}

/**
 * Runs the extraction steps the inputs allow and returns the paths written (relative to `args.out`).
 * Currently `data/items.json`, `data/classes.json`, `data/characters.json`, `data/terrains.json`,
 * `text/en/messages.json` and `fonts/{system,talk,special}.{png,json}`,
 * `portraits/` (sheets, chibis, mouths, cards + `portraits.json`) (need `--symbols`; ids and face names need `--decomp`).
 */
public fun runSteps(args: ExtractArgs): List<String> {
    val written = ArrayList<String>()
    val rom by lazy { args.rom.readBytes() }
    val symbols = args.symbols?.let(::Symbols) ?: return written
    val names = args.decomp?.let { DecompEnums.read(it, "include/constants/items.h", "ITEM_") } ?: emptyMap()
    val items = symbols["gItemData"]
    val json = gbafe.extract.fe8.items.ItemsExtractor.extractJson(
        rom, items.address, symbols.sizeOrSpan("gItemData") / gbafe.extract.fe8.items.ItemsExtractor.ENTRY_SIZE, names,
    )
    File(args.out, "data").mkdirs()
    File(args.out, "data/items.json").writeText(json)
    written += "data/items.json"
    val classNames = args.decomp?.let { DecompEnums.read(it, "include/constants/classes.h", "CLASS_") } ?: emptyMap()
    val classes = symbols["gClassData"]
    File(args.out, "data/classes.json").writeText(
        gbafe.extract.fe8.classes.ClassesExtractor.extractJson(
            rom, classes.address, symbols.sizeOrSpan("gClassData") / gbafe.extract.fe8.classes.ClassesExtractor.ENTRY_SIZE,
            classNames, names,
        ),
    )
    written += "data/classes.json"
    val charNames = args.decomp?.let { DecompEnums.read(it, "include/constants/characters.h", "CHARACTER_") } ?: emptyMap()
    val chars = symbols["gCharacterData"]
    File(args.out, "data/characters.json").writeText(
        gbafe.extract.fe8.characters.CharactersExtractor.extractJson(
            rom, chars.address, symbols.sizeOrSpan("gCharacterData") / gbafe.extract.fe8.characters.CharactersExtractor.ENTRY_SIZE,
            charNames, classNames,
        ),
    )
    written += "data/characters.json"
    val terrainTables = gbafe.extract.fe8.terrains.TerrainsExtractor.TABLE_PREFIXES
        .flatMap(symbols::namesWithPrefix).distinct()
        .filter { symbols.sizeOrSpan(it) == gbafe.extract.fe8.classes.ClassesExtractor.TERRAIN_COUNT }
        .associateWith { symbols[it].address }
    val terrainNames = args.decomp?.let { DecompEnums.read(it, "include/constants/terrains.h", "TERRAIN_") } ?: emptyMap()
    File(args.out, "data/terrains.json").writeText(
        gbafe.extract.fe8.terrains.TerrainsExtractor.extractJson(rom, terrainTables, terrainNames),
    )
    written += "data/terrains.json"
    val faces = args.decomp?.let { File(it, "texts/textdefs.txt") }?.takeIf { it.isFile }
        ?.let(gbafe.extract.text.MessagesExtractor::readFaces) ?: emptyMap()
    val rootPtr = symbols["gMsgHuffmanTableRoot"].address - ROM_BASE
    val rootOffset = (rom[rootPtr].toInt() and 0xFF) or ((rom[rootPtr + 1].toInt() and 0xFF) shl 8) or
        ((rom[rootPtr + 2].toInt() and 0xFF) shl 16) or ((rom[rootPtr + 3].toInt() and 0xFF) shl 24)
    val messages = gbafe.extract.text.MessagesExtractor.extract(
        rom, symbols["gMsgHuffmanTable"].address - ROM_BASE, rootOffset - ROM_BASE,
        symbols["gMsgTable"].address - ROM_BASE, symbols.sizeOrSpan("gMsgTable") / 4, gbafe.text.MessageCodec(faces = faces),
    )
    File(args.out, "text/en").mkdirs()
    File(args.out, "text/en/messages.json").writeText(gbafe.extract.text.MessagesExtractor.toJson("fe8", messages))
    written += "text/en/messages.json"
    File(args.out, "fonts").mkdirs()
    for ((name, symbol) in listOf("system" to "TextGlyphs_System", "talk" to "TextGlyphs_Talk", "special" to "TextGlyphs_Special")) {
        val glyphs = gbafe.extract.font.FontExtractor.read(rom, symbols[symbol].address - ROM_BASE)
        File(args.out, "fonts/$name.png").writeBytes(gbafe.extract.font.FontExtractor.png(glyphs))
        File(args.out, "fonts/$name.json").writeText(gbafe.extract.font.FontExtractor.toJson("fe8", name, "$name.png", glyphs))
        written += listOf("fonts/$name.png", "fonts/$name.json")
    }
    val portraits = gbafe.extract.fe8.portraits.PortraitsExtractor.extract(
        rom, symbols["portrait_data"].address - ROM_BASE,
        symbols.sizeOrSpan("portrait_data") / gbafe.extract.fe8.portraits.PortraitsExtractor.ENTRY_SIZE,
    )
    for ((path, png) in portraits.files) File(args.out, "portraits/$path").apply { parentFile.mkdirs() }.writeBytes(png)
    File(args.out, "portraits/portraits.json").writeText(portraits.json)
    written += "portraits/portraits.json (+${portraits.files.size} PNG)"
    return written
}

private const val ROM_BASE = 0x08000000

/** CLI entry point. Exit codes: 0 ok / help, 2 usage error. */
public fun main(argv: Array<String>) {
    val args = try {
        parseArgs(argv)?.also { a -> locateCodeRepo()?.let { checkOutsideCodeRepo(a.out, it) } }
    } catch (e: UsageException) {
        System.err.println("error: ${e.message}\n\n$USAGE")
        exitProcess(2)
    }
    if (args == null) {
        println(USAGE)
        return
    }
    if (!args.rom.isFile) {
        System.err.println("error: ROM not found: ${args.rom}")
        exitProcess(2)
    }
    args.out.mkdirs()
    val written = runSteps(args)
    if (written.isEmpty()) println("extract: nothing to do (pass --symbols to extract data tables)")
    for (w in written) println("wrote ${File(args.out, w).path}")
}
