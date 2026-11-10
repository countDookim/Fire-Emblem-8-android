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
 * Standalone opening monologue. Generic fade effects and key input are supplied by [hooks]; their
 * timing and the Start skip have not yet been compared with original captures. The continuous
 * parent replay covers steps 1214–4896 (to the end of the monologue), with the original fade-in
 * lifetime supplied to the hook and the initial reused transition timer seeded from step 1213;
 * the closing slow fade-to-black is the real generic fade proc. Scene-setup callbacks span several
 * captured steps in the original and run atomically here. Start-skip behavior is unverified.
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
        /** Starts the generic `NewFadeIn`, which is distinct from screen fades. */
        public val newFadeIn: (speed: Int, parent: Proc) -> Unit,
        /** Whether the generic fade-in proc exists. */
        public val fadeInExists: () -> Boolean,
        /** Starts the slow fade to black used at the natural end. */
        public val startSlowFadeToBlack: () -> Unit,
        /** Starts the fast fade to black used after Start. */
        public val startFastFadeToBlack: () -> Unit,
        /** Advances `WaitForFade`, including breaking [Proc] when appropriate. */
        public val waitForFade: (Proc) -> Unit,
        /** Newly pressed key bitmask. */
        public val newKeys: () -> Int,
    )

    private val slides = OpSubtitleSlides(rom, hardware)
    private val setup = OpSubtitleSetup(rom, hardware, sound::startBgm)
    private val scenes = OpSubtitleScenes(hardware, rom, setup, slides)
    private val transitions = OpSubtitleTransitions(engine, hardware, rom, ::handleStartPress)
    private val flare = OpSubtitleFlare(engine, hardware, rom)
    private val subtitleSound = OpSubtitleSound(sound)

    /**
     * Flare child, including its normal end call; forced proc termination skips that call.
     * Transcribed from src/opsubtitle.c: gProcScr_OpSubtitle_LightFlareFx.
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
     * Parent proc command sequence.
     *
     * Transcribed from src/opsubtitle.c: gProcScr_OpSubtitle (lifecycle and skip unverified).
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_OpSubtitle",
        listOf(
            sleep(0),
            call(ProcFunc { setup.init(it.state as OpSubtitleSlides.State) }),
            sleep(1),
            callArg(ProcArgFunc { speed, proc -> hooks.newFadeIn(speed, proc); true }, 2),
            whileTrue(ProcBoolFunc { hooks.fadeInExists() }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            label(0),
            call(ProcFunc { slides.loadSingle(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.fadeIn(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.fadeOutFirstCards(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            goto(0),
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
            label(2),
            call(ProcFunc { slides.loadDouble(it.state as OpSubtitleSlides.State) }),
            repeat(ProcFunc { transitions.blendIn(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            repeat(ProcFunc { transitions.blendOut(it) }),
            repeat(ProcFunc { transitions.awaitHoldTimer(it) }),
            goto(2),
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
            label(5),
            endEach { flareScript },
            endEach { scenes.finalCardScript },
            call(ProcFunc { subtitleSound.fadeOutBgm() }),
            call(ProcFunc { hooks.startSlowFadeToBlack() }),
            repeat(ProcFunc { hooks.waitForFade(it) }),
            sleep(30),
            goto(7),
            label(6),
            endEach { flareScript },
            endEach { scenes.finalCardScript },
            call(ProcFunc { hooks.startFastFadeToBlack() }),
            repeat(ProcFunc { hooks.waitForFade(it) }),
            sleep(10),
            goto(7),
            label(7),
            call(ProcFunc { scenes.resetCardOffset() }),
            sleep(1),
            end(),
        ),
        newState = { OpSubtitleSlides.State() },
    )

    /**
     * Starts the monologue as a blocking child.
     * Transcribed from src/opsubtitle.c: StartIntroMonologue (lifecycle not yet verified).
     */
    public fun startBlocking(parent: Proc): Proc? = engine.startBlocking(script, parent)

    /**
     * Transcribed from src/opsubtitle.c: OpSubtitle_HandleStartPress; skip timing and cleanup
     * await original input captures.
     */
    private fun handleStartPress(proc: Proc) {
        if (hooks.newKeys() and 8 != 0) {
            sound.fadeOutBgm(1)
            engine.goto(proc, 6)
        }
    }
}
