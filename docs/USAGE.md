# Session usage log

One line per session (scheduled or interactive), appended before its final push (owner, 2026-10-27).
- **Tokens (counter):** this session's token counter, start minus end, summed over turns (counts processing, not
  billing; `~` = estimate). Sessions can't see the account's weekly % or billed totals.
- **Minutes:** wall clock from start to last push. **Weekly %:** only if the owner stated it, else `—`.
Keep the totals line below the table current.

| Date (UTC) | Session | Kind | Minutes | Tokens (counter) | Weekly % | Work |
|---|---|---|---|---|---|---|
| 2026-10-24 → 27 | earlier sessions | session 1 + 8 scheduled runs | — | not recorded | — | M1–M3, start of M4 (before this log existed) |
| 2026-10-27 06:51 | `session_01NGGw9QdfFkmB74pCTyfRrp` | scheduled run | 37 | ~263,000 | — | proc engine, intro steps, logos, TmApplyTsa, time.c, TrigTable; 19… |
| 2026-10-27 09:30–11:25 | `session_01NGGw9QdfFkmB74pCTyfRrp` | interactive (same session, owner questions) | — | ~85,000 | 58% (owner, before this row) | CI check, ADR 0011 (player-supplied ROM), M4-06 GBA library +… |
| 2026-10-27 14:53 | `session_01NGGw9NS5vR8Xyx9jdNSaNi` | scheduled run | ~20 | ~120,000 | — | M4-02 screen loads (BgHardware, SetupBackgrounds, Decompress… |
| 2026-10-27 15:15 | `session_01NGGw9NS5vR8Xyx9jdNSaNi` | interactive (same session, owner question) | ~4 | ~6,000 | — | why the run ended early; `tools/run_clock.sh` wrap-up gate… |
| 2026-10-27 15:25 | `session_01NGGw9NS5vR8Xyx9jdNSaNi` | interactive (same session, owner request) | ~2 | ~4,000 | — | CLAUDE.md rule 10 + brief amendment: Android Kotlin style guide |
| 2026-10-27 22:51 | `session_01NGGw9NG2hKhUCR67jzPm4P` | scheduled | ~26 | ~190,000 | — | GameLoop, GameControl, OpAnim to f1100 (M4-08), proc helpers… |
| 2026-10-28 06:51 | `session_01NGGw9QmHeKqD6tZt5WaVqw` | scheduled | ~25 | ~157,000 | — | OpAnim f843..f1100 (Ephraim + Eirika scenes to idx 22)… |
| 2026-10-28 14:52 | `session_01NGGw9MPE1YLzu6wY3C7fir` | scheduled | ~25 | ~183,000 | — | OpAnim idx 24..114 (Eirika exit, cast scroll, sparkles, 5 face… |
| 2026-10-28 22:51 | `session_01NGGw9RNv1MEWHxMPDD8SzH` | scheduled | ~30 | ~224,000 | — | M4-08 done (op anim to its end), M4-09 + M3-10 (CI green), M4-10… |
| 2026-10-29 06:50 | `session_01NGGw9Q1SuZxnUVKotcVNeC` | scheduled | ~30 | ~150,000 | — | M4-10 (a)..(d): whole title proc + ColorFade/FadeCore verified… |
| 2026-10-29 15:17 | `session_01NGGw9QAQfXPMFE2Jfjg2A3` | scheduled | ~25 | ~138,000 | — | M4-10 (e): OamBuffer + production HardwareScene; idle title, boot… |
| 2026-10-29 22:51 | `session_01NGGw9N4VQvPhrtUPhJ2L5N` | scheduled | ~25 | ~133,000 | — | M4-11 partial: allocation-free HardwareSceneLoader… |
| 2026-10-30 06:52 | `session_01NGGw9PqPBBpbSTMPeUbbVo` | scheduled | ~24 | ~160,000 | — | M4-11: measured step overruns (missedVBlanks) -> op anim + title… |
| 2026-10-30 14:12 | `session_01NGGw9PqPBBpbSTMPeUbbVo` | scheduled | ~22 | ~150,000 | — | md files condensed (TASKS, brief, HANDOFF, CLAUDE, USAGE); M3-15… |
| 2026-10-30 14:41 | `session_01NGGw9PqPBBpbSTMPeUbbVo` | interactive (owner request) | ~25 | ~250,000 | — | all md condensed, stale comments removed, slow-startup fix… |
| 2026-10-30 14:53 | `session_01NGGw9NMrgHXvAZN4eQ9aK8` | scheduled run | ~24 | ~180,000 | — | M4-10 done: title A/Start fade-out and skip/restart paths verified (+7 ports), 2 new reference runs; MODDING dirs note; M3-07 plan |
| 2026-10-30 22:50 | `session_01NGGw9Qb4ji9x4yv3gmgWS2` | scheduled run | ~22 | ~170,000 | — | M3-07 GPU backend (GpuFrame packer, PackedCompositor spec, GLES shader pixel-exact on 15 mGBA frames via WebGL2 tool, FBO, dirty uploads); M3-12 AudioTrack sink + test tone; both await manual test |

Totals recorded so far: ~2,563,000 tokens (counter), 2026-10-27 onward only.
