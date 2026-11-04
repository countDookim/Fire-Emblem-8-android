# 0013 — Kotlin Multiplatform: targets and platform code

**Status.** Accepted 2026-11-04 (owner request 2026-10-27).

**Context.** The owner wants the libraries ready for Kotlin Multiplatform so the `gba`/`gbafe` code is not
tied to the JVM. Sessions have no Android SDK (ADR 0005), so anything needing AGP builds only in CI.

**Decision.**
- Targets: **jvm + android** (owner, 2026-10-27); no iOS/JS/native unless the owner asks. Modules start
  with `jvm()` only; the `android` target comes in a later M4-07 slice because it needs AGP, which only CI
  has (sessions compile `jvm`). Until then Android consumers (`:app`) resolve the `jvm` variant (Kotlin's
  `jvm` -> `androidJvm` compatibility rule).
- Layout: `src/commonMain` + `src/jvmTest` (tests stay JVM: JUnit 5, reference data on disk). Root
  `build.gradle.kts` has one KMP convention (explicit API, JDK toolchain, JUnit) and lists converted modules
  in `kmpModules` so `jvmClasses`/`jvmTest` pick the right tasks.
- `commonMain` uses only the Kotlin stdlib: no `java.*`/`javax.*` imports (`tools/tests/test_kmp_common.py`),
  no `System.*` (`copyInto` instead of `arraycopy`, `kotlin.concurrent.Volatile`).
- Platform code (`java.io.File`, `java.util.zip`, `MessageDigest`) goes behind `expect`/`actual` or a small
  interface with a `jvmMain` implementation, decided per module when it is converted (`:gba:vfs`,
  `:gba:extract`).
- Stay plain JVM: `:gba:testkit`, `:testing`, `:tools:extract` (test/CLI code), `:app` (Android). The
  extraction core `:engine:extract` goes to `commonMain` (owner, M4-07); its bundled manifest resource needs a
  platform reader.

**Consequences.** Hot-path code must keep zero per-frame allocations without JVM helpers; source paths moved
(`src/main` -> `src/commonMain`), no behaviour change. Dokka documents KMP modules via the same lint.
