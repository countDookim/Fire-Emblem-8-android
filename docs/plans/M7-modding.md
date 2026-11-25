# M7 extensive modding plan and historical audit

Audited 2026-11-11 at `74c9fa2`, read-only. Method: grep and reading of the classes named below. "Not checked"
means I did not read it. Statuses: `in place` (works in production code), `partial`, `missing`, `contradicted`
(code or another doc says the opposite of the brief). Test-only means a unit/differential test exercises it
but no production code path (`:app`, `Fe8Boot`, `Fe8Game`) does.

## 0. Accepted direction (owner, 2026-11-15)

[ADR 0016](../decisions/0016-faithful-vanilla-extensive-mods.md): the base game remains faithful to
original behavior; mods can extensively replace data **and executable logic** without rebuilding the APK.
The audit below is a 2026-11-11 snapshot, not a claim that its counts/callers describe today's code.

| Area | Vanilla contract | Required mod capability (planned) |
|---|---|---|
| AI | Original decisions, priorities, movement and RNG consumption | Replace targeting/scoring/movement policies and whole decision routines |
| Rules | Original combat, skills, action and phase behavior | Replace formulas and action/turn rules through explicit contracts |
| Camera | Original framing/scrolling and 240×160 presentation | Replace tracking, zoom, coordinate transforms and viewport dimensions |
| Input | Original key timing and on-screen GBA controls | Semantic actions, custom action types and entirely new schemes, including direct touch |
| Data/content | Imported ROM remains the default | Override tables, maps, event scripts, text, graphics and audio |
| Save state | Preserve vanilla state and original-save compatibility | Versioned per-mod state with explicit migration/compatibility policy |

**Delivery order:** (1) audit boundaries and review runtime/API contracts; (2) loader/data overrides and
vanilla isolation checks; (3) executable logic with independent AI, camera and control demonstrations;
(4) complete remaining reader/effect groups, audio, mod state and author documentation. These are stages,
not cuts to the required scope. First release contents remain an owner decision. M6 continues toward
playable vanilla; each touched system records its replacement boundary or a small follow-up task.

**Contract requirements:** semantic IDs/state views instead of raw ROM/proc access; notifications separate
from decision replacements; documented ordering and conflicts when two mods replace the same service;
versioned APIs and mod dependencies; defined load/unload/error behavior, RNG/clock access and state ownership.
No assumption that `.fe8s`, `UiElements`, an event bus or a button mapper already supplies general logic mods.
Vanilla keeps its allocation/performance budget; loaded logic needs explicit measured budgets. Full API
signatures and runtime choices are review outputs, not decisions silently made by these planning rows.

**Verification:** original captures compare vanilla only. Independent mod tests prove changed behavior;
disabling mods from the same clean initial state must reproduce vanilla. Existing modded saves need an
explicit compatibility decision, not silent conversion. Mod-only tests never add verified FE8 functions.

## 1. Summary (historical audit)

