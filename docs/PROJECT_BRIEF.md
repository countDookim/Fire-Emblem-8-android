# Project brief: native Kotlin Android reimplementation of a GBA game

> Saved verbatim from the owner's first-session message (2026-10-24). **Amendments agreed since then are
> listed at the end and take precedence.**

## Inputs
- ROM: attached (legally obtained, personal use only).
- Decomp: https://github.com/StanHash/fe8
- Code repo: https://github.com/countDookim/Fire-Emblem-8-android (private).
- ROM repo: https://github.com/countDookim/Fire-Emblem-8-rom (private). It holds the ROM and all ROM-derived/cached data, because scheduled runs can't receive attachments.
- Attach both repos with push access via the GitHub app. Never ask me for passwords or tokens.

## Goal
Fully reimplement the game as a native Android app in Kotlin. It must read as if it had been designed as a modern, moddable Android game from day one. It is NOT an emulator, NOT a line-for-line transliteration, and has NO GBA memory-layout mimicry. In "vanilla" mode, gameplay behavior must match the original exactly. The decomp is the spec; the ROM supplies assets and is the ground truth for testing.

## Hard requirements
1. **Modern architecture**
   - Organize by game system (core loop, rendering, audio, input, save, text, overworld, battle, menus, etc.).
   - Use idiomatic Kotlin: sealed classes for states, enums instead of magic numbers, data classes.
   - Use Compose for menus and settings only. The game view uses OpenGL ES 3.0.
2. **Runs smoothly on low-end phones**
   - Zero allocations per frame in the game loop and renderer.
   - Hot data in primitive arrays behind clean typed wrappers.
   - GPU rendering at native 240×160, scaled with a shader.
   - Fixed ~59.73 Hz logic, decoupled from display rate.
   - Low-latency `AudioTrack` output.
   - minSdk 26.
   - Add allocation-tracking tests and a JVM frame-time benchmark for the game loop, run in CI, to catch performance regressions without a device.
3. **Extremely well documented**
   - KDoc on every public class and function: what it does, why, and any non-obvious decisions.
   - Every ported function cites its source, e.g. `/** Ported from src/battle_main.c: BattleMainCB2 */`.
   - A README for each module.
   - Top-level docs: `ARCHITECTURE.md`, `MODDING.md` (kept current with every moddable system), and `docs/decisions/` (short decision records).
   - A Dokka docs site.
4. **README progress bar**
   - Put an estimated-completion bar at the top of README.md, e.g. `Progress: ████████░░░░░░░░░░░░ 41% (1234 / 3010 functions)`.
   - Compute it with `tools/progress.py`: count decomp functions (from the committed reference function list) versus functions marked as ported AND covered by a passing test.
   - Also show a per-system breakdown table.
   - CI regenerates it on every push. Never inflate it, and label it as an estimate.

## Modding architecture (design in from the start, not retrofitted)
- **Input:**
  - Input is abstract game actions (Confirm, Cancel, Move(dir), SelectItem(n), etc.), never raw GBA buttons.
  - Buttons, touch and controllers are interchangeable sources.
  - UI elements expose their screen bounds and actions, so touch hit-testing can be added without touching game logic.
- **Feature flags:**
  - All non-original behavior goes behind feature flags.
  - "Vanilla" mode must stay bit-exact for differential tests.
- **Data-driven content:**
  - All game data (stats, items, moves, encounters, trainers, shops, etc.) lives in versioned, schema-validated JSON.
  - At load time it is compiled into primitive arrays for runtime speed.
  - No game data is hardcoded in Kotlin.
- **Maps and world:**
  - Maps are stored in Tiled format (.tmj): layout, collision, warps, events, NPC placement and map connections.
  - Tilesets are PNGs, so maps are editable in the Tiled editor.
  - A converter generates them from the decomp's map data.
- **Event scripts:**
  - Convert the original event scripts into a human-readable text script format, run by an allocation-free interpreter.
  - New logic can be added without recompiling.
  - Choose between a custom format and embedded Lua in a decision record, weighing low-end performance.
