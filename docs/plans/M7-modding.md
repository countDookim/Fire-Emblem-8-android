# M7 modding audit (task M7-0)

Audited 2026-11-11 at `74c9fa2`, read-only. Method: grep and reading of the classes named below. "Not checked"
means I did not read it. Statuses: `in place` (works in production code), `partial`, `missing`, `contradicted`
(code or another doc says the opposite of the brief). Test-only means a unit/differential test exercises it
but no production code path (`:app`, `Fe8Boot`, `Fe8Game`) does.

## 1. Summary

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

## 3. Decisions needed from the owner

1. **ROM reads vs "all data through the VFS"** (section 2): **decided 2026-11-11: option A** (ROM stays the
   vanilla source; per-table VFS overrides; [ADR 0015](../decisions/0015-rom-base-mod-overrides.md)). Rows below
   with "D1" in Deps are no longer blocked by it.
2. **Which groups must be moddable at 1.0 of M7?** Candidates: unit/class/item/character stats, chapter data and
   maps, event scripts, text, textures, audio. Anything not named stays ROM-only.
3. **Vanilla checks with mods on:** differential tests compare against the ROM. Mods change the data under test.
   Keep tests on the ROM-only path (default) and test mod paths separately? Or add a "no mods" fixture only?
4. **Mod file layout:** the extractor writes `fonts/`, `portraits/`, `ui/`, `songs/` (no `gfx/`/`audio/` prefix);
   MODDING.md documents `gfx/` and `audio/`. Keep the extractor layout (and fix MODDING.md) or rename.
5. **Event-bus scope:** which events (`map.onMapEnter`, `battle.onBattleStart`, ...) must exist for M7, since they
   attach to systems that are only now ported (M6-B..D).
6. **Debug menu scope:** list of tools for M7 (warp, flags/vars, items, battles, inspect), and whether it is
   Compose or an in-game overlay. Debug menus of the decomp are out of scope (ROADMAP).
7. **Camera not tied to 240x160** (brief) versus "Fixed 240x160" (ARCHITECTURE): keep fixed (amend the brief) or
   plan a wide-view camera.
8. **Saves:** switch the running game's SRAM to `SaveContainer` with per-mod sections, or keep raw SRAM and store
   mod sections in a side file.
9. **Mod format on disk:** zip support and `override/` layer now or later; JSON schema validation at load for
   mods only, or for base data as well.

## 4. Proposed M7 rows

Format follows ROADMAP task rules where they apply: one behaviour per row, concrete acceptance, headless test for
WIRE rows, device check at the end. Rows marked "D1" depend on owner decision 1 (and may change shape).

