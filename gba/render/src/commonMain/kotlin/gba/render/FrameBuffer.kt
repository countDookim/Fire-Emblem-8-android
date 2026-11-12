package gba.render

/**
 * A CPU-side frame: one `Int` per pixel in `0xAARRGGBB` order (the "ARGB8888" of [bgr555ToArgb]).
 *
 * Used by the offscreen/software render path that differential tests compare against mGBA frame dumps,
 * and as the upload source for the GPU path while the full GPU compositor is being built.
 */
public class FrameBuffer(
    /** Width in pixels (240 = native GBA). */
    public val width: Int = GBA_WIDTH,
    /** Height in pixels (160 = native GBA). */
    public val height: Int = GBA_HEIGHT,
) {
    /** Pixels, row-major (index `y * width + x`). Exposed for zero-copy upload; fixed size. */
    public val pixels: IntArray = IntArray(width * height)

    /** Fills with [argb]. */
    public fun clear(argb: Int) {
        pixels.fill(argb)
    }

    /** Sets pixel ([x], [y]) to [argb]. No clipping: only the array bounds are checked. */
    public operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    /** Gets pixel ([x], [y]). */
    public operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Native GBA screen size and the BGR555 conversion. */
    public companion object {
        /** Native GBA width. */
        public const val GBA_WIDTH: Int = 240

        /** Native GBA height. */
        public const val GBA_HEIGHT: Int = 160

        /**
         * Converts a GBA BGR555 colour (red in bits 0..4, green 5..9, blue 10..14) to opaque
         * ARGB8888 the way the reference frame dumps compare equal: each 5-bit channel is shifted
         * left by 3, the low 3 bits stay zero (so 31 becomes 0xF8, not 0xFF).
         */
        public fun bgr555ToArgb(color: Int): Int {
            val red = (color and 0x1F) shl 3
            val green = ((color shr 5) and 0x1F) shl 3
            val blue = ((color shr 10) and 0x1F) shl 3
            return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
}

/**
 * The view onto the world that a [Renderer] is asked to show. Not tied to 240×160 so
 * widescreen/resolution mods can enlarge the logical viewport; vanilla mode is always 240×160
 * with no offset, and [SoftwareRenderer] ignores the camera.
 *
 * @property width logical width in GBA pixels.
 * @property height logical height in GBA pixels.
 */
public class Camera(public var width: Int = FrameBuffer.GBA_WIDTH, public var height: Int = FrameBuffer.GBA_HEIGHT) {
    /** Left edge of the view in world pixels. */
    public var x: Int = 0

    /** Top edge of the view in world pixels. */
    public var y: Int = 0
}

/**
 * Backend-neutral renderer. [SoftwareRenderer] (JVM, tests, frame comparison) is the
 * implementation in this module; the app's GPU backend consumes the same per-frame [RenderState]
 * through [GpuFrame].
 */
public interface Renderer {
    /** Renders the current frame for the view [camera]. Must not allocate. */
    public fun render(camera: Camera)
}