- **Graphics:**
  - Sprites, tilesets and UI as PNGs plus JSON metadata (frames, anchors, animation timing, palettes).
  - The renderer supports replacement and higher-resolution texture packs through the asset override layer.
  - Vanilla mode keeps the original palette behavior.
- **Audio:**
  - Music as MIDI plus instrument/voicegroup definitions; sound effects as WAV/OGG.
  - All of it is replaceable through overrides.
- **Text:**
  - All strings live in localization files, with control codes as readable tags (e.g. `{PLAYER}`, `{PAUSE 30}`).
  - A Unicode-capable font system.
- **Mechanics:**
  - Formulas (damage, XP, catch rate, encounter generation, AI decisions, etc.) are swappable strategy interfaces with vanilla implementations.
  - A typed event bus (onStep, onMapEnter, onBattleStart, onItemUsed, etc.) lets mods hook in without editing core code.
  - Dispatch is allocation-free.
- **Flags and variables:**
  - A registry of game flags and variables named after the decomp's constants, never raw indices.
  - Mods can register new ones.
- **Saves:**
  - A versioned, extensible save format with migrations and per-mod data sections.
  - Import from the original `.sav` format.
- **Mod loader:**
  - Mods are folders or zips with a manifest (ID, version, dependencies, load order).
  - A layered virtual filesystem lets mods override any data, map, script, asset or string by path.
  - Hot reload in debug builds.
- **Camera and viewport:** a camera abstraction not tied to 240×160, so widescreen and resolution mods are possible. Vanilla mode stays 240×160.
- **RNG and time:** a central, seedable RNG service (vanilla reproduces the original RNG exactly) and a game-clock abstraction.
- **Dev tools:** an in-game debug menu (warp, set flags and variables, give items, start battles, inspect state) in debug builds.

## Environment and workflow
Everything runs on Anthropic's cloud sessions plus GitHub Actions. Never use my computer, Android Studio, an Android emulator or computer use.

- **CI (GitHub Actions)** runs on every push:
  - Builds a debug APK and runs the full test suite (unit tests, differential tests, allocation and frame-time checks).
  - Regenerates the progress bar, `docs/port_map.json` and Dokka.
  - Uploads the APK and a short results summary as artifacts.
  - Attaches the latest APK to a rolling `latest` GitHub release, so I can download it on my phone.
  - Uses a cached Gradle/SDK setup.
  - Sessions never install the Android SDK.
- **Sessions (scheduled runs)** need only a JDK and kotlinc. `tools/setup.sh` must be:
  - **Minimal:** JDK and kotlinc, plus downloading cached assets from the ROM repo's latest release (ROM, headless mGBA binary, reference test data).
  - **Idempotent:** skip anything already present.
  - **Fast:** target under 3 minutes, and log its own duration.
- **Game logic stays Android-free** in pure Kotlin/JVM modules, so it can be tested in the session in seconds. Android-specific code lives in a thin `app` module.
- **Decomp-derived reference data** (function list, symbols) is generated once by `tools/gen_reference.py` and committed. Rebuild it only if the decomp version changes.
- **ROM handling:** never commit the ROM or anything extracted from it to the code repo; add both to `.gitignore`. ROM-derived data lives only in the ROM repo and its releases. Keep both repos private.
- **Automated verification:**
  - Run differential tests (in vanilla mode) against headless mGBA with identical input sequences, comparing game state and frame dumps. The renderer must have a software/offscreen path for frame comparison on the JVM.
  - A function only counts as "ported" once it passes.
- **Manual testing (me, on my phone):**
  - I install the APK from the `latest` release.
  - Keep `docs/MANUAL_TEST.md` current: a short checklist of what is newly testable since the last APK, and how to test it.
  - I report bugs and feedback as GitHub Issues labeled `user-report`.
  - Treat open `user-report` issues as top priority. Reference the issue in the fixing commit, and close it only when the fix is verified by a test, or ask me to confirm.

## Resume files (keep them small and current)
- **`HANDOFF.md`** (one screen maximum), containing:
  - The last CI result.
  - The current branch.
  - **Next task:** ID, files to touch, the first command to run, and the done criteria.
  - Known bugs.
  - A NEEDS USER section at the top when something is blocked.
