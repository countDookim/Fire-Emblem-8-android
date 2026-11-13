package gba.core.sram

/**
 * Persistent backing store for the cartridge SRAM image (a `.sav` file on disk). Implementations
 * hold exactly one [CartSram.SIZE]-byte image.
 */
public interface SaveStorage {
    /**
     * Fills [dest] (exactly [CartSram.SIZE] bytes) with the saved image and returns true, or
     * returns false and leaves [dest] untouched if nothing usable is saved yet.
     */
    public fun load(dest: ByteArray): Boolean

    /**
     * Persists [src] (exactly [CartSram.SIZE] bytes) atomically: after a crash the previous image
     * or the new one is stored, never a mix.
     */
    public fun store(src: ByteArray)
}

/** A [SaveStorage] that keeps a copy of the image in memory, for tests and headless runs. */
public class InMemorySaveStorage : SaveStorage {
    /** Copy of the last stored image, or null until the first [store]. */
    private var image: ByteArray? = null

    override fun load(dest: ByteArray): Boolean {
        require(dest.size == CartSram.SIZE) { "dest must be ${CartSram.SIZE} bytes" }
        val saved = image ?: return false
        saved.copyInto(dest)
        return true
    }

    override fun store(src: ByteArray) {
        require(src.size == CartSram.SIZE) { "src must be ${CartSram.SIZE} bytes" }
        image = src.copyOf()
    }
}

/**
 * Restores this SRAM from [storage]. Returns true and leaves SRAM clean if an image was loaded;
 * returns false and leaves SRAM blank if [storage] holds nothing.
 */
public fun CartSram.loadFrom(storage: SaveStorage): Boolean {
    val buffer = ByteArray(CartSram.SIZE)
    if (!storage.load(buffer)) return false
    copyIn(buffer)
    clearDirty()
    return true
}

/**
 * Writes this SRAM to [storage] if it is [CartSram.dirty] and returns true; returns false if there
 * was nothing to write. SRAM stays dirty if [SaveStorage.store] throws.
 */
public fun CartSram.flushTo(storage: SaveStorage): Boolean {
    if (!dirty) return false
    val buffer = ByteArray(CartSram.SIZE)
    copyOut(buffer)
    storage.store(buffer)
    clearDirty()
    return true
}
