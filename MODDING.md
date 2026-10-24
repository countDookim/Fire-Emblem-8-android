# Modding guide

> Kept current with every moddable system. Systems marked *(planned)* have a designed contract
> (see the decision record) but no implementation yet — do not rely on them.

## How mods work
A mod is a folder or `.zip` containing `mod.json` plus files at **virtual paths**. Any file at the same
path as a base file overrides it; the highest layer wins ([0003](docs/decisions/0003-mod-vfs.md)).

```json
{ "id": "com.example.hardmode", "version": "1.0.0", "name": "Hard Mode",
  "dependencies": { "com.example.lib": ">=1.2.0" }, "loadOrder": 0, "formatVersion": 1 }
```

| Path prefix | Content | Format | Status |
|---|---|---|---|
| `data/` | items, classes, characters, terrain, shops, chapters, AI tables... | JSON + `schemas/*.schema.json`; patch with `*.merge.json` | *(planned, M2)* |
| `maps/` | chapter maps: layout, collision, events, units, warps | Tiled `.tmj` + tileset PNGs | *(planned, M2)* |
| `scripts/` | event scripts | `.fe8s` ([0001](docs/decisions/0001-script-format.md)) | *(planned, M5)* |
| `gfx/` | sprites, portraits, UI, tilesets; hi-res packs | PNG + JSON metadata | *(planned, M2–3)* |
| `audio/` | music, voicegroups, SFX | MIDI + JSON, WAV/OGG | *(planned, M2–3)* |
| `text/<lang>/` | all strings, tags like `{PLAYER}`, `{PAUSE 30}` | JSON | *(planned, M2)* |

## Feature flags (available now)
Non-original behaviour must be registered as a `FeatureFlag` (`fe8.core.flags.FeatureFlags.register`).
Vanilla mode (default) forces all flags off. Id convention: `qol.*` (built-in quality of life),
`mod.<modid>.*` (mod-defined). No built-in flags exist yet.

## Event bus (available now)
Hook gameplay without editing core code ([0004](docs/decisions/0004-event-bus.md)):
```kotlin
game.events.onTickEnd.subscribe(TickListener { tick -> /* runs after every logic frame */ }, priority = 10)
```
| Channel | Listener | Fires |
|---|---|---|
| `core.onTickStart` | `TickListener(tick: Long)` | start of each logic frame |
| `core.onTickEnd` | `TickListener(tick: Long)` | end of each logic frame |

Planned channels (added with their systems): `map.onMapEnter`, `map.onStep`, `battle.onBattleStart`,
`battle.onBattleEnd`, `item.onItemUsed`, `unit.onLevelUp`, `chapter.onChapterStart`, `phase.onPhaseStart`.

## RNG (available now)
`RngService` is the only randomness source. Vanilla `Fe8Rng` reproduces the original generator exactly
(verified against mGBA). Mods may replace `RngService.source` with any `RandomSource`.

## Mechanics strategies *(planned, M6)*
Damage, hit/avoid/crit, EXP, growths, AI decisions, support gains... each a Kotlin interface with a
vanilla implementation; mods swap implementations.

## Flags & variables *(planned, M5)*
Registry named after the decomp constants (e.g. `FLAG_...`); mods register new ones by id.

## Saves *(planned, M5)*
Versioned container with per-mod sections (keyed by mod id) and migrations; original `.sav` import.
