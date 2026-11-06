package fe8.game.bm

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts

/** The unit fields `StartMu` reads (`struct Unit`), as the caller's snapshot. */
public class MuUnit(
    /** GBA address of the unit (`proc->unit`). */
    public val address: Int,
    /** `unit->xPos`. */
    public val xPos: Int,
    /** `unit->yPos`. */
    public val yPos: Int,
    /** `UNIT_CLASS_ID(unit)`. */
    public val classId: Int,
    /** `unit->state`. */
    public val state: Int,
    /** `GetTrap(unit->ballistaIndex)->extra`, only read when [state] has `US_IN_BALLISTA`. */
    public val ballistaItem: Int = 0,
    /** `GetUnitSpritePalette(unit)`. */
    public val spritePalette: Int = 0,
)

/**
 * Stand-ins for the parts of mu.c outside M6-25 (display M6-27, movement M6-26) and ROM lookups.
 */
public interface MuHooks {
    /**
     * `StartMuInternal` graphics: `AP_Create(GetMuAnimForJid(jid), 10)`,
     * `AP_SwitchAnimation(ap, 4)`, `Decompress(GetMuImg(proc), GetMuImgBufById(slot))` and the
     * `ap->pGraphics`/`tileBase` setup. Returns the animation handle (`proc->sprite_anim`).
     */
    public fun createMuAnim(proc: MuProc, config: MuConfig): Int = 0

    /** `AP_SwitchAnimation(proc->sprite_anim, facing)`. */
    public fun switchMuAnim(proc: MuProc, facing: Int) {}

    /** `SetStandingMuFacing(slot, pGfxVRAM)`. */
    public fun setStandingMuFacing(slot: Int, gfxVram: Int) {}

    /** `GetClassData(jid)->attributes`. */
    public fun classAttributes(jid: Int): Int = 0

    /** `PlayMuStepSe(proc)` (M6-26). */
    public fun playMuStepSe(proc: MuProc) {}
}

/** One `struct MuConfig` slot (`sMuConfig`). */
public class MuConfig {
    /** `slot`: 0 = free, else index + 1. */
    public var slot: Int = 0

    /** `pal`. */
    public var pal: Int = 0

    /** `chr`. */
    public var chr: Int = 0

    /** `pc`: move script cursor. */
    public var pc: Int = 0

    /** `movescr`. */
    public val movescr: IntArray = IntArray(0x40)

    /** `mu`. */
    public var mu: MuProc? = null
}

/** The fields of `struct MuProc` past `PROC_HEADER` (+0x2C..). */
public class MuProc {
    /** `unit` (address; 0 = NULL). */
    public var unitAddress: Int = 0

    /** `sprite_anim` (hook handle). */
    public var spriteAnim: Int = 0

    /** `config`. */
    public var config: MuConfig? = null

    /** `pGfxVRAM`. */
    public var gfxVram: Int = 0

    /** `slot`. */
    public var slot: Int = 0

    /** `cam_b`. */
    public var camB: Boolean = false

    /** `state` (`MU_STATE_*`). */
    public var state: Int = 0

    /** `hidden_b`. */
    public var hiddenB: Int = 0

    /** `jid`. */
    public var jid: Int = 0

    /** `facing`. */
    public var facing: Int = 0

    /** `step_sound_clock`. */
    public var stepSoundClock: Int = 0

    /** `fast_walk_b`. */
    public var fastWalkB: Boolean = false

    /** `layer`. */
    public var layer: Int = 0

    /** `move_clock_q4`. */
    public var moveClockQ4: Int = 0

    /** `moveConfig`. */
    public var moveConfig: Int = 0

    /** `x_q4`. */
    public var xQ4: Int = 0

    /** `y_q4`. */
    public var yQ4: Int = 0

    /** `x_offset_q4`. */
    public var xOffsetQ4: Int = 0

    /** `y_offset_q4`. */
    public var yOffsetQ4: Int = 0
}

/**
 * mu.c start/query half: the MU slots and the `ProcScr_Mu` procs. Movement and display are
 * M6-26/27.
 */
public class Mu(private val procs: ProcEngine, private val hooks: MuHooks = object : MuHooks {}) {
    /** `sMuConfig`. */
    public val configs: List<MuConfig> = List(MU_MAX_COUNT) { MuConfig() }

