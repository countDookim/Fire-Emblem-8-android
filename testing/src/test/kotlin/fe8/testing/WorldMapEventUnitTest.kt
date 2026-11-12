package fe8.testing

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventEngine
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapAutoMuProc
import fe8.game.worldmap.GmapMuPrimProc
import fe8.game.worldmap.GmapMuProc
import fe8.game.worldmap.GmapUnitContainerProc
import fe8.game.worldmap.GmapUnitFadeProc
import fe8.game.worldmap.GmapUnitProc
import fe8.game.worldmap.GmapRmBaPalAnimProc
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPlaceDotProc
import fe8.game.worldmap.WmUnitHooks
import fe8.game.bm.BmSt
import fe8.game.bm.UnitSprites
import fe8.game.worldmap.WorldMapApHost
import fe8.game.worldmap.WorldMapAutoMu
import fe8.game.worldmap.WorldMapSms
import fe8.game.worldmap.WorldMapDot
import fe8.game.worldmap.WorldMapDotEvents
import fe8.game.worldmap.WorldMapMu
import fe8.game.worldmap.WorldMapUnit
import fe8.game.worldmap.WorldMapUnitEvents
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The world map unit and point commands run by the event VM, steps 8101-9279 of
 * `ram_trace_prologue` (opcodes BC, 9E, A8, AC, A0, A7, A3, A5, BD, BE). The seed is the full dump
 * f08100 of `ram_trace_prologue_wm_lcd` (script stalling at 0x08A39AD0, one hidden unit).
 *
 * Opcodes are registered one by one; 0E, the unit and the point commands are the real handlers.
 * Test-only stand-ins (they advance the script as the capture's `pEventCurrent` shows): 1C, 1D
 * (talk), C4, C5 (portrait), C9, 17, C1 (other lanes).
 */
class WorldMapEventUnitTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @Test
    @VerifiesPort(
        "Event9E_WmSetClassUnit", "EventA0_WmRemoveUnit", "EventA3_WmShowUnitFaded",
        "EventA5_WmUnitFadeWait", "EventA7_WmUnitSetPosition", "EventA8_WmUnitMoveFree",
        "EventAC_WmUnitMoveWait", "EventBC_MarkPoint", "EventBD_", "EventBE_",
        "WmDotPalAnim_Init", "sub_80C2C80", "WmDotPalAnim_Loop1", "WmDotPalAnim_Loop2",
        "StartWmDotPalAnim", "EndAllWmDotPalAnim", "sub_80C2E50", "WmPlaceDot_OnEnd",
        "WmPlaceDot_Init", "PutWmDotSprite", "WmPlaceDot_Loop1", "WmPlaceDot_Loop2",
        "StartWmPlaceDot", "IsWmPlaceDotActiveAtIndex", "SetWmPlaceDotFlagForIndex",
        "GmMu_SetUnit", "MapMU_SetUnitClass", "MapMUPrim_MakeUnitForClass", "GmMu_RemoveUnit",
        "MapMUPrim_RemoveLinkedMapUnit", "GmShowMuUnit", "GmHideMuUnit", "GmMu_SetSpriteLayer",
        "GmMu_SetPosition", "GmMu_StartFadeIn", "GmMu_StartFadeOut", "GmUnitFadeExists",
        "sub_80BDFA4", "sub_80BE12C", "GmapAutoMu_OnEnd", "GmapAutoMu_StartFadeIn",
        "GmapAutoMu_80C32E4", "IsGmAutoMuActiveFor", "StartWorldMapSMS",
        "ApplyUnitSpriteUiImage16x16", "SetStandingMuFacingWM", "SMS_DisplayOne", "GetClassSMSId",
    )
    fun `unit and point commands run by the VM match the capture`() {
        val lcd = ref.run("ram_trace_prologue_wm_lcd")
        assumeTrue(
            ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty() &&
                lcd.hasWatch("oam"),
        )
        val names = ProcArrayDump.romNames()
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val pal = lcd.watch("pal")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        fun addr(name: String) = names.entries.first { it.value == name }.key
        val rom = RomView.ofRom(romFile.readBytes())
        val mem = ScriptMemory { a -> rom.u16(a) }

        val engine = ProcEngine()
        engine.init()
        val hw = BgHardware()
        val layers = SpriteLayers()
        val apHost = WorldMapApHost(rom, hw, OamBuffer())
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, apHost)
        player.clearAll()
        val iwram = lcd.region("f08100", "iwram").copyOf(0x8000)
        val gm = GmData(iwram, RngService())
        var step = SEED
        // GmapRm_GetPosition: the map proc is not ported into this test, its fields are fed.
        fun rmPosition(): Pair<Int, Int> {
            val rmProc = dump(step).find("ProcScr_GmapRM")
            return if (rmProc == null) 0 to 0 else {
                rmProc.u16(0x2E).toShort().toInt() to rmProc.u16(0x30).toShort().toInt()
            }
        }
        val clock = lcd.watch("clock")
        val unitSprites = UnitSprites(rom, hw, BmSt())
        // GetGameClock(): the capture's gGameClock (watched from step 5500 to 8450).
        unitSprites.gameClock = {
            val at = minOf(step - 1, 8450)
            clock.u16(at, 0) or (clock.u16(at, 2) shl 16)
        }
        val sms = WorldMapSms(rom, layers, unitSprites)
        val unit = WorldMapUnit(
            engine, rom, hw, player, gm,
            WorldMapUnit.Hooks(
                rmPosition = ::rmPosition, screenPosition = { rmPosition() },
                classSmsId = { sms.classSmsId(it) },
                startWorldMapSms = { id, frame, slot -> sms.startWorldMapSms(id, frame, slot) },
                setStandingMuFacingWm = sms.setStandingMuFacing,
                smsDisplayOne = sms.smsDisplayOne,
                decompressMuGfx = { img, index ->
                    rom.decompressInto(img, apHost.muGfx, index * 0x2200)
                },
            ),
        )
        val nodes = WmNodeData(rom)
        val mu = WorldMapMu(engine, unit, gm, nodes)
        val parent = engine.start(ProcScript("Parent", listOf(block(), end())) { }, 3)
        val seed = dump(SEED)
        val c0 = seed.find("ProcScr_GmapUnitContainer")!!
        unit.newGmapUnitContainer(parent, c0.u16(0x2C), c0.u8(0x30))
        val gmMu = mu.startGmMu(parent)
        lateinit var automu: WorldMapAutoMu
        automu = WorldMapAutoMu(
            engine, gm,
            WorldMapAutoMu.Hooks(
                muMoving = { mu.gmMuIsMoving(gmMu, it) },
                muStop = { mu.finishMoves(gmMu, it) },
                endUnitFade = unit::endGmapUnitFade,
                muSetPosition = { i, x, y -> mu.gmMuSetPosition(gmMu, i, x, y) },
                hideUnit = { mu.gmHideMuUnit(gmMu, it) },
                showUnit = { mu.gmShowMuUnit(gmMu, it) },
                muFadeIn = { i, d -> mu.gmMuStartFadeIn(gmMu, i, d) },
                muFadeOut = { i, d -> mu.gmMuStartFadeOut(gmMu, i, d) },
                unitFadeExists = mu::gmUnitFadeExists,
                muMoveNodes = { mu.startNodeMove(gmMu, it) },
                muMovePositions = { mu.startPositionMove(gmMu, it) },
            ),
        )
        val dots = WorldMapDot(engine, hw, rom, layers, { rmPosition() })

        // Seed: the hidden unit 0 (character slot), then the captured fields over it.
        val u0 = seed.findAll("ProcScr_GmapUnit".let { addr(it) }).single()
        mu.mapMuSetUnitClass(gmMu, 0, u0.u16(0x38), 0, 0)
        iwram.let { lcd.region("f08100", "iwram").copyInto(it, 0, 0, 0x8000) }
        val u0Sim = (unit.container!!.state as GmapUnitContainerProc).units[0]!!
        (u0Sim.state as GmapUnitProc).apply {
            flags = u0.u16(0x2A); layer = u0.u16(0x2C); oam2Base = u0.u16(0x2E)
            chr = u0.u16(0x30); faction = u0.u8(0x35); this.pal = u0.u8(0x36); animId = u0.u8(0x37)
            x = u0.u16(0x3C); y = u0.u16(0x3E)
        }
        val palSeed = lcd.region("f08100", "pal")
        for (i in 0 until 512) {
            hw.palette[i] = u16(palSeed, 2 * i).toShort()
            hw.paletteRam[i] = u16(palSeed, 2 * i).toShort()
        }
        lcd.region("f08100", "vram").copyInto(hw.vram)
        hw.lcd.readRegisters(lcd.watch("displayio").step(SEED))

        val hooks = WmUnitHooks(
            setClassUnit = { id, jid, al, node -> mu.mapMuSetUnitClass(gmMu, id, jid, al, node) },
            removeUnit = { mu.gmMuRemoveUnit(gmMu, it) },
            showUnit = { mu.gmShowMuUnit(gmMu, it) },
            hideUnit = { mu.gmHideMuUnit(gmMu, it) },
            fadeIn = { i, d -> mu.gmMuStartFadeIn(gmMu, i, d) },
            fadeOut = { i, d -> mu.gmMuStartFadeOut(gmMu, i, d) },
            endUnitFade = unit::endGmapUnitFade,
            unitFadeExists = mu::gmUnitFadeExists,
            setPosition = { i, x, y -> mu.gmMuSetPosition(gmMu, i, x, y) },
            startMoveFree = { input, flag -> automu.startType1(input, flag, null) },
            startMovePaths = { input, flag -> automu.startType0(input, flag, null) },
            autoMuActive = automu::isActiveFor,
            endAutoMu = automu::endFor,
        )
        val unitEvents = WorldMapUnitEvents(mem, hooks)
        val dotEvents = WorldMapDotEvents(mem, dots, nodes)

        val playSt = PlaySt()
        val eventState = EventState()
        val events = EventEngine(engine, eventState, mem)
        val core = EventScrCore(
            eventState, mem, object : EventInput {}, Fe8EventFlags.create(), RngService(), playSt,
            KeyStatus(),
        )
        var endStep = SEED
        fun traceCurrent() = checkNotNull(dump(endStep).find("ProcScr_StdEventEngine")).u32(0x38)
        // Trace steps are VBlank samples, not frames. A long frame (NewMapUnit's graphics load,
        // the start of a mover) spans several samples: the procs that run early in the frame (the
        // event engine, the movers) show their new state before the procs that run late (the
        // unit fade, the dot palette pulse). Those samples are "mid-frame": the frame is run at
        // the first and none of them is compared; a sample where only the late procs ticked
        // completes it; a sample where nothing ticked is a lag step (no frame).
        fun early(s: Int): Boolean {
            val n = dump(s)
            val o = dump(s - 1)
            val ev = n.find("ProcScr_StdEventEngine")!!
            val ov = o.find("ProcScr_StdEventEngine")!!
            if (ev.u32(0x38) != ov.u32(0x38) || ev.u16(0x3E) != ov.u16(0x3E)) return true
            val prims = n.findAll(addr("ProcScr_GMapMuPrim"))
            val oldPrims = o.findAll(addr("ProcScr_GMapMuPrim"))
            if (prims.indices.any { prims[it].u16(0x54) != oldPrims[it].u16(0x54) }) return true
            return false
        }
        fun lateTicks(s: Int): Boolean {
            val pn = dump(s).find("ProcScr_WmDotPalAnim")
            val po = dump(s - 1).find("ProcScr_WmDotPalAnim")
            return dump(s).find("ProcScr_GmapUnitFade") != null &&
                dump(s - 1).find("ProcScr_GmapUnitFade") != null ||
                pn != null && po != null && po.u16(0x2A) in 1..28
        }
        fun late(s: Int): Boolean {
            val pn = dump(s).find("ProcScr_WmDotPalAnim")
            val po = dump(s - 1).find("ProcScr_WmDotPalAnim")
            val fn = dump(s).find("ProcScr_GmapUnitFade")
            val fo = dump(s - 1).find("ProcScr_GmapUnitFade")
            return fn != null && fo != null && fn.u16(0x2E) != fo.u16(0x2E) ||
                pn != null && po != null && pn.u16(0x2A) != po.u16(0x2A)
        }
        fun isMid(s: Int): Boolean {
            val cur = dump(s).find("ProcScr_StdEventEngine")!!.u32(0x38)
            // The script stands on a 9E (the frame is still in the one before it).
            return mem.u16(cur) ushr 8 == 0x9E || early(s) && lateTicks(s) && !late(s)
        }
        fun isLag(s: Int) = !early(s) && lateTicks(s) && !late(s) ||
            dump(s - 1).find("ProcScr_StdEventEngine")!!.u16(0x3E) > 0 &&
            !early(s)
        fun follower() = EventHandler { proc ->
            val here = proc.pEventCurrent
            val next = here + 2 * ((mem.u16(here) ushr 4) and 0xF)
            when (traceCurrent()) {
                here -> EVC_STOP_YIELD
                next -> EVC_ADVANCE_YIELD
                else -> EVC_ADVANCE_CONTINUE
            }
        }
        // Commands that continue into the next one unless the traced script stands on them.
        fun holder() = EventHandler { proc ->
            if (traceCurrent() == proc.pEventCurrent) EVC_STOP_YIELD else EVC_ADVANCE_CONTINUE
        }
        events.register(0x0E, core.event0eStal)
        for (code in listOf(0x1C, 0x1D, 0xC9)) events.register(code, follower())
        for (code in listOf(0xC4, 0xC5, 0xC1)) events.register(code, holder())
        // 17 FADI (the fade locks the script; the fade itself is not part of this window).
        events.register(0x17, EventHandler { proc ->
            proc.evStateBits = proc.evStateBits or EV_STATE_FADEDIN
            EVC_ADVANCE_YIELD
        })
        events.register(0x9E, unitEvents.event9EWmSetClassUnit)
        events.register(0xA0, unitEvents.eventA0WmRemoveUnit)
        events.register(0xA3, unitEvents.eventA3WmShowUnitFaded)
        events.register(0xA5, unitEvents.eventA5WmUnitFadeWait)
        events.register(0xA7, unitEvents.eventA7WmUnitSetPosition)
        events.register(0xA8, unitEvents.eventA8WmUnitMoveFree)
        events.register(0xAC, unitEvents.eventACWmUnitMoveWait)
        events.register(0xBC, dotEvents.eventBCMarkPoint)
        events.register(0xBD, dotEvents.eventBD)
        events.register(0xBE, dotEvents.eventBE)
        val vm = events.create(0x08A39AD0, EV_EXEC_WORLDMAP)
        val engSeed = seed.find("ProcScr_StdEventEngine")!!
        vm.pEventCurrent = engSeed.u32(0x38)
        vm.evStateBits = engSeed.u16(0x3C)
        vm.evStallTimer = engSeed.u16(0x3E)


        val unitScript = addr("ProcScr_GmapUnit")
        fun sprites(): List<List<Int>> {
            val buffer = OamBuffer()
            layers.pushLayerObjects(0) { buffer.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
            return List(buffer.hiPut / 4) { i ->
                List(3) { a -> buffer.buffer[i * 4 + a].toInt() and 0xFFFF }
            }
        }
        var shown = emptyList<List<Int>>()
        var latest = emptyList<List<Int>>()
        fun frame() {
            layers.clear()
            for (i in 0 until 8) engine.run(engine.root(i))
            hw.flushBackgrounds()
            shown = latest
            latest = sprites()
        }
        var compared = 0
        var oamChecked = 0
        var oamStepsChecked = 0
        val oamWatch = lcd.watch("oam")
        var palChecked = 0
        var gmChecked = 0
        var palBefore = hw.paletteRam.map { it.toInt() and 0xFFFF }
        var lagged = 0
        var previous = seed
        var inFrame = false
        var fadeWasLive = false
        for (s in SEED + 1..LAST) {
            step = s
            val now = dump(s)
            val t = now.find("ProcScr_StdEventEngine")!!
            // Inside a frame until a late proc ticks.
            val midFrame = isMid(s) || inFrame && lateTicks(s) && !late(s)
            previous = now
            if (midFrame) {
                if (!inFrame) {
                    endStep = s
                    while (isMid(endStep)) endStep++
                    frame()
                }
                inFrame = true
                lagged++
                continue
            }
            if (inFrame) {
                inFrame = false
            } else if (isLag(s)) {
                lagged++
            } else {
                endStep = s
                frame()
            }
            assertEquals(
                listOf(t.u32(0x38), t.u16(0x3C), t.u16(0x3E)),
                listOf(vm.pEventCurrent, vm.evStateBits, vm.evStallTimer),
                "pEventCurrent / evStateBits / evStallTimer @$s",
            )

            // Units by slot index.
            val traced = now.findAll(unitScript).associateBy { it.u8(0x34) }
            val simUnits = (unit.container!!.state as GmapUnitContainerProc).units
            assertEquals(
                traced.keys.sorted(), simUnits.indices.filter { simUnits[it] != null },
                "unit slots @$s",
            )
            for ((index, p) in traced) {
                val u = simUnits[index]!!.state as GmapUnitProc
                assertEquals(
                    listOf(
                        p.u16(0x2A), p.u16(0x2C), p.u16(0x2E), p.u16(0x30), p.u8(0x35), p.u8(0x36),
                        p.u8(0x37), p.u16(0x38), p.u16(0x3A), p.u16(0x3C), p.u16(0x3E),
                    ),
                    listOf(
                        u.flags, u.layer, u.oam2Base, u.chr, u.faction, u.pal, u.animId and 0xFF,
                        u.classId, u.currentClassId, u.x, u.y,
                    ),
                    "unit $index @$s",
                )
            }
            // Auto mu runs in start order.
            val capAuto = now.findAll(addr("ProcScr_GmapAutoMu"))
            val simAuto = ArrayList<Proc>()
            var ap = engine.find(automu.script)
            while (ap != null) {
                simAuto += ap
                ap = engine.findAfter(automu.script, ap)
            }
            assertEquals(capAuto.size, simAuto.size, "auto mu count @$s")
            for (cap in capAuto) {
                val ours = simAuto
                    .firstOrNull { (it.state as GmapAutoMuProc).unitId == cap.u16(0x2C) }
                    ?: error("auto mu for unit ${cap.u16(0x2C)} missing @$s")
                val a = ours.state as GmapAutoMuProc
                assertEquals(
                    listOf(
                        cap.scrCur, cap.u8(0x29), cap.u8(0x2A), cap.u16(0x3C).toShort().toInt(),
                        cap.u16(0x3A).toShort().toInt(), cap.u16(0x30).toShort().toInt(),
                        cap.u16(0x32).toShort().toInt(), cap.u16(0x34).toShort().toInt(),
                        cap.u16(0x36).toShort().toInt(),
                    ),
                    listOf(
                        ours.scrCur, a.kind, a.flags, a.delay, a.speed, a.srcA, a.srcY, a.dstA,
                        a.dstY,
                    ),
                    "auto mu ${cap.u16(0x2C)} @$s",
                )
            }
            // Dots.
            val capDots = now.findAll(addr("ProcScr_WmPlaceDot"))
            val simDots = ArrayList<Proc>()
            var dp = engine.find(dots.script)
            while (dp != null) {
                simDots += dp
                dp = engine.findAfter(dots.script, dp)
            }
            assertEquals(capDots.size, simDots.size, "dot count @$s")
            for (cap in capDots) {
                val ours = simDots.first { (it.state as WmPlaceDotProc).x == cap.u16(0x2E) }
                val d = ours.state as WmPlaceDotProc
                assertEquals(
                    listOf(
                        cap.scrCur, cap.u8(0x29), cap.u8(0x2A), cap.u8(0x2B), cap.u8(0x2C),
                        cap.u16(0x2E), cap.u16(0x30),
                    ),
                    listOf(ours.scrCur, d.flag, d.effects, d.index, d.tile, d.x, d.y),
                    "dot @$s",
                )
            }
            val capPulse = now.find("ProcScr_WmDotPalAnim")
            val simPulse = engine.find(dots.palAnimScript)
            assertEquals(capPulse != null, simPulse != null, "pulse exists @$s")
            if (capPulse != null && simPulse != null) {
                val pu = simPulse.state as GmapRmBaPalAnimProc
                assertEquals(
                    listOf(capPulse.scrCur, capPulse.u8(0x29), capPulse.u16(0x2A)),
                    listOf(simPulse.scrCur, pu.flag, pu.timer),
                    "pulse @$s",
                )
            }
            // Unit fade.
            val capFade = now.find("ProcScr_GmapUnitFade")
            val simFade = unit.getGmapUnitFade()
            assertEquals(capFade != null, simFade != null, "unit fade exists @$s")
            // The first sample of a fade can show the timer of the previous fade (stale slot).
            val fadeSeen = fadeWasLive
            fadeWasLive = capFade != null
            if (capFade != null && simFade != null) {
                val f = simFade.state as GmapUnitFadeProc
                val count = capFade.u8(0x37)
                assertEquals(
                    listOf(
                        capFade.u8(0x29).toByte().toInt(), capFade.u8(0x2A).toByte().toInt(),
                        capFade.u16(0x2C), if (fadeSeen) capFade.u16(0x2E) else 0, count,
                        List(count) { capFade.u8(0x30 + it) },
                    ),
                    listOf(
                        f.palFrom, f.palTo, f.duration, if (fadeSeen) f.timer else 0, f.count,
                        List(count) { f.units[it] },
                    ),
                    "unit fade @$s",
                )
            }
            // Movers.
            val capPrims = now.findAll(addr("ProcScr_GMapMuPrim"))
            val simPrims = (gmMu.state as GmapMuProc).prims
            for (i in 0 until 7) {
                val c = capPrims[i]
                val m = simPrims[i]!!.state as GmapMuPrimProc
                assertEquals(
                    listOf(c.u8(0x2B).toByte().toInt(), c.u8(0x29) and 1, c.u8(0x2C)),
                    listOf(m.mapUnit, if (m.moving) 1 else 0, m.moveKind),
                    "mover $i @$s",
                )
                if (m.moving && c.u8(0x2C) == 2) {
                    assertEquals(
                        listOf(c.u16(0x54), c.u16(0x44), c.u16(0x46), c.u16(0x42)),
                        listOf(m.timer, m.lastX, m.lastY, m.delay),
                        "mover $i move @$s",
                    )
                }
            }
            // OAM at the full dumps: units and dots must be among the traced entries (the
            // hardware shows the end of the previous pass, or this one's when the sample is late).
            if (s in OAM_DUMPS) {
                val oam = lcd.region("f%05d".format(s), "oam")
                val traced = List(128) { i -> List(3) { a -> u16(oam, i * 8 + a * 2) } }
                // Hidden slots (y 160) and the other sprites (text, arrow, ...) are not ours.
                val expected = traced
                    .filter { it[0] and 0xFF != 0xA0 && (it[2] shr 12) and 0xF in OWN_PALETTES }
                    .map { it.toString() }
                fun matches(list: List<List<Int>>) = list.map { it.toString() } == expected
                assertTrue(
                    matches(latest) || matches(shown),
                    "sprites @$s: ours=$latest previous=$shown traced=$expected",
                )
                // Unit tiles in OBJ VRAM (chr 0x280: four rows of the 0x1C columns the units use)
                // and the sprite sheet the standing frames are copied from.
                for (r in 0..3) {
                    val at = 0x15000 + r * 0x400
                    assertEquals(
                        lcd.region("f%05d".format(s), "vram").copyOfRange(at, at + 0x380).toList(),
                        hw.vram.copyOfRange(at, at + 0x380).toList(),
                        "unit OBJ tiles row $r @$s",
                    )
                }
                assertEquals(
                    lcd.region("f%05d".format(s), "ewram").copyOfRange(0x34010, 0x34010 + 0x6000)
                        .toList(),
                    unitSprites.gfxBuffer.toList(),
                    "gSMSGfxBuffer @$s",
                )
                oamChecked++
            }
            // OBJ palettes 4 and 5 (the dots, 0x140..0x15F) and 0xA (the unit fade) in palette RAM,
            // which shows the buffer of the end of the previous pass (or this one's).
            if (s in 8113..8450) {
                val ref = pal.step(s)
                val colors = (0x140..0x15F) + (0x1A0..0x1AF)
                fun bank(buffer: List<Int>) = colors.map { buffer[it] }
                val traced = colors.map { u16(ref, 2 * it) }
                val now = hw.paletteRam.map { it.toInt() and 0xFFFF }
                assertTrue(
                    traced == bank(now) || traced == bank(palBefore),
                    "OBJ palettes 4/5/0xA @$s",
                )
                palChecked++
            }
            palBefore = hw.paletteRam.map { it.toInt() and 0xFFFF }
            // gGMData units at the dumps of the capture.
            dumpIwram(s)?.let { iw ->
                val traced = GmData(iw, RngService())
                for (i in 0 until 7) {
                    assertEquals(
                        listOf(traced.unitId(i), traced.unitLocation(i), traced.unitState(i)),
                        listOf(gm.unitId(i), gm.unitLocation(i), gm.unitState(i)),
                        "gGMData.units[$i] @$s",
                    )
                }
                gmChecked++
            }
            if (s == 8200) {
                assertEquals(
                    lcd.region("f08200", "vram").copyOfRange(0x133C0, 0x133C0 + 0x40).toList(),
                    hw.vram.copyOfRange(0x133C0, 0x133C0 + 0x40).toList(),
                    "dot tiles @8200",
                )
            }
            // Unit and dot OAM entries every step the OAM watch covers.
            if (s in 8101..8450 && s !in OAM_DUMPS) {
                val oamStep = oamWatch.step(s)
                val expected = List(128) { i -> List(3) { a -> u16(oamStep, i * 8 + a * 2) } }
                    .filter { it[0] and 0xFF != 0xA0 && (it[2] shr 12) and 0xF in OWN_PALETTES }
                    .map { it.toString() }
                // A sample inside a long frame can show some entries of this pass and some of the
                // previous one: each traced entry is one of the two (count of either).
                val now = latest.map { it.toString() }
                val before = shown.map { it.toString() }
                assertTrue(
                    (expected.size == now.size || expected.size == before.size) &&
                        expected.all { it in now || it in before },
                    "sprites @$s: ours=$latest previous=$shown traced=$expected",
                )
                oamStepsChecked++
            }
            compared++
        }
        assertTrue(compared > 1000)
        assertEquals(3, oamChecked)
        assertTrue(oamStepsChecked > 200, "OAM steps: $oamStepsChecked")
        assertTrue(palChecked > 300 && gmChecked > 30, "pal=$palChecked gm=$gmChecked")
    }

    private fun dumpIwram(step: Int): ByteArray? {
        val dir = File(assets, "reference/out/ram_trace_prologue")
        for (prefix in listOf("r", "f")) {
            val file = File(dir, "%s%05d.iwram.bin".format(prefix, step))
            if (file.isFile) return file.readBytes()
        }
        return null
    }

    private fun u16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val SEED = 8100
        const val LAST = 9281
        val OAM_DUMPS = setOf(8200, 8300, 8450)

        /** OBJ palettes of the sprites in this window: dots 4/5, unit fade 0xA, units 0xC..0xE. */
        val OWN_PALETTES = setOf(4, 5, 0xA, 0xC, 0xD, 0xE)
    }
}
