# 0005 — Toolchain: sessions are JVM-only, CI builds Android

**Context.** Scheduled cloud sessions must start fast (<3 min) and never install the Android SDK; CI
(GitHub Actions) builds the APK. Session networking: Gradle plugin portal artifacts are blocked (403) and
Maven Central intermittently answers 404/429 through the egress proxy.

**Decision.**
- Gradle 8.14.3 wrapper, Kotlin 2.2.21, AGP 8.13.2, JDK 21 toolchain emitting JVM 17 bytecode.
- `settings.gradle.kts` includes `:app` only when an Android SDK is present. AGP, the Compose compiler
  plugin and Dokka go on the build classpath only when needed (`fe8.android` / `fe8.docs` flags), so
  sessions resolve only the Kotlin plugin + test libraries.
- No Gradle plugin portal: plugin ids are mapped to their Maven modules in `pluginManagement`. No
  `build-logic`/`kotlin-dsl` (portal-only); JVM-module conventions live in the root `build.gradle.kts`.
- Sessions start `tools/maven_mirror.py` (local, retrying, caching) and set `FE8_MAVEN_MIRROR`; CI talks
  to Google Maven / Maven Central directly.
- `tools/setup.sh` installs kotlinc (for quick scripts; Gradle has its own compiler), fetches ROM-repo
  assets, warms Gradle.

**Consequences.** One extra moving part in sessions (the mirror). If Maven Central throttling worsens,
next step is a pre-built dependency cache tarball in the ROM repo release (`GRADLE_RO_DEP_CACHE`).
