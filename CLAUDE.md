# Instructions for Claude sessions

Two run types share `master`: **scheduled runs** (Claude Cowork task every 6 h: fresh clone, ~30 min, no Android
SDK) and **owner-PC sessions** (Claude Code in the owner's WSL, persistent `~/FE8` checkouts, SDK, owner present).
Rules marked *(scheduled)* apply only to scheduled runs; everything else applies to both.

1. Read `HANDOFF.md` first; follow the Schedule of `docs/PROJECT_BRIEF.md` and its Amendments (amendments win).
   *(scheduled)* ~30 min; work until ~minute 23, wrap up by ~30, never end early. Before any wrap-up step run
   `tools/run_clock.sh check` (started by setup.sh); if it fails, start the next task (on `wip/<task-id>`). No
   token/usage reporting (owner, 2026-10-31).
2. Setup: `tools/setup.sh > /tmp/setup.log 2>&1 &` (idempotent; poll the log: cold Gradle can pass 2 min), then
   `source .assets.env`; tests: `./gradlew jvmTest`. Scheduled runs never install the Android SDK. Owner-PC
   sessions: `git fetch` + rebase the existing checkout instead of cloning; no time box; ask the owner directly
   instead of NEEDS USER; the SDK is in `~/android-sdk` (export `ANDROID_HOME` in non-interactive shells), so `:app`
   and the android targets build there too.
3. Never commit the ROM or anything extracted from it. ROM-derived data goes to the ROM repo only. Until the public
   release, **debug/test APKs may bundle the ROM repo's extracted assets** (private `latest` release, owner 2026-10-28);
   public release builds ship none (players supply their ROM, extracted on device: docs/decisions/0011).
4. A function is "ported" only with a `Ported from <file>: <Func>` KDoc AND a passing `@VerifiesPort` test
   against ground truth (docs/decisions/0006-verification-policy.md). Never inflate progress.
5. Default branch is `master`. Commit + push after every completed step; unfinished work on `wip/<task-id>`.
6. Product = 1:1 original; modding = extension points only (docs/decisions/0009). Code is split for reuse by
   FE6/FE7 ports: game-neutral `gbafe.*` vs FE8-only `fe8.*` (docs/decisions/0010); never use `fe8` from `gbafe`.
7. Save tokens, not quality: short replies, commits and HANDOFF lines; no narration. Read excerpts, never re-read
   what you wrote. CI: check step conclusions first, then `tail_lines` <= 80 of the one failing job, never full
   logs. During work run only the tests for what changed; full `jvmTest` (+ Dokka if public API changed) once
   before the final push. Stop a task at its acceptance line, then start the next (runs still use their full
   time). Rule 4 is never relaxed.
8. Kotlin follows Google's Android Kotlin style guide (4-space indent, 100 columns, no wildcard imports, its naming,
   brace and KDoc rules) for code you write or change; don't mass-reformat existing code.
9. **Delegate coding (owner, 2026-11-03).** The session model plans and reviews; a Sonnet subagent writes the
   code. Per task: (a) read HANDOFF/TASKS and only the excerpts needed to plan; (b) split the task into small,
   self-contained coding briefs (one per `Agent` call, `model: "sonnet"`), each stating: goal, files and
   symbols to touch, exact behaviour/API (signatures, KDoc wording if public), source to port (decomp file +
   function, `Ported from` line), the test to add/run and its command, the acceptance check, and rules 3/4/6/8
   that apply; tell it not to commit, push or edit HANDOFF; (c) review the result from `git diff --stat` + the
   diff of the key hunks and the test output, not by re-reading whole files; send a fix brief to the same agent
   (`SendMessage`) rather than coding it yourself; (d) commit/push/HANDOFF yourself. Code yourself only for a
   trivial (< ~10 line) fix, when a brief failed twice, or when no subagent tool is available. Independent
   briefs may run in parallel (separate files).
10. **Claim tasks before starting (owner, 2026-11-05).** Both run types pick tasks from HANDOFF, so before writing
    code for a task: `git pull --rebase`, add a line under HANDOFF `## In progress` (`M5-xx: owner PC|scheduled,
    until ~HH:MMZ`), commit with `[skip ci]` and push. Skip tasks with an unexpired claim; an expired claim is free.
    Remove your line when the task is done or you stop (unfinished work goes to `wip/<task-id>`; say so in the line).
