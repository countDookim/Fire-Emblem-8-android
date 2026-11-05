package fe8.testing

import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-19: [Fe8EventFlags] against the ROM. Symbol addresses come from
 * decomp/fireemblem8.nm.txt: `GetPermanentFlagBitsSize` 0x08083DC8, `GetChapterFlagBitsSize`
 * 0x08083DD4 (4 bytes each: `movs r0,#imm; bx lr`) and `gFlagBitMaskLut` 0x089E857C (13 bytes).
 */
class EventFlagsRomTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private val nmFile = listOf(
        File("decomp/fireemblem8.nm.txt"),
        File("../Fire-Emblem-8-rom/decomp/fireemblem8.nm.txt"),
        File("/home/dookim/FE8/Fire-Emblem-8-rom/decomp/fireemblem8.nm.txt"),
    ).firstOrNull { it.isFile }

    private fun rom(): ByteArray {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return romFile.readBytes()
    }

    /** Value of `movs r0, #imm8` (0x20xx) at [address], requiring `bx lr` (0x4770) after it. */
    private fun movsReturn(rom: ByteArray, address: Int): Int {
        val o = address - 0x08000000
        val ins = (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        val bx = (rom[o + 2].toInt() and 0xFF) or ((rom[o + 3].toInt() and 0xFF) shl 8)
        assertEquals(0x20, ins shr 8, "movs r0,#imm8 at 0x${address.toString(16)}")
        assertEquals(0x4770, bx, "bx lr")
        return ins and 0xFF
    }

    /** Parsed nm line of [name]: address and size (null when the file lists none). */
    private fun symbol(name: String): Pair<Long, Long?>? {
        val nm = nmFile ?: return null
        for (line in nm.readLines()) {
            val p = line.trim().split(' ')
            if (p.last() != name) continue
            return p[0].toLong(16) to (if (p.size == 4) p[1].toLong(16) else null)
        }
        return null
    }

    @Test
    @VerifiesPort("GetPermanentFlagBitsSize", "GetChapterFlagBitsSize")
    fun `save sizes equal the movs immediates in the rom`() {
        val rom = rom()
        val permanent = movsReturn(rom, 0x08083DC8)
        val chapter = movsReturn(rom, 0x08083DD4)
        assertEquals(0x19, permanent)
        assertEquals(5, chapter)
        assertEquals(permanent, Fe8EventFlags.getPermanentFlagBitsSize())
        assertEquals(chapter, Fe8EventFlags.getChapterFlagBitsSize())
        assertEquals(permanent, Fe8EventFlags.layout.permanentSaveBytes)
        assertEquals(chapter, Fe8EventFlags.layout.chapterSaveBytes)
    }

    @Test
    fun `bit mask table equals gFlagBitMaskLut in the rom`() {
        val rom = rom()
        if (nmFile != null) assertEquals(0x089E857CL to 13L, symbol("gFlagBitMaskLut"))
        val o = 0x089E857C - 0x08000000
        val lut = rom.copyOfRange(o, o + 13)
        assertTrue(lut.contentEquals(Fe8EventFlags.layout.bitMask), "mask table differs from ROM")
    }

    @Test
    fun `storage sizes equal the gaps between the nm symbols`() {
        assumeTrue(nmFile != null, "nm symbol file not found")
        // IWRAM bss symbols carry no size in the nm file; the arrays are adjacent, so the size of
        // each is the distance to the next symbol (gPermanentFlagBits, gChapterFlagBits, gGMData).
        val permanent = symbol("gPermanentFlagBits")!!.first
        val chapter = symbol("gChapterFlagBits")!!.first
        val next = symbol("gGMData")!!.first
        assertEquals(0x03005250L, permanent)
        assertEquals(Fe8EventFlags.PERMANENT_STORAGE_BYTES.toLong(), chapter - permanent)
        assertEquals(Fe8EventFlags.CHAPTER_STORAGE_BYTES.toLong(), next - chapter)
    }
}
