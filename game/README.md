# game

Root of gameplay: `Fe8Game` owns the services (RNG seeded exactly like boot, input, events, flags,
offscreen frame) and will run the game systems each tick. Systems are packages under `fe8.game.*`
named after the `system` column of `reference/decomp_functions.tsv` (boot/title, battle_map,
battle_logic, ai, events, menus, ...). Android-free.
