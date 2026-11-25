package fe8.game.ui

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/** Per-proc state of `gProcScr_HelpPromptSpr`: `struct HelpPromptSprProc` (`+0x2C..`). */
public class HelpPromptSprState {
    /** `xDisplay` (+0x2C). */
    public var xDisplay: Int = 0

    /** `yDisplay` (+0x30). */
    public var yDisplay: Int = 0

    /** `tileref` (+0x34): OAM attribute 2 base (`TILEREF(0, palette)`). */
    public var tileref: Int = 0
}

/**
 * The 'R is Info' prompt sprite of the stat screen and save menu (`src/statscreen.c`,
 * `PC-11d`): [startHelpPromptSprite] starts (or reuses) the proc, which puts the prompt object on
 * sprite layer 0 every frame. `EndHelpPromptSprite` and `MoveHelpPromptSprite` are not run by the
 * title menu captures and are not written.
 *
 * @param engine proc scheduler running `gProcScr_HelpPromptSpr`
 * @param layers sprite layer lists receiving the prompt
 * @param hardware palette buffer for `ApplyPalette`
 * @param rom ROM view holding `sSprite_MetaHelp` and `Pal_MapBattleInfoNum`
 */
public class HelpPromptSprite(
    private val engine: ProcEngine,
    private val layers: SpriteLayers,
    private val hardware: BgHardware,
    private val rom: RomView,
) {
    private val promptSprite: SpriteObject = rom.objN(SPRITE_META_HELP)

    /**
     * `gProcScr_HelpPromptSpr`: yields one frame, then repeats [helpPromptOnIdle].
     *
     * Transcribed from src/statscreen.c: gProcScr_HelpPromptSpr (not verified: the procs watch
     * cannot tag a script; the one-frame yield shows as the proc's first drawn frame)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_HelpPromptSpr",
        listOf(
            yield(),
            repeat(ProcFunc { helpPromptOnIdle(it) }),
            end(),
        ),
        newState = { HelpPromptSprState() },
    )

    /**
     * Puts `sSprite_MetaHelp` on sprite layer 0 at the proc's display position with its tileref.
     *
     * Ported from src/statscreen.c: HelpPrompt_OnIdle
     */
    public fun helpPromptOnIdle(proc: Proc) {
        val state = proc.state as HelpPromptSprState
        layers.put(PROMPT_LAYER, state.xDisplay, state.yDisplay, promptSprite, state.tileref)
    }

    /**
     * Loads `Pal_MapBattleInfoNum` into palette bank `palid + 0x10`, starts the prompt proc under
     * [parent] unless one exists, and sets its position and palette.
     *
     * Ported from src/statscreen.c: StartHelpPromptSprite
     */
    public fun startHelpPromptSprite(x: Int, y: Int, palid: Int, parent: Proc): Proc {
        var proc = engine.find(script)
        applyPalette(PAL_MAP_BATTLE_INFO_NUM, palid + OBJ_PALETTE_BASE)
        if (proc == null) proc = engine.start(script, parent)
        val state = proc.state as HelpPromptSprState
        state.xDisplay = x
        state.yDisplay = y
        state.tileref = (palid and 0xF) shl 12
        return proc
    }

    private fun applyPalette(at: Int, palId: Int) {
        for (i in 0 until 16) hardware.palette[palId * 16 + i] = rom.u16(at + 2 * i).toShort()
        hardware.enablePaletteSync()
    }

    /** Addresses and layer of the prompt. */
    public companion object {
        /** ROM address of `sSprite_MetaHelp`. */
        public const val SPRITE_META_HELP: Int = 0x08A00B10

        /** ROM address of `Pal_MapBattleInfoNum`. */
        public const val PAL_MAP_BATTLE_INFO_NUM: Int = 0x08A1D79C

        /** Sprite layer `PutSprite(0, ...)`. */
        public const val PROMPT_LAYER: Int = 0

        private const val OBJ_PALETTE_BASE = 0x10
    }
}
