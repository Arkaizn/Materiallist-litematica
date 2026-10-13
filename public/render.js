/* WebGL: Item-Icons und 3D-Ansicht der Schematic. Braucht mcmodel.js (MCA). */
(function (root) {
  'use strict';

  const VS = `#version 300 es
in vec3 aPos; in vec2 aUv; in vec4 aCol;
uniform mat4 uMvp; uniform vec2 uAtlas;
out highp vec2 vUv; out lowp vec4 vCol;
void main() { vUv = aUv / uAtlas; vCol = aCol; gl_Position = uMvp * vec4(aPos, 1.0); }`;
  const FS = `#version 300 es
precision highp float;
in highp vec2 vUv; in lowp vec4 vCol;
uniform sampler2D uTex;
out vec4 o;
void main() { vec4 c = texture(uTex, vUv) * vCol; if (c.a < 0.1) discard; o = c; }`;

  /* ---------- Matrizen (spaltenweise) ---------- */
  const M = {
    ident: () => new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]),
    mul(a, b) {
      const o = new Float32Array(16);
      for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
      return o;
    },
    translate(x, y, z) { const m = M.ident(); m[12] = x; m[13] = y; m[14] = z; return m; },
    scale(x, y, z) { const m = M.ident(); m[0] = x; m[5] = y; m[10] = z; return m; },
    rot(axis, deg) {
      const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), m = M.ident();
      if (axis === 'x') { m[5] = c; m[6] = s; m[9] = -s; m[10] = c; }
      else if (axis === 'y') { m[0] = c; m[2] = -s; m[8] = s; m[10] = c; }
      else { m[0] = c; m[1] = s; m[4] = -s; m[5] = c; }
      return m;
    },
    perspective(fov, aspect, near, far) {
      const f = 1 / Math.tan(fov / 2), m = new Float32Array(16);
      m[0] = f / aspect; m[5] = f; m[10] = (far + near) / (near - far); m[11] = -1; m[14] = 2 * far * near / (near - far);
      return m;
    },
    lookAt(e, t, up) {
      const n = (v) => { const l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; };
      const cr = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
      const z = n([e[0] - t[0], e[1] - t[1], e[2] - t[2]]), x = n(cr(up, z)), y = cr(z, x);
      const d = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
      return new Float32Array([x[0], y[0], z[0], 0, x[1], y[1], z[1], 0, x[2], y[2], z[2], 0, -d(x, e), -d(y, e), -d(z, e), 1]);
    },
  };

  /* ---------- Geometrie-Puffer ---------- */
  class Geo {
    constructor() { this.n = 0; this.cap = 256; this.pos = new Float32Array(this.cap * 12); this.uv = new Float32Array(this.cap * 8); this.col = new Uint8Array(this.cap * 16); }
    grow() {
      this.cap *= 2;
      for (const [k, s] of [['pos', 12], ['uv', 8], ['col', 16]]) { const a = new this[k].constructor(this.cap * s); a.set(this[k]); this[k] = a; }
    }
    quad(q, x, y, z) {
      if (this.n === this.cap) this.grow();
      const n = this.n++, p = q.p, pos = this.pos, col = this.col, c = q.col;
      const r = c[0] * 255, g = c[1] * 255, b = c[2] * 255;
      for (let i = 0; i < 4; i++) {
        pos[n * 12 + i * 3] = p[i * 3] + x; pos[n * 12 + i * 3 + 1] = p[i * 3 + 1] + y; pos[n * 12 + i * 3 + 2] = p[i * 3 + 2] + z;
        col[n * 16 + i * 4] = r; col[n * 16 + i * 4 + 1] = g; col[n * 16 + i * 4 + 2] = b; col[n * 16 + i * 4 + 3] = 255;
      }
      this.uv.set(q.uv, n * 8);
    }
  }

  function makeGL(canvas, opts) {
    const gl = canvas.getContext('webgl2', opts);
    if (!gl) return null;
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; };
    const prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FS));
    ['aPos', 'aUv', 'aCol'].forEach((n, i) => gl.bindAttribLocation(prog, i, n));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
    gl.useProgram(prog);
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, MCA.img);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    if (opts.mipmaps) {
      // nur bis Stufe 3: die 16-Pixel-Kacheln des Atlas bleiben dabei sauber getrennt, in der Ferne flimmert es aber nicht
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAX_LEVEL, 3);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST_MIPMAP_LINEAR);
    }
    gl.uniform2f(gl.getUniformLocation(prog, 'uAtlas'), MCA.w, MCA.h);
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL);
    gl.enable(gl.CULL_FACE);
    gl.enable(gl.BLEND);
    gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    return { gl, uMvp: gl.getUniformLocation(prog, 'uMvp') };
  }
  function upload(ctx, geo) {
    const gl = ctx.gl, n = geo.n;
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    const bufs = [];
    const attr = (i, data, size, type, norm) => {
      const b = gl.createBuffer(); bufs.push(b);
      gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
      gl.enableVertexAttribArray(i); gl.vertexAttribPointer(i, size, type, norm, 0, 0);
    };
    attr(0, geo.pos.subarray(0, n * 12), 3, gl.FLOAT, false);
    attr(1, geo.uv.subarray(0, n * 8), 2, gl.FLOAT, false);
    attr(2, geo.col.subarray(0, n * 16), 4, gl.UNSIGNED_BYTE, true);
    const idx = new Uint32Array(n * 6);
    for (let i = 0; i < n; i++) idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
    const ib = gl.createBuffer(); bufs.push(ib);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW);
    gl.bindVertexArray(null);
    return { vao, bufs, count: n * 6 };
  }
  function free(ctx, mesh) {
    if (!mesh) return;
    mesh.bufs.forEach((b) => ctx.gl.deleteBuffer(b));
    ctx.gl.deleteVertexArray(mesh.vao);
  }
  function drawMesh(ctx, mesh) {
    if (!mesh || !mesh.count) return;
    ctx.gl.bindVertexArray(mesh.vao);
    ctx.gl.drawElements(ctx.gl.TRIANGLES, mesh.count, ctx.gl.UNSIGNED_INT, 0);
  }

  /* ---------- Icons ---------- */
  const ICON_PX = 64;
  const icons = new Map();
  let iconCtx = null, iconFailed = false;

  function flatIcon(ic) {
    const c = document.createElement('canvas'); c.width = c.height = 32;
    const x = c.getContext('2d');
    x.imageSmoothingEnabled = false;
    for (const t of ic.flat) x.drawImage(MCA.img, t.x, t.y, t.w, t.h, 0, 0, 32, 32);
    if (ic.tint) {
      x.globalCompositeOperation = 'multiply';
      x.fillStyle = `rgb(${ic.tint.map((v) => Math.round(v * 255)).join(',')})`;
      x.fillRect(0, 0, 32, 32);
      x.globalCompositeOperation = 'destination-in';
      for (const t of ic.flat) x.drawImage(MCA.img, t.x, t.y, t.w, t.h, 0, 0, 32, 32);
    }
    return c.toDataURL();
  }
  function modelIcon(ic) {
    if (iconFailed) return null;
    if (!iconCtx) {
      const c = document.createElement('canvas'); c.width = c.height = ICON_PX;
      try { iconCtx = makeGL(c, { alpha: true, antialias: true, preserveDrawingBuffer: true }); } catch (e) { console.warn(e); }
      if (!iconCtx) { iconFailed = true; return null; }
      iconCtx.canvas = c;
    }
    const { gl } = iconCtx;
    const g = ic.gui || {};
    const r = g.rotation || [30, 225, 0], s = g.scale || [0.625, 0.625, 0.625], t = g.translation || [0, 0, 0];
    let m = M.scale(2, 2, -0.5);
    m = M.mul(m, M.translate(t[0] / 16, t[1] / 16, t[2] / 16));
    m = M.mul(m, M.rot('x', r[0])); m = M.mul(m, M.rot('y', r[1])); m = M.mul(m, M.rot('z', r[2]));
    m = M.mul(m, M.scale(s[0], s[1], s[2]));
    m = M.mul(m, M.translate(-0.5, -0.5, -0.5));
    const solid = new Geo(), soft = new Geo();
    for (const q of ic.quads) (q.layer ? soft : solid).quad(q, 0, 0, 0);
    gl.viewport(0, 0, ICON_PX, ICON_PX);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.uniformMatrix4fv(iconCtx.uMvp, false, m);
    const a = upload(iconCtx, solid), b = upload(iconCtx, soft);
    drawMesh(iconCtx, a);
    gl.depthMask(false); drawMesh(iconCtx, b); gl.depthMask(true);
    free(iconCtx, a); free(iconCtx, b);
    return iconCtx.canvas.toDataURL();
  }
  /** undefined = Assets laden noch, null = kein Icon vorhanden, sonst { url, flat, standin } */
  function iconFor(id) {
    if (!MCA.img) return undefined;
    if (icons.has(id)) return icons.get(id);
    let res = null;
    try {
      const ic = MCA.itemIcon(id);
      if (ic) {
        const url = ic.flat ? flatIcon(ic) : modelIcon(ic);
        if (url) res = { url, flat: !!ic.flat, standin: !!ic.standin };
      }
    } catch (e) { console.warn('Icon', id, e); }
    icons.set(id, res);
    return res;
  }

  /* ---------- 3D-Ansicht ---------- */
  function b64ToU16(s) {
    const bin = atob(s), u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return new Uint16Array(u8.buffer, 0, u8.length >> 1);
  }

  class Viewer {
    /** host: Element, vox: Antwort von /api/projects/:id/voxels, onLayer(info): Callback bei Ebenenwechsel */
    constructor(host, vox, onLayer) {
      this.host = host; this.onLayer = onLayer || (() => {});
      this.canvas = document.createElement('canvas');
      host.appendChild(this.canvas);
      this.ctx = makeGL(this.canvas, { alpha: false, antialias: true, mipmaps: true });
      if (!this.ctx) throw new Error('WebGL2 wird von diesem Browser nicht unterstützt');
      this.buildGrid(vox);
      this.yMin = 0; this.yMax = this.size[1] - 1;
      this.resetView();
      this.remesh();
      this.bind();
      this.ro = new ResizeObserver(() => this.draw());
      this.ro.observe(host);
    }
    buildGrid(vox) {
      const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
      for (const r of vox.regions) for (let a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], r.min[a]); hi[a] = Math.max(hi[a], r.min[a] + r.size[a]); }
      const size = this.size = [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]];
      this.origin = lo;
      const grid = this.grid = new Uint16Array(size[0] * size[1] * size[2]);
      const pal = this.pal = [null], keys = new Map();
      this.blocks = 0;
      for (const r of vox.regions) {
        const map = r.palette.map((e) => {
          const key = e.name + JSON.stringify(e.props);
          if (!keys.has(key)) {
            const b = MCA.blockQuads(e.name, e.props);
            if (!b.quads.length) keys.set(key, 0); else { keys.set(key, pal.length); pal.push(b); }
          }
          return keys.get(key);
        });
        const idx = b64ToU16(r.data), [sx, sy, sz] = r.size;
        const ox = r.min[0] - lo[0], oy = r.min[1] - lo[1], oz = r.min[2] - lo[2];
        let i = 0;
        for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) {
          let o = ((y + oy) * size[2] + z + oz) * size[0] + ox;
          for (let x = 0; x < sx; x++, i++, o++) { const v = map[idx[i]]; if (v) { if (!grid[o]) this.blocks++; grid[o] = v; } }
        }
      }
    }
    remesh() {
      const [X, Y, Z] = this.size, grid = this.grid, pal = this.pal, y0 = this.yMin, y1 = this.yMax;
      const geos = [new Geo(), new Geo()];
      const counts = new Map();
      let shown = 0;
      const at = (x, y, z) => (x < 0 || z < 0 || x >= X || z >= Z || y < y0 || y > y1 ? 0 : grid[(y * Z + z) * X + x]);
      for (let y = y0; y <= y1; y++) for (let z = 0; z < Z; z++) for (let x = 0; x < X; x++) {
        const id = grid[(y * Z + z) * X + x];
        if (!id) continue;
        const e = pal[id];
        shown++;
        if (y === y1) counts.set(e.name, (counts.get(e.name) || 0) + 1);
        for (const q of e.quads) {
          if (q.cull >= 0) {
            const c = q.cull;
            const n = c === 0 ? at(x, y - 1, z) : c === 1 ? at(x, y + 1, z) : c === 2 ? at(x, y, z - 1) : c === 3 ? at(x, y, z + 1) : c === 4 ? at(x - 1, y, z) : at(x + 1, y, z);
            if (n && (pal[n].occl || (e.self && pal[n].self && pal[n].name === e.name))) continue;
          }
          geos[q.layer].quad(q, x, y, z);
        }
      }
      (this.meshes || []).forEach((m) => free(this.ctx, m));
      this.meshes = geos.map((g) => upload(this.ctx, g));
      this.onLayer({ yMin: y0, yMax: y1, layers: Y, shown, total: this.blocks, top: [...counts].sort((a, b) => b[1] - a[1]) });
      this.draw();
    }
    setLayers(yMin, yMax) {
      this.yMin = Math.max(0, Math.min(yMin, this.size[1] - 1));
      this.yMax = Math.max(this.yMin, Math.min(yMax, this.size[1] - 1));
      this.remesh();
    }
    resetView() {
      const s = this.size;
      this.target = [s[0] / 2, s[1] / 2, s[2] / 2];
      this.radius = Math.hypot(s[0], s[1], s[2]) / 2;
      this.dist = this.radius / Math.sin(Math.PI / 8) * 1.05;
      this.yaw = Math.PI * 0.25; this.pitch = Math.PI * 0.17;
      this.draw();
    }
    eye() {
      const cp = Math.cos(this.pitch);
      return [this.target[0] + this.dist * cp * Math.sin(this.yaw), this.target[1] + this.dist * Math.sin(this.pitch), this.target[2] + this.dist * cp * Math.cos(this.yaw)];
    }
    draw() {
      if (this.raf || this.dead) return;
      this.raf = requestAnimationFrame(() => {
        this.raf = 0;
        if (this.dead || !this.meshes) return;
        const { gl } = this.ctx, c = this.canvas;
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        const w = Math.max(1, Math.round(this.host.clientWidth * dpr)), h = Math.max(1, Math.round(this.host.clientHeight * dpr));
        if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
        gl.viewport(0, 0, w, h);
        gl.clearColor(0.075, 0.083, 0.095, 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        const far = this.dist + this.radius * 4 + 10;
        const mvp = M.mul(M.perspective(Math.PI / 4, w / h, Math.max(0.1, far / 4000), far), M.lookAt(this.eye(), this.target, [0, 1, 0]));
        gl.uniformMatrix4fv(this.ctx.uMvp, false, mvp);
        drawMesh(this.ctx, this.meshes[0]);
        gl.depthMask(false); drawMesh(this.ctx, this.meshes[1]); gl.depthMask(true);
      });
    }
    bind() {
      const c = this.canvas, pts = new Map();
      let pinch = 0;
      c.addEventListener('contextmenu', (e) => e.preventDefault());
      c.addEventListener('pointerdown', (e) => { c.setPointerCapture(e.pointerId); pts.set(e.pointerId, { x: e.clientX, y: e.clientY, pan: e.button === 2 || e.button === 1 || e.shiftKey }); pinch = 0; });
      const up = (e) => { pts.delete(e.pointerId); pinch = 0; };
      c.addEventListener('pointerup', up); c.addEventListener('pointercancel', up);
      c.addEventListener('pointermove', (e) => {
        const p = pts.get(e.pointerId);
        if (!p) return;
        const dx = e.clientX - p.x, dy = e.clientY - p.y;
        p.x = e.clientX; p.y = e.clientY;
        if (pts.size >= 2) {
          const [a, b] = [...pts.values()];
          const d = Math.hypot(a.x - b.x, a.y - b.y);
          if (pinch) this.zoom(pinch / d);
          pinch = d;
        } else if (p.pan) this.pan(dx, dy);
        else {
          this.yaw -= dx * 0.008;
          this.pitch = Math.max(-1.55, Math.min(1.55, this.pitch + dy * 0.008));
        }
        this.draw();
      });
      c.addEventListener('wheel', (e) => { e.preventDefault(); this.zoom(Math.exp(e.deltaY * (e.deltaMode ? 0.05 : 0.0015))); this.draw(); }, { passive: false });
    }
    zoom(f) { this.dist = Math.max(1.5, Math.min(this.radius * 12 + 20, this.dist * f)); }
    pan(dx, dy) {
      const k = this.dist / this.host.clientHeight * 0.83;
      const sy = Math.sin(this.yaw), cy = Math.cos(this.yaw), sp = Math.sin(this.pitch), cp = Math.cos(this.pitch);
      const right = [cy, 0, -sy], upv = [-sp * sy, cp, -sp * cy];
      for (let i = 0; i < 3; i++) this.target[i] += (-dx * right[i] + dy * upv[i]) * k;
    }
    dispose() {
      this.dead = true;
      if (this.raf) cancelAnimationFrame(this.raf);
      if (this.ro) this.ro.disconnect();
      (this.meshes || []).forEach((m) => free(this.ctx, m));
      const ext = this.ctx.gl.getExtension('WEBGL_lose_context');
      if (ext) ext.loseContext();
      this.canvas.remove();
    }
  }

  root.MC3D = { iconFor, Viewer };
})(self);
