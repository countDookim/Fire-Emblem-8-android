# Decision records

Short, numbered, immutable once accepted (supersede with a new record instead of editing).
Format: Context → Decision → Consequences. Keep each under one screen.

| # | Title | Status |
|---|---|---|
| [0001](0001-script-format.md) | Event script format: custom `.fe8s`, not Lua | Accepted |
| [0002](0002-data-schemas.md) | Data: versioned JSON + JSON Schema, compiled to primitive tables | Accepted |
| [0003](0003-mod-vfs.md) | Layered virtual filesystem for mods | Accepted |
| [0004](0004-event-bus.md) | Typed, allocation-free event bus | Accepted |
| [0005](0005-toolchain-and-sessions.md) | Toolchain: sessions JVM-only, CI builds Android | Accepted |
| [0006](0006-verification-policy.md) | What counts as "ported" | Accepted |
| [0007](0007-decomp-choice.md) | Decomp: fireemblem8u instead of StanHash/fe8 | Accepted |
| [0008](0008-render-model.md) | Render model: typed tile layers + sprite batches | Accepted |
| [0009](0009-vanilla-product-mod-groundwork.md) | Product is a 1:1 copy; modding is groundwork only | Accepted |
| [0010](0010-multi-game-reuse.md) | Built for reuse by FE6 and FE7 ports (`gbafe` vs `fe8` layers) | Accepted |
| [0011](0011-player-supplied-rom.md) | Player supplies the ROM; assets extracted on the device, none shipped | Accepted |