| Bullet | Status | Evidence | Gap |
|---|---|---|---|
| Input | partial | `gba.input.GameAction`, `ActionSource`, `InputFrame`; `UiElements.add/hitTest` + `UiElementsTest`; app has `HardwareKeySource`, `TouchButtons` | Game logic still reads raw GBA key bits (`Fe8Boot(keyInput: () -> Int)`, `KeyStatus`); `UiElements` has no producer in `game/` or `app/`; `SelectItem` never emitted |
| Feature flags | partial | `gbafe.core.flags.FeatureFlags` (register/isEnabled/set, `vanilla=true` forces off); `Fe8Game.flags` | No system registers or checks a flag (grep: no `register(` in `game/`); flags are not persisted or exposed to mods via a loader |
| Data | contradicted | Extractors write `data/{items,classes,characters,terrains,chapters,trig}.json`; `schemas/*.schema.json` (14), validated by `tools/tests/test_schemas.py` and the `*RomTest`s; `gbafe.data.DataTable` is an interface only | No runtime code reads JSON: no `.json` read in `game/`, `app/` or `engine/*` main outside extractors; no compile-to-arrays loader; no `*.merge.json`; game systems read ROM tables by address (section 2); no schema validation at load |
| Maps | partial | `MapsExtractor` writes `maps/chapter_NN.tmj` + `tilesets/*.png`, all chapters (`Fe8Steps`) | Units only for chapter index 0 (`Fe8Steps` line ~88); no collision/warps/events/connections layers (not checked beyond units); game maps load from ROM via `ChapterData`/`BmMap`; no `.tmj` loader |
| Event scripts | partial | `engine/script`: `FeScriptParser`, `ScriptAssembler`; `Fe8ScriptSymbols.assembler`; `ScriptWords(rom, mod)` with `MOD_BASE = 0x0A000000`; extractor writes `scripts/*.fe8s` + `index.json` (all `EventScr_*`, plus world map) | Mod script path is test-only (`ModScriptEndToEndTest`, `ModFlagSaveTest`); production builds `ScriptWords({ rom.u16(it) })` with no mod words (`Fe8Boot` 258, 611, 621); no loader maps a VFS `.fe8s` to a script address or hooks it into chapter/map events; `ScriptVm` contract only (not checked beyond file list) |
| Graphics | partial | Extracted PNG+JSON: `fonts/`, `portraits/`, `mapsprites/`, `ui/` (icons, windowframes, cursors, 3 screens); `gba.vfs.TextureOverrides` (`@Nx.png`, palette-hash variants), `TextureOverridesTest` | `TextureOverrides` has no production caller (grep); graphics are read from ROM (`BootRom`, `Fe8Boot`, `TalkScene`, ...); MODDING.md already says "not yet drawn" |
| Audio | partial | Extractor writes `songs/*.mid`, `songs.json`, `voicegroups/*.json`, `samples/*.wav`; `gba:audio` m4a engine plays | Game plays ROM m4a data (`Fe8Sound` `SONG_TABLE`, `RomView.SOUND_WINDOWS`); nothing reads MIDI/WAV/voicegroup JSON; no OGG path |
| Text | partial | `text/en/messages.json`, `fonts/*.json`; `gbafe.text.TextTag` (`NL`, `A`, `PAUSE n`, `PLAYER` only), `MessageCodec`, `MsgHuffman` | Runtime text is Huffman from ROM (`Fe8Boot` `MSG_HUFFMAN_*`, `Fe8Text` glyph tables); `TextTag` not used in `game/` main; no `text/<lang>/` loader; tag set is 4 of the control codes (rest not checked) |
| Mechanics | partial | `Mechanics`, `BattleRolls` + `VanillaBattleRolls` (`engine/core`); `Fe8Game.mechanics`; `BattleRollsTest` | `Fe8Boot` builds its own `Mechanics()` (line ~389), so `Fe8Game.mechanics` is not what battles use; only `battleRolls.skill` is called (`BattleGenerate` 463); `hit`/`crit` have no caller (hit/crit logic not ported: no `roll2` in `game/`); damage, EXP, growths, AI: no strategy interfaces. Typed bus: only `core.onTickStart/End` exist (`CoreEvents`); MODDING.md lists the others as planned |
| Flags/variables | partial | `FlagRegistry`, `VarRegistry`, `FlagStore`, `ModHandles` (handles >= `MOD_BASE`); `ModFlagSymbols` in `engine/script`; `Fe8EventFlags` | Registries not referenced from `game/` main or `app/` (grep); mod flags wired only in tests (`ModFlagSaveTest`); MODDING.md says wiring is M5-56 |
| Saves | partial | `gbafe.save`: `SaveContainer` v1, `ModSaveSections` (`mod:<id>`), `MigrationRegistry`, checksums; `Fe8SavImporter` (+ export) | No production caller of `Fe8SavImporter`, `ModSaveSections`, `MigrationRegistry` (grep: only tests); the running game saves through `CartSram`/`FileSaveStorage` raw SRAM, not the container; no `.sav` import UI |
| Mod loader | partial | `gba.vfs.VirtualFileSystem` (layers, top wins, `list` union), `VfsLayer`, `DirectoryLayer` (jvm), app `AssetLayer`; `ModManifest` data class | `ModManifest` is a bare data class: no `mod.json` parser, dependency/order resolution, zip layer, or `override/` layer; `VirtualFileSystem` is not instantiated in `game/` or `app/` (grep); no hot reload (ADR 0003 describes it; grep finds none) |
| Camera/RNG/clock | partial | RNG: `RngService.source: RandomSource`, `Fe8Rng`, `seedLikeBoot`; clock: `GameClock`, `FixedStepClock`; `FrameBuffer(width, height)` parameters | RNG and clock in place. Camera: ARCHITECTURE says "Fixed 240x160"; `FrameBuffer` defaults to 240x160 and the renderer/`BmSt.camera` are GBA-sized; no camera abstraction (not checked beyond this) |
| Debug menu | missing | `BuildConfig.DEBUG` only gates `Trace` sections (`GameSurfaceView`); no debug UI in `app/` or `game/` (the decomp `uidebug`/`bmdebug` are out of scope per ROADMAP) | Warp, flags/vars, items, battles, inspect: none |
| Hard req. 5 (gbafe vs fe8) | partial | `tools/tests/test_layering.py`: (1) every `gbafe.*` file (except `gbafe.extract.<fe6\|fe7\|fe8>`) must not contain `fe8.` references outside comments (regex excludes `fe8.extract`); (2) `gba.*` must not import `gbafe.`/`fe8.` | The rule is about package names only. It does not check ROM addresses. No `gbafe.*` file outside `gbafe.extract.fe8` holds an FE8 ROM address: `gbafe/text/MsgStrings.kt` and `TextSystem.kt` have only `ROM_BASE = 0x08000000` (GBA constant); `MsgHuffman` mentions the base in a comment. `fe8.game` holds the tables, so the split holds in practice. `gba.extract.Symbols` mentions the 0x08000000 range only |
| ROM and distribution | partial | ADR 0011; `RomImporter`, `BundledManifest`, `tools/check_no_game_content.py`, `test_no_game_content.py`; app `RomImport`/`FileRomSink` keep `base/rom/baserom.gba` | Consistent with the brief's first bullets. In tension with "all game data through the VFS" (section 2); `ModManifest`-level mods cannot replace anything the game reads from the ROM image |

