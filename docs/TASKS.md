# Task backlog

Tasks ≤ 20 min. Status `todo`/`wip` (branch `wip/<id>`)/`done`/`blocked`. Take the first `todo` whose deps are
`done`; if fewer than 5 `todo` remain, plan the next milestone.

**Priority:** plan M5 (core loop, saves, flag registry, script interpreter) into tasks here, then start it.

## Open

| ID | Task | Acceptance | Status |
|---|---|---|---|
| M4-02 | Intro screens `opanim-healthsafetyscreen.c` (`fe8.game.opanim.OpAnimHs`, `EarlyStartUi`) | step functions `@VerifiesPort` vs RAM traces; screens pixel-exact | partly done (steps, logos, screen loads, whole proc vs `ram_trace_boot`); todo: `IsSoftwareReset`, Huffman/RL in `Decompress` |
| M4-12 | Verify the rest of the proc scheduler (`src/proc.c`, implemented in `gbafe.proc.ProcEngine`): `Proc_Init`, `Proc_ForAll`, `Proc_ForEach*`, `Proc_SetMark`, `Proc_{Block,Unblock,End}EachMarked`, `Proc_SetEndCb`, `Proc_SetRepeatCb` | `@VerifiesPort` vs RAM traces | todo (needs RAM traces that exercise them) |

## Done (details in git log)

- **M1** Toolchain, CI, skeleton, docs: M1-01..M1-12.
- **M2** Extraction into moddable formats (outputs in the ROM repo only): M2-01..M2-21.
- **M3** Rendering, input, audio, override layer: M3-01..M3-16. M3-07 GPU backend and M3-12 `AudioTrack` sink
  (owner device tests 2026-11-01); M3-13 m4a sequencer and M3-14 mixer + PSG, verified vs per-frame mGBA RAM
  traces (owner listen test 2026-11-04).
- **M4** Boot to title verified vs mGBA: M4-01 procs; M4-03 extraction library + manifest (`:engine:extract`);
  M4-04 ROM import in `:app` (owner device test 2026-11-04); M4-05 CI guard; M4-06 `:gba:*` library (ADR 0012); M4-07 Kotlin Multiplatform, jvm + android (ADR 0013);
  M4-08 op anim; M4-09 test-build assets; M4-10 title screen; M4-11 boot wired in `:app`.
