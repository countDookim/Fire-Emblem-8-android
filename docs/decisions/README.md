# Decision records

All accepted. Shape: Status → Context → Decision → Consequences, one screen each. Supersede with a
new record; owner amendments are folded in with their date.

- [0001](0001-script-format.md) Event scripts: custom `.fe8s`, not Lua
- [0002](0002-data-schemas.md) Versioned JSON + schemas → primitive tables
- [0003](0003-mod-vfs.md) Layered VFS for mods
- [0004](0004-event-bus.md) Typed, allocation-free event bus
- [0005](0005-toolchain-and-sessions.md) Sessions JVM-only, CI builds Android
- [0006](0006-verification-policy.md) What counts as "ported"
- [0007](0007-decomp-choice.md) Decomp: fireemblem8u
- [0008](0008-render-model.md) Render model: tile layers + sprite batches
- [0009](0009-vanilla-product-mod-groundwork.md) 1:1 product; modding = extension points
- [0010](0010-multi-game-reuse.md) FE6/FE7 reuse: `gbafe` vs `fe8`
- [0011](0011-player-supplied-rom.md) Player supplies the ROM; release ships no assets
- [0012](0012-gba-library.md) Game-neutral GBA library: `gba.*` modules
- [0013](0013-kmp-targets.md) Kotlin Multiplatform: jvm + android targets, commonMain rules
