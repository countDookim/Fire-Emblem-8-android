# 0013 — Kotlin Multiplatform: targets and platform code

**Status.** Accepted 2026-11-04 (owner request 2026-10-27, M4-07).

**Context.** The owner wants the libraries ready for Kotlin Multiplatform so the `gba`/`gbafe` code is not
tied to the JVM. Sessions have no Android SDK (ADR 0005), so anything needing AGP builds only in CI.

**Decision.**
- Targets: **jvm + android** (owner, 2026-10-27); no iOS/JS/native unless the owner asks. Every KMP module
  has `jvm()`; the `android` target is added by the root convention only when an SDK is present (CI,
  `fe8.android`), with AGP's KMP library plugin (`com.android.kotlin.multiplatform.library`, namespace
  `io.github.countdookim.fe8` + project path, compileSdk 36, minSdk 26, JVM 17). It is applied by id and
  configured through Groovy property access because AGP types are off the script classpath in sessions, which
  therefore still build only `jvm`. No android host/device tests: tests stay in `jvmTest`. `:app` now
  resolves the android variants (it used the `jvm` ones through Kotlin's compatibility rule; CI run 315).
- Layout: `src/commonMain` + `src/jvmTest` (tests stay JVM: JUnit 5, reference data on disk). Root
  `build.gradle.kts` has one KMP convention (explicit API, JDK toolchain, JUnit) and lists converted modules
  in `kmpModules` so `jvmClasses`/`jvmTest` pick the right tasks. `src/jvmMain` (Kotlin and resources) is
  also compiled into the android target: the convention adds both directories to `androidMain`, so `actual`s
  and JVM-only classes exist on Android without duplication, an intermediate source set, or a changed
  default hierarchy. `jvmMain` code must therefore stay Android-compatible (`java.io`, `java.util.zip`,
  `Class.getResource`). Dokka suppresses `androidMain` because it repeats the `jvmMain` documentation.
- `commonMain` uses only the Kotlin stdlib: no `java.*`/`javax.*` imports (`tools/tests/test_kmp_common.py`),
  no `System.*` (`copyInto` instead of `arraycopy`, `kotlin.concurrent.Volatile`).
- Platform code (`java.io.File`, `java.util.zip`, `MessageDigest`) goes behind `expect`/`actual` or a small
  interface with a `jvmMain` implementation, decided per module when it is converted (`:gba:vfs`,
  `:gba:extract`).
- Stay plain JVM: `:gba:testkit`, `:testing`, `:tools:extract` (test/CLI code), `:app` (Android).

**Consequences.** Hot-path code must keep zero per-frame allocations without JVM helpers; source paths moved
(`src/main` -> `src/commonMain`), no behaviour change. Dokka documents KMP modules via the same lint.
