# Instructions for {{assistant}} sessions

Two run types share `master`: **scheduled runs** ({{scheduled}} when configured, fresh clone, ~30 min, no Android
SDK) and **owner-PC sessions** ({{owner_session}} in the owner's WSL, persistent `~/FE8` checkouts, SDK, owner present).
Rules marked *(scheduled)* apply only to scheduled runs; everything else applies to both.

1. Read `HANDOFF.md` first; follow the Schedule of `docs/PROJECT_BRIEF.md` and its Amendments (amendments win).
   *(scheduled)* ~30 min; work until ~minute 23, wrap up by ~30, never end early. Before any wrap-up step run
   `tools/run_clock.sh check` (started by setup.sh); if it fails, start the next task (on `wip/<task-id>`). For an
   owner-timed run, stop starting new tasks at the cutoff, finish active tasks, then update HANDOFF, commit and push.
   No token/usage reporting (owner, 2026-10-31).
2. Setup: `tools/setup.sh > /tmp/setup.log 2>&1 &` for scheduled runs (idempotent; poll the log: cold Gradle can
   pass 2 min), then `source .assets.env`; tests: `./gradlew jvmTest`. Scheduled runs never install the Android SDK.
   Owner-PC sessions use `$HOME/.cache/fe8-tmp` for logs and temporary files; fetch and rebase the existing checkout
   instead of cloning: `git fetch` then `git rebase --rebase-merges origin/master`. They have no time box unless the
   owner sets one; ask the owner directly instead of NEEDS USER. The SDK is in `~/android-sdk` (export `ANDROID_HOME`
   in non-interactive shells), so `:app` and the android targets build there too.
3. Never commit the ROM or anything extracted from it, including bytes, text, glyphs, dumps or assets. ROM-derived
   data goes to the ROM repo only. **No APK bundles game data, debug/test builds included** (owner 2026-11-08,
   replaces the 2026-10-28 amendment): every build needs a ROM imported on the device (docs/decisions/0011); CI's
   `check_no_game_content.py` guards debug and release APKs. Tests read runtime `FE8_ASSETS` rather than embedding
   ROM-derived ground truth.
4. A function is "ported" only with a `Ported from <file>: <Func>` KDoc AND a passing, break-checked
   `@VerifiesPort` test against ground truth (docs/decisions/0006-verification-policy.md). Deliberately mutate the
   port, observe the test fail, restore it and rerun the test green. Never inflate progress.
5. Default branch is `master`. Commit + push after every completed step; unfinished work on `wip/<task-id>`.
6. Product = 1:1 original; modding = extension points only (docs/decisions/0009). Code is split for reuse by
   FE6/FE7 ports: game-neutral `gbafe.*` vs FE8-only `fe8.*` (docs/decisions/0010); never use `fe8` from `gbafe`.
7. Save tokens, not quality: short replies, commits and HANDOFF lines; no narration. Read excerpts, never re-read
   what you wrote. CI: check step conclusions first, then `tail_lines` <= 80 of the one failing job, never full
   logs. During work run only the tests for what changed; full `jvmTest` (+ Dokka if public API changed) once
   before the final push. Stop a task at its acceptance line, then start the next (runs still use their full
   time). Rule 4 is never relaxed. If an owner decision is needed, ask and wait before dependent edits.
8. Kotlin follows Google's Android Kotlin style guide (4-space indent, 100 columns, no wildcard imports, its naming,
   brace and KDoc rules) for code you write or change; don't mass-reformat existing code.
9. **Delegate coding (owner, 2026-11-03).** {{delegation}}
   Per task: (a) read HANDOFF/TASKS and only the excerpts needed to plan; (b) split the job into small,
   self-contained coding briefs {{brief_tool}}, each stating: goal, files and symbols to touch, exact behaviour/API
   (signatures, KDoc wording if public), source to port (decomp file + function, `Ported from` line), the test to
   add/run and its command, the acceptance check, and rules 3/4/6/8 that apply; tell the agent not to commit, push
   or edit HANDOFF; (c) review `git diff --stat`, key diff hunks and test output, not whole files; send fixes to
   the same agent {{fix_tool}} rather than coding them yourself; (d) the parent reviews, commits, cherry-picks as
   needed, removes worktrees, and handles push/HANDOFF. Put coding agents in `.claude/worktrees` and stagger launches
   by ~20 seconds. Run one coding task/agent at a time unless the owner explicitly authorizes parallel work for that
   run. Code yourself only for a trivial (< ~10 line) fix, when a brief failed twice, or when no subagent tool is
   available.
10. **Claim tasks before starting (owner, 2026-11-05).** Both run types pick tasks from HANDOFF, so before writing
    code for a task: `git fetch` and `git rebase --rebase-merges origin/master`, add a line under HANDOFF
    `## In progress` (`M5-xx: owner PC|scheduled, until ~HH:MMZ`), commit and push. Skip tasks with
    an unexpired claim; an expired claim is free. Remove your line when the task is done or you stop (unfinished work
    goes to `wip/<task-id>`; say so in the line).
11. **Readability pass (owner, 2026-11-07; owner-PC sessions only, not scheduled runs).** Every owner-PC session
    reviews at least one `todo` section of `docs/READABILITY.md` (names, KDoc, comments; no behaviour change), commits
    it and marks the row `done <date> <commit>`, until every row is done.
12. **Manual testing.** Whenever there is new manually testable behaviour, update `docs/MANUAL_TEST.md` and proactively
    tell the owner in the final response what changed, concrete steps and expected outcome, known limits, the APK build
    hash, and whether it is published or CI is still pending. Never imply an older APK includes new changes. Mark a
    device issue solved only after a reference test and owner confirmation on a device.
13. **Keep assistant instructions synchronized.** Edit `docs/AGENT_INSTRUCTIONS.template.md` or the profiles in
    `tools/sync_agent_instructions.py`, then run `python3 tools/sync_agent_instructions.py --write` and
    `python3 tools/sync_agent_instructions.py --check`. Include all three generated files in the same commit when they
    change; CI rejects drift.