- **`docs/TASKS.md`:** an ordered backlog of tasks of 20 minutes or less, with IDs, milestones and status.
- **`docs/port_map.json`:** decomp function → Kotlin file → status → test. Generated by `tools/progress.py`.
- **`docs/MANUAL_TEST.md`:** the checklist for my manual testing.

## Schedule (scheduled tasks)
- **Times:** one daily scheduled task firing every 8 hours, at 00:50, 08:50 and 16:50 Asia/Jerusalem time (cron: `CRON_TZ=Asia/Jerusalem 50 0,8,16 * * *`).
- **Budget:** each run lasts 40–45 minutes, measured from the run's own start time.
- **Fresh sessions:** each run starts with no memory of earlier runs.

Every run:
1. Record the start time.
2. Shallow-clone the code repo and run `tools/setup.sh`.
3. Read ONLY `HANDOFF.md`, open `user-report` issues, and the last CI result if it failed. Don't re-read the whole brief unless HANDOFF says something changed.
4. Priority order:
   1. Failing CI.
   2. Open `user-report` issues.
   3. HANDOFF's "Next task", done immediately with no re-planning.
   4. The next task from `docs/TASKS.md`.
5. If `TASKS.md` has fewer than 5 open tasks, spend the run on planning instead: break the next milestone into tasks of 20 minutes or less and add them.
6. **Commit and push after every completed step**, not only at the end, so an interrupted run loses almost nothing. Unfinished work goes on a `wip/<task-id>` branch. Never leave `main` broken.
7. At minute 38, stop new work no matter what. Then, by minute 45:
   - Run the local tests.
   - Update `docs/MANUAL_TEST.md` if anything became testable.
   - Write the NEXT task precisely into `HANDOFF.md`.
   - Commit and push.
   - Do NOT wait for CI to finish after the final push: end the run. The next run checks the last CI result first (step 3).
8. **Out of credits / usage limit:**
   - If a run hits a usage limit, it simply stops. Thanks to frequent pushes, the next scheduled run resumes from `HANDOFF.md` and the latest `wip/` branch.
   - At the start of each run, if a `wip/` branch is newer than HANDOFF's last update, assume the previous run was interrupted and continue that work first.
   - Never try to work around limits or reschedule yourself.
9. If blocked (missing permission, failing setup, unclear or irreversible decision), write it under "NEEDS USER" in `HANDOFF.md`, and open a GitHub Issue labeled `needs-user` so I get notified. Then continue with another unblocked task, or end the run.

## Milestone order
1. Toolchain, CI (with the `latest` APK release), repo skeleton and docs scaffolding. Write decision records for the core abstractions: script format, data schemas, mod VFS, event bus.
2. Asset and data extraction into the moddable formats (PNG, JSON, Tiled maps, scripts, MIDI, strings).
3. Rendering (GPU path plus an offscreen path for tests), input (action-based) and audio layers, plus the asset override layer.
4. Boot to title screen, verified against mGBA. First manual test APK.
5. Core loop, save system, flag/variable registry and script interpreter.
6. Remaining game systems, one by one, each with its strategy interfaces and event hooks.
7. Mod loader, debug menu, touch controls and the first optional QoL feature flags.

Be honest: flag uncertainty, never claim untested behavior works, and say clearly when something is blocked.

## This first session (interactive, not time-boxed)
1. Confirm which game and decomp you're working with.
2. Push the attached ROM to the ROM repo.
3. Build headless mGBA and the reference test data, and publish them as a release in the ROM repo.
4. Write `tools/setup.sh`, `tools/gen_reference.py` and `tools/progress.py`.
5. Set up the CI workflow, including the `latest` APK release, and verify one green run.
6. Create the GitHub labels `user-report` and `needs-user`.
7. Create the repo skeleton and docs:
   - Save this brief as `docs/PROJECT_BRIEF.md`.
   - Create README (progress bar at 0%), ARCHITECTURE.md, MODDING.md, HANDOFF.md and docs/MANUAL_TEST.md.
   - Fill `docs/TASKS.md` for milestones 1–3.