## 2. ROM-read inventory

Brief: "all game data in versioned, schema-validated JSON ... none hardcoded". ARCHITECTURE principle 6: "All
content through the layered VFS". ADR 0011 point 2 keeps the verified ROM image on the device "ported code reads
ROM addresses through `RomView.ofRom`". Fact: `Fe8Game(rom = RomView.ofRom(...))` is what the app runs, and no
production class reads the VFS.

Counting method (approximate, grep): "readers" = non-test classes in `game/src/commonMain` that hold a ROM
address constant (`0x08xxxxxx`/`0x09xxxxxx`, outside comments) or call `RomView.u8/u16/u32/blob/pal/obj*` on ROM
data, counted by group. Overall: 76 files in `game/src/commonMain` mention `RomView`, `ScriptMemory` or a ROM
address (116 files in `game/`); 70 files across `game/` and `engine/` hold `0x08/0x09` address text, of which 17
are extractors (allowed: per-game extractor packages). About 140 distinct address constants sit outside comments
(117 of them in `BootRom.kt`). `engine/*` (non-extract) and `gba/*` contain no FE8 table address.

| Group | Readers | Example classes | Extracted moddable format exists? |
|---|---|---|---|
| Chapter data (table 79 x entry, asset table, map pointers, BGM) | 1 table reader + 8 consumers (`MapMusic`, `ChapterMapGfx`, `WorldMapBeginning`, `UnitSpritesOam`, `BMapMain`, `BmMap`, `MapAnimations`, `BattleMapStart`) | `ChapterData` (`CHAPTER_DATA_TABLE`, `CHAPTER_DATA_ASSET_TABLE`) | Yes: `data/chapters.json` (`ChaptersExtractor`), `maps/chapter_NN.tmj`. Not read by the game |
| Unit, class, character, item, battle tables | 6 | `UnitLoader` (`CHARACTER_DATA`, `CLASS_DATA`), `ItemInfo` (`ITEM_DATA`), `UnitTable` (pointer deref), `BmBattle` (weapon triangle, affinity, effectiveness lists), `BattleGenerate`, `EventUnitLoads` | Characters, classes, items, terrains: yes (`data/*.json`). Weapon-triangle and affinity tables: no extractor found. Effectiveness lists: `items.json` has `effectiveAgainst` (MODDING.md); whether it covers the three `BmBattle` lists: not checked. Unit load lists: only chapter 0 as `.tmj` objects |
| Event scripts | 7 | `Fe8Boot` (3 `ScriptWords` over `rom.u16`), `EventEngineProc`, `EventScrCore` (`EVENT_SCR_08592114`), `EventAsmCall` (guide table, hook address `0x080CF460`), `WorldMapBeginning` (`EVENTS_WM_BEGINNING`), `BMapMain` (skirmish), `EventUnitLoads` | Yes: `scripts/*.fe8s` + `index.json` (all `EventScr_*` and world-map scripts; count of `raw` lines is reported by the extractor). Assembler exists; mod words are accepted by `ScriptWords` but no production code supplies them |
| World map (nodes, paths, graphics, face/text/unit data) | 8 + 4 node consumers | `WmNodeData` (`NODE_DATA`), `WorldMapPath` (`PATH_DATA`), `WorldMapRm`, `WorldMapRmBorder`, `WorldMapFace`, `WorldMapText`, `WorldMapUnit`, `WorldMapBeginning`; consumers `WorldMapMu/Scroll/Nodes/Main` | No (only `scripts/worldmap.fe8s`). Nodes, paths, map graphics: no extractor |
| Graphics, tilemaps, palettes | about 22 | `BootRom` (title/boot, 117 constants), `Fe8Boot`, `UiFrame`, `ItemIcon`, `UnitSprites`, `UnitSpriteHandles`, `UnitSpritesOam`, `MapCursor`, `PhaseIntro`, `Talk*`, `BrownTextBox`, `OpSubtitle*` (5), `ChapterMapGfx`, `MapAnimations`, `Mu`, `MuSpriteAnim` | Partly. Yes: item icons (`ui/icons`), window frames (`UiFrame`), map sprites (`UnitSprites*`), cursors (`MapCursor`, `ui/` cursors), portraits (`TalkScene`/`TalkFace` portrait table), trig (`MuSpriteAnim` sine table), 3 boot screens (health/safety, 2 intro logos). No: title screen graphics, phase-intro banners, talk bubble/brown text box, op-subtitle scenes, misc UI sheet, status icons, object/OAM shape tables, `MU_INFO_TABLE` |
| Text | 3 | `Fe8Text` (glyph tables `GLYPHS_SYSTEM/TALK`, colour LUT), `Fe8Boot` (`MSG_HUFFMAN_TABLE/ROOT`, `MSG_TABLE`), `WorldMapText` | Yes: `text/en/messages.json`, `fonts/*.png+json`. Not read by the game (`TextSystem` decodes the ROM Huffman stream) |
| Sound | 1 (+ the m4a engine through the memory bus) | `Fe8Sound` (`M_PLAY_TABLE`, `SONG_TABLE`); `RomView.SOUND_WINDOWS` | Yes: `songs/*.mid`, `voicegroups/*.json`, `samples/*.wav`. Engine plays raw m4a from the ROM window |
| Proc scripts, sprite objects, native hooks | 4 | `Popup` (`PROC_SCRIPT`), `GotItemPopup` (4 script addresses), `PopupScript`, `EventAsmCall` (ASMC address table) | No |

