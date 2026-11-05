package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import gba.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-28: [ChapterData] against bytes read straight from the ROM at the nm addresses
 * (`gChapterDataTable` 0x088B0890, 0x2DAC bytes; `gChapterDataAssetTable` 0x088B363C), decoded
 * independently of [ChapterData].
 */
class ChapterDataRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun rom(): ByteArray {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return romFile.readBytes()
    }

    private fun ByteArray.u8(addr: Int) = this[addr - 0x08000000].toInt() and 0xFF
    private fun ByteArray.u16(addr: Int) = u8(addr) or (u8(addr + 1) shl 8)
    private fun ByteArray.u32(addr: Int) = u16(addr) or (u16(addr + 2) shl 16)

    @Test
    @VerifiesPort("GetROMChapterStruct", "GetChapterMapPointer", "GetChapterEventDataPointer")
    fun `every chapter matches direct ROM reads`() {
        val bytes = rom()
        val data = ChapterData(RomView.ofRom(bytes))
        assertEquals(0x2DAC, 79 * 0x94)
        for (ch in 0 until 79) {
            val base = 0x088B0890 + ch * 0x94
            assertEquals(base, data.getROMChapterStruct(ch), "struct $ch")
            fun asset(offset: Int) = bytes.u32(0x088B363C + 4 * bytes.u8(base + offset))
            assertEquals(asset(8), data.getChapterMapPointer(ch), "map $ch")
            assertEquals(asset(0x0B), data.getChapterMapChangesPointer(ch), "changes $ch")
            assertEquals(asset(0x74), data.getChapterEventDataPointer(ch), "events $ch")
            assertEquals(bytes.u8(base + 4), data.obj1Id(ch))
            assertEquals(bytes.u8(base + 5), data.obj2Id(ch))
            assertEquals(bytes.u8(base + 6), data.paletteId(ch))
            assertEquals(bytes.u8(base + 7), data.tileConfigId(ch))
            assertEquals(bytes.u8(base + 8), data.mainLayerId(ch))
            assertEquals(bytes.u8(base + 9), data.objAnimId(ch))
            assertEquals(bytes.u8(base + 0x0A), data.paletteAnimId(ch))
            assertEquals(bytes.u8(base + 0x0B), data.changeLayerId(ch))
            assertEquals(bytes.u8(base + 0x74), data.mapEventDataId(ch))
            assertEquals(bytes.u8(base + 0x10), data.initialPosX(ch))
            assertEquals(bytes.u8(base + 0x11), data.initialPosY(ch))
            assertEquals(bytes.u8(base + 0x12), data.initialWeather(ch))
            for (slot in 0 until 11) {
                assertEquals(bytes.u16(base + 0x16 + 2 * slot), data.mapBgmId(ch, slot))
            }
        }
    }

    @Test
    fun `prologue values`() {
        val data = ChapterData(RomView.ofRom(rom()))
        assertEquals(listOf(1, 0, 2, 3, 4, 5, 6), listOf(
            data.obj1Id(0), data.obj2Id(0), data.paletteId(0), data.tileConfigId(0),
            data.mainLayerId(0),
            data.objAnimId(0), data.changeLayerId(0),
        ))
        assertEquals(0x089E863C, data.getChapterEventDataPointer(0))
    }
}
