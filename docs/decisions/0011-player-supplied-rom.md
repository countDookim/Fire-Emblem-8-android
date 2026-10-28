# 0011 — The player supplies the ROM; assets are extracted on the device

**Status.** Accepted by the owner, 2026-10-27.

**Amended 2026-10-28 (owner).** This decision governs **public release builds** only. Until the game is ready
for public release, debug/test APKs (private repo, `latest` release) may bundle the ROM repo's extracted assets
so the owner can test without importing a ROM; the CI guard (M4-05) applies to release builds. The on-device
import (M4-03, M4-04) is done before the public release.

**Context. ADR 0003 made the VFS `base` layer "extracted vanilla content: APK assets". Built that way,
every APK would carry Nintendo / Intelligent Systems graphics, music and text, so the app could not be shared
without distributing copyrighted material. The owner wants the finished product to need the player's own,
legally obtained ROM.

**Decision.**
1. **Distributed builds contain no game content.** No ROM bytes, extracted assets (PNG, WAV, MIDI, JSON
   tables, text, maps, scripts) or anything decoded from the ROM go into the APK, the `latest` release or the
   code repo. The code repo rule in CLAUDE.md (never commit ROM-derived data) extends to release artifacts.
   CI enforces it (task M4-05).
2. **First launch imports the player's ROM.** The app asks for a `.gba` file through the Android document
   picker (no storage permission), checks its SHA1 against the supported ROM (FE8 U,
   `c25b145e37456171ada4b0d440bf88a19f4d509f`) and refuses others with a clear message. The ROM file itself is
   not kept: only the extracted output is.
3. **Extraction runs on the device** with the same code that produces the ROM repo's `extracted/` today
   (`:tools:extract`), so on-device output is byte-for-byte (PNG: pixel-for-pixel) what CI already checks.
   The extraction core moves to a library module usable from `:app` (JVM/Android-safe: no `java.awt`,
   `javax.imageio`, processes or decomp-source reading); the CLI stays a thin wrapper.
4. **No decomp at runtime.** Today extraction reads symbol addresses (`--symbols`, `--map`) and constant
   names (`--decomp`). These become a generated, committed **extraction manifest** (ROM offsets, sizes, ids and
   names per table/asset) shipped in the APK. It holds addresses and identifiers only, no game content.
5. **The VFS `base` layer is the app's private extracted folder** (`filesDir/base/`), versioned by the
   extractor format version: an app update with a newer format re-runs extraction (asks for the ROM again
   if needed). Mods layer on top unchanged (ADR 0003).
6. **Development is unchanged.** Tests and sessions keep using `$FE8_ASSETS` from the private ROM repo;
   the ROM repo keeps its `extracted/` as the reference the on-device extractor must reproduce.

**Consequences.** The APK is engine + ported code + manifest only. First launch takes longer (extraction
time is measured and must stay reasonable on a mid-range phone; target under ~30 s, progress shown). The
extractor becomes product code: its failures need user-facing errors, and it must not allocate wildly on
low-memory devices.

**Not solved by this.** The ported game logic is a translation of decompiled code. Shipping no game content
lowers the legal risk but does not settle the status of the code itself. Not legal advice.
