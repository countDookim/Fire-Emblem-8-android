package fe8.game

import fe8.game.title.TitleScreen
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers

/**
 * Builds the boot screens with their ROM data (addresses from decomp/fireemblem8.nm.txt, pinned
 * 04d1edb) from a [RomView].
 */
public object BootRom {
    /** The title screen (`ProcScr_TitleScreen`) with its graphics, sprites and palettes. */
    public fun titleScreen(
        v: RomView,
        engine: ProcEngine,
        hw: BgHardware,
        sprites: SpriteLayers = SpriteLayers(),
        keys: KeyStatus = KeyStatus(),
        gameControl: GameControl? = null,
    ): TitleScreen {
        val gfx = TitleScreen.Gfx(
            listOf(v.blob(0x08AA7760), v.blob(0x08AA9708), v.blob(0x08AAAE90)),
            v.pal(0x08AAB3F4, 16),
            v.blob(0x08AAB414) to v.blob(0x08AAC2A4),
            v.pal(0x08AAC58C, 16),
            v.blob(0x08AAC5AC),
            v.blob(0x08AACEDC),
            v.pal(0x08AADB68, 64),
            listOf(
                0x08AAFD14, 0x08AAFF10, 0x08AADC08, 0x08AAE61C,
                0x08AAE8EC, 0x08AAF928, 0x08AB1C24, 0x08AB216C,
            ).map { v.blob(it) },
            listOf(
                v.pal(0x08AB0114, 16), v.pal(0x08AAE8CC, 16), v.pal(0x08AAFCF4, 16),
                v.pal(0x08AB210C, 48), v.pal(0x08AB21B8, 16),
            ),
            Triple(v.blob(0x08AB0B44), v.blob(0x08AB1890), v.pal(0x08AB1C04, 16)),
            Triple(v.blob(0x08AB0134), v.blob(0x08AB0A20), v.pal(0x08AB0B24, 16)),
        )
        return TitleScreen(
            engine, hw, gfx, v.obj(0x08590F54, 4), sprites,
            object8x8 = v.obj(0x08590F44, 4),
            bannerSprite = v.obj(0x08AA6794, 13),
            menuSprites = listOf(
                v.obj(0x08590F4C, 4), v.obj(0x08AA6774, 16),
                v.obj(0x08AA67E0, 13), v.obj(0x08AA67AE, 25),
            ),
            glowPal = v.pal(0x08AADBE8, 16),
            keys = keys,
            gameControl = gameControl,
        )
    }
}