| ID | Kind | Task | Acceptance | Deps | Status |
|---|---|---|---|---|---|
| M7-1 | PORT | `ModManifest` parser (`mod.json`): validate id, semver, `formatVersion`, dependencies | Unit test: valid manifest parses; missing id, bad version and unknown `formatVersion` each give a named error | — | todo |
| M7-2 | PORT | Mod resolver: dependency check and ordering (deps, then `loadOrder`, then id), cycle and missing-dependency errors | Unit test: 3 mods with deps load in the expected order; cycle and missing dep report the mod ids | M7-1 | todo |
| M7-3 | PORT | Zip `VfsLayer` (jvm + android) and `override/` debug layer | Test: a zip and a folder with the same path; top layer wins; `list()` unions; zip with `..` paths is refused | M7-1; D9 | todo |
| M7-4 | WIRE | Create the `VirtualFileSystem` in `:app` (base + mods dir), mount mods in resolved order, show loaded mods and errors in the launcher | Headless test with a fake mods dir; MANUAL_TEST entry; allocation test unchanged | M7-2, M7-3 | todo |
| M7-5 | PORT | JSON table loader with schema check at load (`DataTable` impls compiled to primitive arrays) for one table, `items.json` | Test: loads extracted `items.json`; a schema-violating file gives a named error; no allocation after load | D1 | todo |
| M7-6 | PORT | Item data through the loader: `ItemInfo` reads the compiled table, ROM fallback if no file | Existing `ItemInfoReferenceTest` still passes with ROM path; test with a mod file changing one item field changes `ItemInfo` output | M7-5; D1 | todo |
| M7-7 | PORT | Same for characters and classes (`UnitLoader`, `UnitTable`) | Existing unit tests pass unchanged; mod override of one class stat test | M7-6; D1 | todo |
| M7-8 | PORT | Chapter data and `.tmj` maps through the loader (`ChapterData`, `BmMap`) | Existing `ChapterDataRomTest` passes; mod override of one chapter field test | M7-5; D1 | todo |
| M7-9 | WIRE | Script loader: assemble `.fe8s` from the VFS to `ScriptWords` mod words; map a script id to an address; run from a chapter event | Headless end-to-end test (extends `ModScriptEndToEndTest`) runs a mod script from `Fe8Boot`; MANUAL_TEST entry | M7-4 | todo |
| M7-10 | PORT | Text loader: `text/<lang>/messages.json` override with tags; `TextTag` set completed against the extracted tags | Test: mod string replaces message id N; unknown tag errors by name; vanilla ids unchanged | D1, D2 | todo |
| M7-11 | WIRE | Texture override into the renderers for one asset (item icons) via `TextureOverrides` | Headless test: a `@2x.png` is picked; frame compare with and without; MANUAL_TEST entry | M7-4; D2 | todo |
| M7-12 | INV | Extractors for data with no extracted form that mods would need (weapon triangle, affinity, effectiveness lists; unit lists for all chapters) | Each new file has a schema and passes `test_schemas.py`; extraction CI check updated | D2 | todo |
| M7-13 | WIRE | Feature flags: register one non-vanilla flag in a system, behaviour behind it, `vanilla=true` keeps bit-exact | Differential test passes with flags off; test shows the flag changes the behaviour with `vanilla=false` | — | todo |
| M7-14 | WIRE | Use `Fe8Game.mechanics` in `Fe8Boot` (replace its own `Mechanics()`) | Test: swapping `battleRolls` on the game changes a battle in `Fe8Boot`; vanilla differential tests unchanged | — | todo |
| M7-15 | PORT | Event bus: first gameplay events chosen in D5, fired by the ported systems | Test per event: fires once with the expected arguments; zero-allocation test | D5; M6-B..D | todo |
| M7-16 | WIRE | Flag/var registries into `Fe8Boot` and event scripts; mod flags saved in `mod:<id>` sections | Test: a mod flag set by a script survives save and load; vanilla bytes unchanged | D8 | todo |
| M7-17 | WIRE | Save through `SaveContainer` (or the side file, per D8); `.sav` import entry in the launcher | Test: import of the reference `.sav` loads; round trip keeps mod sections; MANUAL_TEST entry | D8 | todo |
| M7-18 | WIRE | Publish `UiElements` from at least the menu screens ported by then; touch mod example | Test: `hitTest` returns the element for a tap on a menu row | M6-B | todo |
| M7-19 | PORT | Hot reload: watch folder layers in debug builds, invalidate loaders between ticks | Test: change a file in a temp mod folder; next tick sees the new data; release build has no watcher | M7-4, M7-5 | todo |
| M7-20 | WIRE | Debug menu (debug builds only) with the tools chosen in D6 | Headless test of each tool's state change; `BuildConfig` guard test shows it absent in release; MANUAL_TEST entry | D6 | todo |
| M7-21 | doc | Bring MODDING.md, ARCHITECTURE principle 6 and the brief in line with D1/D4/D7 and the finished rows | Every "planned" mark in MODDING.md matches code; no row's status contradicts a doc | D1, D4, D7 | todo |
| M7-22 | tool | Layering check for ROM addresses: fail CI if a `gbafe.*` file (outside `gbafe.extract.<game>`) holds an FE8 `0x08xxxxxx` constant | `test_layering.py` case passes today; fails on a seeded offender | — | todo |
| M7-23 | WIRE | Milestone close: mod example (texture + stat + script) loaded on the device | Device check by the owner; MANUAL_TEST entry | M7-4..M7-9 | todo |

Not checked: `ScriptVm` implementation beyond the contract file, Tiled layers other than units, the full
`TextTag` list versus extracted control codes, whether `items.json` `effectiveAgainst` covers the `BmBattle`
lists, `testing/` tests that read the VFS.
