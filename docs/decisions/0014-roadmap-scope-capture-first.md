# 0014 — Roadmap: scope exclusion, player-visible milestones, capture-first tasks

**Status.** Accepted (owner, 2026-11-11).

**Context.** A review of the forward plan found: nothing planned past the Prologue opening; tasks scoped by source
file, so whole files were ported that no capture runs (written, unverified, uncounted); verified work not reaching
the phone; tasks ending "blocked: no reference run reaches it". The progress bar also counted code a phone can
never run.

**Decision.**
1. **Scope:** link-cable play (`sio_*`) and the debug menus (`uidebug.c`, `bmdebug.c`, `bmcommanddbg.c`,
   `mapanim_debug.c`) are out of scope: shown separately, not in the percentage (`tools/progress.py`
   `OUT_OF_SCOPE_*`). A function with a retail caller stays in scope (`KEEP_IN_SCOPE`,
   `tools/check_scope_callers.py`).
2. **Milestones:** M6 becomes M6-A…M6-J, each with a device-testable exit; M7 = modding surface
   ([ROADMAP](../plans/ROADMAP.md)).
3. **Two lanes** in parallel: A = opening wired, B = player phase. Battle animation starts after the Prologue is
   playable (M6-G); combat resolves instantly until then.
4. **Capture-first task kinds:** CAP (new capture, ROM repo), INV (inventory of one capture window), PORT (one
   behaviour, one window, ≤ ~8 functions, only functions the window runs), WIRE (into the game, headless test,
   device check). Every milestone ends with a WIRE task. A lane with fewer than 5 open rows gets the next milestone
   broken into rows.
5. **UNVERIFIED:** every `Transcribed from` entry carries `(not verified: reason)`; `tools/unverified.py`
   generates [UNVERIFIED](../plans/UNVERIFIED.md) and CI checks it.

**Consequences.** The percentage no longer counts code a phone can never run. Captures are
planned work, not blockers. TASKS holds open rows only. Pace depends on captures. Supplements 0006 (what counts as
"ported" is unchanged).
