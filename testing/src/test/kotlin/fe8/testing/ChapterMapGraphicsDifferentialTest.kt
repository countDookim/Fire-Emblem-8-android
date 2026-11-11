package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The chapter map graphics loaders of [ChapterMapGfx] against `ram_trace_newgame_bm`. That
 * run has one full dump (VRAM, palette RAM) right after `StartBattleMap` (f9319, chapter 0); the
 * state before it is synthetic: `gPlaySt` and the LCD buffer come from the dump (the BG priorities
 * are overwritten with values the ports must change), and VRAM and the palette buffer are
 * pre-filled with a sentinel, so a no-op fails.
 *
 * Compared: BG VRAM 0x8000.. (tileset part 1) and 0xC000.. (part 2) for the decompressed length,
 * palette banks 6-15, banks 0x10-0x11, OBJ VRAM bank 0 rows (0x12 x 4 tiles at 0x10000 + n *
 * 0x400) and the four BG control words. Not compared: `gGenericBuffer` (reused by later code),
 * the icon/text/face hooks of `sub_80156D4` (stand-ins, only their call order is checked).
 */
class ChapterMapGraphicsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun post(dump: String): Post {
        val run = ref.run("ram_trace_newgame_bm")
        val ew = run.region(dump, "ewram")
        val iw = run.region(dump, "iwram")
        val playSt = PlaySt().apply { decode(ew, sym["gPlaySt"].address - GbaMem.EWRAM) }
        val lcd = LcdControl().apply {
            readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
        }
        // gPaletteBuffer, not palette RAM: f09319 is mid-fade (palette RAM is not synced yet).
        val buf = sym["gPaletteBuffer"].address - GbaMem.EWRAM
        return Post(run.region(dump, "vram"), ew.copyOfRange(buf, buf + 0x400), lcd, playSt)
    }

    private fun fixture(post: Post, rom: ByteArray): Fixture {
        val hw = BgHardware()
        hw.vram.fill(SENTINEL)
        hw.palette.fill(((SENTINEL.toInt() and 0xFF) * 0x101).toShort())
        // Priorities the ports must overwrite: BG0..BG3 = 3, 2, 1, 0.
        for (bg in 0..3) hw.lcd.bgCnt[bg] = (post.lcd.bgCnt[bg] and 3.inv()) or (3 - bg)
        val playSt = PlaySt()
        playSt.chapterIndex = post.playSt.chapterIndex
        playSt.chapterWeatherId = post.playSt.chapterWeatherId
        val view = RomView.ofRom(rom)
        return Fixture(hw, ChapterMapGfx(view, ChapterData(view), hw, playSt), playSt)
    }

    private fun palEntry(pal: ByteArray, index: Int): Int =
        (pal[2 * index].toInt() and 0xFF) or ((pal[2 * index + 1].toInt() and 0xFF) shl 8)

    private fun assertPalettes(f: Fixture, post: Post, first: Int, count: Int, what: String) {
        for (i in first * 16 until (first + count) * 16) {
            val got = f.hw.palette[i].toInt() and 0xFFFF
            assertEquals(palEntry(post.pal, i), got, "$what color $i")
        }
        assertTrue(f.hw.modifiedPalette, "$what: palette sync requested")
    }

    private fun assertVram(f: Fixture, post: Post, from: Int, len: Int, what: String) {
        for (i in from until from + len) {
            assertEquals(post.vram[i], f.hw.vram[i], "$what VRAM 0x${i.toString(16)}")
        }
    }

    private fun decompressedLength(rom: ByteArray, chapter: Int, part: Int): Int {
        val view = RomView.ofRom(rom)
        val data = ChapterData(view)
        val id = if (part == 1) data.obj1Id(chapter) else data.obj2Id(chapter)
        val at = view.u32(ChapterData.CHAPTER_DATA_ASSET_TABLE + 4 * id)
        if (at == 0) return 0
        return view.decompressInto(at, ByteArray(0x10000), 0)
    }

    @Test
    // Not @VerifiesPort("UnpackChapterMapGraphics"): its obj2Id != 0 branch never runs in a trace.
    fun `tileset graphics and palette match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = romFile.readBytes()
        for (dump in DUMPS) {
            val post = post(dump)
            val f = fixture(post, rom)
            val chapter = post.playSt.chapterIndex
            f.gfx.unpackChapterMapGraphics(chapter)
            val len1 = decompressedLength(rom, chapter, 1)
            assertTrue(len1 > 0x1000, "part 1 length $len1")
            // Chapter 16's tile animation rewrites 0xA000..0xA7FF after the load (f09529 is ~200
            // frames later); the rest of the tileset must match.
            val skip = if (dump == "f09319") IntRange.EMPTY else 0xA000 until 0xA800
            for (i in ChapterMapGfx.TILESET_PART1 until ChapterMapGfx.TILESET_PART1 + len1) {
                if (i in skip) continue
                assertEquals(post.vram[i], f.hw.vram[i], "$dump part 1 VRAM 0x${i.toString(16)}")
            }
            // Part 2 (obj2Id != 0) is not compared: obj2Id is 0 for chapters 0 and 16, the only
            // chapters in the dumps.
            assertPalettes(f, post, 6, 10, "tileset")
        }
    }

    @Test
    @VerifiesPort("UnpackChapterMapPalette")
    fun `chapter palette alone matches the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        for (dump in DUMPS) {
            val post = post(dump)
            val f = fixture(post, romFile.readBytes())
            f.gfx.unpackChapterMapPalette()
            assertPalettes(f, post, 6, 10, "tileset")
            assertEquals(SENTINEL, f.hw.vram[0x8000], "no graphics written")
        }
    }

    @Test
    @VerifiesPort("InitBmBgLayers")
    fun `bg layer priorities match the dump and clouds put bg3 on 2`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        for (dump in DUMPS) {
            val post = post(dump)
            val f = fixture(post, romFile.readBytes())
            f.gfx.initBmBgLayers()
            for (bg in 0..3) assertEquals(post.lcd.bgCnt[bg], f.hw.lcd.bgCnt[bg], "bgcnt $bg")
            f.playSt.chapterWeatherId = ChapterMapGfx.WEATHER_CLOUDS
            f.gfx.initBmBgLayers()
            assertEquals(listOf(0, 1, 2, 2), (0..3).map { f.hw.lcd.bgCnt[it] and 3 })
        }
    }

    @Test
    @VerifiesPort("LoadObjUIGfx", "sub_80156D4")
    fun `OBJ UI graphics and palettes match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        for (dump in DUMPS) {
            val post = post(dump)
            val f = fixture(post, romFile.readBytes())
            val calls = ArrayList<String>()
            f.gfx.resetText = { calls += "text" }
            f.gfx.loadLegacyUiFrameGraphics = { calls += "frame" }
            f.gfx.resetFaces = { calls += "faces" }
            f.gfx.resetIconGraphics = { calls += "icons" }
            f.gfx.loadIconPalettes = { calls += "pal$it" }
            f.gfx.sub80156D4()
            assertEquals(listOf("text", "frame", "faces", "icons", "pal4"), calls)
            for (row in 0 until ChapterMapGfx.MISC_UI_HEIGHT) {
                val at = ChapterMapGfx.OBJ_VRAM0 + row * 0x400
                assertVram(f, post, at, 0x12 * 0x20, "OBJ row $row")
            }
            assertPalettes(f, post, ChapterMapGfx.OBJ_PAL, ChapterMapGfx.OBJ_PAL_COUNT, "OBJ UI")
        }
    }

    @Test
    @VerifiesPort("ApplySystemObjectsPalettes")
    fun `system object palettes match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        for (dump in DUMPS) {
            val post = post(dump)
            val f = fixture(post, romFile.readBytes())
            f.gfx.applySystemObjectsPalettes()
            assertPalettes(f, post, ChapterMapGfx.OBJ_PAL, ChapterMapGfx.OBJ_PAL_COUNT, "OBJ UI")
            assertEquals(SENTINEL, f.hw.vram[ChapterMapGfx.OBJ_VRAM0], "no graphics written")
        }
    }

    private companion object {
        /** Full dumps after the game loaded a chapter map: chapter 0 (f09319), 16 (f09529). */
        val DUMPS = listOf("f09319", "f09529")
        const val SENTINEL: Byte = 0xAA.toByte()
    }

    // Helper classes go last: tools/progress.py credits a tag to the last class above it.

    /** Dump state after the game's `StartBattleMap`. */
    private class Post(
        val vram: ByteArray,
        val pal: ByteArray,
        val lcd: LcdControl,
        val playSt: PlaySt,
    )

    private class Fixture(val hw: BgHardware, val gfx: ChapterMapGfx, val playSt: PlaySt)
}
