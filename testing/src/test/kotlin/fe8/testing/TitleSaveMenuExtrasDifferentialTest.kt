package fe8.testing

import fe8.game.save.BmSaveLib
import fe8.game.save.SaveExtras
import fe8.game.savemenu.SaveMenuChoice
import fe8.game.savemenu.SaveMenuState
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `PC-11b` against the two title runs: the `IsExtra*Enabled` queries on each run's fixture SRAM
 * (`s01000`) must give the Extras list (`extra_options`) and, through `InitSaveMenuChoice`, the
 * main list (`main_options`) that the capture shows once the setup has finished (f950).
 * `ram_trace_title_continue` has three used slots and a suspend save (`0x2B`); `menu_items` has
 * slot 1 and a suspend save (`0x3F`). Both fixtures give link arena + sound room only.
 */
class TitleSaveMenuExtrasDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun run(name: String): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(name)
        assumeTrue(run.dumps.containsKey("r00950"), "$name not present")
        return run
    }

    private fun lib(run: ReferenceData.Run) =
        BmSaveLib(CartSram().also { it.copyIn(run.sram("s01000")) }).also { it.sramInit() }

    private fun menuAt(run: ReferenceData.Run, step: Int) = ProcArrayDump.ofEwram(
        run.region("r%05d".format(step), "ewram"), ProcArrayDump.romNames(),
    ).find("ProcScr_SaveMenu")!!

    private fun check(name: String, mainOptions: Int, menuCount: Int) {
        val run = run(name)
        val extras = SaveExtras(lib(run))
        assertEquals(true, extras.isExtraLinkArenaEnabled(0))
        assertEquals(true, extras.isExtraSoundRoomEnabled())
        assertEquals(false, extras.isExtraSupportViewerEnabled())
        assertEquals(false, extras.isExtraFreeMapEnabled())
        assertEquals(false, extras.isExtraBonusClaimEnabled())

        val c = menuAt(run, 950)
        val seed = menuAt(run, 936)
        val state = SaveMenuState()
        for (i in 0 until 3) state.slots.chapterIdx[i] = seed.u8(CHAPTER_IDX + i)
        state.slots.unk44 = seed.u16(UNK_44)
        SaveMenuChoice(extras = extras).initSaveMenuChoice(state)
        assertEquals(c.u8(EXTRA_OPTIONS), state.extraOptions)
        assertEquals(c.u8(MAX_CHOICE), state.maxChoice)
        assertEquals(mainOptions, c.u8(MAIN_OPTIONS))
        assertEquals(c.u8(MAIN_OPTIONS), state.mainOptions)
        assertEquals(menuCount, c.u8(MENU_COUNT))
        assertEquals(c.u8(MENU_COUNT), state.unk31)
    }

    @Test
    @Timeout(300)
    @VerifiesPort(
        "IsExtraLinkArenaEnabled", "IsExtraSoundRoomEnabled", "IsExtraSupportViewerEnabled",
        "IsExtraFreeMapEnabled", "IsExtraBonusClaimEnabled", "IsGameSaveNotFirstChapter",
        "IsGameSaveComplete", "IsGamePlayedThrough",
    )
    fun `continue fixture gives the extras 3 and main options 0x2B`() {
        check("ram_trace_title_continue", 0x2B, 4)
    }

    @Test
    @Timeout(300)
    @VerifiesPort(
        "IsExtraLinkArenaEnabled", "IsExtraSoundRoomEnabled", "IsExtraSupportViewerEnabled",
        "IsExtraFreeMapEnabled", "IsExtraBonusClaimEnabled", "IsGameSaveNotFirstChapter",
        "IsGameSaveComplete", "IsGamePlayedThrough",
    )
    fun `menu items fixture gives the extras 3 and main options 0x3F`() {
        check("ram_trace_title_menu_items", 0x3F, 6)
    }

    private companion object {
        const val MAIN_OPTIONS = 0x30
        const val MENU_COUNT = 0x31
        const val EXTRA_OPTIONS = 0x32
        const val MAX_CHOICE = 0x33
        const val CHAPTER_IDX = 0x37
        const val UNK_44 = 0x44
    }
}
