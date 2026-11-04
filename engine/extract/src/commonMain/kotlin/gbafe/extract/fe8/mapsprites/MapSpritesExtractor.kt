package gbafe.extract.fe8.mapsprites

import gba.data.Bgr555
import gba.data.Lz77
import gba.data.Tiles
import gba.extract.IndexedPng
import gba.extract.Json
import gbafe.extract.hexUpper
import gbafe.extract.padDec

/**
 * Extracts FE8 standing map sprites (`unit_icon_wait_table`, decomp `src/unit_icon_wait_data.c`,
 * `UnitIconWait {u16 pattern; u16 size; sheet}` in `include/unit_icon_data.h`, 8 bytes per entry).
 *
 * Each sheet is LZ77 4bpp holding 3 animation frames stacked vertically: `size` 0 = 16×16, 1 = 16×32
 * (2 tiles wide), 2 = 32×32 (4 tiles wide). PNGs use the blue (player) faction palette, the first of
 * `gPal_MapSprite`'s 4 palettes (blue, red, green, purple; applied by `src/bmudisp.c` at OBJ palette 0x1C);
 * the other factions only swap palettes, so the indices are what matters.
 *
 * Moving sprites (`unit_icon_move_table`, `struct MuInfo {img; anim}` in decomp `include/mu.h`, entry `jid - 1`,
 * see `GetMuImg`/`GetMuAnimForJid` in `src/mu.c`) are an LZ77 4bpp sheet plus an AP definition (`src/ap.c`):
 * `u16 frameListOff, animListOff` (relative to the definition), frame list = u16 offsets relative to the frame
 * list, anim list = u16 offsets relative to the anim list. A frame is `u16 n; n × {attr0, attr1, attr2}; n × u16
 * sheetTile`; `AP_QueueObjGraphics` copies each object's `w×h` tiles linearly from `sheet + tile*32` (row by
 * row via `Register2dChrMove`), so the sheet PNG is laid out 4 tiles (32 px) wide, row-major. An anim script is
 * `{u16 duration, u16 frame}` pairs ended by duration 0 with code 0xFFFF (loop), 0 (stop) or 1 (delete).
 * Anim index = `MU_FACING_*` (0 left, 1 right, 2 down, 3 up, 4 selected, ...).
 */
public object MapSpritesExtractor {
    /** Size of `UnitIconWait`. */
    public const val ENTRY_SIZE: Int = 8

    /** Result: PNGs keyed by relative path and the metadata rows. */
    public class Output(
        /** `standing/NNN.png` → PNG bytes. */
        public val files: Map<String, ByteArray>,
        /** One row per standing-sprite id. */
        public val entries: List<Map<String, Any?>>,
        /** One row per `unit_icon_move_table` entry (class `id + 1`); empty when not extracted. */
        public val moving: List<Map<String, Any?>> = emptyList(),
    ) {
        /** The `gbafe.mapsprites` document. */
        public val json: String
            get() = Json.write(linkedMapOf("format" to "gbafe.mapsprites", "game" to "fe8", "version" to 1, "standing" to entries, "moving" to moving))
    }

    /** Extracts [count] standing sprites; [paletteOffset] is `gPal_MapSprite` (4 × 16 colors). */
    public fun extract(rom: ByteArray, tableOffset: Int, count: Int, paletteOffset: Int): Output {
        val palette = Bgr555.readPalette(rom, paletteOffset, 16)
        val files = LinkedHashMap<String, ByteArray>()
        val entries = List(count) { i ->
            val e = tableOffset + i * ENTRY_SIZE
            val pattern = u16(rom, e)
            val size = u16(rom, e + 2)
            val ptr = u16(rom, e + 4) or (u16(rom, e + 6) shl 16)
            val (w, h) = when (size) { 0 -> 16 to 16; 1 -> 16 to 32; 2 -> 32 to 32; else -> throw IllegalArgumentException("sprite $i: size $size") }
            val data = Lz77.decode(rom, ptr - 0x08000000).data
            val tiles = data.size / Tiles.BYTES_4BPP
            require(tiles == 3 * (w / 8) * (h / 8)) { "sprite $i: $tiles tiles for 3 frames of ${w}x$h" }
            val path = "standing/${padDec(i, 3)}.png"
            files[path] = IndexedPng.write(Tiles.decode4bpp(data, 0, tiles, w / 8), palette)
            linkedMapOf("id" to i, "image" to path, "width" to w, "height" to h, "frames" to 3, "pattern" to pattern)
        }
        return Output(files, entries)
    }

