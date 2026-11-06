package fe8.testing

import fe8.game.scene.Talk
import fe8.game.scene.TalkFlags
import fe8.game.scene.TalkHost
import fe8.game.scene.TalkState
import fe8.game.text.Fe8Text
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.text.TextHost
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hand-computed checks of the parts of [Talk] and [TalkState] that the reference run does not
 * exercise (flags, delay, colour, talk lock, the bubble width formula). No ROM or trace is
 * involved, so none of this verifies a port; the traced behaviour is in [TalkRomTest].
 */
class TalkUnitTest {
    @Test
    fun `state encodes and decodes the 0x84 byte layout`() {
        val src = ByteArray(TalkState.SIZE) { (it * 7 + 3).toByte() }
        val s = TalkState()
        s.decode(src)
        assertEquals(src[0x0F].toInt(), s.speakingFaceSlot, "s8 field keeps its sign")
        assertEquals(src[0x11].toInt() and 0xFF, s.activeFaceSlot, "u8 field")
        val out = ByteArray(TalkState.SIZE)
        s.encode(out)
        for (i in 0 until TalkState.SIZE) {
            if (i in 0x38 until 0x3C) continue
            assertEquals(src[i], out[i], "byte $i")
        }
        s.speakingFaceSlot = -1
        s.encode(out)
        assertEquals(0xFF.toByte(), out[0x0F])
    }

    @Test
    fun `flags delay and colour setters follow scene c`() {
        val (talk, _) = make()
        talk.setTalkFlag(TalkFlags.NOSKIP or TalkFlags.SPRITE)
        assertEquals(TalkFlags.NOSKIP, talk.checkTalkFlag(TalkFlags.NOSKIP))
        talk.clearTalkFlag(TalkFlags.NOSKIP)
        assertEquals(0, talk.checkTalkFlag(TalkFlags.NOSKIP))
        assertEquals(TalkFlags.SPRITE, talk.state.config)
        talk.clearAllTalkFlags()
        assertEquals(0, talk.state.config)
        talk.setTalkPrintDelay(-3)
        assertEquals(0, talk.state.printDelay)
        talk.setTalkPrintDelay(6)
        assertEquals(6, talk.state.printDelay)
        talk.state.lines = 2
        talk.setTalkPrintColor(4)
        assertEquals(4, talk.texts[0].colorId)
        assertEquals(4, talk.texts[1].colorId)
        assertEquals(0, talk.texts[2].colorId)
        talk.state.printColor = 1
        talk.toggleTalkPrintColor()
        assertEquals(4, talk.state.printColor)
        talk.toggleTalkPrintColor()
        assertEquals(1, talk.state.printColor)
        talk.setTalkLines(3)
        assertEquals(3, talk.state.lines)
    }

    @Test
    fun `lock blocks the parent until resumeTalk`() {
        val (talk, engine) = make()
        val parent = engine.start(
            ProcScript("p", listOf(ProcScripts.yield(), ProcScripts.end())), 3,
        )
        assertFalse(talk.isTalkLocked())
        talk.lockTalk(parent)
        assertTrue(talk.isTalkLocked())
        assertEquals(1, parent.lockCnt)
        talk.resumeTalk()
        assertFalse(talk.isTalkLocked())
        assertEquals(0, parent.lockCnt)
    }

    @Test
    fun `bubble width is 2 plus the length rounded up to tiles of 8`() {
        val (talk, engine) = make()
        val proc = engine.start(ProcScript("p", listOf(ProcScripts.yield(), ProcScripts.end())), 3)
        host.len = 160 // 160 px -> 20 tiles + 2
        talk.state.activeFaceSlot = 1
        talk.state.speakingFaceSlot = 0
        talk.state.str = 0x100
        assertTrue(talk.talkPrepNextChar(proc))
        assertEquals(22, talk.state.activeWidth)
        assertEquals(0x100, host.lenArg)
        host.len = 161
        talk.state.strBackup = 0x200 // a nested string is measured instead
        assertTrue(talk.talkPrepNextChar(proc))
        assertEquals(23, talk.state.activeWidth)
        assertEquals(0x200, host.lenArg)
        // NOBUBBLE skips the bubble branch.
        talk.state.lines = 2
        talk.setTalkFlag(TalkFlags.NOBUBBLE)
        assertFalse(talk.talkPrepNextChar(proc))
    }

    private val host = StubHost()

    private fun make(): Pair<Talk, ProcEngine> {
        val engine = ProcEngine()
        val ts = TextSystem(ByteArray(0), host, Fe8Text.CONFIG)
        return Talk(ts, engine, host) to engine
    }

    private class StubHost : TextHost, TalkHost {
        var len = 0
        var lenArg = 0
        override fun language() = 1
        override fun applyTextPalette(talk: Boolean, palid: Int) {}
        override fun readVram32(addr: Int) = 0
        override fun writeVram32(addr: Int, value: Int) {}
        override fun clearVram(dest: Int, byteCount: Int) {}
        override fun backgroundTileDataOffset(bg: Int) = 0
        override fun decompressToVram(src: Int, dest: Int) {}
        override fun applyPalette(src: Int, palId: Int) {}
        override fun loadObjUiGfx() {}
        override fun setBgPosition(bg: Int, x: Int, y: Int) {}
        override fun startTalkSkipListener() {}
        override fun bg0Tilemap() = ShortArray(0x400)
        override fun getStrTalkLen(str: Int, isBubbleOpen: Boolean): Int {
            lenArg = str
            return len
        }
        override fun clearTalkBubble() {}
        override fun startTalkOpen(faceSlot: Int, parent: Proc) {}
        override fun setTalkFaceLayer(faceSlot: Int, flag: Int) {}
        override fun startTalkShiftClear(parent: Proc) {}
        override fun talkBgSync(bg: Int) {}
        override fun setTalkFaceMouthMove(faceSlot: Int) {}
    }
}
