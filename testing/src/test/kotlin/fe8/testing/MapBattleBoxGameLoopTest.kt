package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.event.EventConst
import fe8.game.event.EventEngineProc
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.text.Font
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The map battle info box as the game loop wires it (D-19, D-22), against the Prologue captures
 * `ram_trace_prologue_enemy_phase` and `ram_trace_prologue_clear`.
 */
class MapBattleBoxGameLoopTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private fun available() = ref.available && File(assets, "baserom.gba").isFile &&
        ProcArrayDump.romNames().isNotEmpty()

    /** A boot resumed from the Prologue save, fed the keys of [runName] from the first idle frame. */
    private fun boot(runName: String, frameOf: () -> Int, idleAt: () -> Int, idleFrame: Int): Fe8Boot {
        val run = ref.run(runName)
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }

            override fun store(src: ByteArray) {}
        }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        return Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt() < 0) pulse[if (frameOf() % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frameOf() - idleAt() + idleFrame) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = storage,
        )
    }

    private fun colorOf(rgb: ByteArray, p: Int) =
        (((rgb[p * 3].toInt() and 255) shl 16) or ((rgb[p * 3 + 1].toInt() and 255) shl 8) or
            (rgb[p * 3 + 2].toInt() and 255)) and 0xF8F8F8

    @Test
    fun `enemy initiated map battle draws the info box frame like the capture`() {
        assumeTrue(available(), "reference data / ROM")
        val run = ref.run("ram_trace_prologue_enemy_phase")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        // The capture's first enemy battle: the info box exists from step 1706, drawn by 1760.
        val capStart = (1100 until 2000).first {
            ProcArrayDump.ofWatch(trace, it, names).find("ProcScr_MapBattleInfoBox") != null
        }
        val sample = capStart + 54
        val rgb = run.frameRgb("f%05d".format(sample))
        val bldcnt = run.region("f%05d".format(sample), "io").let {
            (it[0x50].toInt() and 255) or ((it[0x51].toInt() and 255) shl 8)
        }
        var frame = 0
        var idleAt = -1
        val boot = boot("ram_trace_prologue_enemy_phase", { frame }, { idleAt }, idleFrame)
        var start = -1
        var checked = false
        while (frame < 3000 && !checked) {
            boot.frame()
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
            val box = boot.engine.procs.any { it.script?.name == "ProcScr_MapBattleInfoBox" }
            if (box && start < 0) start = frame
            if (box && frame - start == 54) {
                val lcd = boot.ui.env.hardware.lcd
                assertEquals(bldcnt shr 6 and 3, lcd.bldcnt shr 6 and 3, "BLDCNT effect")
                boot.render()
                var diff = 0
                for (y in 112..140) for (x in 0 until 240) {
                    val p = y * 240 + x
                    if ((boot.screen.pixels[y * boot.screen.width + x] and 0xF8F8F8) !=
                        colorOf(rgb, p)
                    ) diff++
                }
                assertEquals(0, diff, "info box rows differ from the capture")
                checked = true
            }
        }
        assertTrue(checked, "no enemy-initiated info box reached")
    }

    @Test
    fun `battle quote end restores the system text font and UI frame palette`() {
        assumeTrue(available(), "reference data / ROM")
        val clear = ref.run("ram_trace_prologue_clear")
        val names = ProcArrayDump.romNames()
        val trace = clear.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        var frame = 0
        var idleAt = -1
        val boot = boot("ram_trace_prologue_clear", { frame }, { idleAt }, idleFrame)
        while (frame < 1500 && (idleAt < 0 || frame < idleAt + 5)) {
            boot.frame()
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
        }
        val text = boot.talkScene.text
        // A battle quote leaves the talk font (palette bank 2) active.
        text.initTextFont(Font(), 0x06000000, 0x100, 2)
        text.initTalkTextFont()
        assertEquals(2, text.activeFont.palid)
        val proc = EventEngineProc().also { it.execType = EventConst.EV_EXEC_QUIET }
        boot.eventEngine.onEnd(proc)
        assertSame(text.defaultFont, text.activeFont, "SetTextFont(NULL)")
        // Bank 0 as the capture holds it right after the battle quote (f04460).
        val pal = clear.region("f04460", "pal")
        val hw = boot.ui.env.hardware
        for (i in 0 until 16) {
            val want = (pal[2 * i].toInt() and 255) or ((pal[2 * i + 1].toInt() and 255) shl 8)
            assertEquals(want, hw.palette[i].toInt() and 0xFFFF, "BG palette bank 0 colour $i")
        }
    }

    @Test
    fun `player kill keeps the attacker moveunit until the action ends`() {
        assumeTrue(available(), "reference data / ROM")
        val run = ref.run("ram_trace_prologue_kill")
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        val idleFrame = (1100 until 2020).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        // Capture: Seth's MU lives until f2005, the step his state loses HIDDEN (state 1 -> 2).
        val unit0 = run.watch("unit0")
        assertEquals(1, unit0.u8(2004, 0x0C), "capture: attacker hidden at f2004")
        assertTrue(ProcArrayDump.ofWatch(trace, 2004, names).live.any { it.name == "ProcScr_Mu" })
        assertEquals(2, unit0.u8(2005, 0x0C), "capture: attacker acted at f2005")
        assertTrue(ProcArrayDump.ofWatch(trace, 2005, names).live.none { it.name == "ProcScr_Mu" })
        var frame = 0
        var idleAt = -1
        val boot = boot("ram_trace_prologue_kill", { frame }, { idleAt }, idleFrame)
        var attacker = -1
        var combatSeen = false
        var hiddenWithoutMu = 0
        while (frame < 3000) {
            boot.frame()
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
            if (idleAt < 0) continue
            val combat = boot.engine.procs.any { it.script?.name == "sProcScr_CombatAction" }
            if (combat && attacker < 0) {
                // The unit hidden while its MOVEUNIT acts.
                attacker = (1..0x3E).first {
                    boot.units.isValid(it) && boot.units.state(it) and 1 != 0
                }
            }
            if (combat) combatSeen = true
            if (!combatSeen) continue
            val mu = boot.engine.procs.any { it.script?.name == "ProcScr_Mu" }
            if (boot.units.state(attacker) and 1 != 0 && !mu) hiddenWithoutMu++
            if (!combat && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) break
        }
        assertTrue(combatSeen, "no combat")
        assertEquals(0, hiddenWithoutMu, "frames the attacker is hidden with no MOVEUNIT")
    }

    /** One frame of the fight's tail: hidden units (`US_HIDDEN`), MOVEUNIT count and procs. */
    private data class Tail(val hidden: List<Int>, val mus: Int, val procs: List<String>)

    private val tailProcs = setOf(
        "ProcScr_MapAnimBattle", "sProcScr_CombatAction", "ProcScr_MapAnimExpBar",
        "ProcScr_MapBattleInfoBox", "gProcScr_CpPerform", "ProcScr_MuDeathFade",
    )

    private fun collapse(tail: List<Tail>): List<Tail> =
        tail.filterIndexed { i, t -> i == 0 || tail[i - 1] != t }

    /** The capture's tail from the first step [from] without `ProcScr_MapAnimBattle`. */
    private fun captureTail(runName: String, from: Int, steps: Int): List<Tail> {
        val run = ref.run(runName)
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        val dumpOfStep = run.dumps.entries.associate { (dump, step) -> step to dump }
        val out = ArrayList<Tail>()
        for (step in from until from + steps) {
            val d = ProcArrayDump.ofWatch(trace, step, names)
            val dump = dumpOfStep[step]
            val hidden = if (dump == null) out.lastOrNull()?.hidden ?: emptyList() else {
                val table = fe8.game.bm.UnitTable(RomView.ofRom(ByteArray(0)))
                table.loadFrom(run.region(dump, "ewram"))
                (1..0x3E).plus(0x81..0xA0).filter {
                    table.isValid(it) && table.state(it) and 1 != 0
                }
            }
            out += Tail(
                hidden, d.live.count { it.name == "ProcScr_Mu" },
                d.live.mapNotNull { it.name }.filter { it in tailProcs }.sorted(),
            )
        }
        return out
    }

    /** The tail from the experience bar to the first frame with no fight proc and no MU. */
    private fun trim(tail: List<Tail>): List<Tail> {
        val from = collapse(tail).dropWhile { "ProcScr_MapAnimExpBar" !in it.procs }
        val end = from.indexOfFirst { it.procs.isEmpty() && it.mus == 0 }
        return from.take(end + 1)
    }

    /** Units whose sprite handle has the hide bit (`ShowUnitSprite` clears it), by reflection. */
    private fun spriteHidden(boot: Fe8Boot): List<Int> {
        val field = Fe8Boot::class.java.getDeclaredField("spriteHandles").also { it.isAccessible = true }
        val handles = field.get(boot) as fe8.game.bm.UnitSpriteHandles
        return (1..0x3E).plus(0x81..0xA0).filter { id ->
            if (!boot.units.isValid(id)) return@filter false
            val o = boot.units.unitOffset(id) + 0x3C
            val b = boot.units.bytes
            val addr = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
                ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)
            addr != 0 && handles.handles[(addr - 0x0203A018) /
                12 * 12 + 0xB].toInt() and 0x80 != 0
        }
    }

    private var spritesHiddenAtEnd: List<Int> = emptyList()

    private fun portTail(runName: String, idleFrame: Int, fromEnemyPhase: Boolean): List<Tail> {
        var frame = 0
        var idleAt = -1
        val boot = boot(runName, { frame }, { idleAt }, idleFrame)
        val out = ArrayList<Tail>()
        var combatSeen = false
        while (frame < 4000) {
            boot.frame()
            frame++
            if (idleAt < 0 && boot.engine.find(boot.playerPhaseScript)?.scrCur == 16) idleAt = frame
            if (idleAt < 0) continue
            val procs = boot.engine.procs.mapNotNull { it.script?.name }
            if ("sProcScr_CombatAction" in procs) combatSeen = true
            if (!combatSeen) continue
            val hidden = (1..0x3E).plus(0x81..0xA0).filter {
                boot.units.isValid(it) && boot.units.state(it) and 1 != 0
            }
            val mus = procs.count { it == "ProcScr_Mu" }
            out += Tail(hidden, mus, procs.filter { it in tailProcs }.sorted())
            if (out.size > 5 && "ProcScr_MapAnimExpBar" in out.flatMap { it.procs } &&
                procs.none { it in tailProcs } && mus == 0
            ) {
                spritesHiddenAtEnd = spriteHidden(boot)
                break
            }
        }
        return out
    }

    private fun assertTailMatches(runName: String, from: Int, steps: Int) {
        val run = ref.run(runName)
        val names = ProcArrayDump.romNames()
        val trace = run.watch("procs")
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(trace, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        val want = trim(captureTail(runName, from, steps))
        val got = trim(portTail(runName, idleFrame, false))
        assertEquals(want, got, "$runName: hidden units, MOVEUNITs and procs after the fight")
        assertTrue(got.last().hidden.isEmpty(), "everyone visible at the end")
        assertEquals(emptyList<Int>(), spritesHiddenAtEnd, "unit sprites still hidden at the end")
    }

    @Test
    fun `enemy attack tail matches the capture`() {
        assumeTrue(available(), "reference data / ROM")
        assertTailMatches("ram_trace_prologue_enemy_phase", 1880, 200)
    }

    @Test
    fun `player attack tail matches the capture`() {
        assumeTrue(available(), "reference data / ROM")
        assertTailMatches("ram_trace_prologue_attack_noanim", 1980, 140)
    }
}
