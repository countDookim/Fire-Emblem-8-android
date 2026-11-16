package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.MuUnit
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The MU effects of `mu.c` against `ram_trace_prologue_clear`: the speed-up of the attacker's
 * animation (f4485, f4544, f4590), the hit flash of O'Neill's MU (f4491, f4596) and his death fade
 * (f4742..f4772, MU removed f4773).
 *
 * The two MOVEUNITs are started from the f4480 dump and the effect functions are called at the
 * frames the round procs (EP-23) and `MapAnim_DisplayDeathFade` (EP-18) call them, with the call
 * arguments read from the capture. Every frame is compared with the `procs` watch (position,
 * sleep timer and frames left of the effect procs, the number of MU procs); the dump frames also
 * compare the MU fade palette bank (palette buffer), the `APHandle` clock, tile base and layer of
 * the MU's animation, the palette bits of its OAM objects and the blend coefficient. The
 * `ap.c` player and `CallDelayedArg` are hooks: the delayed freeze is a stand-in, so the
 * capture only pins the animation clock at the dump frames (0x40 inside the 20 frames, 0 after).
 * The death fade sound and the ballista branch are not in the capture and not compared.
 */
class MuEffectsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "StartMuSpeedUpAnim",
        "MuSlowDownAnimFreezeFunc",
        "StartMuHitFlash",
        "MuFlashFadeFrom_RestorePal",
        "MU_StartDeathFade",
        "MuDeathFade_OnLoop",
        "EndMuExt",
    )
    fun `MU speed up, hit flash and death fade match the capture f4481 to f4780`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()
        val run = ref.run(RUN)
        val procs = run.watch("procs")
        fun name(f: Int) = "f%05d".format(f)
        val ewram0 = run.region(name(SEED), "ewram")
        val iwram0 = run.region(name(SEED), "iwram")

        val engine = ProcEngine()
        val bm = BmSt()
        val hw = BgHardware()
        val hooks = Hooks(hw, engine, rom, ewram0)
        val mu = Mu(engine, bm, PlaySt(), KeyStatus(), CamMove(engine, bm).script, hooks, rom)
        for (i in 0 until 256) {
            hw.palette[i + 256] = GbaMem.u16(ewram0, GbaMem.EWRAM, PALETTE_BUFFER + 2 * (i + 256))
                .toShort()
        }

        // The MUs of the capture, in slot order, started the way StartMu does and then given the
        // dump's configuration (palette, tile, layer) and animation handle.
        val seed = ProcArrayDump.ofEwram(ewram0, names).findAll(PROC_SCR_MU)
        assertEquals(2, seed.size, "MOVEUNITs in the f$SEED dump")
        val byAddress = HashMap<Int, MuProc>()
        for (d in seed.sortedBy { byteAt(iwram0, GbaMem.IWRAM, it.u32(0x34)) }) {
            val unit = d.u32(0x2C)
            val proc = checkNotNull(
                mu.startMu(
                    MuUnit(
                        address = unit,
                        xPos = 0,
                        yPos = 0,
                        classId = d.u8(0x41),
                        state = GbaMem.u32(ewram0, GbaMem.EWRAM, unit + 0x0C),
                    ),
                ),
            )
            val s = proc.state as MuProc
            val cfg = d.u32(0x34)
            s.config!!.pal = byteAt(iwram0, GbaMem.IWRAM, cfg + 1)
            s.config!!.chr = GbaMem.u16(iwram0, GbaMem.IWRAM, cfg + 2)
            s.layer = d.u16(0x46)
            s.spriteAnim = d.u32(0x30)
            s.state = d.u8(0x3F)
            hooks.anims[s.spriteAnim] = Anim(
                tileBase = apU16(iwram0, s.spriteAnim, 0x22),
                frameInterval = apU16(iwram0, s.spriteAnim, 0x1A),
                objLayer = apU16(iwram0, s.spriteAnim, 0x1E),
            )
            byAddress[d.address] = s
        }
        val ids = byAddress.keys.toList()

        var step = SEED
        // The round procs and MapAnim_DisplayDeathFade call the effects from the map animation proc
        // (tree 3); the captured call arguments pick the MU.
        val caller = ProcScript(
            "Caller (test)",
            listOf(
                ProcScripts.repeat(
                    ProcFunc {
                        hooks.now = step
                        for ((at, apAddress) in SPEED_UPS) {
                            if (step == at) mu.startMuSpeedUpAnim(byAddress.values.first { m ->
                                m.spriteAnim == apAddress
                            })
                        }
                        for ((at, muAddress, type) in HIT_FLASHES) {
                            if (step == at) mu.startMuHitFlash(byAddress.getValue(muAddress), type)
                        }
                        if (step == DEATH_FADE) mu.muStartDeathFade(byAddress.getValue(DYING_MU))
                    },
                ),
                ProcScripts.end(),
            ),
        )
        engine.start(caller, 3)

        var deathFadeSeen = false
        val prevTileBase = HashMap<MuProc, Int>()
        val prevTileBase2 = HashMap<MuProc, Int>()
        while (step < LAST) {
            prevTileBase2.putAll(prevTileBase)
            for (m in byAddress.values) {
                prevTileBase[m] = hooks.anims.getValue(m.spriteAnim).tileBase
            }
            step++
            hooks.step = step
            for (tree in 0 until 8) engine.run(engine.root(tree))
            hooks.runDelayed(step)
            val at = "$RUN step $step"
            val dump = ProcArrayDump.ofWatch(procs, step, names)

            val flashes = dump.findAll(PROC_SCR_MU_HIT_FLASH)
            val myFlash = engine.find(mu.hitFlashScript)
            assertEquals(flashes.isNotEmpty(), myFlash != null, "$at: MuHitFlash exists")
            if (flashes.isNotEmpty() && myFlash != null) {
                val want = flashes.single()
                assertEquals(want.scrCur, myFlash.scrCur, "$at: MuHitFlash script position")
                assertEquals(want.u8(0x24), myFlash.sleepTime, "$at: MuHitFlash sleep timer")
                assertEquals(
                    want.u32(0x2C),
                    ids.first { byAddress.getValue(it) === (myFlash.state as Mu.MuFlashState).mu },
                    "$at: MuHitFlash mu",
                )
            }

            val fades = dump.findAll(PROC_SCR_MU_DEATH_FADE)
            val myFade = engine.find(mu.deathFadeScript)
            assertEquals(fades.isNotEmpty(), myFade != null, "$at: MuDeathFade exists")
            if (fades.isNotEmpty() && myFade != null) {
                deathFadeSeen = true
                val want = fades.single()
                val state = myFade.state as Mu.MuEffectState
                assertEquals(want.scrCur, myFade.scrCur, "$at: MuDeathFade script position")
                assertEquals(want.u16(0x64), state.timeLeft, "$at: MuDeathFade timeLeft")
                assertEquals(
                    want.u32(0x54),
                    ids.first { byAddress.getValue(it) === state.mu },
                    "$at: MuDeathFade mu",
                )
            }
            assertEquals(
                dump.findAll(PROC_SCR_MU).size, engine.count(mu.script), "$at: MOVEUNIT procs",
            )

            if (step % 10 != 0 || step !in DUMPS) continue
            val e = run.region(name(step), "ewram")
            val iw = run.region(name(step), "iwram")
            for (i in 0 until 16) {
                assertEquals(
                    GbaMem.u16(e, GbaMem.EWRAM, PALETTE_BUFFER + 2 * (0x15 * 16 + i)),
                    hw.palette[0x15 * 16 + i].toInt() and 0xFFFF,
                    "$at: MU fade palette color $i",
                )
            }
            for ((address, m) in byAddress) {
                val anim = hooks.anims.getValue(m.spriteAnim)
                // A MU that has been ended no longer has an animation in the dump.
                if (dump.findAll(PROC_SCR_MU).none { it.address == address }) continue
                assertEquals(
                    apU16(iw, m.spriteAnim, 0x22), anim.tileBase, "$at: tileBase of $address",
                )
                assertEquals(
                    apU16(iw, m.spriteAnim, 0x1A), anim.frameInterval, "$at: interval of $address",
                )
                assertEquals(
                    apU16(iw, m.spriteAnim, 0x1E), anim.objLayer, "$at: objLayer of $address",
                )
                // The dump's OAM holds the objects drawn in the previous frame, before that frame's
                // effect procs ran, so they carry the tile base of two steps ago.
                val oam = run.region(name(step), "oam")
                var found = 0
                for (o in 0 until 128) {
                    val a2 = GbaMem.u16(oam, 0, o * 8 + 4)
                    val a0 = GbaMem.u16(oam, 0, o * 8)
                    val a1 = GbaMem.u16(oam, 0, o * 8 + 2)
                    // The MU's object: 32x32 at its first tile.
                    if (a2 and 0x3FF != m.config!!.chr || a0 and 0xC000 != 0 ||
                        a1 and 0xC000 != 0x8000
                    ) {
                        continue
                    }
                    found++
                    assertEquals(
                        prevTileBase2.getValue(m) and 0xF000, a2 and 0xF000, "$at: OAM palette $o",
                    )
                }
                assertTrue(found > 0, "$at: MU objects in OAM")
            }
            if (step in BLEND_DUMPS) {
                val io = run.region(name(step), "io")
                assertEquals(
                    hooks.blendA and 0x1F, GbaMem.u16(io, 0, 0x52) and 0x1F, "$at: BLDALPHA EVA",
                )
            }
        }
        assertTrue(deathFadeSeen, "death fade seen")
        assertEquals(0, hooks.ballistaRemovals, "no ballista")
    }

    private class Anim(var tileBase: Int, var frameInterval: Int, var objLayer: Int)

    private class Hooks(
        val hw: BgHardware,
        val engine: ProcEngine,
        val rom: RomView,
        val ewram: ByteArray,
    ) : MuHooks {
        val anims = HashMap<Int, Anim>()
        val fade = PaletteFade(hw, engine)
        var ballistaRemovals = 0
        var blendA = 0
        var now = 0
        var step = 0
        private val delayed = mutableListOf<Triple<Int, (Int) -> Unit, Int>>()

        override fun applyPalette(src: Int, bank: Int) {
            rom.pal(src, 16).copyInto(hw.palette, bank * 16)
        }

        override fun startPalFade(targetBank: Int, bank: Int, duration: Int, parent: Proc) {
            fade.start(
                hw.palette.copyOfRange(targetBank * 16, targetBank * 16 + 16),
                bank, duration, parent,
            )
        }

        override fun setBlendConfig(effect: Int, coeffA: Int, coeffB: Int, blendY: Int) {
            blendA = coeffA
        }

        override fun freezeSpriteAnim(anim: Int) {
            anims.getValue(anim).frameInterval = 0
        }

        override fun setSpriteAnimClock(anim: Int, interval: Int) {
            anims.getValue(anim).frameInterval = interval
        }

        override fun setSpriteAnimTileBase(anim: Int, tileBase: Int) {
            anims.getValue(anim).tileBase = tileBase
        }

        override fun setSpriteAnimObjLayer(anim: Int, layer: Int) {
            anims.getValue(anim).objLayer = layer
        }

        // Stand-in for CallDelayedArg (bmlib.c, not ported): the capture's proc starts at the
        // call frame and the function runs 21 frames later (f4485 -> f4506).
        override fun callDelayedArg(func: (Int) -> Unit, arg: Int, delay: Int) {
            delayed += Triple(now + delay + 1, func, arg)
        }

        fun runDelayed(step: Int) {
            val due = delayed.filter { it.first <= step }
            delayed.removeAll(due)
            for (d in due) d.second(d.third)
        }

        override fun unitState(address: Int): Int = GbaMem.u32(ewram, GbaMem.EWRAM, address + 0x0C)

        override fun removeUnitFromBallista(address: Int) {
            ballistaRemovals++
        }
    }

    private fun byteAt(dump: ByteArray, base: Int, address: Int) =
        dump[address - base].toInt() and 0xFF

    private fun apU16(iwram: ByteArray, ap: Int, field: Int) =
        GbaMem.u16(iwram, GbaMem.IWRAM, ap + field)

    private companion object {
        const val RUN = "ram_trace_prologue_clear"
        const val SEED = 4480
        const val LAST = 4780
        const val PALETTE_BUFFER = 0x020228A8
        const val PROC_SCR_MU = 0x089A2C48
        const val PROC_SCR_MU_DEATH_FADE = 0x089A2C80
        const val PROC_SCR_MU_HIT_FLASH = 0x089A2D98
        const val DYING_MU = 0x02025378
        const val DEATH_FADE = 4742
        val DUMPS = setOf(4490, 4500, 4510, 4600, 4610, 4740, 4750, 4760, 4770, 4780)
        val BLEND_DUMPS = setOf(4750, 4760, 4770)

        /** (step, `sprite_anim` of the MU): `MapAnim_BeginSubjectFastAnim` callers. */
        val SPEED_UPS = listOf(4485 to 0x03000108, 4544 to 0x03000130, 4590 to 0x03000108)

        /** (step, MU proc address, flash type) of `StartMuHitFlash` calls. */
        val HIT_FLASHES = listOf(Triple(4491, 0x02025378, 0), Triple(4596, 0x02025378, 0))
    }
}
