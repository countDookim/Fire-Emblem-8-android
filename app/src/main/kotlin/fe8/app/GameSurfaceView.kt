/**
 * The game picture on screen: a GL view that runs the game loop, draws the 240×160 GBA frame
 * scaled to fit the screen (3:2, black bars), and routes keyboard and gamepad input to the game.
 */

package fe8.app

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Trace
import android.view.KeyEvent
import android.view.MotionEvent
import fe8.game.Fe8Game
import gba.core.time.GameLoop
import gba.core.time.Simulation
import gba.input.ActionSource
import io.github.countdookim.fe8.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The view the player sees while playing, and the owner of game input.
 *
 * The game draws a native 240×160 frame. Either the CPU-drawn frame is uploaded as a texture, or
 * [GpuBackend] composites it on the GPU; both are scaled to the largest 3:2 area that fits the
 * view, with nearest-neighbour sampling so pixels stay sharp.
 *
 * Threading: [GameLoop] runs on the GL render thread, once per [GLSurfaceView.Renderer.onDrawFrame]
 * (continuous mode, so at the screen's refresh rate; Android behaviour, not checked here). The
 * loop runs as many fixed logic ticks as elapsed time needs, at
 * [gba.core.time.FixedStepClock.TICK_HZ] (280896 cycles per GBA frame at 16777216 Hz, about
 * 59.7275 Hz). Key events arrive on the UI thread and are read by the game thread through
 * [HardwareKeySource.poll]. The per-frame pixel upload reuses one direct buffer.
 *
 * @param context Android context.
 * @param game The game to run; its input source is set here to keys, OR-ed with [touch] if given.
 * @param touch The on-screen buttons' state, or null when no overlay is shown.
 * @param gpu Draw through [GpuBackend]. Takes effect only when the game has a boot sequence
 *   (`Fe8Game.boot`); otherwise the software frame is uploaded.
 */
public class GameSurfaceView(
    context: Context,
    game: Fe8Game,
    touch: ActionSource? = null,
    /** Draw with [GpuBackend] (shaders) instead of uploading the software frame; boot ROM only. */
    gpu: Boolean = false,
) : GLSurfaceView(context) {
    private val keys = HardwareKeySource()

    // Keys and touch are OR-ed: either may hold a button. The view takes focus so the system
    // delivers key events (Android behaviour, not checked here).
    init {
        game.actionSource = if (touch == null) keys else ActionSource { keys.poll() or touch.poll() }
        setEGLContextClientVersion(OPENGL_ES_VERSION)
        setRenderer(GameRenderer(game, gpu))
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
    }

    /** Key pressed: GBA-mapped keys go to [keys] and are consumed; others get default handling. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keys.onKey(event) || super.onKeyDown(keyCode, event)

    /** Key released: same mapping as [onKeyDown]. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keys.onKey(event) || super.onKeyUp(keyCode, event)

    /** Gamepad hat or stick moved: becomes D-pad directions via [HardwareKeySource.onMotion]. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        keys.onMotion(event) || super.onGenericMotionEvent(event)

    /** Releases every held key on focus loss, so no button sticks if the key-up is missed. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) keys.clear()
    }

    private companion object {
        /** Major OpenGL ES version requested for the context (the code calls `GLES30`). */
        private const val OPENGL_ES_VERSION = 3
    }
}

/**
 * The per-frame work on the GL thread: run due game ticks, then draw the game frame to the view.
 *
 * @property game The game being run and drawn.
 * @param gpu Whether [GpuBackend] drawing was requested; honoured only if [game] has a boot
 *   sequence.
 */
private class GameRenderer(private val game: Fe8Game, gpu: Boolean) : GLSurfaceView.Renderer {
    // Wraps game.tick/present in systrace sections in debug builds only (profiling aid).
    private val loop = GameLoop(object : Simulation {
        override fun tick() {
            if (BuildConfig.DEBUG) Trace.beginSection("FE8 tick")
            try {
                game.tick()
            } finally {
                if (BuildConfig.DEBUG) Trace.endSection()
            }
        }

        override fun present(alpha: Float) {
            if (BuildConfig.DEBUG) Trace.beginSection("FE8 present")
            try {
                game.present(alpha)
            } finally {
                if (BuildConfig.DEBUG) Trace.endSection()
            }
        }
    })
    /** Draw callbacks so far; only feeds the debug trace counter. */
    private var callbacks = 0L

    /** The GPU compositor, or null for the software-frame path. */
    private val gpuBackend = game.boot?.takeIf { gpu }?.let { GpuBackend(it.gpuFrame, it.tileSheets) }
    private val frame = game.frame

