# Readability review

Owner, 2026-11-07: every **owner-PC assistant session** (not the scheduled Cowork runs) reviews at least one
section below for human readability, until every section is done. CLAUDE.md rule 11.

## What "reviewed" means

- **Names:** every `val`/`var`/parameter/function/class name says what it holds or does. Single letters only for
  loop indices, coordinates (`x`, `y`) and one-line lambdas. Decomp placeholders (`unk1C`, `sub800E640`,
  `gUnknown...`) stay only while their meaning is unknown; once known, rename and keep the decomp name in the KDoc
  (`` `unk1C` in the decomp``) so the code can still be traced to the C.
- **Docs:** every class, object, interface and non-trivial function (private too) has a KDoc saying what it does
  in game terms; non-obvious blocks have a comment saying why. Stale or wrong comments are fixed or removed.
- **Meaningful and correct (owner, 2026-11-11):** a comment tells a reader something the code does not: what it
  means in the game (which menu, which unit state, which rank), why it is done, or where it comes from. Every
  claim is checked against the decomp (function body, headers, `include/constants/*.h` names); magic numbers get
  their decomp constant name and meaning (`0x2B` → `CLASS_BISHOP`). No restating the code ("returns the value"),
  no guessed meanings: if the meaning is unknown, say so. Claude delegates this to Sonnet, not Haiku (GPT
  uses Terra and Gemini uses Flash, as always); the parent spot-checks at least three new KDocs per file
  against the decomp before committing.
- **Untouched:** `Ported from` / `Transcribed from` lines and `@VerifiesPort` tags (only reworded, never
  upgraded), behaviour (renames and comments only), public API names that other modules use unless all callers
  are updated in the same commit.
- **Done when:** the section's files are read in full, comments pass the meaning check above, fixes are committed, `./gradlew jvmTest test` and the
  module's Dokka are green, and the row below says `done <date> <commit>`.

Rows marked done before 2026-11-11 10:00Z were reviewed for names and no behaviour change; their comments were
not checked for meaning against the decomp. They carry `audit todo` until a meaning audit is committed.

Sizes are main-source files/lines on 2026-11-07; counts for open rows were refreshed 2026-11-11 (done rows keep
their old counts); new packages get a row when they appear.

