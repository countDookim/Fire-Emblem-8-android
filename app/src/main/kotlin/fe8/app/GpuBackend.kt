package fe8.app

import android.opengl.GLES30
import gbafe.render.FrameBuffer
import gbafe.render.GpuFrame
import gbafe.render.RenderState
import gbafe.render.TileLayer
import gbafe.render.TileSheets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.ShortBuffer

/**
 * GPU backend v1 (M3-07): composites a packed [GpuFrame] per pixel in a fragment shader that is a
 * line-by-line copy of [gbafe.render.PackedCompositor] (keep them in sync; the JVM tests check the
 * Kotlin spec against [gbafe.render.SoftwareRenderer]). Textures: per-line palettes `R16UI` 512×rows,
 * line parameters `RGBA32I` 3×160, the CPU-rasterised OBJ plane `R32UI` 240×160, the 4 BG maps as an
 * `R16UI` 128×128×4 array and the 4 BG tile sheets as raw bytes in an `R8UI` 1024×64×4 array. The shader
 * maps and sheets are re-uploaded only when their contents changed. The shader
 * runs once per GBA pixel into a 240×160 texture, which [BLIT] scales to the view. All upload buffers are
 * pre-allocated: [draw] allocates nothing. Colour effects use hardware arithmetic.
 * Call everything on the GL thread after a context exists.
 */
internal class GpuBackend(private val frame: GpuFrame, private val sheets: TileSheets) {
    private val state: RenderState = frame.state
    private val pal: ShortBuffer = direct(GpuFrame.PALETTE_WIDTH * RenderState.LINES * 2).asShortBuffer()
    private val lines: IntBuffer = direct(frame.lines.size * 4).asIntBuffer()
    private val obj: IntBuffer = direct(frame.obj.size * 4).asIntBuffer()
    private val map: ShortBuffer = direct(MAP_ENTRIES * 2).asShortBuffer()
    private val sheet: ByteBuffer = direct(SHEET_BYTES)
    // Last uploaded map/sheet contents per BG: re-upload only what changed.
    private val lastMap = Array(RenderState.LAYERS) { ShortArray(MAP_ENTRIES) }
    private val lastSheet = Array(RenderState.LAYERS) { ByteArray(SHEET_BYTES) }
    private val lastSheetSize = IntArray(RenderState.LAYERS) { -1 }
    private val mapValid = BooleanArray(RenderState.LAYERS)
    private val layerU = IntArray(4 * RenderState.LAYERS)
    private val affU = IntArray(2 * RenderState.LAYERS)
    private val sheetSizeU = IntArray(RenderState.LAYERS)
    private val tex = IntArray(6)
    private val fbo = IntArray(1)
    private var program = 0
    private var blit = 0
    private var uBlitScale = 0
    private var uScale = 0
    private var uLayer = 0
    private var uAff = 0
    private var uSheetSize = 0
    private var uWinOn = 0
    private var uWinMask = 0
    private var uMosaicH = 0
    private var uPalRows = 0

