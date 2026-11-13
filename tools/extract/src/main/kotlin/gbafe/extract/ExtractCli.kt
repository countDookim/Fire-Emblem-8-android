package gbafe.extract

import java.io.File
import kotlin.system.exitProcess

/**
 * Parsed command line of the ROM extractor CLI (see [USAGE]).
 *
 * Plain data class parsed by hand (no CLI library dependency), so the tool builds in offline
 * and restricted CI environments.
 */
public data class ExtractArgs(
    /**
     * The original game ROM file (`baserom.gba`). The CLI does not check its SHA-1; only the
     * on-device importer does (`Fe8Import.ROM_SHA1`).
     */
    val rom: File,
    /** Optional fireemblem8u decomp checkout, read for enum names and face ids. */
    val decomp: File?,
    /** `--map` value; parsed and stored, but no extraction step reads it (see [symbols]). */
    val map: File?,
    /** Output directory; [main] rejects one inside the code repository (ROM-derived data). */
    val out: File,
    /** `nm` symbol list (`fireemblem8.nm.txt`): addresses and sizes of the data tables. */
    val symbols: File? = null,
    /** Which game's steps to run (one of [GAMES]; defaults to `"fe8"`). */
    val game: String = "fe8",
    /** If set, the decomp answers the steps used are also written here as a JSON manifest. */
    val writeManifest: File? = null,
    /** A manifest written by `--write-manifest`; takes precedence over [symbols] and [decomp]. */
    val manifest: File? = null,
)

/**
 * Thrown for a bad command line (unknown flag, flag without value, missing `--rom`/`--out`,
 * unsupported game, or `--out` inside the code repository); [main] prints [message] with [USAGE].
 */
public class UsageException(message: String) : IllegalArgumentException(message)

/**
 * Help text printed by `--help` and upon CLI usage errors. The `--map` and `--symbols` lines are
 * dated: no step reads the map, and `--symbols` (or `--manifest`) is needed for every step, not
 * only data tables.
 */
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
 * @throws UsageException on an unknown flag, a flag without a value, a missing `--rom` or `--out`,
 *   or an unsupported `--game`.
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
 * Enforces the repo rule that nothing extracted from the ROM goes into the code repository.
 *
 * Compares canonical paths, so `..` and symlinks cannot get around the check.
 *
 * @throws UsageException if [out] is [codeRepo] or inside it.
 */
public fun checkOutsideCodeRepo(out: File, codeRepo: File) {
    val outPath = out.canonicalFile.toPath()
    val repoPath = codeRepo.canonicalFile.toPath()
    if (outPath.startsWith(repoPath)) {
        throw UsageException("--out '$out' is inside the code repository ($repoPath); write to the ROM repo instead")
    }
}

/**
 * Finds the code repository root: the `extract.codeRepo` system property (set by the Gradle run
 * task) or the nearest ancestor of the working directory holding both `settings.gradle.kts` and
 * `CLAUDE.md`; null if neither, in which case [main] skips the outside-the-repo check.
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

/** The `--key` options [parseArgs] accepts, each taking one value. */
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
 * Runs the steps of [ExtractArgs.game] and returns what they report as written (see
 * `runFe8Steps`; empty if no symbol source was given).
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
 * - [EXIT_USAGE_ERROR] (2): bad arguments (printed with [USAGE]) or a missing ROM file.
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
