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

/**
 * `struct GMapMuPrimProc` past `PROC_HEADER`: the mover of one world map unit slot (the lord
 * party, the monsters, a ship ...). It owns the link to the unit's [GmapUnitProc] sprite and walks
 * it along a path between two nodes or two map positions, one step per frame. Seven exist, one
 * per [GmapMuProc.prims] slot. Movement fields only; the decomp has no further fields.
 */
public class GmapMuPrimProc {
    /**
     * `flags_0` (+0x29 bit 0): a move is in progress (set when a move starts, cleared when it
     * ends; read by `sub_80BE12C`, here [WorldMapMu.gmMuIsMoving]).
     */
    public var moving: Boolean = false

    /** `flags_1` (+0x29 bit 1): the move is paused ([WorldMapMu.gmMuPauseMovement]). */
    public var paused: Boolean = false

    /**
     * `flags_2` (+0x29 bit 2): holding the A button makes the unit move faster (the timer gains
     * 2 extra per frame).
     */
    public var speedUpOnA: Boolean = false

    /** `flags_3` (+0x29 bit 3): the camera follows the unit. */
    public var cameraFollows: Boolean = false

    /**
     * `flags_4` (+0x29 bit 4): the walking animation follows the direction of travel, and the
     * end animation is set when the move finishes. Both move starters set it.
     */
    public var animateWalk: Boolean = false

    /** `flags_5` (+0x29 bit 5): play the footstep sound on each step. */
    public var stepSounds: Boolean = false

    /** `unk_2a` (+0x2A, `s8`): the slot index, or -1 when free. */
    public var slot: Int = 0

    /** `unk_2b` (+0x2B, `s8`): the linked `GmapUnit` slot, or -1. */
    public var mapUnit: Int = 0

    /**
     * `unk_2c` (+0x2C, `u8`): the kind of move: 0 none, 1 between two nodes ([fromNode] to
     * [toNode]), 2 between two map positions ([fromX], [fromY] to [toX], [toY]).
     */
    public var moveKind: Int = 0

    /** `unk_2d` (+0x2D, `s8`): the node the move starts at (decomp comment: "node ID?"). */
    public var fromNode: Int = 0

    /** `unk_2e` (+0x2E, `s8`): the node the move ends at (decomp comment: "node ID?"). */
    public var toNode: Int = 0

    /**
     * `unk_2f` (+0x2F, `s8`): the easing power of a position move, the third argument of
     * `_DivArm2` (0 = linear). Unused by node moves.
     */
    public var easePower: Int = 0

    /** `unk_30` (+0x30, `u8`): the footstep counter, which picks the entry of the sound table. */
    public var stepCounter: Int = 0

    /**
     * `unk_31` (+0x31, `s8`): the animation to set when the move starts (decomp comment:
     * "anim ID?"); negative = leave it.
     */
    public var anim1: Int = 0

    /**
     * `unk_32` (+0x32, `s8`): the animation to set when the move ends (decomp comment:
     * "anim ID?"); negative = leave it.
     */
    public var anim2: Int = 0

    /** `unk_34[3].a` (+0x34, stride 4, `s8`): the x deltas of the last three moving frames. */
    public val trailA: IntArray = IntArray(3)

    /** `unk_34[3].b` (+0x35, stride 4, `s8`): the y deltas of the last three moving frames. */
    public val trailB: IntArray = IntArray(3)

    /** `unk_40` (+0x40, `s16`): number of valid entries in [trailA] and [trailB] (0 to 3). */
    public var trailCount: Int = 0

    /** `unk_42` (+0x42, `s16`): frames to wait before the move starts. */
    public var delay: Int = 0

    /** `unk_44` (+0x44, `u16`): the sprite's x after the previous step. */
    public var lastX: Int = 0

    /** `unk_46` (+0x46, `u16`): the sprite's y after the previous step. */
    public var lastY: Int = 0

    /** `unk_48` (+0x48, `s16`): start x of a position move. */
    public var fromX: Int = 0

    /** `unk_4a` (+0x4A, `s16`): start y of a position move. */
    public var fromY: Int = 0

    /** `unk_4c` (+0x4C, `s16`): end x of a position move. */
    public var toX: Int = 0

    /** `unk_4e` (+0x4E, `s16`): end y of a position move. */
    public var toY: Int = 0

