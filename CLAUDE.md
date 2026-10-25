# Instructions for Claude sessions

1. Read `HANDOFF.md` first (one screen). Follow the Schedule section of `docs/PROJECT_BRIEF.md`.
2. Run `tools/setup.sh` (idempotent, logs its duration), then `source .assets.env`.
3. Tests: `./gradlew jvmTest` (JVM only; never install the Android SDK in a session).
4. Never commit the ROM or anything extracted from it. ROM-derived data goes to the ROM repo only.
5. A function is "ported" only with a `Ported from <file>: <Func>` KDoc AND a passing `@VerifiesPort` test
   against ground truth (docs/decisions/0006-verification-policy.md). Never inflate progress.
6. Default branch is `master`. Commit + push after every completed step; unfinished work on `wip/<task-id>`.
7. Product = 1:1 original; modding = extension points only (docs/decisions/0009). Code is split for reuse by
   FE6/FE7 ports: game-neutral `gbafe.*` vs FE8-only `fe8.*` (docs/decisions/0010); never use `fe8` from `gbafe`.
