package fe8.game.save

import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.CartSram
import gbafe.save.SaveContainer
import gbafe.save.SaveFormat

/** Typed failure of [Fe8SavImporter.import]. */
public sealed class Fe8SavException(message: String) : RuntimeException(message) {
    /** The input has fewer than 0x8000 bytes. */
    public class TooShort(
        /** Number of bytes supplied. */
        public val size: Int,
    ) : Fe8SavException("Save file too short: $size bytes, need ${CartSram.SIZE}")

    /** The global save info header failed validation (marker, magics or checksum). */
    public class CorruptedHeader : Fe8SavException("Save header is corrupted")

    /** The file holds the other game's save (magic32 0x00040603), which is not supported. */
    public class UnsupportedMagic(
        /** The magic32 value found. */
        public val magic: Int,
    ) : Fe8SavException("Unsupported save magic 0x${magic.toString(16)}")
}

/** Result of [Fe8SavImporter.import]. */
public class Fe8SavImport(
    /** Validity of game save slots 0..2 (`IsSaveValid`). */
    public val slotValid: BooleanArray,
    /** Container holding the 32 KiB image as section `core`. */
    public val container: SaveContainer,
)

/**
 * Imports and exports `.sav` files (the raw cartridge SRAM) to and from a [SaveContainer].
 * Not a port of a C function.
 */
public object Fe8SavImporter {
    /** Game id stored in containers made by [import]. */
    public const val GAME_ID: String = "fe8"

    private const val FOREIGN_MAGIC32 = 0x00040603

    /**
     * Validates [bytes] (at least 0x8000; only the first 32 KiB are used, so padded files work)
     * and wraps them as container section `core`.
     *
     * @throws Fe8SavException.TooShort if fewer than 0x8000 bytes
     * @throws Fe8SavException.UnsupportedMagic for the old magic 0x00040603
     * @throws Fe8SavException.CorruptedHeader if the global save info is invalid
     */
    public fun import(bytes: ByteArray): Fe8SavImport {
        if (bytes.size < CartSram.SIZE) throw Fe8SavException.TooShort(bytes.size)
        val core = bytes.copyOfRange(0, CartSram.SIZE)
        val at = L.HEADER_OFFSET + L.HEADER_MAGIC32
        val magic = (core[at].toInt() and 0xFF) or
            ((core[at + 1].toInt() and 0xFF) shl 8) or
            ((core[at + 2].toInt() and 0xFF) shl 16) or
            ((core[at + 3].toInt() and 0xFF) shl 24)
        if (magic == FOREIGN_MAGIC32) throw Fe8SavException.UnsupportedMagic(magic)
        val sram = CartSram()
        sram.copyIn(core)
        val lib = BmSaveLib(sram)
        lib.sramInit()
        if (!lib.readGlobalSaveInfo()) throw Fe8SavException.CorruptedHeader()
        val valid = BooleanArray(3) { lib.isSaveValid(it) }
        return Fe8SavImport(valid, SaveContainer.fromSram(GAME_ID, core))
    }

    /** Returns the 32 KiB `core` section of [container] as a `.sav` image (a copy). */
    public fun export(container: SaveContainer): ByteArray {
        val core = requireNotNull(container.core) { "container has no ${SaveFormat.CORE_SECTION}" }
        require(core.size == SaveFormat.CORE_SIZE) { "core section is not ${SaveFormat.CORE_SIZE}" }
        return core.copyOf()
    }
}
