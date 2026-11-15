# Modding guide

Vanilla stays faithful to the original. Player-loaded mods may replace data and executable logic,
including AI, camera, game rules and controls, without rebuilding the APK
([0016](docs/decisions/0016-faithful-vanilla-extensive-mods.md)). Original-only defaults do not limit mod
capabilities. *(planned)* means a target contract, not a working author API. The historical audit and current
scope/decisions are in [M7-modding](docs/plans/M7-modding.md); runtime, API and loader work remains planned.

## Mods
Planned packaging (D9 details open): a folder or `.zip` with `mod.json` plus files at **virtual paths**; a file at a base path overrides it, highest
layer wins ([0003](docs/decisions/0003-mod-vfs.md)).

```json
{ "id": "com.example.hardmode", "version": "1.0.0", "name": "Hard Mode",
  "dependencies": { "com.example.lib": ">=1.2.0" }, "loadOrder": 0, "formatVersion": 1 }
```

| Path | Content | Status |
|---|---|---|
| `data/` | JSON tables ([schemas](schemas/)); `*.merge.json` patches *(planned)* | extracted, not loaded yet |
| `maps/` | Tiled `.tmj` + tileset PNGs | extracted, not loaded yet |
| `scripts/` | `.fe8s` ([0001](docs/decisions/0001-script-format.md)) | prologue converted; compiled form and VM: [0001 amendment](docs/decisions/0001-script-format.md#amendment-m5-2026-11-04) |
| `gfx/` | PNG + JSON metadata | texture overrides resolved; not yet drawn (boot screens read ROM data) |
| `audio/` | MIDI + voicegroup JSON, WAV | *(planned; the game plays the ROM's m4a data)* |
| `text/<lang>/` | strings with tags (`{PLAYER}`, `{PAUSE 30}`) | `text/en/messages.json` extracted |

The extractor currently writes graphics to `fonts/`, `portraits/`, `mapsprites/`, `ui/` and audio to `songs/`,
`samples/`, `voicegroups/` (no `gfx/`/`audio/` prefix); mod paths will follow that layout once loaders land.
Boot screens and all sound run on raw ROM windows (`RomView.BOOT_WINDOWS`, `RomView.SOUND_WINDOWS`), so no
asset override reaches them yet.

**Texture packs** (`gba.vfs.TextureOverrides`, ADR 0008): `<asset>@<N>x.png` (N = 1..8) beside the original.
Indexed PNGs keep palette animation; true-colour ones may add `<asset>@<N>x.<paletteHash>.png` variants.

## Strategies
`game.mechanics` (`gbafe.core.mechanics.Mechanics`) supplies library groundwork, not a complete external
mod loader/API. Resolve strategies outside the frame loop; preserve vanilla allocation and RNG behavior.
The example below illustrates the Kotlin interface, not an already loadable mod package.

Planned replacement contracts include AI targeting, scoring, movement and entire decision routines;
combat formulas, skills, actions and turn rules. Expose semantic state views and typed decisions with
explicit legality/ownership. M7-26/29 define signatures and tests before implementation.

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
Vanilla retains original key timing and the on-screen GBA controls. The target mod API supports
semantic selection/movement/actions, mod-defined action types and entirely new schemes, including direct
touch. A custom scheme must not be limited to synthesizing a sequence of GBA buttons.
`ActionSource`, `GameAction` and `UiElements` are groundwork; production ports still read GBA key bits.
M7-28 designs context/legality checks, registration and dispatch, with UI/world coordinate transforms
from M7-27. Do not describe `UiElements` production coverage as complete before it is wired and tested.

## Camera (planned)
Vanilla retains original scrolling/framing and 240×160 presentation. Mods can replace tracking, zoom and
viewport dimensions. World, camera, viewport and UI coordinates need explicit transforms so picking and
new control schemes remain correct under custom views. Current rendering is still fixed-size; M7-27
splits camera policy, viewport rendering and input-coordinate work into separate verified tasks.

## Executable logic and author API (planned)
Load mod-authored AI/camera/control/rule code without recompiling the app. `.fe8s` remains the event-script
format; it is not assumed to provide a general strategy runtime. M7-25 evaluates runtime choices for D10;
M7-30 specifies API versions, dependencies, conflicts, registration, lifecycle/errors and state ownership.
No runtime/language or installable executable-mod format is promised by this guide yet.

Notifications observe changes; replacement contracts supply decisions and behavior. Both must be
documented with minimal original examples, semantic IDs and stable state views. Authors should not need
ROM addresses or proc internals. Audio replacement remains required scope (M7-31), even if delivered later.

## Verification (planned mod integration)
Run original captures with mods disabled. Test mod behavior separately, including re-running a clean
baseline with mods disabled to recover original results. Preserve vanilla frame/allocation budgets and
measure loaded mod cost. Removing a mod from an existing modded save needs a defined compatibility
policy; it is not the same test as restoring a clean vanilla baseline. Mod tests do not earn port credit.

## Feature flags
Non-original behaviour = a `FeatureFlag` (`gbafe.core.flags.FeatureFlags.register`), id `mod.<modid>.*`;
vanilla mode (default) forces all off.

## Events ([0004](docs/decisions/0004-event-bus.md))
```kotlin
game.events.onTickEnd.subscribe(TickListener { tick -> /* after every logic frame */ }, priority = 10)
```
Now: `core.onTickStart`, `core.onTickEnd` (`TickListener(tick: Long)`). Planned with their systems:
`map.onMapEnter`, `map.onStep`, `battle.onBattleStart`, `battle.onBattleEnd`, `item.onItemUsed`,
`unit.onLevelUp`, `chapter.onChapterStart`, `phase.onPhaseStart`. These names are proposals, not a stable
public API; M7-30 reviews the complete list, timing and ordering. Decision replacement is a separate contract.

## RNG
`RngService` is the only randomness source; vanilla `Fe8Rng` matches the original (verified vs mGBA). Mods may
replace `RngService.source` with any `RandomSource`.

## Flags, variables, saves
`FlagRegistry`/`VarRegistry`: vanilla entries named after decomp constants (`EVFLAG_...`; unnamed `EVFLAG_<n>`);
mods register their own by name and get handles ≥ `MOD_BASE`, never touching vanilla bytes. Event-script flag handlers route mod
handles to the registry (`EventEngineProc`, `ModFlagSaveTest`); no mod loader exists before M7. Library groundwork (`gbafe.save`): versioned container with a `core` SRAM section
and opaque `mod:<id>` sections that survive rewrites; register `MigrationRegistry` steps per `(sectionId,
fromVersion)`. Original `.sav` import: `Fe8SavImporter`. Production uses raw SRAM; the mod-aware storage
choice and missing-mod/migration behavior remain D8. The library API is not proof of production support.

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
