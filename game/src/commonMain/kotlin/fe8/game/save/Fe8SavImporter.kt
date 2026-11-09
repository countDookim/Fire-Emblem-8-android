/**
 * Cartridge SRAM `.sav` file import and export utility.
 *
 * Facilitates converting raw 32 KiB battery-backed SRAM files (`.sav`) into modular [gbafe.save.SaveContainer]
 * representations (with a standard `core` section) and vice-versa, performing structural validation
 * on the `GlobalSaveInfo` header, rejecting unsupported foreign formats (such as FE7), and determining
 * slot-by-slot save validity.
 */

package fe8.game.save

import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.CartSram
import gbafe.save.SaveContainer
import gbafe.save.SaveFormat

/**
 * Sealed base class representing errors encountered during [Fe8SavImporter.import].
 *
 * @param message Descriptive error message.
 */
public sealed class Fe8SavException(message: String) : RuntimeException(message) {
    /**
     * Input byte array is smaller than the required 32 KiB cartridge SRAM size.
     *
     * @property size Number of bytes actually supplied.
     */
    public class TooShort(
        public val size: Int,
    ) : Fe8SavException("Save file too short: $size bytes, need ${CartSram.SIZE}")

    /** The global save info header failed validation (invalid marker, magic numbers, or checksum mismatch). */
    public class CorruptedHeader : Fe8SavException("Save header is corrupted")

    /**
     * The file contains a save from another game (e.g. FE7 magic `0x00040603`), which cannot be loaded.
     *
     * @property magic The 32-bit header magic value discovered.
     */
    public class UnsupportedMagic(
        public val magic: Int,
    ) : Fe8SavException("Unsupported save magic 0x${magic.toString(16)}")
}

/**
 * Result returned by a successful [Fe8SavImporter.import] call.
 *
 * @property slotValid Array indicating validity of chapter save slots 0, 1, and 2 (`IsSaveValid`).
 * @property container Portable save container encapsulating the 32 KiB raw SRAM image under section `core`.
 */
public class Fe8SavImport(
    public val slotValid: BooleanArray,
    public val container: SaveContainer,
)

/**
 * Imports and exports `.sav` files (the raw 32 KiB cartridge SRAM) to and from a [SaveContainer].
 *
 * Handles little-endian byte extraction, validation of global save headers, and isolation of core SRAM.
 */
public object Fe8SavImporter {
    /** Identifier used for FE8 save container headers. */
    public const val GAME_ID: String = "fe8"

    /** Magic number identifying FE7 (Blazing Blade) save images (`0x00040603`). */
    private const val FOREIGN_MAGIC32: Int = 0x00040603

    /** Number of game save slots checked during import (slots 0..2). */
    private const val GAME_SAVE_SLOT_COUNT: Int = 3

    /**
     * Validates [bytes] (at least 32 KiB; trailing padding is ignored) and packages the SRAM as container section `core`.
     *
     * @param bytes Raw byte array of the save file.
     * @return [Fe8SavImport] containing parsed validity flags and the packaged save container.
     * @throws Fe8SavException.TooShort If fewer than 32 KiB are provided.
     * @throws Fe8SavException.UnsupportedMagic If an incompatible game magic (e.g. FE7) is found.
     * @throws Fe8SavException.CorruptedHeader If the header fails marker, magic, or checksum verification.
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
        val valid = BooleanArray(GAME_SAVE_SLOT_COUNT) { lib.isSaveValid(it) }
        return Fe8SavImport(valid, SaveContainer.fromSram(GAME_ID, core))
    }

    /**
     * Extracts and copies the 32 KiB `core` SRAM section from [container] suitable for saving as a `.sav` file.
     *
     * @param container The source container holding game save data.
     * @return Cloned 32 KiB byte array of the cartridge SRAM.
     * @throws IllegalArgumentException If the container does not contain a valid `core` section.
     */
    public fun export(container: SaveContainer): ByteArray {
        val core = requireNotNull(container.core) { "container has no ${SaveFormat.CORE_SECTION}" }
        require(core.size == SaveFormat.CORE_SIZE) { "core section is not ${SaveFormat.CORE_SIZE}" }
        return core.copyOf()
    }
}
