package fe8.game.worldmap

import gbafe.core.math.BiosMath
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import kotlin.math.abs

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

    /** `unk_48` (+0x48, `s16`). */
    public var unk48: Int = 0

    /** `unk_4a` (+0x4A, `s16`). */
    public var unk4a: Int = 0

    /** `unk_4c` (+0x4C, `s16`). */
    public var unk4c: Int = 0

    /** `unk_4e` (+0x4E, `s16`). */
    public var unk4e: Int = 0

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
 * The world map unit mover (`src/worldmap_mapmu.c`), on top of [WorldMapUnit]. The spline path
 * (`sub_80BCE34`, `sub_800A950`) and the footstep sound are [Hooks].
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
        /** `GetWMCenteredCameraPosition(x, y, &xCam, &yCam)`: returns `(xCam, yCam)`. */
        public val centeredCamera: (x: Int, y: Int) -> Pair<Int, Int> = { _, _ -> 0 to 0 },
        /** `gKeyStatusPtr->heldKeys`. */
        public val heldKeys: () -> Int = { 0 },
        /** `sub_80BE45C(proc)`: the footstep sound of the unit (not ported). */
        public val stepSound: (GmapMuPrimProc) -> Unit = { },
        /**
         * Spline setup of `GmMuPrim_80BD444`: `sub_80BCE34` and the `Struct02003BE8` for slot
         * `p.slot` (not ported).
         */
        public val splineStart: (GmapMuPrimProc) -> Unit = { },
        /** `sub_800A950(&gUnknown_0201B3A4[slot], t * 0x1000, coord)`: returns `(x, y)` in 1/16. */
        public val splineAt: (slot: Int, t: Int) -> Pair<Int, Int> = { _, _ -> 0 to 0 },
    )

    private fun prim(proc: Proc): GmapMuPrimProc = proc.state as GmapMuPrimProc

    private fun primAt(mu: Proc, index: Int): GmapMuPrimProc =
        prim((mu.state as GmapMuProc).prims[index]!!)

    /**
     * `ProcScr_GMapMuPrim`.
     *
     * Transcribed from src/worldmap_mapmu.c: ProcScr_GMapMuPrim (not verified: no @VerifiesPort
     * test names it)
     */
    public val primScript: ProcScript = ProcScript(
        "ProcScr_GMapMuPrim",
        listOf(
            name("Gmap MU prim"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { }),
            call(ProcFunc { gmMuPrimOnInit(it) }),
            label(0),
            repeat(ProcFunc { }),
            label(1),
            call(ProcFunc { gmMuPrim80BD444(it) }),
            repeat(ProcFunc { gmMuPrim80BD830(it) }),
            goto(0),
            label(2),
            call(ProcFunc { gmMuPrim80BD9D8(it) }),
            repeat(ProcFunc { gmMuPrim80BDA78(it) }),
            goto(0),
            end(),
        ),
    ) { GmapMuPrimProc() }

    /**
     * `ProcScr_GMapMu`.
     *
     * Transcribed from src/worldmap_mapmu.c: ProcScr_GMapMu (not verified: no @VerifiesPort test
     * names it)
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

    /**
     * Transcribed from src/worldmap_mapmu.c: GetCharacterClassId (not verified: no @VerifiesPort
     * test names it)
     */
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

    /**
     * Transcribed from src/worldmap_mapmu.c: MapMUPrim_MakeUnitForChar (not verified: no
     * @VerifiesPort test names it)
     */
    public fun mapMuPrimMakeUnitForChar(proc: Proc, pid: Int, nodeId: Int) {
        makeUnit(prim(proc), getCharacterClassId(pid), nodeId)
    }

    /**
     * Ported from src/worldmap_mapmu.c: MapMUPrim_MakeUnitForClass
     */
    public fun mapMuPrimMakeUnitForClass(proc: Proc, jid: Int, nodeId: Int) {
        makeUnit(prim(proc), jid, nodeId)
    }

    /**
     * Ported from src/worldmap_mapmu.c: MapMUPrim_RemoveLinkedMapUnit
     */
    public fun mapMuPrimRemoveLinkedMapUnit(proc: Proc) {
        val p = prim(proc)
        if (p.mapUnit >= 0) {
            unit.mapUnitCDeleteGmapUnit(p.mapUnit)
            p.mapUnit = -1
        }
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_SetUnit
     */
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

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_RemoveUnit
     */
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

    /**
     * Ported from src/worldmap_mapmu.c: GmShowMuUnit
     *
     * [index] < 0 = all (that form is not run by the capture).
     */
    public fun gmShowMuUnit(mu: Proc, index: Int): Unit = setShown(mu, index, true)

    /**
     * Ported from src/worldmap_mapmu.c: GmHideMuUnit
     *
     * [index] < 0 = all (that form is not run by the capture).
     */
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

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_SetSpriteLayer
     */
    public fun gmMuSetSpriteLayer(mu: Proc, index: Int, layer: Int) {
        unit.mapUnitCSetLayer(primAt(mu, index).mapUnit, layer)
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_SetPosition
     */
    public fun gmMuSetPosition(mu: Proc, index: Int, xBase: Int, yBase: Int) {
        val p = primAt(mu, index)
        val x = xBase.toShort().toInt()
        val y = (yBase + 6).toShort().toInt()
        unit.mapUnitCSetPosition(p.mapUnit, x, y)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
        p.trailCount = 0
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_GetPosition (not verified: no @VerifiesPort test
     * names it); returns (x, y).
     */
    public fun gmMuGetPosition(mu: Proc, index: Int): Pair<Int, Int> {
        val (x, y) = unit.mapUnitCGetPosition(primAt(mu, index).mapUnit)
        return x to (y - 6)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_SetNode (not verified: no @VerifiesPort test
     * names it)
     */
    public fun gmMuSetNode(mu: Proc, index: Int, nodeId: Int) {
        gmMuSetPosition(mu, index, nodes.x(nodeId), nodes.y(nodeId))
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BD41C (not verified: no @VerifiesPort test names
     * it)
     */
    public fun sub80BD41C(a: Int, b: Int): Int = BiosMath.sqrt((a * a + b * b) * 16) / 5

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BDD94 (not verified: no @VerifiesPort test names
     * it); [index] < 0 = all.
     */
    public fun sub80BDD94(mu: Proc, index: Int) {
        val s = mu.state as GmapMuProc
        if (index >= 0) {
            mapMuPrimRemoveLinkedMapUnit(s.prims[index]!!)
        } else {
            for (i in 0 until GmapMuProc.SLOTS) mapMuPrimRemoveLinkedMapUnit(s.prims[i]!!)
        }
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMuPrim_80BD444 (not verified: no traced caller) */
    public fun gmMuPrim80BD444(proc: Proc) {
        val p = prim(proc)
        if (p.unk50 < 0) {
            p.unk50 = sub80BD41C(
                nodes.x(p.toNode) - nodes.x(p.fromNode),
                nodes.y(p.toNode) - nodes.y(p.fromNode),
            )
        }
        if (nodes.shipTravelFlag(p.fromNode) and 1 != 0 &&
            nodes.shipTravelFlag(p.toNode) and 1 != 0
        ) {
            unit.sub80BB0E0(p.mapUnit, 0x50)
        }
        hooks.splineStart(p)
        unit.mapUnitCSetPosition(p.mapUnit, nodes.x(p.fromNode), nodes.y(p.fromNode) + 6)
        if (p.anim1 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim1)
        p.unk54 = 0
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
        p.trailCount = 0
    }

    /** `sub_80BD5B8`: records the last step in the trail; true if the unit moved. */
    private fun recordStep(p: GmapMuPrimProc): Boolean {
        val (x1, y1) = unit.mapUnitCGetPosition(p.mapUnit)
        val x2 = (x1 - p.lastX) and 0xFFFF
        val y2 = (y1 - p.lastY) and 0xFFFF
        if (x2 == 0 && y2 == 0) return false
        if (p.trailCount >= 3) {
            for (i in 0 until 2) {
                p.trailA[i] = p.trailA[i + 1]
                p.trailB[i] = p.trailB[i + 1]
            }
            p.trailA[2] = x2.toByte().toInt()
            p.trailB[2] = y2.toByte().toInt()
        } else {
            p.trailA[p.trailCount] = x2.toByte().toInt()
            p.trailB[p.trailCount] = y2.toByte().toInt()
            p.trailCount++
        }
        return true
    }

    /** `sub_80BD660`: the facing animation for an angle. */
    private fun facingAnim(a: Int): Int {
        if (((a + 0x4D00) and 0xFFFF) < 0x1A00) return 3
        if (a < 0x3300 || a >= 0x4D00) return ((a - 0x4000) and 0xFFFF) shr 15
        return 2
    }

    /** `sub_80BD6A8`: the animation for the trail so far, or -1. */
    private fun trailAnim(p: GmapMuPrimProc): Int {
        var a = 0
        var b = 0
        for (i in 0 until p.trailCount) {
            a = (a + p.trailA[i]).toShort().toInt()
            b = (b + p.trailB[i]).toShort().toInt()
            if (BiosMath.sqrt((a * a + b * b) shl 8) > 35) {
                return facingAnim(BiosMath.arcTan2(a, b) and 0xFFFF)
            }
        }
        return -1
    }

    /** `sub_80BD740`: per-step animation, camera and last position. */
    private fun afterStep(p: GmapMuPrimProc) {
        if (!recordStep(p)) return
        if (p.flag5) {
            hooks.stepSound(p)
            if (p.flag2 && hooks.heldKeys() and A_BUTTON != 0) hooks.stepSound(p)
        }
        val anim = trailAnim(p)
        if (anim >= 0 && unit.mapUnitCGetAnimId(p.mapUnit) != anim && p.flag4) {
            unit.mapUnitCSetAnimId(p.mapUnit, anim)
            unit.mapUnitCSetGfxNeedsUpdate(p.mapUnit)
        }
        followCamera(p)
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
    }

    /** `unk_42` delay and the `unk_54` timer increment shared by both repeat routines. */
    private fun advanceTimer(p: GmapMuPrimProc): Boolean {
        if (p.flag1) return false
        if (p.delay > 0) {
            p.delay--
            return false
        }
        if (p.flag2 && hooks.heldKeys() and A_BUTTON != 0) p.unk54 += 2
        p.unk54++
        return true
    }

    private fun finishMove(proc: Proc, p: GmapMuPrimProc) {
        followCamera(p)
        engine.breakProc(proc)
        p.flag0 = false
    }

    /** Transcribed from src/worldmap_mapmu.c: GmMuPrim_80BD830 (not verified: no traced caller) */
    public fun gmMuPrim80BD830(proc: Proc) {
        val p = prim(proc)
        if (!advanceTimer(p)) return
        if (p.unk54 < p.unk50) {
            val (cx, cy) = hooks.splineAt(p.slot, p.unk54)
            unit.mapUnitCSetPosition(p.mapUnit, cx shr 4, (cy shr 4) + 6)
            afterStep(p)
        } else {
            if (nodes.shipTravelFlag(p.fromNode) and 1 != 0 &&
                nodes.shipTravelFlag(p.toNode) and 1 != 0
            ) {
                unit.sub80BB0E0(p.mapUnit, 0)
            }
            unit.mapUnitCSetPosition(p.mapUnit, nodes.x(p.toNode), nodes.y(p.toNode) + 6)
            if (p.flag4 && p.anim2 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim2)
            gmData.setUnitLocation(p.slot, p.toNode)
            finishMove(proc, p)
        }
    }

    /** Ported from src/worldmap_mapmu.c: GmMuPrim_80BD9D8 */
    public fun gmMuPrim80BD9D8(proc: Proc) {
        val p = prim(proc)
        if (p.unk50 < 0) {
            // The original indexes the node table with the coordinates (unk_4c, unk_48, ...).
            p.unk50 = sub80BD41C(
                nodes.x(p.unk4c) - nodes.x(p.unk48),
                nodes.y(p.unk4e) - nodes.y(p.unk4a),
            )
        }
        p.unk54 = 0
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
        if (p.anim1 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim1)
        p.trailCount = 0
    }

    /** Ported from src/worldmap_mapmu.c: GmMuPrim_80BDA78 */
    public fun gmMuPrim80BDA78(proc: Proc) {
        val p = prim(proc)
        if (!advanceTimer(p)) return
        if (p.unk54 < p.unk50) {
            val coeff = Spline.divArm2(p.unk54, p.unk50, p.unk2f)
            val x = (p.unk48 + Spline.divArm(0x1000, (p.unk4c - p.unk48) * coeff))
                .toShort().toInt()
            val y = (p.unk4a + Spline.divArm(0x1000, (p.unk4e - p.unk4a) * coeff))
                .toShort().toInt()
            unit.mapUnitCSetPosition(p.mapUnit, x, y + 6)
            afterStep(p)
        } else {
            unit.mapUnitCSetPosition(p.mapUnit, p.unk4c, p.unk4e + 6)
            if (p.flag4 && p.anim2 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim2)
            finishMove(proc, p)
        }
    }

    /** `struct UnknownSub80BDEB4` (field names as in `include/worldmap.h`). */
    public class MoveInput(
        /** Field `unk_00`. */
        public val unk00: Int,
        /** Field `unk_01`. */
        public val unk01: Int,
        /** Field `unk_02`. */
        public val unk02: Int,
        /** Field `unk_03`. */
        public val unk03: Int,
        /** Field `unk_04`. */
        public val unk04: Int,
        /** Field `unk_06`. */
        public val unk06: Int,
        /** Field `unk_08`. */
        public val unk08: Int,
        /** Field `unk_0a`. */
        public val unk0a: Int,
        /** Field `unk_0c`. */
        public val unk0c: Int,
    )

    /** `struct UnknownSub80BDFA4` (field names as in `include/worldmap.h`). */
    public class MoveInput2(
        /** Field `unk_00`. */
        public val unk00: Int,
        /** Field `unk_01`. */
        public val unk01: Int,
        /** Field `unk_02`. */
        public val unk02: Int,
        /** Field `unk_03`. */
        public val unk03: Int,
        /** Field `unk_04`. */
        public val unk04: Int,
        /** Field `unk_05`. */
        public val unk05: Int,
        /** Field `unk_06`. */
        public val unk06: Int,
        /** Field `unk_08`. */
        public val unk08: Int,
        /** Field `unk_0a`. */
        public val unk0a: Int,
        /** Field `unk_0c`. */
        public val unk0c: Int,
        /** Field `unk_0e`. */
        public val unk0e: Int,
        /** Field `unk_10`. */
        public val unk10: Int,
    )

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BDEB4 (not verified: no @VerifiesPort test names
     * it)
     */
    public fun sub80BDEB4(mu: Proc, input: MoveInput) {
        val proc = (mu.state as GmapMuProc).prims[input.unk00]!!
        val p = prim(proc)
        p.fromNode = input.unk06.toByte().toInt()
        p.toNode = input.unk08.toByte().toInt()
        var a = input.unk0c
        if (a < 0) {
            val tmp = sub80BD41C(
                abs(nodes.x(p.toNode) - nodes.x(p.fromNode)),
                abs(nodes.y(p.toNode) - nodes.y(p.fromNode)),
            )
            a = tmp * abs(input.unk0c)
        }
        p.unk50 = a
        p.flag2 = input.unk01 != 0
        p.delay = input.unk0a.toShort().toInt()
        p.flag0 = true
        p.flag1 = false
        p.unk2c = 1
        p.flag4 = true
        p.flag5 = input.unk02 != 0
        p.unk30 = 0
        p.anim1 = input.unk03.toByte().toInt()
        p.anim2 = input.unk04.toByte().toInt()
        engine.goto(proc, 1)
    }

    /**
     * Ported from src/worldmap_mapmu.c: sub_80BDFA4
     */
    public fun sub80BDFA4(mu: Proc, input: MoveInput2) {
        val proc = (mu.state as GmapMuProc).prims[input.unk00]!!
        val p = prim(proc)
        p.unk48 = input.unk06.toShort().toInt()
        p.unk4a = input.unk08.toShort().toInt()
        p.unk4c = input.unk0a.toShort().toInt()
        p.unk4e = input.unk0c.toShort().toInt()
        p.unk50 = input.unk10
        p.flag2 = input.unk02 != 0
        p.unk2f = input.unk01.toByte().toInt()
        p.delay = input.unk0e.toShort().toInt()
        p.flag0 = true
        p.flag1 = false
        p.unk2c = 2
        p.flag4 = true
        p.flag5 = input.unk03 != 0
        p.unk30 = 0
        p.anim1 = input.unk04.toByte().toInt()
        p.anim2 = input.unk05.toByte().toInt()
        engine.goto(proc, 2)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_PauseMovement (not verified: no @VerifiesPort
     * test names it)
     */
    public fun gmMuPauseMovement(mu: Proc, index: Int) {
        primAt(mu, index).flag1 = true
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_ResumeMovement (not verified: no @VerifiesPort
     * test names it)
     */
    public fun gmMuResumeMovement(mu: Proc, index: Int) {
        primAt(mu, index).flag1 = false
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE080 (not verified: no @VerifiesPort test names
     * it)
     */
    public fun sub80BE080(mu: Proc, index: Int, blendEnabled: Boolean) {
        unit.mapUnitCSetBlendEnabled(primAt(mu, index).mapUnit, blendEnabled)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE0A4 (not verified: no @VerifiesPort test names
     * it); returns (x, y).
     */
    public fun sub80BE0A4(mu: Proc, index: Int): Pair<Int, Int> =
        unit.mapUnitCGetPosition(primAt(mu, index).mapUnit)

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE0C8 (not verified: no @VerifiesPort test names
     * it)
     */
    public fun sub80BE0C8(mu: Proc, index: Int): Int =
        unit.mapUnitCGetLayer(primAt(mu, index).mapUnit)

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_80BE108 (not verified: no @VerifiesPort test
     * names it)
     */
    public fun gmMu80BE108(mu: Proc, index: Int, flag: Boolean) {
        primAt(mu, index).flag3 = flag
    }

    /**
     * Ported from src/worldmap_mapmu.c: sub_80BE12C
     */
    public fun sub80BE12C(mu: Proc, index: Int): Boolean {
        if (index < 0) {
            for (i in 0 until GmapMuProc.SLOTS) {
                val p = primAt(mu, i)
                if (p.mapUnit >= 0 && p.flag0) return true
            }
        } else {
            val p = primAt(mu, index)
            if (p.mapUnit >= 0) return p.flag0
        }
        return false
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE194 (not verified: no @VerifiesPort test names
     * it)
     */
    public fun sub80BE194(proc: Proc) {
        val p = prim(proc)
        if (!p.flag0) return
        when (p.unk2c) {
            1 -> {
                if (nodes.shipTravelFlag(p.fromNode) and 1 != 0 &&
                    nodes.shipTravelFlag(p.toNode) and 1 != 0
                ) {
                    unit.sub80BB0E0(p.mapUnit, 0)
                }
                unit.mapUnitCSetPosition(p.mapUnit, nodes.x(p.toNode), nodes.y(p.toNode) + 6)
                unit.mapUnitCSetAnimId(p.mapUnit, 4)
                gmData.setUnitLocation(p.slot, p.toNode)
                followCamera(p)
            }
            2 -> {
                unit.mapUnitCSetPosition(p.mapUnit, p.unk4c, p.unk4e + 6)
                unit.mapUnitCSetAnimId(p.mapUnit, 4)
                followCamera(p)
            }
        }
        p.flag0 = false
        engine.goto(proc, 0)
    }

    private fun followCamera(p: GmapMuPrimProc) {
        if (!p.flag3) return
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        val (cx, cy) = hooks.centeredCamera(x, y - 14)
        gmData.xCamera = cx
        gmData.yCamera = cy
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE330 (not verified: no @VerifiesPort test names
     * it); [index] < 0 = all.
     */
    public fun sub80BE330(mu: Proc, index: Int) {
        val s = mu.state as GmapMuProc
        if (index < 0) {
            for (i in 0 until GmapMuProc.SLOTS) sub80BE194(s.prims[i]!!)
        } else {
            sub80BE194(s.prims[index]!!)
        }
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE3E8 (not verified: no @VerifiesPort test names
     * it)
     */
    public fun sub80BE3E8(mu: Proc, index: Int, flag: Boolean) {
        primAt(mu, index).flag2 = flag
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_StartFadeIn
     */
    public fun gmMuStartFadeIn(mu: Proc, index: Int, arg: Int) {
        unit.sub80BB4C0(primAt(mu, index).mapUnit, arg, null)
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_StartFadeOut
     */
    public fun gmMuStartFadeOut(mu: Proc, index: Int, arg: Int) {
        unit.sub80BB538(primAt(mu, index).mapUnit, arg, null)
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmUnitFadeExists
     */
    public fun gmUnitFadeExists(): Boolean = unit.getGmapUnitFade() != null

    /**
     * Ported from src/worldmap_mapmu.c: MapMU_SetUnitClass
     */
    public fun mapMuSetUnitClass(mu: Proc, index: Int, jid: Int, faction: Int, nodeId: Int) {
        gmMuSetUnit(mu, index, true, jid, faction, nodeId)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: MapMU_SetUnitChar (not verified: no @VerifiesPort test
     * names it)
     */
    public fun mapMuSetUnitChar(mu: Proc, index: Int, pid: Int, faction: Int, nodeId: Int) {
        gmMuSetUnit(mu, index, false, pid, faction, nodeId)
    }

    /**
     * Transcribed from src/worldmap_draw.c: SetGmCharUnit (not verified: see
     * WorldMapEventHostTest; the Prologue's slot already holds the character, so only a seeded
     * run reaches it)
     *
     * `MapMU_SetUnitChar(GM_MU, id, pid, allegiance, nodeId)`; `GM_MU` is the running
     * `ProcScr_GMapMu`.
     */
    public fun setGmCharUnit(id: Int, pid: Int, allegiance: Int, nodeId: Int) {
        mapMuSetUnitChar(checkNotNull(engine.find(script)), id, pid, allegiance, nodeId)
    }

    private companion object {
        const val PROC_MARK_WMSTUFF = 8

        /** `A_BUTTON`. */
        const val A_BUTTON = 0x0001

        /** `FACTION_BLUE` and `FACTION_PURPLE`. */
        const val FACTION_BLUE = 0x00
        const val FACTION_PURPLE = 0xC0

        /** `gGMapMuSpriteLayerLut`. */
        val SPRITE_LAYER = intArrayOf(8, 9, 9, 9, 9, 9, 9)
    }
}
