# Session usage log

One line per session (scheduled or interactive), appended before its final push (owner, 2026-10-27).
- **Tokens (counter):** this session's token counter, start minus end, summed over turns (counts processing, not
  billing; `~` = estimate). Sessions can't see the account's weekly % or billed totals.
- **Minutes:** wall clock from start to last push. **Weekly %:** only if the owner stated it, else `—`.
Keep the totals line below the table current.

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
| 2026-10-29 06:50 | `session_01NGGw9Q1SuZxnUVKotcVNeC` | scheduled | ~30 | ~150,000 | — | M4-10 (a)..(d): whole title proc + ColorFade/FadeCore verified, GameControl starts the real title, ram_trace_title_fade; 179 -> 204 verified |
| 2026-10-29 15:17 | `session_01NGGw9QAQfXPMFE2Jfjg2A3` | scheduled | ~25 | ~138,000 | — | M4-10 (e): OamBuffer + production HardwareScene; idle title, boot logos and 150+ op anim frames drawn from our state match mGBA; EarlyStartUi palette-sync bug fixed; 204 -> 208 verified |
| 2026-10-29 22:51 | `session_01NGGw9N4VQvPhrtUPhJ2L5N` | scheduled | ~25 | ~133,000 | — | M4-11 partial: allocation-free HardwareSceneLoader, RomView/BootRom, Fe8Boot (H&S+logos match mGBA from power-on; op anim+title wired, diverge), Fe8Game(rom) + debug APK boot windows; GameLoop alloc bug fixed |
| 2026-10-30 06:52 | `session_01NGGw9PqPBBpbSTMPeUbbVo` | scheduled | ~24 | ~160,000 | — | M4-11: measured step overruns (missedVBlanks) -> op anim + title lag frames (27) and 239 frames match mGBA on the device path; HBlank on device + per-line BLDCNT; darken gap located |
| 2026-10-30 14:12 | `session_01NGGw9PqPBBpbSTMPeUbbVo` | scheduled | ~22 | ~150,000 | — | md files condensed (TASKS, brief, HANDOFF, CLAUDE, USAGE); M3-15 touch buttons; darken fixed (mGBA packed rounding) → op anim/title pixel-exact but f1180; 208 → 209 verified |

Totals recorded so far: ~1,963,000 tokens (counter), 2026-10-27 onward only.
