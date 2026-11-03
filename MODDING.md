# Modding guide

The game ships only original behaviour ([0009](docs/decisions/0009-vanilla-product-mod-groundwork.md)); all
below are extension points for mods. *(planned)* = designed in an ADR, not implemented.

## Mods
A folder or `.zip` with `mod.json` plus files at **virtual paths**; a file at a base path overrides it, highest
layer wins ([0003](docs/decisions/0003-mod-vfs.md)).

```json
{ "id": "com.example.hardmode", "version": "1.0.0", "name": "Hard Mode",
  "dependencies": { "com.example.lib": ">=1.2.0" }, "loadOrder": 0, "formatVersion": 1 }
```

| Path | Content | Status |
|---|---|---|
| `data/` | JSON tables ([schemas](schemas/)); `*.merge.json` patches *(planned)* | extracted, not loaded yet |
| `maps/` | Tiled `.tmj` + tileset PNGs | extracted, not loaded yet |
| `scripts/` | `.fe8s` ([0001](docs/decisions/0001-script-format.md)) | prologue converted; VM *(M5)* |
| `gfx/` | PNG + JSON metadata | texture overrides resolved; not yet drawn (boot screens read ROM data) |
| `audio/` | MIDI + voicegroup JSON, WAV | *(planned; the game plays the ROM's m4a data)* |
| `text/<lang>/` | strings with tags (`{PLAYER}`, `{PAUSE 30}`) | `text/en/messages.json` extracted |

Today the extractor writes graphics to `fonts/`, `portraits/`, `mapsprites/`, `ui/` and audio to `songs/`,
`samples/`, `voicegroups/` (top level, no `gfx/`/`audio/` prefix). Mod paths follow the extractor's layout when the
loaders land; until then `gfx/`/`audio/` above are the planned grouping. The boot screens and all sound currently
run on raw ROM windows (`RomView.BOOT_WINDOWS`, `RomView.SOUND_WINDOWS`), so no asset override reaches them yet.

**Texture packs** (`gba.vfs.TextureOverrides`, ADR 0008): `<asset>@<N>x.png` (N = 1..8) beside the original.
Indexed PNGs keep palette animation; true-colour ones may add `<asset>@<N>x.<paletteHash>.png` variants.

## Strategies
`game.mechanics` (`gbafe.core.mechanics.Mechanics`): swappable, vanilla by default. Swap at load time, never per
frame; must not allocate. More (damage, EXP, growths, AI...) come as systems are ported.

| Strategy | Vanilla |
|---|---|
| `battleRolls: BattleRolls` (`hit`, `crit`, `skill`) | hit = average of 2 RNs < rate; crit/skill = 1 RN |

```kotlin
object OneRnHit : BattleRolls by VanillaBattleRolls {
    override fun hit(rng: RngService, hitRate: Int) = rng.roll1(hitRate)
}
game.mechanics.battleRolls = OneRnHit
```

## Input
Logic sees only `GameAction`s; the app's only touch input is the on-screen GBA buttons (`TouchButtons`). A touch
mod supplies an `ActionSource` (may emit `SelectItem`, which the base game never does). Screens publish
selectable elements into `UiElements` each frame (GBA-pixel bounds + action, e.g. `SelectItem.of(row)`); a mod
calls `hitTest(x, y)` and emits the action.

## Feature flags
Non-original behaviour = a `FeatureFlag` (`gbafe.core.flags.FeatureFlags.register`), id `mod.<modid>.*`;
vanilla mode (default) forces all off.

## Events ([0004](docs/decisions/0004-event-bus.md))
```kotlin
game.events.onTickEnd.subscribe(TickListener { tick -> /* after every logic frame */ }, priority = 10)
```
Now: `core.onTickStart`, `core.onTickEnd` (`TickListener(tick: Long)`). Planned with their systems:
`map.onMapEnter`, `map.onStep`, `battle.onBattleStart`, `battle.onBattleEnd`, `item.onItemUsed`,
`unit.onLevelUp`, `chapter.onChapterStart`, `phase.onPhaseStart`.

## RNG
`RngService` is the only randomness source; vanilla `Fe8Rng` matches the original (verified vs mGBA). Mods may
replace `RngService.source` with any `RandomSource`.

## Planned (M5)
Flag/variable registry named after decomp constants (`FLAG_...`), extensible by id. Saves: versioned, per-mod
sections, migrations, original `.sav` import.

## Data tables
Format `gbafe.<table>` v1. Ids = decomp constants minus prefix (`ITEM_SWORD_IRON` → `SWORD_IRON`; unnamed
`ITEM_XX`). Position = vanilla number, so append new entries.
- `items.json`: position = item number. `maxRange: 0` = Mag/2. `effectiveAgainst` = class numbers.
- `classes.json`: position + 1 = class number. Per-terrain tables (`moveCost.normal/rain/snow`,
  `terrainAvoid/Defense/Resistance`; 65 ids, `-1` = impassable) inlined; `battleAnims` match first-to-last
  by weapon type or item.
- `characters.json`: position + 1 = character number (256). `bases` add to class bases; `supports`: ≤ 7
  partners with starting exp and per-turn growth.
- `terrains.json`: `entries` per terrain id (65: `healAmount` % HP/turn, `healsStatus`); `tables` = other
  65-value tables from `src/data_terrains.c` by decomp symbol.

All formats: [schemas/](schemas/).
