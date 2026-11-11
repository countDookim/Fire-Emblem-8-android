package fe8.game.worldmap

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb

/** `struct GMapMuPrimProc` past `PROC_HEADER` (one world map unit mover); movement fields only. */
public class GmapMuPrimProc {
    /** `flags_0` (+0x29 bit 0). */
    public var flag0: Boolean = false

    /** `flags_1` (+0x29 bit 1). */
    public var flag1: Boolean = false

    /** `flags_2` (+0x29 bit 2). */
    public var flag2: Boolean = false

    /** `flags_3` (+0x29 bit 3): the camera follows the unit. */
    public var flag3: Boolean = false

    /** `flags_4` (+0x29 bit 4). */
    public var flag4: Boolean = false

    /** `flags_5` (+0x29 bit 5). */
    public var flag5: Boolean = false

    /** `unk_2a` (+0x2A, `s8`): the slot index, or -1 when free. */
    public var slot: Int = 0

    /** `unk_2b` (+0x2B, `s8`): the linked `GmapUnit` slot, or -1. */
    public var mapUnit: Int = 0

    /** `unk_2c` (+0x2C, `u8`). */
    public var unk2c: Int = 0

    /** `unk_2d` (+0x2D, `s8`): the source node. */
    public var fromNode: Int = 0

    /** `unk_2e` (+0x2E, `s8`): the destination node. */
    public var toNode: Int = 0

    /** `unk_2f` (+0x2F, `s8`). */
    public var unk2f: Int = 0

    /** `unk_30` (+0x30, `u8`). */
    public var unk30: Int = 0

    /** `unk_31` (+0x31, `s8`): animation id. */
    public var anim1: Int = 0

    /** `unk_32` (+0x32, `s8`): animation id. */
    public var anim2: Int = 0

    /** `unk_34[3].a` (+0x34, stride 4, `s8`). */
    public val trailA: IntArray = IntArray(3)

    /** `unk_34[3].b` (+0x35, stride 4, `s8`). */
    public val trailB: IntArray = IntArray(3)

    /** `unk_40` (+0x40, `s16`). */
    public var trailCount: Int = 0

    /** `unk_42` (+0x42, `s16`). */
    public var delay: Int = 0

    /** `unk_44` (+0x44, `u16`): last x. */
    public var lastX: Int = 0

    /** `unk_46` (+0x46, `u16`): last y. */
    public var lastY: Int = 0

    /** `unk_50` (+0x50). */
    public var unk50: Int = 0

    /** `unk_54` (+0x54). */
    public var unk54: Int = 0
}

/** `struct GMapMuProc` past `PROC_HEADER`. */
public class GmapMuProc {
    /** `unk_2c[7]` (+0x2C): one [GmapMuPrimProc] per world map unit slot. */
    public val prims: Array<Proc?> = arrayOfNulls(SLOTS)

    /** Sizes. */
    public companion object {
        /** Unit slots (`gGMData.units`). */
        public const val SLOTS: Int = 7
    }
}

/**
 * The world map unit mover (`src/worldmap_mapmu.c`), on top of [WorldMapUnit]. Movement routines
 * (`GmMuPrim_80BD444` and later) are not ported yet.
 *
 * @param engine the proc scheduler
 * @param unit the map sprites (`GM_UNITC`)
 * @param gmData `gGMData`
 * @param nodes `gWMNodeData`
 * @param hooks the callees outside this file
 */
