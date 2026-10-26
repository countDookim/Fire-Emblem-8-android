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
| `data/` | items, classes, characters, terrain, shops, chapters, AI tables... | JSON + `schemas/*.schema.json`; patch with `*.merge.json` | `items.json`, `classes.json`, `characters.json`, `terrains.json` formats published ([schemas](schemas/)); extracted by `:tools:extract`, not yet loaded by the game. Others *(planned, M2)* |
| `maps/` | chapter maps: layout, collision, events, units, warps | Tiled `.tmj` + tileset PNGs | *(planned, M2)* |
| `scripts/` | event scripts | `.fe8s` ([0001](docs/decisions/0001-script-format.md)) | *(planned, M5)* |
| `gfx/` | sprites, portraits, UI, tilesets; replacement/hi-res packs (mods): put `<asset>@<N>x.png` (N = 1..8) next to the original path; an indexed PNG keeps palette animation, a true-colour one can add `<asset>@<N>x.<paletteHash>.png` variants (`TextureOverrides.paletteHash`, ADR 0008); the highest mod layer wins | PNG + JSON metadata | resolver done (`gbafe.vfs.TextureOverrides`, M3-08); drawn by the GPU backend (M3-07) |
| `audio/` | music, voicegroups, SFX | MIDI + JSON, WAV/OGG | *(planned, M2–3)* |
| `text/<lang>/` | all strings, tags like `{PLAYER}`, `{PAUSE 30}` | JSON | *(planned, M2)* |

## Scope
The game itself ships only the original behaviour ([0009](docs/decisions/0009-vanilla-product-mod-groundwork.md)).
Everything below is an extension point for mods; the base game adds no options of its own.

## Gameplay strategies (available now)
`game.mechanics` holds swappable strategies, each defaulting to the verified vanilla implementation.
Replace one at mod load time, never per frame. Implementations must not allocate.

| Strategy | Vanilla | Example mod |
|---|---|---|
| `battleRolls: BattleRolls` (`hit`, `crit`, `skill`) | hit = average of 2 RNs < hit rate ("true hit"); crit/skill = 1 RN | 1-RN hit: override only `hit` |

```kotlin
object OneRnHit : BattleRolls by VanillaBattleRolls {
    override fun hit(rng: RngService, hitRate: Int) = rng.roll1(hitRate)
}
game.mechanics.battleRolls = OneRnHit
```
More strategies (damage, exp, growths, AI...) are added as each system is ported.

## Input (available now)
Game logic sees only `GameAction`s. A touch UI is a mod that supplies an `ActionSource` (and may use
`SelectItem`, which the base game never produces). The shipped app's only touch input is an on-screen
GBA button overlay.

## Feature flags (available now)
Non-original behaviour must be registered as a `FeatureFlag` (`gbafe.core.flags.FeatureFlags.register`).
Vanilla mode (default) forces all flags off. Id convention: `mod.<modid>.*`. The base game registers
no flags.

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

## Data tables
`data/items.json` (`gbafe.items` v1, [schema](schemas/items.schema.json)): one entry per item in vanilla
item-number order; an entry's position is its number, so new items go at the end. Ids are the decomp's
`ITEM_*` constants without the prefix (`ITEM_SWORD_IRON` → `SWORD_IRON`); unnamed rows get `ITEM_XX`.
`maxRange: 0` means a magic-based range (Mag/2), as in the original. `effectiveAgainst` holds class
numbers until the class table exists (M2-05). The game does not load these files yet (loader: M2 later).

`data/classes.json` (`gbafe.classes` v1, [schema](schemas/classes.schema.json)): there is no class 0, so an
entry's position + 1 is its vanilla class number. Ids are `CLASS_*` without the prefix. Terrain tables
(`moveCost.normal/rain/snow`, `terrainAvoid/Defense/Resistance`) have one value per terrain id (65,
`-1` = impassable) and are inlined per class; `battleAnims` is matched first-to-last by weapon type or item.

`data/characters.json` (`gbafe.characters` v1, [schema](schemas/characters.schema.json)): position + 1 is the
vanilla character number (256 rows). `bases` are personal bonuses added to the class bases; `supports`
lists up to 7 partners with starting support exp and per-turn growth.

`data/terrains.json` (`gbafe.terrains` v1, [schema](schemas/terrains.schema.json)): `entries` has one row per
terrain id (65) with `healAmount` (% HP per turn) and `healsStatus`; `tables` holds every other 65-value
terrain table from `src/data_terrains.c` keyed by its decomp symbol (movement costs, avoid/def/res,
battle-animation lookups). How classes will reference shared tables by name is decided with the loader.
