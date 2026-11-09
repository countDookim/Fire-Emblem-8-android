package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventCursorCamera
import fe8.game.event.EventCursorCameraHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventState
import fe8.game.event.MapCursorProc
import fe8.game.event.ScriptMemory
import fe8.game.event.createProcScrEventDisplayCursor
import fe8.game.event.eventDisplayCursorLoop
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class EventCursorCameraTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("EventDisplayCursor_Loop")
    fun `event display cursor loop matches prologue procs watch`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")

        val expectedOccurrences = listOf(
            9803 to (13 to 6),
            10635 to (13 to 4),
            12069 to (13 to 4),
            12725 to (13 to 3),
            13114 to (6 to 5),
            13677 to (6 to 5),
            14020 to (9 to 5),
            15093 to (7 to 5),
            15436 to (8 to 5),
            15994 to (9 to 5),
            18046 to (4 to 4),
            18272 to (4 to 4),
            18800 to (10 to 8),
        )

        var matchedOccurrences = 0
        for ((step, expectedPos) in expectedOccurrences) {
            val dump = ProcArrayDump.ofWatch(trace, step)
            val proc = dump.find(PROC_EVENT_DISPLAY_CURSOR)
            assertNotNull(proc, "ProcScr_EventDisplayCursor alive at step $step")
            val p = proc!!
            val x = p.u16(0x64).toShort().toInt()
            val y = p.u16(0x66).toShort().toInt()
            val subcmd = p.u16(0x68)
            val timer = p.u16(0x6A)

            assertEquals(expectedPos.first, x, "x at step $step")
            assertEquals(expectedPos.second, y, "y at step $step")
            assertEquals(1, subcmd, "subcmd at step $step")
            assertEquals(0, timer, "timer at step $step")

            // Test eventDisplayCursorLoop on the state decoded from the watch record
            val state = MapCursorProc(x = x, y = y, subcmd = subcmd, timer = timer)
            var cursorX = -1
            var cursorY = -1
            var cursorKind = -1
            eventDisplayCursorLoop(
                state,
                putMapCursor = { cx, cy, kind ->
                    cursorX = cx
                    cursorY = cy
                    cursorKind = kind
                },
            )
            assertEquals(x * 16, cursorX, "putMapCursor x at step $step")
            assertEquals(y * 16, cursorY, "putMapCursor y at step $step")
            assertEquals(0, cursorKind, "putMapCursor kind at step $step")

            matchedOccurrences++
        }
        assertEquals(expectedOccurrences.size, matchedOccurrences)

        // Also test subcmd & 0xc == 4 shining cursor advance branch
        val shiningState = MapCursorProc(x = 10, y = 20, subcmd = 4, timer = 5)
        var advanced = false
        eventDisplayCursorLoop(
            shiningState,
            putMapCursor = { _, _, _ -> error("should not be called for subcmd 4") },
            eventShinningCursorAdvance = { sx, sy, counter ->
                assertEquals(10, sx)
                assertEquals(20, sy)
                assertEquals(5, counter)
                advanced = true
                42
            },
        )
        assertTrue(advanced)
        assertEquals(42, shiningState.timer)

        // Test running via ProcEngine with createProcScrEventDisplayCursor
        val engine = ProcEngine()
        var loopCallCount = 0
        val script = createProcScrEventDisplayCursor(
            putMapCursor = { cx, cy, _ ->
                assertEquals(16, cx)
                assertEquals(32, cy)
                loopCallCount++
            },
        )
        val p = engine.start(script, 3)
        val s = p.state as MapCursorProc
        s.x = 1
        s.y = 2
        s.subcmd = 0
        engine.run(p)
        assertEquals(1, loopCallCount)
        engine.run(p)
        assertEquals(2, loopCallCount)
        engine.endEach(script)
        assertEquals(null, engine.find(script))
    }

    @Test
    @VerifiesPort("Event26_CameraControl")
    fun `event26CameraControl updates cursor position to match newgame_bm trace r09784`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_newgame_bm")
        val dir = File(ref.root, "ram_trace_newgame_bm")
        assumeTrue(File(dir, "r09783.ewram.bin").isFile, "r09783.ewram.bin missing")
        assumeTrue(File(dir, "r09784.ewram.bin").isFile, "r09784.ewram.bin missing")

        val preEwram = run.region("r09783", "ewram")
        val postEwram = run.region("r09784", "ewram")

        val procOffset = GPROC_EVENT_ENGINE - EWRAM_BASE
        val pEventCurrent83 = (preEwram[procOffset + 0x38].toInt() and 0xFF) or
            ((preEwram[procOffset + 0x39].toInt() and 0xFF) shl 8) or
            ((preEwram[procOffset + 0x3A].toInt() and 0xFF) shl 16) or
            ((preEwram[procOffset + 0x3B].toInt() and 0xFF) shl 24)
        assertEquals(0x089EECF0.toInt(), pEventCurrent83, "pEventCurrent at r09783")

        val bmOffset = GBMST - EWRAM_BASE
        val preX = (preEwram[bmOffset + 0x14].toInt() and 0xFF) or
            (preEwram[bmOffset + 0x15].toInt() shl 8)
        val preY = (preEwram[bmOffset + 0x16].toInt() and 0xFF) or
            (preEwram[bmOffset + 0x17].toInt() shl 8)
        assertEquals(0, preX)
        assertEquals(0, preY)

        val postX = (postEwram[bmOffset + 0x14].toInt() and 0xFF) or
            (postEwram[bmOffset + 0x15].toInt() shl 8)
        val postY = (postEwram[bmOffset + 0x16].toInt() and 0xFF) or
            (postEwram[bmOffset + 0x17].toInt() shl 8)
        val postDispX = (postEwram[bmOffset + 0x1C].toInt() and 0xFF) or
            (postEwram[bmOffset + 0x1D].toInt() shl 8)
        val postDispY = (postEwram[bmOffset + 0x1E].toInt() and 0xFF) or
            (postEwram[bmOffset + 0x1F].toInt() shl 8)
        assertEquals(14, postX)
        assertEquals(0, postY)
        assertEquals(224, postDispX)
        assertEquals(0, postDispY)

        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        val romBytes = romFile.readBytes()
        val mem = ScriptMemory { address ->
            val romOffset = address - 0x08000000
            if (romOffset in 0 until romBytes.size - 1) {
                (romBytes[romOffset].toInt() and 0xFF) or
                    ((romBytes[romOffset + 1].toInt() and 0xFF) shl 8)
            } else {
                0
            }
        }

        val bm = BmSt().apply { decode(preEwram, bmOffset) }
        var ensuredOntoX = -1
        var ensuredOntoY = -1
        val hooks = object : EventCursorCameraHooks {
            override fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) {
                ensuredOntoX = x
                ensuredOntoY = y
            }
        }
        val state = EventState()
        val cursorCam = EventCursorCamera(state, mem, bm, hooks)
        val proc = EventEngineProc().apply {
            pEventCurrent = pEventCurrent83
            evStateBits = 0
        }

        val result = cursorCam.event26CameraControl.run(proc)
        assertEquals(EVC_ADVANCE_YIELD, result)
        assertEquals(postX, bm.playerCursorX)
        assertEquals(postY, bm.playerCursorY)
        assertEquals(postDispX, bm.playerCursorDisplayX)
        assertEquals(postDispY, bm.playerCursorDisplayY)
        assertEquals(postX, ensuredOntoX)
        assertEquals(postY, ensuredOntoY)
    }

    @Test
    fun `event26CameraControl handles sc2 centred, slot coordinates, unit parameter, and skip`() {
        val state = EventState()
        state.slots[0xB] = (5 and 0xFFFF) or (9 shl 16)
        val bm = BmSt()

        // 1. subcode 0 with negative args reading slot 0xB and sc2 != 0
        var centredX = -1
        var centredY = -1
        val wordsCentred = shortArrayOf(0x2628.toShort(), (-1).toShort())
        val memCentred = ScriptMemory { wordsCentred[(it ushr 1) and 1].toInt() and 0xFFFF }
        val camCentred = EventCursorCamera(
            state, memCentred, bm,
            object : EventCursorCameraHooks {
                override fun cameraOntoPositionCentred(proc: EventEngineProc, x: Int, y: Int) {
                    centredX = x
                    centredY = y
                }
            },
        )
        val p = EventEngineProc().apply { pEventCurrent = 0; evStateBits = 0 }
        val rCentred = camCentred.event26CameraControl.run(p)
        assertEquals(EVC_ADVANCE_YIELD, rCentred)
        assertEquals(5, bm.playerCursorX)
        assertEquals(9, bm.playerCursorY)
        assertEquals(5, centredX)
        assertEquals(9, centredY)

        // 2. subcode 1: unit position resolution
        val wordsUnit = shortArrayOf(0x2621.toShort(), 0x000F.toShort())
        val memUnit = ScriptMemory { wordsUnit[(it ushr 1) and 1].toInt() and 0xFFFF }
        val camUnit = EventCursorCamera(
            state, memUnit, bm,
            object : EventCursorCameraHooks {
                override fun unitPosition(pid: Int): Int =
                    if (pid == 0xF) (13 shl 16) or 6 else -1
            },
        )
        p.pEventCurrent = 0
        val rUnit = camUnit.event26CameraControl.run(p)
        assertEquals(EVC_ADVANCE_YIELD, rUnit)
        assertEquals(13, bm.playerCursorX)
        assertEquals(6, bm.playerCursorY)

        // Error branch when unit not found
        val wordsMissing = shortArrayOf(0x2621.toShort(), 0x0099.toShort())
        val memMissing = ScriptMemory { wordsMissing[(it ushr 1) and 1].toInt() and 0xFFFF }
        val camMissing = EventCursorCamera(
            state, memMissing, bm,
            object : EventCursorCameraHooks {
                override fun unitPosition(pid: Int): Int = -1
            },
        )
        assertEquals(EVC_ERROR, camMissing.event26CameraControl.run(p))

        // 3. skipping / fadedin branch
        var rendered = false
        var realCamX = -1
        var realCamY = -1
        val camSkip = EventCursorCamera(
            state, memUnit, bm,
            object : EventCursorCameraHooks {
                override fun unitPosition(pid: Int): Int = (13 shl 16) or 6
                override fun setSomeRealCamPos(x: Int, y: Int, sc2: Int) {
                    realCamX = x
                    realCamY = y
                }
                override fun renderBmMap() { rendered = true }
            },
        )
        p.evStateBits = EV_STATE_SKIPPING
        val rSkip = camSkip.event26CameraControl.run(p)
        assertEquals(EVC_ADVANCE_CONTINUE, rSkip)
        assertEquals(13, realCamX)
        assertEquals(6, realCamY)
        assertTrue(rendered)
    }

    @Test
    @VerifiesPort("Event3B_DisplayCursor")
    fun `event3bDisplayCursor spawns cursor matching prologue procs watch`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")

        val expectedOccurrences = listOf(
            9803 to (13 to 6),
            10635 to (13 to 4),
            12069 to (13 to 4),
            12725 to (13 to 3),
            13114 to (6 to 5),
            13677 to (6 to 5),
            14020 to (9 to 5),
            15093 to (7 to 5),
            15436 to (8 to 5),
            15994 to (9 to 5),
            18046 to (4 to 4),
            18272 to (4 to 4),
            18800 to (10 to 8),
        )

        for ((step, expectedPos) in expectedOccurrences) {
            val dump = ProcArrayDump.ofWatch(trace, step)
            val proc = dump.find(PROC_EVENT_DISPLAY_CURSOR)
            assertNotNull(proc, "ProcScr_EventDisplayCursor alive at step $step")
            val p = proc!!
            val refX = p.u16(0x64).toShort().toInt()
            val refY = p.u16(0x66).toShort().toInt()
            val refSubcmd = p.u16(0x68)

            assertEquals(expectedPos.first, refX)
            assertEquals(expectedPos.second, refY)
            assertEquals(1, refSubcmd)

            // Verify event3bDisplayCursor spawns the cursor with matching coordinates and subcmd
            var startedX = -1
            var startedY = -1
            var startedSubcmd = -1
            val hooks = object : EventCursorCameraHooks {
                override fun unitPosition(pid: Int): Int = (refX shl 16) or (refY and 0xFFFF)
                override fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {
                    startedX = x
                    startedY = y
                    startedSubcmd = subcmd
                }
            }
            val words = shortArrayOf(0x3B21.toShort(), 0x0001.toShort()) // subcmd 1
            val mem = ScriptMemory { words[(it ushr 1) and 1].toInt() and 0xFFFF }
            val cam = EventCursorCamera(EventState(), mem, BmSt(), hooks)
            val engineProc = EventEngineProc().apply { pEventCurrent = 0; evStateBits = 0 }
            val res = cam.event3bDisplayCursor.run(engineProc)

            assertEquals(EVC_ADVANCE_CONTINUE, res)
            assertEquals(refX, startedX, "startedX at step $step")
            assertEquals(refY, startedY, "startedY at step $step")
            assertEquals(refSubcmd, startedSubcmd, "startedSubcmd at step $step")
        }
    }

    @Test
    fun `event3bDisplayCursor handles position, cure, and skipping`() {
        val state = EventState()
        state.slots[0xB] = (7 and 0xFFFF) or (3 shl 16)
        val bm = BmSt()

        // 1. subcmd 0 (CURSOR_AT) with explicit coordinates
        val wordsPos = shortArrayOf(0x3B20.toShort(), 0x0204.toShort()) // x=4, y=2
        val memPos = ScriptMemory { wordsPos[(it ushr 1) and 1].toInt() and 0xFFFF }
        var startedX = -1
        var startedY = -1
        var startedSubcmd = -1
        var cam = EventCursorCamera(
            state, memPos, bm,
            object : EventCursorCameraHooks {
                override fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {
                    startedX = x
                    startedY = y
                    startedSubcmd = subcmd
                }
            },
        )
        val p = EventEngineProc().apply { pEventCurrent = 0; evStateBits = 0 }
        assertEquals(EVC_ADVANCE_CONTINUE, cam.event3bDisplayCursor.run(p))
        assertEquals(4, startedX)
        assertEquals(2, startedY)
        assertEquals(0, startedSubcmd)

        // subcmd 0 with negative coordinates reading slot 0xB
        val wordsNeg = shortArrayOf(0x3B20.toShort(), (-1).toShort())
        val memNeg = ScriptMemory { wordsNeg[(it ushr 1) and 1].toInt() and 0xFFFF }
        cam = EventCursorCamera(
            state, memNeg, bm,
            object : EventCursorCameraHooks {
                override fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {
                    startedX = x
                    startedY = y
                    startedSubcmd = subcmd
                }
            },
        )
        assertEquals(EVC_ADVANCE_CONTINUE, cam.event3bDisplayCursor.run(p))
        assertEquals(7, startedX)
        assertEquals(3, startedY)

        // 2. subcmd 2 (CURE)
        var ended = false
        val wordsCure = shortArrayOf(0x3B22.toShort(), 0.toShort())
        val memCure = ScriptMemory { wordsCure[(it ushr 1) and 1].toInt() and 0xFFFF }
        cam = EventCursorCamera(
            state, memCure, bm,
            object : EventCursorCameraHooks {
                override fun endDisplayCursor() { ended = true }
            },
        )
        assertEquals(EVC_ADVANCE_YIELD, cam.event3bDisplayCursor.run(p))
        assertTrue(ended)

        // 3. skipping
        ended = false
        p.evStateBits = EV_STATE_SKIPPING
        assertEquals(EVC_ADVANCE_CONTINUE, cam.event3bDisplayCursor.run(p))
        assertTrue(ended)
    }

    private companion object {
        const val EWRAM_BASE = 0x02000000
        const val GPROC_EVENT_ENGINE = 0x02025018
        const val GBMST = 0x0202BCB0
        const val PROC_EVENT_DISPLAY_CURSOR = 0x08591F08
    }
}
