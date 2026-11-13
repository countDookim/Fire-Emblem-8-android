package fe8.game.opanim

import fe8.game.RomView
import fe8.game.SoundWrapper
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endEach
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChild
import gbafe.proc.ProcScripts.whileTrue
import gbafe.render.BgHardware

/**
 * The opening monologue of the intro (`src/opsubtitle.c`, `StartIntroMonologue`): the story
 * text slides (`gOpSubtitleGfxLut`, 7 entries, each with a hold time in frames) over the
 * prologue music. Order: slides 0 and 1 fade in and out one at a time (label 0); then a
 * lens-flare scene with a full-screen background image fading in and the title card (slide 2)
 * (label 1); slides 3 to 5 are blended in over that background (label 2); slide 6 follows after
 * the whole screen fades out (label 4); the music and screen then fade to black (label 5). Start
 * at any time skips to a quick fade out (label 6). Both ends meet at label 7.
 *
 * Key input and the generic fades come from [hooks].
 *
 * Verification: the continuous parent replay covers steps 1214-4897 with the real archived
 * palette fade-in and slow fade to black, seeding the original reused transition timer from
 * step 1213. Long scene set-up callbacks are compared at completion because they run
 * atomically here. The three Start-skip captures (1505, 2375, 4405) check the skip's cursor,
 * fade, child ends and sound.
 */
public class OpSubtitle(
    private val engine: ProcEngine,
    hardware: BgHardware,
    rom: RomView,
    private val sound: SoundWrapper,
    private val hooks: Hooks,
) {
    /** External fade operations and the newly pressed keys for this frame. */
    public class Hooks(
        /** Starts `NewFadeIn` (the generic fade-in proc, distinct from `StartFadeCore` fades). */
        public val newFadeIn: (speed: Int, parent: Proc) -> Unit,
        /** `FadeInExists`: whether the fade-in proc is still running. */
        public val fadeInExists: () -> Boolean,
        /** `StartSlowFadeToBlack`: the fade used when the monologue ends by itself (label 5). */
        public val startSlowFadeToBlack: () -> Unit,
        /** `StartFastFadeToBlack`: the fade used after Start was pressed (label 6). */
        public val startFastFadeToBlack: () -> Unit,
        /** `WaitForFade`: one step of waiting for the fade to black, breaking [Proc] when done. */
        public val waitForFade: (Proc) -> Unit,
        /** `gKeyStatusPtr->newKeys`: the keys newly pressed this frame. */
        public val newKeys: () -> Int,
    )

    /** Loaders of the story text slides. */
    private val slides = OpSubtitleSlides(rom, hardware)
    private val setup = OpSubtitleSetup(rom, hardware, sound::startBgm)
    private val scenes = OpSubtitleScenes(hardware, rom, setup, slides)
    private val transitions = OpSubtitleTransitions(engine, hardware, rom, ::handleStartPress)
    private val flare = OpSubtitleFlare(engine, hardware, rom)
    private val subtitleSound = OpSubtitleSound(sound)

    /**
     * The lens-flare child: a 60-frame flare on BG2 during the flare scene, including its normal
     * end call (hides BG2); forced proc termination skips that call.
     * Transcribed from src/opsubtitle.c: gProcScr_OpSubtitle_LightFlareFx (not verified: no
     * @VerifiesPort test names it).
     */
    public val flareScript: ProcScript = ProcScript(
        "gProcScr_OpSubtitle_LightFlareFx",
        listOf(
            sleep(1),
            call(ProcFunc { flare.init(it.state as OpSubtitleFlare.State) }),
            repeat(ProcFunc { flare.loop(it, it.state as OpSubtitleFlare.State) }),
            sleep(1),
            call(ProcFunc { flare.end() }),
            end(),
        ),
        newState = { OpSubtitleFlare.State() },
    )

    /**
     * Parent proc command sequence. Labels: 0 slides 0 and 1; 1 flare scene and title card;
     * 2 slides 3 to 5; 4 slide 6; 5 natural end; 6 skipped with Start; 7 shared end.
     *
     * Transcribed from src/opsubtitle.c: gProcScr_OpSubtitle (not verified: lifecycle and skip).
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_OpSubtitle",
        listOf(
            sleep(0),
            call(ProcFunc { setup.init(it.state as OpSubtitleSlides.State) }),
            sleep(1),
            // Fade the screen in from black before the first hold.
            callArg(ProcArgFunc { speed, proc -> hooks.newFadeIn(speed, proc); true }, 2),
            whileTrue(ProcBoolFunc { hooks.fadeInExists() }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            // Label 0: loops for the first two slides.
            label(0),
            call(ProcFunc { slides.loadSingle(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.fadeIn(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.fadeOutFirstCards(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            goto(0),
            // Label 1: the flare scene (reached from the end of slide 1).
            label(1),
            startChild { flareScript },
            startChild { scenes.finalCardScript },
            call(ProcFunc { scenes.clearForFlare(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.fadeInCardPalette(it) }),
            call(ProcFunc { scenes.setupFinalScene(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.blendOut(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            call(ProcFunc { transitions.showFinalLayers() }),
            // Label 2: loops for slides 3 to 5 (blendIn jumps to label 4 after slide 5).
            label(2),
            call(ProcFunc { slides.loadDouble(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.blendIn(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.blendOut(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            goto(2),
            // Label 4: fade everything out, then show the last slide (6).
            label(4),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            call(ProcFunc { transitions.snapshotPalette(it) }),
            repeat(ProcFunc { transitions.fadeOutAll(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            call(ProcFunc { slides.loadSingle(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.fadeIn(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.fadeOutToCard(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            // Label 5: natural end; music fades out (speed 4), slow fade to black.
            label(5),
            endEach { flareScript },
            endEach { scenes.finalCardScript },
            call(ProcFunc { subtitleSound.fadeOutBgm() }),
            call(ProcFunc { hooks.startSlowFadeToBlack() }),
            repeat(ProcFunc { hooks.waitForFade(it) }),
            sleep(30),
            goto(7),
            // Label 6: Start pressed; the music fade (speed 1) was started by handleStartPress.
            label(6),
            endEach { flareScript },
            endEach { scenes.finalCardScript },
            call(ProcFunc { hooks.startFastFadeToBlack() }),
            repeat(ProcFunc { hooks.waitForFade(it) }),
            sleep(10),
            goto(7),
            // Label 7: both ends; puts BG1 back at scroll (0, 0).
            label(7),
            call(ProcFunc { scenes.resetCardOffset() }),
            sleep(1),
            end(),
        ),
        newState = { OpSubtitleSlides.State() },
    )

    /**
     * Starts the monologue as a blocking child of [parent].
     *
     * Transcribed from src/opsubtitle.c: StartIntroMonologue (not verified: lifecycle).
     */
    public fun startBlocking(parent: Proc): Proc? = engine.startBlocking(script, parent)

    /**
     * Skips the monologue when Start (`START_BUTTON`, 0x08) is newly pressed: fades the music out
     * at speed 1 and jumps [proc] to label 6 (fast fade to black). Called by every transition and
     * hold step, so Start works at any time.
     *
     * Ported from src/opsubtitle.c: OpSubtitle_HandleStartPress
     */
    private fun handleStartPress(proc: Proc) {
        if (hooks.newKeys() and 8 != 0) {
            sound.fadeOutBgm(1)
            engine.goto(proc, 6)
        }
    }
}
