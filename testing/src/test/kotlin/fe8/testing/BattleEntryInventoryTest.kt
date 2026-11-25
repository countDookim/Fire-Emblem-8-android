package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Inventory (not a port claim, BA-i1) of the map-to-battle entry procs in the two reference
 * windows `ram_trace_prologue_attack` f1463..1500 (Eirika/Soldier) and `ram_trace_prologue_battle`
 * f14429..14470 (scripted Seth/Valter): `ProcScr_ekrBattleStarting` (banim-ekrbattlestarting.c),
 * `gProc_ekrBattleDeamon` (banim-ekrbattle.c), `ProcScr_ekrUnitKakudai`, `ProcScr_EkrBaseKaiten`,
 * `ProcScr_ekrWindowAppear`, `ProcScr_ekrNamewinAppear`, `ProcScr_ekrBaseAppear`
 * (banim-ekrbattleintro.c) and `ProcScr_ScriptBattleDeamon` (eventscr.c). Method as
 * [BattleEndingInventoryTest]: there is no function-entry trace, a dump step shows the state after
 * that step's proc run; the callback is `repeatFunc` (0x0C) mapped to a decomp name through the nm
 * file at runtime, the cursor is `ProcArrayDump.Proc.scrCur` (a NAME command is command 0, so the
 * Nth repeat command shows as cursor N + 1 where a NAME exists). Timer 0x2C, terminator 0x2E,
 * x1 0x32, x2 0x34, y1 0x3A, y2 0x3C, type 0x44. The ROM is not read and nothing from it is
 * embedded. See docs/plans/M6C-J-breakdown.md ("BA-i1 result").
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class BattleEntryInventoryTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Frame(
        val n: Int,
        val ram: ByteArray,
        val procs: Map<String, Proc>,
        val all: Set<String>,
        val byAddress: Map<Int, String>,
    )

    private class Proc(
        val slot: Int,
        val cb: String,
        val cur: Int,
        val timer: Int,
        val term: Int,
        val box: List<Int>,
        val f: (Int) -> Int,
    )

    private var attack: List<Frame>? = null
    private var script: List<Frame>? = null

    @AfterAll
    fun release() {
        attack = null
        script = null
    }

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun load(run: String, range: IntRange): List<Frame> {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        val nm = ProcArrayDump.nmFile()
        assumeTrue(names.isNotEmpty() && nm != null, "decomp nm file not present")
        val funcs = HashMap<Int, String>()
        nm!!.forEachLine {
            val p = it.split(" ")
            if (p.size >= 4 && p[2] == "T") funcs[p[0].toLong(16).toInt() or 1] = p[3]
        }
        val capture = ref.run(run)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        return range.map { n ->
            val e = capture.region(byStep.getValue(n), "ewram")
            val dump = ProcArrayDump.ofEwram(e, names)
            val procs = dump.live
                .filter { it.name in WATCH }
                .associate {
                    it.name!! to Proc(
                        it.slot,
                        it.u32(0x0C).let { cb -> if (cb == 0) "(none)" else funcs[cb] ?: "%08x".format(cb) },
                        it.scrCur, it.u16(0x2C), it.u16(0x2E),
                        listOf(0x32, 0x34, 0x3A, 0x3C).map { o -> it.u16(o) },
                        { o -> it.u32(o) },
                    )
                }
            Frame(
                n, e, procs, dump.live.map { it.name ?: "?" }.toSet(),
                dump.live.associate { it.address to (it.name ?: "?") },
            )
        }
    }

    private fun attack() = attack ?: load(ATTACK, 1463..1500).also { attack = it }
    private fun script() = script ?: load(SCRIPT, 14429..14469).also { script = it }

    private fun List<Frame>.at(n: Int) = first { it.n == n }
    private fun List<Frame>.p(name: String, n: Int) = at(n).procs.getValue(name)
    private fun List<Frame>.firstSeen(name: String) = first { name in it.procs }.n
    private fun List<Frame>.goneAt(name: String) =
        first { name !in it.procs && it.n > firstSeen(name) }.n

    /** `first..last:callback@cursor:steps` for the steps where [name] exists. */
    private fun List<Frame>.runs(name: String): List<String> {
        val f = filter { name in it.procs }
        val out = ArrayList<String>()
        var i = 0
        while (i < f.size) {
            var j = i
            val a = f[i].procs.getValue(name)
            while (j + 1 < f.size && f[j + 1].procs.getValue(name).let { it.cb == a.cb && it.cur == a.cur }) j++
            out += "${f[i].n}..${f[j].n}:${a.cb}@${a.cur}:${j - i + 1}"
            i = j + 1
        }
        return out
    }

    private fun List<Frame>.timers(name: String, range: IntRange) = range.map { p(name, it).timer }

    private fun List<Frame>.diff(n: Int): Pair<Set<String>, Set<String>> {
        val a = at(n - 1).all
        val b = at(n).all
        return (b - a) to (a - b)
    }

    private fun List<Frame>.u16At(n: Int, a: Int) = u16(at(n).ram, a)

    @Test
    fun `ekrBattleStarting callbacks, cursor and timer`() {
        val a = attack()
        // The proc is created and ekrBaStart_InitScreen has already run in the creating step.
        assertEquals(
            listOf(
                "1464..1464:(none)@2:1",
                "1465..1478:ekrBaStart_SreenFailIn@3:14",
                "1479..1479:(none)@3:1",
                "1480..1481:ekrBaStart_InitBattleScreen@4:2",
                "1482..1482:(none)@4:1",
                "1483..1495:ekrBaStart_ExecEkrBattle6C@5:13",
            ),
            a.runs("ProcScr_ekrBattleStarting"),
        )
        // timer 0, SreenFailIn +1 per step to the terminator 15, InitBattleScreen resets to 0;
        // ExecEkrBattle6C shows 1 for three steps (no proc run), then +1 per step to 11.
        assertEquals(
            listOf(0) + (1..15) + listOf(15, 15, 0, 1, 1, 1) + (2..11),
            a.timers("ProcScr_ekrBattleStarting", 1464..1495),
        )
        assertEquals(List(32) { 15 }, (1464..1495).map { a.p("ProcScr_ekrBattleStarting", it).term })
        // 12th ExecEkrBattle6C call (++timer > 0xB, gBanimBG == 0): NewEkrBattle + Proc_End in
        // the same step, so no later script command (8055FE8..8056078) is ever read.
        assertEquals(1496, a.goneAt("ProcScr_ekrBattleStarting"))
        assertEquals(1496, a.firstSeen("gProc_ekrBattle"))
        assertEquals("ekrBattle_Init", a.p("gProc_ekrBattle", 1496).cb)
        assertEquals(0, a.u16At(1495, BANIM_BG))

        val s = script()
        // Here the proc is read one step after creation (its first dump still shows InitScreen set).
        assertEquals(
            listOf(
                "14431..14431:ekrBaStart_InitScreen@2:1",
                "14432..14432:(none)@2:1",
                "14433..14446:ekrBaStart_SreenFailIn@3:14",
                "14447..14447:(none)@3:1",
                "14448..14450:ekrBaStart_InitBattleScreen@4:3",
                "14451..14451:(none)@4:1",
                "14452..14466:ekrBaStart_ExecEkrBattle6C@5:15",
            ),
            s.runs("ProcScr_ekrBattleStarting"),
        )
        assertEquals(
            listOf(0) + (1..15) + listOf(15, 15, 15, 0, 1, 1, 1, 1, 1) + (2..11),
            s.timers("ProcScr_ekrBattleStarting", 14432..14466),
        )
        assertEquals(14467, s.goneAt("ProcScr_ekrBattleStarting"))
        assertEquals(14467, s.firstSeen("gProc_ekrBattle"))
        assertEquals(0, s.u16At(14466, BANIM_BG))
    }

    @Test
    fun `intro furniture procs callbacks and timers`() {
        val a = attack()
        val tail = listOf("1496..1496:(none)@2:1")
        val window = listOf("1482..1495:EkrWindowAppearMain@2:14") + tail
        assertEquals(window, a.runs("ProcScr_ekrWindowAppear"))
        assertEquals(
            listOf("1482..1495:EkrBaseKaitenMain@2:14") + tail, a.runs("ProcScr_EkrBaseKaiten"),
        )
        assertEquals(
            listOf("1482..1495:EkrBaseAppearMain@2:14") + tail, a.runs("ProcScr_ekrBaseAppear"),
        )
        assertEquals(
            listOf(
                "1482..1484:EkrNamewinAppearDelay@2:3",
                "1485..1485:(none)@2:1",
                "1486..1496:EkrNamewinAppearMain@3:11",
                "1497..1498:(none)@3:2",
            ),
            a.runs("ProcScr_ekrNamewinAppear"),
        )
        assertEquals(
            listOf(
                "1482..1484:UnitKakudaiPrepareAnimScript@2:3",
                "1485..1485:(none)@2:1",
                "1486..1496:UnitKakudaiMain@3:11",
                "1497..1498:(none)@3:2",
                "1499..1499:(none)@4:1",
            ),
            a.runs("ProcScr_ekrUnitKakudai"),
        )
        // Timers: 0 for the three steps after the start (the procs did not run), then +1 per step
        // to the terminator 11; the 12th call (1496) takes the `timer >= terminator` arm.
        val ramp = listOf(0, 0, 0) + (1..11) + listOf(11)
        for (nm in listOf("ProcScr_ekrWindowAppear", "ProcScr_EkrBaseKaiten", "ProcScr_ekrBaseAppear")) {
            assertEquals(ramp, a.timers(nm, 1482..1496), nm)
            assertEquals(1497, a.goneAt(nm), nm)
        }
        assertEquals(
            listOf(0, 0, 0, 0) + (1..11) + listOf(11, 11), a.timers("ProcScr_ekrNamewinAppear", 1482..1498),
        )
        assertEquals(1499, a.goneAt("ProcScr_ekrNamewinAppear"))
        // UnitKakudaiPrepareAnimScript ran in 1485 (timer 0, x1..y2 set); Main counts 1..11, its
        // 12th call breaks (1497), EndNop is read in 1499 (cursor 4), PROC_END in 1500.
        assertEquals(listOf(0) + (1..11) + listOf(11, 11, 11), a.timers("ProcScr_ekrUnitKakudai", 1485..1499))
        assertEquals(1500, a.goneAt("ProcScr_ekrUnitKakudai"))
        assertEquals(List(37) { "nullsub_35@3" }, (1464..1500).map { a.p("gProc_ekrBattleDeamon", it).let { p -> "${p.cb}@${p.cur}" } })
        assertEquals(false, a.any { "gProc_ekrBattleDeamon" !in it.procs && it.n >= 1464 })

        val s = script()
        val st = listOf("14467..14467:(none)@2:1")
        assertEquals(listOf("14451..14466:EkrWindowAppearMain@2:16") + st, s.runs("ProcScr_ekrWindowAppear"))
        assertEquals(listOf("14451..14466:EkrBaseKaitenMain@2:16") + st, s.runs("ProcScr_EkrBaseKaiten"))
        assertEquals(listOf("14451..14466:EkrBaseAppearMain@2:16") + st, s.runs("ProcScr_ekrBaseAppear"))
        assertEquals(
            listOf(
                "14451..14455:EkrNamewinAppearDelay@2:5",
                "14456..14456:(none)@2:1",
                "14457..14467:EkrNamewinAppearMain@3:11",
                "14468..14469:(none)@3:2",
            ),
            s.runs("ProcScr_ekrNamewinAppear"),
        )
        assertEquals(
            listOf(
                "14450..14455:UnitKakudaiPrepareAnimScript@2:6",
                "14456..14456:(none)@2:1",
                "14457..14467:UnitKakudaiMain@3:11",
                "14468..14469:(none)@3:2",
            ),
            s.runs("ProcScr_ekrUnitKakudai"),
        )
        val sramp = List(5) { 0 } + (1..11) + listOf(11)
        for (nm in listOf("ProcScr_ekrWindowAppear", "ProcScr_EkrBaseKaiten", "ProcScr_ekrBaseAppear")) {
            assertEquals(sramp, s.timers(nm, 14451..14467), nm)
            assertEquals(14468, s.goneAt(nm), nm)
        }
        assertEquals(List(6) { 0 } + (1..11) + listOf(11, 11), s.timers("ProcScr_ekrNamewinAppear", 14451..14469))
        assertEquals(listOf(0) + (1..11) + listOf(11, 11), s.timers("ProcScr_ekrUnitKakudai", 14456..14469))
        // ScriptBattleDeamon (eventscr.c): created the step before ekrBattleStarting and
        // ekrBattleDeamon, one repeat command (cursor 1, no NAME), never ends in the window.
        assertEquals(14430, s.firstSeen("ProcScr_ScriptBattleDeamon"))
        assertEquals(
            List(40) { "ScriptBattleDeamon@1" },
            (14430..14469).map { s.p("ProcScr_ScriptBattleDeamon", it).let { p -> "${p.cb}@${p.cur}" } },
        )
        assertEquals(14431, s.firstSeen("gProc_ekrBattleDeamon"))
        assertEquals(List(39) { "nullsub_35@3" }, (14431..14469).map { s.p("gProc_ekrBattleDeamon", it).let { p -> "${p.cb}@${p.cur}" } })
    }

    @Test
    fun `entry state, creation arguments and flags`() {
        val a = attack()
        val s = script()
        for ((fr, first, bm, k, kakudai) in listOf(
            Entry(a, 1464, listOf(8, 6, 8, 5), 1482, 1485),
            Entry(s, 14431, listOf(8, 5, 7, 5), 14451, 14456),
        )) {
            val e = fr.at(first).ram
            // Preconditions of the normal path: no debug mode, no banim BG (so no map fade-out
            // arms), CLOSE distance, both sides valid, init position 1.
            assertEquals(0, u16(e, DEBUG_MODE))
            assertEquals(0, u16(e, BANIM_BG))
            assertEquals(0, u16(e, DISTANCE_TYPE))
            assertEquals(listOf(1, 1), listOf(u16(e, BANIM_VALID), u16(e, BANIM_VALID + 2)))
            assertEquals(1, u32(e, INIT_POS_REAL))
            assertEquals(bm, (0..3).map { u16(e, BM_LOCATION + 2 * it) })
            assertEquals(0, u32(fr.at(first - 1).ram, DEAMON_ACTIVE))
            assertEquals(1, u32(e, DEAMON_ACTIVE))
            val deamon = fr.p("gProc_ekrBattleDeamon", first)
            assertEquals(ProcArrayDump.ARRAY_ADDRESS + deamon.slot * ProcArrayDump.PROC_SIZE, u32(e, DEAMON_PTR))
            // ekrBaStart_InitScreen: x1 = x2, y1 = y2 from gEkrBmLocation, terminator 0xF.
            val st = fr.p("ProcScr_ekrBattleStarting", first + 1)
            val x = (bm[0] + bm[2]) * 8 + 8
            val y = (bm[1] + bm[3]) * 8 + 8
            assertEquals(listOf(x, x, y, y), st.box)
            assertEquals(15, st.term)
            // NewEkrBaseKaiten(0): box (x1, x2, y1, y2) = (x, 0x78, y, 0x68), terminator 0xB, type 0.
            val kt = fr.p("ProcScr_EkrBaseKaiten", k)
            assertEquals(listOf(x, 0x78, y, 0x68), kt.box)
            assertEquals(11, kt.term)
            assertEquals(0, kt.f(0x44))
            // UnitKakudaiPrepareAnimScript: box = gEkrBmLocation * 16 + 8, both sides valid.
            val kk = fr.p("ProcScr_ekrUnitKakudai", kakudai)
            assertEquals(listOf(bm[0] * 16 + 8, bm[2] * 16 + 8, bm[1] * 16 + 8, bm[3] * 16 + 8), kk.box)
            assertEquals(11, kk.term)
            assertEquals(0, kk.f(0x44))
            assertEquals(listOf(1, 1), listOf(kk.f(0x4C), kk.f(0x50)))
            assertEquals(listOf(92, 148), listOf(kk.f(0x36) and 0xFFFF, kk.f(0x36) ushr 16))
            // NewEkrWindowAppear(0, 0xB): type 0, ymax 0x39; NewEkrNamewinAppear(0, 0xB, 0):
            // delay 0, ymax_name -49; NewEkrBaseAppear(0, 0xB).
            val w = fr.p("ProcScr_ekrWindowAppear", k)
            assertEquals(listOf(11, 0x39, 0), listOf(w.term, w.f(0x30) and 0xFFFF, w.f(0x44)))
            val nw = fr.p("ProcScr_ekrNamewinAppear", k)
            assertEquals(listOf(11, 0, 0, -49), listOf(nw.term, nw.f(0x30) and 0xFFFF, nw.f(0x44), nw.f(0x48)))
            val ba = fr.p("ProcScr_ekrBaseAppear", k)
            assertEquals(listOf(11, 0), listOf(ba.term, ba.f(0x44)))
        }
        // The exist flags: set by the New* calls, cleared by the 12th (terminator) call; the
        // namewin flag one step later because its Delay command ran first.
        for (n in 1463..1500) {
            val e = a.at(n).ram
            assertEquals(if (n in 1482..1495) 1 else 0, u32(e, WINDOW_EXIST), "win $n")
            assertEquals(if (n in 1482..1495) 1 else 0, u32(e, BASE_EXIST), "base $n")
            assertEquals(if (n in 1482..1496) 1 else 0, u32(e, NAMEWIN_EXIST), "name $n")
            assertEquals(0, u32(e, BATTLE_END_FLAG), "end $n")
        }
        for (n in 14429..14469) {
            val e = s.at(n).ram
            val cleared = n < 14451 || n >= 14467
            assertEquals(if (cleared) 0 else 1, u32(e, WINDOW_EXIST), "win $n")
            assertEquals(if (cleared) 0 else 1, u32(e, BASE_EXIST), "base $n")
            assertEquals(if (n in 14451..14467) 1 else 0, u32(e, NAMEWIN_EXIST), "name $n")
        }
        // ScriptBattleDeamon: lock = game lock at creation (1), parent and evtproc = the event
        // engine proc; the lock check never matches inside the window.
        val sd = s.p("ProcScr_ScriptBattleDeamon", 14430)
        assertEquals(1, sd.f(0x64) and 0xFFFF)
        assertEquals(sd.f(0x14), sd.f(0x58))
        assertEquals("ProcScr_StdEventEngine", s.at(14430).byAddress[sd.f(0x58)])
    }

    @Test
    fun `proc set changes`() {
        val a = attack()
        val added = mapOf(
            1464 to setOf("ProcScr_ekrBattleStarting", "sProcScr_CombatAction", "gProc_ekrBattleDeamon"),
            1480 to setOf("ProcScr_ekrGauge", "gProc_ekrDispUP"),
            1482 to setOf(
                "ProcScr_ekrNamewinAppear", "ProcScr_ekrWindowAppear", "ProcScr_EkrBaseKaiten",
                "ProcScr_ekrUnitKakudai", "ProcScr_ekrBaseAppear",
            ),
            1496 to setOf("gMusicProc3Script", "gProc_ekrBattle"),
        )
        val removed = mapOf(
            1479 to setOf("ProcScr_Mu"),
            1496 to setOf("ProcScr_ekrBattleStarting"),
            1497 to setOf("ProcScr_ekrWindowAppear", "ProcScr_EkrBaseKaiten", "ProcScr_ekrBaseAppear"),
            1499 to setOf("ProcScr_ekrNamewinAppear"),
            1500 to setOf("ProcScr_ekrUnitKakudai"),
        )
        for (n in 1464..1500) {
            assertEquals(added[n].orEmpty() to removed[n].orEmpty(), a.diff(n), "attack $n")
        }
        val s = script()
        val sAdded = mapOf(
            14430 to setOf("ProcScr_Mu", "ProcScr_ScriptBattleDeamon"),
            14431 to setOf("ProcScr_ekrBattleStarting", "gProc_ekrBattleDeamon"),
            14448 to setOf("ProcScr_ekrGauge", "gProc_ekrDispUP"),
            14450 to setOf("ProcScr_ekrUnitKakudai"),
            14451 to setOf(
                "ProcScr_EkrBaseKaiten", "ProcScr_ekrNamewinAppear", "ProcScr_ekrWindowAppear",
                "ProcScr_ekrBaseAppear",
            ),
            14467 to setOf("gProc_ekrBattle", "gMusicProc3Script"),
        )
        val sRemoved = mapOf(
            14447 to setOf("ProcScr_Mu"),
            14467 to setOf("ProcScr_ekrBattleStarting"),
            14468 to setOf("ProcScr_EkrBaseKaiten", "ProcScr_ekrWindowAppear", "ProcScr_ekrBaseAppear"),
        )
        for (n in 14430..14469) {
            assertEquals(sAdded[n].orEmpty() to sRemoved[n].orEmpty(), s.diff(n), "script $n")
        }
    }

    private data class Entry(val fr: List<Frame>, val first: Int, val bm: List<Int>, val k: Int, val kakudai: Int)

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val SCRIPT = "ram_trace_prologue_battle"
        const val EWRAM = 0x02000000
        const val DEBUG_MODE = 0x0203E0FC
        const val BANIM_BG = 0x0203E0FE
        const val BANIM_VALID = 0x0203E104
        const val DISTANCE_TYPE = 0x0203E120
        const val BM_LOCATION = 0x0203E122
        const val INIT_POS_REAL = 0x02017744
        const val DEAMON_ACTIVE = 0x0203E0F4
        const val DEAMON_PTR = 0x0203E0F8
        const val WINDOW_EXIST = 0x0201FACC
        const val NAMEWIN_EXIST = 0x0201FAD0
        const val BASE_EXIST = 0x0201FAD4
        const val BATTLE_END_FLAG = 0x02017724
        val WATCH = setOf(
            "ProcScr_ekrBattleStarting", "ProcScr_ekrUnitKakudai", "ProcScr_ekrBaseAppear",
            "ProcScr_EkrBaseKaiten", "ProcScr_ekrWindowAppear", "gProc_ekrBattleDeamon",
            "ProcScr_ekrNamewinAppear", "ProcScr_ScriptBattleDeamon", "gProc_ekrBattle",
        )
    }
}
