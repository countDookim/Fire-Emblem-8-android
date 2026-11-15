package fe8.testing

import fe8.game.RomView
import fe8.game.bm.AiIntStep
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.CombatAction
import fe8.game.bm.ItemInfo
import fe8.game.bm.UnitLoader
import gbafe.core.mechanics.Mechanics
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScripts
import fe8.game.bm.AiStep
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.CpDecide
import fe8.game.bm.CpPerform
import fe8.game.bm.AiState
import fe8.game.bm.ActiveUnit
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MapCursor
import fe8.game.bm.MovementMap
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuUnit
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The AI perform proc (EP-8) wired over the capture state of frame [seed]: `CpDecide` for one
 * unit with the decision of the capture fed in (the decision code is EP-3a..EP-7), then
 * `gProcScr_CpPerform` for real. [frame] runs one engine frame; the capture frame it shows is
 * `seed + frames run`.
 */
internal class CpPerformSim(
    val data: ReferenceData.Run,
    val ref: ReferenceData,
    val rom: RomView,
    val romBytes: ByteArray,
    val seed: Int,
    val unitId: Int,
) {
    val engine = ProcEngine()
    val bm = BmSt()
    val playSt = PlaySt()
    val units = UnitTable(rom)
    val keys = KeyStatus()
    val rng = RngService()
    val active = ActiveUnit()
    val aiState = AiState()
    val map: BmMap
    val camMove = CamMove(engine, bm)
    val mu = Mu(engine, bm, playSt, keys, camMove.script, ClassHooks())
    val cursor: MapCursor
    val phase: PlayerPhase
    val decide: CpDecide
    val perform: CpPerform
    val workingScript = IntArray(0x40)
    val loader = UnitLoader(units, rom, { 0 }, rng, { false })
    val battle: BmBattle
    val gen: BattleGenerate
    val combat: CombatAction

    /** The capture step whose procs the map animation placeholder follows. */
    var stepOfFrame = 0
    var combatStartStep = -1
    val log = ArrayList<String>()

    /** The capture's `gAiDecision` fed to the decision loop (bytes of the watch). */
    var fed: ByteArray = ByteArray(12)
    var spritePalette = 0
    var frames = 0

    init {
        data.watch("blue").step(seed).copyInto(units.bytes, 0)
        data.watch("red").step(seed).copyInto(units.bytes, 62 * 0x48)
        playSt.decode(data.watch("gplayst").step(seed), 0)
        bm.decode(data.watch("gbmst").step(seed), 0)
        val ad = data.watch("actiondata")
        val a = active.action
        a.subjectIndex = ad.u8(seed, 0x0C)
        a.targetIndex = ad.u8(seed, 0x0D)
        a.xMove = ad.u8(seed, 0x0E)
        a.yMove = ad.u8(seed, 0x0F)
        a.moveCount = ad.u8(seed, 0x10)
        a.unitActionType = ad.u8(seed, 0x11)
        a.itemSlotIndex = ad.u8(seed, 0x12)
        a.xOther = ad.u8(seed, 0x13)
        a.yOther = ad.u8(seed, 0x14)
        a.trapType = ad.u8(seed, 0x15)
        a.suspendPointType = ad.u8(seed, 0x16)
        map = BmMap(rom, ChapterData(rom), bm)
        map.initChapterMap(playSt.chapterIndex)
        val entityMaps = EntityBmMaps(map, units, playSt)
        val moves = MovementMap(rom, map, units, playSt)
        cursor = MapCursor(rom, bm, SpriteLayers())
        phase = PlayerPhase(
            engine, bm, playSt, map, units, camMove, entityMaps,
            BmMapRender(bm, map, BgHardware()), keys, { false }, active = active,
            movementMap = moves,
        )
        decide = CpDecide(engine, units, aiState, active, null, entityMaps)
        perform = CpPerform(
            engine, bm, playSt, units, ItemInfo(rom), entityMaps, active, decide.decision, map,
            moves, camMove, mu, phase, keys, rng, workingScript,
        )
        battle = BmBattle(rom, units, loader, playSt, bm) { y, x -> map.terrain[y, x] }
        gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics()).also {
            it.bmMap = map
            it.unitTable = units
        }
        val e = data.watch("bactor").step(seed)
        e.copyInto(battle.actor.bytes, 0, 0, BattleUnit.SIZE)
        data.watch("btarget").step(seed).copyInto(battle.target.bytes, 0, 0, BattleUnit.SIZE)
        battle.stats.decode(data.watch("bstats").step(seed), 0)
        combat = CombatAction(engine, battle, gen, units, active)
        phase.hooks.actionCombat = ProcBoolFunc { combat.actionCombat(it) }
        combat.hooks.itemGotPopup = CombatAction.ItemPopup { _, _, _ -> error("no item drop") }
        // The map battle animation (M6-H) is a placeholder: it locks the game and ends when the
        // capture's `ProcScr_MapAnimBattle` proc is gone (as MC-13's test does).
        // From the attack on, engine frame f is capture step f + 1 (see the action test).
        val animation = ProcScript(
            "map animation (test placeholder)",
            listOf(
                ProcScripts.whileTrue(
                    ProcBoolFunc {
                        stepOfFrame == combatStartStep ||
                            ProcArrayDump.ofWatch(data.watch("procs"), stepOfFrame + 1)
                                .find(MAP_ANIM_BATTLE) != null
                    },
                ),
                ProcScripts.call(ProcFunc { bm.lock = (bm.lock - 1) and 0xFF }),
                ProcScripts.end(),
            ),
        )
        combat.hooks.beginBattleAnimations = ProcFunc {
            battle.stats.config = battle.stats.config or BattleStats.CONFIG_MAPANIMS
            bm.lock = (bm.lock + 1) and 0xFF
            combatStartStep = stepOfFrame
            engine.start(animation, 3)
        }
        perform.hooks.endAllMus = AiStep { mu.endAllMus() }
        perform.hooks.showUnitSprite = AiIntStep { log += "show ${Integer.toHexString(it)}" }
        val h = perform.hooks
        val muUnit = MuUnit(address = 0, xPos = 0, yPos = 0, classId = 0, state = 0)
        h.startMu = AiIntStep { id ->
            muUnit.address = UnitTable.BLUE_ADDRESS + units.unitOffset(id)
            muUnit.xPos = units.xPos(id)
            muUnit.yPos = units.yPos(id)
            muUnit.classId = units.classId(id)
            muUnit.state = units.state(id)
            muUnit.spritePalette = spritePalette
            mu.startMu(muUnit)
        }
        h.setAutoMuDefaultFacing = AiStep {
            val proc = engine.find(mu.script)
            if (proc != null) mu.setMuDefaultFacing(proc.state as fe8.game.bm.MuProc)
        }
        h.putMapCursor = PlayerPhase.PutMapCursor { x, y, kind -> cursor.put(x, y, kind) }
        h.hideUnitSprite = AiIntStep { log += "hide ${Integer.toHexString(it)}" }
        decide.hooks.startCpPerform = perform.start
        decide.aiDecideMainFunc = AiStep { feed() }
        aiState.units[0] = unitId.toByte()
        aiState.units[1] = 0
    }

    private fun feed() {
        val d = decide.decision
        d.actionId = fed[0].toInt() and 0xFF
        d.unitId = fed[1].toInt() and 0xFF
        d.xMove = fed[2].toInt() and 0xFF
        d.yMove = fed[3].toInt() and 0xFF
        d.unk04 = fed[4].toInt() and 0xFF
        d.unk05 = fed[5].toInt() and 0xFF
        d.targetId = fed[6].toInt() and 0xFF
        d.itemSlot = fed[7].toInt() and 0xFF
        d.xTarget = fed[8].toInt() and 0xFF
        d.yTarget = fed[9].toInt() and 0xFF
        d.actionPerformed = fed[10].toInt() != 0
    }

    /**
     * Arms the first frame: a root 2 proc (the tree of `gProc_BMapMain`) starts `CpDecide` the way
     * `CpOrder` does, blocking it, in the engine frame that shows capture frame `seed + 1`.
     */
    fun arm() {
        val decision = data.watch("aidecision").step(seed + 1)
        fed = decision
        engine.start(
            ProcScript(
                "starter",
                listOf(
                    repeat {
                        engine.startBlocking(decide.script, it)
                        engine.breakProc(it)
                    },
                    end(),
                ),
            ),
            2,
        )
    }

    /** Runs one engine frame (every root tree in order). */
    fun frame() {
        stepOfFrame = seed + 2 + frames
        for (t in 0 until 8) {
            if (t == 2 && bm.lock != 0) continue
            engine.run(engine.root(t))
        }
        frames++
    }

    /** The EWRAM or IWRAM dump of capture frame [f] (`r` per-frame dumps, `f` full dumps). */
    fun dumpName(f: Int): String {
        val r = "r%05d".format(f)
        val per = java.io.File(ref.root, "${data.name}/$r.ewram.bin")
        return if (per.isFile) r else "f%05d".format(f)
    }

    fun ewram(f: Int): ByteArray = data.region(dumpName(f), "ewram")

    fun iwram(f: Int): ByteArray = data.region(dumpName(f), "iwram")

    /** Class data from the ROM (read at test time, not copied). */
    inner class ClassHooks : MuHooks {
        private fun classByte(jid: Int, o: Int): Int =
            romBytes[GCLASS_DATA - ROM_BASE + (jid - 1) * CLASS_SIZE + o].toInt() and 0xFF

        override fun classAttributes(jid: Int): Int = classByte(jid, 0x28) or
            (classByte(jid, 0x29) shl 8) or (classByte(jid, 0x2A) shl 16) or
            (classByte(jid, 0x2B) shl 24)

        override fun classSlowWalking(jid: Int): Int = classByte(jid, 0x07)
    }

    companion object {
        /** The sim armed over the capture state of frame [seed] for [unit] (skips without data). */
        fun create(
            ref: ReferenceData,
            romFile: java.io.File,
            run: String,
            seed: Int,
            unit: Int,
            rngStep: Int = seed + 1,
        ): CpPerformSim {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            val data = ref.run(run)
            assumeTrue(data.hasWatch("aidecision"), "run has no aidecision watch")
            val bytes = romFile.readBytes()
            val sim = CpPerformSim(data, ref, RomView.ofRom(bytes), bytes, seed, unit)
            val rn = data.watch("rng")
            sim.rng.primary.load(rn.u16(rngStep, 0), rn.u16(rngStep, 2), rn.u16(rngStep, 4))
            sim.spritePalette = sim.iwram(seed + 1)[CFG - GbaMem.IWRAM + 1].toInt() and 0xFF
            val e = sim.ewram(seed)
            sim.cursor.lastCoordX = GbaMem.u16(e, GbaMem.EWRAM, LAST_COORD).toShort().toInt()
            sim.cursor.lastCoordY = GbaMem.u16(e, GbaMem.EWRAM, LAST_COORD + 2).toShort().toInt()
            sim.cursor.lastTimeDrawn = GbaMem.u32(e, GbaMem.EWRAM, LAST_TIME)
            sim.arm()
            return sim
        }

        const val MAP_ANIM_BATTLE = 0x089A3508
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val ROM_BASE = 0x08000000
        const val CFG = 0x03001900
        const val GAME_CLOCK = 0x03000014
        const val LAST_COORD = 0x0202BD3C
        const val LAST_TIME = 0x0202BD40
        val IWRAM = GbaMem.IWRAM
        val EWRAM = GbaMem.EWRAM
    }
}
