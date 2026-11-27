package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.BattleForecast
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ItemInfo
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.text.Fe8Text
import fe8.game.ui.ItemIcon
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * PC-10g: the forecast tutorial's A-button mask against `ram_trace_newgame_easy`
 * (`gKeyStatusIgnoredSt`, IWRAM).
 *
 * First forecast (`FORECAST` queued, `f22524..22565`): `NewBattleForecast` adds the A bit
 * (`0x10A -> 0x10B` at `f22559 -> f22560`, held `f22560..22565`) and
 * `TriggerBattleForcastToturialEvent` removes it and starts the event (`0x10B -> 0x10A` at
 * `f22565 -> f22566`). The `IGNORE_KEYS` command of the event then changes the mask (`f22567`, not
 * this port). Second forecast
 * (`f23366..23374`, nothing queued): neither function touches the mask, A is accepted at `f23370`.
 */
@Timeout(180)
class ForecastTutorialMaskDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("NewBattleForecast")
    fun `NewBattleForecast adds the A bit to the ignore mask only while FORECAST is queued`() {
        val r = rig()
        // Before the forecast opens the queue is not FORECAST yet: the mask is untouched.
        r.assertNew(22523, expectedMask = r.mask(22523))
        // The forecast opens at f22559 -> f22560 with FORECAST queued.
        assertEquals(0x10A, r.mask(22559), "f22559 mask before")
        assertEquals(0x10B, r.mask(22560), "f22560 mask after")
        r.assertNew(22559, expectedMask = r.mask(22560))
        // Second forecast: nothing queued, the mask stays without the A bit.
        for (f in 23366..23374) r.assertNew(f, expectedMask = r.mask(f))
    }

    @Test
    @VerifiesPort("TriggerBattleForcastToturialEvent")
    fun `TriggerBattleForcastToturialEvent unmasks A and starts the event at f22566`() {
        val r = rig()
        for (f in 22560..22565) assertEquals(0x10B, r.mask(f), "f$f mask held")
        r.assertTrigger(22565, expectedMask = r.mask(22566), starts = 1)
        assertEquals(0x10A, r.mask(22566), "f22566 mask after")
        for (f in 23366..23374) r.assertTrigger(f, expectedMask = r.mask(f), starts = 0)
        r.assertTrigger(22523, expectedMask = r.mask(22523), starts = 0)
        // A is accepted at f23370: the mask has no A bit then.
        assertEquals(0, r.mask(23370) and 1, "f23370 A not masked")
    }

    @Test
    @VerifiesPort("NewBattleForecast", "TriggerBattleForcastToturialEvent")
    fun `production wiring starts the forecast tutorial event`() {
        val r = rig()
        r.assertWiring()
    }

    private fun rig(): Rig {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val trace = EventTrace.prologue(ref, RUN)
        assumeTrue(trace != null, "trace not present")
        return Rig(romFile.readBytes(), trace!!, ref.run(RUN))
    }

    private class Rig(
        private val romBytes: ByteArray,
        private val trace: EventTrace,
        private val run: ReferenceData.Run,
    ) {
        private val rom = RomView.ofRom(romBytes)
        private val chapters = ChapterData(rom)
        private val sym = RamSymbols.fromEnvironment()
        private val addr = sym["gKeyStatusIgnoredSt"].address
        private val mem = ScriptMemory { a ->
            val o = a - ROM_BASE
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }

        /** `gKeyStatusIgnoredSt` in the dump of [step]. */
        fun mask(step: Int): Int {
            val tag = listOf("f%05d", "r%05d").map { it.format(step) }
                .first { run.dumps.containsKey(it) }
            return GbaMem.u16(run.region(tag, "iwram"), GbaMem.IWRAM, addr)
        }

        private class Seeded(
            val forecast: BattleForecast,
            val keys: KeyStatus,
            val starts: IntArray,
            val pending: Boolean,
        )

        /** A forecast whose hooks run the real [EventInfos] seeded from the dump of [step]. */
        private fun seed(step: Int): Seeded {
            val playSt = PlaySt().also { it.decode(trace.at(step).playSt!!, 0) }
            val snap = trace.at(step)
            val flags = Fe8EventFlags.create().also {
                it.copyIn(snap.permanentFlags!! + snap.chapterFlags!!)
            }
            val infos = EventInfos(mem, flags, playSt)
            val starts = IntArray(1)
            infos.hooks = object : EventInfoHooks {
                override fun callEvent(script: Int, execType: Int) {
                    starts[0]++
                }

                override fun chapterEventGroup(chapter: Int): Int =
                    chapters.getChapterEventDataPointer(chapter)
            }
            val keys = KeyStatus().also { it.ignoreMask = mask(step) }
            val bm = BmSt()
            val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
            val units = UnitTable(rom)
            val engine = ProcEngine()
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            val battle = BmBattle(rom, units, loader, playSt, bm) { y, x -> map.terrain[y, x] }
            val gen = BattleGenerate(battle, rom, loader, playSt, RngService(), Mechanics())
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            val text = TextSystem(romBytes, TextVram(), Fe8Text.CONFIG)
            val forecast = BattleForecast(
                engine, battle, gen, ItemInfo(rom), units, ActiveUnit(), playSt, bm, map,
                BgHardware(), rom, text, messages,
                ByteArray(ItemIcon.MAX_ICON_COUNT * ItemIcon.ICON_ENTRY_SIZE),
                ByteArray(ItemIcon.MAX_ICON_GFX_COUNT), SpriteLayers(), { 0 }, keys,
            )
            forecast.hooks.tutorialPending = { infos.checkBattleForecastTutorialEvent() }
            forecast.hooks.startTutorialEvent = { infos.startBattleForecastTutorialEvent() }
            return Seeded(forecast, keys, starts, infos.checkBattleForecastTutorialEvent())
        }

        fun assertNew(step: Int, expectedMask: Int) {
            val s = seed(step)
            s.forecast.newBattleForecast()
            assertEquals(expectedMask, s.keys.ignoreMask, "f$step mask after NewBattleForecast")
            assertEquals(0, s.starts[0], "f$step NewBattleForecast starts no event")
        }

        fun assertTrigger(step: Int, expectedMask: Int, starts: Int) {
            val s = seed(step)
            s.forecast.triggerTutorial()
            assertEquals(expectedMask, s.keys.ignoreMask, "f$step mask after trigger")
            assertEquals(starts, s.starts[0], "f$step events started")
        }

        /**
         * The production wiring (`Fe8Boot`): seeded from the dumps, its forecast masks A at
         * `f22559 -> f22560` and unmasks it and starts the `StdEventEngine` at `f22565 -> f22566`;
         * in the second window (`f23370`) it leaves the mask alone and starts nothing.
         */
        fun assertWiring() {
            val boot = Fe8Boot(rom, { 0 }, RngService())
            boot.bmapMain
            val forecast = checkNotNull(boot.battleForecast) { "forecast wired" }

            fun seedBoot(step: Int) {
                val tag = listOf("f%05d", "r%05d").map { it.format(step) }
                    .first { run.dumps.containsKey(it) }
                val ew = run.region(tag, "ewram")
                val iw = run.region(tag, "iwram")
                boot.engine.init()
                boot.control.playSt.decode(ew, sym["gPlaySt"].address - GbaMem.EWRAM)
                boot.battleBmMap.initChapterMap(boot.control.playSt.chapterIndex)
                boot.flags.copyIn(iw, sym["gPermanentFlagBits"].address - GbaMem.IWRAM)
                boot.keys.ignoreMask = mask(step)
            }

            seedBoot(22559)
            forecast.newBattleForecast()
            assertEquals(mask(22560), boot.keys.ignoreMask, "boot f22560 mask")
            assertEquals(null, boot.eventEngine.findStd(), "no event before the slide in")
            seedBoot(22565)
            forecast.triggerTutorial()
            assertEquals(mask(22566), boot.keys.ignoreMask, "boot f22566 mask")
            assertEquals(true, boot.eventEngine.findStd() != null, "boot started the event")
            seedBoot(23370)
            forecast.newBattleForecast()
            forecast.triggerTutorial()
            assertEquals(mask(23370), boot.keys.ignoreMask, "boot f23370 mask")
            assertEquals(null, boot.eventEngine.findStd(), "boot started no event at f23370")
        }
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val ROM_BASE = 0x08000000

        fun u32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