    /** Direct buffer the software frame is copied into before the texture upload. */
    private val upload: IntBuffer = ByteBuffer.allocateDirect(frame.pixels.size * BYTES_PER_PIXEL)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
    /** GL handles for the software-frame path: shader program, frame texture, `uScale` uniform. */
    private var program = 0
    private var texture = 0
    private var uScale = 0

    /** Surface size in pixels, set by [onSurfaceChanged]; used for the aspect-fit. */
    private var viewW = 1
    private var viewH = 1

    /**
     * Creates the GL objects (also the GPU backend's) once the context exists. GLSurfaceView calls
     * this again if the context is recreated, e.g. after a pause (Android behaviour, not checked
     * here), which is why nothing is created earlier. Also tells the game whether to pack its frame
     * for the GPU (`gpuPresent`) or draw it in software.
     */
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        game.gpuPresent = gpuBackend != null
        gpuBackend?.init(::link, VERTEX)
        program = link(VERTEX, FRAGMENT)
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, frame.width, frame.height)
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0]) // quad corners are generated from gl_VertexID
    }

    /** Surface resized (first show, rotation): remember its size and set the GL viewport to it. */
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    /**
     * One display refresh: run the game ticks now due (and present once), then draw the frame.
     * In debug builds each step is a systrace section and ticks per callback are counted.
     * `Trace.setCounter` needs API 29 (Android behaviour, not checked here).
     */
    override fun onDrawFrame(gl: GL10?) {
        if (BuildConfig.DEBUG) Trace.beginSection("FE8 draw frame")
        try {
            val previousTicks = loop.ticks
            if (BuildConfig.DEBUG) Trace.beginSection("FE8 game loop")
            try {
                loop.frame(System.nanoTime())
            } finally {
                if (BuildConfig.DEBUG) Trace.endSection()
            }
            if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 29) {
                Trace.setCounter("FE8 ticks per callback", loop.ticks - previousTicks)
                Trace.setCounter("FE8 game ticks", loop.ticks)
                Trace.setCounter("FE8 draw callbacks", ++callbacks)
            }
            drawFrame()
        } finally {
            if (BuildConfig.DEBUG) Trace.endSection()
        }
    }

    /**
     * Draws the current game frame fitted to the surface: black bars on the spare sides, via the
     * GPU backend if present, else by uploading the software frame to the texture and drawing a
     * quad.
     */
    private fun drawFrame() {
        // Aspect-correct fit: scale the [-1,1] quad so the 3:2 image fits the view; a view wider
        // than 3:2 shrinks x (side bars), a taller one shrinks y (top and bottom bars).
        val gameAspect = frame.width.toFloat() / frame.height
        val viewAspect = viewW.toFloat() / viewH
        val sx = if (viewAspect > gameAspect) gameAspect / viewAspect else 1f
        val sy = if (viewAspect > gameAspect) 1f else viewAspect / gameAspect
        val gpu = gpuBackend
        if (gpu != null) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            gpu.draw(viewW, viewH, sx, sy)
            return
        }
        upload.position(0)
        upload.put(frame.pixels)
        upload.position(0)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, sx, sy)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        // ARGB ints are B,G,R,A bytes in little-endian memory; the shader swizzles back.
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
    }

    /**
     * Compiles the two shaders and links them into a program, returning its handle. A shader that
     * fails to compile throws with the GL log. NOTE: the link result is not checked, so a link
     * failure would show up only as a blank picture.
     */
    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    private companion object {
        /** Bytes per pixel: one `Int` of `0xAARRGGBB` per pixel in the frame. */
        private const val BYTES_PER_PIXEL = 4

        /** Vertices of the screen quad, drawn as a triangle strip. */
        private const val QUAD_VERTEX_COUNT = 4

        /**
         * Vertex shader: no vertex buffer; the quad's four corners come from `gl_VertexID` 0..3,
         * scaled by `uScale` (the aspect-fit) and with v flipped so frame row 0 is at the top.
         */
        private const val VERTEX = """#version 300 es
            uniform vec2 uScale;
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)); // unit quad corner
                vUv = vec2(p.x, 1.0 - p.y);
                gl_Position = vec4((p * 2.0 - 1.0) * uScale, 0.0, 1.0);
            }"""

        /**
         * Fragment shader: samples the frame texture. The ARGB ints are uploaded as RGBA bytes, so
         * the red and blue channels arrive swapped and `.bgra` puts them right.
         */
        private const val FRAGMENT = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            in vec2 vUv;
            out vec4 color;
            void main() { color = texture(uTex, vUv).bgra; }"""
    }
}
