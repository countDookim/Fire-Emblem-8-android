package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ItemInfo
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.ActiveEventRegistry
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The after-move checks of `ram_trace_prologue_clear` (EP-35), turn 3, where Seth attacks the
 * boss: `StartDestSelectedEvent` at f4130, `StartAfterUnitMovedEvent`, `CheckForCharacterEvents`
 * and `GetAvailableTileEventCommand` at f4181, `CheckBattleForecastTutorialEvent` at f4240, and
 * the strings of the weapon list (f4211) and of the forecast (f4241).
 *
 * Events: no event starts in the window (the Prologue's character, location, destination and
 * after-move lists hold only the end entry, and no tutorial event is queued). Each check is
 * seeded from the dump of its frame (play state, flags, event registry) and must report nothing,
 * call no event and leave the registry, the flags and the tutorial queue as the dump has them.
 * The result equals that of a check that never finds anything, so this verifies the list reads
 * only as far as a wrong list would start an event.
 *
 * Strings: `GetItemName` (`GetStringFromIndex`, `StrInsertTact`, `CopyString`) of an item the
 * unit holds reproduces `sMsgString.buffer4` and `buffer5` of the dump byte for byte.
 */
class AfterMoveEventsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    /** The event lists the checks read, seeded from the dump of [step]. */
    private inner class Seed(step: Int) {
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val playSt = PlaySt().also { it.decode(run.watch("gplayst").step(step), 0) }
        val flags = Fe8EventFlags.create().also { it.copyIn(run.watch("flags").step(step)) }
        val calls = ArrayList<Pair<Int, Int>>()
        val infos = EventInfos(ScriptMemory { a -> rom.u16(a) }, flags, playSt)
        val dump = run.dumps.entries.single { it.value == step }.key

        init {
            val chapters = ChapterData(rom)
            infos.hooks = object : EventInfoHooks {
                override fun callEvent(script: Int, execType: Int) {
                    calls += script to execType
                }

                override fun chapterEventGroup(chapter: Int): Int =
                    chapters.getChapterEventDataPointer(chapter)
            }
            infos.registry.load(run.region(dump, "iwram"), REGISTRY - IWRAM)
        }

        fun assertUnchanged(step: Int) {
            assertEquals(emptyList<Pair<Int, Int>>(), calls, "CallEvent at f$step")
            val got = ByteArray(ActiveEventRegistry.BYTES)
            infos.registry.store(got, 0)
            val want = run.region(dump, "iwram")
                .copyOfRange(REGISTRY - IWRAM, REGISTRY - IWRAM + ActiveEventRegistry.BYTES)
            assertEquals(want.toList(), got.toList(), "gActiveEventRegistry at f$step")
            val flagBytes = ByteArray(0x30).also { flags.copyOut(it) }
            assertEquals(run.watch("flags").step(step).toList(), flagBytes.toList(), "flags")
            val want2 = PlaySt().also { it.decode(run.watch("gplayst").step(step), 0) }
            assertEquals(want2.tutorialCounter, playSt.tutorialCounter, "tutorial_counter")
            assertEquals(want2.tutorialExecType, playSt.tutorialExecType, "tutorial_exec_type")
        }
    }

    private fun available() = assumeTrue(ref.available && romFile.isFile, "reference data missing")

    @Test
    fun `the Prologue lists searched in the window hold no entry and no tutorial is queued`() {
        available()
        val s = Seed(4130)
        assertEquals(0, s.playSt.chapterIndex, "the Prologue")
        assertEquals(0, s.playSt.tutorialCounter, "no tutorial event queued")
    }

    @Test
    fun `StartDestSelectedEvent at f4130 starts nothing`() {
        available()
        val s = Seed(4130)
        assertFalse(s.infos.startDestSelectedEvent())
        s.assertUnchanged(4130)
    }

    @Test
    fun `the checks at f4181 find nothing`() {
        available()
        val s = Seed(4181)
        assertFalse(s.infos.startAfterUnitMovedEvent())
        assertFalse(s.infos.checkForCharacterEvents(1, 2))
        assertEquals(0, s.infos.getAvailableTileEventCommand(8, 6))
        s.assertUnchanged(4181)
    }

    @Test
    fun `CheckBattleForecastTutorialEvent at f4240 is false`() {
        available()
        val s = Seed(4240)
        assertFalse(s.infos.checkBattleForecastTutorialEvent())
        s.assertUnchanged(4240)
    }

    /** The item words every unit of the dump holds (player, enemy, NPC and phantom units). */
    private fun heldItems(ewram: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        for (u in 0 until UNIT_COUNT) {
            val o = UnitTable.BLUE_ADDRESS - UnitTable.EWRAM_BASE + u * UnitTable.UNIT_SIZE
            if (ewram[o].toInt() == 0 && ewram[o + 1].toInt() == 0 &&
                ewram[o + 2].toInt() == 0 && ewram[o + 3].toInt() == 0
            ) {
                continue
            }
            for (slot in 0 until 5) {
                val w = (ewram[o + 0x1E + 2 * slot].toInt() and 0xFF) or
                    ((ewram[o + 0x1F + 2 * slot].toInt() and 0xFF) shl 8)
                if (w != 0) out += w
            }
        }
        return out
    }

    private fun messages(rom: ByteArray): MsgStrings {
        val root = u32(rom, 0x0815D488 - BASE) - BASE
        val huff = MsgHuffman(rom, 0x0815A72C - BASE, root)
        return MsgStrings(rom, huff, 0x0815D48C - BASE)
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun upToTerminator(b: ByteArray, o: Int): List<Byte> {
        var e = o
        while (b[e].toInt() != 0) e++
        return b.copyOfRange(o, e + 1).toList()
    }

    @Test
    @VerifiesPort("StrInsertTact", "CopyString")
    fun `an item name through StrInsertTact reproduces buffer4 and buffer5 of f4211 and f4250`() {
        available()
        val run = ref.run(RUN)
        val romBytes = romFile.readBytes()
        val items = ItemInfo(RomView.ofRom(romBytes))
        for (step in listOf(4211, 4250)) {
            val dump = run.dumps.entries.single { it.value == step }.key
            val ewram = run.region(dump, "ewram")
            val want4 = upToTerminator(ewram, BUFFER4)
            val want5 = upToTerminator(ewram, BUFFER5)
            val hits = heldItems(ewram).filter { item ->
                val msgs = messages(romBytes)
                val name = items.getItemName(item, msgs)
                msgs.buffer4.toList().subList(0, want4.size) == want4 &&
                    msgs.buffer5.toList().subList(0, want5.size) == want5 &&
                    name.toList() == want5
            }
            assertEquals(true, hits.isNotEmpty(), "f$step: no held item reproduces the buffers")
        }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_clear"
        const val IWRAM = 0x03000000
        const val BASE = 0x08000000
        const val UNIT_COUNT = UnitTable.TOTAL_SIZE / UnitTable.UNIT_SIZE

        /** `gActiveEventRegistry`. */
        const val REGISTRY = 0x03001C80

        /** `sMsgString.buffer4` and `buffer5` as offsets in the EWRAM dump. */
        const val BUFFER4 = 0x2B4AC
        const val BUFFER5 = 0x2B5AC
    }
}
