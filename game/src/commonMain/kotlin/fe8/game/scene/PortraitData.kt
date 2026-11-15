package fe8.game.scene

/** `GetPortraitData` and the address of the `portrait_data` table, shared by the face hosts. */
public object PortraitData {
    /** GBA address of `portrait_data` (the ROM table; its contents are read from the ROM). */
    private const val TABLE = 0x088ACBE0

    /** Bytes of one `struct FaceData`. */
    private const val ENTRY_SIZE = 0x1C

    /**
     * The GBA address of the `struct FaceData` of portrait [fid] (`portrait_data + fid - 1`); the
     * caller reads its fields from the ROM.
     *
     * Ported from src/face.c: GetPortraitData
     */
    public fun of(fid: Int): Int = TABLE + (fid - 1) * ENTRY_SIZE
}