Notes. (a) "Readers" counts classes, not call sites; `UnitLoader` alone has 63 `rom.` calls and `BmBattle` 31.
(b) Boot screens and sound use extracted ROM windows (`RomView.BOOT_WINDOWS`, `SOUND_WINDOWS`), a partial image,
not VFS files. (c) None of the extracted JSON/PNG/MIDI is consumed by production code; they serve as reference
data, tests and the ROM-repo CI check (`ci_check_extraction.sh`).

### Options (state of the gap, not a recommendation)

| Option | What it means | Rough cost (readers touched) |
|---|---|---|
| A. ROM stays the vanilla source; add VFS overrides per table | Each reader asks a small resolver "mod file for table T? else ROM". Vanilla stays bit-exact by default | One resolver per group; 1 reader for chapters, 6 for unit/item tables, 4 for proc/objects, 7 for scripts. Tables with an extractor can start at once: chapters, characters, classes, items, text, event scripts (about 15 readers). Groups without an extractor need an extractor first |
| B. Migrate table reads to extracted JSON (VFS `base` is the only source) | Readers consume compiled arrays from `data/*.json`; ROM image only for code-like data (graphics blobs, m4a) | About 15 readers for tables with an extractor; about 45 more (graphics, world map, proc) need new extractors + schemas. Highest work and risk to differential tests (they compare against ROM bytes) |
| C. Hybrid by group | Data tables (B), assets via `TextureOverrides`/path override (A), scripts via `.fe8s` loader; world map and proc data stay ROM-only until a mod needs them | Smallest first step: about 15 readers (tables, scripts); the rest deferred |
| D. Document the contradiction | Amend the brief/ARCHITECTURE: mods override only what has a loader; ROM stays the base for the rest | 0 code; edits to PROJECT_BRIEF, ARCHITECTURE, MODDING |

## 3. Decisions and remaining choices

| ID | Status | Direction / remaining choice |
|---|---|---|
| D1 | Decided 11-11 | ROM is the vanilla source; per-table overrides (ADR 0015). |
| D2 | Scope decided 11-15; release staging open | All content groups, including audio, plus AI, rules, camera and new controls are required. Decide the first release cut after estimates; do not permanently defer these capabilities. |
| D3 | Decided 11-15 | Separate vanilla reference tests and mod behavior tests; disabling mods on a clean baseline restores original behavior. |
| D4 | Open | Keep extractor paths or rename? Existing-layout recommendation remains a proposal. |
| D5 | Direction decided 11-15; API details open | Both notifications and replacement contracts are needed. Review event list, timing, mutation permissions and conflict ordering. |
| D6 | Open | Debug tools and Android/Compose versus in-game UI; debug-only requirement unchanged. |
| D7 | Direction decided 11-15 | Vanilla remains 240×160; mods can replace camera, zoom and viewport. Rendering/input contracts require design, not permission to change vanilla defaults. |
| D8 | Requirement decided; storage choice open | Versioned mod state and original-save compatibility required. SaveContainer versus sidecar, missing-mod behavior and migrations need review. |
| D9 | Open | Packaging/reload support and schema-validation policy: folder/ZIP/override layers and which data is checked when. |
| D10 | New, open | Executable-mod runtime and public API/lifecycle: evaluate how authors supply AI/camera/control logic without rebuilding the app. No language, VM or native-plugin format selected. |

The owner approved the extensive-modding direction; this does not silently approve every earlier
packaging, debug-UI or save-format recommendation. Open choices block their dependent implementation,
not M6 ports or the read-only design inventories below.

## 4. Proposed M7 rows

