# 0011 — The player supplies the ROM; assets are extracted on the device

**Status.** Accepted (owner, 2026-10-27); amended 2026-10-28.

**Context.** Bundled assets would make every APK distribute Nintendo / Intelligent Systems content. The product
must need the player's own ROM.

**Decision.**
1. **Public release builds** contain no ROM bytes or anything decoded from it, in APK, release or repo (CI guard
   M4-05). **Until public release**, debug/test APKs (private `latest` release) bundle the ROM repo's assets.
2. **First launch imports the ROM** (document picker, no storage permission; SHA1 must be FE8 U
   `c25b145e37456171ada4b0d440bf88a19f4d509f`, others refused clearly). Only extracted output is kept.
3. **Extraction on device** reuses `:tools:extract` (output = ROM repo `extracted/`, PNG pixel-exact), moved to a
   library `:app` can use (no `java.awt`, `javax.imageio`, processes, decomp reads; CLI stays a wrapper).
4. **No decomp at runtime:** `--symbols`/`--map`/`--decomp` inputs become a committed **extraction manifest**
   (offsets, sizes, ids, names; no content) shipped in the APK.
5. **VFS `base` = `filesDir/base/`**, versioned by extractor format (newer format re-extracts). Mods on top.
6. **Development unchanged:** tests use `$FE8_ASSETS`; ROM repo `extracted/` is the reference.

Open: M4-03 (library + manifest), M4-04 (import UI).

**Consequences.** Release APK = engine + code + manifest. First launch slower (target < ~30 s mid-range, with
progress). Extractor becomes product code: user-facing errors, bounded memory. Not solved: the ported code
translates decompiled code; this lowers legal risk only. Not legal advice.
