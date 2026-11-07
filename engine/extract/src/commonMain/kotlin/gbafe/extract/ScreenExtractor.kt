package gbafe.extract

import gba.data.Bgr555
import gba.data.Lz77
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.extract.Json
import gbafe.data.Tsa

/**
 * Full-screen still images drawn from one tile set plus TSA tilemaps (title cards, the health &
 * safety screen): writes `<name>.png` (the 4bpp tile set, 16 tiles per row in tile order, with
 * the screen's palette banks) and a `gbafe.screen` document listing each BG layer's tilemap
 * (raw u16 entries, top row first, tile base added). Game-neutral; each game's step supplies the
 * ROM offsets.
 */
public object ScreenExtractor {
    /** One BG layer of the screen. */
    public class Layer(
        /** BG number (0..3) the game draws it on. */
        public val bg: Int,
        /** ROM offset of the LZ77-compressed TSA. */
        public val tsa: Int,
        /** Tile reference added to every entry (`CallARM_FillTileRect`'s third argument). */
        public val tileBase: Int = 0,
    )

    /** Result: the PNG bytes and the `gbafe.screen` document. */
    public class Output(
        /** Indexed PNG of the tile set. */
        public val png: ByteArray,
        /** The `gbafe.screen` document. */
        public val json: String,
        /**
         * Per layer (same order as requested): BG number to its tilemap, tile base applied (what
         * the JSON lists).
         */
        public val tilemaps: List<Pair<Int, Tsa>>,
    )

    /**
     * Extracts screen [name] for [game]: LZ77 4bpp tiles at [image], [paletteBanks] × 16 colours
     * at [palette], and [layers]. The game loads the tile set at BG tile [firstTile] and the
     * palette at BG bank [firstPalette] (written to the JSON only when non-zero); layer entries
     * are the game's VRAM references, so a layer's [Layer.tileBase] usually includes them.
     * [image], [palette] and each layer's `tsa` are ROM offsets (not GBA addresses).
     */
    public fun extract(
        rom: ByteArray,
        game: String,
        name: String,
        image: Int,
        palette: Int,
        paletteBanks: Int,
        layers: List<Layer>,
        firstTile: Int = 0,
        firstPalette: Int = 0,
    ): Output {
        val img = Lz77.decode(rom, image).data
        val tiles = img.size / Tiles.BYTES_4BPP
        // Pad to whole rows of 16 tiles so the PNG is a rectangle.
        val padded = img.copyOf((tiles + 15) / 16 * 16 * Tiles.BYTES_4BPP)
        val pal = Bgr555.readPalette(rom, palette, 16 * paletteBanks)
        val png = IndexedPng.write(
            Tiles.decode4bpp(padded, 0, padded.size / Tiles.BYTES_4BPP, 16),
            pal,
        )
        val maps = layers.map { l ->
            val t = Tsa.decode(Lz77.decode(rom, l.tsa).data)
            // Add the layer's tile base to every entry, wrapping to the 16-bit TSA entry.
            val shifted = IntArray(t.entries.size) { (t.entries[it] + l.tileBase) and 0xFFFF }
            l.bg to Tsa(t.width, t.height, shifted)
        }
        val rows = maps.map { (bg, t) ->
            linkedMapOf(
                "bg" to bg, "width" to t.width, "height" to t.height,
                "entries" to t.entries.toList(),
            )
        }
        val doc = linkedMapOf<String, Any>(
            "format" to "gbafe.screen", "game" to game, "version" to 1, "name" to name,
            "image" to "$name.png", "tiles" to tiles, "paletteBanks" to paletteBanks,
        )
        if (firstTile != 0) doc["firstTile"] = firstTile
        if (firstPalette != 0) doc["firstPalette"] = firstPalette
        doc["layers"] = rows
        val json = Json.write(doc)
        return Output(png, json, maps)
    }
}