The original M7-1..23 rows below are retained as proposals, amended for the accepted scope. They are
not all ready coding briefs. M7-24..32 are bounded design inventories in TASKS; their results split the
implementation proposals and add executable-logic work before coding. Existing engine libraries are
not proof that production supports loading mods. New mod infrastructure does not receive FE8 port tags.

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| M7-1 | PORT | `ModManifest` parser (`mod.json`): validate id, semver, `formatVersion`, dependencies | Unit test: valid manifest parses; missing id, bad version and unknown `formatVersion` each give a named error | — | proposed |
| M7-2 | PORT | Mod resolver: dependency check and ordering (deps, then `loadOrder`, then id), cycle and missing-dependency errors | Unit test: 3 mods with deps load in the expected order; cycle and missing dep report the mod ids | M7-1 | proposed |
| M7-3 | PORT | Zip `VfsLayer` (jvm + android) and `override/` debug layer | Test: a zip and a folder with the same path; top layer wins; `list()` unions; zip with `..` paths is refused | M7-1; D9 | proposed |
| M7-4 | WIRE | Create the `VirtualFileSystem` in `:app` (base + mods dir), mount mods in resolved order, show loaded mods and errors in the launcher | Headless test with a fake mods dir; MANUAL_TEST entry; allocation test unchanged | M7-2, M7-3 | proposed |
| M7-5 | PORT | JSON table loader with schema check at load (`DataTable` impls compiled to primitive arrays) for one table, `items.json` | Test: loads extracted `items.json`; a schema-violating file gives a named error; no allocation after load | D1 | proposed |
| M7-6 | PORT | Item data through the loader: `ItemInfo` reads the compiled table, ROM fallback if no file | Existing `ItemInfoReferenceTest` still passes with ROM path; test with a mod file changing one item field changes `ItemInfo` output | M7-5; D1 | proposed |
| M7-7 | PORT | Same for characters and classes (`UnitLoader`, `UnitTable`) | Existing unit tests pass unchanged; mod override of one class stat test | M7-6; D1 | proposed |
| M7-8 | PORT | Chapter data and `.tmj` maps through the loader (`ChapterData`, `BmMap`) | Existing `ChapterDataRomTest` passes; mod override of one chapter field test | M7-5; D1 | proposed |
| M7-9 | WIRE | Script loader: assemble `.fe8s` from the VFS to `ScriptWords` mod words; map a script id to an address; run from a chapter event | Headless end-to-end test (extends `ModScriptEndToEndTest`) runs a mod script from `Fe8Boot`; MANUAL_TEST entry | M7-4 | proposed |
| M7-10 | PORT | Text loader: `text/<lang>/messages.json` override with tags; `TextTag` set completed against the extracted tags | Test: mod string replaces message id N; unknown tag errors by name; vanilla ids unchanged | D1, D2 | proposed |
| M7-11 | WIRE | Texture override into the renderers for one asset (item icons) via `TextureOverrides` | Headless test: a `@2x.png` is picked; frame compare with and without; MANUAL_TEST entry | M7-4; D2 | proposed |
| M7-12 | INV | Extractors for data with no extracted form that mods would need (weapon triangle, affinity, effectiveness lists; unit lists for all chapters) | Each new file has a schema and passes `test_schemas.py`; extraction CI check updated | D2 | proposed |
| M7-13 | WIRE | Feature flags: register one non-vanilla flag in a system, behaviour behind it, `vanilla=true` keeps bit-exact | Differential test passes with flags off; test shows the flag changes the behaviour with `vanilla=false` | — | proposed |
| M7-14 | WIRE | Use `Fe8Game.mechanics` in `Fe8Boot` (replace its own `Mechanics()`) | Test: swapping `battleRolls` on the game changes a battle in `Fe8Boot`; vanilla differential tests unchanged | — | proposed |
| M7-15 | PORT | Typed notifications for the reviewed D5 event list; replacement policies are separate M7-26..29 contracts | Timing, order and arguments tested per event; vanilla allocation check | D5; M7-30; M6-B..D | proposed |
| M7-16 | WIRE | Flag/var registries into `Fe8Boot` and event scripts; mod flags saved in `mod:<id>` sections | Test: a mod flag set by a script survives save and load; vanilla bytes unchanged | D8 | proposed |
| M7-17 | WIRE | Save through `SaveContainer` (or the side file, per D8); `.sav` import entry in the launcher | Test: import of the reference `.sav` loads; round trip keeps mod sections; MANUAL_TEST entry | D8 | proposed |
| M7-18 | WIRE | First semantic input adapter and direct-touch selection, scoped by M7-28; UiElements is one source | Valid action dispatch changes selection; invalid/context-mismatched action rejected; original key replay unchanged | M7-28; D10 | proposed |
| M7-19 | PORT | Hot reload: watch folder layers in debug builds, invalidate loaders between ticks | Test: change a file in a temp mod folder; next tick sees the new data; release build has no watcher | M7-4, M7-5 | proposed |
| M7-20 | WIRE | Debug menu (debug builds only) with the tools chosen in D6 | Headless test of each tool's state change; `BuildConfig` guard test shows it absent in release; MANUAL_TEST entry | D6 | proposed |
| M7-21 | doc | Bring MODDING.md, ARCHITECTURE principle 6 and the brief in line with D1/D4/D7 and the finished rows | Every "planned" mark in MODDING.md matches code; no row's status contradicts a doc | D1, D4, D7 | proposed |
| M7-22 | tool | Layering check for ROM addresses: fail CI if a `gbafe.*` file (outside `gbafe.extract.<game>`) holds an FE8 `0x08xxxxxx` constant | `test_layering.py` case passes today; fails on a seeded offender | — | proposed |
| M7-23 | WIRE | Milestone close after all required capability groups, including executable logic, audio and mod state | Coverage ledger complete; separate AI/camera/control examples run without APK rebuild; clean vanilla replay restored; owner device tests | M7-24..32 outputs and required implementation rows | blocked: inventories must add implementation deps |

