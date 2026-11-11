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
