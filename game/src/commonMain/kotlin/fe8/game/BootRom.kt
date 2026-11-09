package fe8.game

import fe8.game.opanim.OpAnim
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
        rom: RomView,
        engine: ProcEngine,
        hw: BgHardware,
        sprites: SpriteLayers = SpriteLayers(),
        keys: KeyStatus = KeyStatus(),
        gameControl: GameControl? = null,
    ): TitleScreen {
        val gfx = TitleScreen.Gfx(
            listOf(rom.blob(0x08AA7760), rom.blob(0x08AA9708), rom.blob(0x08AAAE90)),
            rom.pal(0x08AAB3F4, 16),
            rom.blob(0x08AAB414) to rom.blob(0x08AAC2A4),
            rom.pal(0x08AAC58C, 16),
            rom.blob(0x08AAC5AC),
            rom.blob(0x08AACEDC),
            rom.pal(0x08AADB68, 64),
            listOf(
                0x08AAFD14, 0x08AAFF10, 0x08AADC08, 0x08AAE61C,
                0x08AAE8EC, 0x08AAF928, 0x08AB1C24, 0x08AB216C,
            ).map { rom.blob(it) },
            listOf(
                rom.pal(0x08AB0114, 16), rom.pal(0x08AAE8CC, 16), rom.pal(0x08AAFCF4, 16),
                rom.pal(0x08AB210C, 48), rom.pal(0x08AB21B8, 16),
            ),
            Triple(rom.blob(0x08AB0B44), rom.blob(0x08AB1890), rom.pal(0x08AB1C04, 16)),
            Triple(rom.blob(0x08AB0134), rom.blob(0x08AB0A20), rom.pal(0x08AB0B24, 16)),
        )
        return TitleScreen(
            engine, hw, gfx, rom.obj(0x08590F54, 4), sprites,
            object8x8 = rom.obj(0x08590F44, 4),
            bannerSprite = rom.obj(0x08AA6794, 13),
            menuSprites = listOf(
                rom.obj(0x08590F4C, 4), rom.obj(0x08AA6774, 16),
                rom.obj(0x08AA67E0, 13), rom.obj(0x08AA67AE, 25),
            ),
            glowPal = rom.pal(0x08AADBE8, 16),
            keys = keys,
            gameControl = gameControl,
        )
    }

    /**
     * The op anim (`ProcScr_OpAnim`) with all its scenes' graphics: split line, world map, Ephraim,
     * Eirika and the cast scenes.
     */
    public fun opAnim(
        rom: RomView,
        engine: ProcEngine,
        hw: BgHardware,
        keys: KeyStatus,
        gameControl: GameControl,
    ): OpAnim {
        /** Builds an [OpAnim.Face] from image, shadow, and palette ROM addresses. */
        fun face(imageAddress: Int, shadowAddress: Int, paletteAddress: Int) =
            OpAnim.Face(
                rom.blob(imageAddress), rom.blob(shadowAddress), rom.pal(paletteAddress, 16),
            )

        /** Pairs the decompressed image blob with its TSA map blob from ROM. */
        fun pair(imageAddress: Int, tsaAddress: Int) =
            rom.blob(imageAddress) to rom.blob(tsaAddress)

        val opGfx = OpAnim.Gfx(
            listOf(0x08AF1AE8, 0x08AF1B38, 0x08AF1FD0, 0x08AF2654).map { rom.blob(it) },
            listOf(0x08AF1B18, 0x08AF1FB0, 0x08AF2634, 0x08AF310C).map { rom.pal(it, 16) },
        )
        val worldMap = OpAnim.WorldMapGfx(
            rom.blob(0x08AB21D8),
            rom.blob(0x08AB5D90),
            rom.blob(0x08AB632C),
            rom.blob(0x08AB7AC0),
            rom.pal(0x08AB7DB0, 16),
            rom.pal(0x08AB630C, 16),
        )
        val ephraim = OpAnim.EphraimGfx(
            pair(0x08AB7DD0, 0x08AB87E0),
            pair(0x08ABF168, 0x08AC1878),
            pair(0x08AC0BD8, 0x08AC1B98),
            pair(0x08ABB14C, 0x08ABC074),
            rom.pal(0x08AC1C8C, 16 * 11),
            pair(0x08ABE304, 0x08ABEF70),
            pair(0x08ABD348, 0x08ABE120),
            pair(0x08ABC22C, 0x08ABD174),
            rom.pal(0x08AB8CAC, 16),
            rom.obj(0x08590F9C, 4) to rom.obj(0x08590FA4, 4),
            rom.obj(0x08AA6BFA, 10),
        )
        val eirika = OpAnim.EirikaGfx(
            pair(0x08AC5614, 0x08AC8DDC),
            pair(0x08AC7374, 0x08AC91F8),
            pair(0x08AC1DEC, 0x08AC2B24),
            rom.pal(0x08AC933C, 16 * 11),
            pair(0x08AC4928, 0x08AC5420),
            pair(0x08AC3BC8, 0x08AC4760),
            pair(0x08AC2CB4, 0x08AC3A2C),
            rom.obj(0x08AA6C0E, 10),
            List(100) { rom.blob(rom.u32(0x08AA6D14 + 4 * it)) },
            List(100) { rom.blob(rom.u32(0x08AA6EA4 + 4 * it)) },
        )
        val cast = OpAnim.CastGfx(
            rom.pal(0x08AF47F0, 16 * 4),
            rom.pal(0x08B103D8, 16 * 8),
            pair(0x08AB8CCC, 0x08AB942C),
            pair(0x08AF312C, 0x08AF404C),
            OpAnim.CastScene(
                pair(0x08AC949C, 0x08ACBF58),
                pair(0x08ACAF70, 0x08ACC340),
                rom.pal(0x08ACC3E0, 16 * 11),
                true, 0x118, 0xE8, 0xE8,
            ),
            listOf(
                OpAnim.CastScene(
                    pair(0x08ACC540, 0x08ACEED0),
                    pair(0x08ACE0B8, 0x08ACF200),
                    rom.pal(0x08ACF314, 16 * 11),
                    false, 0xC0, 0x140, 0x108,
                ),
                OpAnim.CastScene(
                    pair(0x08ACF474, 0x08AD206C),
                    pair(0x08AD1048, 0x08AD236C),
                    rom.pal(0x08AD24B4, 16 * 11),
                    true, 0xC0, 0x140, 0x118,
                ),
                OpAnim.CastScene(
                    pair(0x08AD2614, 0x08AD5094),
                    pair(0x08AD423C, 0x08AD543C),
                    rom.pal(0x08AD54DC, 16 * 11),
                    false, 0x110, 0xF0, 0x108,
                ),
                OpAnim.CastScene(
                    pair(0x08AD563C, 0x08AD8518),
                    pair(0x08AD72FC, 0x08AD88D4),
                    rom.pal(0x08AD89A8, 16 * 11),
                    true, 0xE8, 0x118, 0xE8,
                ),
                OpAnim.CastScene(
                    pair(0x08AD8B08, 0x08ADB804),
                    pair(0x08ADA31C, 0x08ADBC0C),
                    rom.pal(0x08ADBD18, 16 * 11),
                    false, 200, 0x138, 0x128,
                ),
            ),
            listOf(
                0x08AA6C22, 0x08AA6C36, 0x08AA6C4A, 0x08AA6C58, 0x08AA6C66, 0x08AA6C74,
                0x08AA6C82, 0x08AA6C8A, 0x08AA6C92, 0x08AA6C9A,
            ).map { rom.objN(it) },
            rom.pal(0x08ADBE78, 16),
            listOf(
                OpAnim.Face(rom.blob(0x08ADBE98), rom.blob(0x08ADC3C0), rom.pal(0x08ADC708, 16)),
                OpAnim.Face(rom.blob(0x08ADC728), rom.blob(0x08ADCCB8), rom.pal(0x08ADD050, 16)),
                OpAnim.Face(rom.blob(0x08ADD070), rom.blob(0x08ADD6EC), rom.pal(0x08ADDA54, 16)),
            ),
            rom.objN(0x08AA709C),
            rom.objN(0x08AA70BC),
            listOf(
                OpAnim.Face(rom.blob(0x08ADDA74), rom.blob(0x08ADE080), rom.pal(0x08ADE47C, 16)),
                OpAnim.Face(rom.blob(0x08ADEEF4), rom.blob(0x08ADF58C), rom.pal(0x08ADFA08, 16)),
                OpAnim.Face(rom.blob(0x08ADE49C), rom.blob(0x08ADEB1C), rom.pal(0x08ADEED4, 16)),
                OpAnim.Face(rom.blob(0x08ADFA28), rom.blob(0x08AE00E4), rom.pal(0x08AE04D4, 16)),
            ),
            rom.objN(0x08AA70DC),
            listOf(
                OpAnim.Face(rom.blob(0x08AE04F4), rom.blob(0x08AE0A48), rom.pal(0x08AE0D74, 16)),
                OpAnim.Face(rom.blob(0x08AE0D94), rom.blob(0x08AE12BC), rom.pal(0x08AE1634, 16)),
            ),
            rom.objN(0x08AA70EA),
            listOf(
                OpAnim.Face(rom.blob(0x08AE1654), rom.blob(0x08AE1D00), rom.pal(0x08AE20C4, 16)),
                OpAnim.Face(rom.blob(0x08AE20E4), rom.blob(0x08AE281C), rom.pal(0x08AE2BF4, 16)),
                OpAnim.Face(rom.blob(0x08AE2C14), rom.blob(0x08AE33D4), rom.pal(0x08AE3734, 16)),
            ),
            rom.objN(0x08AA7116),
            listOf(
                OpAnim.Face(rom.blob(0x08AE3754), rom.blob(0x08AE3E54), rom.pal(0x08AE41E4, 16)),
                OpAnim.Face(rom.blob(0x08AE4204), rom.blob(0x08AE4940), rom.pal(0x08AE4CE8, 16)),
                OpAnim.Face(rom.blob(0x08AE4D08), rom.blob(0x08AE53C8), rom.pal(0x08AE5730, 16)),
            ),
            listOf(
                listOf(
                    face(0x08AE5750, 0x08AE5D54, 0x08AE60B0),
                    face(0x08AE60D0, 0x08AE6648, 0x08AE69EC),
                ),
                listOf(
                    face(0x08AE6A0C, 0x08AE6FA8, 0x08AE72C8),
                    face(0x08AE84DC, 0x08AE8AB8, 0x08AE8D84),
                    face(0x08AE72E8, 0x08AE78B0, 0x08AE7C2C),
                    face(0x08AE7C4C, 0x08AE819C, 0x08AE84BC),
                ),
                listOf(
                    face(0x08AE8DA4, 0x08AE9364, 0x08AE9720),
                    face(0x08AE9740, 0x08AE9CA4, 0x08AEA02C),
                ),
                listOf(
                    face(0x08AECAE0, 0x08AED0A4, 0x08AED3E8),
                    face(0x08AED408, 0x08AED95C, 0x08AEDC80),
                    face(0x08AEDCA0, 0x08AEE218, 0x08AEE564),
                ),
                listOf(
                    face(0x08AEA04C, 0x08AEA7A8, 0x08AEAB50),
                    face(0x08AEAB70, 0x08AEB1B8, 0x08AEB56C),
                    face(0x08AEB58C, 0x08AEBCEC, 0x08AEC048),
                    face(0x08AEC068, 0x08AEC724, 0x08AECAC0),
                ),
                listOf(
                    face(0x08AEE584, 0x08AEEBB0, 0x08AEEECC),
                    face(0x08AEEEEC, 0x08AEF580, 0x08AEF880),
                    face(0x08AEF8A0, 0x08AEFDC8, 0x08AF0130),
                ),
                listOf(
                    face(0x08AF0150, 0x08AF09E4, 0x08AF0DE8),
                    face(0x08AF0E08, 0x08AF16CC, 0x08AF1AC8),
                ),
            ),
            rom.objN(0x08AA712A),
            rom.objN(0x08AA715C),
            rom.objN(0x08AA7194),
            rom.blob(0x08AF4330),
        )
        return OpAnim(
            engine, hw, keys, gameControl, opGfx, worldMap, ephraim, eirika = eirika, cast = cast,
        )
    }
}