8. Make the first commit and push.
9. Create the scheduled task described above. Its prompt should say: "Continue the GBA Kotlin reimplementation project in <GITHUB_OWNER>/<REPO_NAME>: follow docs/PROJECT_BRIEF.md (Schedule section) and HANDOFF.md."
10. Report:
    - The measured `setup.sh` time.
    - Which approval setting the scheduled task got. If its runs would ask for approval, tell me to switch it to automatic approval.
    - The first scheduled task.
    - Anything unverified.

---

## Amendments (take precedence over the text above)

| Date | Change | Why |
|---|---|---|
| 2026-10-24 | **Decomp is [FireEmblemUniverse/fireemblem8u](https://github.com/FireEmblemUniverse/fireemblem8u)** pinned at `reference/DECOMP_VERSION`, not StanHash/fe8. Approved by the owner in session 1. | StanHash/fe8 (last commit 2021) is ~95% undecompiled asm; fireemblem8u is near-fully decompiled C for the same ROM SHA1. See `docs/decisions/0007-decomp-choice.md`. |
| 2026-10-24 | The default branch is **`master`** (the repo's existing default); "main" in this brief means `master`. | Repo was created with `master`. |
| 2026-10-24 | Sessions use the Gradle wrapper (JVM modules only, no Android SDK) in addition to JDK + kotlinc, plus a local retrying Maven mirror. | Needed to run the tests; Maven Central is rate-limited through the session proxy. See `docs/decisions/0005-toolchain-and-sessions.md`. |
| 2026-10-24 | The ROM repo's release is published by its own GitHub Actions workflow (`publish-assets`). | Session tokens may not create releases. |
| 2026-10-24 | Runs last **40–45 min** (stop new work at minute 38, wrap up by 45) instead of 30–35; runs **don't wait for CI** after the final push. Requested by the owner. | ~17% of a run was spent waiting on CI at the end; the next run checks CI first anyway. |
| 2026-10-25 | **The finished app is a 1:1 copy of the original.** Modding means groundwork only: extension points (data/VFS, event hooks, strategy interfaces) that make changes easy, but the app ships **no** non-original features: no touch gameplay controls, no QoL feature flags, no alternate formulas, no widescreen. Milestone 7 becomes: mod loader, debug menu (debug builds only), on-screen GBA buttons. See `docs/decisions/0009-vanilla-product-mod-groundwork.md`. | Owner request. |
| 2026-10-25 | The only touch input shipped is an **on-screen GBA button overlay** (D-pad, A, B, L, R, Start, Select) sending exactly the GBA buttons, so phones without a controller can play. | Owner choice. |
| 2026-10-25 | Random battle outcomes go through the `BattleRolls` strategy (`fe8.core.mechanics`): vanilla hit uses two RNs, and switching to one RN must be a one-method change. Every ported system adds such strategy interfaces (vanilla only in the product, alternatives only in tests). | Owner request. |
| 2026-10-25 | **Build for reuse by future FE6/FE7 ports.** Engine, shared FE systems, data/asset formats, schemas and tools are game-neutral (`gbafe.*` packages, `gbafe.<table>` formats with a `game` field); FE8-only systems and ROM layouts live in the `fe8` layer. Per-game differences only via data, strategies or per-game extractor packages. CI enforces that `gbafe` code never uses `fe8`. See `docs/decisions/0010-multi-game-reuse.md`. | Owner request. |
| 2026-10-27 | **The player supplies the ROM.** Distributed builds (APK, `latest` release) contain no game content. On first launch the app imports the player's `.gba` (SHA1-checked), extracts the assets on the device with the `:tools:extract` code into the VFS `base` layer, and does not keep the ROM. Extraction needs a committed manifest of ROM offsets/ids instead of the decomp. CI checks the APK holds no game content. See `docs/decisions/0011-player-supplied-rom.md`; tasks M4-03..05. | Owner request (avoid distributing copyrighted material). |
| 2026-10-26 | Runs last **about 45 min** as a guideline (may go a little over to finish a nearly done task or something important). Work until ~minute 38, wrap up by ~45; **never end early** with time left (start the next task on a `wip/` branch instead). Schedule section updated in place. | Owner request; a run had ended at minute 19 because the stale 30–35 min text in the Schedule section was followed instead of the amendment. |
