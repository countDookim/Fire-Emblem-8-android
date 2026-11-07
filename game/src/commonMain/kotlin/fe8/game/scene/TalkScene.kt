package fe8.game.scene

import fe8.game.RomView
import fe8.game.text.Fe8Text
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem

/**
 * The talk window of a running game: [Talk], [TalkBubble] and [TalkFace] on the game's own
 * [BgHardware] (BG0 text tilemap, BG1 bubble tilemap, VRAM, palette, LCD registers), proc engine,
 * key status, sprite layers and message table. It is the production counterpart of the test rig
 * the talk ports were verified with; it implements the host interfaces they need.
 *
 * Message strings: [stringFromIndex] decodes a message into the shared [MsgStrings] buffer,
 * which is seen at the fake GBA address [MSG_ADDR]; every other address reads the ROM.
 *
 * Stand-ins (no behaviour is invented; each is a no-op or the smallest working substitute):
 *  - face blink and mouth animation procs and the face OBJ output are not modelled by
 *    [TalkFace] (the face is loaded into OBJ VRAM and its palette, but not drawn);
 *  - face fade in is not modelled ([startFaceFadeIn] does nothing, the palette is applied at once);
 *  - [startFaceFadeOut] ends the face after the 8 frames of `EndFaceIn8Frames` without the
 *    palette fade;
 *  - sprite text, talk choices, `[G]` numbers and the tactician name are not ported and fail;
 *  - the portrait of the active unit and the portrait swap are not ported and fail.
 */
