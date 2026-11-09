package gbafe.extract

import java.io.File
import kotlin.system.exitProcess

/**
 * Parsed command line options and arguments for the ROM asset extractor CLI.
 *
 * Kept as a standalone plain data class without external CLI library dependencies so the tool
 * can run in restricted cloud and offline CI environments without Gradle plugin portal access.
 */
public data class ExtractArgs(
    /** The original game ROM file (`baserom.gba`), verified against SHA1 checksums. */
    val rom: File,
    /** Optional path to a checkout of the fireemblem8u decomp, used for layouts and enum names. */
    val decomp: File?,
    /** Optional linker map file (`fireemblem8.map`) providing ROM addresses of data symbols. */
    val map: File?,
    /** Output directory destination; must reside outside the code repository root. */
    val out: File,
    /** Optional `nm` symbol list (`fireemblem8.nm.txt`) with sizes for locating data tables. */
    val symbols: File? = null,
    /** Which game layouts to use (one of [GAMES]; defaults to `"fe8"`). */
    val game: String = "fe8",
    /** Optional file destination to serialize queried decomp symbols/enums as a JSON manifest. */
    val writeManifest: File? = null,
    /** Optional pre-recorded manifest file used in place of `--symbols` and `--decomp`. */
    val manifest: File? = null,
)

/**
 * Exception thrown when invalid CLI arguments, missing values, or missing required flags are encountered.
 *
 * [message] describes the syntax violation and is displayed alongside [USAGE].
 */
public class UsageException(message: String) : IllegalArgumentException(message)

/** Help text printed by `--help` and upon CLI usage errors. */
public const val USAGE: String = """usage: extract --rom <baserom.gba> --out <dir> [--decomp <fireemblem8u>] [--map <fireemblem8.map>] [--symbols <fireemblem8.nm.txt>] [--game fe8] [--write-manifest <file>] [--manifest <file>]

Extracts Fire Emblem 8 assets from the ROM into moddable formats (JSON, PNG, Tiled, MIDI).
  --rom     original ROM (never copied, never committed to the code repo)
  --out     output directory; must be OUTSIDE the code repository (ROM-derived data goes to the ROM repo)
  --decomp  fireemblem8u checkout (table layouts, names)
  --map     fireemblem8.map linker map (ROM addresses of data symbols)
  --symbols fireemblem8.nm.txt symbol list with sizes (enables data tables: data/items.json)
  --game    which game the ROM is (default fe8; only fe8 so far)
  --write-manifest  also write the decomp answers used (symbols, names) as a JSON manifest
  --manifest  use this extraction manifest instead of --symbols/--decomp
  --help    show this text"""

/**
 * Parses [argv] (`--key value` pairs) into an [ExtractArgs] instance.
 *
 * Returns null if `--help` or `-h` was requested.
 *
 * @throws UsageException on unrecognized flags, missing argument values, or missing required options.
 */
public fun parseArgs(argv: Array<String>): ExtractArgs? {
    if ("--help" in argv || "-h" in argv) return null
    val values = HashMap<String, String>()
    var index = 0
    while (index < argv.size) {
        val key = argv[index]
        if (key !in KNOWN_OPTIONS) {
            throw UsageException("unknown argument '$key'")
        }
        val value = argv.getOrNull(index + 1) ?: throw UsageException("$key needs a value")
        values[key] = value
        index += 2
    }
    fun requireFile(key: String): File =
        File(values[key] ?: throw UsageException("missing $key"))

    val gameChoice = values["--game"] ?: "fe8"
    if (gameChoice !in GAMES) {
        throw UsageException("unsupported game '$gameChoice' (known: ${GAMES.joinToString()})")
    }

    return ExtractArgs(
        rom = requireFile("--rom"),
        decomp = values["--decomp"]?.let(::File),
        map = values["--map"]?.let(::File),
        out = requireFile("--out"),
        symbols = values["--symbols"]?.let(::File),
        writeManifest = values["--write-manifest"]?.let(::File),
        manifest = values["--manifest"]?.let(::File),
        game = gameChoice,
    )
}

/**
 * Enforces the repository policy against committing ROM or extracted binary assets into the code tree.
 *
 * Compares canonical paths to prevent relative paths (`..`) or symlinks from bypassing the check.
 *
 * @throws UsageException if [out] resolves within [codeRepo] or matches it directly.
 */
public fun checkOutsideCodeRepo(out: File, codeRepo: File) {
    val outPath = out.canonicalFile.toPath()
    val repoPath = codeRepo.canonicalFile.toPath()
    if (outPath.startsWith(repoPath)) {
        throw UsageException("--out '$out' is inside the code repository ($repoPath); write to the ROM repo instead")
    }
}

/**
 * Locates the root directory of the code repository by checking the `extract.codeRepo` system
 * property (passed by Gradle execution tasks) or walking up directory ancestors until both
 * `settings.gradle.kts` and `CLAUDE.md` are found.
 */
public fun locateCodeRepo(): File? {
    System.getProperty("extract.codeRepo")?.let { return File(it) }
    var currentDir: File? = File("").absoluteFile
    while (currentDir != null) {
        if (File(currentDir, "settings.gradle.kts").isFile && File(currentDir, "CLAUDE.md").isFile) {
            return currentDir
        }
        currentDir = currentDir.parentFile
    }
    return null
}

/** Supported game identifiers recognized by `--game`. */
public val GAMES: Set<String> = setOf("fe8")

/** Recognized CLI option flags. */
private val KNOWN_OPTIONS: Set<String> = setOf(
    "--rom",
    "--decomp",
    "--map",
    "--out",
    "--symbols",
    "--game",
    "--write-manifest",
    "--manifest",
)

/** Exit code returned on successful execution or help output. */
private const val EXIT_SUCCESS: Int = 0

/** Exit code returned on command-line usage or configuration errors. */
private const val EXIT_USAGE_ERROR: Int = 2

/**
 * Dispatches extraction pipelines for the specified [ExtractArgs.game] and returns relative paths
 * of all generated asset files.
 */
public fun runSteps(args: ExtractArgs): List<String> = when (args.game) {
    "fe8" -> gbafe.extract.fe8.runFe8Steps(args)
    else -> throw UsageException("unsupported game '${args.game}'")
}

/**
 * CLI application entry point.
 *
 * Exit codes:
 * - [EXIT_SUCCESS] (0): extraction succeeded or `--help` printed.
 * - [EXIT_USAGE_ERROR] (2): invalid arguments, unknown flags, or missing input ROM file.
 */
public fun main(argv: Array<String>) {
    val args = try {
        parseArgs(argv)?.also { parsed ->
            locateCodeRepo()?.let { repo -> checkOutsideCodeRepo(parsed.out, repo) }
        }
    } catch (e: UsageException) {
        System.err.println("error: ${e.message}\n\n$USAGE")
        exitProcess(EXIT_USAGE_ERROR)
    }
    if (args == null) {
        println(USAGE)
        return
    }
    if (!args.rom.isFile) {
        System.err.println("error: ROM not found: ${args.rom}")
        exitProcess(EXIT_USAGE_ERROR)
    }
    args.out.mkdirs()
    val written = runSteps(args)
    if (written.isEmpty()) {
        println("extract: nothing to do (pass --symbols to extract data tables)")
    }
    for (path in written) {
        println("wrote ${File(args.out, path).path}")
    }
}
