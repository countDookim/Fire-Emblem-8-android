package fe8.game.ui

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.TextSystem

/**
 * Hosts `ProcScr_Popup` (decomp `src/popup.c`) on a [ProcEngine]: the proc's script mirrors the
 * original command for command and calls the step functions of [Popup]. The proc's user data
 * ([Proc.state]) is a [Popup.PopupProc]. A popup is the framed banner shown over the map when an
 * item or gold is received or stolen, a weapon breaks and so on; the proc closes itself after
 * its clock runs out or a key is pressed and erases its frame.
 */
public class PopupScript(
    /** The proc engine the popup proc runs on. */
    public val engine: ProcEngine,
    /** What the popup steps read and write outside the proc. */
    public val deps: Dependencies,
) {
    /**
     * Writes a popup icon sprite to the secondary OAM buffer (`CallARM_PushToSecondaryOAM`, the
     * OAM list for sprites drawn after the map's).
     */
    public fun interface SecondaryOamWriter {
        /**
         * Pushes the ROM sprite shape [objectAddress] at pixel ([x], [y]) with OAM attribute 2
         * [oam2] (tile id and palette).
         */
        public fun push(x: Int, y: Int, objectAddress: Int, oam2: Int)
    }

    /** The external state and services the popup steps use; see [Popup.prepareGfx]. */
    public class Dependencies(
        /** ROM view. */
        public val rom: RomView,
        /** Message strings (`GetMsg`). */
        public val messages: MsgStrings,
        /** Text system. */
        public val text: TextSystem,
        /** BG, VRAM and LCD state. */
        public val hardware: BgHardware,
        /** `DrawnIconLookupTable`. */
        public val drawnIconLookupTable: ByteArray,
        /** `IconGFXIDLookupTable`. */
        public val iconGfxIdLookupTable: ByteArray,
        /** `gPopupItem`: the item shown by the item opcodes (`SetPopupItem`). */
        public val popupItem: () -> Int,
        /** `gPopupNumber`: the number shown by `POPUP_OP_NUM` (`SetPopupNumber`). */
        public val popupNumber: () -> Int,
        /** Text id of the popup unit's name (`gpPopupUnit->pCharacterData->nameTextId`). */
        public val unitNameTextId: () -> Int,
        /** `LoadIconPalette(slot, palId)`; slot 0 for item icons, 1 for weapon types. */
        public val loadIconPalette: (slot: Int, palId: Int) -> Unit,
        /**
         * `StartBgmVolumeChange(volume, end, time, proc)`; [proc] is the popup proc, which the
         * original blocks until the volume change ends.
         */
        public val startBgmVolumeChange: (proc: Proc, volume: Int, end: Int, time: Int) -> Unit,
        /** `PlaySoundEffect`. */
        public val playSoundEffect: (songId: Int) -> Unit,
        /** `gKeyStatusPtr->newKeys`: the buttons newly pressed this frame. */
        public val newKeys: () -> Int,
        /**
         * `CallARM_PushToSecondaryOAM`: the object address is `gObject_16x16` for the popup icon.
         */
        public val pushToSecondaryOam: SecondaryOamWriter =
            SecondaryOamWriter { _, _, _, _ -> },
    )

    /** The [Popup.PopupProc] user data of [proc]. */
    private fun popupOf(proc: Proc): Popup.PopupProc = proc.state as Popup.PopupProc

    /**
     * `ProcScr_PopupUpdateIcon`: the child's state is [Popup.iconChildFields] (`unk_2C`, `unk_30`,
     * `unk_4A`), and its loop pushes the 16x16 object every frame. The original has no
     * `PROC_END`; the trailing one is never reached because the loop never breaks.
     *
     * Transcribed from src/popup.c: ProcScr_PopupUpdateIcon (not verified: no @VerifiesPort test
     * names it)
     */
    public val iconScript: ProcScript = ProcScript(
        "ProcScr_PopupUpdateIcon",
        listOf(repeat(ProcFunc { proc -> iconLoop(proc.state as IntArray) }), end()),
    )

    /**
     * Pushes the popup icon's 16x16 sprite, at the position and attributes in [fields], to the
     * secondary OAM. [iconScript] runs this every frame.
     *
     * Ported from src/popup.c: PopupIconUpdateProc_Loop
     *
     * [fields] are `unk_2C`, `unk_30` and `unk_4A` of the child.
     */
    private fun iconLoop(fields: IntArray) {
        deps.pushToSecondaryOam.push(fields[0], fields[1], OBJECT_16X16, fields[2])
    }

    /** Starts [iconScript] as a child of [parent], keeping [fields] as the child's state. */
    private fun startIconChild(parent: Proc, fields: IntArray) {
        engine.start(iconScript, parent).state = fields
    }

    /**
     * The repeated step of [script]: counts the clock down or waits for a key, and breaks the
     * popup proc once [Popup.waitForPress] reports it done.
     */
    private val waitForPress = ProcFunc { proc ->
        val popup = popupOf(proc)
        Popup.waitForPress(popup, deps.newKeys)
        if (popup.broken) {
            popup.broken = false
            engine.breakProc(proc)
        }
    }

    /**
     * `ProcScr_Popup`: set the end callback (erases the frame), init, sleep 10 frames (the
     * delay before the popup appears), prepare graphics, dim the BGM, yield, play the sound,
     * draw, wait for the clock or a key, restore the BGM, yield, end.
     *
     * Transcribed from src/popup.c: ProcScr_Popup (not verified: no @VerifiesPort test names it)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_Popup",
        listOf(
            setEndCb {
                Popup.gfxClear(popupOf(it), deps.hardware.mapBuffer(0), deps.hardware.mapBuffer(1))
                deps.hardware.enableBgSyncByMask(3)
            },
            call { Popup.init(popupOf(it)) },
            sleep(0xA),
            call {
                Popup.prepareGfx(
                    popupOf(it), deps.rom, deps.messages, deps.text, deps.hardware,
                    deps.drawnIconLookupTable, deps.iconGfxIdLookupTable, deps.popupItem(),
                    deps.popupNumber(), deps.unitNameTextId(), deps.loadIconPalette,
                )
            },
            call { proc ->
                Popup.maybeSetVolume(popupOf(proc)) { v, e, t ->
                    deps.startBgmVolumeChange(proc, v, e, t)
                }
            },
            yield(),
            call { Popup.playSound(popupOf(it), deps.playSoundEffect) },
            call {
                Popup.gfxDraw(
                    popupOf(it), deps.rom, deps.messages, deps.text, deps.hardware,
                    deps.popupItem(), deps.popupNumber(), deps.unitNameTextId(),
                    deps.loadIconPalette,
                )?.let { fields -> startIconChild(it, fields) }
            },
            repeat(waitForPress),
            call { proc ->
                Popup.maybeResetVolume(popupOf(proc)) { v, e, t ->
                    deps.startBgmVolumeChange(proc, v, e, t)
                }
            },
            yield(),
            end(),
        ),
        newState = { Popup.PopupProc() },
    )

    /**
     * Starts a popup showing the instruction list at [definition] (`NewPopup_Simple`, which
     * calls `NewPopupCore`): blocking [parent] when it is non-null, otherwise as a root proc of
     * tree 3 (`PROC_TREE_3`). [clock] is the number of frames it stays up (0x60 in the game),
     * [winStyle] the frame style. Returns the proc, or null if a blocking popup ended while
     * starting.
     *
     * Ported from src/popup.c: NewPopup_Simple
     *
     * Ported from src/popup.c: NewPopupCore
     */
    public fun start(parent: Proc?, definition: Int, clock: Int, winStyle: Int): Proc? {
        val proc =
            if (parent != null) engine.startBlocking(script, parent) else engine.start(script, 3)
        if (proc != null) Popup.setupSimple(popupOf(proc), definition, clock, winStyle)
        return proc
    }

    private companion object {
        /** ROM address of `gObject_16x16`, the 16x16 sprite shape of the popup icon. */
        const val OBJECT_16X16 = 0x08590F4C
    }
}
