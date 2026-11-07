package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.event.EventInfo
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gbafe.core.flags.EventFlags
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [EventInfos] on the turn event list of chapter 0 (`PrologueEvents.turnBasedEvents`, ROM
 * 0x089E863C -> 0x089E858C):
 * - ground truth: `ram_trace_first_phase` frames 19300..19340. The game ran `RunPhaseSwitchEvents`
 *   around f19312/f19313 (turn 0 -> turn 1, faction 64 -> 0) and started no event: no
 *   StdEventEngine proc appears and the chapter flags stay unchanged, i.e. `SearchAvailableEvent`
 *   returned NULL. Our search over the real list with the PlaySt and flags of every dumped frame
 *   must return null too (walks four EvCheck02 rejections, then EvCheck00 as terminator).
 * - ROM golden values: script pointers decoded by hand from the raw list words for turn 1 / 2.
 * - RunPhaseSwitchEvents: chapter 0 (`ram_trace_first_phase`) and chapter 7 (defeat-boss goal,
 *   `ram_trace_ch6_phase_start`, turn 0 -> 1) both return 0 with no engine proc; the end event
 *   and chapter-0 skip are checked on the C semantics only (no trace reaches them).
 * StartEventFromInfo, EvCheck01 and EvCheck0C/0D are not exercised by any trace or this list.
 */
class EventInfoRomTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun infos(rom: ByteArray, ps: PlaySt, flags: EventFlags = Fe8EventFlags.create()) =
        EventInfos(
            ScriptMemory { a ->
                val o = a - 0x08000000
                (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
            },
            flags,
            ps,
        )

    private fun turnList(rom: ByteArray): Int {
        val chapters = ChapterData(RomView.ofRom(rom))
        val group = chapters.getChapterEventDataPointer(0)
        assertEquals(0x089E863C, group)
        val o = group - 0x08000000
        return (0 until 4).sumOf { (rom[o + it].toInt() and 0xFF) shl (8 * it) }
    }

    @Test
    @VerifiesPort("SearchAvailableEvent", "EvCheck00_Always", "EvCheck02_TURN")
    fun `turn list search finds no event at the first player phase of the trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = romFile.readBytes()
        val list = turnList(rom)
        assertEquals(0x089E858C, list)
        val run = ref.run("ram_trace_first_phase")
        val names = ProcArrayDump.romNames()
        val ps = PlaySt()
        val flags = Fe8EventFlags.create()
        val bytes = ByteArray(EventTrace.FLAGS_SIZE)
        val search = infos(rom, ps, flags)
        var checked = 0
        var sawTurn1 = false
        for (f in 19300..19340) {
            val ew = run.region("r$f", "ewram")
            val iw = run.region("r$f", "iwram")
            ps.decode(ew, 0x0202BCF0 - GbaMem.EWRAM)
            iw.copyInto(bytes, 0, EventTrace.FLAGS_ADDRESS - GbaMem.IWRAM, 0x30 +
                EventTrace.FLAGS_ADDRESS - GbaMem.IWRAM)
            flags.copyIn(bytes)
            val engine = ProcArrayDump.ofEwram(ew, names).find(EventTrace.STD_EVENT_ENGINE)
            val info = EventInfo().also { it.listScript = list }
            val found = search.searchAvailableEvent(info)
            if (f >= 19313) {
                assertNull(engine, "engine proc at f$f: the game started an event")
                assertNull(found, "f$f turn=${ps.chapterTurnNumber} faction=${ps.faction}")
                assertEquals(0, info.script)
                assertEquals(0, info.flag)
                sawTurn1 = sawTurn1 || ps.chapterTurnNumber == 1
            }
            checked++
        }
        assertTrue(sawTurn1 && checked == 41)
    }

    @Test
    fun `turn list script pointers match the raw ROM words`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = romFile.readBytes()
        val list = turnList(rom)
        val ps = PlaySt()
        val flags = Fe8EventFlags.create()
        val search = infos(rom, ps, flags)
        fun walk(turn: Int, faction: Int): List<Pair<Int, Int>> {
            ps.chapterTurnNumber = turn
            ps.faction = faction
            val out = mutableListOf<Pair<Int, Int>>()
            var r = search.searchAvailableEvent(EventInfo().also { it.listScript = list })
            while (r != null) {
                out += r.script to r.flag
                r = search.searchNextAvailableEvent(r)
            }
            return out
        }
        // Entries: (turn 1, faction 0x80) 0x089EF214; (turn 2, fac 0) 0x089EF23C;
        // (turn 3, fac 0) 0x089EF254; flag 8 (turns 1..0xFF, fac 0x80) 0x089EF12C.
        assertEquals(listOf(0x089EF214 to 0, 0x089EF12C to 8), walk(1, 0x80))
        assertEquals(listOf(0x089EF23C to 0), walk(2, 0))
        assertEquals(listOf(0x089EF254 to 0), walk(3, 0))
        assertEquals(emptyList<Pair<Int, Int>>(), walk(1, 0))
        flags.setFlag(8)
        assertEquals(listOf(0x089EF214 to 0), walk(1, 0x80))
        assertEquals(emptyList<Pair<Int, Int>>(), walk(2, 0x80))
    }

    private class PhaseHooks(
        val chapters: ChapterData,
        val rom: ByteArray,
        var enemyDead: Boolean,
    ) : EventInfoHooks {
        val calls = mutableListOf<String>()

        override fun chapterEventGroup(chapter: Int): Int =
            chapters.getChapterEventDataPointer(chapter)

        override fun goalWindowDataType(chapter: Int): Int =
            rom[chapters.getROMChapterStruct(chapter) - 0x08000000 + 0x8C].toInt() and 0xFF

        override fun areAnyEnemyUnitDead(): Boolean = enemyDead

        override fun callEvent(script: Int, execType: Int) {
            calls += "call $script"
        }

        override fun callEndEvent() {
            calls += "end"
        }

        override fun clearActiveEventRegistry() {
            calls += "clear"
        }
    }

    private fun phaseSwitch(run: String, frames: IntRange, firstAfter: Int, chapter: Int) {
        val rom = romFile.readBytes()
        val data = ref.run(run)
        val names = ProcArrayDump.romNames()
        val ps = PlaySt()
        val flags = Fe8EventFlags.create()
        val hooks = PhaseHooks(ChapterData(RomView.ofRom(rom)), rom, enemyDead = true)
        val search = infos(rom, ps, flags).also { it.hooks = hooks }
        var compared = 0
        for (f in frames) {
            val key = "r%05d".format(f)
            val ew = try { data.region(key, "ewram") } catch (e: java.io.IOException) { continue }
            ps.decode(ew, 0x0202BCF0 - GbaMem.EWRAM)
            if (f < firstAfter) continue
            assertEquals(chapter, ps.chapterIndex)
            val engine = ProcArrayDump.ofEwram(ew, names).find(EventTrace.STD_EVENT_ENGINE)
            // The game started no event after the switch (turn 1, faction 0): the result is 0.
            assertNull(engine, "engine proc at f$f")
            assertEquals(false, search.runPhaseSwitchEvents(), "f$f")
            assertEquals(emptyList<String>(), hooks.calls, "f$f")
            assertEquals(false, flags.checkFlag(3), "f$f EVFLAG_WIN")
            compared++
        }
        assertTrue(compared > 20, "compared $compared")
    }

    @Test
    @VerifiesPort("RunPhaseSwitchEvents")
    fun `phase switch runs no event at the first player phase of chapter 0`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        phaseSwitch("ram_trace_first_phase", 19313..19340, 19313, 0)
    }

    @Test
    @VerifiesPort("RunPhaseSwitchEvents")
    fun `phase switch runs no event at the first player phase of chapter 7`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        phaseSwitch("ram_trace_ch6_phase_start", 1620..1830, 1682, 7)
    }

    @Test
    fun `phase switch end event and skirmish paths follow the C`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = romFile.readBytes()
        val ps = PlaySt()
        val flags = Fe8EventFlags.create()
        val hooks = PhaseHooks(ChapterData(RomView.ofRom(rom)), rom, enemyDead = false)
        val search = infos(rom, ps, flags).also { it.hooks = hooks }
        ps.chapterIndex = 0
        ps.chapterTurnNumber = 1
        ps.faction = 0
        assertEquals(false, search.runPhaseSwitchEvents(), "chapter 0 skips the goal check")
        ps.chapterIndex = 7
        println("goal type ch7 = ${hooks.goalWindowDataType(7)}")
        assertEquals(true, search.runPhaseSwitchEvents(), "no enemy left")
        assertEquals(listOf("end"), hooks.calls)
        assertTrue(flags.checkFlag(3))
    }
}
