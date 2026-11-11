# game

`fe8.game`, Android-free. `Fe8Game` owns the services (RNG seeded like the original boot, input, events, flags,
mechanics, offscreen frame) and runs the systems each tick. Boot (`Fe8Boot`: health & safety, logos, `opanim`,
`title`) runs with sound (`SoundWrapper`, `Fe8Sound`), reading ROM data through `RomView` (whole ROM or extracted
windows). Packages: `bm` (PlaySt/BmSt, chapter map, render, camera, units, MU, player phase), `event` (event engine, script
commands), `flags`, `opanim` (opening, subtitle), `save` (SRAM layout, `.sav` import, suspend), `savemenu`, `scene`
(talk, portraits), `text`, `title`, `ui` (popups, item icons), `worldmap` (world-map ports, not yet wired into boot).
`GameControl` runs New Game: the Prologue opening to the turn-1 map. Open work: [TASKS](../docs/TASKS.md).