| Module | Package | Files | Lines | Status |
|---|---|---:|---:|---|
| app | fe8.app | 9 | 1270 | done 2026-11-09 1941586; audit todo |
| engine/core | gbafe.core.flags | 6 | 849 | done 2026-11-07 c7b7651; audit done 2026-11-12 02666d9 |
| engine/core | gbafe.core.math | 1 | 48 | done 2026-11-07 7fab3cc; audit done 2026-11-11 fce7054 |
| engine/core | gbafe.core.mechanics | 1 | 60 | done 2026-11-07 7fab3cc; audit done 2026-11-11 fce7054 |
| engine/core | gbafe.core.rng | 1 | 181 | done 2026-11-07 7fab3cc; audit done 2026-11-11 fce7054 |
| engine/core | gbafe.core.time | 1 | 30 | done 2026-11-07 7fab3cc; audit done 2026-11-11 fce7054 |
| engine/core | gbafe.proc | 2 | 573 | done 2026-11-07 c0f92eb; audit done 2026-11-11 44d2337 |
| engine/data | gbafe.data | 3 | 88 | done 2026-11-07 c0f92eb; audit done 2026-11-11 44d2337 |
| engine/extract | gbafe.extract | 5 | 197 | done 2026-11-07 200eae1; audit done 2026-11-11 f0b91dd |
| engine/extract | gbafe.extract.fe8 | 2 | 278 | done 2026-11-07 200eae1; audit done 2026-11-11 f0b91dd |
| engine/extract | gbafe.extract.fe8.chapters | 1 | 102 | done 2026-11-07 50ec53d; audit done 2026-11-11 f0b91dd |
| engine/extract | gbafe.extract.fe8.characters | 1 | 102 | done 2026-11-07 50ec53d; audit done 2026-11-11 f0b91dd |
| engine/extract | gbafe.extract.fe8.classes | 1 | 132 | done 2026-11-07 50ec53d; audit done 2026-11-11 f0b91dd |
| engine/extract | gbafe.extract.fe8.events | 4 | 2359 | done 2026-11-09 a0981c8; audit todo |
| engine/extract | gbafe.extract.fe8.items | 1 | 111 | done 2026-11-07 21ee533; audit done 2026-11-11 e7a92eb |
| engine/extract | gbafe.extract.fe8.maps | 1 | 177 | done 2026-11-07 21ee533; audit done 2026-11-11 e7a92eb |
| engine/extract | gbafe.extract.fe8.mapsprites | 1 | 140 | done 2026-11-07 21ee533; audit done 2026-11-11 e7a92eb |
| engine/extract | gbafe.extract.fe8.portraits | 1 | 97 | done 2026-11-07 21ee533; audit done 2026-11-11 e7a92eb |
| engine/extract | gbafe.extract.fe8.terrains | 1 | 65 | done 2026-11-09 7c6df24; audit todo |
| engine/extract | gbafe.extract.fe8.ui | 3 | 189 | done 2026-11-09 94e08df; audit todo |
| engine/extract | gbafe.extract.fe8.units | 1 | 86 | done 2026-11-09 0dfc62b; audit todo |
| engine/extract | gbafe.extract.font | 1 | 77 | done 2026-11-09 92119c9; audit done 2026-11-12 7d13c5d |
| engine/extract | gbafe.extract.m4a | 1 | 311 | done 2026-11-09 19d121b; audit todo |
| engine/extract | gbafe.extract.text | 1 | 55 | done 2026-11-09 0194222; audit done 2026-11-12 7d13c5d |
| engine/input | gbafe.input | 2 | 238 | done 2026-11-07 697847c; audit done 2026-11-12 02666d9 |
| engine/render | gbafe.render | 10 | 1592 | done 2026-11-09 83f2cb6; audit done 2026-11-12 abaf60c |
| engine/save | gbafe.save | 7 | 621 | done 2026-11-09 d3b21d6; audit done 2026-11-12 ad1504f |
| engine/script | gbafe.script | 7 | 920 | done 2026-11-09 d3b21d6; audit todo |
| engine/text | gbafe.text | 6 | 926 | done 2026-11-09 d3b21d6; audit done 2026-11-12 7d13c5d |
| game | fe8.game | 8 | 1999 | done 2026-11-09 ee10196; audit todo |
| game | fe8.game.bm (map, camera, state, cursor, music, chapter data: 16 files) | 16 | 2964 | done 2026-11-10 bac8ed1; audit todo |
| game | fe8.game.bm (battle: BattleGenerate, BattleUnit, BmBattle, ItemInfo) | 4 | 2316 | done 2026-11-11 49679cb; audit done 2026-11-11 39aa20b |
| game | fe8.game.bm (PhaseIntro, UnitTable, MapAnimations, UnitSprites, UnitSpriteHandles, UnitSpritesOam) | 6 | 1732 | done 2026-11-11 d9263c3; audit todo |
| game | fe8.game.bm (UnitLoader, Mu, MuCtrl, MuSpriteAnim, PlayerPhase, BattleMapStart) | 6 | 3863 | todo |
| game | fe8.game.event (EventEngine, EventEngineProc, EventState, ReleaseEventStubs) | 4 | 845 | done 2026-11-10 ff286fe / 0bfe1ab; audit todo |
| game | fe8.game.event (remaining command handlers and script formats) | 18 | 3299 | done 2026-11-10 077a47d; audit todo |
| game | fe8.game.flags | 1 | 69 | done 2026-11-09 0194222; audit done 2026-11-12 ad1504f |
| game | fe8.game.opanim (OpAnim, OpAnimHs, EarlyStartUi) | 3 | 3225 | done 2026-11-11 8cf022f / 4735f55; audit todo |
| game | fe8.game.opanim (OpSubtitle*.kt) | 7 | 796 | done 2026-11-10 f3ad834 / d05e87f; audit todo |
| game | fe8.game.save | 8 | 2541 | done 2026-11-09 1941586; audit todo |
| game | fe8.game.savemenu | 2 | 156 | done 2026-11-09 81db35b; audit todo |
| game | fe8.game.scene | 7 | 3521 | done 2026-11-09 c3bb2ed; audit done 2026-11-12 24bb602 |
| game | fe8.game.text | 1 | 64 | done 2026-11-09 0194222; audit done 2026-11-12 24bb602 |
| game | fe8.game.title | 1 | 824 | done 2026-11-09 83f2cb6; audit todo |
| game | fe8.game.ui | 5 | 1069 | done 2026-11-11 262e4fc; audit todo |
| game | fe8.game.worldmap (WorldMapRm, WorldMapRmBaPalAnim, WorldMapPaletteFade, WorldMapText, WorldMapTextFadeEvents) | 5 | 1519 | done 2026-11-12 8f6f480 |
| game | fe8.game.worldmap (Nodes, WmNodeData, GmData, ApHost, Sms, Path, NodeIcons, Hblank, LineFade, Cursor, Beginning, Exit, MainScript; Text/Misc/Spawn/Face/BigMap events) | 18 | 2553 | done 2026-11-13 8dfd5f7 |
| game | fe8.game.worldmap (UnitEvents, MuEntry, RmBorder, AutoMu, Dot, Route, Main) | 7 | 3097 | done 2026-11-13 5e95a34 |
| game | fe8.game.worldmap (WorldMapFace) | 1 | 606 | done 2026-11-13 98805fa |
| game | fe8.game.worldmap (WorldMapScroll) | 1 | 661 | done 2026-11-13 85a15d0 |
| game | fe8.game.worldmap (WorldMapScreen) | 1 | 633 | done 2026-11-13 418759a |
| game | fe8.game.worldmap (WorldMapUnit) | 1 | 936 | done 2026-11-13 784af68 |
| game | fe8.game.worldmap (WorldMapMu) | 1 | 1066 | done 2026-11-13 2df8623 |
| gba/audio | gba.audio | 17 | 3162 | done 2026-11-09 1db6a62; audit done 2026-11-12 520ab72 |
| gba/core | gba.core | 1 | 21 | done 2026-11-07 697847c; audit todo |
| gba/core | gba.core.event | 2 | 101 | done 2026-11-07 697847c; audit todo |
| gba/core | gba.core.sram | 5 | 380 | done 2026-11-07 1b2b121; audit todo |
| gba/core | gba.core.time | 2 | 111 | done 2026-11-07 697847c; audit todo |
| gba/data | gba.data | 7 | 585 | done 2026-11-09 19d121b; audit todo |
| gba/extract | gba.extract | 9 | 572 | done 2026-11-09 19d121b; audit todo |
| gba/input | gba.input | 3 | 362 | done 2026-11-07 52e07f0; audit todo |
| gba/render | gba.render | 10 | 1388 | done 2026-11-09 83f2cb6; audit done 2026-11-12 abaf60c |
| gba/testkit | gba.testkit | 5 | 352 | done 2026-11-09 19d121b; audit todo |
| gba/vfs | gba.vfs | 4 | 228 | done 2026-11-07 1b2b121; audit todo |
| testing | fe8.testing | 3 | 295 | done 2026-11-09 db51e13; audit todo |
| tools/extract | gbafe.extract | 3 | 182 | done 2026-11-09 81db35b; audit todo |
| tools/extract | gbafe.extract.fe8 | 1 | 24 | done 2026-11-09 81db35b; audit todo |
