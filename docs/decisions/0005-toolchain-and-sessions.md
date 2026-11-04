# 0005 — Toolchain: sessions are JVM-only, CI builds Android

**Status.** Accepted. Amended 2026-11-04: applies to scheduled runs; owner-PC (WSL) sessions have the SDK (brief Amendments).

**Context.** Sessions must start in < 3 min without the Android SDK; CI builds the APK. Sessions get 403 from the
Gradle plugin portal and intermittent 404/429 from Maven Central.

**Decision.**
- Versions in `gradle/libs.versions.toml` (Gradle 8.14.3, Kotlin 2.2.21, AGP 8.13.2); JDK 21, JVM 17 bytecode.
- `:app` is included only with an Android SDK; AGP/Compose/Dokka load only via `fe8.android` / `fe8.docs`.
- No plugin portal: plugin ids mapped to Maven modules in `pluginManagement`; no `build-logic`/`kotlin-dsl`.
- Sessions use `tools/maven_mirror.py` (`FE8_MAVEN_MIRROR`), seeded by `tools/setup.sh` from the ROM repo
  release's `maven-cache.tar`; CI uses Maven repos directly.
- `tools/setup.sh`: kotlinc, ROM-repo assets, Maven cache, Gradle warm-up.

**Consequences.** Dependency changes must also reach the ROM repo's `maven-cache.tar`, or sessions fall back to
slow Maven Central fetches.
