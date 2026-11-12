package fe8.testing

import fe8.game.scene.TalkHost
import gbafe.proc.Proc
import gbafe.text.Text

/**
 * [TalkHost] whose interpreter-side services do nothing, for tests that only exercise the talk
 * set-up half. Members the test does not use fail loudly where a silent default would hide a
 * missing stub.
 */
interface TalkHostDefaults : TalkHost {
    override fun stringArray(addr: Int): ByteArray = error("stringArray unused")

    override fun stringOffset(addr: Int): Int = error("stringOffset unused")

    override fun isTalkFaceMoving(): Boolean = false

    override fun setTalkFaceNoMouthMove(faceSlot: Int) {}

    override fun talkLoadFace(proc: Proc) {}

    override fun startFaceFadeOut(face: Int) {}

    override fun startTemporaryLock(proc: Proc, frames: Int) {}

    override fun moveTalkFace(from: Int, to: Int) {}

    override fun setFaceBlinkControl(face: Int, mode: Int) {}

    override fun setFaceEyes(face: Int, mode: Int) {}

    override fun startTalkChoice(
        buySell: Boolean,
        text: Text,
        tm: ShortArray,
        offset: Int,
        defaultChoice: Int,
        color: Int,
        parent: Proc,
    ) {}

    override fun startTalkShiftClearAll(parent: Proc) {}

    override fun setPaletteColor(bank: Int, index: Int, color: Int) {}

    override fun talkFlushAllLine() {}

    override fun endTalkSkipListener() {}

    override fun endTalkShiftClearAll() {}

    override fun numberToStringAscii(number: Int, buf: ByteArray) {}

    override fun tacticianName(): Int = 0

    override fun textDisplaySpeed(): Int = 0

    override fun gameClock(): Int = 0

    override fun newKeys(): Int = 0

    override fun playSoundEffect(song: Int) {}

    override fun putSprite(layer: Int, x: Int, y: Int, lutIndex: Int, oam2: Int) {}
}
