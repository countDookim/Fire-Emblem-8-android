# Instructions for GPT sessions

Two run types share `master`: **scheduled runs** (GPT automation every 6 h: fresh clone, ~30 min, no Android
SDK) and **owner-PC sessions** (Codex with GPT in the owner's WSL, persistent `~/FE8` checkouts, SDK, owner present).
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
3. Never commit the ROM or anything extracted from it. ROM-derived data goes to the ROM repo only. **No APK bundles
   game data, debug/test builds included** (owner 2026-11-08, replaces the 2026-10-28 amendment): every build needs a
   ROM imported on the device (docs/decisions/0011); CI's `check_no_game_content.py` guards debug and release APKs.
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
9. **Delegate coding (owner, 2026-11-03; GPT workflow, 2026-11-08).** One capable GPT overseer breaks down the
   job, plans small tasks and reviews the results; a smaller GPT coding agent implements each task. Required
   models (owner, 2026-11-09): GPT-6.1 Sol at high effort for planning and review; GPT-6.1 Terra at medium
   effort for coding agents (`model: "gpt-6.1-terra"`, `reasoning_effort: "medium"`). Per task:
   (a) read HANDOFF/TASKS and only the excerpts needed to plan; (b) split the job into small, self-contained
   coding briefs (one per `spawn_agent` call, explicit coding model/effort and `fork_turns: "none"`), stating:
   goal, files and symbols to touch, exact behaviour/API (signatures, KDoc wording if public), source to port (decomp file +
   function, `Ported from` line), the test to add/run and its command, the acceptance check, and rules 3/4/6/8
   that apply; tell it not to commit, push or edit HANDOFF; (c) review the result from `git diff --stat` + the
   diff of the key hunks and the test output, not by re-reading whole files; send a fix brief to the same agent
   (`followup_task`) rather than coding it yourself; (d) commit/push/HANDOFF yourself. Code yourself only for a
   trivial (< ~10 line) fix, when a brief failed twice, or when no subagent tool is available. Run only ONE
   coding task/agent at a time (owner, 2026-11-06), unless the owner explicitly permits parallel work for a run.
   For an owner-timed run, stop starting new tasks at the deadline, finish the active tasks, update HANDOFF,
   commit and push. The owner permits up to three coding agents for the 30-minute run on 2026-11-08.
10. **Claim tasks before starting (owner, 2026-11-05).** Both run types pick tasks from HANDOFF, so before writing
    code for a task: `git pull --rebase`, add a line under HANDOFF `## In progress` (`M5-xx: owner PC|scheduled,
    until ~HH:MMZ`), commit with `[skip ci]` and push. Skip tasks with an unexpired claim; an expired claim is free.
    Remove your line when the task is done or you stop (unfinished work goes to `wip/<task-id>`; say so in the line).
11. **Readability pass (owner, 2026-11-07; owner-PC GPT/Codex sessions only, not scheduled runs).** Every
    owner-PC session reviews at least one `todo` section of `docs/READABILITY.md` (names, KDoc, comments; no
    behaviour change), commits it and marks the row `done <date> <commit>`, until every row is done.
