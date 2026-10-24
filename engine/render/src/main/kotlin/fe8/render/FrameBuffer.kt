package fe8.render

/**
 * A CPU-side RGBA8888 frame (one `Int` per pixel, `0xAARRGGBB`).
 *
 * Used by the offscreen/software render path that differential tests compare against mGBA frame dumps,
 * and as the upload source for the GPU path while the full GPU compositor is being built.
 */
public class FrameBuffer(public val width: Int = GBA_WIDTH, public val height: Int = GBA_HEIGHT) {
    /** Pixels, row-major. Exposed for zero-copy upload; do not resize. */
    public val pixels: IntArray = IntArray(width * height)

    /** Fills with [argb]. */
    public fun clear(argb: Int) {
        pixels.fill(argb)
    }

    /** Sets pixel ([x], [y]). No bounds clipping beyond the array check. */
    public operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    /** Gets pixel ([x], [y]). */
    public operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    public companion object {
        /** Native GBA width. */
        public const val GBA_WIDTH: Int = 240

        /** Native GBA height. */
        public const val GBA_HEIGHT: Int = 160

        /**
         * Converts a GBA BGR555 colour to ARGB8888 the same way mGBA's reference dumps do (`c << 3`,
         * zero low bits), so vanilla frames compare exactly.
         */
        public fun bgr555ToArgb(c: Int): Int {
            val r = (c and 0x1F) shl 3
            val g = ((c shr 5) and 0x1F) shl 3
            val b = ((c shr 10) and 0x1F) shl 3
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * The game's view onto the world. Not tied to 240×160 so widescreen/resolution mods can enlarge the
 * logical viewport; vanilla mode is always 240×160 with no offset.
 *
 * @property width logical width in GBA pixels.
 * @property height logical height in GBA pixels.
 */
public class Camera(public var width: Int = FrameBuffer.GBA_WIDTH, public var height: Int = FrameBuffer.GBA_HEIGHT) {
    /** Top-left of the view in world pixels. */
    public var x: Int = 0

    /** Top-left of the view in world pixels. */
    public var y: Int = 0
}

/**
 * Backend-neutral renderer. Implementations: the software renderer (JVM, tests, frame comparison) and
 * the OpenGL ES 3.0 renderer in `:app`. Both consume the same per-frame render state (milestone 3).
 */
public interface Renderer {
    /** Renders the current frame. Must not allocate. */
    public fun render(camera: Camera)
}
