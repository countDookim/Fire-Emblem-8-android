# HANDOFF

_Updated: 2026-10-24 (session 1, interactive). Brief changed: see Amendments in docs/PROJECT_BRIEF.md._

## NEEDS USER
1. **CI can't read the private ROM repo** (issue labelled `needs-user`). Add ONE repository secret in
   Fire-Emblem-8-android → Settings → Secrets and variables → Actions (never paste it anywhere else):
   `ROM_REPO_TOKEN` = fine-grained PAT, *Only select repositories: Fire-Emblem-8-rom*, *Contents: Read-only*.
   Until then CI skips differential tests (they never count as passed). Unblocks M1-06. Issue #1.
2. **M1-09 needs a yes/no:** may a ROM-repo workflow build the fireemblem8u decomp (to get its symbol map)?
   A local decomp build was blocked by the permission system in session 1. Answer on issue #1 or a new issue.

## Status
- Last CI: ✅ green on `master` @37f4e61 (run 40129576919): 10 passed, 2 skipped (differential — no ROM secret), APK on `latest`.
- Session setup (measured 2026-10-24): cold 87 s, warm re-run 3 s. Local `./gradlew jvmTest`: 12/12 pass.
- Branch: `master` (default). No `wip/` branches.
- Progress: 4 functions verified (rng.c) when reference data is present; 0 in CI until NEEDS USER 1.

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
- RAM symbol addresses for differential tests are currently hand-derived from `sym_iwram.txt`
  (M1-09/M1-10 add a real map).
