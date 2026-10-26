# engine:vfs

Layered virtual filesystem (`VirtualFileSystem`, `VfsLayer`, `DirectoryLayer`) and `ModManifest`.
The only way any module reads content. Load-time only. Design: `docs/decisions/0003-mod-vfs.md`.
`TextureOverrides` finds mod replacement / hi-res textures (`<asset>@<N>x.png`, palette variants; ADR 0008).
Planned: zip layer, Android-assets layer (in `:app`), merge-patch support, hot reload.
