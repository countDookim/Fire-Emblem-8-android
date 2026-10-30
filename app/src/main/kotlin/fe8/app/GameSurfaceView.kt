package fe8.app

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.KeyEvent
import android.view.MotionEvent
import gbafe.core.time.GameLoop
import gbafe.input.ActionSource
import fe8.game.Fe8Game
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 game view. The game renders at native 240×160 into a texture, which a quad
 * scales to the largest aspect-correct size with nearest filtering (pixel-exact look; a
 * smoothing/CRT shader can be added behind a feature flag later).
 *
 * Threading: the [GameLoop] runs on the GL thread, driven by `onDrawFrame` (display rate), with logic
 * fixed at ~59.73 Hz by [gbafe.core.time.FixedStepClock]. Nothing here allocates per frame: the upload
 * buffer is a pre-allocated direct [IntBuffer].
 */
public class GameSurfaceView(
    context: Context,
    game: Fe8Game,
    touch: ActionSource? = null,
) : GLSurfaceView(context) {
    private val keys = HardwareKeySource()

    init {
        game.actionSource = if (touch == null) keys else ActionSource { keys.poll() or touch.poll() }
        setEGLContextClientVersion(3)
        setRenderer(GameRenderer(game))
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keys.onKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keys.onKey(event) || super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        keys.onMotion(event) || super.onGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) keys.clear()
    }
}

private class GameRenderer(private val game: Fe8Game) : GLSurfaceView.Renderer {
    private val loop = GameLoop(game)
    private val frame = game.frame
    private val upload: IntBuffer = ByteBuffer.allocateDirect(frame.pixels.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
    private var program = 0
    private var texture = 0
    private var uScale = 0
    private var viewW = 1
    private var viewH = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        loop.frame(System.nanoTime())
        upload.position(0)
        upload.put(frame.pixels)
        upload.position(0)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        // Aspect-correct fit: scale the [-1,1] quad so the 3:2 image fits the view.
        val gameAspect = frame.width.toFloat() / frame.height
        val viewAspect = viewW.toFloat() / viewH
        if (viewAspect > gameAspect) GLES30.glUniform2f(uScale, gameAspect / viewAspect, 1f)
        else GLES30.glUniform2f(uScale, 1f, viewAspect / gameAspect)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        // ARGB ints are B,G,R,A bytes in little-endian memory; the shader swizzles back.
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, upload)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

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
        private const val VERTEX = """#version 300 es
            uniform vec2 uScale;
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)); // unit quad corner
                vUv = vec2(p.x, 1.0 - p.y);
                gl_Position = vec4((p * 2.0 - 1.0) * uScale, 0.0, 1.0);
            }"""

        private const val FRAGMENT = """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            in vec2 vUv;
            out vec4 color;
            void main() { color = texture(uTex, vUv).bgra; }"""
    }
}
