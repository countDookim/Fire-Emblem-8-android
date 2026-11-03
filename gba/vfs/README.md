# gba:vfs

`VirtualFileSystem`, `VfsLayer`, `DirectoryLayer`, `ModManifest`: the only way modules read content, load time
only (ADR 0003). `TextureOverrides` finds mod replacement/hi-res textures (`<asset>@<N>x.png`, palette
variants; ADR 0008). Planned: zip layer, merge patches, hot reload.
