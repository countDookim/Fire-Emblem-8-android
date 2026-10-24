# 0003 — Layered virtual filesystem for mods

**Context.** Mods must override any data, map, script, asset or string by path, as folders or zips,
with dependencies, load order and hot reload in debug builds.

**Decision.** `fe8.vfs.VirtualFileSystem` is the *only* way game code reads content.
- Normalised virtual paths (`data/items.json`, `maps/ch01.tmj`, `gfx/portraits/eirika.png`,
  `text/en/ch01.json`, `audio/music/SONG_X.mid`); `..` escapes are rejected.
- Layer stack, lowest first: `base` (extracted vanilla content: APK assets / `$FE8_ASSETS` in tests) →
  enabled mods sorted by dependencies then `loadOrder` → `override/` dev folder (debug builds). The
  top-most layer containing a path wins; `list()` is the union. Merge patches (0002) are the exception
  to "top wins" for data tables.
- Mods: folder or zip with `mod.json` (`ModManifest`: id, version, name, dependencies, loadOrder,
  formatVersion). Missing/incompatible dependencies disable the mod with a visible error, never crash.
- Hot reload (debug): file watcher on folder layers → invalidate affected compiled tables/textures at the
  next safe point (between ticks), never mid-tick.
- Read only at load/reload time; nothing touches the VFS inside the frame loop.

**Consequences.** Assets are addressed by stable paths, which become part of the modding API and are
listed in `MODDING.md`. Renaming a path is a breaking change for mods.
