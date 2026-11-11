package fe8.game.bm

import fe8.game.RomView
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts

/**
 * The unit fields `StartMu` reads (`struct Unit`), as the caller's snapshot. The fields are
 * mutable so a caller can refill one instance per start instead of allocating (the hover start
 * runs from the idle loop); [Mu.startMu] copies what it needs and keeps no reference.
 */
public class MuUnit(
    /** GBA address of the unit (`proc->unit`). */
    public var address: Int,
    /** `unit->xPos`. */
    public var xPos: Int,
    /** `unit->yPos`. */
    public var yPos: Int,
    /** `UNIT_CLASS_ID(unit)`. */
    public var classId: Int,
    /** `unit->state`. */
    public var state: Int,
    /** `GetTrap(unit->ballistaIndex)->extra`, only read when [state] has `US_IN_BALLISTA`. */
    public var ballistaItem: Int = 0,
    /** `GetUnitSpritePalette(unit)`. */
    public var spritePalette: Int = 0,
)

/**
 * Hooks for what mu.c calls outside itself: the sprite animation player (ap.c), the standing
 * map sprite (bmudisp.c), unit and fog lookups, sound and ROM lookups. `Fe8Boot` (Fe8Boot.kt:707)
 * overrides the sprite animation player calls and the class lookups; the rest are stand-ins
 * until M6-B (the fog bump: M6-H).
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

    /** `GetClassData(jid)->slowWalking`. */
    public fun classSlowWalking(jid: Int): Int = 0

    /** `IsGamePlayedThrough()` (reads the save). */
    public fun isGamePlayedThrough(): Boolean = false

    /**
     * `StartPlayMuStepSe(scr[2 + pc], scr[1], position.x)` for the sound script [script]
     * (`Mu.STEP_SE_*`) at step [pc]; the song table and `GetMuDisplayPosition` belong to the sound
     * and display ports.
     */
    public fun startPlayMuStepSe(proc: MuProc, script: Int, pc: Int) {}

    /** `StartMuFogBump(x, y)`: [x]/[y] are the screen-relative pixel position. */
    public fun startMuFogBump(x: Int, y: Int) {}

    /** `MU_IsFogBumpFxActive()`. */
    public fun isFogBumpFxActive(): Boolean = false

    /**
     * `AP_Update(anim, x, y)` ([x] is the OAM1 x, [y] the OAM0 y with the blend bit). The sprite
     * animation player (ap.c) is a separate system, so it stays a hook.
     */
    public fun apUpdate(anim: Int, x: Int, y: Int) {}

    /** `ResetSpriteAnimClock(anim)`: `frameTimer = 0; frameInterval = 0x100`. */
    public fun resetSpriteAnimClock(anim: Int) {}

    /** `anim->objLayer` of an `APHandle`. */
    public fun spriteAnimObjLayer(anim: Int): Int = 0

    /** `sub_8026FF4(slot, gfxVram)` (bmudisp.c): copies the standing frame into the MU tiles. */
    public fun smsFrameToMuGfx(slot: Int, gfxVram: Int) {}

    /**
     * `sub_8027DB4(layer, x, y, oam2, jid, slot)` (bmudisp.c): puts the standing map sprite of
     * class [jid].
     */
    public fun putSmsObject(layer: Int, x: Int, y: Int, oam2: Int, jid: Int, slot: Int) {}

    /** `UNIT_FACTION(unit)` (`unit->index & 0xC0`) of the unit at [address]. */
    public fun unitFaction(address: Int): Int = 0

    /** `gBmMapFog[y][x]`. */
    public fun fogAt(x: Int, y: Int): Int = 0

    /** `AP_Delete(proc->sprite_anim)`. */
    public fun deleteMuAnim(proc: MuProc) {}
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
    /** The proc this state belongs to (`EndMu(proc)` ends it). */
    public var owner: Proc? = null

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

    /** True from [Mu]'s proc start until [Mu.onEnd]; marks the pooled instance as taken. */
    internal var inUse: Boolean = false
}

