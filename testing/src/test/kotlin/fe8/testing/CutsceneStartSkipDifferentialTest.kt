package fe8.testing

import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventChangeMap
import fe8.game.event.EventFade
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.flags.Fe8EventFlags
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.render.BgHardware
import fe8.game.event.ScriptWords
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.LcdControl
import gbafe.render.ScreenFade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `EventEngine_StartSkip` (Start during the Prologue castle cutscene,
 * `ram_trace_prologue_cutscene_skip`)
 * against the per-frame `procs` and `displayio` watches.
 *
 * The proc array of f10004 is mirrored with idle stand-ins (marks, lock counts, tree links); the
 * standard event engine proc gets a real [EventEngineProc] seeded from its RAM (`evStateBits`,
 * `execType`). The START press at f10005 goes through [EventEngine.onUpdate]. Then only the fade
 * proc it started runs (the talk procs stay idle stand-ins in this test): unverified here is the
 * talk tree's own reaction (see the second test, f10010..f10011) and the per-frame proc order.
 */
class CutsceneStartSkipDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val names = ProcArrayDump.romNames()

    @Test
    @VerifiesPort("EventEngine_StartSkip", "Proc_BlockEachMarked", "StartLockingFadeToBlack")
    fun `Start during the castle cutscene fades to black and blocks the talk procs`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue_cutscene_skip")
        val procsTrace = run.watch("procs")
        val io = run.watch("displayio")
        fun at(step: Int) = ProcArrayDump.ofWatch(procsTrace, step, names)

        val before = at(PRESS - 1)
        val lcd = LcdControl()
        lcd.dispcnt = io.u16(PRESS - 1, 0)
        lcd.bldcnt = io.u16(PRESS - 1, BLDCNT)
        lcd.blendY = io.u8(PRESS - 1, BLDY)
        val engine = ProcEngine()
        val fade = ScreenFade(engine, lcd)
        var pressed = false
        val hooks = object : EventEngineHooks {
            override fun startPressed() = pressed
            override fun startLockingFadeToBlack(speed: Int, parent: Proc) {
                fade.startLockingFadeToBlack(speed, parent)
            }
        }
        val events = EventEngine(engine, EventState(), ScriptWords({ 0 }), hooks)
        val seeded = seed(engine, before, events)
        val engineDump = before.find(STD_EVENT_ENGINE)
        assertNotNull(engineDump)
        val state = seeded[engineDump!!.slot]!!.state as EventEngineProc
        assertEquals(0x2, state.evStateBits)

        pressed = true
        events.onUpdate(state)
        pressed = false
        // The fade proc runs once more in the press frame (its level is 0x40 in the f10005 dump).
        for (frame in PRESS..LAST) {
            engine.procs.firstOrNull { it.script === fade.toBlack }?.let { engine.run(it) }
            compare(frame, at(frame), engine, state, lcd, io)
        }
    }

    /**
     * After the fade, f10005..f10069: the engine's `Event1D_WaitForLockTalk` (skipping) ends the
     * talk tree and the faces at f10010 (`ClearTalkBubble` clears the DISPCNT window bits, seen in
     * the hardware register at f10011) and `Event1B` REMA yields at f10011. The commands up to
     * f10038 run under SKIPPING; `Event02` clears the skip bit at f10038; `Event25` sets chapter 64
     * at f10039 (the game's map restart lags 13 frames, in which the engine does not run);
     * `Event17` fades in from black at f10053..f10069 and clears FADEDIN. Compared per frame: live
     * procs by script, `evStateBits`, `pEventCurrent`, event slots 1..13, `gPlaySt.chapterIndex`,
     * DISPCNT, and from f10053 the fade proc and BLDY. The talk and face procs are seeded with
     * their real scripts and end callbacks but never run (blocked until they end).
     *
     * Test-only stand-ins ([STAND_IN_CODES]): `Event2C`, `Event2F`, `Event30`, `Event34` and
     * `Event3B` (unit loads, moves, state, cursor; they return what the trace shows). `Event25`
     * runs with no-op map restart, camera and render callbacks (only the chapter is compared).
     */
    @Test
    @VerifiesPort(
        "Event1D_WaitForLockTalk", "Event1B_TEXTSHOW", "sub_800E640", "ClearTalkBubble",
        "Event02_EvBitAndIdMod", "Event0E_STAL",
    )
    fun `skipping ends the talk tree and the faces when the engine reaches the text wait`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val assets = java.io.File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val romFile = java.io.File(assets, "baserom.gba")
        val runDir = java.io.File(assets, "reference/out/ram_trace_prologue_cutscene_skip")
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_prologue_cutscene_skip")
        val procsTrace = run.watch("procs")
        val io = run.watch("displayio")
        fun at(step: Int) = ProcArrayDump.ofWatch(procsTrace, step, names)

        val rig = TalkRig(rom, runDir)
        val engine = rig.engine
        val before = at(PRESS - 1)
        val lcd = LcdControl()
        lcd.dispcnt = io.u16(PRESS - 1, 0)
        lcd.bldcnt = io.u16(PRESS - 1, BLDCNT)
        lcd.blendY = io.u8(PRESS - 1, BLDY)
        val fade = ScreenFade(engine, lcd)
        var pressed = false
        val hooks = object : EventEngineHooks {
            override fun startPressed() = pressed
            override fun startLockingFadeToBlack(speed: Int, parent: Proc) {
                fade.startLockingFadeToBlack(speed, parent)
            }
        }
        val words = ScriptWords({ a ->
            val o = a - ROM_BASE
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        })
        val state = EventState()
        val trace = TalkTrace(runDir)
        state.load(trace.iwram(PRESS - 1), EventState.BASE_ADDRESS - IWRAM)
        val playSt = PlaySt()
        playSt.chapterIndex = trace.ewram(PRESS - 1)[PLAY_ST_CHAPTER].toInt() and 0xFF
        val events = EventEngine(engine, state, words, hooks)
        EventScrCore(
            state, words, object : EventInput {}, Fe8EventFlags.create(), RngService(), playSt,
            KeyStatus(),
        ).registerAll(events)
        EventFade(words, fade).registerOn(events)
        EventChangeMap(state, words, playSt, BmSt(), BgHardware(), {}, {}, {}).registerOn(events)
        rig.onDisableTalkWindows = { lcd.setWinEnable(0, 0, 0) }
        val host = EventTextTalkHost(
            engine, rig.talk, rig.bubble, rig.face, rig, rig,
        ) { error("no string needed") }
        EventText(state, words, host).registerOn(events)
        // Unit, cursor and movement commands (2C, 2F, 30, 34, 3B) are test stand-ins: they leave no
        // state this test compares and return what the trace shows (yield when the frame's last
        // pc is the next command, park when the pc stays).
        var frameTarget = 0
        val stubbed = HashMap<Int, Int>()
        for (code in STAND_IN_CODES) {
            events.register(code, EventHandler { proc ->
                stubbed.merge(code, 1, Int::plus)
                val here = proc.pEventCurrent
                val next = here + 2 * ((words.u16(here) ushr 4) and 0xF)
                when (frameTarget) {
                    here -> EVC_STOP_YIELD
                    next -> EVC_ADVANCE_YIELD
                    else -> EVC_ADVANCE_CONTINUE
                }
            })
        }
        val real = mapOf(
            TalkRig.ADDR_TALK to rig.talk.talkScript,
            0x08591340 to rig.skipScript,
            TalkRig.ADDR_WAIT to rig.talk.waitForInputScript,
            0x08591154 to rig.face.faceScript,
            0x08591264 to rig.face.blinkScript,
            0x0859124C to rig.face.mouthScript,
        )
        val seeded = seed(engine, before, events, real)
        val engineProc = seeded[before.find(STD_EVENT_ENGINE)!!.slot]!!.state as EventEngineProc
        engineProc.pEventCurrent = before.find(STD_EVENT_ENGINE)!!.u32(0x38)
        val fadeAddress = names.entries.first { it.value == "ProcScr_FadeToBlack" }.key
        val addressOf: Map<ProcScript, Int> =
            real.entries.associate { (a, sc) -> sc to a } +
            (events.stdScript to STD_EVENT_ENGINE) + (fade.toBlack to fadeAddress) +
            (fade.fromBlack to names.entries.first { it.value == "ProcScr_FadeFromBlack" }.key)
        pressed = true
        events.onUpdate(engineProc)
        pressed = false
        for (frame in PRESS..LAST_FADE) {
            val dump = at(frame)
            val std = dump.find(STD_EVENT_ENGINE)!!
            frameTarget = std.u32(0x38)
            // The engine proc comes before the fade proc in the frame (tree 3 before tree 0 here).
            // The game's ChangeMap (f10039) lags 13 frames: the engine does not run in them.
            val inLag = frame in LAST_CHAPTER + 1..MAP_DONE
            if (frame > PRESS && !inLag && engineProc.proc!!.lockCnt == 0) {
                events.onUpdate(engineProc)
            }
            engine.procs.firstOrNull { it.script === fade.toBlack || it.script === fade.fromBlack }
                ?.let { engine.run(it) }
            assertEquals(
                dump.live.map { it.script }.sorted(),
                engine.procs.mapNotNull { p ->
                    p.script?.let { addressOf[it] ?: it.name.toLong(16).toInt() }
                }.sorted(),
                "f$frame live procs by script",
            )
            assertEquals(std.u16(0x3C), engineProc.evStateBits, "f$frame evStateBits")
            // ChangeMap (0x25) spans a lag frame: the dump of f10039 has the chapter set but the pc
            // still on the command (its yield advances it later, see EventChangeMapDifferentialTest).
            val lagged = if (frame in LAST_CHAPTER until MAP_DONE) 4 else 0
            assertEquals(std.u32(0x38) + lagged, engineProc.pEventCurrent, "f$frame pEventCurrent")
            if (frame >= TEXT_REMA) {
                val ew = trace.ewram(frame)
                assertEquals(
                    ew[PLAY_ST_CHAPTER].toInt() and 0xFF, playSt.chapterIndex, "f$frame chapter",
                )
                val iw = trace.iwram(frame)
                for (i in 1 until 14) {
                    assertEquals(
                        trace.u32(iw, SLOTS_IN_IWRAM + 4 * i),
                        state.slots[i], "f$frame slot $i",
                    )
                }
            }
            dump.find("ProcScr_FadeFromBlack")?.let { d ->
                val f = engine.procs.first { it.script === fade.fromBlack }
                    .state as ScreenFade.State
                assertEquals(d.u16(0x64), f.speed, "f$frame fade-in speed")
                assertEquals(d.u16(0x66), f.level, "f$frame fade-in level")
            }
            if (frame >= FADE_IN) {
                assertEquals(io.u8(frame + 1, BLDY), lcd.blendY, "f$frame BLDY (next frame)")
            }
            // The LCD buffer reaches DISPCNT at the next VBlank.
            assertEquals(io.u16(frame + 1, 0), lcd.dispcnt, "f$frame DISPCNT (next frame)")
        }
        println("CutsceneStartSkip stand-in commands run: $stubbed")
    }

    private fun compare(
        frame: Int,
        dump: ProcArrayDump,
        engine: ProcEngine,
        state: EventEngineProc,
        lcd: LcdControl,
        io: gba.testkit.WatchTrace,
    ) {
        val std = dump.find(STD_EVENT_ENGINE)!!
        assertEquals(std.u16(0x3C), state.evStateBits, "f$frame evStateBits")
        assertEquals(std.u8(LOCK), state.proc!!.lockCnt, "f$frame engine lockCnt")
        for (d in dump.live.filter { it.u8(MARK) == PROC_MARK_TALK }) {
            assertEquals(d.u8(LOCK), engine.procs[d.slot].lockCnt, "f$frame ${d.name} lockCnt")
        }
        val fadeDump = dump.find("ProcScr_FadeToBlack")
        val fadeProc = engine.procs.firstOrNull { it.script?.name == "ProcScr_FadeToBlack" }
        assertEquals(fadeDump != null, fadeProc != null, "f$frame fade proc exists")
        if (fadeDump != null) {
            val s = fadeProc!!.state as ScreenFade.State
            assertEquals(fadeDump.u16(0x64), s.speed, "f$frame fade speed")
            assertEquals(fadeDump.u16(0x66), s.level, "f$frame fade level")
        }
        // The LCD control buffer reaches the hardware registers at the next VBlank.
        assertEquals(io.u8(frame + 1, BLDY), lcd.blendY, "f$frame BLDY (hardware at f${frame + 1})")
        assertEquals(io.u16(frame + 1, BLDCNT), lcd.bldcnt, "f$frame BLDCNT")
    }

    /** Mirrors the live procs of [dump]; the event engine proc gets [events]' script and state. */
    private fun seed(
        engine: ProcEngine,
        dump: ProcArrayDump,
        events: EventEngine,
        real: Map<Int, ProcScript> = emptyMap(),
    ): Map<Int, Proc> {
        val idle = ProcScript("idle", listOf(ProcScripts.yield(), ProcScripts.repeat(ProcFunc { })))
        val top = dump.live.maxOf { it.slot }
        val started = (0..top).map { engine.start(idle, 0) }
        val live = dump.live.associateBy { it.slot }
        for (slot in 0..top) if (slot !in live) engine.end(started[slot])
        fun link(address: Int): Proc? =
            if (address < ProcArrayDump.ARRAY_ADDRESS) null
            else engine.procs[(address - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE]
        val result = HashMap<Int, Proc>()
        for ((slot, d) in live) {
            val p = engine.procs[slot]
            if (d.script == STD_EVENT_ENGINE) {
                p.script = events.stdScript
                val s = EventEngineProc()
                s.proc = p
                s.evStateBits = d.u16(0x3C)
                s.execType = d.u8(0x41)
                p.state = s
            } else if (d.script in real) {
                // A real script with its end callback (set before the first yield).
                val script = real.getValue(d.script)
                p.script = script
                p.scrScript = script
                p.state = script.newState()
            } else {
                p.script = ProcScript("%08x".format(d.script), idle.cmds)
            }
            p.mark = d.u8(MARK)
            p.lockCnt = d.u8(LOCK)
            p.flags = d.u8(0x27)
            p.parent = link(d.u32(0x14))
            p.rootIndex = if (p.parent == null) d.u32(0x14) else -1
            p.child = link(d.u32(0x18))
            p.next = link(d.u32(0x1C))
            p.prev = link(d.u32(0x20))
            p.endCb = (p.script?.cmds?.firstOrNull { it.opcode == SET_END_CB }?.ptr as? ProcFunc)
            p.idleCb = null
            result[slot] = p
        }
        return result
    }

    private companion object {
        const val PRESS = 10005
        const val LAST = 10009
        const val SET_END_CB = 0x04
        const val ROM_BASE = 0x08000000
        const val TEXT_REMA = 10011
        const val LAST_CHAPTER = 10039
        const val MAP_DONE = 10052
        const val FADE_IN = 10053
        const val LAST_FADE = 10069
        const val IWRAM = 0x03000000
        const val SLOTS_IN_IWRAM = EventState.BASE_ADDRESS - IWRAM + EventState.SLOTS_OFFSET
        const val PLAY_ST_CHAPTER = 0x2BCF0 + 0x0E
        val STAND_IN_CODES = intArrayOf(0x2C, 0x2F, 0x30, 0x34, 0x3B)
        const val STD_EVENT_ENGINE = 0x08591AC0
        const val MARK = 0x26
        const val LOCK = 0x28
        const val PROC_MARK_TALK = 5
        const val BLDCNT = 0x50
        const val BLDY = 0x54
    }
}