Not checked: `ScriptVm` implementation beyond the contract file, Tiled layers other than units, the full
`TextTag` list versus extracted control codes, whether `items.json` `effectiveAgainst` covers the `BmBattle`
lists, `testing/` tests that read the VFS.

## 5. Bounded design tasks (no implementation in this planning revision)

These inventories produce reviewable documents and small implementation briefs, not code, captures or
port credit. Prefer one representative running flow over auditing every source file. Confirm current
code before deciding an interface. Each brief names files, signatures, semantics, tests and acceptance.

| ID | Scope | Required output / stop condition | Deps |
|---|---|---|---|
| M7-24 | Boundary audit of one enemy turn, one camera scroll and one player action | Actual callers/ownership in CpDecide/CpBattle/CpUtility, BmSt/camera and PlayerPhaseWiring/input; identify hard coupling; small per-system follow-up rows | — |
| M7-25 | Executable logic options only; `.fe8s` remains the event-script format | Compare JVM/Android feasibility, author workflow, API access, determinism, cost/allocation, failure behavior and distribution constraints; one recommended D10 ADR proposal for owner review; no runtime code | M7-24 |
| M7-26 | Enemy AI replacement contract for one enemy turn | State view, targeting/movement/scoring and whole-decision boundaries; legality/ownership, RNG call order, custom state, conflict policy; split adapter/strategy/loader tasks and vanilla/mod tests | M7-24 |
| M7-27 | Camera tracking and viewport contract for one map scene | Separate logical world, camera, viewport and UI transforms; scope tracking, zoom and viewport implementation separately; original-frame parity and transformed hit-test cases | M7-24 |
| M7-28 | Semantic input contract for select → move → choose action | Trace original keys; define context/legality and custom action registration, device adapters, UI/world coordinates; direct touch must not require fake key navigation; split small implementation rows | M7-24, M7-27 |
| M7-29 | Rule replacement contract for one combat action and one phase transition | Separate formula, skill, action and turn-policy replacement points with explicit state ownership; vanilla hit/RNG/phase replay and alternative-policy tests; no rewrite of all combat | M7-24 |
| M7-30 | Versioned mod API registration and lifecycle | D5/D10 proposal: notifications versus replacements, API/dependency compatibility, conflict/order, load/unload/errors, clock/RNG access, save-state handoff; signatures and bounded implementation rows | M7-25, M7-26, M7-27, M7-28, M7-29 |
| M7-31 | Audio override path for one song and one effect | Inspect Fe8Sound/m4a/VFS boundary; format/loader choices, unchanged vanilla playback, replace-and-disable checks, resource lifetime and performance; split song/effect rows, list remaining audio groups | — |
| M7-32 | Mod coverage and verification plan | Ledger for every required capability/reader group, vanilla baseline and independent mod tests; load-disable baseline, API compatibility, save/missing-mod cases and performance checks; update M7-23 dependencies and propose release staging | M7-30, M7-31 |

**Required demonstrations, each implemented as separate small tasks after design:**
- Load authored logic without rebuilding the APK; compatibility failures identify the affected mod.
- Replace an enemy decision routine and observe different legal behavior; vanilla retains original choices/RNG.
- Replace camera tracking, then separately exercise zoom/viewport and matching coordinate transforms.
- Select/move/act through a new control scheme; demonstrate a registered custom action beyond button remapping.
- Replace one combat rule and one action/turn policy independently; no default behavior changes.
- Replace one song and one sound effect; the vanilla playback path still matches reference checks.
- Persist versioned mod state under the chosen D8 policy; disabling on a clean baseline restores vanilla.

Publish author-facing API documentation and minimal original examples for each capability. No ROM-derived
content goes into public examples. A texture/stat/script mod remains an early check, not milestone completion.

### M7-24 result (boundary audit, 2026-11-25; code read only, no behaviour change)

Paths: `game/.../fe8/game/` (`bm/` unless noted). "Hook" = a nullable `hooks.*` field or a `var` the boot code
sets; none of the hooks below is a mod boundary today: they stand in for unported or cross-module services.

**A. One enemy turn** (wired in `Fe8Boot.wirePlayerPhase`, ~l.1476-1520)

