// Runs the GPU backend's GLSL (app GpuBackend.kt FRAGMENT + GameSurfaceView.kt VERTEX) in headless
// Chromium WebGL2 on packed frames dumped by FrameCompareTest (FE8_SHADER_DUMP=<dir>) and compares
// every pixel with the software renderer. Usage: node tools/shader_parity.mjs <dump dir>
// Needs Playwright with Chromium (preinstalled in Claude sessions); not part of CI.
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = new URL('..', import.meta.url).pathname;
const kt = (f) => readFileSync(join(root, 'app/src/main/kotlin/fe8/app', f), 'utf8');
const frag = kt('GpuBackend.kt').split('const val FRAGMENT = """')[1].split('"""')[0];
const vert = kt('GameSurfaceView.kt').split('VERTEX = """')[1].split('"""')[0];
const dir = process.argv[2];

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage();
let failed = 0;
for (const f of readdirSync(dir).filter((n) => n.endsWith('.json'))) {
  const data = JSON.parse(readFileSync(join(dir, f), 'utf8'));
  const res = await page.evaluate(({ vert, frag, d }) => {
    const c = document.createElement('canvas');
    c.width = 240; c.height = 160;
    const gl = c.getContext('webgl2');
    const sh = (t, s) => { const o = gl.createShader(t); gl.shaderSource(o, s); gl.compileShader(o);
      if (!gl.getShaderParameter(o, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(o)); return o; };
    const p = gl.createProgram();
    gl.attachShader(p, sh(gl.VERTEX_SHADER, vert)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, frag));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    gl.useProgram(p);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    const u = (n) => gl.getUniformLocation(p, n);
    const tex = (unit, target) => { const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit);
      gl.bindTexture(target, t); gl.texParameteri(target, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
      gl.texParameteri(target, gl.TEXTURE_MAG_FILTER, gl.NEAREST); return t; };
    tex(0, gl.TEXTURE_2D);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R16UI, 512, d.paletteRows, 0, gl.RED_INTEGER, gl.UNSIGNED_SHORT, new Uint16Array(d.palette));
    tex(1, gl.TEXTURE_2D);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32I, 3, 160, 0, gl.RGBA_INTEGER, gl.INT, new Int32Array(d.lines));
    tex(2, gl.TEXTURE_2D);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R32UI, 240, 160, 0, gl.RED_INTEGER, gl.UNSIGNED_INT, new Uint32Array(d.obj));
    tex(3, gl.TEXTURE_2D_ARRAY);
    const maps = new Uint16Array(128 * 128 * 4);
    for (let b = 0; b < 4; b++) maps.set(d['map' + b], b * 128 * 128);
    gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.R16UI, 128, 128, 4, 0, gl.RED_INTEGER, gl.UNSIGNED_SHORT, maps);
    tex(4, gl.TEXTURE_2D_ARRAY);
    const sheets = new Uint8Array(0x10000 * 4);
    for (let b = 0; b < 4; b++) sheets.set(d['sheet' + b], b * 0x10000);
    gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.R8UI, 1024, 64, 4, 0, gl.RED_INTEGER, gl.UNSIGNED_BYTE, sheets);
    ['uPal', 'uLines', 'uObj', 'uMap', 'uSheet'].forEach((n, i) => gl.uniform1i(u(n), i));
    const layer = [], aff = [], size = [];
    for (let b = 0; b < 4; b++) { const l = d['layer' + b]; layer.push(...l.slice(0, 4)); aff.push(l[4], l[5]);
      size.push(d['sheet' + b].length); }
    gl.uniform4iv(u('uLayer'), layer); gl.uniform2iv(u('uAff'), aff); gl.uniform1iv(u('uSheetSize'), size);
    gl.uniform1i(u('uWinOn'), d.win[0]); gl.uniform4i(u('uWinMask'), d.win[1], d.win[2], d.win[3], d.win[4]);
    gl.uniform1i(u('uMosaicH'), d.mosaicH); gl.uniform1i(u('uPalRows'), d.paletteRows);
    gl.uniform2f(u('uScale'), 1, 1);
    gl.bindVertexArray(gl.createVertexArray());
    gl.viewport(0, 0, 240, 160);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const px = new Uint8Array(240 * 160 * 4);
    gl.readPixels(0, 0, 240, 160, gl.RGBA, gl.UNSIGNED_BYTE, px);
    let diff = 0, first = null;
    for (let y = 0; y < 160; y++) for (let x = 0; x < 240; x++) {
      const o = ((159 - y) * 240 + x) * 4;
      const got = (px[o] << 16) | (px[o + 1] << 8) | px[o + 2];
      const want = d.expected[y * 240 + x];
      if (got !== want) { diff++; if (!first) first = { x, y, got: got.toString(16), want: want.toString(16) }; }
    }
    return { diff, first, err: gl.getError() };
  }, { vert, frag, d: data });
  console.log(f, JSON.stringify(res));
  if (res.diff) failed++;
}
await browser.close();
process.exit(failed ? 1 : 0);
