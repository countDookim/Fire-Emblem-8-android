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
- **Untouched:** `Ported from` / `Transcribed from` lines and `@VerifiesPort` tags (only reworded, never
  upgraded), behaviour (renames and comments only), public API names that other modules use unless all callers
  are updated in the same commit.
- **Done when:** the section's files are read in full, fixes are committed, `./gradlew jvmTest test` and the
  module's Dokka are green, and the row below says `done <date> <commit>`.

Sizes are main-source files/lines on 2026-11-07; new packages get a row when they appear.

| Module | Package | Files | Lines | Status |
|---|---|---:|---:|---|
| app | fe8.app | 9 | 1270 | done 2026-11-09 1941586 |
| engine/core | gbafe.core.flags | 6 | 849 | done 2026-11-07 c7b7651 |
| engine/core | gbafe.core.math | 1 | 48 | done 2026-11-07 7fab3cc |
| engine/core | gbafe.core.mechanics | 1 | 60 | done 2026-11-07 7fab3cc |
| engine/core | gbafe.core.rng | 1 | 181 | done 2026-11-07 7fab3cc |
| engine/core | gbafe.core.time | 1 | 30 | done 2026-11-07 7fab3cc |
| engine/core | gbafe.proc | 2 | 573 | done 2026-11-07 c0f92eb |
| engine/data | gbafe.data | 3 | 88 | done 2026-11-07 c0f92eb |
| engine/extract | gbafe.extract | 5 | 197 | done 2026-11-07 200eae1 |
| engine/extract | gbafe.extract.fe8 | 2 | 278 | done 2026-11-07 200eae1 |
| engine/extract | gbafe.extract.fe8.chapters | 1 | 102 | done 2026-11-07 50ec53d |
| engine/extract | gbafe.extract.fe8.characters | 1 | 102 | done 2026-11-07 50ec53d |
| engine/extract | gbafe.extract.fe8.classes | 1 | 132 | done 2026-11-07 50ec53d |
| engine/extract | gbafe.extract.fe8.events | 4 | 2359 | done 2026-11-09 a0981c8 |
| engine/extract | gbafe.extract.fe8.items | 1 | 111 | done 2026-11-07 21ee533 (some long code lines left) |
| engine/extract | gbafe.extract.fe8.maps | 1 | 177 | done 2026-11-07 21ee533 (some long code lines left) |
| engine/extract | gbafe.extract.fe8.mapsprites | 1 | 140 | done 2026-11-07 21ee533 (some long code lines left) |
| engine/extract | gbafe.extract.fe8.portraits | 1 | 97 | done 2026-11-07 21ee533 (some long code lines left) |
| engine/extract | gbafe.extract.fe8.terrains | 1 | 65 | done 2026-11-09 7c6df24 |
| engine/extract | gbafe.extract.fe8.ui | 3 | 189 | done 2026-11-09 94e08df |
| engine/extract | gbafe.extract.fe8.units | 1 | 86 | done 2026-11-09 0dfc62b |
| engine/extract | gbafe.extract.font | 1 | 77 | done 2026-11-09 92119c9 |
| engine/extract | gbafe.extract.m4a | 1 | 311 | done 2026-11-09 19d121b |
| engine/extract | gbafe.extract.text | 1 | 55 | done 2026-11-09 0194222 |
| engine/input | gbafe.input | 2 | 238 | done 2026-11-07 697847c |
| engine/render | gbafe.render | 10 | 1592 | done 2026-11-09 83f2cb6 |
| engine/save | gbafe.save | 7 | 621 | done 2026-11-09 d3b21d6 |
| engine/script | gbafe.script | 7 | 920 | done 2026-11-09 d3b21d6 |
| engine/text | gbafe.text | 6 | 926 | done 2026-11-09 d3b21d6 |
| game | fe8.game | 8 | 1999 | done 2026-11-09 ee10196 |
| game | fe8.game.bm | 29 | 9298 | todo |
| game | fe8.game.event | 16 | 3197 | todo |
| game | fe8.game.flags | 1 | 69 | done 2026-11-09 0194222 |
| game | fe8.game.opanim | 3 | 3128 | todo |
| game | fe8.game.save | 8 | 2541 | done 2026-11-09 1941586 |
| game | fe8.game.savemenu | 2 | 156 | done 2026-11-09 81db35b |
| game | fe8.game.scene | 5 | 1921 | done 2026-11-09 c3bb2ed |
| game | fe8.game.text | 1 | 30 | done 2026-11-09 0194222 |
| game | fe8.game.title | 1 | 824 | done 2026-11-09 83f2cb6 |
| gba/audio | gba.audio | 17 | 2799 | done 2026-11-09 1db6a62 |
| gba/core | gba.core | 1 | 21 | done 2026-11-07 697847c |
| gba/core | gba.core.event | 2 | 101 | done 2026-11-07 697847c |
| gba/core | gba.core.sram | 5 | 380 | done 2026-11-07 1b2b121 |
| gba/core | gba.core.time | 2 | 111 | done 2026-11-07 697847c |
| gba/data | gba.data | 7 | 585 | done 2026-11-09 19d121b |
| gba/extract | gba.extract | 9 | 572 | done 2026-11-09 19d121b |
| gba/input | gba.input | 3 | 362 | done 2026-11-07 52e07f0 |
| gba/render | gba.render | 10 | 1388 | done 2026-11-09 83f2cb6 |
| gba/testkit | gba.testkit | 5 | 352 | done 2026-11-09 19d121b |
| gba/vfs | gba.vfs | 4 | 228 | done 2026-11-07 1b2b121 |
| testing | fe8.testing | 3 | 295 | done 2026-11-09 db51e13 |
| tools/extract | gbafe.extract | 3 | 182 | done 2026-11-09 81db35b |
| tools/extract | gbafe.extract.fe8 | 1 | 24 | done 2026-11-09 81db35b |
