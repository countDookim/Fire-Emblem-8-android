# Reusing the mGBA reference harness for another GBA game (FE6/FE7, ...)

Facts below were read from the ROM repo (`harness/`, `README.md`, `.github/workflows/`),
`gba/testkit`, `build.gradle.kts`, `tools/{setup,ci_fetch_assets}.sh` and `reference/README.md`.
Items marked (unverified) were not confirmed in those files. Never commit the ROM or anything
produced from it (CLAUDE.md rule 3).

## What the harness produces
- `bin/mgba-harness [--frames-every N] [--audio [--psg-mask N]] [--psg-writes] <rom> <script>
  <outdir>`: headless, deterministic mGBA (pinned `MGBA_TAG`, 0.10.5), source `harness/harness.c`,
  built by `harness/build.sh` (static libmgba, GBA core only, patched with an IO-write hook for
  `--psg-writes`).
- Script (`reference/scripts/<name>.txt`): `run N`, `hold KEYS N`, `press KEYS`, `dump NAME`,
  `dumpram NAME`, `savestate NAME`, `loadstate NAME`. `harness/gen_scripts.py` generates them from
  input timelines; an optional `# args: ...` header line passes harness flags.
- `harness/make_reference.sh` runs every script into `reference/out/<script>/` and writes
  `reference/out/PROVENANCE.sha1` (ROM, harness, scripts hashes).
- Per run dir: `manifest.txt` (`<dump> <frame>`), `inputs.bin` (u16 LE key mask per frame),
  `<dump>.{ewram,iwram,vram,pal,oam,io}.bin` + `<dump>.frame.rgb` (240x160 RGB888, top 5 bits
  valid); optional `states.txt`, `frames.rgb`+`frames.txt` (`--frames-every`),
  `audio.s16`+`audio.txt` (`--audio`), `psg_writes.txt` (`--psg-writes`). Frame numbers are emulated
  steps since boot, not game frames after a `loadstate`.

## How a test reads it
- `gba.testkit.ReferenceData.fromEnvironment()` opens `<fe8.assets | $FE8_ASSETS |
  .assets>/reference/out`; `run("<script>")` gives `dumps`, `inputs`, `region(dump, "ewram")`,
  `frameRgb`, audio and PSG accessors. `available` is true only if `boot_to_title/manifest.txt`
  exists (hard-coded run name; FE8 convention).
- `GbaMem.u16/u32(dump, base, address)` reads little-endian values by bus address (`EWRAM`, `IWRAM`
  bases).
- `RamSymbols.fromEnvironment()` loads `<fe8.reference>/ram_symbols.tsv`; `sym["name"].address` must
  be unique, so ambiguous `static` names fail. Test-only: GBA addresses never enter port code.
- Gradle (`build.gradle.kts`, all `Test` tasks) sets `fe8.assets` from `FE8_ASSETS` (default
  `.assets`), `fe8.reference` to `<repo>/reference`, and declares the manifest and `reference/out`
  presence as task inputs, so the build cache never restores results from an asset-less run.
- Tests call `assumeTrue(ref.available, ...)`: absent data means SKIPPED, which never counts as
  ported (docs/decisions/0006). A port-verification test replays `inputs.bin` through the port, then
  compares state by decomp symbol (or frames on 5-bit channels) at each dump frame.

## Game-specific vs reusable
Reusable as is:
- `harness.c`, `build.sh`, `make_reference.sh`, the script format, `PROVENANCE.sha1`.
- `gba:testkit` (`ReferenceData`, `GbaMem`; `RamSymbols` reads any file in the same TSV format).
- `tools/gen_reference.py` structure (nm/map parsing, TSV columns); the `@VerifiesPort` policy; the
  shape of the ROM-repo `publish.yml`.

Game-specific:
- The ROM and its SHA1; the decomp repo and commit pin.
- `gen_reference.py` `SYSTEM_RULES` and parsing heuristics (written for the FE8 decomp; fit for
  FE6/FE7 is unverified).
- `reference/*.tsv`; input timelines in `gen_scripts.py` (boot frames, menu paths).
- `ReferenceData.available` probes run `boot_to_title`; property names are `fe8.*`: rename or alias
  them (or keep a run of that name).

## Checklist for a new game
1. Create a private ROM repo (or directory) per FE8's layout: `rom/`, `harness/`, `bin/`, `decomp/`,
   `reference/scripts/`, `dist/`. Add the ROM (user-supplied; never public).
2. Copy `harness/`; run `build.sh` to get `bin/mgba-harness` (keep `MGBA_TAG` pinned).
3. Pin the game's decomp in `decomp/PIN`; copy `.github/workflows/decomp-map.yml` and adapt the
   toolchain steps so the decomp build is byte-identical to the ROM; it commits
   `fireemblem8.map`/`.nm.txt` equivalents.
4. In the code repo run `tools/gen_reference.py --decomp <checkout> --nm <nm.txt>` (adapt
   `SYSTEM_RULES`/parsers if the decomp layout differs) to write `reference/decomp_functions.tsv`,
   `ram_symbols.tsv`, `DECOMP_VERSION`; record the pin in an ADR.
5. Write input timelines in `gen_scripts.py` (find the boot/title frames by dumping; FE8 uses A at
   f181, START at f700), run `make_reference.sh`, inspect `reference/out/<script>/`.
6. Pack `reference/out` (`dist/reference.tar.xz` + `MANIFEST.sha1`; how FE8 builds this tarball is
   not scripted in the repo: unverified) and adapt `publish.yml` to release ROM, harness and
   tarball.
7. Point the code repo at it: `ROM_REPO` in `tools/setup.sh` / `ci_fetch_assets.sh` (both default to
   the FE8 repo; assets land in `$FE8_ASSETS`, extracted to `reference/out`); CI needs
   `ROM_REPO_TOKEN` or `ROM_REPO_DEPLOY_KEY`, else differential tests skip.
8. Write tests: `ReferenceData` + `RamSymbols` + `GbaMem`, `assumeTrue(ref.available)`,
   `@VerifiesPort` with a `Ported from <file>: <Func>` KDoc in main code (CLAUDE.md rule 4).

