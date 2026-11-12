package fe8.testing

import fe8.game.Fe8Sound
import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.opanim.OpSubtitle
import fe8.game.opanim.OpSubtitleSlides
import gba.audio.M4aBus
import gba.audio.M4aPlayer
import gba.audio.M4aSequencer
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.render.PaletteFade
import gbafe.render.ScreenFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Characterizes the original Start skip through the subtitle parent's termination, and checks the
 * skip's sound calls (`Sound_FadeOutBGM(1)` from `OpSubtitle_HandleStartPress`) against the
 * captured `gSoundSt` and BGM player fade state after each skip.
 */
class OpSubtitleSkipReplayTest {
    @Test
    @VerifiesPort("OpSubtitle_HandleStartPress", "Sound_FadeOutBGM")
    fun `original Start presses end children and run fast fade`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val rom = RomView.ofRom(romFile.readBytes())
        val baseline = ref.run("ram_trace_prologue").watch("procs")
        val soundSt = RamSymbols.fromEnvironment()["gSoundSt"].address
        val lcdOffset = RamSymbols.fromEnvironment()["gLCDControlBuffer"].address - GbaMem.IWRAM
        for (start in listOf(1505, 2375, 4405)) {
            val capture = ref.run("ram_trace_subtitle_skip_$start")
            val trace = capture.watch("procs")
            val keys = capture.watch("keys")
            val engine = ProcEngine()
            val m4a = Fe8Sound.create(rom)
            val wrapper = SoundWrapper(engine, m4a) { 0 }
            var sequencer: M4aSequencer? = null
            var players = emptyList<M4aPlayer>()
            val hardware = BgHardware()
            val screenFade = ScreenFade(engine, hardware.lcd)
            val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
            var step = 1213
            val host = OpSubtitle(
                engine, hardware, rom, wrapper,
                OpSubtitle.Hooks(
                    newFadeIn = { speed, proc -> fade.newFadeIn(speed, proc) },
                    fadeInExists = { fade.exists() },
                    startSlowFadeToBlack = { screenFade.startSlowFadeToBlack() },
                    startFastFadeToBlack = { screenFade.startFastFadeToBlack() },
                    waitForFade = { screenFade.waitForFade(it) },
                    // The captured game key status is the input consumed in this update.
                    newKeys = { keys.u16(step, 8) },
                ),
            )
            val parent = engine.start(ProcScript("parent", listOf(block(), end())), 3)
            val child = host.startBlocking(parent)!!
            val initial = ProcArrayDump.ofWatch(baseline, 1213).find(SUBTITLE)!!
            (child.state as OpSubtitleSlides.State).transitionTimer =
                initial.u16(0x2C).toShort().toInt()
            // Original scene-setup CALLs can span video steps. Replay each atomically and
            // compare after completion; tree 0 activity inside those callbacks is not modeled.
            var spanEnd = 0
            var fadeSteps = 0
            var soundFrames = 0
            for (frame in 1214..start + SOUND_FRAMES) {
                step = frame
                if (frame == start) {
                    // The sequencer model diverges from the capture over thousands of frames, so
                    // the sound state is restored from the dump taken after the frame before the skip.
                    val before = "r%05d".format(start - 1)
                    val ewram = capture.region(before, "ewram")
                    assertEquals(1, ewram[soundSt + 6 - GbaMem.EWRAM].toInt(), "$start playing")
                    wrapper.startBgmCore(GbaMem.u16(ewram, GbaMem.EWRAM, soundSt + 4), -1)
                    val (restored, info) = M4aRam.sound(capture.region(before, "iwram"))
                    @Suppress("UNCHECKED_CAST")
                    val live = m4a.players as MutableList<M4aPlayer>
                    for (i in restored.indices) live[i] = restored[i]
                    players = restored
                    sequencer = M4aSequencer(M4aBus { address -> rom.u8(address) }, info)
                }
                // Each frame ticks the m4a sequencer (VBlank) before the procs run; dump N is
                // taken after frame N.
                if (frame >= start) for (player in players) sequencer!!.step(player)
                val original = ProcArrayDump.ofWatch(trace, frame)
                val node = original.find(SUBTITLE)
                if (frame > spanEnd) {
                    if (frame < start && node != null &&
                        host.script.cmds[node.scrCur - 1].opcode == 0x02
                    ) {
                        var last = frame
                        while (
                            ProcArrayDump.ofWatch(trace, last + 1).find(SUBTITLE)?.scrCur ==
                            node.scrCur
                        ) {
                            last++
                        }
                        spanEnd = last + 1
                    }
                    for (tree in 0..7) engine.run(engine.root(tree))
                }
                if (frame >= start) {
                    val dump = "r%05d".format(frame)
                    val expected = M4aRam.sound(capture.region(dump, "iwram")).first
                    for (i in 0..1) {
                        assertEquals(
                            listOf(expected[i].fadeOI, expected[i].fadeOC, expected[i].fadeOV),
                            listOf(players[i].fadeOI, players[i].fadeOC, players[i].fadeOV),
                            "$start step $frame BGM player $i fade",
                        )
                        assertEquals(
                            expected[i].status, players[i].status, "$start step $frame player $i",
                        )
                    }
                    val ewram = capture.region(dump, "ewram")
                    assertEquals(
                        GbaMem.u16(ewram, GbaMem.EWRAM, soundSt + 4), wrapper.songId,
                        "$start step $frame songId",
                    )
                    assertEquals(
                        ewram[soundSt + 6 - GbaMem.EWRAM].toInt() != 0, wrapper.isSongPlaying,
                        "$start step $frame isSongPlaying",
                    )
                    soundFrames++
                }
                if (frame <= spanEnd - 1 || frame < start - 1) continue
                assertEquals(node != null, child.script != null, "$start step $frame parent")
                if (node != null) {
                    assertEquals(node.scrCur, child.scrCur, "$start step $frame cursor")
                }
                val origFade = original.find(FADE)
                val ourFade = engine.find(screenFade.toBlack)
                assertEquals(origFade != null, ourFade != null, "$start step $frame fade")
                if (origFade != null && ourFade != null) {
                    fadeSteps++
                    val state = ourFade.state as ScreenFade.State
                    assertEquals(origFade.scrCur, ourFade.scrCur, "$start step $frame fade cursor")
                    assertEquals(origFade.u16(0x64).toShort().toInt(), state.speed)
                    assertEquals(origFade.u16(0x66).toShort().toInt(), state.level)
                    val expected = LcdControl().apply {
                        readFrom(capture.region("r%05d".format(frame), "iwram"), lcdOffset)
                    }
                    assertEquals(expected.bldcnt, hardware.lcd.bldcnt, "$start step $frame bldcnt")
                    assertEquals(expected.blendY, hardware.lcd.blendY, "$start step $frame bldy")
                }
                val children = listOf(
                    FLARE to "gProcScr_OpSubtitle_LightFlareFx",
                    FINAL to "gProcScr_08AA2184",
                )
                for ((address, name) in children) {
                    var found = false
                    engine.forAll(ProcFunc { if (it.script?.name == name) found = true })
                    assertEquals(original.find(address) != null, found, "$start step $frame $name")
                }
                assertEquals(if (node == null) 0 else 1, parent.lockCnt, "$start step $frame lock")
            }
            assertEquals(SOUND_FRAMES + 1, soundFrames, "$start sound frames")
            assertTrue(fadeSteps > 0, "$start observes a fast fade")
            assertEquals(null, child.script, "$start subtitle terminates")
            if (start == 2375) {
                val before = ProcArrayDump.ofWatch(trace, start - 1)
                assertTrue(before.find(FLARE) != null && before.find(FINAL) != null)
                val after = ProcArrayDump.ofWatch(trace, start + 1)
                assertTrue(after.find(FLARE) == null && after.find(FINAL) == null)
            }
        }
    }

    private companion object {
        /**
         * Frames after the skip with sound compared: the fade ends the players by start + 22;
         * from start + 24 the code after the monologue starts its own fades (not ported here).
         */
        const val SOUND_FRAMES = 23
        const val SUBTITLE = 0x08AA21BC
        const val FLARE = 0x08AA2154
        const val FINAL = 0x08AA2184
        const val FADE = 0x0859A024
    }
}