    /**
     * `unk_50` (+0x50): the move's length in frames; negative = work it out from the distance
     * when the move starts ([WorldMapMu.travelFrames]).
     */
    public var duration: Int = 0

    /** `unk_54` (+0x54): frames elapsed in the move (the speed-up adds extra). */
    public var timer: Int = 0
}

/**
 * `struct GMapMuProc` past `PROC_HEADER`: the world map unit mover (`GM_MU`), a container of seven
 * [GmapMuPrimProc] movers, one per `gGMData.units` slot.
 */
public class GmapMuProc {
    /** `unk_2c[7]` (+0x2C): one [GmapMuPrimProc] per world map unit slot. */
    public val prims: Array<Proc?> = arrayOfNulls(SLOTS)

    /** Capacity of the mover. */
    public companion object {
        /** Unit slots (`gGMData.units`). */
        public const val SLOTS: Int = 7
    }
}

/**
 * The world map unit mover (`src/worldmap_mapmu.c`), on top of [WorldMapUnit]: it puts the lord
 * party, monsters and ships (the seven `gGMData.units` slots) on the map and walks them from node
 * to node or point to point. The spline path (`sub_80BCE34`, `sub_800A950`) and the footstep
 * sound are [Hooks].
 *
 * Positions given to the sprite are 6 pixels below the node or position the mover is told
 * (`+ 6` in the decomp): the sprite's feet stand on the node.
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
    /** The callees of `worldmap_mapmu.c` that live in other files; each defaults to a no-op. */
    public class Hooks(
        /**
         * `GetUnit(i)` of the battle unit table: the `(character id, class id)` of unit [index]
         * if it is a valid unit, else null. Searched for `index` 1 up to (not including)
         * `FACTION_PURPLE`, i.e. every player, NPC and enemy unit.
         */
        public val unitAt: (index: Int) -> Pair<Int, Int>? = { null },
        /** `gCharacterData[pid - 1].defaultClass`: the class a character starts as. */
        public val defaultClass: (pid: Int) -> Int = { 0 },
        /**
         * `sub_80BD20C(index)` (`src/worldmap_path.c`): the faction value for unit slot [index]
         * from `gUnknown_08206868` (0, 0, 2, 1, 1, 1, 1; 0 for slots 7 and up).
         *
         * NOTE: that table follows `worldmap_unit_alignace` (1 = red, 2 = green), but the value
         * is passed to [WorldMapUnit.mapUnitCSetFaction], whose palette lookup follows
         * `FACTION_ID_*` (1 = green, 2 = red). The decomp does the same; if the colours look
         * swapped against the original, this is where. Not checked against the original.
         */
        public val slotFaction: (index: Int) -> Int = { 0 },
        /**
         * `GetWMCenteredCameraPosition(x, y, &xCam, &yCam)`: the camera position that centres
         * the point ([x], [y]) on screen; returns `(xCam, yCam)`.
         */
        public val centeredCamera: (x: Int, y: Int) -> Pair<Int, Int> = { _, _ -> 0 to 0 },
        /** `gKeyStatusPtr->heldKeys`: the buttons held this frame. */
        public val heldKeys: () -> Int = { 0 },
        /**
         * `sub_80BE45C(proc)`: plays the next footstep sound of the unit, chosen by its class
         * (flying, armoured, ship ...) and [GmapMuPrimProc.stepCounter] (not ported).
         */
        public val stepSound: (GmapMuPrimProc) -> Unit = { },
        /**
         * Spline setup of `GmMuPrim_80BD444`: builds the curved path between
         * [GmapMuPrimProc.fromNode] and [GmapMuPrimProc.toNode] with `sub_80BCE34` into the
         * `Struct02003BE8` of slot `p.slot` (not ported).
         */
        public val splineStart: (GmapMuPrimProc) -> Unit = { },
        /**
         * `sub_800A950(&gUnknown_0201B3A4[slot], t * 0x1000, coord)`: the point on the slot's
         * path after [t] frames; returns `(x, y)` in 1/16 pixel (not ported).
         */
        public val splineAt: (slot: Int, t: Int) -> Pair<Int, Int> = { _, _ -> 0 to 0 },
    )

    /** The [GmapMuPrimProc] state of the mover proc [proc]. */
    private fun prim(proc: Proc): GmapMuPrimProc = proc.state as GmapMuPrimProc

    /** The state of the mover of slot [index] of `GM_MU` [mu]. */
    private fun primAt(mu: Proc, index: Int): GmapMuPrimProc =
        prim((mu.state as GmapMuProc).prims[index]!!)

    /**
     * `ProcScr_GMapMuPrim`: the script of one slot's mover. It idles at label 0 until a move
     * starter jumps to label 1 (node move) or 2 (position move), runs that move's step each frame
     * until it ends, then returns to label 0.
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
            call(ProcFunc { gmMuPrimStartNodeMove(it) }),
            repeat(ProcFunc { gmMuPrimStepNodeMove(it) }),
            goto(0),
            label(2),
            call(ProcFunc { gmMuPrimStartPositionMove(it) }),
            repeat(ProcFunc { gmMuPrimStepPositionMove(it) }),
            goto(0),
            end(),
        ),
    ) { GmapMuPrimProc() }

    /**
     * `ProcScr_GMapMu`: the script of the mover container; it creates the seven movers and then
     * only blocks.
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

    /** Ported from src/worldmap_mapmu.c: GmMuPrim_OnInit: a new mover has no linked sprite. */
    public fun gmMuPrimOnInit(proc: Proc) {
        prim(proc).mapUnit = -1
    }

    /** Ported from src/worldmap_mapmu.c: GmMu_OnInit: creates the seven movers. */
    public fun gmMuOnInit(proc: Proc) {
        val s = proc.state as GmapMuProc
        for (i in 0 until GmapMuProc.SLOTS) {
            s.prims[i] = engine.start(primScript, checkNotNull(proc.parent))
        }
    }

    /** Ported from src/worldmap_mapmu.c: StartGmMu: starts `GM_MU` under [parent]. */
    public fun startGmMu(parent: Proc?): Proc = engine.start(script, checkNotNull(parent))

    /**
     * Transcribed from src/worldmap_mapmu.c: GetCharacterClassId (not verified: no @VerifiesPort
     * test names it)
     *
     * The class a character currently has: that of the unit with character id [pid] in the unit
     * table, or the character's default class if none is there.
     */
    public fun getCharacterClassId(pid: Int): Int {
        for (i in FACTION_BLUE + 1 until FACTION_PURPLE) {
            val u = hooks.unitAt(i) ?: continue
            if (u.first != pid) continue
            return u.second
        }
        return hooks.defaultClass(pid)
    }

    /**
     * Common body of `MapMUPrim_MakeUnitForChar` and `MapMUPrim_MakeUnitForClass`: replaces the
     * mover's sprite with a new standing one of [classId] on node [nodeId].
     */
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
     *
     * Gives the mover a standing sprite of character [pid]'s class, on node [nodeId].
     */
    public fun mapMuPrimMakeUnitForChar(proc: Proc, pid: Int, nodeId: Int) {
        makeUnit(prim(proc), getCharacterClassId(pid), nodeId)
    }

    /**
     * Ported from src/worldmap_mapmu.c: MapMUPrim_MakeUnitForClass
     *
     * Gives the mover a standing sprite of class [jid], on node [nodeId].
     */
    public fun mapMuPrimMakeUnitForClass(proc: Proc, jid: Int, nodeId: Int) {
        makeUnit(prim(proc), jid, nodeId)
    }

    /**
     * Ported from src/worldmap_mapmu.c: MapMUPrim_RemoveLinkedMapUnit
     *
     * Deletes the mover's sprite, if it has one.
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
     *
     * Puts a unit into slot [index] on node [nodeId]: [id] is a class id when [useClass], else a
     * character id; it is recorded in `gGMData.units[index]` (state bit 1 marks a class id).
     * A negative [faction] takes the slot's own faction. The sprite layer comes from
     * `gGMapMuSpriteLayerLut`.
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
        p.moveKind = 0
        p.moving = false
        p.speedUpOnA = false
        p.cameraFollows = false
        gmMuSetSpriteLayer(mu, index, SPRITE_LAYER[index])
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_RemoveUnit
     *
     * Takes the unit out of slot [index]: deletes its sprite and clears its id and its shown bit
     * (state bit 0) in `gGMData.units`. Does nothing for an empty slot.
     */
    public fun gmMuRemoveUnit(mu: Proc, index: Int) {
        val prim = (mu.state as GmapMuProc).prims[index]!!
        val p = prim(prim)
        if (p.mapUnit >= 0) {
            p.slot = -1
            mapMuPrimRemoveLinkedMapUnit(prim)
            // The decomp's two branches (state bit 1 set or clear) both leave that bit as it was.
            gmData.setUnitId(index, 0)
            gmData.setUnitState(index, gmData.unitState(index) and 1.inv())
        }
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmShowMuUnit
     *
     * Shows the sprite of slot [index] and sets state bit 0 of the slot in `gGMData.units`;
     * [index] < 0 = every slot that has a sprite (that form is not run by the capture).
     */
    public fun gmShowMuUnit(mu: Proc, index: Int): Unit = setShown(mu, index, true)

    /**
     * Ported from src/worldmap_mapmu.c: GmHideMuUnit
     *
     * Hides the sprite of slot [index] and clears state bit 0 of the slot in `gGMData.units`;
     * [index] < 0 = every slot that has a sprite (that form is not run by the capture).
     */
    public fun gmHideMuUnit(mu: Proc, index: Int): Unit = setShown(mu, index, false)

    /** Common body of `GmShowMuUnit` and `GmHideMuUnit`. */
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
     *
     * Sets the OBJ layer the sprite of slot [index] is drawn on.
     */
    public fun gmMuSetSpriteLayer(mu: Proc, index: Int, layer: Int) {
        unit.mapUnitCSetLayer(primAt(mu, index).mapUnit, layer)
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_SetPosition
     *
     * Puts the sprite of slot [index] at ([xBase], [yBase]) on the map (drawn 6 pixels lower)
     * and clears its step trail.
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
     *
     * The position of the slot as [gmMuSetPosition] was given it (without the 6 pixel offset).
     */
    public fun gmMuGetPosition(mu: Proc, index: Int): Pair<Int, Int> {
        val (x, y) = unit.mapUnitCGetPosition(primAt(mu, index).mapUnit)
        return x to (y - 6)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_SetNode (not verified: no @VerifiesPort test
     * names it)
     *
     * Puts the sprite of slot [index] on node [nodeId].
     */
    public fun gmMuSetNode(mu: Proc, index: Int, nodeId: Int) {
        gmMuSetPosition(mu, index, nodes.x(nodeId), nodes.y(nodeId))
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BD41C (not verified: no @VerifiesPort test names
     * it)
     *
     * How many frames a move across the offset ([a], [b]) takes: `sub_80BD41C` in the decomp.
     * 4/5 of the straight-line distance in pixels (`sqrt(16 * (a^2 + b^2)) / 5`), i.e. 0.8
     * frames per pixel.
     */
    public fun travelFrames(a: Int, b: Int): Int = BiosMath.sqrt((a * a + b * b) * 16) / 5

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BDD94 (not verified: no @VerifiesPort test names
     * it); [index] < 0 = all.
     *
     * Deletes the sprite of slot [index] (every slot when negative) without clearing the unit
     * from `gGMData.units`; `sub_80BDD94` in the decomp.
     */
    public fun removeLinkedMapUnits(mu: Proc, index: Int) {
        val s = mu.state as GmapMuProc
        if (index >= 0) {
            mapMuPrimRemoveLinkedMapUnit(s.prims[index]!!)
        } else {
            for (i in 0 until GmapMuProc.SLOTS) mapMuPrimRemoveLinkedMapUnit(s.prims[i]!!)
        }
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMuPrim_80BD444 (not verified: no traced caller)
     *
     * Start of a node move (label 1 of the mover script, `GmMuPrim_80BD444`): works out the
     * length when [GmapMuPrimProc.duration] is negative, switches a unit between two ship-travel
     * nodes to the fleet sprite (class 0x50, `CLASS_FLEET`; `shipTravelFlag` bit 0 of both
     * nodes), starts the spline path via [Hooks.splineStart], puts the sprite on the start node
     * and sets the start animation.
     */
    public fun gmMuPrimStartNodeMove(proc: Proc) {
        val p = prim(proc)
        if (p.duration < 0) {
            p.duration = travelFrames(
                nodes.x(p.toNode) - nodes.x(p.fromNode),
                nodes.y(p.toNode) - nodes.y(p.fromNode),
            )
        }
        if (nodes.shipTravelFlag(p.fromNode) and 1 != 0 &&
            nodes.shipTravelFlag(p.toNode) and 1 != 0
        ) {
            unit.mapUnitCSwitchClass(p.mapUnit, CLASS_FLEET)
        }
        hooks.splineStart(p)
        unit.mapUnitCSetPosition(p.mapUnit, nodes.x(p.fromNode), nodes.y(p.fromNode) + 6)
        if (p.anim1 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim1)
        p.timer = 0
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
        p.trailCount = 0
    }

    /**
     * `sub_80BD5B8`: appends the sprite's movement since the last step to the trail of the last
     * three deltas; returns false (and records nothing) if it did not move.
     */
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

    /**
     * `sub_80BD660`: the walking animation for the direction angle [a] (`ArcTan2`, 0x10000 = a
     * full turn): 3 for angles 0xB300 up to 0xCD00, 2 for 0x3300 up to 0x4D00, else 0 or 1 by the
     * half-turn the angle is in. Which animation is which compass direction is not checked.
     */
    private fun facingAnim(a: Int): Int {
        if (((a + 0x4D00) and 0xFFFF) < 0x1A00) return 3
        if (a < 0x3300 || a >= 0x4D00) return ((a - 0x4000) and 0xFFFF) shr 15
        return 2
    }

    /**
     * `sub_80BD6A8`: the walking animation for the direction of the trail, or -1 if the unit has
     * not moved far enough. The deltas are summed oldest first until their length passes 35
     * (`sub_80C0834` scaled by 1 shl 8, i.e. 35/16 pixels); that direction picks the animation.
     */
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

    /**
     * `sub_80BD740`: the work after each step of a move: records the step, plays the footstep
     * sound ([GmapMuPrimProc.stepSounds], twice while A is held to speed up), turns the walking
     * animation to the direction of travel, moves the camera if it follows and remembers the
     * position.
     */
    private fun afterStep(p: GmapMuPrimProc) {
        if (!recordStep(p)) return
        if (p.stepSounds) {
            hooks.stepSound(p)
            if (p.speedUpOnA && hooks.heldKeys() and A_BUTTON != 0) hooks.stepSound(p)
        }
        val anim = trailAnim(p)
        if (anim >= 0 && unit.mapUnitCGetAnimId(p.mapUnit) != anim && p.animateWalk) {
            unit.mapUnitCSetAnimId(p.mapUnit, anim)
            unit.mapUnitCSetGfxNeedsUpdate(p.mapUnit)
        }
        followCamera(p)
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
    }

    /**
     * The start of both step routines: returns false while the move is paused or still waiting
     * out [GmapMuPrimProc.delay] (counting it down), else advances [GmapMuPrimProc.timer] by 1
     * (3 with A held and [GmapMuPrimProc.speedUpOnA]) and returns true.
     */
    private fun advanceTimer(p: GmapMuPrimProc): Boolean {
        if (p.paused) return false
        if (p.delay > 0) {
            p.delay--
            return false
        }
        if (p.speedUpOnA && hooks.heldKeys() and A_BUTTON != 0) p.timer += 2
        p.timer++
        return true
    }

    /** Ends a finished move: follows with the camera if due, ends the repeat, clears `moving`. */
    private fun endMoveStep(proc: Proc, p: GmapMuPrimProc) {
        followCamera(p)
        engine.breakProc(proc)
        p.moving = false
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMuPrim_80BD830 (not verified: no traced caller)
     *
     * One frame of a node move: places the sprite on the spline path at the current timer; at
     * the end snaps it to the destination node, switches a fleet sprite back to the unit's own
     * class (class 0 = its own), sets the end animation and records the destination node as the
     * unit's location in `gGMData.units`.
     */
    public fun gmMuPrimStepNodeMove(proc: Proc) {
        val p = prim(proc)
        if (!advanceTimer(p)) return
        if (p.timer < p.duration) {
            val (cx, cy) = hooks.splineAt(p.slot, p.timer)
            unit.mapUnitCSetPosition(p.mapUnit, cx shr 4, (cy shr 4) + 6)
            afterStep(p)
        } else {
            if (nodes.shipTravelFlag(p.fromNode) and 1 != 0 &&
                nodes.shipTravelFlag(p.toNode) and 1 != 0
            ) {
                unit.mapUnitCSwitchClass(p.mapUnit, 0)
            }
            unit.mapUnitCSetPosition(p.mapUnit, nodes.x(p.toNode), nodes.y(p.toNode) + 6)
            if (p.animateWalk && p.anim2 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim2)
            gmData.setUnitLocation(p.slot, p.toNode)
            endMoveStep(proc, p)
        }
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMuPrim_80BD9D8
     *
     * Start of a position move (label 2 of the mover script): works out the length if needed,
     * remembers the sprite's position and sets the start animation.
     *
     * NOTE: when the length is not given, the decomp indexes the node table with the coordinates
     * `unk_4c`/`unk_48` (and `unk_4e`/`unk_4a`) as if they were node ids. This port copies that.
     * It looks like a decomp slip; the `-1` length is not used by any caller in the capture.
     */
    public fun gmMuPrimStartPositionMove(proc: Proc) {
        val p = prim(proc)
        if (p.duration < 0) {
            p.duration = travelFrames(
                nodes.x(p.toX) - nodes.x(p.fromX),
                nodes.y(p.toY) - nodes.y(p.fromY),
            )
        }
        p.timer = 0
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        p.lastX = x and 0xFFFF
        p.lastY = y and 0xFFFF
        if (p.anim1 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim1)
        p.trailCount = 0
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMuPrim_80BDA78
     *
     * One frame of a position move: places the sprite between the start and end positions by the
     * eased fraction of the timer ([GmapMuPrimProc.easePower]); at the end snaps it to the end
     * position and sets the end animation.
     */
    public fun gmMuPrimStepPositionMove(proc: Proc) {
        val p = prim(proc)
        if (!advanceTimer(p)) return
        if (p.timer < p.duration) {
            val coeff = Spline.divArm2(p.timer, p.duration, p.easePower)
            val x = (p.fromX + Spline.divArm(0x1000, (p.toX - p.fromX) * coeff))
                .toShort().toInt()
            val y = (p.fromY + Spline.divArm(0x1000, (p.toY - p.fromY) * coeff))
                .toShort().toInt()
            unit.mapUnitCSetPosition(p.mapUnit, x, y + 6)
            afterStep(p)
        } else {
            unit.mapUnitCSetPosition(p.mapUnit, p.toX, p.toY + 6)
            if (p.animateWalk && p.anim2 >= 0) unit.mapUnitCSetAnimId(p.mapUnit, p.anim2)
            endMoveStep(proc, p)
        }
    }

    /**
     * `struct UnknownSub80BDEB4` (field names as in `include/worldmap.h`): the arguments of
     * [startNodeMove], filled in by the world map auto-move code. Meanings from how
     * `sub_80BDEB4` uses them.
     */
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

    /**
     * `struct UnknownSub80BDFA4` (field names as in `include/worldmap.h`): the arguments of
     * [startPositionMove]. Meanings from how `sub_80BDFA4` uses them.
     */
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
     *
     * Starts a move of a unit from one node to another (`sub_80BDEB4` in the decomp) and sends
     * its mover to label 1 of its script. A negative length is the distance-based length times
     * the absolute value of the given one.
     */
    public fun startNodeMove(mu: Proc, input: MoveInput) {
        val proc = (mu.state as GmapMuProc).prims[input.unk00]!!
        val p = prim(proc)
        p.fromNode = input.unk06.toByte().toInt()
        p.toNode = input.unk08.toByte().toInt()
        var a = input.unk0c
        if (a < 0) {
            val tmp = travelFrames(
                abs(nodes.x(p.toNode) - nodes.x(p.fromNode)),
                abs(nodes.y(p.toNode) - nodes.y(p.fromNode)),
            )
            a = tmp * abs(input.unk0c)
        }
        p.duration = a
        p.speedUpOnA = input.unk01 != 0
        p.delay = input.unk0a.toShort().toInt()
        p.moving = true
        p.paused = false
        p.moveKind = 1
        p.animateWalk = true
        p.stepSounds = input.unk02 != 0
        p.stepCounter = 0
        p.anim1 = input.unk03.toByte().toInt()
        p.anim2 = input.unk04.toByte().toInt()
        engine.goto(proc, 1)
    }

    /**
     * Ported from src/worldmap_mapmu.c: sub_80BDFA4
     *
     * Starts a move of a unit from one map position to another (`sub_80BDFA4` in the decomp) and
     * sends its mover to label 2 of its script.
     */
    public fun startPositionMove(mu: Proc, input: MoveInput2) {
        val proc = (mu.state as GmapMuProc).prims[input.unk00]!!
        val p = prim(proc)
        p.fromX = input.unk06.toShort().toInt()
        p.fromY = input.unk08.toShort().toInt()
        p.toX = input.unk0a.toShort().toInt()
        p.toY = input.unk0c.toShort().toInt()
        p.duration = input.unk10
        p.speedUpOnA = input.unk02 != 0
        p.easePower = input.unk01.toByte().toInt()
        p.delay = input.unk0e.toShort().toInt()
        p.moving = true
        p.paused = false
        p.moveKind = 2
        p.animateWalk = true
        p.stepSounds = input.unk03 != 0
        p.stepCounter = 0
        p.anim1 = input.unk04.toByte().toInt()
        p.anim2 = input.unk05.toByte().toInt()
        engine.goto(proc, 2)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_PauseMovement (not verified: no @VerifiesPort
     * test names it)
     *
     * Freezes the move of slot [index] until [gmMuResumeMovement].
     */
    public fun gmMuPauseMovement(mu: Proc, index: Int) {
        primAt(mu, index).paused = true
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_ResumeMovement (not verified: no @VerifiesPort
     * test names it)
     *
     * Lets the paused move of slot [index] continue.
     */
    public fun gmMuResumeMovement(mu: Proc, index: Int) {
        primAt(mu, index).paused = false
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE080 (not verified: no @VerifiesPort test names
     * it)
     *
     * Turns semi-transparent drawing of the sprite of slot [index] on or off (`sub_80BE080` in the
     * decomp).
     */
    public fun gmMuSetBlendEnabled(mu: Proc, index: Int, blendEnabled: Boolean) {
        unit.mapUnitCSetBlendEnabled(primAt(mu, index).mapUnit, blendEnabled)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE0A4 (not verified: no @VerifiesPort test names
     * it); returns (x, y).
     *
     * The sprite's position as the sprite holds it, including the 6 pixel offset (unlike
     * [gmMuGetPosition]); `sub_80BE0A4` in the decomp.
     */
    public fun gmMuGetRawPosition(mu: Proc, index: Int): Pair<Int, Int> =
        unit.mapUnitCGetPosition(primAt(mu, index).mapUnit)

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE0C8 (not verified: no @VerifiesPort test names
     * it)
     *
     * The OBJ layer of the sprite of slot [index] (`sub_80BE0C8` in the decomp).
     */
    public fun gmMuGetSpriteLayer(mu: Proc, index: Int): Int =
        unit.mapUnitCGetLayer(primAt(mu, index).mapUnit)

    /**
     * Transcribed from src/worldmap_mapmu.c: GmMu_80BE108 (not verified: no @VerifiesPort test
     * names it)
     *
     * Makes the camera follow ([flag]) or not follow the unit of slot [index] while it moves
     * (`GmMu_80BE108` in the decomp).
     */
    public fun gmMuSetCameraFollows(mu: Proc, index: Int, flag: Boolean) {
        primAt(mu, index).cameraFollows = flag
    }

    /**
     * Ported from src/worldmap_mapmu.c: sub_80BE12C
     *
     * Whether the unit of slot [index] is moving (`sub_80BE12C` in the decomp); [index] < 0 asks
     * about any unit. A slot without a sprite is never moving.
     */
    public fun gmMuIsMoving(mu: Proc, index: Int): Boolean {
        if (index < 0) {
            for (i in 0 until GmapMuProc.SLOTS) {
                val p = primAt(mu, i)
                if (p.mapUnit >= 0 && p.moving) return true
            }
        } else {
            val p = primAt(mu, index)
            if (p.mapUnit >= 0) return p.moving
        }
        return false
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE194 (not verified: no @VerifiesPort test names
     * it)
     *
     * Ends the running move of one mover at once (`sub_80BE194` in the decomp): the sprite is
     * put on its destination, set to the standing animation (4), the camera follows if it should
     * and the mover returns to its idle label. Does nothing if no move is running.
     */
    public fun finishMove(proc: Proc) {
        val p = prim(proc)
        if (!p.moving) return
        when (p.moveKind) {
            1 -> {
                if (nodes.shipTravelFlag(p.fromNode) and 1 != 0 &&
                    nodes.shipTravelFlag(p.toNode) and 1 != 0
                ) {
                    unit.mapUnitCSwitchClass(p.mapUnit, 0)
                }
                unit.mapUnitCSetPosition(p.mapUnit, nodes.x(p.toNode), nodes.y(p.toNode) + 6)
                unit.mapUnitCSetAnimId(p.mapUnit, 4)
                gmData.setUnitLocation(p.slot, p.toNode)
                followCamera(p)
            }
            2 -> {
                unit.mapUnitCSetPosition(p.mapUnit, p.toX, p.toY + 6)
                unit.mapUnitCSetAnimId(p.mapUnit, 4)
                followCamera(p)
            }
        }
        p.moving = false
        engine.goto(proc, 0)
    }

    /**
     * The camera step of a move: if the camera follows this unit, centres it on the sprite's
     * position 14 pixels higher (`y - 14` in the decomp).
     */
    private fun followCamera(p: GmapMuPrimProc) {
        if (!p.cameraFollows) return
        val (x, y) = unit.mapUnitCGetPosition(p.mapUnit)
        val (cx, cy) = hooks.centeredCamera(x, y - 14)
        gmData.xCamera = cx
        gmData.yCamera = cy
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE330 (not verified: no @VerifiesPort test names
     * it); [index] < 0 = all.
     *
     * Ends the running move of slot [index] (every slot when negative) with [finishMove]
     * (`sub_80BE330` in the decomp).
     */
    public fun finishMoves(mu: Proc, index: Int) {
        val s = mu.state as GmapMuProc
        if (index < 0) {
            for (i in 0 until GmapMuProc.SLOTS) finishMove(s.prims[i]!!)
        } else {
            finishMove(s.prims[index]!!)
        }
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: sub_80BE3E8 (not verified: no @VerifiesPort test names
     * it)
     *
     * Sets whether holding A speeds up the move of slot [index] (`sub_80BE3E8` in the decomp).
     */
    public fun gmMuSetSpeedUpOnA(mu: Proc, index: Int, flag: Boolean) {
        primAt(mu, index).speedUpOnA = flag
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_StartFadeIn
     *
     * Fades the sprite of slot [index] in over [arg] frames.
     */
    public fun gmMuStartFadeIn(mu: Proc, index: Int, arg: Int) {
        unit.fadeInMapUnit(primAt(mu, index).mapUnit, arg, null)
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmMu_StartFadeOut
     *
     * Fades the sprite of slot [index] out over [arg] frames.
     */
    public fun gmMuStartFadeOut(mu: Proc, index: Int, arg: Int) {
        unit.fadeOutMapUnit(primAt(mu, index).mapUnit, arg, null)
    }

    /**
     * Ported from src/worldmap_mapmu.c: GmUnitFadeExists
     *
     * Whether a unit fade is running.
     */
    public fun gmUnitFadeExists(): Boolean = unit.getGmapUnitFade() != null

    /**
     * Ported from src/worldmap_mapmu.c: MapMU_SetUnitClass
     *
     * [gmMuSetUnit] with a class id [jid].
     */
    public fun mapMuSetUnitClass(mu: Proc, index: Int, jid: Int, faction: Int, nodeId: Int) {
        gmMuSetUnit(mu, index, true, jid, faction, nodeId)
    }

    /**
     * Transcribed from src/worldmap_mapmu.c: MapMU_SetUnitChar (not verified: no @VerifiesPort test
     * names it)
     *
     * [gmMuSetUnit] with a character id [pid].
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
        /** `PROC_MARK_WMSTUFF` (`include/proc.h`): the proc mark of world map procs. */
        const val PROC_MARK_WMSTUFF = 8

        /** `A_BUTTON`: the key bit of the A button. */
        const val A_BUTTON = 0x0001

        /** `FACTION_BLUE` (`include/bmunit.h`): first unit id after it starts the search. */
        const val FACTION_BLUE = 0x00

        /** `FACTION_PURPLE` (`include/bmunit.h`): the unit id the class search stops before. */
        const val FACTION_PURPLE = 0xC0

        /** `CLASS_FLEET` (`include/constants/classes.h`): the ship sprite used at sea. */
        const val CLASS_FLEET = 0x50

        /** `gGMapMuSpriteLayerLut`: the OBJ layer of each slot's sprite (slot 0: 8, others: 9). */
        val SPRITE_LAYER = intArrayOf(8, 9, 9, 9, 9, 9, 9)
    }
}