| Step | Kotlin owner | Owned / shared state | Current hook | Proposed boundary |
|---|---|---|---|---|
| phase start, unit list | `CpPhase.aiPhaseInit/buildAiUnitList/sortAiUnitList` | `AiState` (owned); `UnitTable`, `PlaySt` (shared) | none (priority via `getUnitAiPriority`, plain fn) | `OrderPolicy`: ordered unit ids from a read-only state view; vanilla = current sort |
| order loop | `CpPhase.cpOrderMain` -> `startCpDecide` | `AiState.orderState/unitIt` | none | stays engine-owned; mod only supplies the list above |
| per-unit setup | `CpDecide.cpDecideMain` | writes `ActiveUnit` (shared with player phase), `AiState.unitIt/decideState`, `UnitTable` state bit US_HAS_MOVED_AI | `hooks.renderBmMap`, `refreshUnitSprites` (render only) | keep; setup is engine bookkeeping, not policy |
| decide | `CpDecide.aiDecideMainFunc` (var, default `decideMainStep`) | `AiDecision` (owned, shared by reference with `CpPerform`/`CpEquip`), `AiState` | **only existing whole-decision seam**; also `tryExecScriptA/B`, `decideScriptA/B`, `specialItemFunc` | `AiStrategy.decide(view, out: AiDecision)`: whole-decision replacement writing `AiDecision` through `aiSetDecision`-style validated setter (M7-26) |
| scripts | `CpScript` (`AiTry` hooks into `CpBattle`/`CpUtility`) | `AiState`, `cmdResult`; draws from `RngService` (shared global stream) | `CpScript.hooks.attemptOffensiveAction/tryDoStaff/moveTowardsEnemy` set in `CpPhase` init | targeting / movement / scoring strategies behind these three; mods must draw RNG only via the service so call order stays replayable |
| scoring | `CpBattle.aiAttemptOffensiveAction`, `CpUtility` | `AiCombatSimulationSt` (owned), `BmBattle.battleUnits` (a var set by boot: shared scratch), `BmMap` danger/range maps (shared, written by `AiInitDangerMap`) | `hooks.battleGenerateSimulation` etc. (EP-12 stand-ins) | `ScoreFn(candidate) -> int`; simulation stays engine side, mod reads result only |
| legality | none: `aiSetDecision` stores unchecked fields; `CpPerform` trusts `AiDecision` | - | none | validate decision (reachable tile, target in range, item owned) before `startCpPerform`; reject falls back to vanilla or wait |
| perform | `CpPerform.script` via `decide.hooks.startCpPerform` | `CpPerformState`, `AiTargetCursorState`, `ActiveUnit.action` (`ActionData`, shared with player phase), `Mu`, `CamMove.fields` | `perform.hooks.*` (render, music, MU, fade) | keep engine side; actions go through `PlayerPhase.applyUnitAction` (see C) |
| phase end | `CpPhase.aiPhaseCleanup`, `PhaseStartEnd` | `PlaySt.faction`, unit state bits | `main.hooks.cpPhase/berserkCpPhase` (proc scripts) | turn policy: M7-29 |

Blockers: `ActiveUnit`/`ActionData`, `AiDecision` and `BmMap` are single shared mutable instances (no per-call
view); `CpPhase`/`CpDecide` take 8-9 constructor collaborators and `CpPhase.utility/battle` are null unless
loader+map+entity maps exist (partial wiring is possible only in tests); `CpEquip`, `CpPerform` and `CpUtility`
keep references to the same `AiDecision`, so replacing the object, not its contents, breaks them.
`CpPerform` handles only move-only and combat actions (others throw/skip), so a custom AI action has nowhere to land.

**B. One camera scroll** (`Camera` object, `CamMove`, `BmSt`)

| Step | Kotlin owner | Owned / shared state | Current hook | Proposed boundary |
|---|---|---|---|---|
| target from tile | `Camera.getCameraAdjustedX/Y`, `getCameraCenteredX/Y`, `storeAdjustedCameraPosition*` | stateless `object`; reads `BmSt.cameraX/Y/cameraMax*` | none (constants `MARGIN_*`, 15x10 window, hard-coded) | `CameraPolicy.target(bm view, tileX, tileY)`; margins/window as data |
| scripted ease | `CamMove` (`ensureCameraOntoPosition`, `startCameraOntoPositionCentred`, `onInit/onLoop`) | `CamMove.fields` (one set shared by every `ProcScr_CamMove` proc, like the C), `animTable`; writes `BmSt.cameraX/Y` | boot injects the single instance into `PlayerPhase`, `CpPerform`, `PlayerPhaseWiring`, event code, `AttackMapSelect`, `EfxHpBar`, `MapAnimApi`; `camMoveExists` probes by `engine.find(script)` | one `CameraService` facade over `CamMove`; callers stop probing the proc |
| cursor-follow scroll | `PlayerPhase.handleMoveCameraWithMapCursor/handleMoveMapCursor` (inside the player phase, steps 4/8 px) | writes `BmSt.cameraX/Y`, `unk32/34/36/37`, `playerCursorDisplayX/Y` | none | move out of `PlayerPhase` behind `CameraPolicy.follow`; biggest coupling |
| apply to display | `BmMapRender`, `MapCursor`, sprites read `bm.cameraX/Y` directly | `BmSt` (single global, `loop.bmSt`) | none | `Viewport` transform (world->screen) read by renderers and hit-testing (M7-27) |

