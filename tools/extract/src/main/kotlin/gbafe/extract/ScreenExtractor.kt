package gbafe.extract

import gbafe.data.Bgr555
import gbafe.data.Lz77
import gbafe.data.Tiles
import gbafe.data.Tsa

/**
 * Full-screen still images drawn from one tile set plus TSA tilemaps (title cards, the health & safety screen):
 * writes `<name>.png` (the 4bpp tile set, 16 tiles per row in tile order, with the screen's palette banks) and a
 * `gbafe.screen` document listing each BG layer's tilemap (raw u16 entries, top row first, tile base added).
 * Game-neutral; each game's step supplies the ROM offsets.
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
        /** Per layer (same order as requested): BG number to its tilemap, tile base applied (what the JSON lists). */
        public val tilemaps: List<Pair<Int, Tsa>>,
    )

    /**
     * Extracts screen [name] for [game]: LZ77 4bpp tiles at [image], [paletteBanks] × 16 colours at [palette],
     * and [layers].
     */
    public fun extract(rom: ByteArray, game: String, name: String, image: Int, palette: Int, paletteBanks: Int, layers: List<Layer>): Output {
        val img = Lz77.decode(rom, image).data
        val tiles = img.size / Tiles.BYTES_4BPP
        val padded = img.copyOf((tiles + 15) / 16 * 16 * Tiles.BYTES_4BPP)
        val pal = Bgr555.readPalette(rom, palette, 16 * paletteBanks)
        val png = IndexedPng.write(Tiles.decode4bpp(padded, 0, padded.size / Tiles.BYTES_4BPP, 16), pal)
        val maps = layers.map { l ->
            val t = Tsa.decode(Lz77.decode(rom, l.tsa).data)
            l.bg to Tsa(t.width, t.height, IntArray(t.entries.size) { (t.entries[it] + l.tileBase) and 0xFFFF })
        }
        val rows = maps.map { (bg, t) ->
            linkedMapOf("bg" to bg, "width" to t.width, "height" to t.height, "entries" to t.entries.toList())
        }
        val json = Json.write(linkedMapOf("format" to "gbafe.screen", "game" to game, "version" to 1, "name" to name,
            "image" to "$name.png", "tiles" to tiles, "paletteBanks" to paletteBanks, "layers" to rows))
        return Output(png, json, maps)
    }
}
