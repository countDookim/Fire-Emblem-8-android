# 0003 — Layered virtual filesystem for mods

**Status.** Accepted; `base` layer per 0011.

**Context.** Mods override any content by path (folders or zips), with dependencies, load order, hot reload.

**Decision.** `gba.vfs.VirtualFileSystem` is the *only* way to read content, at load/reload time only.
- Normalised virtual paths (`data/items.json`, `maps/ch01.tmj`, `text/en/ch01.json`); `..` rejected.
- Layers, lowest first: `base` (release: extracted to `filesDir/base/`; debug/test: APK assets; tests:
  `$FE8_ASSETS`) → mods by dependencies then `loadOrder` → `override/` (debug). Top layer wins; `list()` is the
  union; data merge patches (0002) are the exception.
- `mod.json` (`ModManifest`: id, version, name, dependencies, loadOrder, formatVersion). Bad dependencies
  disable the mod with a visible error, never crash.
- Debug hot reload: watch folder layers, invalidate between ticks.

**Consequences.** Virtual paths are modding API (`MODDING.md`); renaming one breaks mods.
