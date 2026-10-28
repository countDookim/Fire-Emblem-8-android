package fe8.game.opanim

import fe8.game.GameControl
import gbafe.core.math.Interpolate
import gbafe.data.Decompress
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endEach
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.startChild
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.HBlankHandler
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The op anim (intro cutscene after the logos), `ProcScr_OpAnim` (decomp `src/opanim-main.c`,
 * `src/opanimfx.c`). Partial: ported from `OpAnimInit` through `sub_80CAF2C`; a stand-in
 * `PROC_BLOCK` sits after it.
 */
public class OpAnim(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware (palette buffer, VRAM, LCD control). */
    public val hardware: BgHardware,
    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus,
    /** The game control proc's owner, for `SetNextGameActionId`. */
    public val gameControl: GameControl,
    /** Graphics and palettes loaded by [init]. */
    public val gfx: Gfx,
    /** World map graphics loaded by [setupWorldMap]. */
    public val worldMap: WorldMapGfx,
    /** Ephraim scene graphics loaded by [prepareEphraim] (null until a caller supplies them). */
    public val ephraim: EphraimGfx? = null,
    /** The sprite lists (`src/ctc.c`) the split line is put into. */
    public val sprites: SpriteLayers = SpriteLayers(),
    /** Eirika scene graphics loaded by [prepareEirika] (null until a caller supplies them). */
    public val eirika: EirikaGfx? = null,
    /** Graphics of the cast scenes after Eirika's (null until a caller supplies them). */
    public val cast: CastGfx? = null,
) {
    /** ROM data of the cast scenes (`sub_80C8564` onwards). */
    public class CastGfx(
        /** `Pal_OpAnimShiningRing` (4 palettes). */
        public val ringPal: ShortArray,
        /** `pal_08B103D8` (8 palettes, the scroll lines'). */
        public val scrollPal: ShortArray,
        /** `Img_OpAnimGenericCharacterBG`, `Tsa_OpAnimGenericCharacterBG`. */
        public val characterBg: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimShiningRing`, `Tsa_OpAnimShiningRing`. */
        public val ring: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** Joshua's scene (loaded by [joshuaScroll]). */
        public val joshua: CastScene,
        /** L'Arachel's, Seth's, Myrrh's, Saleh's and Tethys's scenes, in script order. */
        public val scenes: List<CastScene>,
        /** `Obj_08AA6C22`, `6C36`, `6C4A`, `6C58`, `6C66`, `6C74`, `6C82`, `6C8A`, `6C92`, `6C9A` (sparkles). */
        public val sparkles: List<SpriteObject>,
        /** `Pal_08ADBE78` (the face shadows' palette). */
        public val shadowPal: ShortArray,
        /** The first face screen's faces (Rennac, L'Arachel, Dozla), loaded by [loadFaces]. */
        public val faces1: List<Face>,
        /** `gUnknown_08AA709C` (a face, 64x64 in OBJ window parts). */
        public val faceObj: SpriteObject,
        /** `gUnknown_08AA70BC` (a face's OBJ window mask). */
        public val faceWindowObj: SpriteObject,
        /** The second face screen's faces (Ewan, Tethys, Gerik, Marisa), loaded by [loadFacesPulsed]. */
        public val faces2: List<Face>,
        /** `gUnknown_08AA70DC` (the second face screen's OBJ window mask). */
        public val faceWindowObj2: SpriteObject,
        /** The third face screen's faces (Garcia, Ross). */
        public val faces3: List<Face>,
        /** `gUnknown_08AA70EA` (the third face screen's OBJ window mask). */
        public val faceWindowObj3: SpriteObject,
        /** The fourth face screen's faces (Vanessa, Tana, Syrene). */
        public val faces4: List<Face>,
        /** `gUnknown_08AA7116` (the fourth face screen's OBJ window mask). */
        public val faceWindowObj4: SpriteObject,
    )

    /** A face in the face screens: `Img_OpAnimFace<Name>`, `Img_OpAnimFace<Name>Shadow`, `Pal_OpAnimFace<Name>`. */
    public class Face(
        /** The face image (compressed). */
        public val img: OpAnimHs.Blob,
        /** Its shadow image (compressed). */
        public val shadow: OpAnimHs.Blob,
        /** Its palette (16 colours). */
        public val pal: ShortArray,
    )

    /**
     * One character's scene in the cast scroll: two images and tilemaps (`Img_OpAnim<Name>`,
     * `Img_OpAnim<Name>2`...), 11 palettes and the scroll parameters its `sub_80C9C08`-like setup
     * function sets.
     */
    public class CastScene(
        /** `Img_OpAnim<Name>`, `Tsa_OpAnim<Name>`. */
        public val first: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnim<Name>2`, `Tsa_OpAnim<Name>2`. */
        public val second: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Pal_OpAnim<Name>` (11 palettes). */
        public val pal: ShortArray,
        /** `unk47`: scrolls in from the left. */
        public val fromLeft: Boolean,
        /** `unk44`. */
        public val offset: Int,
        /** `unk42`. */
        public val base: Int,
        /** `unk40`. */
        public val distance: Int,
    )

    /** ROM data used by [prepareEirika], in `OpAnimPreparefxEirika` order. */
    public class EirikaGfx(
        /** `Img_OpAnimEirikaClose1`, `Tsa_OpAnimEirikaClose1`. */
        public val close1: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEirikaClose2`, `Tsa_OpAnimEirikaClose2`. */
        public val close2: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEirika`, `Tsa_OpAnimEirika`. */
        public val eirika: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Pal_OpAnimEirikaBlur` (11 palettes). */
        public val blurPal: ShortArray,
        /** `Img_OpAnimEirikaBlur3`, `Tsa_OpAnimEirikaBlur3`. */
        public val blur3: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEirikaBlur2`, `Tsa_OpAnimEirikaBlur2`. */
        public val blur2: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEirikaBlur1`, `Tsa_OpAnimEirikaBlur1` (loaded by [eirikaFlyIn]). */
        public val blur1: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Obj_08AA6C0E` (Eirika's name sprite). */
        public val nameObj: SpriteObject,
        /** `imgs_08AA6D14` (100 compressed images, one per scroll line). */
        public val scrollImgs: List<OpAnimHs.Blob>,
        /** `tsas_08AA6EA4` (100 uncompressed 30-entry TSA rows). */
        public val scrollTsas: List<OpAnimHs.Blob>,
    )

    /** ROM data used by [setupWorldMap]. */
    public class WorldMapGfx(
        /** `Img_OpAnimWorldMap`. */
        public val img: OpAnimHs.Blob,
        /** `Tsa_OpAnimWorldMap`. */
        public val tsa: OpAnimHs.Blob,
        /** `Img_OpAnimWorldMapFog`. */
        public val fogImg: OpAnimHs.Blob,
        /** `Tsa_OpAnimWorldMapFog`. */
        public val fogTsa: OpAnimHs.Blob,
        /** `Pal_OpAnimWorldMapFog` (16 colours). */
        public val fogPal: ShortArray,
        /** `Pal_OpAnimWorldMap` (16 colours). */
        public val pal: ShortArray,
    )

    /** ROM data used by [prepareEphraim], in `OpAnimPreparefxEphraim` order. */
    public class EphraimGfx(
        /** `Img_OpAnimCharacterBG`, `Tsa_OpAnimCharacterBG`. */
        public val characterBg: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimClose1`, `Tsa_OpAnimEphraimClose1`. */
        public val close1: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimClose2`, `Tsa_OpAnimEphraimClose2`. */
        public val close2: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraim`, `Tsa_OpAnimEphraim`. */
        public val ephraim: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Pal_OpAnimEphraimBlur` (11 palettes). */
        public val blurPal: ShortArray,
        /** `Img_OpAnimEphraimBlur3`, `Tsa_OpAnimEphraimBlur3`. */
        public val blur3: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimBlur2`, `Tsa_OpAnimEphraimBlur2`. */
        public val blur2: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Img_OpAnimEphraimBlur1`, `Tsa_OpAnimEphraimBlur1` (loaded by [ephraimFlyIn]). */
        public val blur1: Pair<OpAnimHs.Blob, OpAnimHs.Blob>,
        /** `Pal_OpAnimCharacterBG` (1 palette, used by [mergeBgUpdatePalette]). */
        public val characterBgPal: ShortArray,
        /** `gObject_32x8`, `gObject_8x32` (the split line's sprites). */
        public val splitLineObjs: Pair<SpriteObject, SpriteObject>,
        /** `Obj_OpAnimEphEirikaName` (the name sprite). */
        public val nameObj: SpriteObject,
    )

    /** `gGenericBuffer` (0x2000 bytes used here). */
    public val genericBuffer: ByteArray = ByteArray(0x2000)

    /** `struct ProcOpAnimFadeIn`. */
    public class FadeInState {
        /** `cur`. */
        public var cur: Int = 0

        /** `max`. */
        public var max: Int = 0
    }

    /** `ProcScr_OpAnimFadeIn`: fades the screen in from black over `max` frames. */
    public val fadeInScript: ProcScript = ProcScript(
        "ProcScr_OpAnimFadeIn",
        listOf(
            yield(),
            call(ProcFunc { allBlack(it) }),
            repeat(ProcFunc { fadeInMain(it) }),
            end(),
        ),
        newState = { FadeInState() },
    )

    /** `struct ProcOpAnimBLDALPHA`. */
    public class BldAlphaState {
        /** `timer`. */
        public var timer: Int = 0
    }

    /**
     * `ProcScr_OpAnimBLDALPHA`: fades the BG palette in from black to white, waits, then blends the
     * fog in.
     */
    public val bldAlphaScript: ProcScript = ProcScript(
        "ProcScr_OpAnimBLDALPHA",
        listOf(
            call(ProcFunc { (it.state as BldAlphaState).timer = 0 }), // OpAnimBldAlphaInit
            repeat(ProcFunc { bldAlphaUpdateBgPalette(it) }),
            repeat(ProcFunc { bldAlphaDelay(it) }),
            repeat(ProcFunc { bldAlphaMain(it) }),
            end(),
        ),
        newState = { BldAlphaState() },
    )

    /** ROM data used by [init]: four compressed OBJ images and their 16-colour palettes. */
    public class Gfx(
        /**
         * `Img_OpAnimSplitLine`, `Img_opanim_unk1`, `Img_OpAnimEphEirikaName`, `Img_OpAnimDivlEye`.
         */
        public val images: List<OpAnimHs.Blob>,
        /** The matching `Pal_*` (16 colours each). */
        public val palettes: List<ShortArray>,
    )

    /** `struct ProcOpAnim` fields used so far. */
    public class State {
        /** `timer`. */
        public var timer: Int = 0

        /** `unk2C`: BG1 x scroll. */
        public var unk2C: Int = 0

        /** `unk2E`: BG1 y scroll. */
        public var unk2E: Int = 0

        /** `unk30`. */
        public var unk30: Int = 0

        /** `unk32`. */
        public var unk32: Int = 0

        /** `unk34`. */
        public var unk34: Int = 0

        /** `unk36`. */
        public var unk36: Int = 0

        /** `unk38`, `unk3A`, `unk3C`, `unk3E`, `unk46`. */
        public val unk38to46: IntArray = IntArray(5)

        /** `unk40`, `unk42` (u16), `unk44` (s16): scene scroll distance, base and offset. */
        public val unk40to44: IntArray = IntArray(3)

        /** `unk47`: scene scrolls in from the left (non-zero) or the right. */
        public var unk47: Int = 0

        /** `unk48`: byte offset of the scene's first tilemap in [genericBuffer]. */
        public var unk48: Int = 0

        /** `unk4C`: byte offset of its second tilemap in [genericBuffer], or null. */
        public var unk4C: Int? = null
    }

    /** `gOpAnimSt.x`, `y`, `unk04`, `unk06`, `unk08`, `unk0A` (s16 each). */
    public val opAnimSt: IntArray = IntArray(6)

    /** `gOpAnimSt.pal`: colour forced into palette entry 0 by [copyToPal]. */
    public var pal: Int = 0

    /** `ProcScr_OpAnimfxTerminator`: ends the op anim on A, B or Start. */
    public val terminatorScript: ProcScript = ProcScript(
        "ProcScr_OpAnimfxTerminator",
        listOf(yield(), repeat(ProcFunc { terminatorMain(it) }), end()),
    )

    /** The script (partial, see the class doc); [ProcScript.newState] creates a [State]. */
    public val script: ProcScript = ProcScript(
        "ProcScr_OpAnim",
        listOf(
            yield(),
            call(ProcFunc { init(it) }),
            yield(),
            call(ProcFunc { setupWorldMap(it) }),
            startChild { bldAlphaScript },
            whileTrue(ProcBoolFunc { bldAlphaExists() }),
            call(ProcFunc { updateScreen1(it) }),
            repeat(ProcFunc { worldMapScroll(it) }),
            call(ProcFunc { setupEphraimScene(it) }),
            yield(),
            repeat(ProcFunc { prepareEphraim(it) }),
            call(ProcFunc { newProc08AA6D04() }),
            repeat(ProcFunc { ephraimFlyIn(it) }),
            repeat(ProcFunc { setupSplitScreen(it) }),
            repeat(ProcFunc { advanceSplitLine(it) }),
            repeat(ProcFunc { ephraimMergeShadow(it) }),
            repeat(ProcFunc { ephraimDisplayName(it) }),
            repeat(ProcFunc { ephraimExit(it) }),
            repeat(ProcFunc { prepareEirika(it) }),
            repeat(ProcFunc { eirikaFlyIn(it) }),
            repeat(ProcFunc { eirikaSetupSplitScreen(it) }),
            repeat(ProcFunc { eirikaAdvanceSplitLine(it) }),
            repeat(ProcFunc { eirikaMergeShadow(it) }),
            repeat(ProcFunc { eirikaDisplayName(it) }),
            repeat(ProcFunc { eirikaExit(it) }),
            repeat(ProcFunc { fadeToDark(it) }),
            call(ProcFunc { setupCastScroll(it) }),
            startChild { castDarkenScript },
            repeat(ProcFunc { joshuaScroll(it) }),
            startChild { bg3ScrollScript },
            call(ProcFunc { showCastScroll() }),
            repeat(ProcFunc { fadeInCastScroll(it) }),
            call(ProcFunc { setupCastScene(it, castGfx().joshua) }),
            repeat(ProcFunc { castSceneScrollIn(it) }),
            repeat(ProcFunc { castSceneHold(it) }),
            repeat(ProcFunc { castSceneScrollOut(it) }),
            call(ProcFunc { showCastScrollFaded() }),
            repeat(ProcFunc { fadeOutCastScene(it) }),
            call(ProcFunc { setupCastScroll(it) }),
        ) + (0 until 5).flatMap { i ->
            listOf(
                repeat(ProcFunc { loadCastScene(it, castGfx().scenes[i], endDarken = i == 0) }),
                call(ProcFunc { showCastScroll() }),
                repeat(ProcFunc { fadeInCastScroll(it) }),
                call(ProcFunc { setupCastScene(it, castGfx().scenes[i]) }),
                repeat(ProcFunc { castSceneScrollIn(it) }),
                repeat(ProcFunc { castSceneHold(it) }),
                repeat(ProcFunc { castSceneScrollOut(it) }),
                call(ProcFunc { showCastScrollFaded() }),
                repeat(ProcFunc { fadeOutCastScene(it) }),
                call(ProcFunc { setupCastScroll(it) }),
            )
        } + listOf(
            startChild { castBrightenScript },
            endEach { bg3ScrollScript },
            repeat(ProcFunc { castScrollEnd(it) }),
            call(ProcFunc { showObjOnly() }),
            repeat(ProcFunc { sparkles(it) }),
            call(ProcFunc { endSparkles(it) }),
            call(ProcFunc { setupCastScroll(it) }),
            repeat(ProcFunc { loadFaces(it, castGfx().faces1) }),
            call(ProcFunc { setupFaceScreen() }),
            repeat(ProcFunc { faces1In(it) }),
            call(ProcFunc { setupCastScroll(it) }),
            repeat(ProcFunc { loadFacesPulsed(it, castGfx().faces2, 0x12, 7) }),
            call(ProcFunc { setupFaceScreen() }),
            repeat(ProcFunc { faces2In(it) }),
            call(ProcFunc { setupCastScroll(it) }),
            repeat(ProcFunc { loadFacesPulsed(it, castGfx().faces3, 0xE, 8) }),
            call(ProcFunc { setupFaceScreen() }),
            repeat(ProcFunc { faces3In(it) }),
            call(ProcFunc { setupCastScroll(it) }),
            repeat(ProcFunc { loadFacesPulsed(it, castGfx().faces4, 0xC, 9) }),
            call(ProcFunc { setupFaceScreen() }),
            repeat(ProcFunc { faces4In(it) }),
            block(), // not ported: sub_80C8564 (7th) and the rest of the cutscene
            end(),
        ),
        newState = { State() },
    )

    /**
     * Resets the display, loads the cutscene's OBJ graphics and palettes, sets the next game action
     * to "played through" and starts the terminator. `Sound_FadeOutBGM` is not modelled.
     *
     * Partially ported from src/opanim-main.c: OpAnimInit
     */
    public fun init(proc: Proc) {
        val hw = hardware
        hw.setupBackgrounds()
        hw.hblank.setPrimary(null)
        (proc.state as State).timer = 0
        pal = 0
        hw.lcd.dispcnt = hw.lcd.dispcnt or DISPCNT_OBJ_1D
        hw.lcd.setDispEnable(0, 0, 0, 0, 0)
        val objOffsets = intArrayOf(0x0000, 0x0100, 0x08C0, 0x1140)
        for (i in 0..3) {
            val img = gfx.images[i]
            Decompress.into(img.data, img.offset, hw.vram, OBJ_VRAM + objOffsets[i])
        }
        for (i in 0..3) copyToPal(gfx.palettes[i], 0x200 + 0x20 * i, 0x20)
        hw.vramFill16(0xFFFF, OBJ_VRAM + 0x7800, 0x800)
        gameControl.setNextGameActionId(GameControl.GAME_ACTION_PLAYED_THROUGH)
        engine.start(terminatorScript, proc)
    }

    /**
     * Sets up BG2 (world map) and BG1 (fog, palette 2), blending and display. `StartBgmExt` is not
     * modelled.
     *
     * Partially ported from src/opanim-main.c: SetupOpAnimWorldMapfx
     */
    public fun setupWorldMap(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        setOffsets(
            intArrayOf(0x0000, 0x4000, 0x8000, 0x0000),
            intArrayOf(0xD800, 0xE800, 0xF000, 0xF800),
        )
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.setBgPosition(bg, 0, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
        }
        lcd.dispcnt = lcd.dispcnt and 0xE000.inv() // SetWinEnable(0, 0, 0)
        val w = worldMap
        Decompress.into(w.img.data, w.img.offset, hw.vram, hw.tileDataOffset(2))
        Decompress.into(w.tsa.data, w.tsa.offset, hw.vram, 0xF000)
        hw.palette.fill(0, 0, 16)
        hw.fill(hw.mapBuffer(0), 0)
        hw.enableBgSyncByMask(1)
        Decompress.into(w.fogImg.data, w.fogImg.offset, hw.vram, hw.tileDataOffset(1))
        copyToPal(w.fogPal, 0x40, 0x20)
        Decompress.into(w.fogTsa.data, w.fogTsa.offset, hw.vram, 0xE800)
        for (i in 0 until 0x400) {
            val o = 0xE800 + 2 * i
            add16(hw.vram, o, 0x2000)
        }
        hw.enablePaletteSync()
        lcd.setBlendConfig(1, 0, 0x10, 8)
        lcd.setBlendTargetA(0, 0, 0, 0, 1)
        lcd.setBlendTargetB(1, 1, 1, 1, 0)
        (proc.state as State).timer = 0
        lcd.setDispEnable(0, 0, 1, 0, 1)
    }

    /**
     * Fills BG palette 0 with a grey interpolated from black to white over 0x3E frames, then sets
     * [pal] to white, keeps only OBJ as first blend target and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaUpdateBgPalette
     */
    public fun bldAlphaUpdateBgPalette(proc: Proc) {
        val st = proc.state as BldAlphaState
        val v = linear(0, 0x1F, st.timer, 0x3E)
        val color = (v shl 10) or (v shl 5) or v
        hardware.palette.fill(color.toShort(), 0, 16)
        hardware.enablePaletteSync()
        if (st.timer == 0x3E) {
            st.timer = 0
            pal = 0x7FFF
            hardware.lcd.setBlendTargetA(0, 0, 0, 0, 1)
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * Waits 8 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaDelay
     */
    public fun bldAlphaDelay(proc: Proc) {
        val st = proc.state as BldAlphaState
        if (st.timer == 8) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * Moves the blend coefficients from (0x10, 0) to (0, 0x10) over 0x10 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaMain
     */
    public fun bldAlphaMain(proc: Proc) {
        val st = proc.state as BldAlphaState
        val v = linear(0x10, 0, st.timer, 0x10)
        hardware.lcd.blendCoeffA = v and 0xFF
        hardware.lcd.blendCoeffB = (0x10 - v) and 0xFF
        if (st.timer == 0x10) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer++
    }

    /**
     * True while a `ProcScr_OpAnimBLDALPHA` proc exists.
     *
     * Ported from src/opanim-main.c: OpAnimBldAlphaExists
     */
    public fun bldAlphaExists(): Boolean = engine.find(bldAlphaScript) != null

    /**
     * Blends the fog (BG1) over the world map (BG2) and shows BG1, BG2 and OBJ.
     *
     * Ported from src/opanim-main.c: OpAnimUpdateScreen1
     */
    public fun updateScreen1(proc: Proc) {
        val lcd = hardware.lcd
        lcd.setBlendConfig(1, 8, 0x10, 8)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 0, 0)
        lcd.setDispEnable(0, 1, 1, 0, 1)
        val st = proc.state as State
        st.unk2C = 0
        st.unk2E = 0
    }

    /**
     * World map scene, one frame: scrolls the fog, fades the map palette in from white and the fog
     * out, starts the screen fade-in at frame 0xEF and breaks at 0x110.
     *
     * Ported from src/opanim-main.c: sub_80C6F70
     */
    public fun worldMapScroll(proc: Proc) {
        val st = proc.state as State
        val lcd = hardware.lcd
        if (st.timer and 1 == 0) st.unk2C = (st.unk2C + 1).toShort().toInt()
        if (st.timer and 3 == 0) st.unk2E = (st.unk2E + 1).toShort().toInt()
        lcd.setBgPosition(1, st.unk2C, st.unk2E)
        if (st.timer <= 0x60) {
            val v = linear(0x10, 0, st.timer, 0x60)
            copyToPal(worldMap.pal, 0, 0x20)
            blendPalette(0, 0x7FFF, v)
        }
        if (st.timer - 0x80 in 0..0x60) {
            lcd.blendCoeffA = linear(8, 0, st.timer - 0x80, 0x60) and 0xFF
        }
        if (st.timer == 0xEF) {
            lcd.setDispEnable(0, 0, 1, 0, 1)
            newFadeIn(0x20, proc)
        }
        if (st.timer == 0x110) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Resets the BG layout for the Ephraim / Eirika scenes: offsets, priorities, empty tilemaps,
     * display off, blend setup.
     *
     * Ported from src/opanim-main.c: sub_80C7050
     */
    public fun setupEphraimScene(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        setOffsets(
            intArrayOf(0x0000, 0x0000, 0x4000, 0x4000),
            intArrayOf(0xD800, 0xE000, 0xE800, 0xF800),
        )
        for (bg in 0..3) {
            hw.setScreenSize(bg, 0)
            lcd.setBgPosition(bg, 0, 0)
            lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
        }
        lcd.setDispEnable(0, 0, 0, 0, 0)
        lcd.dispcnt = lcd.dispcnt and 0xE000.inv() // SetWinEnable(0, 0, 0)
        for (bg in 0..3) hw.fill(hw.mapBuffer(bg), 0)
        hw.enableBgSyncByMask(0xF)
        // SetBlendBackdropA(0), SetBlendBackdropB(0)
        lcd.bldcnt = lcd.bldcnt and (BLD_BACKDROP_A or BLD_BACKDROP_B).inv()
        lcd.setBlendConfig(1, 8, 8, 8)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 1, 0)
        val st = proc.state as State
        st.timer = 0
        st.unk38to46.fill(0)
    }

    /**
     * Loads the Ephraim scene over 7 steps (by `timer`: one or two ROM blobs per step), then sets
     * up BG3 / palettes / `gOpAnimSt` and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimPreparefxEphraim
     */
    public fun prepareEphraim(proc: Proc) {
        val g = ephraim ?: error("OpAnim.ephraim graphics not supplied")
        val hw = hardware
        val vram = hw.vram
        val buf = genericBuffer
        val st = proc.state as State
        fun dec(b: OpAnimHs.Blob, dst: ByteArray, at: Int) =
            Decompress.into(b.data, b.offset, dst, at)
        when (st.timer) {
            0 -> {
                dec(g.characterBg.first, vram, 0x2000 + hw.tileDataOffset(3))
                val t = Decompress.decode(g.characterBg.second.data, g.characterBg.second.offset)
                val map = hw.mapBuffer(3)
                for (i in 0 until minOf(t.size / 2, map.size)) {
                    map[i] = ((t[2 * i].toInt() and 0xFF) or
                        ((t[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                for (i in 1 until 16) hw.palette[0xE0 + i] = 0x7FFF
                for (i in 0 until 0x280) map[i] = (map[i] + 0xE100).toShort()
                hw.enableBgSyncByMask(8)
            }
            1 -> {
                dec(g.close1.first, vram, hw.tileDataOffset(0))
                dec(g.close1.second, buf, 0)
            }
            2 -> {
                dec(g.close2.first, vram, 0x2000 + hw.tileDataOffset(0))
                dec(g.close2.second, buf, 0x800)
            }
            3 -> {
                dec(g.ephraim.first, vram, hw.tileDataOffset(2))
                dec(g.ephraim.second, buf, 0x1000)
                copyToPal(g.blurPal, 0, 0x20 * 11)
            }
            4 -> {
                dec(g.blur3.first, vram, 0x8000)
                dec(g.blur3.second, vram, 0xC000)
            }
            5 -> {
                dec(g.blur2.first, vram, 0xA000)
                dec(g.blur2.second, vram, 0xC800)
                for (i in 0 until 0x280) add16(vram, 0xC800 + 2 * i, 0x100)
            }
            6 -> {
                for (i in 1 until 16) hw.palette[0xF0 + i] = 0
                buf.copyInto(buf, 0x1800, 0x1000, 0x1800)
                for (i in 0 until 0x280) {
                    val o = 0x1800 + 2 * i
                    val v = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
                    val n = (v and 0xFFF) or 0xF000
                    buf[o] = n.toByte()
                    buf[o + 1] = (n shr 8).toByte()
                }
                buf.copyInto(vram, 0xF000, 0x1800, 0x1D00)
                hw.enablePaletteSync()
                hw.lcd.setDispEnable(0, 1, 1, 1, 1)
                st.timer = 0
                st.unk30 = 0
                st.unk32 = 0
                opAnimSt[0] = 0
                opAnimSt[1] = 0
                opAnimSt[2] = 0
                opAnimSt[3] = 0xA0
                pal = 0
                hw.setTileDataOffset(2, 0x8000)
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /** `struct Proc08AA7034`. */
    public class MergeBgState {
        /** `timer`. */
        public var timer: Int = 0
    }

    /** `ProcScr_OpAnimMergeBG`: fades BG palette 14 from white to `Pal_OpAnimCharacterBG`. */
    public val mergeBgScript: ProcScript = ProcScript(
        "ProcScr_OpAnimMergeBG",
        listOf(
            call(ProcFunc { mergeBgInit(it) }),
            repeat(ProcFunc { mergeBgUpdatePalette(it) }),
            end(),
        ),
        newState = { MergeBgState() },
    )

    /**
     * Clears the merge timer.
     *
     * Ported from src/opanim-main.c: OpAnimMergeBGProcInit
     */
    public fun mergeBgInit(proc: Proc) {
        (proc.state as MergeBgState).timer = 0
    }

    /**
     * Reloads BG palette 14 and blends it towards white by a factor falling from 16 to 0 over
     * 0x20 frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimMergeBGProcUpdateBgPalette
     */
    public fun mergeBgUpdatePalette(proc: Proc) {
        val st = proc.state as MergeBgState
        val g = ephraim ?: error("OpAnim.ephraim graphics not supplied")
        val v = Interpolate.interpolate(Interpolate.LINEAR, 0x10, 0, st.timer, 0x20)
        copyToPal(g.characterBgPal, 0x20 * 14, 0x20)
        blendPalette(16 * 14, 0x7FFF, v)
        if (st.timer == 0x20) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer = (st.timer + 1).toShort().toInt()
    }

    /**
     * Slides Ephraim's close-up in on BG2 (column by column, swapping in the blurred images) and
     * BG0/BG1 from the right, then breaks; starts [mergeBgScript] at step 16.
     *
     * Ported from src/opanim-main.c: OpAnimEphraimfxFlyIn
     */
    public fun ephraimFlyIn(proc: Proc) {
        val g = ephraim ?: error("OpAnim.ephraim graphics not supplied")
        val hw = hardware
        val vram = hw.vram
        val st = proc.state as State
        hw.enableBgSyncByMask(7)
        if (st.timer <= 0x10) {
            val ret = Interpolate.interpolate(Interpolate.RSQUARE, 0, 0x80, st.timer, 0x10)
            when (st.timer) {
                12 -> tsaModifyFirstPal(ret, 0, 2, vram, 0xC800)
                13 -> {
                    Decompress.into(g.blur1.first.data, g.blur1.first.offset, vram, 0x8000)
                    tsaModifyFirstPal(ret, st.unk30, 2, vram, 0xC800)
                }
                14 -> {
                    Decompress.into(g.blur1.second.data, g.blur1.second.offset, vram, 0xC000)
                    tsaModifyFirstPal(ret, 0, 2, vram, 0xC000)
                }
                16 -> {
                    tsaModifyFirstPal(ret, 0, 2, genericBuffer, 0x1000)
                    hw.setTileDataOffset(2, 0x4000)
                    engine.start(mergeBgScript, proc)
                }
                else -> tsaModifyFirstPal(ret, st.unk30, 2, vram, 0xC000)
            }
            st.unk30 = ret.toShort().toInt()
        }
        val v = st.timer - 5
        if (v >= 0) {
            val ret = Interpolate.interpolate(Interpolate.RSQUARE, 0, 0x90, v, 0x10)
            scrollInFirstPal(ret, st.unk32, 0xC8, 1, genericBuffer, 0, 0x800, setPal = true)
            st.unk32 = ret.toShort().toInt()
            if (v == 0x10) {
                scrollInFirstPal(st.unk32, 0, 0xC8, 0, genericBuffer, 0, 0x800, setPal = false)
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * `OpAnimHBlank1` (not verified yet: no frame trace covers it): BG2 uses map block 0x1E and
     * BLDCNT 0xC46 from the first line, map block 0x1D and BLDCNT 0xC42 from line
     * `gOpAnimSt.unk08`.
     */
    public val hBlank1: HBlankHandler = HBlankHandler { vcount, io ->
        fun put16(at: Int, v: Int) {
            io[at] = v.toByte()
            io[at + 1] = (v shr 8).toByte()
        }
        if (((vcount - 1) and 0xFFFF) > 0x9F) {
            put16(0x0C, (u16(io, 0x0C) and 0xC0FF) or 0x1E00)
            put16(0x50, 0xC46)
        }
        val split = opAnimSt[4] and 0xFFFF
        if (vcount == split || split == 0) {
            put16(0x0C, (u16(io, 0x0C) and 0xC0FF) or 0x1D00)
            put16(0x50, 0xC42)
        }
    }

    /**
     * Sets up the split screen: [hBlank1], window 0 (`gOpAnimSt.x` wide) and the outside window,
     * BG2 map at 0xF000, blend target A BG1/BG2, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C7900
     */
    public fun setupSplitScreen(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        hw.hblank.setPrimary(hBlank1)
        lcd.setWinEnable(1, 0, 0)
        lcd.setWin0Layers(1, 1, 1, 1, 1)
        lcd.setWin1Layers(1, 1, 0, 1, 1)
        lcd.setWOutLayers(0, 1, 1, 1, 1)
        lcd.setDispEnable(1, 1, 1, 1, 1)
        lcd.setWincntBit(5, true)
        lcd.setWincntBit(21, true)
        lcd.setWin0Box(0, 0, opAnimSt[0], 0xA0)
        hw.setMapDataOffset(2, 0xF000)
        lcd.setBlendTargetA(0, 1, 1, 0, 0)
        engine.breakProc(proc)
    }

    /**
     * `OpAnimHBlank2` (not verified yet: no frame trace covers it): BLDCNT 0xC46 from the first
     * line, 0xCCF from line `gOpAnimSt.unk0A`.
     */
    public val hBlank2: HBlankHandler = HBlankHandler { vcount, io ->
        if (((vcount - 1) and 0xFFFF) > 0x9F) {
            io[0x50] = 0x46
            io[0x51] = 0x0C
        }
        if (vcount == (opAnimSt[5] and 0xFFFF)) {
            io[0x50] = 0xCF.toByte()
            io[0x51] = 0x0C
        }
    }

    /**
     * Puts the split line: eight 32x8 sprites along line [yOam0] and eight 8x32 sprites down
     * column [xOam1], all on layer 0.
     *
     * Ported from src/opanim-main.c: OpAnimDrawSplitLine
     */
    public fun drawSplitLine(xOam1: Int, yOam0: Int) {
        val (h, v) = (ephraim ?: error("OpAnim.ephraim graphics not supplied")).splitLineObjs
        for (i in 0 until 8) sprites.putExt(0, i shl 5, yOam0, h, 0x80 / 0x20)
        for (i in 0 until 8) sprites.putExt(0, xOam1, i shl 5, v, 0)
    }

    /**
     * Moves the split point (`gOpAnimSt.x`, `y`) to (0xF0, 0xA0) over 0x10 steps, widening
     * window 0 and drawing the split line; then switches to [hBlank2] and breaks.
     *
     * Ported from src/opanim-main.c: OpAnim1AdvanceSplitLine
     */
    public fun advanceSplitLine(proc: Proc) {
        val st = proc.state as State
        opAnimSt[0] = Interpolate.interpolate(Interpolate.RSQUARE, 0, 0xF0, st.timer, 0x10)
            .toShort().toInt()
        opAnimSt[1] = Interpolate.interpolate(Interpolate.RSQUARE, 0, 0xA0, st.timer, 0x10)
            .toShort().toInt()
        hardware.lcd.setWin0Box(0, 0, opAnimSt[0], 0xA0)
        drawSplitLine(minOf(opAnimSt[0], 0xE8), minOf(opAnimSt[1], 0x88))
        if (st.timer == 0x10) {
            hardware.hblank.setPrimary(hBlank2)
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Raises the shadow split (`gOpAnimSt.unk06`, 0xA0 to 0x78) over 0x10 steps with the split
     * line at its end position, showing the name sprite from step 15; then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimEphraimMergeShadow
     */
    public fun ephraimMergeShadow(proc: Proc) {
        val st = proc.state as State
        opAnimSt[3] = Interpolate.interpolate(Interpolate.RSQUARE, 0xA0, 0x78, st.timer, 0x10)
            .toShort().toInt()
        drawSplitLine(0xE8, 0x88)
        if (st.timer > 0xE) putName(0x8C0 / 0x20)
        if (st.timer == 0x10) {
            st.timer = 0
            engine.breakProc(proc)
            return
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Shows the split line and the name sprite for 0x38 steps; then saves `unk30/32` into
     * `unk34/36`, hides BG1, forces palette 15 on BG2's map and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimEphraimDisplayName
     */
    public fun ephraimDisplayName(proc: Proc) {
        val st = proc.state as State
        drawSplitLine(0xE8, 0x88)
        putName(0xB40 / 0x20)
        if (st.timer == 0x38) {
            st.timer = 0
            st.unk34 = st.unk30
            st.unk36 = st.unk32
            hardware.lcd.setDispEnable(1, 0, 1, 1, 1)
            val map = hardware.mapBuffer(2)
            for (i in 0 until 0x280) map[i] = ((map[i].toInt() and 0x7FFF) or 0xF000).toShort()
            engine.breakProc(proc)
            return
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Ephraim's exit: the split line and shadow slide away (HBlank handler cleared at step 16),
     * BG2 slides out from step 4 and BG0 from step 11 with a blend fade from step 0x1C; breaks
     * at step 0x23.
     *
     * Ported from src/opanim-main.c: OpAnimEphraimExit
     */
    public fun ephraimExit(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        val st = proc.state as State
        hw.enableBgSyncByMask(5)
        val g = ephraim ?: error("OpAnim.ephraim graphics not supplied")
        if (st.timer < 2) sprites.putExt(1, 0x98, 0x88, g.nameObj, 0x2046)
        if (st.timer < 0x11) {
            opAnimSt[3] = Interpolate.interpolate(Interpolate.SQUARE, 0x78, 0xA0, st.timer, 0x10)
                .toShort().toInt()
            val x = Interpolate.interpolate(Interpolate.SQUARE, 0xE8, 0x100, st.timer, 0x10)
            drawSplitLine(x, opAnimSt[3] + 0x10)
            if (st.timer == 0x10) hw.hblank.setPrimary(null)
        }
        val time = st.timer
        val time1 = time - 4
        if (time1 >= 0) {
            if (time1 <= 0x18) {
                val ret = Interpolate.interpolate(Interpolate.SQUARE, st.unk34, 0x170, time1, 0x18)
                tsaModifyFirstPal(ret, st.unk30, 2, genericBuffer, 0x1000, setPal = true)
                st.unk30 = ret.toShort().toInt()
            }
            val time2 = time - 0xB
            if (time2 >= 0) {
                if (time1 == 0x18) {
                    lcd.setBlendTargetA(1, 0, 0, 0, 0)
                    lcd.setBlendTargetB(0, 0, 0, 1, 0)
                }
                if (time1 > 0x17) {
                    val a = Interpolate.interpolate(Interpolate.LINEAR, 0x10, 0, time - 0x1B, 0x8)
                    lcd.setBlendConfig(1, a, 0x10 - a, 8)
                }
                val ret = Interpolate.interpolate(Interpolate.SQUARE, st.unk36, 0x180, time2, 0x18)
                scrollInFirstPal(ret, st.unk32, 0xC8, 0, genericBuffer, 0, 0x800, setPal = false)
                st.unk32 = ret.toShort().toInt()
                if (time2 == 0x18) {
                    st.timer = 0
                    engine.breakProc(proc)
                    return
                }
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Resets BG0..2 and the blend for the Eirika scene, then loads it over 6 more steps (by
     * `timer`), sets `gOpAnimSt` and breaks.
     *
     * Ported from src/opanim-main.c: OpAnimPreparefxEirika
     */
    public fun prepareEirika(proc: Proc) {
        val g = eirika ?: error("OpAnim.eirika graphics not supplied")
        val hw = hardware
        val lcd = hw.lcd
        val vram = hw.vram
        val buf = genericBuffer
        val st = proc.state as State
        fun dec(b: OpAnimHs.Blob, dst: ByteArray, at: Int) =
            Decompress.into(b.data, b.offset, dst, at)
        when (st.timer) {
            0 -> {
                lcd.setDispEnable(0, 0, 0, 1, 1)
                lcd.setWinEnable(0, 0, 0)
                hw.setMapDataOffset(2, 0xE800)
                for (bg in 0..2) lcd.setBgPosition(bg, 0, 0)
                for (bg in 0..2) hw.fill(hw.mapBuffer(bg), 0)
                hw.enableBgSyncByMask(7)
                lcd.bldcnt = lcd.bldcnt and (0x20 or 0x2000).inv() // SetBlendBackdropA/B(0)
                lcd.setBlendConfig(1, 8, 8, 8)
                lcd.setBlendTargetA(0, 1, 0, 0, 0)
                lcd.setBlendTargetB(0, 0, 1, 1, 0)
            }
            1 -> {
                dec(g.close1.first, vram, hw.tileDataOffset(0))
                dec(g.close1.second, buf, 0)
            }
            2 -> {
                dec(g.close2.first, vram, 0x2000 + hw.tileDataOffset(0))
                dec(g.close2.second, buf, 0x800)
            }
            3 -> {
                dec(g.eirika.first, vram, hw.tileDataOffset(2))
                dec(g.eirika.second, buf, 0x1000)
                copyToPal(g.blurPal, 0, 0x160)
            }
            4 -> {
                dec(g.blur3.first, vram, 0x8000)
                dec(g.blur3.second, vram, 0xC000)
            }
            5 -> {
                dec(g.blur2.first, vram, 0xA000)
                dec(g.blur2.second, vram, 0xC800)
                for (i in 0 until 0x280) add16(vram, 0xC800 + 2 * i, 0x100)
            }
            6 -> {
                buf.copyInto(buf, 0x1800, 0x1000, 0x1800)
                for (i in 0 until 0x280) {
                    val o = 0x1800 + 2 * i
                    val n = (u16(buf, o) and 0xFFF) or 0xF000
                    buf[o] = n.toByte()
                    buf[o + 1] = (n shr 8).toByte()
                }
                buf.copyInto(vram, 0xF000, 0x1800, 0x1D00)
                hw.enablePaletteSync()
                lcd.setDispEnable(0, 1, 1, 1, 1)
                st.timer = 0
                st.unk30 = 0
                st.unk32 = 0
                opAnimSt[0] = 240
                opAnimSt[1] = 0
                opAnimSt[2] = 0
                opAnimSt[3] = 160
                hw.setTileDataOffset(2, 0x8000)
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Slides Eirika's close-up in on BG2 from the right (swapping in the blurred images) and
     * BG0/BG1 from the left, then breaks: the mirror of [ephraimFlyIn].
     *
     * Ported from src/opanim-main.c: sub_80C7F90
     */
    public fun eirikaFlyIn(proc: Proc) {
        val g = eirika ?: error("OpAnim.eirika graphics not supplied")
        val hw = hardware
        val vram = hw.vram
        val st = proc.state as State
        hw.enableBgSyncByMask(7)
        if (st.timer <= 0x10) {
            val ret = Interpolate.interpolate(Interpolate.RSQUARE, 0, 0x68, st.timer, 0x10)
            fun slide(start: Int, src: ByteArray, at: Int) =
                scrollInFirstPal(ret, start, 0x98, 2, src, at, null, setPal = false)
            when (st.timer) {
                12 -> slide(0, vram, 0xC800)
                13 -> {
                    Decompress.into(g.blur1.first.data, g.blur1.first.offset, vram, 0x8000)
                    slide(st.unk30, vram, 0xC800)
                }
                14 -> {
                    Decompress.into(g.blur1.second.data, g.blur1.second.offset, vram, 0xC000)
                    slide(0, vram, 0xC000)
                }
                16 -> {
                    slide(0, genericBuffer, 0x1000)
                    hw.setTileDataOffset(2, 0x4000)
                }
                else -> slide(st.unk30, vram, 0xC000)
            }
            st.unk30 = ret.toShort().toInt()
        }
        val time = st.timer - 5
        if (time >= 0) {
            val ret = Interpolate.interpolate(Interpolate.RSQUARE, 0, 0xCC, time, 0x10)
            tsaModifyFirstPal(ret, st.unk32, 1, genericBuffer, 0, 0x800, setPal = true)
            st.unk32 = ret.toShort().toInt()
            if (time == 0x10) {
                tsaModifyFirstPal(st.unk32, 0, 0, genericBuffer, 0, 0x800, setPal = false)
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Sets up the split screen for Eirika: [hBlank1], window 0 from `gOpAnimSt.x` to the right
     * edge, BG2 map at 0xF000, blend target A BG1/BG2, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8100
     */
    public fun eirikaSetupSplitScreen(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        hw.hblank.setPrimary(hBlank1)
        lcd.setWinEnable(1, 0, 0)
        lcd.setDispEnable(1, 1, 1, 1, 1)
        lcd.setWin0Box(opAnimSt[0], 0, 240, 160)
        hw.setMapDataOffset(2, 0xF000)
        lcd.setBlendTargetA(0, 1, 1, 0, 0)
        engine.breakProc(proc)
    }

    /**
     * Moves the split point (`gOpAnimSt.x`, `y`) from (240, 0) to (0, 160) over 0x10 steps with
     * window 0 and the split line; then switches to [hBlank2] and breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8184
     */
    public fun eirikaAdvanceSplitLine(proc: Proc) {
        val st = proc.state as State
        opAnimSt[0] = Interpolate.interpolate(Interpolate.RSQUARE, 240, 0, st.timer, 0x10)
            .toShort().toInt()
        opAnimSt[1] = Interpolate.interpolate(Interpolate.RSQUARE, 0, 160, st.timer, 0x10)
            .toShort().toInt()
        hardware.lcd.setWin0Box(opAnimSt[0], 0, 240, 160)
        drawSplitLine(maxOf(opAnimSt[0], 8), minOf(opAnimSt[1], 0x88))
        if (st.timer == 0x10) {
            hardware.hblank.setPrimary(hBlank2)
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Raises the shadow split (`gOpAnimSt.unk06`, 160 to 120) over 0x10 steps with the split line
     * at the left, showing Eirika's name sprite from step 15; then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8214
     */
    public fun eirikaMergeShadow(proc: Proc) {
        val st = proc.state as State
        val g = eirika ?: error("OpAnim.eirika graphics not supplied")
        opAnimSt[3] = Interpolate.interpolate(Interpolate.RSQUARE, 160, 120, st.timer, 0x10)
            .toShort().toInt()
        drawSplitLine(8, 0x88)
        if (st.timer > 0xE) sprites.putExt(1, 8, 0x88, g.nameObj, 0x206E)
        if (st.timer == 0x10) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Decompresses scroll line [index] (0..99, else nothing) into VRAM 0x8000 + 0x400 * slot and
     * writes its 30-entry row into the BG map at VRAM 0xE800, row `~index & 0x1F`.
     *
     * Not verified yet (VRAM is not in the RAM traces): src/opanim-main.c:
     * OpAnim1_UpdateScrollOneLine
     */
    public fun updateScrollOneLine(index: Int) {
        val g = eirika ?: error("OpAnim.eirika graphics not supplied")
        val idx = index.toShort().toInt()
        if (idx < 0 || idx > 99) return
        val vram = hardware.vram
        var at = 0xE800 + ((idx.inv() and 0x1F) shl 6)
        val n = 99 - idx
        val slot = n % 0x16
        val img = g.scrollImgs[n]
        Decompress.into(img.data, img.offset, vram, 0x8000 + 0x400 * slot)
        val tsa = g.scrollTsas[n]
        for (i in 0 until 0x1E) {
            val v = (u16(tsa.data, tsa.offset + 2 * i) and 0xFC00) or (32 * slot + i)
            vram[at] = v.toByte()
            vram[at + 1] = (v shr 8).toByte()
            at += 2
        }
    }

    /**
     * Shows the split line and Eirika's name for 0x38 steps, loading scroll lines 0..21 over the
     * first three; then saves `unk30/32` into `unk34/36`, hides BG1, forces palette 15 on BG2's
     * map and breaks.
     *
     * Not verified yet (checked up to step 28: `ram_trace_opanim` ends at f1100):
     * src/opanim-main.c: sub_80C8278
     */
    public fun eirikaDisplayName(proc: Proc) {
        val st = proc.state as State
        val g = eirika ?: error("OpAnim.eirika graphics not supplied")
        drawSplitLine(8, 0x88)
        sprites.putExt(1, 8, 0x88, g.nameObj, 0x7C + (2 shl 12))
        when (st.timer) {
            0 -> for (i in 0 until 8) updateScrollOneLine(i)
            1 -> for (i in 8 until 0x10) updateScrollOneLine(i)
            2 -> for (i in 0x10 until 0x16) updateScrollOneLine(i)
        }
        if (st.timer == 0x38) {
            st.timer = 0
            st.unk34 = st.unk30
            st.unk36 = st.unk32
            hardware.lcd.setDispEnable(1, 0, 1, 1, 1)
            val map = hardware.mapBuffer(2)
            for (i in 0 until 0x280) map[i] = ((map[i].toInt() and 0x7FFF) or 0xF000).toShort()
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Eirika's exit, mirroring [ephraimExit] from the left: hides her name, closes the split line,
     * scrolls BG2 (her) out to the left and BG0 out, then fades BG0 over BG3 and breaks.
     *
     * Ported from src/opanim-main.c: sub_80C835C
     */
    public fun eirikaExit(proc: Proc) {
        val hw = hardware
        val lcd = hw.lcd
        val st = proc.state as State
        hw.enableBgSyncByMask(5)
        val g = eirika ?: error("OpAnim.eirika graphics not supplied")
        if (st.timer < 2) sprites.putExt(1, 8, 0x88, g.nameObj, 0x6E + (2 shl 12))
        if (st.timer < 0x11) {
            opAnimSt[3] = Interpolate.interpolate(Interpolate.SQUARE, 0x78, 0xA0, st.timer, 0x10)
                .toShort().toInt()
            val x = Interpolate.interpolate(Interpolate.SQUARE, 8, 0, st.timer, 0x10)
            drawSplitLine(x, 0x10 + opAnimSt[3])
            if (st.timer == 0x10) hw.hblank.setPrimary(null)
        }
        val time = st.timer
        val time1 = time - 4
        if (time1 >= 0) {
            if (time1 <= 0x18) {
                val ret = Interpolate.interpolate(Interpolate.SQUARE, st.unk34, 0x158, time1, 0x18)
                scrollInFirstPal(ret, st.unk30, 0x98, 2, genericBuffer, 0x1000, null, setPal = true)
                st.unk30 = ret.toShort().toInt()
            }
            val time2 = time - 0xB
            if (time2 >= 0) {
                if (time1 == 0x18) {
                    lcd.setBlendTargetA(1, 0, 0, 0, 0)
                    lcd.setBlendTargetB(0, 0, 0, 1, 0)
                }
                if (time1 > 0x17) {
                    val a = Interpolate.interpolate(Interpolate.LINEAR, 0x10, 0, time - 0x1B, 8)
                    lcd.setBlendConfig(1, a, 0x10 - a, 8)
                }
                val ret = Interpolate.interpolate(Interpolate.SQUARE, st.unk36, 0x1BC, time2, 0x18)
                tsaModifyFirstPal(ret, st.unk32, 0, genericBuffer, 0, 0x800, setPal = false)
                st.unk32 = ret.toShort().toInt()
                if (time2 == 0x18) {
                    hw.hblank.setPrimary(null)
                    st.timer = 0
                    engine.breakProc(proc)
                    return
                }
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Shows only BG3 and OBJ and darkens everything to black over 8 steps, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C84D8
     */
    public fun fadeToDark(proc: Proc) {
        val lcd = hardware.lcd
        val st = proc.state as State
        if (st.timer == 0) {
            lcd.setDispEnable(0, 0, 0, 1, 1)
            lcd.setBlendConfig(3, 8, 8, 0)
            lcd.setBlendTargetA(1, 1, 1, 1, 1)
        }
        lcd.blendY = Interpolate.interpolate(Interpolate.LINEAR, 0, 0x10, st.timer, 8)
        if (st.timer == 8) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Sets up the cast scroll display: [type] 0 shows BG2 only; otherwise BG0..3 get priorities
     * 0..3, BG1 shows the shining ring (tiles 0x4000, map 0xF000, palettes 8..11) blended over BG2.
     * Either way BG2 shows the scroll lines (tiles 0x8000, map 0xE800, palettes 0..7).
     *
     * Ported from src/opanim-main.c: sub_80C72A4
     */
    public fun setupCastDisplay(type: Int) {
        val hw = hardware
        val lcd = hw.lcd
        val g = cast ?: error("OpAnim.cast graphics not supplied")
        lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
        if (type and 0xFF == 0) {
            lcd.setDispEnable(0, 0, 1, 0, 0)
        } else {
            for (bg in 0..3) lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or bg
            lcd.setDispEnable(1, 0, 0, 0, 0)
            hw.setTileDataOffset(1, 0x4000)
            hw.setMapDataOffset(1, 0xF000)
            lcd.setBlendTargetA(0, 1, 0, 0, 0)
            lcd.setBlendTargetB(0, 0, 1, 0, 0)
            copyToPal(g.ringPal, 0x20 * 8, 0x20 * 4)
            lcd.setBgPosition(1, 0, 0)
            lcd.setWinEnable(0, 0, 0)
        }
        hw.setTileDataOffset(2, 0x8000)
        hw.setMapDataOffset(2, 0xE800)
        copyToPal(g.scrollPal, 0, 0x20 * 8)
    }

    /**
     * Loads the scroll lines between [old] and [new] (in pixels, 8 per line, 0x15 lines ahead)
     * and scrolls BG2 to `0x60 - new`.
     *
     * Ported from src/opanim-main.c: OpAnim1_UpdateScroll
     */
    public fun updateScroll(new: Int, old: Int) {
        for (i in (old shr 3) + 0x15 until (new shr 3) + 0x15) updateScrollOneLine(i)
        hardware.lcd.setBgPosition(2, 0, 0x60 - new)
    }

    /**
     * Sets up the cast display (type `unk46`) and loads the scroll lines up to `unk38`.
     *
     * Ported from src/opanim-main.c: sub_80C8564
     */
    public fun setupCastScroll(proc: Proc) {
        val st = proc.state as State
        setupCastDisplay(st.unk38to46[4])
        updateScroll(st.unk38to46[0], st.unk38to46[1])
    }

    /** `struct ProcOpAnimBlend` (`gUnknown_08AA705C`, `gUnknown_08AA707C`). */
    public class BlendState {
        /** `unk4C`. */
        public var timer: Int = 0
    }

    /** `gUnknown_08AA705C`: lifts a darkening of BG2 over 0x28 steps; resets the blend when ended. */
    public val castDarkenScript: ProcScript = ProcScript(
        "gUnknown_08AA705C",
        listOf(
            setEndCb(ProcFunc { hardware.lcd.setBlendConfig(0, 0x10, 0, 0) }), // sub_80C85FC
            call(ProcFunc { castDarkenInit(it) }),
            repeat(ProcFunc { castDarkenMain(it) }),
            end(),
        ),
        newState = { BlendState() },
    )

    /**
     * Darkens BG2 by 14/16 and clears the timer.
     *
     * Ported from src/opanim-main.c: sub_80C8580
     */
    public fun castDarkenInit(proc: Proc) {
        hardware.lcd.setBlendConfig(3, 8, 8, 14)
        hardware.lcd.setBlendTargetA(0, 0, 1, 0, 0)
        (proc.state as BlendState).timer = 0
    }

    /**
     * Lowers the darkening from 14 to 0 over 0x28 steps, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C85B0
     */
    public fun castDarkenMain(proc: Proc) {
        val st = proc.state as BlendState
        hardware.lcd.setBlendConfig(3, 8, 8, Interpolate.interpolate(Interpolate.LINEAR, 0xE, 0, st.timer, 0x28))
        if (st.timer == 0x28) engine.breakProc(proc) else st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Scrolls the cast lines (`unk38` from 0 to 0x270 over 0x430 steps of `unk3C`) while loading
     * Joshua's scene and the shining ring over steps 1..6; at step 0x28 ends the darken procs and
     * breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8690
     */
    public fun joshuaScroll(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val vram = hw.vram
        val g = cast ?: error("OpAnim.cast graphics not supplied")
        advanceCastScroll(st)
        fun dec(b: OpAnimHs.Blob, dst: ByteArray, at: Int) = Decompress.into(b.data, b.offset, dst, at)
        when (st.timer) {
            1 -> {
                dec(g.characterBg.first, vram, 0x2000 + hw.tileDataOffset(3))
                val t = Decompress.decode(g.characterBg.second.data, g.characterBg.second.offset)
                val map = hw.mapBuffer(3)
                for (i in 0 until minOf(t.size / 2, map.size)) {
                    map[i] = ((t[2 * i].toInt() and 0xFF) or ((t[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
                }
                for (i in 0 until 0x280) map[i] = (map[i] + 0xE100).toShort()
                hw.enableBgSyncByMask(8)
            }
            2, 3, 4 -> loadCastSceneStep(g.joshua, st.timer - 1)
            5 -> dec(g.ring.first, vram, 0x4000)
            6 -> {
                dec(g.ring.second, vram, 0xF000)
                for (i in 0 until 0x280) {
                    val a = 0xF000 + 2 * i
                    val v = u16(vram, a) - 0x8000
                    vram[a] = v.toByte()
                    vram[a + 1] = (v shr 8).toByte()
                }
            }
        }
        if (st.timer == 0x28) {
            engine.endEach(castDarkenScript)
            engine.endEach(castBrightenScript)
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    private fun castGfx(): CastGfx = cast ?: error("OpAnim.cast graphics not supplied")

    /** One cast scroll step: loads lines up to `unk38`, then moves `unk38` along 0..0x270 by `unk3C`. */
    private fun advanceCastScroll(st: State) {
        val u = st.unk38to46
        updateScroll(u[0], u[1])
        u[1] = u[0]
        u[0] = Interpolate.interpolate(Interpolate.LINEAR, 0, 0x270, u[2], 0x430) and 0xFFFF
        u[2] = (u[2] + 1) and 0xFFFF
    }

    /** `gUnknown_08AA704C`: scrolls BG3 right by 2 pixels every step, forever. */
    public val bg3ScrollScript: ProcScript = ProcScript(
        "gUnknown_08AA704C",
        listOf(repeat(ProcFunc { scrollBg3() }), end()),
    )

    /**
     * Scrolls BG3 by 2 pixels.
     *
     * Ported from src/opanim-main.c: sub_80C8554
     */
    public fun scrollBg3() {
        val lcd = hardware.lcd
        lcd.bgHofs[3] = (lcd.bgHofs[3] + 2) and 0xFFFF
    }

    /**
     * Shows all layers with BG2 (the scroll lines) on top, fully blended over BG3.
     *
     * Ported from src/opanim-main.c: sub_80C9DF0
     */
    public fun showCastScroll() {
        val lcd = hardware.lcd
        lcd.setBlendConfig(1, 0x10, 0, 8)
        lcd.setBlendTargetA(0, 0, 1, 0, 0)
        lcd.setBlendTargetB(0, 0, 0, 1, 0)
        lcd.setDispEnable(1, 1, 1, 1, 1)
        setBgPriorities(1, 2, 0, 3)
    }

    private fun setBgPriorities(p0: Int, p1: Int, p2: Int, p3: Int) {
        val c = hardware.lcd.bgCnt
        c[0] = (c[0] and 3.inv()) or p0
        c[1] = (c[1] and 3.inv()) or p1
        c[2] = (c[2] and 3.inv()) or p2
        c[3] = (c[3] and 3.inv()) or p3
    }

    /**
     * Scrolls the cast lines and fades BG2's blend from 16/0 to 0/16 over 0x10 steps, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C9E6C
     */
    public fun fadeInCastScroll(proc: Proc) {
        val st = proc.state as State
        val lcd = hardware.lcd
        advanceCastScroll(st)
        if (st.timer < 0x11) {
            val a = Interpolate.interpolate(Interpolate.LINEAR, 0x10, 0, st.timer, 0x10)
            lcd.blendCoeffA = a
            lcd.blendCoeffB = 16 - a
            if (st.timer == 0x10) {
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * BG0..3 priorities 0..3, BG1 (tiles 0, map 0xE000) blended over BG3, BG2 hidden.
     *
     * Ported from src/opanim-main.c: sub_80C9B6C
     */
    public fun setupCastSceneDisplay() {
        val hw = hardware
        val lcd = hw.lcd
        setBgPriorities(0, 1, 2, 3)
        lcd.setBlendConfig(1, 0x10, 0, 8)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 0, 1, 0)
        lcd.setDispEnable(1, 1, 0, 1, 1)
        hw.setTileDataOffset(1, 0)
        hw.setMapDataOffset(1, 0xE000)
    }

    /**
     * Sets up a character's scene: display ([setupCastSceneDisplay]), its palettes 0..10 and
     * scroll parameters; clears `unk30/32`. Each C function hard-codes one [CastScene].
     *
     * Ported from src/opanim-main.c: sub_80C9C08, sub_80C9C5C, sub_80C9CAC, sub_80C9CFC, sub_80C9D4C, sub_80C9DA0
     */
    public fun setupCastScene(proc: Proc, scene: CastScene) {
        val st = proc.state as State
        setupCastSceneDisplay()
        copyToPal(scene.pal, 0, 0x160)
        st.unk47 = if (scene.fromLeft) 1 else 0
        st.unk48 = 0
        st.unk4C = 0x800
        st.unk40to44[2] = scene.offset
        st.unk40to44[1] = scene.base
        st.unk40to44[0] = scene.distance
        st.unk30 = 0
        st.unk32 = 0
    }

    /** Steps 1..3 of a scene load: image + tilemap 1, image + tilemap 2, clear BG0/1 and VRAM 0xD800. */
    private fun loadCastSceneStep(scene: CastScene, step: Int) {
        val hw = hardware
        val vram = hw.vram
        fun dec(b: OpAnimHs.Blob, dst: ByteArray, at: Int) = Decompress.into(b.data, b.offset, dst, at)
        when (step) {
            1 -> {
                dec(scene.first.first, vram, hw.tileDataOffset(0))
                dec(scene.first.second, genericBuffer, 0)
            }
            2 -> {
                dec(scene.second.first, vram, 0x2000 + hw.tileDataOffset(0))
                dec(scene.second.second, genericBuffer, 0x800)
            }
            3 -> {
                hw.mapBuffer(0).fill(0)
                hw.mapBuffer(1).fill(0)
                vram.fill(0, 0xD800, 0xE800)
            }
        }
    }

    /**
     * Scrolls the cast lines while loading [scene] over steps 1..3; at step 0x28 breaks (the
     * first, L'Arachel's, also ends the darken proc). Each C function hard-codes one [CastScene].
     *
     * Ported from src/opanim-main.c: sub_80C883C, sub_80C8934, sub_80C8A20, sub_80C8B0C, sub_80C8BF8
     */
    public fun loadCastScene(proc: Proc, scene: CastScene, endDarken: Boolean) {
        val st = proc.state as State
        advanceCastScroll(st)
        loadCastSceneStep(scene, st.timer)
        if (st.timer == 0x28) {
            if (endDarken) engine.endEach(castDarkenScript)
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Scrolls the scene's BG0 in over 0x1E steps (from the left if `unk47`, else from the right),
     * then draws its BG1 copy at the end position and breaks.
     *
     * Ported from src/opanim-main.c: sub_80C9FF8
     */
    public fun castSceneScrollIn(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val u = st.unk40to44
        advanceCastScroll(st)
        hw.enableBgSyncByMask(1)
        castBg0(st, Interpolate.interpolate(Interpolate.RSQUARE, 0, u[0], st.timer, 0x1E))
        if (st.timer == 0x1E) {
            hw.enableBgSyncByMask(2)
            st.unk32 = 0
            castBg1(st, u[1] + 240 - u[0])
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /** The scene's BG0 (front copy) to [x], from `unk30`: `TsaModifyFirstPalMaybe` / `sub_80C689C` by `unk47`. */
    private fun castBg0(st: State, x: Int) {
        val off = st.unk40to44[2].toShort().toInt()
        if (st.unk47 != 0) tsaModifyFirstPal(x, st.unk30, 0, genericBuffer, st.unk48, st.unk4C, setPal = false)
        else scrollInFirstPal(x, st.unk30, off, 0, genericBuffer, st.unk48, st.unk4C, setPal = false)
        st.unk30 = x.toShort().toInt()
    }

    /** The scene's BG1 (palette-15 shadow) to [x], from `unk32`, mirrored relative to [castBg0]. */
    private fun castBg1(st: State, x: Int) {
        val off = st.unk40to44[2].toShort().toInt()
        if (st.unk47 != 0) scrollInFirstPal(x, st.unk32, off, 1, genericBuffer, st.unk48, st.unk4C, setPal = true)
        else tsaModifyFirstPal(x, st.unk32, 1, genericBuffer, st.unk48, st.unk4C, setPal = true)
        st.unk32 = x.toShort().toInt()
    }

    /** `(240 - unk40) + unk42 + lin(0, 0x60, t, 0x3C) + sq(0, 0x60, t, 0x3C)`: BG1's drift. */
    private fun castShadowX(st: State, t: Int): Int {
        val u = st.unk40to44
        return 240 - u[0] + u[1] + Interpolate.interpolate(Interpolate.LINEAR, 0, 0x60, t, 0x3C) +
            Interpolate.interpolate(Interpolate.SQUARE, 0, 0x60, t, 0x3C)
    }

    /**
     * Holds the scene for 0x1E steps: BG0 drifts 0x10 pixels, BG1 (the shadow) drifts away and its
     * blend falls from 8/8.
     *
     * Ported from src/opanim-main.c: sub_80CA10C
     */
    public fun castSceneHold(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val u = st.unk40to44
        advanceCastScroll(st)
        val a = Interpolate.interpolate(Interpolate.SQUARE, 8, 0, st.timer, 0x28)
        hw.lcd.setBlendConfig(1, a, 16 - a, 8)
        hw.enableBgSyncByMask(1)
        castBg0(st, Interpolate.interpolate(Interpolate.LINEAR, u[0], u[0] + 0x10, st.timer, 0x1E))
        hw.enableBgSyncByMask(2)
        castBg1(st, castShadowX(st, st.timer))
        if (st.timer == 0x1E) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Continues [castSceneHold]'s shadow for 0xB more steps, then hides BG1 and blends BG0 over
     * BG3 at 16/0.
     *
     * Ported from src/opanim-main.c: sub_80CA26C
     */
    public fun castShadowOut(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val lcd = hw.lcd
        if (st.timer == 0xB) {
            lcd.setDispEnable(1, 0, 0, 1, 1)
            lcd.setBlendTargetA(1, 0, 0, 0, 0)
            lcd.setBlendTargetB(0, 0, 0, 1, 0)
            lcd.setBlendConfig(1, 0x10, 0, 8)
            lcd.setDispEnable(1, 0, 0, 1, 1)
        }
        if (st.timer < 0xB) {
            val a = Interpolate.interpolate(Interpolate.SQUARE, 8, 0, st.timer + 0x1E, 0x28)
            lcd.setBlendConfig(1, a, 16 - a, 8)
            hw.enableBgSyncByMask(2)
            castBg1(st, castShadowX(st, st.timer + 0x1E))
        }
    }

    /**
     * Scrolls the scene's BG0 out over 0x1E steps while fading it out from step 0xF, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80CA3B8
     */
    public fun castSceneScrollOut(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val u = st.unk40to44
        castShadowOut(proc)
        advanceCastScroll(st)
        if (st.timer - 0xF >= 0) {
            val a = Interpolate.interpolate(Interpolate.LINEAR, 0x10, 0, st.timer - 0xF, 0xF)
            hw.lcd.blendCoeffA = a
            hw.lcd.blendCoeffB = 16 - a
        }
        hw.enableBgSyncByMask(1)
        castBg0(st, Interpolate.interpolate(Interpolate.SQUARE, u[0] + 0x10, u[0] * 2 + 0x10, st.timer, 0x1E))
        if (st.timer == 0x1E) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Reloads the scroll lines' palettes and shows BG2 (on top) blended over BG3 at 0/16.
     *
     * Ported from src/opanim-main.c: sub_80C9EE8
     */
    public fun showCastScrollFaded() {
        val g = cast ?: error("OpAnim.cast graphics not supplied")
        val lcd = hardware.lcd
        copyToPal(g.scrollPal, 0, 0x100)
        lcd.setBlendConfig(1, 0, 0x10, 8)
        lcd.setBlendTargetA(0, 0, 1, 0, 0)
        lcd.setBlendTargetB(0, 0, 0, 1, 0)
        lcd.setDispEnable(0, 0, 1, 1, 1)
        setBgPriorities(1, 2, 0, 3)
    }

    /**
     * Scrolls the cast lines and raises BG2's blend from 0/16 to 16/0 over 0x10 steps, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C9F7C
     */
    public fun fadeOutCastScene(proc: Proc) {
        val st = proc.state as State
        val lcd = hardware.lcd
        advanceCastScroll(st)
        if (st.timer < 0x11) {
            val a = Interpolate.interpolate(Interpolate.LINEAR, 0, 0x10, st.timer, 0x10)
            lcd.blendCoeffA = a
            lcd.blendCoeffB = 16 - a
            if (st.timer == 0x10) {
                st.timer = 0
                engine.breakProc(proc)
                return
            }
        }
        st.timer = (st.timer + 1) and 0xFFFF
    }

    /** `gUnknown_08AA707C`: darkens BG2 from 0 to 12/16 over 0x28 steps; resets the blend when ended. */
    public val castBrightenScript: ProcScript = ProcScript(
        "gUnknown_08AA707C",
        listOf(
            setEndCb(ProcFunc { hardware.lcd.setBlendConfig(0, 0x10, 0, 0) }), // sub_80C8684
            call(ProcFunc { castFadeOutInit(it) }),
            repeat(ProcFunc { castFadeOutMain(it) }),
            end(),
        ),
        newState = { BlendState() },
    )

    /**
     * Sets a darken blend (0) on BG2 and clears the timer.
     *
     * Ported from src/opanim-main.c: sub_80C8608
     */
    public fun castFadeOutInit(proc: Proc) {
        hardware.lcd.setBlendConfig(3, 8, 8, 0)
        hardware.lcd.setBlendTargetA(0, 0, 1, 0, 0)
        (proc.state as BlendState).timer = 0
    }

    /**
     * Raises the darkening from 0 to 12 over 0x28 steps, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8638
     */
    public fun castFadeOutMain(proc: Proc) {
        val st = proc.state as BlendState
        hardware.lcd.setBlendConfig(3, 8, 8, Interpolate.interpolate(Interpolate.LINEAR, 0, 12, st.timer, 0x28))
        if (st.timer == 0x28) engine.breakProc(proc) else st.timer = (st.timer + 1) and 0xFFFF
    }

    /**
     * Keeps scrolling the cast lines for 0x28 steps, then breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8CE4
     */
    public fun castScrollEnd(proc: Proc) {
        val st = proc.state as State
        advanceCastScroll(st)
        if (st.timer == 0x28) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Ends the darkening proc and shows only OBJ, with no blend.
     *
     * Ported from src/opanim-main.c: sub_80CA4A4
     */
    public fun showObjOnly() {
        engine.endEach(castBrightenScript)
        hardware.lcd.setDispEnable(0, 0, 0, 0, 1)
        hardware.lcd.setBlendConfig(0, 0x10, 0, 0)
    }

    /**
     * Puts sparkle sprite [kind] (0..4: size class) variant [variant] at ([x], [y]) on layer 0,
     * palette 3.
     *
     * Ported from src/opanim-main.c: OpAnimPutObjCommon
     */
    public fun putSparkle(x: Int, y: Int, kind: Int, variant: Int) {
        val chr = SPARKLE_CHR[kind][variant shr 1]
        sprites.putExt(0, x, y, castGfx().sparkles[2 * kind + (variant and 1)], 0x3000 or chr)
    }

    /**
     * After the cast scroll: loads its last lines (steps 1..4), shows the sparkles appearing one by
     * one (step 0x1E onwards), shows BG2 again and fades its palettes from white (step 0x52, 0x60
     * steps), then fades OBJ out over it (step 0xB1, 0x20 steps); breaks at step 0xD1.
     *
     * Ported from src/opanim-main.c: sub_80CA4DC
     */
    public fun sparkles(proc: Proc) {
        val st = proc.state as State
        val hw = hardware
        val lcd = hw.lcd
        when (st.timer) {
            1 -> updateScroll(0x1B0, 0x188)
            2 -> updateScroll(0x1F0, 0x1B0)
            3 -> updateScroll(0x230, 0x1F0)
            4 -> {
                st.unk38to46[1] = 0x230
                st.unk38to46[0] = 0x230
            }
        }
        val t = SPARKLES
        for (i in t.indices step 5) if (st.timer > t[i]) putSparkle(t[i + 1], t[i + 2], t[i + 3], t[i + 4])
        var timer = st.timer - 0x52 // u32 in C: negative values compare as large
        if (timer == 0) {
            lcd.setDispEnable(0, 0, 1, 0, 1)
            hw.setTileDataOffset(2, 0x8000)
            hw.setMapDataOffset(2, 0xE800)
        }
        if (timer in 0 until 0x60) {
            copyToPal(castGfx().scrollPal, 0, 0x100)
            val ret = Interpolate.interpolate(Interpolate.RSQUARE, 0x10, 0xC, timer, 0x5F)
            for (i in 0 until 8) blendPalette(i * 0x10, 0, ret)
        }
        timer = st.timer - 0xB1
        if (timer == 0) {
            lcd.setBlendConfig(1, 0x10, 0, 0)
            lcd.setBlendTargetA(0, 0, 0, 0, 1)
            lcd.setBlendTargetB(0, 0, 1, 0, 0)
        }
        if (timer in 0 until 0x21) {
            val a = Interpolate.interpolate(Interpolate.LINEAR, 0x10, 0, timer, 0x20)
            lcd.blendCoeffA = a
            lcd.blendCoeffB = 16 - a
        }
        if (st.timer == 0xD1) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Resets the blend; the next [setupCastScroll] then uses the full display (`unk46` = 1).
     *
     * Ported from src/opanim-main.c: sub_80CA92C
     */
    public fun endSparkles(proc: Proc) {
        hardware.lcd.setBlendConfig(0, 0x10, 0, 0)
        (proc.state as State).unk38to46[4] = 1
    }

    /**
     * Pulses BG1's (the shining ring's) blend with `unk3E` (period 0x4C), fades the scroll lines'
     * palettes in from black over 0x26 steps and loads [faces] into OBJ VRAM (steps 2..5), then
     * breaks.
     *
     * Ported from src/opanim-main.c: sub_80C8D30
     */
    public fun loadFaces(proc: Proc, faces: List<Face>) {
        val st = proc.state as State
        val g = castGfx()
        val vram = hardware.vram
        pulseRing(st, 0x4C)
        copyToPal(g.scrollPal, 0, 0x100)
        val ret = Interpolate.interpolate(Interpolate.LINEAR, 0xC, 0, st.timer, 0x26)
        for (i in 0 until 8) blendPalette(i * 0x10, 0, ret)
        when (st.timer) {
            2 -> for (at in intArrayOf(0x220, 0x260, 0x2A0, 0x2E0)) copyToPal(g.shadowPal, at, 0x20)
            3, 4, 5 -> {
                val k = st.timer - 3
                val f = faces[k]
                Decompress.into(f.img.data, f.img.offset, vram, OBJ_VRAM + 0x1E00 * k)
                copyToPal(f.pal, 0x200 + 0x40 * k, 0x20)
                Decompress.into(f.shadow.data, f.shadow.offset, vram, OBJ_VRAM + 0xF00 + 0x1E00 * k)
            }
        }
        if (st.timer == 0x26) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Sets up a face screen: ends the darkening proc, shows BG2 and OBJ, with the OBJ window
     * showing BG2 and OBJ blended (4/8) and nothing outside it.
     *
     * Ported from src/opanim-main.c: sub_80CA940
     */
    public fun setupFaceScreen() {
        val lcd = hardware.lcd
        engine.endEach(castBrightenScript)
        lcd.setBgPosition(3, 0, 0)
        lcd.setDispEnable(0, 0, 1, 0, 1)
        lcd.setWinEnable(0, 0, 1)
        lcd.setWObjLayers(0, 0, 1, 0, 1)
        lcd.setWOutLayers(0, 0, 0, 0, 0)
        lcd.setWincntBit(29, true)
        lcd.setWincntBit(21, false)
        lcd.setBlendConfig(1, 4, 8, 8)
        lcd.setBlendTargetA(0, 0, 0, 0, 0)
    }

    /**
     * The position of a sliding face: `0xFFFF` (hidden) before step `d - 0x10`, then [a] + [b] *
     * 240 easing to [a] over 0x10 steps of [c].
     *
     * Ported from src/opanim-main.c: sub_80CA9F8
     */
    public fun faceSlideX(a: Int, b: Int, c: Int, d: Int): Int {
        val v = 0x10 - (d - c)
        if (v < 0) return 0xFFFF
        if (v >= 0x10) return a
        return Interpolate.interpolate(Interpolate.RSQUARE, a + b * 240, a, v, 0x10)
    }

    /**
     * Slides the first face screen's three faces (with shadows and window masks) in from the
     * right, 4 steps apart, over 0x14 steps; then breaks.
     *
     * Ported from src/opanim-main.c: sub_80CAA38
     */
    public fun faces1In(proc: Proc) {
        val st = proc.state as State
        val g = castGfx()
        for (k in 0 until 3) {
            val x = faceSlideX(0x78, -1, st.timer + 10 - 4 * k, 0x14)
            if (x == 0xFFFF) continue
            val y = 0x34 * k - 0x14 // 0xEC, 0x20, 0x54 as u8
            sprites.putExt(1, (x - 0x30) and 0x1FF, y and 0xFF, g.faceObj, 0xF0 * k + (2 * k shl 12))
            sprites.putExt(2, (x - 0x2C) and 0x1FF, 0x400 or (y and 0xFF), g.faceObj, 0x78 + 0xF0 * k + ((2 * k + 1) shl 12))
            sprites.putExt(0, (x - 0x30) and 0x1FF, 0x800 or ((y + 0x18) and 0xFF), g.faceWindowObj, 0)
        }
        if (st.timer == 0x14) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Pulses the ring with [period] and loads [faces] into OBJ VRAM (one per step from step 1);
     * breaks at step [last]. Each C function hard-codes its faces, period and last step.
     *
     * Ported from src/opanim-main.c: sub_80C8ED4, sub_80C9024, sub_80C9100
     */
    public fun loadFacesPulsed(proc: Proc, faces: List<Face>, period: Int, last: Int) {
        val st = proc.state as State
        val vram = hardware.vram
        pulseRing(st, period)
        if (st.timer in 1..faces.size) {
            val k = st.timer - 1
            val f = faces[k]
            Decompress.into(f.img.data, f.img.offset, vram, OBJ_VRAM + 0x1E00 * k)
            copyToPal(f.pal, 0x200 + 0x40 * k, 0x20)
            Decompress.into(f.shadow.data, f.shadow.offset, vram, OBJ_VRAM + 0xF00 + 0x1E00 * k)
        }
        if (st.timer == last) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Slides the second face screen's four faces in diagonally from the corners over 0x12 steps;
     * then breaks.
     *
     * Ported from src/opanim-main.c: sub_80CABB0
     */
    public fun faces2In(proc: Proc) {
        val st = proc.state as State
        val g = castGfx()
        val t = st.timer + 2
        val x1 = faceSlideX(0x42, -1, t, 0x12)
        val y1 = faceSlideX(0x2A, -1, t, 0x12)
        val x2 = faceSlideX(0xAE, 1, t, 0x12)
        val y2 = faceSlideX(0x76, 1, t, 0x12)
        fun put(k: Int, x: Int, y: Int) {
            if (x == 0xFFFF || y == 0xFFFF || y < -0x28 || y >= 0xC9) return
            sprites.putExt(1, (x - 0x30) and 0x1FF, (y - 0x28) and 0xFF, g.faceObj, 0xF0 * k + (2 * k shl 12))
            sprites.putExt(2, (x - 0x2C) and 0x1FF, ((y - 0x28) and 0xFF) or 0x400, g.faceObj,
                0x78 + 0xF0 * k + ((2 * k + 1) shl 12))
            sprites.putExt(0, (x - 0x30) and 0x1FF, ((y - 0x20) and 0xFF) or 0x800, g.faceWindowObj2, 0)
        }
        put(0, x1, y1)
        put(1, x1, y2)
        put(2, x2, y1)
        put(3, x2, y2)
        if (st.timer == 0x12) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Slides the third face screen's two faces in from the left and right over 0x10 steps; then
     * breaks.
     *
     * Ported from src/opanim-main.c: sub_80CAE20
     */
    public fun faces3In(proc: Proc) {
        val st = proc.state as State
        val g = castGfx()
        val t = st.timer + 2
        for (k in 0 until 2) {
            val x = faceSlideX(if (k == 0) 0x3E else 0xB2, if (k == 0) -1 else 1, t, 0x10)
            if (x == 0xFFFF) continue
            val dx = if (k == 0) 0x20 else 0x40
            sprites.putExt(1, (x - dx) and 0x1FF, 0x20, g.faceObj, 0xF0 * k + (2 * k shl 12))
            sprites.putExt(2, (x - dx + 4) and 0x1FF, 0x420, g.faceObj, 0x78 + 0xF0 * k + ((2 * k + 1) shl 12))
            sprites.putExt(0, (x - 0x38) and 0x1FF, 0x838, g.faceWindowObj3, 0)
        }
        if (st.timer == 0x10) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /**
     * Drops the fourth face screen's three faces in from the top, 4 steps apart, over 0xF steps;
     * then breaks.
     *
     * Ported from src/opanim-main.c: sub_80CAF2C
     */
    public fun faces4In(proc: Proc) {
        val st = proc.state as State
        val g = castGfx()
        for (k in 0 until 3) {
            val y = faceSlideX(0x50, -1, st.timer + 10 - 4 * k, 0xF)
            if (y == 0xFFFF || y < -0x28 || y >= 0xC9) continue
            val oy = (y - 0x28) and 0xFF
            sprites.putExt(1, 0x48 * k, oy, g.faceObj, 0xF0 * k + (2 * k shl 12))
            sprites.putExt(2, 0x48 * k + 4, oy or 0x400, g.faceObj, 0x78 + 0xF0 * k + ((2 * k + 1) shl 12))
            sprites.putExt(0, 0x48 * k + 0x10, oy or 0x800, g.faceWindowObj4, 0)
        }
        if (st.timer == 0xF) {
            st.timer = 0
            engine.breakProc(proc)
        } else {
            st.timer = (st.timer + 1) and 0xFFFF
        }
    }

    /** The ring pulse: blend `(i, 16)` with `i` rising 0..12 and falling back as `unk3E` advances. */
    private fun pulseRing(st: State, period: Int) {
        val u = st.unk38to46
        val i = Interpolate.interpolate(Interpolate.LINEAR, 0, 0x1A, u[3] % period, period)
        hardware.lcd.setBlendConfig(1, if (i < 0xD) i else 0x19 - i, 0x10, 8)
        u[3] = (u[3] + 1) and 0xFFFF
    }

    private fun putName(chr: Int) {
        val g = ephraim ?: error("OpAnim.ephraim graphics not supplied")
        sprites.putExt(1, 0x98, 0x88, g.nameObj, (2 shl 12) + chr)
    }

    /**
     * Copies tilemap columns `(start >> 3) + 1 until (end >> 3) + 1` (20 rows each) into BG
     * [bg]'s map buffer, columns 0..31 from the 32-wide map at byte [at] of [src] and 32..63 from
     * the one at [at2] (cleared if null, as are columns outside 0..63), forcing palette 15 if
     * [setPal] (`flag`); then scrolls the BG to `end + 0x10`.
     *
     * Ported from src/opanimfx.c: TsaModifyFirstPalMaybe
     */
    public fun tsaModifyFirstPal(
        end: Int,
        start: Int,
        bg: Int,
        src: ByteArray,
        at: Int,
        at2: Int? = null,
        setPal: Boolean = false,
    ) {
        val dst = hardware.mapBuffer(bg)
        val e = end.toShort().toInt()
        var s = start.toShort().toInt()
        if (s == 0) s -= 8
        for (i in (s shr 3) + 1 until (e shr 3) + 1) firstPalColumn(i, dst, src, at, at2, setPal)
        hardware.lcd.setBgPosition(bg, e + 0x10, 0)
    }

    /**
     * Like [tsaModifyFirstPal] but from the right: columns `(start' >> 3) - 1 downTo` above
     * `(end' >> 3) - 1`, where `x' = w - (x + offset)` with `w` 0x200 if [at2] is set, else 0x100;
     * then scrolls the BG to `end'`.
     *
     * Ported from src/opanimfx.c: sub_80C689C
     */
    public fun scrollInFirstPal(
        end: Int,
        start: Int,
        offset: Int,
        bg: Int,
        src: ByteArray,
        at: Int,
        at2: Int?,
        setPal: Boolean,
    ) {
        val dst = hardware.mapBuffer(bg)
        var s = start.toShort().toInt()
        if (s == 0) s -= 8
        var e = (end.toShort() + offset).toShort().toInt()
        s = (s + offset).toShort().toInt()
        val w = if (at2 != null) 0x200 else 0x100
        e = (w - e).toShort().toInt()
        s = (w - s).toShort().toInt()
        var i = (s shr 3) - 1
        while (i > (e shr 3) - 1) {
            firstPalColumn(i, dst, src, at, at2, setPal)
            i--
        }
        hardware.lcd.setBgPosition(bg, e, 0)
    }

    private fun firstPalColumn(
        i: Int,
        dst: ShortArray,
        src: ByteArray,
        at: Int,
        at2: Int?,
        setPal: Boolean,
    ) {
        if (i < 0 || i >= 0x40 || (i >= 0x20 && at2 == null)) {
            clearFirstPal(dst, i and 0x1F)
            return
        }
        val o = if (i < 0x20) at else at2!!
        if (setPal) setFirstPal(src, o, i, dst, 0xF) else copyFirstPal(src, o, i, dst)
    }


    /** Ported from src/opanimfx.c: CopyFirstPalDirectly */
    private fun copyFirstPal(src: ByteArray, at: Int, i: Int, dst: ShortArray) {
        val c = i and 0x1F
        for (r in 0 until 20) dst[c + r * 0x20] = u16(src, at + 2 * (c + r * 0x20)).toShort()
    }

    /** Ported from src/opanimfx.c: SetFirstPalDirectly */
    private fun setFirstPal(src: ByteArray, at: Int, i: Int, dst: ShortArray, pal: Int) {
        val c = i and 0x1F
        for (r in 0 until 20) {
            val v = u16(src, at + 2 * (c + r * 0x20))
            dst[c + r * 0x20] = ((v and 0x7FFF) or (pal shl 12)).toShort()
        }
    }

    /** Ported from src/opanimfx.c: ClearFirstPalDirectly */
    private fun clearFirstPal(dst: ShortArray, c: Int) {
        for (r in 0 until 20) dst[c + r * 0x20] = 0
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /**
     * Copies `gOpAnimSt.y` / `unk06` into `unk08` / `unk0A` every VBlank.
     *
     * Ported from src/opanim-main.c: Proc08AA6D04Main
     */
    public fun proc08AA6D04Main() {
        opAnimSt[4] = opAnimSt[1]
        opAnimSt[5] = opAnimSt[3]
    }

    /** `ProcScr_08AA6D04` (runs on tree 0, VSync). */
    public val proc08AA6D04Script: ProcScript = ProcScript(
        "ProcScr_08AA6D04",
        listOf(repeat(ProcFunc { proc08AA6D04Main() }), end()),
    )

    /**
     * Starts [proc08AA6D04Script] on tree 0.
     *
     * Ported from src/opanim-main.c: NewProc08AA6D04
     */
    public fun newProc08AA6D04() {
        engine.start(proc08AA6D04Script, 0)
    }

    /**
     * Blends the 16 colours of the palette buffer at entry [at] towards [color] by [blend]/16.
     *
     * Ported from src/opanimfx.c: sub_80C69B0
     */
    public fun blendPalette(at: Int, color: Int, blend: Int) {
        val p = hardware.palette
        for (i in at until at + 16) {
            val c = p[i].toInt()
            fun ch(v: Int, shift: Int): Int {
                val a = (v shr shift) and 0x1F
                val b = (color shr shift) and 0x1F
                return ((a * (0x10 - blend) + b * blend) shr 4) and 0x1F
            }
            p[i] = ((ch(c, 10) shl 10) or (ch(c, 5) shl 5) or ch(c, 0)).toShort()
        }
    }

    /**
     * Starts the fade-in proc with [max] frames as a child of [parent].
     *
     * Ported from src/opanim-main.c: NewProcOpAnimFadeIn
     */
    public fun newFadeIn(max: Int, parent: Proc) {
        (engine.start(fadeInScript, parent).state as FadeInState).max = max.toShort().toInt()
    }

    /**
     * Brightness-fade setup: effect 2, BLDY 0, all layers first target.
     *
     * Ported from src/opanim-main.c: OpAnimAllBlack
     */
    public fun allBlack(proc: Proc) {
        val lcd = hardware.lcd
        lcd.bldcnt = (lcd.bldcnt and 0xC0.inv()) or (2 shl 6)
        lcd.blendY = 0
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        (proc.state as FadeInState).cur = 0
    }

    /**
     * Fade-in frame: BLDY from 0 to 0x10 over `max` frames, then breaks.
     *
     * Ported from src/opanim-main.c: OpAnimFadeInMain
     */
    public fun fadeInMain(proc: Proc) {
        val st = proc.state as FadeInState
        hardware.lcd.blendY = linear(0, 0x10, st.cur, st.max) and 0xFF
        if (st.cur == st.max) engine.breakProc(proc)
        st.cur = (st.cur + 1).toShort().toInt()
    }

    /** `Interpolate(INTERPOLATE_LINEAR, ...)` (src/bmlib.c), the only method used here. */
    private fun linear(lo: Int, hi: Int, x: Int, xMax: Int): Int =
        if (xMax == 0) hi else lo + (hi - lo) * x / xMax

    private fun setOffsets(tiles: IntArray, maps: IntArray) {
        for (bg in 0..3) {
            hardware.setTileDataOffset(bg, tiles[bg])
            hardware.setMapDataOffset(bg, maps[bg])
        }
    }

    private fun add16(b: ByteArray, o: Int, d: Int) {
        val v = ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)) + d
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }

    /**
     * Copies [size] bytes of [src] to the palette buffer at byte [offset], requests a palette sync
     * and forces entry 0 to [pal].
     *
     * Ported from src/opanimfx.c: CopyToPalOpAnim
     */
    public fun copyToPal(src: ShortArray, offset: Int, size: Int) {
        src.copyInto(hardware.palette, offset shr 1, 0, size shr 1)
        hardware.enablePaletteSync()
        hardware.palette[0] = pal.toShort()
    }

    /**
     * On A, B or Start: sets the next game action to "user skipped", clears [pal] and jumps the op
     * anim to label 99.
     *
     * Label 99 is not ported yet: the jump is a no-op.
     *
     * Partially ported from src/opanimfx.c: OpAnimfxTerminatorMain
     */
    public fun terminatorMain(proc: Proc) {
        if (keys.newKeys and (A_BUTTON or B_BUTTON or START_BUTTON) != 0) {
            gameControl.setNextGameActionId(GameControl.GAME_ACTION_USR_SKIPPED)
            pal = 0
            engine.goto(proc.parent!!, 99)
        }
    }

    private companion object {
        /** `OpAnimPutObjCommon`'s tile numbers by kind and `variant >> 1`. */
        val SPARKLE_CHR = arrayOf(
            intArrayOf(0x8A, 0xB2),
            intArrayOf(0xDA, 0xF2),
            intArrayOf(0x10A, 0x116, 0x13A),
            intArrayOf(0x122, 0x126, 0x12E),
            intArrayOf(0x12A, 0x12C, 0x132, 0x134, 0x136, 0x138),
        )

        /** `sub_80CA4DC`'s sparkles in call order: (shown after step, x, y, kind, variant). */
        val SPARKLES = intArrayOf(
            0x8E, 0xAE, 10, 4, 9, 0x8D, 0x38, 0x28, 4, 7, 0x8C, 0x9C, 0x3A, 3, 3, 0x8B, 0x22, 0, 4, 1,
            0x8A, 0xD2, 0x10, 4, 5, 0x89, 0xC, 0x1A, 3, 1, 0x88, 0xCC, 0x2A, 3, 4, 0x87, 0x60, 0, 4, 0,
            0x86, 0xE4, 0x10, 4, 0xB, 0x85, 0x58, 0x1E, 3, 3, 0x84, 0x54, 0x2E, 2, 1, 0x83, 0, 2, 4, 2,
            0x82, 0xC6, 0x18, 4, 9, 0x81, 0x90, 0x22, 3, 5, 0x80, 0xA8, 0x44, 2, 0, 0x7F, 0xCA, 2, 4, 7,
            0x7E, 0x26, 0x18, 4, 2, 0x7D, 0x80, 0x26, 3, 0, 0x7B, 0x80, 0x32, 2, 5, 0x79, 0x86, 6, 4, 0,
            0x77, 0x98, 0x16, 4, 1, 0x75, 0xE8, 0x16, 3, 3, 0x73, 100, 0x48, 2, 1, 0x71, 0x70, 6, 4, 10,
            0x6F, 0x40, 0x14, 4, 8, 0x6D, 0, 0x2E, 3, 4, 0x6B, 0x2C, 0x4A, 2, 4, 0x69, 0x40, 8, 4, 6,
            0x67, 0x78, 0x1A, 4, 2, 0x65, 0x24, 0x32, 3, 2, 0x62, 0xE, 0x4E, 2, 0, 0x5F, 0x16, 10, 4, 4,
            0x5C, 0xB4, 0x28, 4, 1, 0x59, 0x42, 0x36, 3, 1, 0x55, 0xDC, 0x32, 2, 2, 0x51, 200, 0x48, 1, 0,
            0x4C, 0x38, 0x28, 4, 0, 0x47, 0x8C, 0x54, 1, 2, 0x41, 0x10, 100, 1, 1, 0x37, 0, 0x7E, 0, 1,
            0x31, 0xA0, 0x78, 0, 2, 0x1D, 0x58, 0x70, 0, 0,
        )
        const val OBJ_VRAM = 0x10000
        const val BLD_BACKDROP_A = 1 shl 5
        const val BLD_BACKDROP_B = 1 shl 13
        const val DISPCNT_OBJ_1D = 0x40
        const val A_BUTTON = 0x1
        const val B_BUTTON = 0x2
        const val START_BUTTON = 0x8
    }
}
