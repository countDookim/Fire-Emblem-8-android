package gbafe.audio

/**
 * CGB (PSG) channel helpers of `CgbSound`. Not verified yet (needs `CgbSound` against
 * `ram_trace_menu_cgb`); no `Ported from` until then.
 */
public object M4aCgb {
    /**
     * `CgbModVol`: hard pan (0x0F right / 0xF0 left) when one side is at most half the other,
     * else both (0xFF); envelope goal `eg` = (right + left) / 16 (capped at 15 when hard-panned),
     * sustain goal `sg` = (eg * su + 15) >> 4; `pan` masked by `panMask`.
     */
    public fun modVol(c: M4aChannel) {
        val r = c.rightVolume and 0xFF
        val l = c.leftVolume and 0xFF
        val hard = if (r >= l) r / 2 >= l else l / 2 >= r
        var eg = (c.rightVolume + c.leftVolume) / 16
        if (hard) {
            c.pan = if (r >= l) 0x0F else 0xF0
            if (eg > 15) eg = 15
        } else {
            c.pan = 0xFF
        }
        eg = eg and 0xFF
        c.envelopeRight = eg // `eg` shares offset 0x0A with envelopeRight
        val su = (c.adsr ushr 16) and 0xFF
        c.sustainGoal = ((eg * su + 15) shr 4) and 0xFF
        c.pan = c.pan and c.panMask
    }
}
