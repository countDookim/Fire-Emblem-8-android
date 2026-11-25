package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, BA-i5) of the battle-screen furniture procs `ProcScr_ekrGauge`
 * (banim-ekrgauge.c), `gProc_ekrDispUP` (banim-ekrdispup.c), `ProcScr_efxWeaponIcon`
 * (banim-efxflashobj.c) and `ProcScr_efxHPBarColorChange` (banim-efxflashunit.c) in
 * `ram_trace_prologue_attack`, f1480..1993 (Eirika/Soldier, three hits). There is no
 * function-entry trace; a dump step shows the state after that step's proc run. Proc layouts:
 * `ProcEkrGauge` (ekrbattle.h: battle_init 0x29, valid 0x2A, x 0x32, y 0x3A, unk44, unk4C/50
 * per-side hide flags), `ProcEkrDispUP` (efxbattle.h: sync 0x29, asnyc 0x2A, y 0x3A, unk4C/50),
 * `ProcEfxWeaponIcon` (timer 0x2C, frame 0x44, unk4C, invalid 0x50, eff1 0x54, eff2 0x58) and
 * `ProcEfxHpBarColorChange` (disabled 0x29, timer1 0x2C, timer2 0x2E, frame1 0x44, frame2 0x4C,
 * unk54, unk58). Callbacks are mapped to decomp names through the nm file at runtime; the ROM
 * is not read and nothing from it is embedded. Step numbers are 1-based dump steps. The
 * appearance of gauge and dispup (f1480..1500) is also covered by BattleEntryInventoryTest
 * (BA-i1). See docs/plans/M6C-J-breakdown.md ("BA-i5 result").
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class BattlePanelsInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    /** One furniture proc at one step; [f] holds the fields read by [FIELDS]. */
    private class P(val cb: String, val cur: Int, val addr: Int, val f: Map<Int, Int>) {
        operator fun get(field: Int) = f.getValue(field)
        fun s16(field: Int) = get(field).toShort().toInt()
    }

    private class Step(
        val n: Int,
        val gauge: P?,
        val up: P?,
        val icon: P?,
        val color: P?,
        val hp: List<Int>,
        val hpBak: List<Int>,
        val hitNow: List<Int>,
        val quake: List<Int>,
        val ptrs: List<Int>,
        val misc: List<Int>,
    )

    private var cache: List<Step>? = null

    @AfterAll
    fun release() {
        cache = null
    }

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun load(): List<Step> {
        cache?.let { return it }
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        val nm = ProcArrayDump.nmFile()
        assumeTrue(names.isNotEmpty() && nm != null, "decomp nm file not present")
        val funcs = HashMap<Int, String>()
        nm!!.forEachLine {
            val p = it.split(" ")
            if (p.size >= 4 && p[2] == "T") funcs[p[0].toLong(16).toInt() or 1] = p[3]
        }
        val capture = ref.run(ATTACK)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val out = (FIRST..LAST).map { n ->
            val e = capture.region(byStep.getValue(n), "ewram")
            val all = ProcArrayDump.ofEwram(e, names)
            fun proc(name: String): P? {
                val p = all.find(name) ?: return null
                val cb = when (val c = p.u32(0x0C)) {
                    0 -> "(none)"
                    else -> funcs[c] ?: "%08x".format(c)
                }
                val fields = FIELDS.associateWith { if (it == 0x29 || it == 0x2A) p.u8(it) else p.u16(it) }
                return P(cb, p.scrCur, p.address, fields)
            }
            Step(
                n,
                proc("ProcScr_ekrGauge"), proc("gProc_ekrDispUP"),
                proc("ProcScr_efxWeaponIcon"), proc("ProcScr_efxHPBarColorChange"),
                listOf(s16(e, GAUGE_HP), s16(e, GAUGE_HP + 2)),
                listOf(s16(e, GAUGE_HP_BAK), s16(e, GAUGE_HP_BAK + 2)),
                listOf(u16(e, HIT_NOW), u16(e, HIT_NOW + 2)),
                listOf(s16(e, BG0_QUAKE), s16(e, BG0_QUAKE + 2)),
                listOf(
                    u32(e, GP_GAUGE), u32(e, GP_DISPUP), u32(e, GP_WEAPON_ICON),
                    u32(e, GP_HPBAR_COLOR),
                ),
                listOf(
                    u16(e, DISTANCE), u16(e, WTA_BONUS), u16(e, WTA_BONUS + 2),
                    u16(e, MAX_HP), u16(e, MAX_HP + 2), u16(e, EFFECTIVENESS),
                    u16(e, EFFECTIVENESS + 2),
                ),
            )
        }
        cache = out
        return out
    }

    private fun List<Step>.at(n: Int) = this[n - FIRST]
    private fun List<Step>.range(a: Int, b: Int) = (a..b).map { at(it) }

    /** Run-length list of the steps in [a]..[b] whose [key] differs from the previous step. */
    private fun <T> List<Step>.changes(a: Int, b: Int, key: (Step) -> T): List<Pair<Int, T>> {
        val out = ArrayList<Pair<Int, T>>()
        for (n in a..b) {
            val k = key(at(n))
            if (out.isEmpty() || out.last().second != k) out += n to k
        }
        return out
    }

    @Test
    fun `proc lifetimes callbacks and cursors`() {
        val s = load()
        // ProcScr_ekrGauge and gProc_ekrDispUP: one PROC_REPEAT each (cursor 2); the callback stays
        // set for the whole window (their proc ends are f2001 / f2009, BA-i8).
        for (n in FIRST..1479) {
            assertEquals(null, s.at(n).gauge, "gauge $n")
            assertEquals(null, s.at(n).up, "dispup $n")
        }
        for (n in 1480..LAST) {
            assertEquals(listOf("ekrGaugeMain", 2), s.at(n).gauge!!.let { listOf(it.cb, it.cur) }, "gauge $n")
            assertEquals(listOf("ekrDispUPMain", 2), s.at(n).up!!.let { listOf(it.cb, it.cur) }, "u $n")
        }
        // efxWeaponIcon (NAME, MARK, SET_END_CB, REPEAT: cursor 4) and efxHPBarColorChange (NAME,
        // MARK, REPEAT: cursor 3) are both first seen at f1505 and both gone at f1985.
        assertEquals(null, s.at(1504).icon)
        assertEquals(null, s.at(1504).color)
        for (n in 1505..1984) {
            assertEquals(listOf("sub_8054F10", 4), s.at(n).icon!!.let { listOf(it.cb, it.cur) }, "i $n")
            assertEquals(
                listOf("EfxHPBarColorChangeMain", 3),
                s.at(n).color!!.let { listOf(it.cb, it.cur) },
                "c $n",
            )
        }
        for (n in 1985..LAST) {
            assertEquals(null, s.at(n).icon, "icon $n")
            assertEquals(null, s.at(n).color, "color $n")
        }
        // The gp* globals hold the proc address while the proc exists; the end clears the
        // weapon-icon pointer (EndProcEfxWeaponIcon) but the colour-change one keeps the stale address.
        for (n in 1480..LAST) {
            assertEquals(s.at(n).gauge!!.addr, s.at(n).ptrs[0], "gpProcEkrGauge $n")
            assertEquals(s.at(n).up!!.addr, s.at(n).ptrs[1], "gpProcEkrDispUP $n")
        }
        for (n in 1505..1984) {
            assertEquals(s.at(n).icon!!.addr, s.at(n).ptrs[2], "gpProcEfxWeaponIcon $n")
            assertEquals(s.at(n).color!!.addr, s.at(n).ptrs[3], "gpProcEfxHpBarColorChange $n")
        }
        for (n in 1985..LAST) {
            assertEquals(0, s.at(n).ptrs[2], "gpProcEfxWeaponIcon cleared $n")
            assertEquals(s.at(1984).ptrs[3], s.at(n).ptrs[3], "gpProcEfxHpBarColorChange stale $n")
        }
        // Inputs constant over the window: CLOSE distance, wta bonus (-15, +15), max HP (22, 16),
        // no effectiveness flash (eff1 = eff2 = 0), no hide/invalid/disabled/asnyc flag.
        for (n in 1480..LAST) {
            val st = s.at(n)
            assertEquals(listOf(0, 0xFFF1, 15, 22, 16, 0, 0), st.misc, "inputs $n")
            assertEquals(listOf(0, 0), listOf(st.gauge!![0x2A], st.up!![0x2A]), "valid/asnyc $n")
            assertEquals(
                listOf(0, 0, 0, 0),
                listOf(st.gauge[0x4C], st.gauge[0x50], st.up[0x4C], st.up[0x50]),
                "hide flags $n",
            )
        }
        for (n in 1505..1984) {
            val st = s.at(n)
            assertEquals(listOf(0, 0, 0), listOf(st.icon!![0x50], st.icon[0x54], st.icon[0x58]), "icon $n")
            assertEquals(0, st.color!![0x29], "disabled $n")
        }
    }

    @Test
    fun `gauge and dispup position and sync flags`() {
        val s = load()
        // Slide-in (BA-i1 window, driven by EkrWindowAppearMain / EkrNamewinAppearMain): gauge y
        // starts at 0x39 and falls to 0, dispup y starts at -49 and rises to 0, both 0 at f1495/6.
        assertEquals(
            listOf(0, 0, 57, 57, 57, 57, 56, 53, 50, 46, 41, 34, 27, 19, 10, 0, 0),
            (1480..1496).map { s.at(it).gauge!!.s16(0x3A) },
        )
        assertEquals(
            listOf(0, 0, -49, -49, -49, -49, -49, -48, -46, -43, -39, -35, -30, -24, -17, -9, 0),
            (1480..1496).map { s.at(it).up!!.s16(0x3A) },
        )
        // battle_init (gauge 0x29): 0 until f1495, 1 from the step the window-appear proc ends
        // (f1496) to f1992, 0 again at f1993 (the ending's window-appear, BA-i8).
        assertEquals(
            listOf(1480 to 0, 1496 to 1, 1993 to 0),
            s.changes(1480, 1993) { it.gauge!![0x29] },
        )
        // sync (dispup 0x29): 1 from f1497 (one step later) to f1988; cleared at f1989.
        assertEquals(
            listOf(1480 to 0, 1497 to 1, 1989 to 0),
            s.changes(1480, 1993) { it.up!![0x29] },
        )
        // x (0x32) and y (0x3A) of both procs are equal from f1496 (battle_init set) and non-zero
        // in 24 steps (+-3, three hits: the BG0 shake set through EkrGauge_Setxy323A and the
        // dispup position setter); gEkrBg0QuakeVec itself stays (0, 0) in the whole window.
        val quakeSteps = (1496..1984).filter { s.at(it).gauge!!.s16(0x3A) != 0 }
        assertEquals(24, quakeSteps.size)
        for (n in 1496..1984) {
            val st = s.at(n)
            assertEquals(listOf(0, 0), st.quake, "gEkrBg0QuakeVec $n")
            assertEquals(st.gauge!!.s16(0x32), st.up!!.s16(0x32), "x $n")
            assertEquals(st.gauge.s16(0x3A), st.up.s16(0x3A), "y $n")
        }
        assertEquals(
            listOf(
                1578 to -3, 1583 to 3, 1586 to -3, 1589 to 3, 1591 to -3, 1593 to 3, 1595 to -3,
                1596 to 3, 1660 to -3, 1664 to 3, 1667 to -3, 1670 to 3, 1672 to -3, 1674 to 3,
                1676 to -3, 1677 to 3, 1785 to -3, 1790 to 3, 1793 to -3, 1796 to 3, 1798 to -3,
                1800 to 3, 1802 to -3, 1803 to 3,
            ),
            quakeSteps.map { it to s.at(it).gauge!!.s16(0x3A) },
        )
        assertEquals(true, quakeSteps.all { s.at(it).gauge!!.s16(0x32) == s.at(it).gauge!!.s16(0x3A) })
        // unk44 (gauge OAM priority shift = val * 0x400) is 0 except f1862..1981 (exp-bar window).
        assertEquals(
            listOf(1480 to 0, 1862 to 0x400, 1982 to 0),
            s.changes(1480, 1993) { it.gauge!![0x44] },
        )
    }

    @Test
    fun `gauge hp tracking`() {
        val s = load()
        // NewEkrGauge sets gEkrGaugeHpBak to -1 (f1480..1482); the first ekrGaugeMain run (f1483)
        // copies gEkrGaugeHp into it, and from then on it equals gEkrGaugeHp at every dump step:
        // hp_changed is true in the step the value changes (and at f1483), false otherwise.
        for (n in 1480..1482) assertEquals(listOf(-1, -1), s.at(n).hpBak, "bak $n")
        for (n in 1483..LAST) assertEquals(s.at(n).hp, s.at(n).hpBak, "bak $n")
        assertEquals(listOf(22, 16), s.at(1483).hp)
        // gEkrGaugeHp[0] 22 -> 15 (f1579..1592), [1] 16 -> 7 (f1661..1677), [0] 15 -> 8
        // (f1786..1799): -1 every 2..3 steps while efxHPBar counts down.
        assertEquals(
            listOf(
                1480 to listOf(22, 16),
                1579 to listOf(21, 16), 1581 to listOf(20, 16), 1584 to listOf(19, 16),
                1586 to listOf(18, 16), 1588 to listOf(17, 16), 1590 to listOf(16, 16),
                1592 to listOf(15, 16),
                1661 to listOf(15, 15), 1663 to listOf(15, 14), 1665 to listOf(15, 13),
                1667 to listOf(15, 12), 1669 to listOf(15, 11), 1671 to listOf(15, 10),
                1673 to listOf(15, 9), 1675 to listOf(15, 8), 1677 to listOf(15, 7),
                1786 to listOf(14, 7), 1788 to listOf(13, 7), 1791 to listOf(12, 7),
                1793 to listOf(11, 7), 1795 to listOf(10, 7), 1797 to listOf(9, 7),
                1799 to listOf(8, 7),
            ),
            s.changes(1480, LAST) { it.hp },
        )
        // gEkrHitNow: side 0 in f1577..1608, side 1 in f1659..1689, side 0 in f1784..1815 (the
        // gauge's CheckEkrHitNow rotate-scale arm); the hit side's value is the one that drops.
        assertEquals(
            listOf(
                1480 to listOf(0, 0), 1577 to listOf(1, 0), 1609 to listOf(0, 0),
                1659 to listOf(0, 1), 1690 to listOf(0, 0), 1784 to listOf(1, 0),
                1816 to listOf(0, 0),
            ),
            s.changes(1480, LAST) { it.hitNow },
        )
        // Branches never taken by ekrGaugeMain here: HP > 0x50 (purple), max HP > 0x28 (second
        // bar row), hide flags unk4C/unk50 (both sides always drawn), valid (never disabled).
        // The battle_init == 0 arm (BG0 panel tilemap redraw) runs f1480..1495 only.
        for (n in 1480..LAST) {
            assertEquals(true, s.at(n).hp.all { it in 1..0x28 }, "hp range $n")
            assertEquals(true, s.at(n).misc.slice(3..4).all { it <= 0x28 }, "max hp $n")
        }
    }

    /** Applies EfxAdvanceFrameLut's observable effect to a (timer, frame) pair: no value table. */
    private fun stepOf(prevTimer: Int, prevFrame: Int, timer: Int, frame: Int): String = when {
        prevTimer != 0 && timer == prevTimer - 1 && frame == prevFrame -> "wait"
        prevTimer == 0 && frame == prevFrame + 1 -> "next"
        prevTimer == 0 && frame == 1 && prevFrame > 1 -> "wrap"
        else -> "other"
    }

    @Test
    fun `weapon icon and hp bar colour lut stepping`() {
        val s = load()
        // First seen at f1505 with all counters 0 (started, not yet run), then one call per step
        // (EfxAdvanceFrameLut: timer 0 -> read the next entry, set timer = duration - 1, frame++;
        // otherwise timer--), except in three whole-screen no-run steps where none of the four
        // procs changes.
        val noRun = (1506..1984).filter {
            val a = s.at(it - 1)
            val b = s.at(it)
            a.icon!!.f == b.icon!!.f && a.color!!.f == b.color!!.f
        }
        assertEquals(listOf(1582, 1752, 1789), noRun)
        for (n in noRun) {
            assertEquals(s.at(n - 1).hp, s.at(n).hp)
            assertEquals(s.at(n - 1).gauge!!.f, s.at(n).gauge!!.f)
        }
        assertEquals(listOf(0, 0), listOf(s.at(1505).icon!![0x2C], s.at(1505).icon!![0x44]))
        assertEquals(
            listOf(0, 0, 0, 0, 0, 0),
            listOf(0x2C, 0x2E, 0x44, 0x4C, 0x54, 0x58).map { s.at(1505).color!![it] },
        )
        val kinds = HashMap<String, Int>()
        val iconWraps = ArrayList<Int>()
        val c1Wraps = ArrayList<Int>()
        val c2Wraps = ArrayList<Int>()
        for (n in 1506..1984) {
            if (n in noRun) continue
            val a = s.at(n - 1)
            val b = s.at(n)
            val ik = stepOf(a.icon!![0x2C], a.icon[0x44], b.icon!![0x2C], b.icon[0x44])
            val c1 = stepOf(a.color!![0x2C], a.color[0x44], b.color!![0x2C], b.color[0x44])
            val c2 = stepOf(a.color[0x2E], a.color[0x4C], b.color[0x2E], b.color[0x4C])
            for (k in listOf(ik, c1, c2)) {
                assertTrue(k != "other", "step $n")
                kinds.merge(k, 1, Int::plus)
            }
            if (ik == "wrap") iconWraps += n
            if (c1 == "wrap") c1Wraps += n
            if (c2 == "wrap") c2Wraps += n
            // the value (unk4C / unk54 / unk58) changes only when the frame advances
            if (ik == "wait") assertEquals(a.icon[0x4C], b.icon[0x4C], "icon value $n")
            if (c1 == "wait") assertEquals(a.color[0x54], b.color[0x54], "color value1 $n")
            if (c2 == "wait") assertEquals(a.color[0x58], b.color[0x58], "color value2 $n")
        }
        // Cycle lengths in steps: icon 32, colour 1 36, colour 2 80 (a no-run step inside the
        // cycle adds one: 1578..1615, 1731..1764, 1764..1797).
        assertEquals(
            listOf(1538, 1570, 1603, 1635, 1667, 1699, 1731, 1764, 1797, 1829, 1861, 1893, 1925, 1957),
            iconWraps,
        )
        assertEquals(
            listOf(1542, 1578, 1615, 1651, 1687, 1723, 1760, 1797, 1833, 1869, 1905, 1941, 1977),
            c1Wraps,
        )
        assertEquals(listOf(1587, 1667, 1747, 1829, 1909), c2Wraps)
        // Value ranges: icon unk4C 0..16, colour unk54 0..5 and unk58 0..4, both HP halves
        // <= 80 so the EfxDecodeSplitedPalette arm runs for both sides (purple arm never).
        val icons = s.range(1505, 1984).map { it.icon!![0x4C] }
        val c54 = s.range(1505, 1984).map { it.color!![0x54] }
        val c58 = s.range(1505, 1984).map { it.color!![0x58] }
        assertEquals(listOf(0, 16), listOf(icons.min(), icons.max()))
        assertEquals(listOf(0, 5), listOf(c54.min(), c54.max()))
        assertEquals(listOf(0, 4), listOf(c58.min(), c58.max()))
        // Last step with both procs: f1984 (icon timer 4 / frame 11, colour frames 4 / 8); both
        // are removed in f1985 by the round-end code, not by their own scripts (callback set).
        assertEquals(listOf(4, 11), listOf(s.at(1984).icon!![0x2C], s.at(1984).icon!![0x44]))
        assertEquals(listOf(4, 8), listOf(s.at(1984).color!![0x44], s.at(1984).color!![0x4C]))
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val EWRAM = 0x02000000
        const val FIRST = 1478
        const val LAST = 1993
        const val GP_GAUGE = 0x02000068
        const val GP_DISPUP = 0x0200006C
        const val BG0_QUAKE = 0x02000038
        const val GP_WEAPON_ICON = 0x02017774
        const val GP_HPBAR_COLOR = 0x0201777C
        const val HIT_NOW = 0x02017780
        const val DISTANCE = 0x0203E120
        const val GAUGE_HP = 0x0203E1AC
        const val MAX_HP = 0x0203E1B0
        const val GAUGE_HP_BAK = 0x0203E1B4
        const val WTA_BONUS = 0x0203E1D4
        const val EFFECTIVENESS = 0x0203E1D8
        val FIELDS = listOf(0x29, 0x2A, 0x2C, 0x2E, 0x32, 0x3A, 0x44, 0x4C, 0x50, 0x54, 0x58)
    }
}