public class WorldMapMu(
    private val engine: ProcEngine,
    private val unit: WorldMapUnit,
    private val gmData: GmData,
    private val nodes: WmNodeData,
    private val hooks: Hooks = Hooks(),
) {
    /** The callees of `worldmap_mapmu.c` that live in other files. */
    public class Hooks(
        /** `GetUnit(i)` for the NPC faction: the `(pid, classId)` of a valid unit, else null. */
        public val unitAt: (index: Int) -> Pair<Int, Int>? = { null },
        /** `gCharacterData[pid - 1].defaultClass`. */
        public val defaultClass: (pid: Int) -> Int = { 0 },
        /** `sub_80BD20C(index)`: the faction of slot [index]. */
        public val slotFaction: (index: Int) -> Int = { 0 },
    )

    private fun prim(proc: Proc): GmapMuPrimProc = proc.state as GmapMuPrimProc

    private fun primAt(mu: Proc, index: Int): GmapMuPrimProc =
        prim((mu.state as GmapMuProc).prims[index]!!)

    /**
     * `ProcScr_GMapMuPrim`: the labelled movement branches (labels 1 and 2) are not ported yet.
     *
     * Transcribed from src/worldmap_mapmu.c: ProcScr_GMapMuPrim (not verified)
     */
    public val primScript: ProcScript = ProcScript(
        "ProcScr_GMapMuPrim",
        listOf(
            name("Gmap MU prim"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { }),
            call(ProcFunc { gmMuPrimOnInit(it) }),
            repeat(ProcFunc { }),
            end(),
        ),
    ) { GmapMuPrimProc() }

    /**
     * `ProcScr_GMapMu`.
     *
     * Transcribed from src/worldmap_mapmu.c: ProcScr_GMapMu (not verified)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GMapMu",
        listOf(
            name("Gmap MU"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { }),
            call(ProcFunc { gmMuOnInit(it) }),
            block(),
            end(),
        ),
    ) { GmapMuProc() }

    /** Ported from src/worldmap_mapmu.c: GmMuPrim_OnInit */
    public fun gmMuPrimOnInit(proc: Proc) {
        prim(proc).mapUnit = -1
    }

    /** Ported from src/worldmap_mapmu.c: GmMu_OnInit */
    public fun gmMuOnInit(proc: Proc) {
        val s = proc.state as GmapMuProc
        for (i in 0 until GmapMuProc.SLOTS) {
            s.prims[i] = engine.start(primScript, checkNotNull(proc.parent))
        }
    }

    /** Ported from src/worldmap_mapmu.c: StartGmMu */
    public fun startGmMu(parent: Proc?): Proc = engine.start(script, checkNotNull(parent))

    /** Transcribed from src/worldmap_mapmu.c: GetCharacterClassId (not verified) */
    public fun getCharacterClassId(pid: Int): Int {
        for (i in FACTION_BLUE + 1 until FACTION_PURPLE) {
            val u = hooks.unitAt(i) ?: continue
            if (u.first != pid) continue
            return u.second
        }
        return hooks.defaultClass(pid)
    }

    private fun makeUnit(p: GmapMuPrimProc, classId: Int, nodeId: Int) {
        if (p.mapUnit >= 0) {
            unit.mapUnitCDeleteGmapUnit(p.mapUnit)
            p.mapUnit = -1
        }
        p.mapUnit = unit.newMapUnit(checkNotNull(unit.container), classId, 0)
        unit.mapUnitCSetPosition(p.mapUnit, nodes.x(nodeId), nodes.y(nodeId) + 6)
        unit.mapUnitCSetAnimId(p.mapUnit, 4)
        unit.mapUnitCSetGfxNeedsUpdate(p.mapUnit)
    }

    /** Transcribed from src/worldmap_mapmu.c: MapMUPrim_MakeUnitForChar (not verified) */
    public fun mapMuPrimMakeUnitForChar(proc: Proc, pid: Int, nodeId: Int) {
        makeUnit(prim(proc), getCharacterClassId(pid), nodeId)
    }

    /** Transcribed from src/worldmap_mapmu.c: MapMUPrim_MakeUnitForClass (not verified) */
    public fun mapMuPrimMakeUnitForClass(proc: Proc, jid: Int, nodeId: Int) {
        makeUnit(prim(proc), jid, nodeId)
    }

    /** Transcribed from src/worldmap_mapmu.c: MapMUPrim_RemoveLinkedMapUnit (not verified) */
    public fun mapMuPrimRemoveLinkedMapUnit(proc: Proc) {
        val p = prim(proc)
        if (p.mapUnit >= 0) {
            unit.mapUnitCDeleteGmapUnit(p.mapUnit)
            p.mapUnit = -1
        }
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMu_SetUnit (not verified) */
    public fun gmMuSetUnit(
        mu: Proc,
        index: Int,
        useClass: Boolean,
        id: Int,
        faction: Int,
        nodeId: Int,
    ) {
        val prim = (mu.state as GmapMuProc).prims[index]!!
        val p = prim(prim)
        if (p.slot > -1) gmMuRemoveUnit(mu, index)
        p.slot = index
        if (useClass) {
            mapMuPrimMakeUnitForClass(prim, id, nodeId)
            gmData.setUnitId(index, id)
            gmData.setUnitState(index, gmData.unitState(index) or 2)
        } else {
            mapMuPrimMakeUnitForChar(prim, id, nodeId)
            gmData.setUnitId(index, id)
            gmData.setUnitState(index, gmData.unitState(index) and 2.inv())
        }
        gmData.setUnitLocation(index, nodeId)
        val f = if (faction < 0) hooks.slotFaction(index) else faction
        unit.mapUnitCSetFaction(p.mapUnit, f)
        p.trailCount = 0
        p.unk2c = 0
        p.flag0 = false
        p.flag2 = false
        p.flag3 = false
        gmMuSetSpriteLayer(mu, index, SPRITE_LAYER[index])
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMu_RemoveUnit (not verified) */
    public fun gmMuRemoveUnit(mu: Proc, index: Int) {
        val prim = (mu.state as GmapMuProc).prims[index]!!
        val p = prim(prim)
        if (p.mapUnit >= 0) {
            p.slot = -1
            mapMuPrimRemoveLinkedMapUnit(prim)
            // Both branches of the original clear the id and keep bit 1 of the state as it was.
            gmData.setUnitId(index, 0)
            gmData.setUnitState(index, gmData.unitState(index) and 1.inv())
        }
    }

    /** Transcribed from src/worldmap_mapmu.c: GmShowMuUnit (not verified); [index] < 0 = all. */
    public fun gmShowMuUnit(mu: Proc, index: Int): Unit = setShown(mu, index, true)

    /** Transcribed from src/worldmap_mapmu.c: GmHideMuUnit (not verified); [index] < 0 = all. */
    public fun gmHideMuUnit(mu: Proc, index: Int): Unit = setShown(mu, index, false)

    private fun setShown(mu: Proc, index: Int, shown: Boolean) {
        val bit = if (shown) 1 else 1.inv()
        fun apply(i: Int) {
            unit.mapUnitCSetDisplayEnabled(primAt(mu, i).mapUnit, shown)
            val st = gmData.unitState(i)
            gmData.setUnitState(i, if (shown) st or 1 else st and bit)
        }
        if (index >= 0) {
            apply(index)
        } else {
            for (i in 0 until GmapMuProc.SLOTS) if (primAt(mu, i).mapUnit >= 0) apply(i)
        }
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMu_SetSpriteLayer (not verified) */
    public fun gmMuSetSpriteLayer(mu: Proc, index: Int, layer: Int) {
        unit.mapUnitCSetLayer(primAt(mu, index).mapUnit, layer)
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMu_SetPosition (not verified) */
    public fun gmMuSetPosition(mu: Proc, index: Int, xBase: Int, yBase: Int) {
        val p = primAt(mu, index)
        val x = xBase.toShort().toInt()
        val y = (yBase + 6).toShort().toInt()
        unit.mapUnitCSetPosition(p.mapUnit, x, y)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
        p.trailCount = 0
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMu_GetPosition (not verified); returns (x, y). */
    public fun gmMuGetPosition(mu: Proc, index: Int): Pair<Int, Int> {
        val (x, y) = unit.mapUnitCGetPosition(primAt(mu, index).mapUnit)
        return x to (y - 6)
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMu_SetNode (not verified) */
    public fun gmMuSetNode(mu: Proc, index: Int, nodeId: Int) {
        gmMuSetPosition(mu, index, nodes.x(nodeId), nodes.y(nodeId))
    }

    private companion object {
        const val PROC_MARK_WMSTUFF = 8

        /** `FACTION_BLUE` and `FACTION_PURPLE`. */
        const val FACTION_BLUE = 0x00
        const val FACTION_PURPLE = 0xC0

        /** `gGMapMuSpriteLayerLut`. */
        val SPRITE_LAYER = intArrayOf(8, 9, 9, 9, 9, 9, 9)
    }
}
