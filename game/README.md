# game

`fe8.game`, Android-free. `Fe8Game` owns the services (RNG seeded like the original boot, input, events, flags,
mechanics, offscreen frame) and runs the systems each tick. Done: boot (`Fe8Boot`: health & safety, logos,
`opanim`, `title`) with sound (`SoundWrapper`, `Fe8Sound`), reading ROM data through `RomView` (whole ROM or extracted windows). Systems are packages
named after the `system` column of `reference/decomp_functions.tsv`.
