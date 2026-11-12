package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * START during the Prologue world-map narration through the real `Fe8Boot` New Game path (D14-1,
 * wiring only, no port claim) against `ram_trace_prologue_wm_skip`: instant black at START (no
 * `ProcScr_FadeToBlack`: `EventEngine_StartSkip`'s world map branch, `sub_800D488`), the whole
 * chapter intro, black again at `ChapterIntro_End`, the castle scene's engine, then its FADU
 * (`ProcScr_FadeFromBlack`, BLDY 15 down to 0 one step per frame) and the first text box.
 *
 * Alignment: the boot's monologue and world map differ in length from the original's, so frames
 * are counted from the START key frame (`i = 1` is the first frame after it, as the capture's step
 * 5266 is after its key frame 5265). The boot is driven like NEWGAME: A on the title and menus,
 * then A every 30 steps of the world map's own clock (step `4902 + n`, [LAG_STEPS] ignored), one
 * START at step 5265 and no input after. The original's loop overruns its frame at screen loads
 * (the intro is 551 frames there, 540 here; the castle engine waits 15 frames before FADU there,
 * 3 here), so those two spans are compared with a tolerance; every other fact is exact.
 */
class WorldMapSkipBootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val names by lazy { ProcArrayDump.romNames() }
    private val rom = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    /** One frame after the START key frame. */
    private class Snap(val procs: Set<String>, val dispcnt: Int, val bldy: Int)

    private fun run(startAt: Int): List<Snap> {
        var frame = 0
        var f0 = -1
        val a: Any = 0x0001
        val start: Any = 0x0008
        val zero: Any = 0
        val keys: () -> Any = {
            when {
                f0 < 0 -> if (frame % 20 in 5..8) a else zero
                frame == f0 + startAt -> start
                frame > f0 + startAt -> zero
                (frame - f0 + WM_FIRST_STEP) % 30 in 0..1 -> a
                else -> zero
            }
        }
        @Suppress("UNCHECKED_CAST")
        val boot = Fe8Boot(RomView.ofRom(rom.readBytes()), keys as () -> Int, RngService())
        val out = ArrayList<Snap>()
        var boxAt = -1
        while (frame < 24000 && (boxAt < 0 || out.size < boxAt + 10)) {
            boot.frame()
            frame++
            val procs = boot.engine.procs.mapNotNull { it.script?.name }.toSet()
            if (f0 < 0 && "ProcScr_WorldMapMain" in procs) f0 = frame
            if (f0 >= 0 && frame > f0 + startAt) {
                val lcd = boot.screenFade.lcd
                out.add(Snap(procs, lcd.dispcnt, lcd.blendY))
                if (boxAt < 0 && "ProcScr_BrownTextBox" in procs) boxAt = out.size
            }
        }
        return out
    }

    private fun capture(): List<Snap> {
        val data = ref.run("ram_trace_prologue_wm_skip")
        val procs = data.watch("procs")
        val io = data.watch("displayio")
        return (START_STEP + 1..LAST_STEP).map { step ->
            val live = ProcArrayDump.ofWatch(procs, step, names).live.mapNotNull { it.name }.toSet()
            Snap(live, io.u16(step, 0), io.u8(step, BLDY))
        }
    }

    /** The order and relative timing of the skip path, from 1-based frames after START. */
    private class Facts(private val snaps: List<Snap>) {
        private fun first(from: Int = 0, test: (Snap) -> Boolean): Int =
            (from until snaps.size).firstOrNull { test(snaps[it]) } ?: -1

        val black1 = snaps[0].dispcnt == 0 && snaps[0].bldy == 16
        val fadeToBlackProc = snaps.any { "ProcScr_FadeToBlack" in it.procs }
        val talkGone = 1 + first { "gProcScr_Talk" !in it.procs }
        private val introFirst = first { "gProcScr_ChapterIntro" in it.procs }
        private val introEnd = first(introFirst) { "gProcScr_ChapterIntro" !in it.procs }
        val introFrames = introEnd - introFirst
        // BLDY 16 from the frame the intro is gone (here) or the one after (capture). DISPCNT not
        // compared: the capture's layers stay off 11 frames (loop overrun), here the engine's first
        // update turns them on at once.
        val black2 = (introEnd..introEnd + 1).any { snaps[it].bldy == 16 }
        val castleEngine = first(introEnd) { "ProcScr_StdEventEngine" in it.procs }
        val castleAfterIntro = castleEngine - introEnd
        val fadeFirst = first(introEnd) { "ProcScr_FadeFromBlack" in it.procs }
        private val rampStart = first(introEnd) { it.bldy == 15 }
        val rampValues = (rampStart..rampStart + 15).map { snaps[it].bldy }
        val fadeProcDuringRamp = (rampStart..rampStart + 14).all {
            "ProcScr_FadeFromBlack" in snaps[it].procs
        }
        val box = first(introEnd) { "ProcScr_BrownTextBox" in it.procs }
        val engineToFade = fadeFirst - castleEngine
        val rampEndToBox = box - (rampStart + 15)
    }

    @Test
    fun `world map skip follows the capture's order and fade-in`() {
        assumeTrue(ref.available && rom.isFile && names.isNotEmpty(), "reference data missing")
        val theirs = Facts(capture())
        val ours = Facts(run(SKIP_AT))
        assertTrue(ours.black1, "black at once after START")
        assertEquals(theirs.black1, ours.black1)
        assertFalse(theirs.fadeToBlackProc)
        assertFalse(ours.fadeToBlackProc, "the world map skip runs no fade to black")
        // The talk procs end one frame later here than in the capture (the engine's own end order).
        assertTrue(ours.talkGone in theirs.talkGone..theirs.talkGone + 1, "talk ${ours.talkGone}")
        assertTrue(ours.introFrames in 530..560, "intro frames ${ours.introFrames}")
        assertTrue(theirs.introFrames in 530..560, "captured intro frames ${theirs.introFrames}")
        assertTrue(theirs.black2 && ours.black2, "black at ChapterIntro_End")
        assertEquals(theirs.castleAfterIntro, ours.castleAfterIntro, "castle engine after intro")
        assertEquals((15 downTo 0).toList(), theirs.rampValues, "captured BLDY ramp")
        assertEquals((15 downTo 0).toList(), ours.rampValues, "BLDY ramp, one step per frame")
        assertTrue(ours.fadeProcDuringRamp && theirs.fadeProcDuringRamp, "FadeFromBlack proc")
        assertTrue(ours.engineToFade in 1..theirs.engineToFade, "to FADU ${ours.engineToFade}")
        assertTrue(ours.box > 0, "first text box")
        assertTrue(ours.rampEndToBox in 1..theirs.rampEndToBox + 3, "box after the fade")
    }

    /**
     * START while a portrait is on the map (E_FACE_ExtraFrame): the skip ends the talk's face procs
     * but not the world map's wrapper entries; the face control proc then cast a reused slot (a
     * `GmapUnitProc`) to a face and threw `ClassCastException`, freezing the boot.
     */
    @Test
    fun `world map skip with a portrait shown reaches the castle scene fade-in`() {
        assumeTrue(rom.isFile, "ROM unavailable")
        for (startAt in intArrayOf(FACE_AT, FACE_AT + 100)) {
            val ours = Facts(run(startAt))
            assertEquals((15 downTo 0).toList(), ours.rampValues, "ramp after START at $startAt")
            assertTrue(ours.box > 0, "text box after START at $startAt")
        }
    }

    private companion object {
        const val WM_FIRST_STEP = 4902
        const val START_STEP = 5265
        const val LAST_STEP = 5958
        const val SKIP_AT = START_STEP - WM_FIRST_STEP
        const val FACE_AT = 605
        const val BLDY = 0x54
    }
}
