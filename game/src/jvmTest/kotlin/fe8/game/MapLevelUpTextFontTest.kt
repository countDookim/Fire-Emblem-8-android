package fe8.game

import fe8.game.bm.BattleUnit
import fe8.game.event.EventConst
import fe8.game.event.EventEngineProc
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The level-up panel after a battle quote, through the real [Fe8Boot] wiring and its own text
 * system, palette and event engine (nothing seeded but the unit and battle state): the quote
 * leaves the talk font active until `EventEngine_OnEnd` restores the system font
 * (`SetTextFont(NULL)`, `InitSystemTextFont`), so the stat numbers and labels of
 * `ProcScr_ManimLevelUp` must be drawn with the default font (BG palette bank 0) as in
 * `ram_trace_prologue_levelup`.
 */
class MapLevelUpTextFontTest {
    @Test
    fun `panel text after a battle quote uses the system font and bank 0 palette`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val ref = ReferenceData.fromEnvironment()
        val romFile = File(dir, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val boot = Fe8Boot(RomView.ofRom(romFile.readBytes()), { 0 }, RngService())
        boot.bmapMain
        val wiring = checkNotNull(boot.playerWiring)
        val hw = boot.ui.env.hardware
        val run = ref.run("ram_trace_prologue_levelup")
        val symbols = RamSymbols.fromEnvironment()
        val seed = run.region("r06412", "ewram")
        val base = GbaMem.EWRAM
        boot.units.loadFrom(seed)
        val at = symbols["gBattleActor"].address - base
        seed.copyInto(wiring.battle.actor.bytes, 0, at, at + BattleUnit.SIZE)
        val unitPointer = GbaMem.u32(seed, base, symbols["gManimSt"].address)
        wiring.mapAnim.state.unitBytes[0] = seed
        wiring.mapAnim.state.unitOffset[0] = unitPointer - base
        wiring.mapAnim.state.bu[0] = wiring.battle.actor
        wiring.mapAnim.state.actorCount = 2
        hw.lcd.readFrom(
            run.region("r06412", "iwram"), symbols["gLCDControlBuffer"].address - GbaMem.IWRAM,
        )

        val text = boot.talkScene.text
        text.resetText() // MapAnim_PrepareBattleTalk
        boot.talkScene.talk.initTalk(0x80, 2, true) // the quote's EventText
        boot.eventEngine.onEnd(EventEngineProc().apply { execType = EventConst.EV_EXEC_QUIET })

        val parent = boot.engine.start(ProcScript("Parent", listOf(block())), 3)
        val levelUp = checkNotNull(wiring.levelUp.startManimLevelUp(0, parent))
        wiring.levelUp.initMainScreen(levelUp)
        hw.flushBackgrounds()

        val bg0 = symbols["gBG0TilemapBuffer"].address - base
        val want = run.region("r06519", "ewram")
        for (i in 0 until 1024) {
            assertEquals(
                GbaMem.u16(want, 0, bg0 + 2 * i), hw.tilemaps[0][i].toInt() and 0xFFFF,
                "BG0[$i]",
            )
        }
        val pal = run.region("f06530", "pal")
        for (i in (0..15) - 14) {
            assertEquals(
                GbaMem.u16(pal, 0x05000000, 0x05000000 + 2 * i), hw.paletteRam[i].toInt() and 0xFFFF,
                "palette RAM bank 0 [$i]",
            )
        }
    }
}
