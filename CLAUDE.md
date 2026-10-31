# Instructions for Claude sessions

1. Read `HANDOFF.md` first; follow the Schedule of `docs/PROJECT_BRIEF.md` and its Amendments (amendments win).
   Scheduled runs: ~30 min; work until ~minute 23, wrap up by ~30, never end early. Before any wrap-up step run
   `tools/run_clock.sh check` (started by setup.sh); if it fails, start the next task (on `wip/<task-id>`). No
   token/usage reporting (owner, 2026-10-31).
2. `tools/setup.sh > /tmp/setup.log 2>&1 &` (idempotent; poll the log: cold Gradle can pass 2 min), then
   `source .assets.env`.
3. Tests: `./gradlew jvmTest` (JVM only; never install the Android SDK in a session).
4. Never commit the ROM or anything extracted from it. ROM-derived data goes to the ROM repo only. Until the public
   release, **debug/test APKs may bundle the ROM repo's extracted assets** (private `latest` release, owner 2026-10-28);
   public release builds ship none (players supply their ROM, extracted on device: docs/decisions/0011).
5. A function is "ported" only with a `Ported from <file>: <Func>` KDoc AND a passing `@VerifiesPort` test
   against ground truth (docs/decisions/0006-verification-policy.md). Never inflate progress.
6. Default branch is `master`. Commit + push after every completed step; unfinished work on `wip/<task-id>`.
7. Product = 1:1 original; modding = extension points only (docs/decisions/0009). Code is split for reuse by
   FE6/FE7 ports: game-neutral `gbafe.*` vs FE8-only `fe8.*` (docs/decisions/0010); never use `fe8` from `gbafe`.
8. Token hygiene: never fetch full CI job logs; check step conclusions first, then `tail_lines` <= 80 of the one
   failing job. Prefer reading files in excerpts over whole-file dumps.
9. Save tokens, not quality: short replies, commits and HANDOFF lines; no narration. During work run only the tests for
   what changed; full `jvmTest` (+ Dokka if public API changed) once before the final push. Read excerpts, don't
   re-read what you wrote. Stop a task at its acceptance line, then start the next (runs still use their full time).
   Rule 5 is never relaxed.
10. Kotlin follows Google's Android Kotlin style guide (4-space indent, 100 columns, no wildcard imports, its naming,
    brace and KDoc rules) for code you write or change; don't mass-reformat existing code.