    /** M6-26 hook: `Mu_OnLoop` (per-frame movement is not ported here). */
    public var onLoop: ProcFunc = ProcFunc {}

    /** M6-26 hook: `MU_OnEnd`. */
    public var onEnd: ProcFunc = ProcFunc {}

    /** `ProcScr_Mu`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_Mu",
        listOf(
            ProcScripts.name("MOVEUNIT"),
            ProcScripts.mark(PROC_MARK_MU),
            ProcScripts.setEndCb { onEnd.invoke(it) },
            ProcScripts.repeat { onLoop.invoke(it) },
            ProcScripts.end(),
        ),
        ::MuProc,
    )

    /**
     * Frees every MU slot.
     *
     * Transcribed from src/mu.c: InitMus (not verified: the trace has no free-slot state to
     * compare)
     */
    public fun initMus() {
        for (c in configs) c.slot = 0
    }

    /**
     * Starts a MU for [unit] with an explicit class and palette.
     *
     * Transcribed from src/mu.c: StartMuExt (not verified: the trace MU is started by StartMu)
     */
    public fun startMuExt(unit: MuUnit, jid: Int, pal: Int): Proc? {
        val proc = startMuInternal(unit.xPos, unit.yPos, jid, -1, pal) ?: return null
        val mu = proc.state as MuProc
        mu.unitAddress = unit.address
        mu.camB = true
        return proc
    }

    /**
     * Starts a MU for [unit].
     *
     * Ported from src/mu.c: StartMu
     */
    public fun startMu(unit: MuUnit): Proc? {
        var jid = unit.classId
        if (unit.state and US_IN_BALLISTA != 0) {
            when (unit.ballistaItem) {
                ITEM_BALLISTA_REGULAR -> jid = CLASS_BLST_REGULAR_USED
                ITEM_BALLISTA_LONG -> jid = CLASS_BLST_LONG_USED
                ITEM_BALLISTA_KILLER -> jid = CLASS_BLST_KILLER_USED
            }
        }
        val proc = startMuInternal(unit.xPos, unit.yPos, jid, -1, unit.spritePalette) ?: return null
        val mu = proc.state as MuProc
        mu.unitAddress = unit.address
        mu.camB = true
        return proc
    }

    /**
     * Allocates a MU slot and starts the `ProcScr_Mu` proc; graphics setup goes through
     * [MuHooks.createMuAnim]. [objTileId] -1 selects the default config (`GetDefaultMuConfig`,
     * tile 0x380), else `GetNewMuConfig`.
     *
     * Ported from src/mu.c: StartMuInternal
     */
    public fun startMuInternal(x: Int, y: Int, jid: Int, objTileId: Int, palId: Int): Proc? {
        val default = objTileId == -1
        val tile = if (default) OBCHR_MU_380 else objTileId
        val index = freeSlot()
        if (index < 0) return null
        val config = configs[index]
        config.slot = index + 1
        config.chr = (if (default) CHR_OFF_DEFAULT[index] else CHR_OFF[index]) + tile
        val delay = if (procs.find(script) != null) 0xFE else 0
        val proc = procs.start(script, 5)
        val mu = proc.state as MuProc
        mu.unitAddress = 0
        mu.state = MU_STATE_INACTIVE
        mu.xQ4 = ((x * 16) shl MU_SUBPIXEL_PRECISION).toShort().toInt()
        mu.yQ4 = ((y * 16) shl MU_SUBPIXEL_PRECISION).toShort().toInt()
        mu.xOffsetQ4 = 0
        mu.yOffsetQ4 = 0
        mu.facing = MU_FACING_UNK11
        mu.moveClockQ4 = 0
        mu.stepSoundClock = delay
        mu.jid = jid and 0xFF
        mu.hiddenB = 0
        mu.gfxVram = OBJ_VRAM0 + 0x20 * tile
        mu.slot = index
        mu.layer = 2 shl 10
        mu.moveConfig = 0
        mu.fastWalkB = false
        config.pal = palId and 0xFF
        mu.config = config
        config.mu = mu
        mu.spriteAnim = hooks.createMuAnim(mu, config)
        return proc
    }

    private fun freeSlot(): Int {
        for (i in 0 until MU_MAX_COUNT) if (configs[i].slot == 0) return i
        return -1
    }

