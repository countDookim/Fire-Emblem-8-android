package gbafe.extract.fe8.maps

import gbafe.data.Bgr555
import gbafe.data.IndexedImage
import gbafe.data.Lz77
import gbafe.extract.IndexedPng
import gbafe.extract.Json

/**
 * Converts a chapter's map (decomp `src/bmmap.c: UnpackChapterMap/UnpackChapterMapGraphics`) into a
 * Tiled map (`.tmj`) plus a tileset PNG.
 *
 * Assets come from `gChapterDataAssetTable` by the chapter's `map.*Id` fields (all LZ77 except the raw
 * palette): `mainLayerId` = width, height bytes then `u16` cells (metatile index × 4); `tileConfigId` =
 * 1024 metatiles × 4 BG tilemap entries (TL, TR, BL, BR; tile | hflip << 10 | vflip << 11 | palette << 12)
 * followed by 1024 terrain bytes; `obj1Id` tiles load at BG tile 0 and `obj2Id` (optional) at tile 0x200;
 * `paletteId` = 10 palettes, of which the game uses 0..4 for lit tiles (BG palette 6 + n) and 5..9 under
 * fog (`DisplayBmTile`).
 *
 * The tileset PNG renders all 1024 metatiles (32 per row, 16×16 px) as indices `palette * 16 + color`
 * (the tilemap entry's palette field, i.e. as lit) into a 256-color PNG palette holding the 10 asset
 * palettes (fields 10..15 never occur and are black). The Tiled tileset carries each metatile's
 * terrain as an int property `terrain`.
 */
public object MapsExtractor {
    /** Metatiles per tile config. */
    public const val METATILES: Int = 1024

    /** Result for one chapter. */
    public class Output(
        /** Map width in metatiles. */
        public val width: Int,
        /** Map height in metatiles. */
        public val height: Int,
        /** Metatile index per cell, row-major. */
        public val cells: IntArray,
        /** Terrain id per metatile. */
        public val terrain: IntArray,
        /** Rendered tileset (512×512, indexed). */
        public val tileset: IndexedImage,
        /** Tileset PNG palette (the 10 asset palettes, padded to 256, ARGB). */
        public val palette: IntArray,
        /** Map changes (doors, chests, ...) in table order. */
        public val changes: List<MapChange> = emptyList(),
    ) {
        /** The tileset as PNG bytes. */
        public fun tilesetPng(): ByteArray = IndexedPng.write(tileset, palette)

        /** Tiled JSON map with an embedded tileset referencing [tilesetImage]. */
        public fun tmj(name: String, tilesetImage: String): String = Json.write(
            linkedMapOf(
                "type" to "map", "version" to "1.10", "tiledversion" to "1.10.2", "orientation" to "orthogonal",
                "renderorder" to "right-down", "width" to width, "height" to height, "tilewidth" to 16, "tileheight" to 16,
                "infinite" to false, "nextlayerid" to 2 + changes.size, "nextobjectid" to 1,
                "layers" to listOf(
                    linkedMapOf(
                        "id" to 1, "name" to "main", "type" to "tilelayer", "x" to 0, "y" to 0, "width" to width,
                        "height" to height, "opacity" to 1, "visible" to true, "data" to cells.map { it + 1 },
                    ),
                ) + changes.mapIndexed { i, c ->
                    val data = IntArray(width * height)
                    for (y in 0 until c.h) for (x in 0 until c.w) {
                        val v = c.cells[y * c.w + x]
                        if (v != 0 && c.x + x < width && c.y + y < height) data[(c.y + y) * width + c.x + x] = (v shr 2) + 1
                    }
                    linkedMapOf(
                        "id" to 2 + i, "name" to "change_${c.id}", "type" to "tilelayer", "x" to 0, "y" to 0, "width" to width,
                        "height" to height, "opacity" to 1, "visible" to false, "data" to data.toList(),
                        "properties" to listOf("id" to c.id, "x" to c.x, "y" to c.y, "w" to c.w, "h" to c.h)
                            .map { (k, v) -> linkedMapOf("name" to k, "type" to "int", "value" to v) },
                    )
                },
                "tilesets" to listOf(
                    linkedMapOf(
                        "firstgid" to 1, "name" to name, "image" to tilesetImage, "imagewidth" to 512, "imageheight" to 512,
                        "tilewidth" to 16, "tileheight" to 16, "columns" to 32, "tilecount" to METATILES, "margin" to 0, "spacing" to 0,
                        "tiles" to terrain.mapIndexed { i, t ->
                            linkedMapOf("id" to i, "properties" to listOf(linkedMapOf("name" to "terrain", "type" to "int", "value" to t)))
                        },
                    ),
                ),
            ),
        )
    }

