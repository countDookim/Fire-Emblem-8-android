package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BanimInit
import fe8.game.bm.BattleMapKind
import fe8.game.bm.EkrBattleInit
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.anim.AnimPool
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `ekrBattle_Init`, `RegisterEkrDragonStatusType`, `InitEkrDragonStatus` and
 * `ShouldCallBattleQuote` against f1496 -> f1497 (`ram_trace_prologue_attack`, no quote) and
 * f14467 -> f14468 (`ram_trace_prologue_battle`, quote). The `ekrBattle` proc exists in the
 * pre-step dump (created at f1496 / f14467); its init runs in the next frame.
 *
 * Inputs from the pre dump. `GetBattleQuoteEntry` is a test stand-in that scans the ROM quote
 * table by pids and chapter and treats every event flag as clear and no triangle attack (so it
 * is not part of the verified port). Compared with the post dump: `gEkrBgPosition`,
 * `gCtrlC01Blocking`, proc `timer`, `is_quote`, `unk58`, both `gEkrDragonStatus*` structs'
 * `type`, and the anim list through [BanimInit] as in `BattleAnimInitDifferentialTest`.
 */
class EkrBattleInitDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun check(run: String, step: Int, expectQuote: Boolean) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val capture = ref.run(run)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val pre = capture.region(byStep.getValue(step - 1), "ewram")
        val post = capture.region(byStep.getValue(step), "ewram")
        val rom = RomView.ofRom(romFile.readBytes())

        val proc = u32(pre, PROC_EKR_BATTLE)
        assertEquals(u32(post, PROC_EKR_BATTLE), proc)
        val chapter = u8(pre, PLAY_ST + 0x0E)
        val battleUnit = intArrayOf(u32(pre, UNIT_LEFT), u32(pre, UNIT_LEFT + 4))

        val env = object : EkrBattleInit.Env {
            override val initPosReal = u32(pre, INIT_POS_REAL)
            override val initialHitSide = s16(pre, INITIAL_HIT_SIDE)
            override fun pid(side: Int) = u8(pre, PIDS + side)
            override val arenaFlag = u32(pre, ARENA_FLAG) != 0
            override val unitActionType = u8(pre, ACTION_DATA + 0x11)
            override fun battleMapKind() = BattleMapKind.STORY // prologue is a story chapter
            override fun classId(side: Int) = rom.u8(u32(pre, battleUnit[side] + 4) + 4)

            // Stand-in for GetBattleQuoteEntry: flags treated clear, no triangle attack.
            override fun battleQuoteEntryExists(pidA: Int, pidB: Int): Boolean {
                var it = TALK_LIST
                while (rom.u16(it) != 0xFFFF) {
                    val a = rom.u16(it)
                    val b = rom.u16(it + 2)
                    val ch = rom.u16(it + 4)
                    it += 0x10
                    if (ch != 0xFF && ch != chapter) continue
                    val hit = when {
                        a != 0 && b == 0 -> pidA == a
                        a == 0 && b == 0 -> false
                        a == 0 -> pidB == b
                        else -> (pidA == a && pidB == b) || (pidB == a && pidA == b)
                    }
                    if (hit) return true
                }
                return false
            }
        }

        fun table(base: Int): (Int) -> Int = { frame -> rom.u32(base + 4 * frame) }
        val banim = BanimInit(
            rom,
            AnimPool(),
            BanimInit.Buffers(
                table(u32(pre, MODES_LEFT)), table(u32(pre, MODES_RIGHT)), SCR_LEFT, SCR_RIGHT,
                DEFAULT_ANIM_SCR, IMG_SHEET_LEFT, IMG_SHEET_RIGHT, OAML, OAMR2,
            ),
        )
        banim.distanceType = s16(pre, DISTANCE_TYPE)
        for (i in 0..1) banim.valid[i] = s16(pre, BANIM_VALID + 2 * i) == 1
        val init = EkrBattleInit(banim, env)

        assertEquals(0, u32(pre, CTRL_C01_BLOCKING), "pre gCtrlC01Blocking")
        init.run()

        assertEquals(s16(post, BG_POSITION), banim.bgPosition, "gEkrBgPosition")
        assertEquals(u32(post, CTRL_C01_BLOCKING), init.ctrlC01Blocking, "gCtrlC01Blocking")
        assertEquals(s16(post, proc + 0x2C), init.procTimer, "proc timer")
        assertEquals(u32(post, proc + 0x54), init.procIsQuote, "proc is_quote")
        assertEquals(u32(post, proc + 0x58), init.procUnk58, "proc unk58")
        assertEquals(if (expectQuote) 1 else 0, init.procIsQuote, "quote branch")
        for (i in 0..1) {
            assertEquals(u8(post, DRAGON_STATUS + 0x10 * i), init.dragonType[i], "dragon $i")
        }
        for (i in 0 until 4) {
            val p = u32(post, G_ANIMS + 4 * i)
            assertEquals(u16(post, p), banim.anims[i]!!.state, "anim $i state")
        }
    }

    @Test
    @VerifiesPort(
        "ekrBattle_Init", "RegisterEkrDragonStatusType", "ShouldCallBattleQuote",
    )
    fun `attack window without quote f1497`() = check("ram_trace_prologue_attack", 1497, false)

    @Test
    @VerifiesPort(
        "ekrBattle_Init", "RegisterEkrDragonStatusType", "ShouldCallBattleQuote",
    )
    fun `scripted window with quote f14468`() = check("ram_trace_prologue_battle", 14468, true)

    private companion object {
        const val EWRAM = 0x02000000
        const val G_ANIMS = 0x02000000
        const val CTRL_C01_BLOCKING = 0x02000024
        const val MODES_LEFT = 0x0200005C
        const val MODES_RIGHT = 0x02000060
        const val PROC_EKR_BATTLE = 0x02000064
        const val IMG_SHEET_LEFT = 0x02000088
        const val IMG_SHEET_RIGHT = 0x02002088
        const val OAML = 0x020041C8
        const val OAMR2 = 0x020099C8
        const val SCR_LEFT = 0x0200F1C8
        const val SCR_RIGHT = 0x02011BC8
        const val BG_POSITION = 0x0201FB0C
        const val INIT_POS_REAL = 0x02017744
        const val DRAGON_STATUS = 0x02020048
        const val PLAY_ST = 0x0202BCF0
        const val ACTION_DATA = 0x0203A958
        const val BANIM_VALID = 0x0203E104
        const val INITIAL_HIT_SIDE = 0x0203E100
        const val DISTANCE_TYPE = 0x0203E120
        const val UNIT_LEFT = 0x0203E188
        const val PIDS = 0x0203E190
        const val ARENA_FLAG = 0x0203E1E4
        const val TALK_LIST = 0x089EC6BC
        const val DEFAULT_ANIM_SCR = 0x085B9D5C
    }
}
