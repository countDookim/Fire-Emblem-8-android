package gba.core

/**
 * Marks a test as verifying one or more ported decomp functions.
 *
 * `tools/progress.py` scans test sources for this annotation and cross-references the JUnit XML
 * results: a decomp function only counts as *ported* when (1) some main-source KDoc contains
 * `Ported from <file>: <Function>` and (2) at least one test annotated with
 * `@VerifiesPort("<Function>")` **passed** (skipped tests do not count).
 *
 * Per `docs/decisions/0006-verification-policy.md`, a verifying test must compare against ground
 * truth that does not come from our own port: mGBA reference data (differential test) or values
 * derived from the ROM. A unit test that only re-states the Kotlin logic does not qualify.
 *
 * SOURCE retention: this is read by tooling only and costs nothing at runtime.
 *
 * @property functions decomp function names exactly as listed in `reference/decomp_functions.tsv`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
public annotation class VerifiesPort(vararg val functions: String)
