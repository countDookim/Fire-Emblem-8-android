package fe8.testing

import fe8.game.Fe8Game
import fe8.game.Fe8Sound
import fe8.game.RomView
import gba.input.ActionSource
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The chapter intro wired into the New Game boot (D11-ci5, wiring only, no port claim): after the
 * Prologue world map `BMapMain` starts the real `gProcScr_ChapterIntro`, whose proc tree matches
 * `ram_trace_newgame_intro` frame by frame, then the beginning scene and the player phase follow.
 *
 * Alignment: the boot's world map is shorter than the original's, so frames are aligned on the
 * intro's first frame: the boot frame that first has `gProcScr_ChapterIntro` is the dump of 9322
 * (the original's intro proc exists from 9322). Keys: A on the title and menus until the intro
 * starts, then none (the capture presses nothing during the intro). The comparison stops at
 * [LAST_COMPARED]: from 9831 the original's main loop overran its frame twice (see
 * [ChapterIntroMainPathTest]), which the port does not, so the last frames are checked by their
 * own facts: the intro ends and the beginning events follow.
 */
class ChapterIntroBootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val rom = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )
    private val names by lazy { ProcArrayDump.romNames() }

    private val shared = setOf(
        "gProcScr_GameControl", "gProc_BMapMain", "sProc_BMVSync", "gProcScr_ChapterIntro",
        "ProcScr_ChapterIntro_KeyListen", "ProcScr_ChapterIntro_Bg1And3Scroll",
        "ProcScr_ChapterIntro_LightBurst", "ProcScr_ChapterIntro_Bg2Scroll",
        "ProcScr_ChapterIntro_PutDecalSprite", "ProcScr_ChapterIntro_RevealDecalSprite",
    )

    private fun intro(p: PrologueBoot) =
        p.boot.engine.procs.firstOrNull { it.script?.name == "gProcScr_ChapterIntro" }

    private fun ours(p: PrologueBoot): List<String> =
        p.boot.engine.procs.filter { it.script != null && it.script!!.name in shared }
            .map { "${it.script!!.name}@${it.scrCur}/l${it.lockCnt}" }.sorted()

    private fun theirs(data: ReferenceData.Run, frame: Int): List<String> {
        val key = data.dumps.entries.first { it.value == frame }.key
        return ProcArrayDump.ofEwram(data.region(key, "ewram"), names).live
            .filter { it.name in shared }
            .map { "${it.name}@${it.scrCur}/l${it.u8(0x28)}" }.sorted()
    }

    private fun boot(): PrologueBoot {
        lateinit var holder: PrologueBoot
        var seenIntro = false
        var introOver = false
        holder = PrologueBoot(
            rom,
            Pulse { f ->
                val running = holder.boot.engine.procs.any {
                    it.script?.name == "gProcScr_ChapterIntro"
                }
                if (running) seenIntro = true
                introOver = seenIntro && !running
                (!seenIntro || introOver) && f % 20 in 5..8
            },
        )
        return holder
    }

    @Test
    fun `intro after the world map matches the capture and hands over to the scene`() {
        assumeTrue(ref.available && rom.isFile && names.isNotEmpty(), "reference data missing")
        val p = boot()
        while (intro(p) == null && p.frame < 24000) p.advance()
        assertNotNull(intro(p), "chapter intro reached")
        val data = ref.run("ram_trace_newgame_intro")
        val bad = ArrayList<String>()
        for (f in FIRST..LAST_COMPARED) {
            val o = ours(p)
            val t = theirs(data, f)
            if (o != t) bad.add("f$f (boot ${p.frame}): expected $t, got $o")
            p.advance()
        }
        assertEquals(emptyList<String>(), bad.map { it.substringBefore(" (") }, "proc tree mismatches: ${bad.size}")
        var guard = 0
        while (intro(p) != null && guard++ < 400) p.advance()
        assertTrue(guard < 400, "intro ended")
        assertTrue(p.reachIdle(), "player phase reached after the intro")
    }

    /** Steady intro frames (every proc cursor unchanged around the frame) allocate nothing. */
    @Test
    fun `steady intro frames allocate no bytes`() {
        assumeTrue(AllocationMeter.supported, "allocation meter unavailable")
        assumeTrue(rom.isFile, "ROM unavailable")
        val view = RomView.ofRom(rom.readBytes())
        val game = Fe8Game(rom = view, sound = Fe8Sound.create(view))
        var frame = 0
        var seenIntro = false
        game.actionSource = ActionSource {
            if (!seenIntro && frame % 20 in 5..8) 0x1L else 0L
        }
        val boot = game.boot!!
        fun signature(): Int {
            var h = 0
            for (proc in boot.engine.procs) {
                if (proc.script != null) h = h * 31 + proc.script.hashCode() * 7 + proc.scrCur
            }
            return h
        }
        val signatures = ArrayList<Int>()
        val bytes = ArrayList<Long>()
        var introFrames = 0
        while (frame < 24000 && introFrames < 500) {
            if (boot.engine.procs.any { it.script?.name == "gProcScr_ChapterIntro" }) seenIntro = true
            val inIntro = boot.engine.procs.any { it.script?.name == "gProcScr_ChapterIntro" }
            game.input.update(game.actionSource.poll())
            val before = AllocationMeter.current()
            boot.frame()
            val after = AllocationMeter.current()
            game.present(0f)
            frame++
            if (inIntro) {
                introFrames++
                signatures.add(signature())
                bytes.add(after - before)
            }
        }
        var steady = 0
        val allocating = ArrayList<String>()
        for (t in 1 until signatures.size - 1) {
            if (signatures[t - 1] == signatures[t] && signatures[t] == signatures[t + 1]) {
                steady++
                if (bytes[t] != 0L) allocating.add("intro frame $t: ${bytes[t]}")
            }
        }
        assertTrue(steady > 100, "steady intro frames: $steady")
        assertEquals(emptyList<String>(), allocating.map { it.substringBefore(':') }, "bytes in steady intro frames")
    }

    private companion object {
        const val FIRST = 9325
        const val LAST_COMPARED = 9830
    }
}