    /**
     * Sets [mu]'s facing and switches its animation.
     *
     * Transcribed from src/mu.c: SetMuFacing (not verified: facing at start is always 11)
     */
    public fun setMuFacing(mu: MuProc, facing: Int) {
        mu.facing = facing
        if (facing == MU_FACING_STANDING) {
            hooks.setStandingMuFacing(mu.slot, mu.gfxVram)
        } else {
            hooks.switchMuAnim(mu, mu.facing)
        }
    }

    /**
     * Faces [mu] 1 if its class is mounted-aid, else 2.
     *
     * Transcribed from src/mu.c: SetMuDefaultFacing (not verified: not exercised at f9674)
     */
    public fun setMuDefaultFacing(mu: MuProc) {
        val mounted = hooks.classAttributes(mu.jid) and CA_MOUNTEDAID != 0
        setMuFacing(mu, if (mounted) 1 else 2)
    }

    /**
     * Copies [commands] (0x40 bytes) into the MU's config script, rewinds it and enters movement.
     *
     * Transcribed from src/mu.c: SetMuMoveScript (not verified: movement starts after f9674)
     */
    public fun setMuMoveScript(mu: MuProc, commands: IntArray) {
        val config = mu.config!!
        for (i in 0 until 0x40) config.movescr[i] = commands[i]
        config.pc = 0
        mu.state = MU_STATE_MOVEMENT
        hooks.playMuStepSe(mu)
    }

    /**
     * Starts a MU at tile ([x], [y]) running [commands].
     *
     * Transcribed from src/mu.c: StartMuScripted (not verified: not exercised at f9674)
     */
    public fun startMuScripted(x: Int, y: Int, jid: Int, pal: Int, commands: IntArray): Proc? {
        val proc = startMuInternal(x, y, jid, -1, pal) ?: return null
        setMuMoveScript(proc.state as MuProc, commands)
        return proc
    }

    /**
     * True if a `ProcScr_Mu` proc exists.
     *
     * Transcribed from src/mu.c: MuExists (not verified: trivial Proc_Find)
     */
    public fun muExists(): Boolean = procs.find(script) != null

    /**
     * True if any allocated MU is past `MU_STATE_INACTIVE`.
     *
     * Transcribed from src/mu.c: MuExistsActive (not verified: not exercised by the trace)
     */
    public fun muExistsActive(): Boolean {
        for (c in configs) {
            if (c.slot == 0) continue
            if (c.mu!!.state != MU_STATE_INACTIVE) return true
        }
        return false
    }

    /** Constants of mu.h / classes / items. */
    public companion object {
        /** `MU_MAX_COUNT`. */
        public const val MU_MAX_COUNT: Int = 4

        /** `OBCHR_MU_380`. */
        public const val OBCHR_MU_380: Int = 0x380

        /** `OBJ_VRAM0`. */
        public const val OBJ_VRAM0: Int = 0x06010000

        /** `MU_SUBPIXEL_PRECISION`. */
        public const val MU_SUBPIXEL_PRECISION: Int = 4

        /** `MU_STATE_INACTIVE`. */
        public const val MU_STATE_INACTIVE: Int = 1

        /** `MU_STATE_MOVEMENT`. */
        public const val MU_STATE_MOVEMENT: Int = 2

        /** `MU_FACING_UNK11`. */
        public const val MU_FACING_UNK11: Int = 11

        /** `MU_FACING_STANDING`. */
        public const val MU_FACING_STANDING: Int = 15

        /** `PROC_MARK_MU`. */
        public const val PROC_MARK_MU: Int = 4

        private const val US_IN_BALLISTA = 1 shl 11
        private const val CA_MOUNTEDAID = 1
        private const val ITEM_BALLISTA_REGULAR = 0x35
        private const val ITEM_BALLISTA_LONG = 0x36
        private const val ITEM_BALLISTA_KILLER = 0x37
        private const val CLASS_BLST_REGULAR_USED = 0x67
        private const val CLASS_BLST_LONG_USED = 0x68
        private const val CLASS_BLST_KILLER_USED = 0x69
        private val CHR_OFF_DEFAULT = intArrayOf(0x00, 0x10, 0x08, 0x18)
        private val CHR_OFF = intArrayOf(0x00, 0x08, 0x04, 0x10)
    }
}
