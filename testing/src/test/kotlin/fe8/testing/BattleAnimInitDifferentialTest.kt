package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BanimInit
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.AnimPool
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `InitMainAnims` (CLOSE arm), `InitBattleAnimFrame`, `InitLeftAnim` and `InitRightAnim`
 * (banim-ekrmain.c) against the two animated battle windows: f1496 -> f1497
 * (`ram_trace_prologue_attack`) and f14467 -> f14468 (`ram_trace_prologue_battle`).
 *
 * Inputs come from the pre-step dump (`gBanimValid`, `gEkrDistanceType`, `gEkrBgPosition`, the
 * `gpBanimModes*` tables and the buffer addresses); the ROM tables come from the runtime ROM.
 * Compared with the post-step dump: `gAnims[0..3]` and per Anim `state`, `xPosition`,
 * `yPosition`, `oam2Base`, `drawLayerPriority`, `nextRoundId`, `currentRoundType`,
 * `pScrStart`, `pImgSheetBuf`, `pSpriteDataPool` and the state2 bits 0x600 (right side, 0x400);
 * `gEkrXPos*`, `gEkrYPos*` (Real and Base) and `gEfxHpLutOff`. `timer`, the rest of `state2` and
 * the script pointer are rewritten by the driver within the same frame and are not compared.
 */
class BattleAnimInitDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun check(run: String, step: Int) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val capture = ref.run(run)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val pre = capture.region(byStep.getValue(step - 1), "ewram")
        val post = capture.region(byStep.getValue(step), "ewram")

        val modesLeft = u32(pre, MODES_LEFT)
        val modesRight = u32(pre, MODES_RIGHT)
        val rom = RomView.ofRom(romFile.readBytes())
        // The mode tables point into the ROM (the loaded banim entry's modes).
        fun table(base: Int): (Int) -> Int = { frame -> rom.u32(base + 4 * frame) }
        val init = BanimInit(
            rom,
            AnimPool(),
            BanimInit.Buffers(
                table(modesLeft), table(modesRight), SCR_LEFT, SCR_RIGHT, DEFAULT_ANIM_SCR,
                IMG_SHEET_LEFT, IMG_SHEET_RIGHT, OAML, OAMR2,
            ),
        )
        init.distanceType = s16(pre, DISTANCE_TYPE)
        init.bgPosition = u32(pre, BG_POSITION)
        for (i in 0..1) init.valid[i] = s16(pre, BANIM_VALID + 2 * i) == 1
        assertEquals(BanimInit.EKR_DISTANCE_CLOSE, init.distanceType)
        assertEquals(listOf(true, true), init.valid.toList())
        for (i in 0 until 4) assertEquals(0, u32(pre, G_ANIMS + 4 * i), "pre gAnims[$i]")

        init.initMainAnims()

        for (i in 0..1) {
            assertEquals(s16(post, X_REAL + 2 * i), init.xPosReal[i], "xPosReal[$i]")
            assertEquals(s16(post, Y_REAL + 2 * i), init.yPosReal[i], "yPosReal[$i]")
            assertEquals(s16(post, X_BASE + 2 * i), init.xPosBase[i], "xPosBase[$i]")
            assertEquals(s16(post, Y_BASE + 2 * i), init.yPosBase[i], "yPosBase[$i]")
            assertEquals(s16(post, HP_LUT_OFF + 2 * i), init.hpLutOff[i], "hpLutOff[$i]")
        }
        for (i in 0 until 4) {
            val p = u32(post, G_ANIMS + 4 * i)
            assertEquals(POOL + STRIDE * i, p, "gAnims[$i]")
            val a = init.anims[i]!!
            assertEquals(u16(post, p), a.state, "anim $i state")
            assertEquals(s16(post, p + 0x02), a.xPosition, "anim $i x")
            assertEquals(s16(post, p + 0x04), a.yPosition, "anim $i y")
            assertEquals(u16(post, p + 0x08), a.oam2Base, "anim $i oam2Base")
            assertEquals(u16(post, p + 0x0A), a.drawLayerPriority, "anim $i priority")
            assertEquals(u16(post, p + 0x0C) and 0x0600, a.state2 and 0x0600, "anim $i state2")
            assertEquals(u16(post, p + 0x0E), a.nextRoundId, "anim $i nextRoundId")
            assertEquals(u8(post, p + 0x12), a.currentRoundType, "anim $i round type")
            assertEquals(u32(post, p + 0x24), a.pScrStart, "anim $i pScrStart")
            assertEquals(u32(post, p + 0x2C), a.pImgSheetBuf, "anim $i pImgSheetBuf")
            assertEquals(u32(post, p + 0x30), a.pSpriteDataPool, "anim $i pSpriteDataPool")
        }
    }

    @Test
    @VerifiesPort("InitMainAnims", "InitBattleAnimFrame", "InitLeftAnim", "InitRightAnim")
    fun `attack window f1497`() = check("ram_trace_prologue_attack", 1497)

    @Test
    @VerifiesPort("InitMainAnims", "InitBattleAnimFrame", "InitLeftAnim", "InitRightAnim")
    fun `scripted window f14468`() = check("ram_trace_prologue_battle", 14468)

    private companion object {
        const val EWRAM = 0x02000000
        const val G_ANIMS = 0x02000000
        const val X_REAL = 0x02000028
        const val Y_REAL = 0x0200002C
        const val X_BASE = 0x02000030
        const val Y_BASE = 0x02000034
        const val MODES_LEFT = 0x0200005C
        const val MODES_RIGHT = 0x02000060
        const val IMG_SHEET_LEFT = 0x02000088
        const val IMG_SHEET_RIGHT = 0x02002088
        const val OAML = 0x020041C8
        const val OAMR2 = 0x020099C8
        const val SCR_LEFT = 0x0200F1C8
        const val SCR_RIGHT = 0x02011BC8
        const val BG_POSITION = 0x0201FB0C
        const val BANIM_VALID = 0x0203E104
        const val DISTANCE_TYPE = 0x0203E120
        const val HP_LUT_OFF = 0x0203E152
        const val DEFAULT_ANIM_SCR = 0x085B9D5C
        const val POOL = 0x02028F78
        const val STRIDE = 0x48
    }
}
