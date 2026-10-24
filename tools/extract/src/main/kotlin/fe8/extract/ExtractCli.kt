package fe8.extract

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
)

/** Thrown for unusable command lines; [message] is shown to the user together with the usage text. */
public class UsageException(message: String) : IllegalArgumentException(message)

/** Help text printed by `--help` and on usage errors. */
public const val USAGE: String = """usage: extract --rom <baserom.gba> --out <dir> [--decomp <fireemblem8u>] [--map <fireemblem8.map>]

Extracts Fire Emblem 8 assets from the ROM into moddable formats (JSON, PNG, Tiled, MIDI).
  --rom     original ROM (never copied, never committed to the code repo)
  --out     output directory; must be OUTSIDE the code repository (ROM-derived data goes to the ROM repo)
  --decomp  fireemblem8u checkout (table layouts, names)
  --map     fireemblem8.map linker map (ROM addresses of data symbols)
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
        if (key !in setOf("--rom", "--decomp", "--map", "--out")) throw UsageException("unknown argument '$key'")
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
 * Finds the code repository root: the `fe8.codeRepo` system property (set by `./gradlew :tools:extract:run`),
 * else the nearest ancestor of the working directory containing `settings.gradle.kts` and `CLAUDE.md`.
 */
public fun locateCodeRepo(): File? {
    System.getProperty("fe8.codeRepo")?.let { return File(it) }
    var d: File? = File("").absoluteFile
    while (d != null) {
        if (File(d, "settings.gradle.kts").isFile && File(d, "CLAUDE.md").isFile) return d
        d = d.parentFile
    }
    return null
}

/** CLI entry point. Exit codes: 0 ok / help, 2 usage error. Extraction steps arrive with M2-02 onwards. */
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
    println("extract: no extraction steps yet (M2-02+); output dir ${args.out.canonicalPath}")
}
