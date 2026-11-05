package fe8.testing

import fe8.game.event.Fe8ScriptTools
import fe8.game.event.NmSymbolIndex
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * ROM-golden test of the script assembler (not a port): the v1 `prologue.fe8s` extracted from the
 * ROM, parsed and assembled with every event at its vanilla address (from
 * `decomp/fireemblem8.nm.txt`), must reproduce the bytes of all 32 `EventScr_Prologue*` scripts
 * in the ROM at their symbol sizes.
 */
class ScriptAssemblerRomTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")
    private val nmFile = listOf(
        File("decomp/fireemblem8.nm.txt"),
        File("../Fire-Emblem-8-rom/decomp/fireemblem8.nm.txt"),
        File("/home/dookim/FE8/Fire-Emblem-8-rom/decomp/fireemblem8.nm.txt"),
    ).firstOrNull { it.isFile }
    private val scriptFile = listOf(
        File(assets, "extracted/scripts/prologue.fe8s"),
        File("../Fire-Emblem-8-rom/extracted/scripts/prologue.fe8s"),
        File("/home/dookim/FE8/Fire-Emblem-8-rom/extracted/scripts/prologue.fe8s"),
    ).firstOrNull { it.isFile }

    private class Sized(val address: Int, val size: Int)

    /** `EventScr_Prologue*` name to (address, size) from rows like `089eebe8 000000d4 T Name`. */
    private fun prologueSymbols(nm: String): Map<String, Sized> {
        val out = LinkedHashMap<String, Sized>()
        for (row in nm.lineSequence()) {
            if (!row.contains(" EventScr_Prologue")) continue
            val p = row.trim().split(' ')
            if (p.size != 4 || p[2] != "T") continue
            out[p[3]] = Sized(p[0].toLong(16).toInt(), p[1].toLong(16).toInt())
        }
        return out
    }

    @Test
    fun `prologue fe8s assembles to the ROM bytes of all 32 scripts`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assumeTrue(nmFile != null, "decomp/fireemblem8.nm.txt not present")
        assumeTrue(scriptFile != null, "prologue.fe8s not extracted")
        val nmText = nmFile!!.readText()
        val rom = romFile.readBytes()
        val syms = prologueSymbols(nmText)
        assertEquals(32, syms.size)

        val tools = Fe8ScriptTools(NmSymbolIndex(nmText))
        val parsed = tools.parser.parse("prologue.fe8s", scriptFile!!.readText())
        assertTrue(parsed.ok, parsed.errors.take(10).joinToString("\n"))
        val image = tools.assembler.assemble(parsed.lines) { syms[it]?.address ?: -1 }
        assertEquals(32, image.entryCount)

        val bad = ArrayList<String>()
        var exact = 0
        for ((name, s) in syms) {
            val e = image.find(name)
            if (e == null) {
                bad += "$name: not assembled"
                continue
            }
            assertEquals(s.address, e.baseAddress, name)
            val got = image.bytesOf(e)
            val off = s.address - 0x08000000
            val want = rom.copyOfRange(off, off + s.size)
            if (got.contentEquals(want)) {
                exact++
                continue
            }
            var w = 0
            while (w * 2 < minOf(got.size, want.size) && word(got, w) == word(want, w)) w++
            bad += "$name: size ${got.size} vs ${want.size}, first differing word $w " +
                "(got ${hex(got, w)}, want ${hex(want, w)})"
        }
        assertEquals(emptyList<String>(), bad, "$exact of 32 scripts byte-exact")
        assertEquals(32, exact)
    }

    private fun word(b: ByteArray, w: Int): Int {
        if (2 * w + 1 >= b.size) return -1
        return (b[2 * w].toInt() and 0xFF) or ((b[2 * w + 1].toInt() and 0xFF) shl 8)
    }

    private fun hex(b: ByteArray, w: Int): String = word(b, w).toString(16)
}
