# M6-B player phase: notes

Lane B of [ROADMAP](ROADMAP.md); rows in [TASKS](../TASKS.md). Start state: `prologue.sav` (turn-1 suspend).

## Captures

### `ram_trace_prologue_select_move` (PP-cap1, ROM repo `c3ad5d4`)

Resume (A f1002, A f1104), then select Eirika, move 2 tiles right, Wait. Per-step watches gplayst, gbmst, rng,
evglobals, flags, procs, actiondata, `unit1` (Eirika, `gUnitArrayBlue[1]` 0x0202BE94, 0x48); per-frame RAM
f1255..1420; full dumps every 10f f1260..1420. Seth is `gUnitArrayBlue[0]` at (4,4).

| Event | Frame |
|---|---|
| PlayerPhase proc up / map UI up | f1169 / f1172 |
| LEFT: cursor (5,5) → (4,5), onto Eirika | f1250 |
| A: select (unit state 0x1); MoveLimitView proc | f1260; f1262 |
| Range built (ViewChange proc ends) | f1268 |
| RIGHT, RIGHT: cursor (5,5), (6,5) | f1275, f1283 |
| A: confirm; MoveLimitView removed; MU walk | f1295; f1296..f1314 |
| Unit at (6,5); unit menu (Item, Wait) opens | f1314 |
| DOWN: cursor to Wait; A: Wait | f1340; f1355 |
| StdEventEngine f1356..1359; unit state 0x42 (greyed); Mu removed | f1360 |
| SideWindowMaker; idle UI procs (Goal/Terrain/UnitDisplay) back | f1363; f1364 |

The resume cursor starts at (5,5), not on Eirika. The menu offers only Item and Wait (no enemy in range).

### `ram_trace_prologue_cancel_end` (PP-cap2, ROM repo `45f35cf`)

Same start, watches and `unit1`; per-frame RAM f1255..1650, full dumps every 10f. Cancel select, cancel move,
map menu End, phase change, enemy phase intro, first AI action.

| Event | Frame |
|---|---|
| LEFT onto Eirika; A select; range procs | f1250; f1260; f1262..1268 |
| B: cancel select (state 0x0, range proc removed); idle procs back | f1290; f1291 |
| A select; RIGHT, RIGHT; A confirm; MU walk | f1310; f1330, f1340; f1355; f1355..1374 |
| Unit at (6,5), unit menu | f1374 |
| B: menu closes; unit back at (4,5), state 0x1; range + path arrow | f1400; f1401; f1403..1409 |
| B: back to idle (cursor (4,5)); idle procs back | f1425; f1426 |
| RIGHT to (5,5); A: map menu (Unit Status Guide Options Suspend End) | f1445; f1465 |
| UP: cursor wraps Unit → End; A: End (PlayerPhase/menu procs end) | f1490; f1510 |
| BerserkCpOrder/CpPhase procs; gPlaySt faction 0 → 0x80; StdEventEngine | f1511..1513; f1514; f1514..1517 |
| PhaseIntro (enemy banner) f1520..1591; CpDecide/CpOrder/CpPhase; enemy MU + CpPerform | f1597; f1599 |
| AiTargetCursor; run ends (first enemy battle would start ~f1690) | f1633; f1650 |

The enemy phase banner here is per frame (no per-scanline blend), so D12-2 still needs its own capture.
