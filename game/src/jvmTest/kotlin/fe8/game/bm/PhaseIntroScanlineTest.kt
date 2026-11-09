package fe8.game.bm

import fe8.game.RomView
import gba.render.HBlankLines
import gba.render.LineTable
import gba.render.RenderState
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PhaseIntroScanlineTest {
    @Test
    fun `vcount blend bands reach the intended visible lines`() {
        val rom = RomView(emptyList())
        val bm = BmSt()
        val hw = BgHardware()
        val intro = PhaseIntro(
            ProcEngine(), bm, PlaySt(), UnitTable(rom), rom, hw, OamBuffer(), SpriteLayers(),
        )
        intro.initDisp()
        bm.altBlendACa = 3
        bm.altBlendACb = 13
        bm.altBlendBCa = 7
        bm.altBlendBCb = 9
        val state = RenderState()
        HBlankLines.capture(ByteArray(0x60), state) { vcount, io ->
            hw.hblank.active!!.onHBlank(vcount, io)
        }

        for ((line, middle) in listOf(
            0 to false, 71 to false, 72 to true, 95 to true, 96 to false, 159 to false,
        )) {
            val mask = state.lines.mask[line]
            assertEquals(LineTable.BLEND or LineTable.BLEND_CONTROL,
                mask and (LineTable.BLEND or LineTable.BLEND_CONTROL), "line $line overrides")
            assertEquals(if (middle) 0x3E41 else 0x3C42, state.lines.blendControl[line],
                "line $line blend control")
            assertEquals(if (middle) 3 or (13 shl 8) else 7 or (9 shl 8),
                state.lines.blend[line], "line $line blend coefficients")
        }
    }
}