    /** Size of `MuInfo`. */
    public const val MOVE_ENTRY_SIZE: Int = 8

    /** Sheet width in tiles used for moving-sprite PNGs (32×32 objects are copied linearly, 16 tiles each). */
    public const val MOVE_SHEET_TILES_PER_ROW: Int = 4

    /** [extract] plus [moveCount] moving sprites from `unit_icon_move_table` at [moveTableOffset]. */
    public fun extract(rom: ByteArray, tableOffset: Int, count: Int, paletteOffset: Int, moveTableOffset: Int, moveCount: Int): Output {
        val standing = extract(rom, tableOffset, count, paletteOffset)
        val palette = Bgr555.readPalette(rom, paletteOffset, 16)
        val files = LinkedHashMap(standing.files)
        val sheetPaths = HashMap<Int, String>()
        val moving = List(moveCount) { i ->
            val e = moveTableOffset + i * MOVE_ENTRY_SIZE
            val img = u32(rom, e)
            val anim = u32(rom, e + 4)
            require(img != 0 && anim != 0) { "moving sprite $i: null pointer" }
            val path = sheetPaths.getOrPut(img) {
                val raw = Lz77.decode(rom, img - ROM).data
                // Some sheets end mid-row (e.g. 241 tiles); pad with blank tiles to a full PNG row.
                val tiles = (raw.size / Tiles.BYTES_4BPP + MOVE_SHEET_TILES_PER_ROW - 1) / MOVE_SHEET_TILES_PER_ROW * MOVE_SHEET_TILES_PER_ROW
                val data = raw.copyOf(tiles * Tiles.BYTES_4BPP)
                "moving/${padDec(i, 3)}.png".also { files[it] = IndexedPng.write(Tiles.decode4bpp(data, 0, tiles, MOVE_SHEET_TILES_PER_ROW), palette) }
            }
            val (frames, anims) = readAp(rom, anim - ROM)
            linkedMapOf("id" to i, "classId" to i + 1, "image" to path, "frames" to frames, "anims" to anims)
        }
        return Output(files, standing.entries, moving)
    }

    /** Decodes an AP definition at [base] into frames (objects) and anim scripts. */
    internal fun readAp(rom: ByteArray, base: Int): Pair<List<List<Map<String, Int>>>, List<Map<String, Any>>> {
        val frameList = base + u16(rom, base)
        val animList = base + u16(rom, base + 2)
        require(animList > frameList) { "AP at 0x${hexUpper(base)}: anim list before frame list" }
        val frameCount = (animList - frameList) / 2
        val frameStarts = List(frameCount) { frameList + u16(rom, frameList + 2 * it) }
        var limit = frameStarts.minOrNull() ?: Int.MAX_VALUE
        val animStarts = ArrayList<Int>()
        while (animList + 2 * animStarts.size < limit) {
            val a = animList + u16(rom, animList + 2 * animStarts.size)
            animStarts += a
            limit = minOf(limit, a)
        }
        val frames = frameStarts.map { f ->
            val n = u16(rom, f)
            List(n) { k ->
                linkedMapOf(
                    "attr0" to u16(rom, f + 2 + 6 * k), "attr1" to u16(rom, f + 4 + 6 * k), "attr2" to u16(rom, f + 6 + 6 * k),
                    "tile" to u16(rom, f + 2 + 6 * n + 2 * k),
                )
            }
        }
        val anims = animStarts.map { a ->
            val steps = ArrayList<Map<String, Int>>()
            var p = a
            while (u16(rom, p) != 0) {
                val frame = u16(rom, p + 2)
                require(frame < frameCount) { "AP at 0x${hexUpper(base)}: frame $frame of $frameCount" }
                steps += linkedMapOf("duration" to u16(rom, p), "frame" to frame)
                p += 4
            }
            val end = when (val code = u16(rom, p + 2)) { 0xFFFF -> "loop"; 0 -> "stop"; 1 -> "delete"; else -> throw IllegalArgumentException("AP at 0x${hexUpper(base)}: end code $code") }
            linkedMapOf("steps" to steps, "end" to end)
        }
        return frames to anims
    }

    private const val ROM = 0x08000000

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
