# HANDOFF

_Updated: 2026-10-24 (owner follow-up). Brief changed: runs are now 40–45 min and do not wait for CI at the end (see Amendments)._

## NEEDS USER
_(nothing blocked)_

## Status
- This run: M1-07 (Dokka KDoc lint, fails on any undocumented public API), M1-08 (`progress.py` self-test,
  in CI), M1-12 (harness `savestate`/`loadstate`/`--frames-every`, ROM repo 2cf109e, release republished),
  M2-01 (`:tools:extract` CLI skeleton), M2-02 (`engine:data` `Lz77` + ROM golden test).
- Last CI before this run: ✅ master. CI now also runs `python3 -m unittest discover tools/tests` and Dokka
  with `failOnWarning` — check the first run on these commits.
- Session setup (measured 2026-10-24 21:51): 118 s cold. Dokka deps are now in the ROM repo's seeded
  Maven cache (deps/maven-files.txt), so `-Pfe8.docs=true` no longer goes through the slow mirror.
- Branch: `master`. No `wip/` branches.
- Progress: 4 / 8352 verified (src/rng.c). Lz77 is a BIOS routine, not a decomp function: it does not count.

## Next task: M2-03 — 4bpp/8bpp tiles + BGR555 palette → indexed PNG writer
- Files: new `engine/data/src/main/kotlin/fe8/data/Tiles.kt` (decode 4bpp/8bpp 8×8 tiles into a
  palette-index `ByteArray` image; `Bgr555` → ARGB helper exists as `FrameBuffer` companion in engine:render —
  do not depend on render from data, duplicate the 3-line conversion or move it to engine:core),
  new `tools/extract/src/main/kotlin/fe8/extract/IndexedPng.kt` (write/read PNG colour type 3 with PLTE,
  `java.util.zip.Deflater`/`Inflater` + CRC32, no ImageIO so indices are preserved exactly).
- Do: tiles→indices (4bpp: low nibble = left pixel), indices+palette→PNG, PNG→indices+palette; a test that
  decodes `Img_TriPegasusKnightBG` (0x080E17C0, LZ77, 8192 bytes = 256 4bpp tiles) → PNG → back to tile
  bytes and asserts byte-identical; plus small synthetic unit tests. ROM-address tests go in `:testing`
  (only `:testing` knows GBA addresses), see `testing/src/test/kotlin/fe8/testing/Lz77RomTest.kt`.
- Done when: round-trip test passes (not skipped) and `./gradlew dokkaGenerate -Pfe8.docs=true` stays clean.

## Known bugs / caveats
- Maven Central through the session proxy returns spurious 404/429; `tools/setup.sh` starts
  `tools/maven_mirror.py` (retries). CI is unaffected.
- `GameSurfaceView` is untested on a real device (no gameplay yet; manual test only).
- Static C names can repeat across files; `RamSymbols[name]` throws if ambiguous — use unique names.
- ROM data symbols in `decomp/fireemblem8.nm.txt` (ROM repo) mostly have no size (asm labels): use the
  next symbol's address as the span, as `Lz77RomTest` does.
- Dokka's Kotlin types are not on the build classpath outside docs builds; `configureDokkaLint()` in
  `build.gradle.kts` configures it via Groovy property access — keep it that way.
