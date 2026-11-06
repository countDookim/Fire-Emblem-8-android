package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M6-15: the unit sprite loaders of [UnitSprites] against `ram_trace_first_phase` f19430. The
 * sprites loaded in the dump are replayed through `ResetUnitSprites` and `UseUnitSprite` in the
 * order their slots imply (16x16 sprites by falling counter, the others by rising counter) and
 * `gSMSGfxBuffer`, `gUnitSpriteSlots` and the counters are compared with the dump; the OBJ
 * palettes are compared with `gPaletteBuffer` and palette RAM.
 */
class UnitSpritesDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ResetUnitSprites", "UseUnitSprite", "ApplyUnitSpriteImage16x16",
        "ApplyUnitSpriteImage16x32",
    )
    fun `sprite buffer and slots match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val d = dump()
        val rom = RomView.ofRom(romFile.readBytes())
        val used = (0 until 0xD0).filter { d.slots[it] != 0xFF }
        val small = used.filter { rom.u16(INFO + 8 * (it and 0x7F) + 2) == 0 }
        val big = used - small.toSet()
        // Two runs over differently pre-filled buffers: the bytes both agree on were written by
        // the port. The dump also holds stale data of earlier screens outside the loaded slots.
        val runs = listOf(0x5A, 0xA5).map { fill ->
            val sprites = UnitSprites(rom, BgHardware(), BmSt())
            sprites.gfxBuffer.fill(fill.toByte())
            sprites.slots.fill(5) // stale entries the reset must clear
            sprites.resetUnitSprites()
            for (id in small.sortedByDescending { slotIndex(d.slots[it]) }) {
                sprites.useUnitSprite(id)
            }
            for (id in big.sortedBy { slotIndex(d.slots[it]) }) sprites.useUnitSprite(id)
            sprites
        }
        val sprites = runs[0]
        for (id in 0 until 0xD0) {
            assertEquals(d.slots[id], sprites.slots[id].toInt() and 0xFF, "slot of id $id")
        }
        var written = 0
        for (i in d.buf.indices) {
            if (runs[0].gfxBuffer[i] != runs[1].gfxBuffer[i]) continue
            written++
            assertEquals(d.buf[i], sprites.gfxBuffer[i], "gSMSGfxBuffer 0x${i.toString(16)}")
        }
        assertEquals(1920, written, "bytes written by the port: $written")
        assertEquals(d.gfx32, sprites.gfx32Counter, "gSMS32xGfxIndexCounter")
        assertEquals(d.gfx16, sprites.gfx16Counter, "gSMS16xGfxIndexCounter")
    }

    @Test
    @VerifiesPort("ApplyUnitSpritePalettes")
    fun `unit sprite palettes match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val d = dump()
        val hw = BgHardware()
        val sprites = UnitSprites(RomView.ofRom(romFile.readBytes()), hw, d.bmSt)
        sprites.applyUnitSpritePalettes()
        for (i in 0x1B * 16 until 0x20 * 16) {
            assertEquals(d.palBuf[i], hw.palette[i].toInt() and 0xFFFF, "palette colour $i")
        }
    }

    @Test
    @VerifiesPort("RefreshUnitSprites", "AddUnitSprite", "GetUnitSpritePalette")
    fun `handle list matches the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val d = dump()
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run("ram_trace_first_phase")
        val ew = run.region("f19430", "ewram")
        val e = GbaMem.EWRAM
        val units = UnitTable(rom).apply { loadFrom(ew) }
        val rowTable = GbaMem.u32(ew, e, sym["gBmMapUnit"].address)
        val mapUnit = { x: Int, y: Int ->
            ew[GbaMem.u32(ew, e, rowTable + 4 * y) - e + x].toInt() and 0xFF
        }
        val trapAt = sym["sTrapPool"].address - e
        val traps = ew.copyOfRange(trapAt, trapAt + 0x200)
        val sprites = UnitSprites(rom, BgHardware(), d.bmSt)
        sprites.resetUnitSprites()
        val list = UnitSpriteHandles(rom, sprites, units)
        list.refreshUnitSprites(mapUnit, traps)
        val at = sym["gSMSHandleArray"].address - e
        val used = list.handleIt * 12
        assertTrue(list.handleIt > 3, "handles: ${list.handleIt}")
        for (i in 0 until used) {
            assertEquals(ew[at + i], list.handles[i], "handle byte $i")
        }
        assertEquals(
            GbaMem.u32(ew, e, sym["gSMSHandleIt"].address),
            0x0203A018 + used,
            "gSMSHandleIt",
        )
        // pMapSpriteHandle of every unit (offset 0x3C).
        val ref0 = UnitTable(rom).apply { loadFrom(ew) }
        for (o in 0 until UnitTable.TOTAL_SIZE step UnitTable.UNIT_SIZE) {
            assertEquals(
                GbaMem.u32(ref0.bytes, 0, o + 0x3C),
                GbaMem.u32(units.bytes, 0, o + 0x3C),
                "pMapSpriteHandle at $o",
            )
        }
    }

    @Test
    @VerifiesPort("ForceSyncUnitSpriteSheet")
    fun `forced sheet sync matches OBJ VRAM`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run("ram_trace_first_phase")
        val e = GbaMem.EWRAM
        val bufAt = sym["gSMSGfxBuffer"].address - e
        val rom = RomView.ofRom(romFile.readBytes())
        for (frame in 19430..19530 step 10) {
            val dump = "f$frame"
            val ew = run.region(dump, "ewram")
            val iw = run.region(dump, "iwram")
            val clock = GbaMem.u32(iw, GbaMem.IWRAM, sym["gGameClock"].address)
            val hw = BgHardware()
            val sprites = UnitSprites(rom, hw, BmSt())
            ew.copyInto(sprites.gfxBuffer, 0, bufAt, bufAt + 0x6000)
            sprites.gameClock = { clock }
            sprites.syncFlag = 3
            sprites.forceSyncUnitSpriteSheet()
            assertEquals(0, sprites.syncFlag)
            val vram = run.region(dump, "vram")
            for (i in 0 until 0x2000) {
                assertEquals(
                    vram[0x11000 + i], hw.vram[0x11000 + i],
                    "$dump (clock $clock, ${clock % 72}) OBJ VRAM +0x1000+$i",
                )
            }
        }
    }

    private fun slotIndex(value: Int): Int {
        val lut = value * 2
        val row = lut / 32
        return (row / 4) * 32 + lut % 32 + (row % 4) / 2
    }

    private fun dump(): Dump {
        val run = ref.run("ram_trace_first_phase")
        val ew = run.region("f19430", "ewram")
        val e = GbaMem.EWRAM
        val slotsAt = sym["gUnitSpriteSlots"].address - e
        val bufAt = sym["gSMSGfxBuffer"].address - e
        val palAt = sym["gPaletteBuffer"].address - e
        val bm = BmSt().apply { decode(ew, sym["gBmSt"].address - e) }
        return Dump(
            IntArray(0xD0) { ew[slotsAt + it].toInt() and 0xFF },
            ew.copyOfRange(bufAt, bufAt + 0x6000),
            IntArray(512) { GbaMem.u16(ew, e, sym["gPaletteBuffer"].address + 2 * it) },
            GbaMem.u32(ew, e, sym["gSMS32xGfxIndexCounter"].address),
            GbaMem.u32(ew, e, sym["gSMS16xGfxIndexCounter"].address),
            bm,
        ).also { check(palAt >= 0) }
    }

    private class Dump(
        val slots: IntArray,
        val buf: ByteArray,
        val palBuf: IntArray,
        val gfx32: Int,
        val gfx16: Int,
        val bmSt: BmSt,
    )

    private companion object {
        const val INFO = 0x088AF880
    }
}
