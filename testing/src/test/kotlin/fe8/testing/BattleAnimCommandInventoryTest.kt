package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Inventory (not a port claim, BA-i3) of the animation script instructions and `ekrcmd` ids
 * (`ANIM_INS_TYPE_COMMAND`) reached by `gAnims[0..3]` in the first melee round of
 * `ram_trace_prologue_attack` (anims set f1497; round 1 = right pair, f1537..f1630; the
 * left pair takes the hit from f1621). There is no function-entry trace, so every event is
 * inferred from the per-step EWRAM dumps: when `Anim::pScrCurrent` changes between two steps,
 * the instructions between the old and the new pointer are decoded (script words are read from
 * the same dump for RAM scripts and at runtime from the ROM for ROM scripts; no bytes are
 * embedded here). A pointer that cannot be reached by walking forward is a `jump` (script
 * switch or the `C01` pre-battle reset) and names the first instruction at the target. A
 * `hold` is a wait-type command (`pScrCurrent--` in `AnimInterpret`) first seen at its word.
 * Handler mapping is in docs/plans/M6C-J-breakdown.md ("BA-i3 result").
 */
class BattleAnimCommandInventoryTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun word(e: ByteArray, rom: ByteArray, a: Int): Int =
        if (a >= ROM_BASE) {
            val o = a - ROM_BASE
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8) or
                ((rom[o + 2].toInt() and 0xFF) shl 16) or ((rom[o + 3].toInt() and 0xFF) shl 24)
        } else {
            u32(e, a)
        }

    /** Instruction kind name and length in words (`AnimInterpret`'s decode). */
    private fun kind(x: Int): Pair<String, Int> {
        if (x >= 0) return "FORCESPRITE" to 1
        if (x and 0x40000000 != 0) return "PTRINS${(x ushr 28) and 3}" to 1
        return when (val t = (x ushr 24) and 0x3F) {
            0 -> "STOP" to 1
            1 -> "END" to 1
            2 -> "LOOP" to 1
            3 -> "MOVE" to 1
            4 -> "WAIT" to 1
            5 -> "CMD%02x".format(x and 0xFF) to 1
            6 -> "FRAME" to 3
            else -> "T$t" to 1
        }
    }

    private class Setup(val ram: Map<Int, ByteArray>, val rom: ByteArray)

    private fun load(first: Int, last: Int): Setup {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        val capture = ref.run(ATTACK)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val ram = (first..last).associateWith { capture.region(byStep.getValue(it), "ewram") }
        return Setup(ram, romFile.readBytes())
    }

    private fun anim(e: ByteArray, i: Int) = u32(e, G_ANIMS + 4 * i)

    /** `step:anim:event` strings for the steps after [first] up to [last]. */
    private fun events(s: Setup, first: Int, last: Int): List<String> {
        val out = ArrayList<String>()
        val prev = HashMap<Int, Int>()
        val held = HashSet<Pair<Int, Int>>()
        for (step in first..last) {
            val e = s.ram.getValue(step)
            for (i in 0 until 4) {
                val p = anim(e, i)
                val scr = u32(e, p + P_SCR_CURRENT)
                val old = prev[i]
                prev[i] = scr
                if (old == null) continue
                if (scr != old) {
                    var a = old
                    val seq = ArrayList<String>()
                    var ok = false
                    for (n in 0 until 12) {
                        val (k, len) = kind(word(e, s.rom, a))
                        seq += k
                        a += 4 * len
                        if (a == scr) {
                            ok = true
                            break
                        }
                    }
                    out += if (ok) {
                        "$step:$i:adv ${seq.joinToString(" ")}"
                    } else {
                        "$step:$i:jump ${kind(word(e, s.rom, scr)).first}"
                    }
                } else {
                    val k = kind(word(e, s.rom, scr)).first
                    if (k.startsWith("CMD") && k.substring(3).toInt(16) == u8(e, p + P_QUEUE) &&
                        u8(e, p + P_QUEUE_SIZE) == 0 && held.add(i to scr)
                    ) {
                        out += "$step:$i:hold at $k"
                    }
                }
            }
        }
        return out
    }

    @Test
    fun `anim pool setup f1497 and waiting loop f1499 to f1536`() {
        val s = load(1496, 1660)
        val at = s.ram.getValue(1497)
        // Four consecutive pool entries, all ENABLED.
        for (i in 0 until 4) {
            assertEquals(POOL + STRIDE * i, anim(at, i))
            assertEquals(1, u16(at, anim(at, i)))
        }
        // state2 = 0x400 | index << 8 (front frame bit 8, right position bit 9).
        val e = s.ram.getValue(1498)
        for (i in 0 until 4) assertEquals(0x0400 + 0x100 * i, u16(e, anim(e, i) + P_STATE2))
        // Pre-battle: anims 1 and 3 sit on the STOP word (state2 STOP bit) from f1499.
        val e1499 = s.ram.getValue(1499)
        assertEquals(0x4500, u16(e1499, anim(e1499, 1) + P_STATE2))
        assertEquals(0x4700, u16(e1499, anim(e1499, 3) + P_STATE2))
        val stop = word(e1499, s.rom, u32(e1499, anim(e1499, 1) + P_SCR_CURRENT))
        assertEquals("STOP", kind(stop).first)
        // Anims 0 and 2: FRAME, CMD01 loop; C01 (pre-battle arm) resets pScrCurrent to pScrStart.
        val ev = events(s, 1497, 1536)
        for (i in intArrayOf(0, 2)) {
            val mine = ev.filter { it.split(":")[1] == "$i" }
            assertEquals((1499..1535 step 2).map { "$it:$i:jump FRAME" }, mine.filter { "jump" in it })
            assertEquals((1500..1536 step 2).map { "$it:$i:adv FRAME" }, mine.filter { "adv" in it })
            assertEquals(38, mine.size)
        }
        for (st in 1500..1536) {
            val m = s.ram.getValue(st)
            assertEquals(1, u8(m, anim(m, 0) + P_QUEUE), "C01 queued f$st")
            assertEquals(0, u8(m, anim(m, 0) + P_QUEUE_SIZE), "queue drained f$st")
        }
    }

    @Test
    fun `first hit round events f1537 to f1630`() {
        val s = load(1496, 1660)
        val got = events(s, 1497, 1630).filter { it.substringBefore(':').toInt() >= 1537 }
        assertEquals(EXPECTED_ROUND1, got)
        val ids = got.filter { "CMD" in it }.map { it.substringAfter("CMD").take(2) }.toSortedSet()
        assertEquals(sortedSetOf("01", "03", "04", "06", "07", "1a", "1f", "24", "34", "43"), ids)
        // Only FRAME and CMD instructions are interpreted (no MOVE/WAIT/END/LOOP/PTRINS/sprite).
        val kinds = got.flatMap { it.substringAfterLast(':').split(" ") }
            .filter { it.startsWith("CMD") || it == "FRAME" || it == "STOP" || it == "MOVE" ||
                it == "WAIT" || it == "END" || it == "LOOP" || it.startsWith("PTRINS") }
            .map { if (it.startsWith("CMD")) "CMD" else it }.toSortedSet()
        assertEquals(sortedSetOf("CMD", "FRAME"), kinds)
    }

    @Test
    fun `round type and state3 progression of the first hit`() {
        val s = load(1496, 1660)
        fun b(step: Int, i: Int, off: Int): Int {
            val e = s.ram.getValue(step)
            return u8(e, anim(e, i) + off)
        }
        fun h(step: Int, i: Int, off: Int): Int {
            val e = s.ram.getValue(step)
            return u16(e, anim(e, i) + off)
        }
        // f1537: round start; right pair HIT_CLOSE (0), left pair TAKING_HIT_CLOSE (6),
        // C01 blocking in battle set on all four.
        for (i in 2..3) assertEquals(0, b(1537, i, P_ROUND_TYPE))
        for (i in 0..1) assertEquals(6, b(1537, i, P_ROUND_TYPE))
        for (i in 0..3) assertEquals(ANIM_BIT3_C01_BLOCKING, h(1537, i, P_STATE3))
        // CMD04 (f1574): TAKE_BACK_ENABLE | C01 blocking | HIT_EFFECT_APPLIED.
        assertEquals(ANIM_BIT3_C01_BLOCKING, h(1573, 2, P_STATE3))
        assertEquals(0x000d, h(1574, 2, P_STATE3))
        // CMD01 holds f1588..f1610 and is released at f1611 (state3 cleared).
        assertEquals(0x000d, h(1610, 2, P_STATE3))
        assertEquals(0, h(1611, 2, P_STATE3))
        // Left pair takes over its own round after CMD06 (f1621): round type 0 from f1622.
        assertEquals(6, b(1621, 0, P_ROUND_TYPE))
        assertEquals(0, b(1622, 0, P_ROUND_TYPE))
        // CMD0d at f1630 switches the right pair to the next round type (TAKING_HIT_CLOSE).
        assertEquals(0, b(1629, 2, P_ROUND_TYPE))
        assertEquals(6, b(1630, 2, P_ROUND_TYPE))
        assertEquals(0x0d, b(1630, 2, P_QUEUE))
        assertTrue(b(1629, 2, P_QUEUE) != 0x0d)
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val EWRAM = 0x02000000
        const val ROM_BASE = 0x08000000
        const val G_ANIMS = 0x02000000
        const val POOL = 0x02028F78
        const val STRIDE = 0x48
        const val P_STATE2 = 0x0C
        const val P_STATE3 = 0x10
        const val P_ROUND_TYPE = 0x12
        const val P_QUEUE_SIZE = 0x14
        const val P_QUEUE = 0x15
        const val P_SCR_CURRENT = 0x20
        const val ANIM_BIT3_C01_BLOCKING = 0x0004

        /** Events of f1537..f1630 (see the class KDoc for the notation). */
        val EXPECTED_ROUND1 = listOf(
            "1537:0:jump FRAME",
            "1537:1:jump FRAME",
            "1537:2:jump CMD03",
            "1537:3:jump CMD03",
            "1538:0:adv FRAME",
            "1538:1:adv FRAME",
            "1538:2:adv CMD03",
            "1538:3:adv CMD03",
            "1539:0:hold at CMD01",
            "1539:2:adv CMD07",
            "1539:3:adv CMD07",
            "1540:2:adv FRAME",
            "1540:3:adv FRAME",
            "1541:2:adv FRAME",
            "1541:3:adv FRAME",
            "1545:2:adv CMD43",
            "1545:3:adv CMD43",
            "1546:2:adv FRAME",
            "1546:3:adv FRAME",
            "1548:2:adv FRAME",
            "1548:3:adv FRAME",
            "1550:2:adv FRAME",
            "1550:3:adv FRAME",
            "1560:2:adv FRAME",
            "1560:3:adv FRAME",
            "1564:2:adv FRAME",
            "1564:3:adv FRAME",
            "1568:2:adv FRAME",
            "1568:3:adv FRAME",
            "1573:2:adv CMD24",
            "1573:3:adv CMD24",
            "1574:2:adv CMD04",
            "1574:3:adv CMD04",
            "1575:2:adv FRAME",
            "1575:3:adv FRAME",
            "1577:2:adv CMD1a",
            "1577:3:adv CMD1a",
            "1578:2:adv CMD1f",
            "1578:3:adv CMD1f",
            "1579:2:adv FRAME",
            "1579:3:adv FRAME",
            "1581:2:adv FRAME",
            "1581:3:adv FRAME",
            "1585:2:adv FRAME",
            "1585:3:adv FRAME",
            "1588:2:hold at CMD01",
            "1588:3:hold at CMD01",
            "1611:0:adv CMD01",
            "1611:2:adv CMD01",
            "1611:3:adv CMD01",
            "1612:2:adv FRAME",
            "1612:3:adv FRAME",
            "1615:2:adv FRAME",
            "1615:3:adv FRAME",
            "1617:2:adv FRAME",
            "1617:3:adv FRAME",
            "1619:2:adv FRAME",
            "1619:3:adv FRAME",
            "1621:2:adv CMD06",
            "1621:3:adv CMD06",
            "1622:0:jump CMD03",
            "1622:1:jump CMD03",
            "1622:2:adv FRAME",
            "1622:3:adv FRAME",
            "1623:0:adv CMD03",
            "1623:1:adv CMD03",
            "1624:0:adv CMD07",
            "1624:1:adv CMD07",
            "1624:2:adv FRAME",
            "1624:3:adv FRAME",
            "1625:0:adv FRAME",
            "1625:1:adv FRAME",
            "1626:0:adv FRAME",
            "1626:1:adv FRAME",
            "1626:2:adv CMD34",
            "1626:3:adv CMD34",
            "1627:2:adv FRAME",
            "1627:3:adv FRAME",
            "1628:0:adv FRAME",
            "1628:1:adv FRAME",
            "1629:2:adv FRAME",
            "1629:3:adv FRAME",
            "1630:0:adv FRAME",
            "1630:1:adv FRAME",
            "1630:2:jump FRAME",
            "1630:3:jump FRAME",
        )
    }
}