public class TalkScene(
    private val rom: RomView,
    private val romBytes: ByteArray,
    private val hw: BgHardware,
    private val procs: ProcEngine,
    private val sprites: SpriteLayers,
    private val keys: KeyStatus,
    private val clockFrames: () -> Int,
    private val messages: MsgStrings,
    private val playSound: (Int) -> Unit,
    private val textSpeed: () -> Int,
    private val setDialogueSkipEvBit: () -> Unit,
) : TextHost, TalkHost, TalkBubbleHost, TalkFaceHost {
    /** The text system of the talk font (`sTalkFont`'s glyph tables). */
    public val text: TextSystem = TextSystem(romBytes, this, Fe8Text.CONFIG)

    /** The talk window. */
    public val talk: Talk = Talk(text, procs, this)

    /** The bubble and shift procs. */
    public val bubble: TalkBubble = TalkBubble(text, procs, talk, this, this)

    /** The faces. */
    public val face: TalkFace = TalkFace(procs, talk, this, this)

    private val skipScript = ProcScript(
        "gProcScr_TalkSkipListener",
        listOf(mark(Talk.PROC_MARK_TALK), repeat(ProcFunc { skipListenerOnIdle(it) }), end()),
    )
    private val lockScript = ProcScript(
        "ProcScr_TemporaryLock",
        listOf(yield(), repeat(ProcFunc { lockOnLoop(it) }), end()),
    ) { LockState() }
    private val faceEndScript = ProcScript(
        "gProcScr_FaceEndIn8Frames",
        listOf(sleep(8), call(ProcFunc { endFace(it) }), end()),
    ) { FaceEndState() }

    private class LockState {
        var frames = 0
    }

    private class FaceEndState {
        var slot = 0
    }

    private val arrowLut: Array<SpriteObject> by lazy {
        Array(ARROW_LUT_SIZE) { rom.objN(rom.u32(ARROW_LUT + 4 * it)) }
    }

    /**
     * `GetStringFromIndex` reduced to what `StartTalkExt` needs: makes message [msg] current in
     * the shared buffer and returns its GBA address ([MSG_ADDR]).
     */
    public fun stringFromIndex(msg: Int): Int {
        messages.getStringFromIndex(msg)
        return MSG_ADDR
    }

    // --- TextHost

    /** The text language: 1 (English, the US ROM). */
    override fun language(): Int = 1

    /** Loads the text (or [talk] text) palette into BG bank [palid], colour 0 transparent. */
    override fun applyTextPalette(talk: Boolean, palid: Int) {
        applyPalette(PAL_TEXT + if (talk) 0x20 else 0, palid)
        hw.palette[palid * 16] = 0
    }

    /** Reads the little-endian word at VRAM address [addr]. */
    override fun readVram32(addr: Int): Int {
        val o = addr - VRAM_BASE
        val v = hw.vram
        return (v[o].toInt() and 0xFF) or ((v[o + 1].toInt() and 0xFF) shl 8) or
            ((v[o + 2].toInt() and 0xFF) shl 16) or ((v[o + 3].toInt() and 0xFF) shl 24)
    }

    /** Writes [value] little-endian at VRAM address [addr]. */
    override fun writeVram32(addr: Int, value: Int) {
        val o = addr - VRAM_BASE
        for (k in 0..3) hw.vram[o + k] = (value shr (8 * k)).toByte()
    }

    /** Zeroes [byteCount] bytes of VRAM from address [dest]. */
    override fun clearVram(dest: Int, byteCount: Int) {
        hw.vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
    }

    // --- TalkHost

    override fun backgroundTileDataOffset(bg: Int): Int = hw.tileDataOffset(bg)

    override fun decompressToVram(src: Int, dest: Int) {
        rom.decompressInto(src, hw.vram, dest - VRAM_BASE)
    }

    override fun applyPalette(src: Int, palId: Int) {
        rom.pal(src, 16).copyInto(hw.palette, palId * 16)
        hw.enablePaletteSync()
    }

    /**
     * Transcribed from src/bm.c: LoadObjUIGfx (not verified: no trace dump holds the OBJ UI tiles
     * of a talk).
     */
    override fun loadObjUiGfx() {
        val buf = ByteArray(MISC_UI_ROWS * MISC_UI_WIDTH_TILES * CHR_SIZE)
        rom.decompressInto(GFX_MISC_UI, buf, 0)
        val line = MISC_UI_WIDTH_TILES * CHR_SIZE
        for (row in 0 until MISC_UI_ROWS) {
            buf.copyInto(hw.vram, OBJ_VRAM + row * CHR_SIZE * 0x20, row * line, (row + 1) * line)
        }
        applyPalette(PAL_MISC_UI, 0x10)
        applyPalette(PAL_MISC_UI + 0x20, 0x11)
    }

    override fun setBgPosition(bg: Int, x: Int, y: Int) {
        hw.lcd.setBgPosition(bg, x, y)
    }

    override fun startTalkSkipListener() {
        procs.start(skipScript, TREE_3)
    }

    override fun bg0Tilemap(): ShortArray = hw.mapBuffer(0)

    override fun stringArray(addr: Int): ByteArray =
        if (addr - MSG_ADDR in 0 until messages.buffer.size) messages.buffer else romBytes

    override fun stringOffset(addr: Int): Int =
        if (addr - MSG_ADDR in 0 until messages.buffer.size) addr - MSG_ADDR else addr - ROM_BASE

    /**
     * Transcribed from src/scene.c: GetStrTalkLen (without the nested-string codes, which
     * [stringArray] cannot resolve; they fail). Not verified in production; the same code drives
     * the traced talk through the test rig.
     */
    override fun getStrTalkLen(str: Int, isBubbleOpen: Boolean): Int {
        val s = talk.state
        val a = stringArray(str)
        var i = stringOffset(str)
        var open = isBubbleOpen
        val speak = s.speakingFaceSlot
        var active = s.activeFaceSlot
        var cur = 0
        var max = 24
        var speaker = speak
        val w = IntArray(1)
        while (true) {
            val c = a[i].toInt() and 0xFF
            when (c) {
                0x00, 0x15 -> return maxOf(max, cur)
                0x01, 0x02 -> {
                    max = maxOf(max, cur)
                    cur = 0
                    i++
                }
                0x04, 0x05, 0x06, 0x07, 0x16, 0x17, 0x1C, 0x1D -> i++
                0x03 -> {
                    cur += 12
                    i++
                }
                in 0x08..0x0F -> {
                    active = c - 8
                    i++
                }
                0x10 -> {
                    while (true) {
                        val d = a[i].toInt() and 0xFF
                        if (d in 0x08..0x0F) {
                            active = d - 8
                            i++
                        } else if (d == 0x10) {
                            i += 3
                        } else {
                            break
                        }
                    }
                }
                0x11 -> {
                    if (active == speaker) return maxOf(max, cur)
                    i++
                }
                0x12, 0x13, 0x14 -> {
                    if (!open) return maxOf(max, cur)
                    i++
                }
                0x18, 0x19, 0x1A, 0x1B -> {
                    cur += 0x80
                    i++
                }
                0x80 -> {
                    i++
                    val d = a[i].toInt() and 0xFF
                    if (d in 0x0A..0x11) {
                        active = d - 0x0A
                        i++
                    } else if (d == 0x05 || d == 0x06 || d == 0x20) {
                        throw NotImplementedError("not ported: nested string in GetStrTalkLen")
                    } else {
                        i++
                    }
                }
                else -> {
                    if (c == 0x81 && (a[i + 1].toInt() and 0xFF) == 0x40) {
                        i += 2
                        cur += 6
                    } else {
                        if (active != speaker && active != 0xFF) {
                            if (!open) {
                                open = true
                                speaker = active
                            } else {
                                return maxOf(max, cur)
                            }
                        }
                        i = text.getCharTextLen(a, i, w)
                        cur += w[0]
                    }
                }
            }
        }
    }

    override fun clearTalkBubble() {
        bubble.clearTalkBubble()
    }

    override fun startTalkOpen(faceSlot: Int, parent: Proc) {
        bubble.startTalkOpen(faceSlot, parent)
    }

    override fun setTalkFaceLayer(faceSlot: Int, flag: Int) {
        face.setTalkFaceLayer(faceSlot, flag)
    }

    override fun startTalkShiftClear(parent: Proc) {
        bubble.startTalkShiftClear(parent)
    }

    /** Transcribed from src/scene.c: TalkBgSync (not verified). */
    override fun talkBgSync(bg: Int) {
        if (talk.checkTalkFlag(TalkFlags.SPRITE) == 0) hw.enableBgSyncByMask(bg)
    }

    override fun setTalkFaceMouthMove(faceSlot: Int) {
        face.setTalkFaceMouthMove(faceSlot)
    }

    override fun isTalkFaceMoving(): Boolean = face.isTalkFaceMoving()

    override fun setTalkFaceNoMouthMove(faceSlot: Int) {
        face.setTalkFaceNoMouthMove(faceSlot)
    }

    override fun talkLoadFace(proc: Proc) {
        face.talkLoadFace(proc)
    }

    /**
     * Stand-in for `StartFaceFadeOut(face)` ([face] is a [TalkFace.FACE_HANDLE] handle): the
     * palette fade to black is not modelled; the face is ended after 8 frames like
     * `EndFaceIn8Frames`.
     */
    override fun startFaceFadeOut(face: Int) {
        val slot = face - TalkFace.FACE_HANDLE
        if (this.face.gFaces[slot] == null) return
        (procs.start(faceEndScript, TREE_3).state as FaceEndState).slot = slot
    }

    private fun endFace(p: Proc) {
        val slot = (p.state as FaceEndState).slot
        val f = face.gFaces[slot] ?: return
        face.gFaces[slot] = null
        procs.end(f)
    }

    override fun startTemporaryLock(proc: Proc, frames: Int) {
        val p = procs.startBlocking(lockScript, proc) ?: return
        (p.state as LockState).frames = frames
    }

    private fun lockOnLoop(p: Proc) {
        val s = p.state as LockState
        if (s.frames == 0) procs.breakProc(p) else s.frames--
    }

    override fun moveTalkFace(from: Int, to: Int) {
        face.moveTalkFace(from, to)
    }

    /** Stand-in: the face blink procs are not ported. */
    override fun setFaceBlinkControl(face: Int, mode: Int) {}

    /** Stand-in: the face eye state is not ported. */
    override fun setFaceEyes(face: Int, mode: Int) {}

    override fun startTalkChoice(
        buySell: Boolean,
        text: Text,
        tm: ShortArray,
        offset: Int,
        defaultChoice: Int,
        color: Int,
        parent: Proc,
    ) {
        throw NotImplementedError("not ported: StartTalkChoice")
    }

    override fun startTalkShiftClearAll(parent: Proc) {
        bubble.startTalkShiftClearAll(parent)
    }

    override fun startTalkSpriteShiftClear(parent: Proc) {
        throw NotImplementedError("not ported: sprite talk")
    }

    override fun talkFlushAllLine() {
        bubble.talkFlushAllLine()
    }

    override fun endTalkSkipListener() {
        procs.endEach(skipScript)
    }

    override fun endTalkShiftClearAll() {
        bubble.endTalkShiftClearAll()
    }

    override fun numberToStringAscii(number: Int, buf: ByteArray) {
        throw NotImplementedError("not ported: NumberToStringAscii")
    }

    override fun tacticianName(): Int {
        throw NotImplementedError("not ported: GetTacticianName")
    }

    /** `GetTextDisplaySpeed`: the frames per character of `gPlaySt.config.textSpeed`. */
    override fun textDisplaySpeed(): Int = DISPLAY_SPEED[textSpeed() and 3]

    override fun gameClock(): Int = clockFrames()

    override fun newKeys(): Int = keys.newKeys

    override fun playSoundEffect(song: Int) {
        playSound(song)
    }

    override fun putSprite(layer: Int, x: Int, y: Int, lutIndex: Int, oam2: Int) {
        sprites.put(layer, x, y, arrowLut[lutIndex], oam2)
    }

    /**
     * Transcribed from src/scene.c: TalkSkipListener_OnIdle (not verified: the traced talk has no
     * B or START press).
     */
    private fun skipListenerOnIdle(proc: Proc) {
        if (procs.find(bubble.shiftClearAllScript) != null) return
        if (procs.find(bubble.shiftClearScript) != null) return
        val s = talk.state
        if (talk.checkTalkFlag(TalkFlags.NOSKIP) == 0 && keys.newKeys and (B_BUTTON or START) != 0) {
            setDialogueSkipEvBit()
            face.setTalkFaceNoMouthMove(s.activeFaceSlot)
            procs.end(proc)
            procs.endEach(talk.talkScript)
            hw.fill(hw.mapBuffer(0), 0)
            hw.fill(hw.mapBuffer(1), 0)
            hw.enableBgSyncByMask(3)
            return
        }
        if (procs.find(talk.waitForInputScript) != null) return
        if (talk.checkTalkFlag(TalkFlags.NOFAST) == 0 && keys.newKeys and ANY_KEY != 0) {
            s.instantScroll = 1
        }
    }

    // --- TalkBubbleHost

    override fun bg1Tilemap(): ShortArray = hw.mapBuffer(1)

    /** Stand-in: the battle animation (deamon) is not ported, so it is never active. */
    override fun isBattleDeamonActive(): Boolean = false

    /** Transcribed from src/scene.c: TalkToggleInvertedPalette (not verified). */
    override fun toggleInvertedPalette(flag: Int) {
        if (flag != 0) {
            applyPalette(PAL_TALK_BUBBLE_INVERTED, 3)
            applyPalette(PAL_TEXT_INVERTED, 2)
        } else {
            applyPalette(Talk.PAL_TALK_BUBBLE, 3)
            applyPalette(PAL_TEXT, 2)
        }
    }

    /** Transcribed from src/scene.c: InitTalkTextWin (not verified). */
    override fun initTalkTextWin(x: Int, y: Int, width: Int, height: Int) {
        val lcd = hw.lcd
        lcd.setWinEnable(1, 0, 0)
        lcd.setWin0Box((x + 1) * 8, (y + 1) * 8, (x + width - 1) * 8, (y + height - 1) * 8)
        lcd.setWin0Layers(1, 1, 1, 1, 1)
        lcd.setWOutLayers(0, 1, 1, 1, 1)
    }

    /** The window-off end of `ClearTalkBubble`. */
    override fun disableTalkWindows() {
        hw.lcd.setWinEnable(0, 0, 0)
    }

    /** Transcribed from src/scene.c: TalkOpen_InitBlend, without TALK_FLAG_8 (not verified). */
    override fun initTalkBlend() {
        val lcd = hw.lcd
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendTargetB(0, 0, 1, 1, 1)
        lcd.setBlendBackdropB(1)
        lcd.setWincntBit(WIN0_ENABLE_BLEND, true)
        lcd.setWincntBit(WOUT_ENABLE_BLEND, true)
        setBlendAlpha(0, 16)
    }

    override fun setBlendAlpha(a: Int, b: Int) {
        hw.lcd.setBlendConfig(1, a, b, 0)
    }

    override fun spriteTextClear(text: Text) {
        throw NotImplementedError("not ported: sprite talk")
    }

    // --- TalkFaceHost

    override fun portraitImage(fid: Int): Int = rom.u32(PORTRAIT_TABLE + (fid - 1) * 0x1C)

    override fun portraitPalette(fid: Int): Int = rom.u32(PORTRAIT_TABLE + (fid - 1) * 0x1C + 8)

    override fun activeUnitPortraitId(): Int {
        throw NotImplementedError("not ported: GetUnitPortraitId(gActiveUnit)")
    }

    /** Stand-in: the face palette fade in is not modelled; the palette was applied at once. */
    override fun startFaceFadeIn(face: Proc) {}

    override fun changeFacePortrait(face: Proc, faceId: Int) {
        throw NotImplementedError("not ported: sub_80066E0")
    }

    /** Addresses and constants of the talk scene. */
    public companion object {
        /** The GBA address the shared message buffer is seen at (`sMsgString.buffer1`). */
        public const val MSG_ADDR: Int = 0x0202A6AC

        private const val ROM_BASE = 0x08000000
        private const val VRAM_BASE = 0x06000000
        private const val OBJ_VRAM = 0x10000
        private const val CHR_SIZE = 0x20
        private const val TREE_3 = 3
        private const val PORTRAIT_TABLE = 0x088ACBE0
        private const val ARROW_LUT = 0x08591430
        private const val ARROW_LUT_SIZE = 16
        private const val PAL_TEXT = 0x0859EF00
        private const val PAL_TEXT_INVERTED = 0x0859EFE0
        private const val PAL_TALK_BUBBLE_INVERTED = 0x0859F000
        private const val GFX_MISC_UI = 0x0859E8E0
        private const val PAL_MISC_UI = 0x0859ED70
        private const val MISC_UI_ROWS = 4
        private const val MISC_UI_WIDTH_TILES = 0x12
        private const val WIN0_ENABLE_BLEND = 5
        private const val WOUT_ENABLE_BLEND = 21
        private const val B_BUTTON = 2
        private const val START = 8
        private const val ANY_KEY = 0xF3
        private val DISPLAY_SPEED = intArrayOf(8, 4, 1, 0)
    }
}
