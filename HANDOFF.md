# HANDOFF

_Updated: 2026-10-25 07:21 UTC (scheduled run, started 06:51)._

## Owner decisions 2026-10-25 (read docs/decisions/0009)
- The app is a 1:1 copy. Modding = extension points only; ship no touch gameplay, QoL flags or alternate
  formulas. Only touch input shipped: on-screen GBA buttons (task M3-15).
- Every ported system adds strategy interfaces to `gbafe.core.mechanics.Mechanics` (vanilla impl only,
  alternatives only in tests). Battle must use `mechanics.battleRolls` for hit/crit/skill rolls, never
  `rng.roll1/roll2` directly.

- Reuse by future FE6/FE7 ports (docs/decisions/0010): packages are now `gbafe.*` (engine, extract) vs
  `fe8.*` (game, app, testing, `gbafe.extract.fe8`); data formats `gbafe.<table>` + `"game": "fe8"`.
  New shared systems go in `gbafe.<system>` modules; FE8-only ones (world map, skirmishes...) in `fe8`.
  Known gap: `ExtractCli` calls the FE8 extractors directly; add `--game` dispatch when touching it.

## NEEDS USER
_(nothing blocked)_

## Status
- This run: M2-03 (`Tiles`/`IndexedImage`/`Bgr555` + `IndexedPng`, ROM round trip byte-identical),
  M2-04 items, M2-05 classes, M2-06 characters, M2-07 terrains (schemas in `schemas/`, extractors in
  `tools/extract/.../{items,classes,characters,terrains}`, CLI `--symbols`), M2-08 `MsgHuffman` (port of
  `asm/arm.s: DecodeString`, verified; all 3404 messages decode). Extracted JSON committed to the ROM repo
  (`extracted/data/`, 4f3cb5b).
- Last CI before this run: ✅ master. New in CI this run: the Python step now `pip install`s
  `jsonschema==4.26.0` (`--break-system-packages`) and runs `tools/tests/test_schemas.py`, which validates
  JSON that the `*RomTest`s dump into `testing/build/extracted/` — check the first CI run on these commits.
- Session setup: 107 s. Branch: `master`. No `wip/` branches.
- Progress: 5 / 8352 verified (rng.c + DecodeString).

## Next task: M2-09 — message control codes → readable tags; all strings → `text/en/*.json`
- Spec: decomp `texts/textdefs.txt` (code table: `[NL] = 1`, `[A] = 3`, `[LoadFace] = 16` followed by a
  2-byte face id like `[FID_Anna]`, `0x80, XX` two-byte codes such as `[G] = 0x80, 0x05`, `[X] = 0` end)
  and `texts/texts.txt` (all strings in source form, `## MSG_xxx` headers). Get them with the sparse
  clone below (`git sparse-checkout set --no-cone include src texts asm`).
- Files: extend `engine/text/src/main/kotlin/gbafe/text/TextTag.kt` (or a generated code table) and add
  `MessageCodec` in engine:text: bytes → tagged string (`{NL}`, `{A}`, `{LoadFace FID_Anna}`, ...) and
  tagged string → bytes. Unknown bytes must survive as `{0xNN}`. Extractor step `text/en/messages.json`
  (`{format: "gbafe.text", version: 1, entries: [{id: "MSG_001", text: "..."}]}` + schema).
- Done when: a `:testing` ROM test decodes all 3404 strings (`MsgHuffmanRomTest` shows how) to tags and
  re-encodes each to identical bytes; spot-check 2 strings against `texts/texts.txt`; schema test passes.

## Known bugs / caveats
- The decomp **source** is not in the session assets (only `decomp/fireemblem8.{map,nm.txt}` in the ROM
  repo). Extraction with names needs a checkout: `git clone --filter=blob:none --sparse
  https://github.com/FireEmblemUniverse/fireemblem8u` then `git checkout 04d1edb…` + `git sparse-checkout
  set --no-cone include src texts asm` (~10 s, works through the session proxy). Tests hard-code the few
  names/addresses they need, so CI does not need it.
- Classes inline copies of shared terrain tables; terrains.json also lists them by symbol. Decide with the
  data loader (later M2) whether classes should reference tables by name.
- `effectiveAgainst` in items.json holds class numbers until items and classes are cross-linked.
- Always `source .assets.env` in the same shell before Gradle: without `FE8_MAVEN_MIRROR` set, Dokka
  resolution goes straight to Maven Central and fails with 429s.
- Long Gradle runs (Dokka with `-Pfe8.docs=true`) can exceed the 2-min tool timeout on a cold daemon: run
  them in the background, or repeat (second run is warm).
- Maven Central through the session proxy returns spurious 404/429; `tools/setup.sh` starts
  `tools/maven_mirror.py` (retries). CI is unaffected.
- `GameSurfaceView` is untested on a real device (no gameplay yet; manual test only).
- Static C names can repeat across files; `RamSymbols[name]` throws if ambiguous — use unique names.
- ROM data symbols in `decomp/fireemblem8.nm.txt` (ROM repo) mostly have no size (asm labels): use the
  next symbol's address as the span, as `Lz77RomTest` does.
- Dokka's Kotlin types are not on the build classpath outside docs builds; `configureDokkaLint()` in
  `build.gradle.kts` configures it via Groovy property access — keep it that way.
