# HANDOFF

_Updated: 2026-10-25 (session 1 follow-up, interactive). Brief changed: see Amendments in docs/PROJECT_BRIEF.md._

## NEEDS USER
_(nothing blocked)_

## Status
- Last CI: ✅ `master` run 40133276579: 12 passed, 0 skipped, ROM assets available (secret `ROM_REPO_TOKEN` works).
  CI now FAILS if assets are present but any test is skipped (guards against stale build-cache results).
- Session setup (measured 2026-10-24): cold 87 s, warm re-run 3 s.
- Branch: `master`. No `wip/` branches.
- Progress: 4 / 8352 verified (src/rng.c).
- Decomp pin verified: the ROM repo's `decomp-map` workflow builds fireemblem8u byte-identical to the ROM;
  `reference/ram_symbols.tsv` + function addresses come from its ELF symbols (`RamSymbols` in :testing).

## Next task: M1-07 — KDoc lint
- Files: `build.gradle.kts` (dokka config in `if (docs)` block), any `engine/*/src/main/**/*.kt` lacking KDoc.
- First command: `source .assets.env && ./gradlew dokkaGenerate -Pfe8.docs=true --console=plain 2>&1 | grep -i undocumented`
- Do: in the docs block configure every JVM module's `dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }`
  and `dokka { dokkaPublications.configureEach { failOnWarning.set(true) } }`; add missing KDoc until clean.
- Done when: the command above prints nothing and CI's Dokka step is green.
- Note: Dokka's deps are not in the seeded Maven cache, so the first `-Pfe8.docs=true` run in a session
  goes through the slow mirror. Afterwards refresh `deps/maven-files.txt` in the ROM repo (see its deps/README.md).

## Known bugs / caveats
- Maven Central through the session proxy returns spurious 404/429; `tools/setup.sh` starts
  `tools/maven_mirror.py` (retries) — first warm-up can take several minutes. CI is unaffected.
- `GameSurfaceView` is untested on a real device (no gameplay yet; manual test only).
- Static C names can repeat across files; `RamSymbols[name]` throws if ambiguous — use unique names.