/**
 * mu.c: the MU slots, the `ProcScr_Mu` procs, their movement and display position. The
 * sprite animation itself is [MuHooks].
 */
public class Mu(
    private val procs: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val keys: KeyStatus,
    private val camMoveScript: ProcScript,
    private val hooks: MuHooks = object : MuHooks {},
    /** The ROM (`gMuInfoTable`); only [getMuImg] and [getMuAnimForJid] read it. */
    private val rom: RomView? = null,
) {
    /** `out->x` of the last [getMuDisplayPosition] (the `struct Vec2` is reused). */
    public var displayX: Int = 0
        private set

    /** `out->y` of the last [getMuDisplayPosition]. */
    public var displayY: Int = 0
        private set

    /** `sMuConfig`. */
    public val configs: List<MuConfig> = List(MU_MAX_COUNT) { MuConfig() }

    /** `ProcScr_Mu`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_Mu",
        listOf(
            ProcScripts.name("MOVEUNIT"),
            ProcScripts.mark(PROC_MARK_MU),
            ProcScripts.setEndCb(ProcFunc { onEnd(it.state as MuProc) }),
            ProcScripts.repeat(ProcFunc { onLoop(it.state as MuProc) }),
            ProcScripts.end(),
        ),
        ::newMuProc,
    )

    /** One `MuProc` per slot, reused so starting a MU in the idle loop allocates nothing. */
    private val muProcPool: List<MuProc> = List(MU_MAX_COUNT) { MuProc() }

    /** The next free pooled `MuProc` (a fresh one if all are taken), reset for a new MU. */
    private fun newMuProc(): MuProc {
        var mu: MuProc? = null
        for (i in 0 until MU_MAX_COUNT) {
            if (!muProcPool[i].inUse) {
                mu = muProcPool[i]
                break
            }
        }
        if (mu == null) mu = MuProc()
        mu.inUse = true
        mu.camB = false
        return mu
    }

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
        val config = (if (default) getDefaultMuConfig(tile) else getNewMuConfig(tile))
            ?: return null
        val index = config.slot - 1
        val delay = if (procs.find(script) != null) 0xFE else 0
        val proc = procs.start(script, 5)
        val mu = proc.state as MuProc
        mu.owner = proc
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

    /**
     * Claims the first free slot with the default chr offset (`sMuChrOffLut_Default`); null when
     * all are taken. The slot index is `config.slot - 1`.
     *
     * Ported from src/mu.c: GetDefaultMuConfig
     */
    public fun getDefaultMuConfig(objTileId: Int): MuConfig? {
        for (i in 0 until MU_MAX_COUNT) {
            val c = configs[i]
            if (c.slot != 0) continue
            c.slot = i + 1
            c.chr = CHR_OFF_DEFAULT[i] + objTileId
            return c
        }
        return null
    }

    /**
     * Claims the first free slot with the chr offset `sMuChrOffLut`; null when all are taken.
     *
     * Transcribed from src/mu.c: GetNewMuConfig (not verified: the trace only uses the default
     * config slot)
     */
    public fun getNewMuConfig(objTileId: Int): MuConfig? {
        for (i in 0 until MU_MAX_COUNT) {
            val c = configs[i]
            if (c.slot != 0) continue
            c.slot = i + 1
            c.chr = CHR_OFF[i] + objTileId
            return c
        }
        return null
    }

    /**
     * Resets the sprite animation clock of every used MU slot.
     *
     * Transcribed from src/mu.c: ResetMuAnims (not verified: nothing in the trace calls it)
     */
    public fun resetMuAnims() {
        for (c in configs) {
            val mu = c.mu
            if (c.slot != 0 && mu != null) hooks.resetSpriteAnimClock(mu.spriteAnim)
        }
    }

    /**
     * Computes the screen position of [proc] into [displayX]/[displayY]; false when it is off
     * screen.
     *
     * Ported from src/mu.c: GetMuDisplayPosition
     */
    public fun getMuDisplayPosition(proc: MuProc): Boolean {
        val sum = proc.xQ4 + proc.xOffsetQ4
        val sumY = proc.yQ4 + proc.yOffsetQ4
        if (proc.state == MU_STATE_DISPLAY_UI) {
            displayX = (sum shr MU_SUBPIXEL_PRECISION).toShort().toInt()
            displayY = (sumY shr MU_SUBPIXEL_PRECISION).toShort().toInt()
            return true
        }
        val x = ((sum shr MU_SUBPIXEL_PRECISION) - bm.cameraX + 8).toShort().toInt()
        val y = ((sumY shr MU_SUBPIXEL_PRECISION) - bm.cameraY + 8).toShort().toInt()
        displayX = x
        displayY = (y + 8).toShort().toInt()
        return !(x < -0x10 || x > 0x100 || y < -0x10 || y > 0xB0)
    }

    /**
     * Puts the standing map sprite of [proc].
     *
     * Transcribed from src/mu.c: PutMuSMS (not verified: the trace MU never stands, and
     * sub_8026FF4/sub_8027DB4 are hooks)
     */
    public fun putMuSms(proc: MuProc) {
        if (proc.hiddenB != 0) return
        if (!getMuDisplayPosition(proc)) return
        val x = displayX and 0x1FF
        var y = displayY and 0xFF
        if (proc.state == MU_STATE_DEATHFADE) y = y or OAM0_BLEND
        hooks.smsFrameToMuGfx(proc.slot, proc.gfxVram)
        val oam2 = ((((proc.gfxVram - OBJ_VRAM0) and 0x1FFFF) shr 5) or
            ((proc.config!!.pal and 0xF) shl 12)) + proc.layer
        hooks.putSmsObject(
            hooks.spriteAnimObjLayer(proc.spriteAnim), x - 8, y - 16, oam2, proc.jid, proc.slot,
        )
    }

    /**
     * Updates the sprite animation of [proc] at its display position, unless it is hidden, off
     * screen or an enemy in fog. The animation player (`AP_Update`) is [MuHooks.apUpdate].
     *
     * Ported from src/mu.c: PutMu
     */
    public fun putMu(proc: MuProc) {
        if (proc.hiddenB != 0) return
        if (!getMuDisplayPosition(proc)) return
        val x = displayX and 0x1FF
        var y = displayY and 0xFF
        if (proc.state != MU_STATE_DISPLAY_UI && proc.unitAddress != 0 &&
            hooks.unitFaction(proc.unitAddress) == FACTION_RED && playSt.chapterVisionRange != 0
        ) {
            val sum = (proc.xQ4 + proc.xOffsetQ4) shr MU_SUBPIXEL_PRECISION
            val sumY = (proc.yQ4 + proc.yOffsetQ4) shr MU_SUBPIXEL_PRECISION
            if (hooks.fogAt((sum + 8) shr 4, (sumY + 8) shr 4) == 0) return
        }
        if (proc.state == MU_STATE_DEATHFADE) y = y or OAM0_BLEND
        hooks.apUpdate(proc.spriteAnim, x, y)
    }

    /**
     * ROM address of the sprite sheet of [proc]'s class (`gMuInfoTable[jid - 1].img`).
     *
     * Ported from src/mu.c: GetMuImg
     */
    public fun getMuImg(proc: MuProc): Int =
        checkNotNull(rom) { "Mu needs a RomView" }.u32(MU_INFO_TABLE + 8 * (proc.jid - 1))

    /**
     * ROM address of the animation of class [jid] (`gMuInfoTable[jid - 1].anim`).
     *
     * Ported from src/mu.c: GetMuAnimForJid
     */
    public fun getMuAnimForJid(jid: Int): Int =
        checkNotNull(rom) { "Mu needs a RomView" }.u32(MU_INFO_TABLE + 8 * (jid - 1) + 4)

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
        updateMuStepSounds(mu)
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

    /**
     * Runs the move script commands of [proc] until one takes a frame (`proc->config->pc` is the
     * cursor).
     *
     * Ported from src/mu.c: RunMuMoveScript
     */
    public fun runMuMoveScript(proc: MuProc) {
        val config = proc.config!!
        while (true) {
            var command = config.movescr[config.pc++ and 0xFF].toByte().toInt()
            when (command) {
                MOVE_CMD_SLEEP -> {
                    val ticks = config.movescr[config.pc++ and 0xFF].toByte().toInt()
                    proc.moveClockQ4 = ticks and 0xFFFF
                    proc.state = MU_STATE_SLEEPING
                    return
                }
                MOVE_CMD_BUMP -> {
                    proc.state = MU_STATE_BUMPING
                    hooks.startMuFogBump(
                        (proc.xQ4 shr MU_SUBPIXEL_PRECISION) - bm.cameraX,
                        (proc.yQ4 shr MU_SUBPIXEL_PRECISION) - bm.cameraY,
                    )
                    return
                }
                MOVE_CMD_HALT -> {
                    haltMu(proc)
                    return
                }
                MOVE_CMD_END -> {
                    endMu(proc)
                    return
                }
                MOVE_CMD_MOVE_LEFT, MOVE_CMD_MOVE_RIGHT, MOVE_CMD_MOVE_DOWN, MOVE_CMD_MOVE_UP -> {
                    command -= MOVE_CMD_MOVE_BASE
                    if (command != proc.facing) {
                        setMuFacing(proc, command - MOVE_CMD_MOVE_BASE)
                        proc.state = MU_STATE_MOVEMENT
                    }
                    return
                }
                MOVE_CMD_FACE_LEFT, MOVE_CMD_FACE_RIGHT, MOVE_CMD_FACE_DOWN, MOVE_CMD_FACE_UP -> {
                    command -= MOVE_CMD_FACE_BASE
                    if (command != proc.facing) setMuFacing(proc, command)
                }
                MOVE_CMD_SET_SPEED ->
                    proc.moveConfig = config.movescr[config.pc++ and 0xFF].toShort().toInt()
                MOVE_CMD_CAMERA_ON -> enableMuCamera(proc)
                MOVE_CMD_CAMERA_OFF -> disableMuCamera(proc)
            }
        }
    }

    /**
     * Advances a walking MU by one frame step.
     *
     * Ported from src/mu.c: Mu_OnStateMovement
     */
    public fun onStateMovement(proc: MuProc) {
        val moveSpeed = getMuQ4MovementSpeed(proc)
        val dx = MOVE_OFFSET_LUT[proc.facing * 2]
        val dy = MOVE_OFFSET_LUT[proc.facing * 2 + 1]
        proc.moveClockQ4 = (moveSpeed + proc.moveClockQ4) and 0xFFFF
        proc.xQ4 = (proc.xQ4 + moveSpeed * dx).toShort().toInt()
        proc.yQ4 = (proc.yQ4 + moveSpeed * dy).toShort().toInt()
        if (proc.moveClockQ4 / 16 >= 16) {
            proc.moveClockQ4 = (proc.moveClockQ4 - 0x100) and 0xFFFF
            proc.xQ4 = (proc.xQ4 - proc.moveClockQ4 * dx).toShort().toInt()
            proc.yQ4 = (proc.yQ4 - proc.moveClockQ4 * dy).toShort().toInt()
            proc.moveClockQ4 = 0
            proc.xQ4 = (proc.xQ4 and 0xF.inv()).toShort().toInt()
            proc.yQ4 = (proc.yQ4 and 0xF.inv()).toShort().toInt()
        }
        if (proc.camB && procs.find(camMoveScript) == null) {
            bm.cameraX = Camera.getCameraAdjustedX(bm, proc.xQ4 shr MU_SUBPIXEL_PRECISION)
                .toShort().toInt()
            bm.cameraY = Camera.getCameraAdjustedY(bm, proc.yQ4 shr MU_SUBPIXEL_PRECISION)
                .toShort().toInt()
        }
        if (proc.moveConfig and MU_CONFIG_SPEED_NORMAL == 0) updateMuStepSounds(proc)
    }

    /**
     * The movement speed of [proc] in 1/16 pixel per frame.
     *
     * Ported from src/mu.c: GetMuQ4MovementSpeed
     */
    public fun getMuQ4MovementSpeed(proc: MuProc): Int {
        var config = proc.moveConfig
        if (config and 0x80 != 0) config += 0x80
        if (proc.fastWalkB) return 0x100
        if (config != 0x40) {
            if (config != 0x00) {
                var speed = config
                if (speed and 0x40 != 0) {
                    speed = speed xor 0x40
                } else if (playSt.gameSpeed != 0 || keys.heldKeys and A_BUTTON != 0) {
                    speed *= 4
                }
                if (speed > 0x80) speed = 0x80
                return speed and 0xFFFF
            }
            if (!isFirstPlaythrough() && keys.heldKeys and A_BUTTON != 0) return 0x80
            if (playSt.gameSpeed != 0) return 0x40
        }
        return 16 * WALK_SPEED_LUT[hooks.classSlowWalking(proc.jid) and 1]
    }

    private fun isFirstPlaythrough(): Boolean {
        if (!hooks.isGamePlayedThrough()) return true
        if (playSt.chapterStateBits and PLAY_FLAG_EXTRA_MAP != 0) return false
        return playSt.unk41x5 != 0
    }

    /**
     * Advances the step sound clock of [proc] and starts the step sound of its class.
     *
     * Transcribed from src/mu.c: UpdateMuStepSounds (not verified: the walk compares only the
     * clock, not the sound chosen; the class lists are unexercised)
     */
    public fun updateMuStepSounds(proc: MuProc) {
        val jid = proc.jid
        val script: Int
        if (hooks.classAttributes(jid) and CA_MOUNTEDAID != 0) {
            script = when (jid) {
                CLASS_WYVERN_RIDER, CLASS_WYVERN_RIDER_F, CLASS_WYVERN_LORD, CLASS_WYVERN_LORD_F,
                CLASS_WYVERN_KNIGHT, CLASS_WYVERN_KNIGHT_F -> STEP_SE_WYVERN
                CLASS_MOGALL, CLASS_ARCH_MOGALL -> STEP_SE_MOGALL
                CLASS_PEGASUS_KNIGHT, CLASS_FALCON_KNIGHT -> STEP_SE_PEGASUS
                else -> STEP_SE_MOUNTED
            }
        } else {
            script = when (jid) {
                CLASS_REVENANT, CLASS_ENTOUMBED -> STEP_SE_ZOMBIE
                CLASS_BONEWALKER, CLASS_BONEWALKER_BOW, CLASS_WIGHT, CLASS_WIGHT_BOW ->
                    STEP_SE_SKELETON
                CLASS_BAEL, CLASS_ELDER_BAEL -> STEP_SE_SPIDER
                CLASS_MAUTHEDOOG, CLASS_GWYLLGI -> STEP_SE_DOG
                CLASS_TARVOS, CLASS_MAELDUIN -> STEP_SE_MOUNTED
                CLASS_MOGALL, CLASS_ARCH_MOGALL -> STEP_SE_MOGALL
                CLASS_GORGON -> STEP_SE_GORGON
                CLASS_GARGOYLE, CLASS_DEATHGOYLE -> STEP_SE_WYVERN
                CLASS_ARMOR_KNIGHT, CLASS_ARMOR_KNIGHT_F, CLASS_GENERAL, CLASS_GENERAL_F,
                CLASS_MANAKETE_2, CLASS_CYCLOPS, CLASS_DRACO_ZOMBIE, CLASS_DEMON_KING,
                CLASS_BLST_REGULAR_USED, CLASS_BLST_LONG_USED, CLASS_BLST_KILLER_USED ->
                    STEP_SE_FOOT_HEAVY
                CLASS_FLEET -> STEP_SE_BOAT
                CLASS_MANAKETE_MYRRH -> STEP_SE_MYRRH
                CLASS_FALLEN_PRINCE, CLASS_FALLEN_PEER -> return
                else -> STEP_SE_FOOT
            }
        }
        val pc = proc.stepSoundClock % STEP_SE_PERIOD[script]
        proc.stepSoundClock = (proc.stepSoundClock + 1) and 0xFF
        hooks.startPlayMuStepSe(proc, script, pc)
    }

    /**
     * Runs one frame of [proc]: the move script when idle between tiles, then the state handler.
     * The sprite is put by [putMu] or [putMuSms].
     *
     * Ported from src/mu.c: Mu_OnLoop
     */
    public fun onLoop(proc: MuProc) {
        if (proc.state != 0) {
            if (proc.moveClockQ4 == 0) {
                if (proc.state == MU_STATE_SLEEPING || proc.state == MU_STATE_MOVEMENT) {
                    runMuMoveScript(proc)
                }
            }
            when (proc.state) {
                MU_STATE_MOVEMENT -> onStateMovement(proc)
                MU_STATE_SLEEPING -> {
                    if (proc.moveClockQ4 == 0) {
                        proc.state = MU_STATE_MOVEMENT
                    } else {
                        proc.moveClockQ4--
                    }
                }
                MU_STATE_UNK4 -> proc.state = MU_STATE_MOVEMENT
                MU_STATE_BUMPING -> if (!hooks.isFogBumpFxActive()) proc.state = MU_STATE_SLEEPING
            }
        }
        if (proc.facing == MU_FACING_STANDING) putMuSms(proc) else putMu(proc)
    }

    /**
     * Frees the slot and deletes the sprite animation.
     *
     * Ported from src/mu.c: MU_OnEnd
     */
    public fun onEnd(proc: MuProc) {
        proc.config!!.slot = 0
        hooks.deleteMuAnim(proc)
        proc.inUse = false
    }

    /**
     * Ends every MU proc.
     *
     * Transcribed from src/mu.c: EndAllMus (not verified: the walk ends one MU through EndMu)
     */
    public fun endAllMus() {
        procs.endEach(script)
    }

    /**
     * Ends [proc]'s proc (`EndMuExt`: `Proc_End`).
     *
     * Ported from src/mu.c: EndMu
     */
    public fun endMu(proc: MuProc) {
        procs.end(proc.owner)
    }

    /**
     * Lets [proc] scroll the camera while it walks.
     *
     * Transcribed from src/mu.c: EnableMuCamera (not verified: trivial setter)
     */
    public fun enableMuCamera(proc: MuProc) {
        proc.camB = true
    }

    /**
     * Stops [proc] scrolling the camera.
     *
     * Transcribed from src/mu.c: DisableMuCamera (not verified: trivial setter, driven by the test
     * only as the event code's input)
     */
    public fun disableMuCamera(proc: MuProc) {
        proc.camB = false
    }

    private fun haltMu(proc: MuProc) {
        proc.state = MU_STATE_INACTIVE
    }

    /**
     * Applies the move script [movescr] to the tile coordinates in [xy] (`xy[0]` = x, `xy[1]` = y).
     *
     * Ported from src/mu.c: ApplyMoveScriptToCoordinates
     */
    public fun applyMoveScriptToCoordinates(xy: IntArray, movescr: IntArray) {
        var i = 0
        while (true) {
            when (movescr[i++].toByte().toInt()) {
                MOVE_CMD_END, MOVE_CMD_HALT -> return
                MOVE_CMD_MOVE_LEFT -> xy[0]--
                MOVE_CMD_MOVE_RIGHT -> xy[0]++
                MOVE_CMD_MOVE_UP -> xy[1]--
                MOVE_CMD_MOVE_DOWN -> xy[1]++
                MOVE_CMD_SLEEP -> i++
            }
        }
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


        /** `MU_STATE_SLEEPING`. */
        public const val MU_STATE_SLEEPING: Int = 3

        /** `MU_STATE_UNK4`. */
        public const val MU_STATE_UNK4: Int = 4

        /** `MU_STATE_DISPLAY_UI`. */
        public const val MU_STATE_DISPLAY_UI: Int = 6

        /** `MU_STATE_DEATHFADE`. */
        public const val MU_STATE_DEATHFADE: Int = 7

        /** `FACTION_RED`. */
        public const val FACTION_RED: Int = 0x80

        /** `OAM0_BLEND`. */
        public const val OAM0_BLEND: Int = 0x400

        /** `gMuInfoTable` (`unit_icon_move_table`) in the ROM. */
        public const val MU_INFO_TABLE: Int = 0x089A2E00

        /** `MU_STATE_BUMPING`. */
        public const val MU_STATE_BUMPING: Int = 5

        /** `MU_CONFIG_SPPED_NORMAL`. */
        public const val MU_CONFIG_SPEED_NORMAL: Int = 0x80

        /** `MOVE_CMD_END`. */
        public const val MOVE_CMD_END: Int = -1

        /** `MOVE_CMD_MOVE_BASE`. */
        public const val MOVE_CMD_MOVE_BASE: Int = 0

        /** `MOVE_CMD_MOVE_LEFT`. */
        public const val MOVE_CMD_MOVE_LEFT: Int = 0

        /** `MOVE_CMD_MOVE_RIGHT`. */
        public const val MOVE_CMD_MOVE_RIGHT: Int = 1

        /** `MOVE_CMD_MOVE_DOWN`. */
        public const val MOVE_CMD_MOVE_DOWN: Int = 2

        /** `MOVE_CMD_MOVE_UP`. */
        public const val MOVE_CMD_MOVE_UP: Int = 3

        /** `MOVE_CMD_HALT`. */
        public const val MOVE_CMD_HALT: Int = 4

        /** `MOVE_CMD_FACE_BASE`. */
        public const val MOVE_CMD_FACE_BASE: Int = 5

        /** `MOVE_CMD_FACE_LEFT`. */
        public const val MOVE_CMD_FACE_LEFT: Int = 5

        /** `MOVE_CMD_FACE_RIGHT`. */
        public const val MOVE_CMD_FACE_RIGHT: Int = 6

        /** `MOVE_CMD_FACE_DOWN`. */
        public const val MOVE_CMD_FACE_DOWN: Int = 7

        /** `MOVE_CMD_FACE_UP`. */
        public const val MOVE_CMD_FACE_UP: Int = 8

        /** `MOVE_CMD_SLEEP`. */
        public const val MOVE_CMD_SLEEP: Int = 9

        /** `MOVE_CMD_BUMP`. */
        public const val MOVE_CMD_BUMP: Int = 10

        /** `MOVE_CMD_SET_SPEED`. */
        public const val MOVE_CMD_SET_SPEED: Int = 12

        /** `MOVE_CMD_CAMERA_ON`. */
        public const val MOVE_CMD_CAMERA_ON: Int = 13

        /** `MOVE_CMD_CAMERA_OFF`. */
        public const val MOVE_CMD_CAMERA_OFF: Int = 14

        /** Step sound script `MuSoundScr_Foot` for [MuHooks.startPlayMuStepSe]. */
        public const val STEP_SE_FOOT: Int = 0

        /** `MuSoundScr_FootHeavy`. */
        public const val STEP_SE_FOOT_HEAVY: Int = 1

        /** `MuSoundScr_Mounted`. */
        public const val STEP_SE_MOUNTED: Int = 2

        /** `MuSoundScr_Wyvern`. */
        public const val STEP_SE_WYVERN: Int = 3

        /** `MuSoundScr_Pegasus`. */
        public const val STEP_SE_PEGASUS: Int = 4

        /** `MuSoundScr_Zombie`. */
        public const val STEP_SE_ZOMBIE: Int = 5

        /** `MuSoundScr_Skeleton`. */
        public const val STEP_SE_SKELETON: Int = 6

        /** `MuSoundScr_Mogall`. */
        public const val STEP_SE_MOGALL: Int = 7

        /** `MuSoundScr_Spider`. */
        public const val STEP_SE_SPIDER: Int = 8

        /** `MuSoundScr_Dog`. */
        public const val STEP_SE_DOG: Int = 9

        /** `MuSoundScr_Gorgon`. */
        public const val STEP_SE_GORGON: Int = 10

        /** `MuSoundScr_Boat`. */
        public const val STEP_SE_BOAT: Int = 11

        /** `MuSoundScr_Myrrh`. */
        public const val STEP_SE_MYRRH: Int = 12

        private const val A_BUTTON = 1
        private const val PLAY_FLAG_EXTRA_MAP = 1 shl 7
        private const val CLASS_ARMOR_KNIGHT = 0x09
        private const val CLASS_ARMOR_KNIGHT_F = 0x0A
        private const val CLASS_GENERAL = 0x0B
        private const val CLASS_GENERAL_F = 0x0C
        private const val CLASS_WYVERN_RIDER = 0x1F
        private const val CLASS_WYVERN_RIDER_F = 0x20
        private const val CLASS_WYVERN_LORD = 0x21
        private const val CLASS_WYVERN_LORD_F = 0x22
        private const val CLASS_WYVERN_KNIGHT = 0x23
        private const val CLASS_WYVERN_KNIGHT_F = 0x24
        private const val CLASS_MANAKETE_2 = 0x3B
        private const val CLASS_MANAKETE_MYRRH = 0x3C
        private const val CLASS_PEGASUS_KNIGHT = 0x48
        private const val CLASS_FALCON_KNIGHT = 0x49
        private const val CLASS_FLEET = 0x50
        private const val CLASS_REVENANT = 0x52
        private const val CLASS_ENTOUMBED = 0x53
        private const val CLASS_BONEWALKER = 0x54
        private const val CLASS_BONEWALKER_BOW = 0x55
        private const val CLASS_WIGHT = 0x56
        private const val CLASS_WIGHT_BOW = 0x57
        private const val CLASS_BAEL = 0x58
        private const val CLASS_ELDER_BAEL = 0x59
        private const val CLASS_CYCLOPS = 0x5A
        private const val CLASS_MAUTHEDOOG = 0x5B
        private const val CLASS_GWYLLGI = 0x5C
        private const val CLASS_TARVOS = 0x5D
        private const val CLASS_MAELDUIN = 0x5E
        private const val CLASS_MOGALL = 0x5F
        private const val CLASS_ARCH_MOGALL = 0x60
        private const val CLASS_GORGON = 0x61
        private const val CLASS_GARGOYLE = 0x63
        private const val CLASS_DEATHGOYLE = 0x64
        private const val CLASS_DRACO_ZOMBIE = 0x65
        private const val CLASS_DEMON_KING = 0x66
        private const val CLASS_FALLEN_PRINCE = 0x78
        private const val CLASS_FALLEN_PEER = 0x7B
        private val MOVE_OFFSET_LUT = intArrayOf(-1, 0, 1, 0, 0, 1, 0, -1)
        private val WALK_SPEED_LUT = intArrayOf(2, 1)
        private val STEP_SE_PERIOD =
            intArrayOf(0x10, 0x20, 0x15, 0x14, 0x14, 0x0F, 0x0F, 0x14, 0x0A, 0x15, 0x0F, 0x18, 0x10)

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