    /**
     * One `struct MapChange` (decomp `include/types.h`; applied by `src/bmtrick.c: ApplyMapChangesById`):
     * a [w]×[h] block of raw map cells at ([x], [y]); cell value 0 leaves the map unchanged. In the
     * `.tmj` each change is a hidden tile layer `change_<id>` (gid 0 = unchanged) with its rectangle as
     * properties.
     */
    public class MapChange(
        /** Change id (`TILE_CHANGE` event argument). */
        public val id: Int,
        /** Left column. */
        public val x: Int,
        /** Top row. */
        public val y: Int,
        /** Width in metatiles. */
        public val w: Int,
        /** Height in metatiles. */
        public val h: Int,
        /** Raw cells (metatile × 4, 0 = unchanged), row-major. */
        public val cells: IntArray,
    )

    /** Reads the `MapChange` list at ROM offset [offset] (terminated by a negative id). */
    public fun readChanges(rom: ByteArray, offset: Int): List<MapChange> {
        val out = ArrayList<MapChange>()
        var o = offset
        while (rom[o] >= 0) {
            val w = rom[o + 3].toInt() and 0xFF
            val h = rom[o + 4].toInt() and 0xFF
            val data = u32(rom, o + 8) - 0x08000000
            out += MapChange(rom[o].toInt(), rom[o + 1].toInt() and 0xFF, rom[o + 2].toInt() and 0xFF, w, h, IntArray(w * h) { u16(rom, data + 2 * it) })
            o += 12
        }
        return out
    }

    /**
     * Extracts the map of the chapter whose `struct ChapterMap` ids are [obj1Id], [obj2Id], [paletteId],
     * [tileConfigId], [mainLayerId] and [changeLayerId] (0 = none), from the asset pointer table at ROM offset [assetTable].
     */
    public fun extract(
        rom: ByteArray, assetTable: Int, obj1Id: Int, obj2Id: Int, paletteId: Int, tileConfigId: Int, mainLayerId: Int, changeLayerId: Int = 0,
    ): Output {
        fun asset(id: Int): Int = u32(rom, assetTable + 4 * id).let { if (it == 0) -1 else it - 0x08000000 }
        val tiles = ByteArray(METATILES * 32)
        Lz77.decode(rom, asset(obj1Id)).data.let { it.copyInto(tiles, 0, 0, minOf(it.size, tiles.size)) }
        if (obj2Id != 0 && asset(obj2Id) >= 0) Lz77.decode(rom, asset(obj2Id)).data.let { it.copyInto(tiles, 0x200 * 32, 0, minOf(it.size, tiles.size - 0x200 * 32)) }
        val cfg = Lz77.decode(rom, asset(tileConfigId)).data
        val map = Lz77.decode(rom, asset(mainLayerId)).data
        val w = map[0].toInt() and 0xFF
        val h = map[1].toInt() and 0xFF
        val cells = IntArray(w * h) { u16(map, 2 + 2 * it) shr 2 }
        val terrain = IntArray(METATILES) { cfg[METATILES * 8 + it].toInt() and 0xFF }
        val img = IndexedImage(512, 512)
        for (mt in 0 until METATILES) {
            for (k in 0 until 4) {
                val e = u16(cfg, mt * 8 + k * 2)
                val t = e and 0x3FF
                val hf = e shr 10 and 1 == 1
                val vf = e shr 11 and 1 == 1
                val pal = e shr 12
                val ox = mt % 32 * 16 + (k and 1) * 8
                val oy = mt / 32 * 16 + (k shr 1) * 8
                for (y in 0 until 8) for (x in 0 until 8) {
                    val sx = if (hf) 7 - x else x
                    val sy = if (vf) 7 - y else y
                    val b = tiles[t * 32 + sy * 4 + sx / 2].toInt() and 0xFF
                    val px = if (sx and 1 == 1) b shr 4 else b and 15
                    img.pixels[(oy + y) * 512 + ox + x] = (pal * 16 + px).toByte()
                }
            }
        }
        val changes = if (changeLayerId != 0 && asset(changeLayerId) >= 0) readChanges(rom, asset(changeLayerId)) else emptyList()
        // All 10 palettes: a few metatiles reference palette fields >= 5. Fields 10..15 (none seen) map to black.
        val palette = Bgr555.readPalette(rom, asset(paletteId), 10 * 16).copyOf(256).also { it.fill(0xFF000000.toInt(), 160, 256) }
        return Output(w, h, cells, terrain, img, palette, changes)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
