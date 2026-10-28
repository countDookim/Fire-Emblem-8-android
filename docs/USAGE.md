# Session usage log

One row per session (scheduled run or interactive conversation), appended by that session before its final push
(owner request 2026-10-27, to track the project against the weekly usage limit).

**What a session can measure, and what it can't.** A session cannot see the account's weekly-limit percentage
or its billed token totals; only the owner's usage page in the Claude account settings shows those. What a session
*can* see is its own token counter (the "tokens left" figure of its context budget, 15,000,000 per turn). Record:

- **Tokens (counter):** tokens consumed per that counter: start-of-session value minus end-of-session value,
  summed over turns (the counter resets each turn). It counts what the model processed, not billing; cached
  re-reads may be weighted differently in the weekly limit. Mark estimated values with `~`.
- **Minutes:** wall-clock from the session's start to its last push.
- **Weekly %:** only if the owner stated it in the conversation (sessions cannot read it); else `—`.

Keep each row to one line; add the totals line below the table up to date.

| Date (UTC) | Session | Kind | Minutes | Tokens (counter) | Weekly % | Work |
|---|---|---|---|---|---|---|
| 2026-10-24 → 27 | earlier sessions | session 1 + 8 scheduled runs | — | not recorded | — | M1–M3, start of M4 (before this log existed) |
| 2026-10-27 06:51 | `session_01NGGw9QdfFkmB74pCTyfRrp` | scheduled run | 37 | ~263,000 | — | proc engine, intro steps, logos, TmApplyTsa, time.c, TrigTable; 19 → 42 verified |
| 2026-10-27 09:30–11:25 | `session_01NGGw9QdfFkmB74pCTyfRrp` | interactive (same session, owner questions) | — | ~85,000 | 58% (owner, before this row) | CI check, ADR 0011 (player-supplied ROM), M4-06 GBA library + M4-07 KMP planned, 30-min runs, this log, CLAUDE.md token rule |
| 2026-10-27 14:53 | `session_01NGGw9NS5vR8Xyx9jdNSaNi` | scheduled run | ~20 | ~120,000 | — | M4-02 screen loads (BgHardware, SetupBackgrounds, Decompress, HBlank handlers, FlushBackgrounds), intro proc from its start vs boot trace, lag-frame finding; 42 → 58 verified |
| 2026-10-27 15:15 | `session_01NGGw9NS5vR8Xyx9jdNSaNi` | interactive (same session, owner question) | ~4 | ~6,000 | — | why the run ended early; `tools/run_clock.sh` wrap-up gate (CLAUDE.md, HANDOFF, setup.sh) |
| 2026-10-27 15:25 | `session_01NGGw9NS5vR8Xyx9jdNSaNi` | interactive (same session, owner request) | ~2 | ~4,000 | — | CLAUDE.md rule 10 + brief amendment: Android Kotlin style guide |
| 2026-10-27 22:51 | `session_01NGGw9NG2hKhUCR67jzPm4P` | scheduled | ~26 | ~190,000 | — | GameLoop, GameControl, OpAnim to f1100 (M4-08), proc helpers, M4-05 CI guard; 58 -> 79 verified |
| 2026-10-28 06:51 | `session_01NGGw9QmHeKqD6tZt5WaVqw` | scheduled | ~25 | ~157,000 | — | OpAnim f843..f1100 (Ephraim + Eirika scenes to idx 22), Interpolate, SpriteLayers, window macros; 79 -> ~105 verified |
| 2026-10-28 14:52 | `session_01NGGw9MPE1YLzu6wY3C7fir` | scheduled | ~25 | ~183,000 | — | OpAnim idx 24..114 (Eirika exit, cast scroll, sparkles, 5 face screens), 3 new reference traces; ~105 -> 153 verified |
| 2026-10-28 22:51 | `session_01NGGw9RNv1MEWHxMPDD8SzH` | scheduled | ~30 | ~224,000 | — | M4-08 done (op anim to its end), M4-09 + M3-10 (CI green), M4-10 title start (6 ports), ram_trace_title1; 157 -> 179 verified |

Totals recorded so far: ~1,232,000 tokens (counter), 2026-10-27 onward only.
