# Instructions for Claude sessions

1. Read `HANDOFF.md` first (one screen). Follow the Schedule section of `docs/PROJECT_BRIEF.md` AND its Amendments table
   (amendments win). Scheduled runs: **~30 min** budget; work until ~minute 23, wrap up by ~30, never end early.
   **Gate:** `tools/setup.sh` records the start (`tools/run_clock.sh start`). Before any wrap-up step (final full test,
   HANDOFF, USAGE row) run `tools/run_clock.sh check`; if it fails, start the next HANDOFF task instead (on
   `wip/<task-id>` if it may not finish), and check again later. Rule 9 never shortens a run (a run ended at ~17 min).
   Every session appends its token usage to `docs/USAGE.md` before its final push (format in that file).
2. Run `tools/setup.sh` (idempotent, logs its duration), then `source .assets.env`. Run it in the background with a log
   (`tools/setup.sh > /tmp/setup.log 2>&1 &`, then poll): a cold Gradle warm-up can exceed a 2-min tool timeout.
3. Tests: `./gradlew jvmTest` (JVM only; never install the Android SDK in a session).
4. Never commit the ROM or anything extracted from it. ROM-derived data goes to the ROM repo only; the APK and
   releases ship none either (players supply their ROM, extracted on device: docs/decisions/0011).
5. A function is "ported" only with a `Ported from <file>: <Func>` KDoc AND a passing `@VerifiesPort` test
   against ground truth (docs/decisions/0006-verification-policy.md). Never inflate progress.
6. Default branch is `master`. Commit + push after every completed step; unfinished work on `wip/<task-id>`.
7. Product = 1:1 original; modding = extension points only (docs/decisions/0009). Code is split for reuse by
   FE6/FE7 ports: game-neutral `gbafe.*` vs FE8-only `fe8.*` (docs/decisions/0010); never use `fe8` from `gbafe`.
8. Token hygiene: never fetch full CI job logs; check step conclusions first, then `tail_lines` <= 80 of the one
   failing job. Prefer reading files in excerpts over whole-file dumps.
9. Save tokens without lowering quality: be concise (short replies, commit messages and HANDOFF lines; no narration
   of routine steps). Don't over-verify: during work run only the tests for what changed; run the full `jvmTest`
   (+ Dokka if public API changed) once before the final push, not after every step. Don't re-read files you just
   wrote or dump large files/logs; read excerpts. Stop when a task is done: no extra polish, refactors or
   features beyond its acceptance line, then start the next task (scheduled runs still use their full budget:
   saving tokens means less waste per minute, never fewer minutes).
   Rule 5 (ground-truth `@VerifiesPort` tests) is never relaxed to save tokens.
