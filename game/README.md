# game

`fe8.game`, Android-free. `Fe8Game` owns the services (RNG seeded like the original boot, input, events, flags,
mechanics, offscreen frame) and runs the systems each tick. Boot (`Fe8Boot`: health & safety, logos, `opanim`,
`title`) runs with sound (`SoundWrapper`, `Fe8Sound`), reading ROM data through `RomView` (whole ROM or extracted
windows). Systems are packages named after the `system` column of `reference/decomp_functions.tsv`.
In progress (M5): `bm` (PlaySt/BmSt, chapter map, render, camera, raw units), `event` (event engine, script
commands), `save` (SRAM layout, BmSaveLib, `.sav` import), `savemenu`, `flags` (`Fe8EventFlags`), `GameControl`.
