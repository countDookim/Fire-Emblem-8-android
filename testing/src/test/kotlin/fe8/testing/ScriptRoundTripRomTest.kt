package fe8.testing

import fe8.game.event.Fe8ScriptTools
import fe8.game.event.NmSymbolIndex
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * ROM-golden round trip of the whole event-script extraction (not a port): every
 * `.fe8s` script file extracted from the ROM, parsed and assembled together with every event at its
 * vanilla address (from `decomp/fireemblem8.nm.txt`), must reproduce the ROM bytes of all 765
 * non-world-map `EventScr_*` symbols at their symbol sizes.
 */
class ScriptRoundTripRomTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")
    private val nmFile = ProcArrayDump.nmFile()
    private val scriptDir = listOf(
        File(assets, "extracted/scripts"),
        File("../../Fire-Emblem-8-rom/extracted/scripts"),
    ).firstOrNull { it.isDirectory && !it.listFiles { f -> f.extension == "fe8s" }.isNullOrEmpty() }

    /** Script name to the reason it is not byte-exact (target: empty). */
    private val allowList: Map<String, String> = emptyMap()

    /** Script name to the reason its extraction contains a `raw` line (target: empty). */
    private val rawAllowList: Map<String, String> = emptyMap()

    private class Sized(val address: Int, val size: Int)

    private fun symbols(nm: String): Map<String, Sized> {
        val out = LinkedHashMap<String, Sized>()
        for (row in nm.lineSequence()) {
            val p = row.trim().split(' ')
            if (p.size != 4 || p[2] != "T") continue
            if (!p[3].startsWith("EventScr_") || p[3].startsWith("EventScrWM")) continue
            out[p[3]] = Sized(p[0].toLong(16).toInt(), p[1].toLong(16).toInt())
        }
        return out
    }

    @Test
    fun `all 765 extracted scripts assemble to the ROM bytes`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assumeTrue(nmFile != null, "decomp/fireemblem8.nm.txt not present")
        assumeTrue(scriptDir != null, "scripts/*.fe8s not extracted")
        val nmText = nmFile!!.readText()
        val rom = romFile.readBytes()
        val syms = symbols(nmText)
        assertEquals(765, syms.size)

        val tools = Fe8ScriptTools(NmSymbolIndex(nmText))
        val files = scriptDir!!.listFiles { f -> f.extension == "fe8s" }!!.sortedBy { it.name }
        val lines = ArrayList<gbafe.script.ScriptLine>()
        val rawIn = ArrayList<String>()
        for (f in files) {
            val parsed = tools.parser.parse(f.name, f.readText())
            assertTrue(parsed.ok, f.name + ": " + parsed.errors.take(10).joinToString("\n"))
            lines += parsed.lines
        }
        for (f in files) {
            var event = ""
            for (l in f.readLines()) {
                if (l.startsWith("event ")) event = l.removePrefix("event ").trim()
                if (l.trimStart().startsWith("raw ") && event !in rawAllowList) {
                    rawIn += "${f.name}: $event: $l"
                }
            }
        }
        assertEquals(emptyList<String>(), rawIn, "raw lines outside the allow-list")

        val image = tools.assembler.assemble(lines) { syms[it]?.address ?: -1 }
        assertEquals(765, image.entryCount)

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
            if (name in allowList) continue
            var w = 0
            while (w * 2 < minOf(got.size, want.size) && word(got, w) == word(want, w)) w++
            bad += "$name: size ${got.size} vs ${want.size}, first differing word $w " +
                "(got ${hex(got, w)}, want ${hex(want, w)})"
        }
        assertEquals(emptyList<String>(), bad.take(20), "$exact of 765 scripts byte-exact")
        assertEquals(765 - allowList.size, exact)

        // Prologue references to other scripts resolve to assembled entries.
        val prologue = File(scriptDir, "prologue.fe8s").readText()
        val refs = Regex("EventScr_\\w+").findAll(prologue).map { it.value }.toSet()
        assertTrue(refs.isNotEmpty())
        for (r in refs) assertTrue(image.find(r) != null, "unresolved reference $r")
    }

    private fun word(b: ByteArray, w: Int): Int {
        if (2 * w + 1 >= b.size) return -1
        return (b[2 * w].toInt() and 0xFF) or ((b[2 * w + 1].toInt() and 0xFF) shl 8)
    }

    private fun hex(b: ByteArray, w: Int): String = word(b, w).toString(16)
}
