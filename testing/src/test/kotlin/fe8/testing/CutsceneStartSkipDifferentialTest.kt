package fe8.testing

import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
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
 * `EventEngine_StartSkip` (Start during the Prologue castle cutscene, `ram_trace_prologue_cutscene_skip`)
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
     * After the fade: the engine's `Event1D_WaitForLockTalk` (skipping) ends the talk tree and the
     * faces at f10010, `Event1B` REMA yields at f10011. The talk procs are seeded with their real
     * scripts and end callbacks but are not run (they are blocked until they end); the face
     * procs likewise. Window f10005..f10011.
     */
    @Test
    @VerifiesPort("Event1D_WaitForLockTalk", "Event1B_TEXTSHOW", "sub_800E640")
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
        val events = EventEngine(engine, state, words, hooks)
        val host = EventTextTalkHost(
            engine, rig.talk, rig.bubble, rig.face, rig, rig,
        ) { error("no string needed") }
        EventText(state, words, host).registerOn(events)
        // 0x2F follows REMA (0x1B22): it must not run in this window (REMA yields).
        events.register(0x2F, EventHandler { error("0x2F ran: REMA did not yield") })
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
            (events.stdScript to STD_EVENT_ENGINE) + (fade.toBlack to fadeAddress)
        pressed = true
        events.onUpdate(engineProc)
        pressed = false
        for (frame in PRESS..TEXT_REMA) {
            // The engine proc comes before the fade proc in the frame (tree 3 before tree 0 here).
            if (frame > PRESS && engineProc.proc!!.lockCnt == 0) events.onUpdate(engineProc)
            engine.procs.firstOrNull { it.script === fade.toBlack }?.let { engine.run(it) }
            val dump = at(frame)
            assertEquals(
                dump.live.map { it.script }.sorted(),
                engine.procs.mapNotNull { p ->
                    p.script?.let { addressOf[it] ?: it.name.toLong(16).toInt() }
                }.sorted(),
                "f$frame live procs by script",
            )
            val std = dump.find(STD_EVENT_ENGINE)!!
            assertEquals(std.u16(0x3C), engineProc.evStateBits, "f$frame evStateBits")
            assertEquals(std.u32(0x38), engineProc.pEventCurrent, "f$frame pEventCurrent")
        }
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
        const val STD_EVENT_ENGINE = 0x08591AC0
        const val MARK = 0x26
        const val LOCK = 0x28
        const val PROC_MARK_TALK = 5
        const val BLDCNT = 0x50
        const val BLDY = 0x54
    }
}