    /** Creates the program and textures (again after a context loss). */
    fun init(link: (String, String) -> Int, vertex: String) {
        program = link(vertex, FRAGMENT)
        GLES30.glUseProgram(program)
        uScale = GLES30.glGetUniformLocation(program, "uScale")
        uLayer = GLES30.glGetUniformLocation(program, "uLayer")
        uAff = GLES30.glGetUniformLocation(program, "uAff")
        uSheetSize = GLES30.glGetUniformLocation(program, "uSheetSize")
        uWinOn = GLES30.glGetUniformLocation(program, "uWinOn")
        uWinMask = GLES30.glGetUniformLocation(program, "uWinMask")
        uMosaicH = GLES30.glGetUniformLocation(program, "uMosaicH")
        uPalRows = GLES30.glGetUniformLocation(program, "uPalRows")
        blit = link(vertex, BLIT)
        uBlitScale = GLES30.glGetUniformLocation(blit, "uScale")
        GLES30.glUseProgram(blit)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blit, "uTex"), T_OUT)
        GLES30.glUseProgram(program)
        GLES30.glGenTextures(tex.size, tex, 0)
        lastSheetSize.fill(-1)
        mapValid.fill(false)
        storage2d(T_PAL, GLES30.GL_R16UI, GpuFrame.PALETTE_WIDTH, RenderState.LINES)
        storage2d(T_LINES, GLES30.GL_RGBA32I, GpuFrame.LINE_INTS / 4, RenderState.LINES)
        storage2d(T_OBJ, GLES30.GL_R32UI, FrameBuffer.GBA_WIDTH, FrameBuffer.GBA_HEIGHT)
        storage3d(T_MAP, GLES30.GL_R16UI, TileLayer.MAX_TILES, TileLayer.MAX_TILES)
        storage3d(T_SHEET, GLES30.GL_R8UI, SHEET_WIDTH, SHEET_BYTES / SHEET_WIDTH)
        storage2d(T_OUT, GLES30.GL_RGBA8, FrameBuffer.GBA_WIDTH, FrameBuffer.GBA_HEIGHT)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[T_OUT], 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val names = arrayOf("uPal", "uLines", "uObj", "uMap", "uSheet")
        for (i in names.indices) GLES30.glUniform1i(GLES30.glGetUniformLocation(program, names[i]), i)
    }

    /**
     * Uploads the packed frame, composites it at 240×160 into an offscreen texture, then scales that onto
     * the current framebuffer of [viewW]×[viewH]; [sx]/[sy] scale the quad (aspect fit).
     */
    fun draw(viewW: Int, viewH: Int, sx: Float, sy: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScale, 1f, 1f)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        pal.position(0)
        pal.put(frame.palette, 0, GpuFrame.PALETTE_WIDTH * frame.paletteRows).position(0)
        upload2d(T_PAL, GpuFrame.PALETTE_WIDTH, frame.paletteRows, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, pal)
        lines.position(0)
        lines.put(frame.lines).position(0)
        upload2d(T_LINES, GpuFrame.LINE_INTS / 4, RenderState.LINES, GLES30.GL_RGBA_INTEGER, GLES30.GL_INT, lines)
        obj.position(0)
        obj.put(frame.obj).position(0)
        upload2d(T_OBJ, FrameBuffer.GBA_WIDTH, FrameBuffer.GBA_HEIGHT, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_INT, obj)
        val fx = state.effects
        for (bg in 0 until RenderState.LAYERS) {
            val l = state.layers[bg]
            val s = sheets[l.sheet]
            var flags = 0
            if (l.enabled) flags = flags or 1
            if (l.affine) flags = flags or 2
            if (l.wrap) flags = flags or 4
            if (l.mosaic) flags = flags or 8
            if (l.bpp8) flags = flags or 16
            layerU[4 * bg] = flags
            layerU[4 * bg + 1] = l.priority
            layerU[4 * bg + 2] = l.widthTiles
            layerU[4 * bg + 3] = l.heightTiles
            affU[2 * bg] = l.pa
            affU[2 * bg + 1] = l.pc
            if (!l.enabled || s == null) {
                sheetSizeU[bg] = 0
                continue
            }
            val n = minOf(s.size, SHEET_BYTES)
            sheetSizeU[bg] = n
            uploadSheet(bg, s, n)
            uploadMap(bg, l.entries)
        }
        GLES30.glUniform4iv(uLayer, RenderState.LAYERS, layerU, 0)
        GLES30.glUniform2iv(uAff, RenderState.LAYERS, affU, 0)
        GLES30.glUniform1iv(uSheetSize, RenderState.LAYERS, sheetSizeU, 0)
        GLES30.glUniform1i(uWinOn, (if (fx.win0) 1 else 0) or (if (fx.win1) 2 else 0) or (if (fx.objWindow) 4 else 0))
        GLES30.glUniform4i(uWinMask, fx.winInside[0], fx.winInside[1], fx.winInside[2], fx.winOutside)
        GLES30.glUniform1i(uMosaicH, maxOf(1, fx.mosaic[0]))
        GLES30.glUniform1i(uPalRows, frame.paletteRows)
        for (i in 0 until T_OUT) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(if (i >= T_MAP) GLES30.GL_TEXTURE_2D_ARRAY else GLES30.GL_TEXTURE_2D, tex[i])
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, FrameBuffer.GBA_WIDTH, FrameBuffer.GBA_HEIGHT)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glUseProgram(blit)
        GLES30.glUniform2f(uBlitScale, sx, sy)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + T_OUT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[T_OUT])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun uploadSheet(bg: Int, s: ByteArray, n: Int) {
        val last = lastSheet[bg]
        if (lastSheetSize[bg] == n && s.contentEqualsRange(last, n)) return
        s.copyInto(last, 0, 0, n)
        lastSheetSize[bg] = n
        sheet.position(0)
        sheet.put(s, 0, n).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[T_SHEET])
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, bg, SHEET_WIDTH, (n + SHEET_WIDTH - 1) / SHEET_WIDTH,
            1, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, sheet)
    }

    private fun uploadMap(bg: Int, entries: ShortArray) {
        val last = lastMap[bg]
        if (mapValid[bg] && entries.contentEquals(last)) return
        entries.copyInto(last)
        mapValid[bg] = true
        map.position(0)
        map.put(entries).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[T_MAP])
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, bg, TileLayer.MAX_TILES, TileLayer.MAX_TILES, 1,
            GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, map)
    }

    private fun ByteArray.contentEqualsRange(other: ByteArray, n: Int): Boolean {
        for (i in 0 until n) if (this[i] != other[i]) return false
        return true
    }

    private fun storage2d(t: Int, format: Int, w: Int, h: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[t])
        nearest(GLES30.GL_TEXTURE_2D)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
    }

    private fun storage3d(t: Int, format: Int, w: Int, h: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[t])
        nearest(GLES30.GL_TEXTURE_2D_ARRAY)
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, format, w, h, RenderState.LAYERS)
    }

    private fun nearest(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    private fun upload2d(t: Int, w: Int, h: Int, format: Int, type: Int, data: java.nio.Buffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[t])
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, data)
    }

    private companion object {
        const val T_PAL = 0
        const val T_LINES = 1
        const val T_OBJ = 2
        const val T_MAP = 3
        const val T_SHEET = 4
        const val T_OUT = 5
        const val MAP_ENTRIES = TileLayer.MAX_TILES * TileLayer.MAX_TILES
        const val SHEET_WIDTH = 1024
        const val SHEET_BYTES = 0x10000

        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        /** Nearest-filtered scale of the composited 240×160 texture (rows already top-first). */
        const val BLIT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
out vec4 color;
void main() { color = texture(uTex, vec2(vUv.x, 1.0 - vUv.y)); }
"""

        /** Copy of PackedCompositor.pixel; see that class for the rules. */
        const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp isampler2D;
precision highp usampler2DArray;
uniform usampler2D uPal;
uniform isampler2D uLines;
uniform usampler2D uObj;
uniform usampler2DArray uMap;
uniform usampler2DArray uSheet;
uniform ivec4 uLayer[4];
uniform ivec2 uAff[4];
uniform int uSheetSize[4];
uniform int uWinOn;
uniform ivec4 uWinMask;
uniform int uMosaicH;
uniform int uPalRows;
in vec2 vUv;
out vec4 color;

int sheetByte(int l, int o) {
    if (o < 0 || o >= uSheetSize[l]) return 0;
    return int(texelFetch(uSheet, ivec3(o & 1023, o >> 10, l), 0).r);
}
int tilePixel(int l, int tile, int px, int py, bool bpp8) {
    if (bpp8) return sheetByte(l, tile * 64 + py * 8 + px);
    return (sheetByte(l, tile * 32 + py * 4 + (px >> 1)) >> ((px & 1) << 2)) & 15;
}
int entry(int l, int x, int y) { return int(texelFetch(uMap, ivec3(x, y, l), 0).r); }
bool inside(int v, int lo, int hi) { return lo <= hi ? (v >= lo && v < hi) : (v >= lo || v < hi); }
int affineIndex(int l, int fx, int fy) {
    int size = uLayer[l].z * 8;
    int tx = fx >> 8;
    int ty = fy >> 8;
    if ((uLayer[l].x & 4) != 0) {
        tx &= size - 1;
        ty &= size - 1;
    } else if (tx < 0 || ty < 0 || tx >= size || ty >= size) {
        return -1;
    }
    int idx = tilePixel(l, entry(l, tx >> 3, ty >> 3) & 255, tx & 7, ty & 7, true);
    return idx == 0 ? -1 : idx;
}
int textIndex(int l, int wx, int wy) {
    int mx = wx & (uLayer[l].z * 8 - 1);
    int my = wy & (uLayer[l].w * 8 - 1);
    int e = entry(l, mx >> 3, my >> 3);
    int px = mx & 7;
    int py = my & 7;
    if ((e & 0x400) != 0) px = 7 - px;
    if ((e & 0x800) != 0) py = 7 - py;
    bool bpp8 = (uLayer[l].x & 16) != 0;
    int idx = tilePixel(l, e & 0x3FF, px, py, bpp8);
    if (idx == 0) return -1;
    return bpp8 ? idx : (((e >> 12) << 4) | idx);
}
int pal(int row, int i) { return int(texelFetch(uPal, ivec2(i, row), 0).r) & 0x7FFF; }
int alphaCh(int a, int b, int s, int eva, int evb) {
    return min(31, (((a >> s) & 31) * eva + ((b >> s) & 31) * evb) >> 4) << s;
}
int blendAlpha(int a, int b, int eva, int evb) {
    return alphaCh(a, b, 0, eva, evb) | alphaCh(a, b, 5, eva, evb) | alphaCh(a, b, 10, eva, evb);
}
int brightCh(int c, int s, int evy) { int v = (c >> s) & 31; return (v + (((31 - v) * evy) >> 4)) << s; }
int darkCh(int c, int s, int evy) { int v = (c >> s) & 31; return (v - ((v * evy) >> 4)) << s; }

int composite(int x, int y) {
    int palRow = min(y, uPalRows - 1);
    int o = int(texelFetch(uObj, ivec2(x, y), 0).r);
    ivec4 l0 = texelFetch(uLines, ivec2(0, y), 0);
    ivec4 l1 = texelFetch(uLines, ivec2(1, y), 0);
    ivec4 l2 = texelFetch(uLines, ivec2(2, y), 0);
    ivec2 origin[4] = ivec2[4](l0.xy, l0.zw, l1.xy, l1.zw);
    int wx = l2.x;
    int wf = l2.y;
    int wmask;
    if (uWinOn == 0) wmask = 0x3F;
    else if ((wf & 1) != 0 && inside(x, wx & 255, (wx >> 8) & 255)) wmask = uWinMask.x;
    else if ((wf & 2) != 0 && inside(x, (wx >> 16) & 255, (wx >> 24) & 255)) wmask = uWinMask.y;
    else if ((uWinOn & 4) != 0 && (o & (1 << 19)) != 0) wmask = uWinMask.z;
    else wmask = uWinMask.w;
    int c1 = 0; int t1 = 0; bool semi1 = false; int c2 = 0; int t2 = 0; int filled = 0;
    bool objPending = (o & (1 << 18)) != 0 && (wmask & 16) != 0;
    int objPrio = (o >> 16) & 3;
    int objC = o & 0x7FFF;
    bool objSemi = (o & (1 << 15)) != 0;
    for (int prio = 0; prio < 4; prio++) {
        for (int bg = 0; bg < 4; bg++) {
            if (filled == 2) break;
            ivec4 L = uLayer[bg];
            if ((L.x & 1) == 0 || L.y != prio) continue;
            if (objPending && objPrio <= prio) {
                if (filled == 0) { c1 = objC; t1 = 16; semi1 = objSemi; } else { c2 = objC; t2 = 16; }
                filled++;
                objPending = false;
                if (filled == 2) break;
            }
            if ((wmask & (1 << bg)) == 0 || uSheetSize[bg] == 0) continue;
            ivec2 s = origin[bg];
            int ci;
            if ((L.x & 2) != 0) ci = affineIndex(bg, s.x + uAff[bg].x * x, s.y + uAff[bg].y * x);
            else ci = textIndex(bg, ((L.x & 8) != 0 ? x - x % uMosaicH : x) + s.x, s.y);
            if (ci >= 0) {
                int c = pal(palRow, ci);
                if (filled == 0) { c1 = c; t1 = 1 << bg; semi1 = false; } else { c2 = c; t2 = 1 << bg; }
                filled++;
            }
        }
    }
    if (filled < 2 && objPending) {
        if (filled == 0) { c1 = objC; t1 = 16; semi1 = objSemi; } else { c2 = objC; t2 = 16; }
        filled++;
    }
    if (filled < 2) {
        int c = pal(palRow, 0);
        if (filled == 0) { c1 = c; t1 = 32; semi1 = false; } else { c2 = c; t2 = 32; }
    }
    if ((wmask & 32) == 0) return c1;
    int k = l2.z;
    int eva = k & 255; int evb = (k >> 8) & 255; int evy = (k >> 16) & 255;
    int b = l2.w;
    int top = b & 0x3F; int mode = (b >> 6) & 3; int bottom = (b >> 8) & 0x3F;
    if (semi1 && (bottom & t2) != 0) return blendAlpha(c1, c2, eva, evb);
    if ((top & t1) == 0) return c1;
    if (mode == 1) return (bottom & t2) != 0 ? blendAlpha(c1, c2, eva, evb) : c1;
    if (mode == 2) return brightCh(c1, 0, evy) | brightCh(c1, 5, evy) | brightCh(c1, 10, evy);
    if (mode == 3) return darkCh(c1, 0, evy) | darkCh(c1, 5, evy) | darkCh(c1, 10, evy);
    return c1;
}

void main() {
    int x = clamp(int(vUv.x * 240.0), 0, 239);
    int y = clamp(int(vUv.y * 160.0), 0, 159);
    int c = composite(x, y);
    color = vec4(float((c << 3) & 248), float((c >> 2) & 248), float((c >> 7) & 248), 255.0) / 255.0;
}
"""
    }
}