Blockers: camera position is the shared `BmSt` fields in pixels, read directly by menus (`cursorScreenX =
cursorTargetX - cameraX`), renderers and MU code, so a zoom or non-integer camera needs every reader moved to a
transform first. `CamMove.fields` is global per script, so two simultaneous moves are impossible. The
original-frame easing (`animTable`, `onLoop`) is ported and verified (`CamMoveDifferentialTest`,
`CameraScrollDifferentialTest`); a replacement must keep vanilla as the default implementation of the same interface.

**C. One player action** (select, move, choose action, apply)

| Step | Kotlin owner | Owned / shared state | Current hook | Proposed boundary |
|---|---|---|---|---|
| read input | `PlayerPhase.playerPhaseMainIdle`, `handlePlayerCursorMovement`, `handleMapCursorInput` read `KeyStatus` (`newKeys`, `repeatedKeys`) and mask `A/B/L/R/START` back | `KeyStatus` (shared, mutated by the phase), `BmSt.playerCursor*`, `cursorTarget*` | `hooks.lButton/rButton/startButton/unitSelectEnabled` | `PlayerIntent` (select tile, confirm, cancel, menu item); a key adapter produces it; touch gives a tile directly |
| select | `getPlayerSelectKind`, `unitBeginAction` | `ActiveUnit` (`unit`, `unitId`, move origin), `BmMap.unit/movement` | `hooks.canCharacterBePrepMoved` only | selectability predicate in the rules layer (M7-29) |
| move range / path | `playerPhaseInitUnitMovementSelect`, `playerPhaseRangeDisplayIdle` (cursor-driven), `canMoveActiveUnitTo`, `MovementMap`, `PathArrow` | `BmMap.movement`, `workingMovementScript` (shared array; `CpPerform` reuses it) | `unitMenu.hooks.displayMoveRangeGraphics` etc. | `canMoveTo(x,y)` + path result; direct-tap destination needs no cursor stepping but today the range proc reads the cursor tile each frame |
| action menu | `UnitActionMenu` + `MenuTargets` (`PlayerPhaseWiring.wireMenus`, ~25 `unitMenu.hooks.*` lambdas) | menu proc state, `SelectTargets` (player's own list) | per-entry `*TargetCount` hooks, `unportedUsability` | registered action list (id, availability, target lister, execute) replacing the fixed menu table |
| prepare / apply | `playerPhasePrepareAction`, `applyUnitAction` (only COMBAT, WAIT, TRAPPED; others `check` fail), `CombatAction.actionCombat` | `ActionData.unitActionType`, `RngService` (call order is part of replay) | `hooks.applyUnitAction`, `hooks.actionCombat` (`ProcBoolFunc`) | `ActionRegistry[type] -> execute(proc)`; same entry used by `CpPerform` |
| finish | `playerPhaseFinishAction`, `handlePostActionTraps`, `runPotentialWaitEvents` | unit state bits, event engine | `hooks.execTrap` | rules layer / turn policy (M7-29) |

Blockers: input is polled inside the phase procs, so direct touch has to fake `newKeys`/cursor stepping today;
the unit-action id space is an Int with `check` on unknown values (no registration); `PlayerPhase` has ~40 `hooks`
and a 1600-line body mixing input, cursor, camera and rules.

**Coupling summary.** Hard: `BmSt` camera/cursor fields (B, C), one shared `ActiveUnit`/`ActionData` and
`AiDecision` (A, C), polled `KeyStatus` (C), `RngService` order (A, C). Soft (already an interface): `aiDecideMainFunc`,
`CpScript.hooks.*`, `PlayerPhase.hooks.applyUnitAction/actionCombat`.

**Follow-up rows (small, each one system; to add to TASKS when scheduled).**
1. M7-26a: validate `AiDecision` before `startCpPerform` (legality), vanilla no-op test.
2. M7-26b: `AiStrategy` interface over `aiDecideMainFunc`, vanilla default, replay test of one enemy turn.
3. M7-27a: `CameraService` facade for the 9 `CamMove` callers; move cursor-follow scroll out of `PlayerPhase`.
4. M7-27b: `Viewport` transform read by `BmMapRender`, `MapCursor`, menu cursor x.
5. M7-28a: `PlayerIntent` input layer that `playerPhaseMainIdle` consumes; key adapter reproduces vanilla.
6. M7-28b: `ActionRegistry` behind `applyUnitAction`/`CpPerform`; menu entries from the registry.
