/**
 * Import and export of `.sav` files: the 32 KiB cartridge SRAM image that other GBA emulators and
 * flash carts write, converted to and from a [gbafe.save.SaveContainer] (the image becomes its
 * `core` section).
 *
 * Importing checks the `struct GlobalSaveInfo` header the way the game does at boot (marker,
 * magics and checksum), rejects a save whose magic belongs to another game, and reports which of
 * the three chapter slots hold a valid save.
 */

package fe8.game.save

import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.CartSram
import gbafe.save.SaveContainer
import gbafe.save.SaveFormat

/** Why [Fe8SavImporter.import] rejected a file. */
public sealed class Fe8SavException(message: String) : RuntimeException(message) {
    /**
     * The file is smaller than the 32 KiB SRAM image (`CART_SRAM_SIZE`).
     *
     * @property size Number of bytes in the file.
     */
    public class TooShort(
        public val size: Int,
    ) : Fe8SavException("Save file too short: $size bytes, need ${CartSram.SIZE}")

    /**
     * `ReadGlobalSaveInfo` rejected the header: wrong marker text, `magic32`, `magic16` or
     * checksum.
     */
    public class CorruptedHeader : Fe8SavException("Save header is corrupted")

    /**
     * The header `magic32` is the one the decomp's `EraseInvalidSaveData` recognises as a save
     * from a different game (`0x00040603`; the decomp asks "which game holds it?", the port
     * assumes FE7). The game erases such a save, the importer refuses it.
     *
     * @property magic The `magic32` found in the header.
     */
    public class UnsupportedMagic(
        public val magic: Int,
    ) : Fe8SavException("Unsupported save magic 0x${magic.toString(16)}")
}

/**
 * What a successful [Fe8SavImporter.import] found.
 *
 * @property slotValid For each chapter save slot 0..2, whether `IsSaveValid` accepts it (its
 *   block descriptor has the right magics and its payload checksum matches).
 * @property container The unmodified 32 KiB SRAM image as the container section `core`.
 */
public class Fe8SavImport(
    public val slotValid: BooleanArray,
    public val container: SaveContainer,
)

/** Imports and exports `.sav` files (raw 32 KiB cartridge SRAM) to and from a [SaveContainer]. */
public object Fe8SavImporter {
    /** The game id stored in the [SaveContainer] of an FE8 save. */
    public const val GAME_ID: String = "fe8"

    /**
     * `magic32` of a save from another game (`0x00040603`, from `EraseInvalidSaveData` in
     * src/bmsave-misc.c). The decomp does not say which game; FE7 is an assumption.
     */
    private const val FOREIGN_MAGIC32: Int = 0x00040603

    /** The three chapter save slots (`SAVE_ID_GAME0..2`) whose validity is reported. */
    private const val GAME_SAVE_SLOT_COUNT: Int = 3

    /**
     * Checks the save image in [bytes] and returns it as the container section `core`. Only the
     * first 32 KiB are used; any trailing padding is dropped.
     *
     * The header is checked after `SramInit` as at boot: the foreign magic is looked for first
     * (as `EraseInvalidSaveData` does), then `ReadGlobalSaveInfo` must accept the header.
     *
     * @throws Fe8SavException.TooShort If [bytes] is under 32 KiB.
     * @throws Fe8SavException.UnsupportedMagic If the header holds the other game's magic.
     * @throws Fe8SavException.CorruptedHeader If marker, magics or checksum do not match.
     */
    public fun import(bytes: ByteArray): Fe8SavImport {
        if (bytes.size < CartSram.SIZE) throw Fe8SavException.TooShort(bytes.size)
        val core = bytes.copyOfRange(0, CartSram.SIZE)
        val magicOffset = L.HEADER_OFFSET + L.HEADER_MAGIC32
        val magic = (core[magicOffset].toInt() and 0xFF) or
            ((core[magicOffset + 1].toInt() and 0xFF) shl 8) or
            ((core[magicOffset + 2].toInt() and 0xFF) shl 16) or
            ((core[magicOffset + 3].toInt() and 0xFF) shl 24)
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
     * Returns a copy of the 32 KiB `core` section of [container], the bytes to write as a `.sav`
     * file.
     *
     * @throws IllegalArgumentException If the container has no `core` section or it is not
     *   32 KiB.
     */
    public fun export(container: SaveContainer): ByteArray {
        val core = requireNotNull(container.core) { "container has no ${SaveFormat.CORE_SECTION}" }
        require(core.size == SaveFormat.CORE_SIZE) { "core section is not ${SaveFormat.CORE_SIZE}" }
        return core.copyOf()
    }
}
