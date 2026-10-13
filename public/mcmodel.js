/* Minecraft-Assets für Icons und 3D-Ansicht: Texturatlas, Blockstates und Modelle -> fertige Quads */
(function (root) {
  'use strict';

  const DIRS = ['down', 'up', 'north', 'south', 'west', 'east'];
  const DIRV = [[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]];
  const strip = (s) => String(s).replace(/^minecraft:/, '');

  const A = { ok: false, img: null, w: 16, h: 16, atlas: {}, states: {}, models: {}, items: {}, ready: null };
  let pix = null;
  const texCache = new Map();
  const modelCache = new Map();

  // Blöcke ohne sichtbares Modell
  const NO_RENDER = new Set(['air', 'cave_air', 'void_air', 'water', 'lava', 'bubble_column', 'moving_piston', 'light',
    'barrier', 'structure_void', 'nether_portal', 'end_portal', 'end_gateway', 'fire', 'soul_fire']);

  function loadImg(src) {
    return new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = () => rej(new Error(src)); i.src = src; });
  }
  function whiteCanvas() {
    const c = document.createElement('canvas'); c.width = c.height = 16;
    const x = c.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, 16, 16);
    return c;
  }

  async function load() {
    const j = (n) => fetch('/assets/' + n).then((r) => { if (!r.ok) throw new Error(n); return r.json(); });
    try {
      const [atlas, states, models, items, img] = await Promise.all([j('atlas.json'), j('blockstates.json'), j('models.json'), j('items.json'), loadImg('/assets/atlas.png')]);
      Object.assign(A, { atlas, states, models, items, img, w: img.width, h: img.height, ok: true });
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
      pix = c.getContext('2d', { willReadFrequently: true });
      pix.drawImage(img, 0, 0);
    } catch (e) {
      console.warn('Minecraft-Assets nicht verfügbar, es werden Platzhalter benutzt:', e.message);
      A.img = whiteCanvas();
    }
    return A.ok;
  }

  /** Textur im Atlas. alpha: 0 = deckend, 1 = Löcher (cutout), 2 = durchscheinend */
  function tex(name) {
    if (!A.ok) return { x: 0, y: 0, w: 16, h: 16, alpha: 0 };
    name = strip(name);
    if (texCache.has(name)) return texCache.get(name);
    const r = A.atlas[name];
    let t = null;
    if (r) {
      // animierte Texturen: nur das erste Bild
      const h = /^(block|item)\//.test(name) && r[3] > r[2] && r[3] % r[2] === 0 ? r[2] : r[3];
      t = { x: r[0], y: r[1], w: r[2], h, alpha: 0 };
      const d = pix.getImageData(t.x, t.y, t.w, t.h).data;
      let holes = 0, soft = 0;
      for (let i = 3; i < d.length; i += 4) { if (d[i] < 250) holes++; if (d[i] > 8 && d[i] < 250) soft++; }
      if (soft > d.length / 4 * 0.05) t.alpha = 2; else if (holes) t.alpha = 1;
    }
    texCache.set(name, t);
    return t;
  }

  /** Modell samt Eltern auflösen */
  function model(id) {
    id = strip(id);
    if (modelCache.has(id)) return modelCache.get(id);
    const chain = [];
    let cur = id, last = id;
    for (let g = 0; cur && g < 24; g++) {
      last = cur;
      const m = A.models[cur];
      if (!m) break;
      chain.push(m);
      cur = m.parent ? strip(m.parent) : null;
    }
    const res = { textures: {}, elements: null, gui: null, generated: last === 'builtin/generated', found: chain.length > 0 };
    for (let i = chain.length - 1; i >= 0; i--) {
      const m = chain[i];
      if (m.textures) Object.assign(res.textures, m.textures);
      if (m.elements) res.elements = m.elements;
      if (m.display && m.display.gui) res.gui = m.display.gui;
    }
    modelCache.set(id, res);
    return res;
  }
  function texRef(ref, textures) {
    for (let g = 0; g < 12 && ref != null; g++) {
      if (typeof ref === 'object') ref = ref.sprite;
      if (typeof ref !== 'string') return null;
      if (ref[0] !== '#') return strip(ref);
      ref = textures[ref.slice(1)];
    }
    return null;
  }

  /* ---------- Geometrie ---------- */
  function rotAxis(p, axis, deg, o) {
    const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
    const x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
    if (axis === 'x') { p[1] = y * c - z * s + o[1]; p[2] = y * s + z * c + o[2]; }
    else if (axis === 'y') { p[0] = x * c + z * s + o[0]; p[2] = -x * s + z * c + o[2]; }
    else { p[0] = x * c - y * s + o[0]; p[1] = x * s + y * c + o[1]; }
  }
  const CENTER = [8, 8, 8];
  function rotVariant(p, rot) {
    if (rot.z) rotAxis(p, 'z', -rot.z, CENTER);
    if (rot.x) rotAxis(p, 'x', -rot.x, CENTER);
    if (rot.y) rotAxis(p, 'y', -rot.y, CENTER);
  }
  function rotElement(p, r) {
    const o = r.origin || CENTER;
    if (r.axis) {
      rotAxis(p, r.axis, r.angle || 0, o);
      if (r.rescale) {
        const k = 1 / Math.cos((r.angle || 0) * Math.PI / 180);
        for (let i = 0; i < 3; i++) if ('xyz'[i] !== r.axis) p[i] = (p[i] - o[i]) * k + o[i];
      }
    } else {
      if (r.x) rotAxis(p, 'x', r.x, o);
      if (r.y) rotAxis(p, 'y', r.y, o);
      if (r.z) rotAxis(p, 'z', r.z, o);
    }
  }
  // Ecken je Seite: oben links, unten links, unten rechts, oben rechts (von aussen gesehen)
  function faceVerts(d, a, b) {
    const [x1, y1, z1] = a, [x2, y2, z2] = b;
    switch (d) {
      case 0: return [[x1, y1, z2], [x1, y1, z1], [x2, y1, z1], [x2, y1, z2]];
      case 1: return [[x1, y2, z1], [x1, y2, z2], [x2, y2, z2], [x2, y2, z1]];
      case 2: return [[x2, y2, z1], [x2, y1, z1], [x1, y1, z1], [x1, y2, z1]];
      case 3: return [[x1, y2, z2], [x1, y1, z2], [x2, y1, z2], [x2, y2, z2]];
      case 4: return [[x1, y2, z1], [x1, y1, z1], [x1, y1, z2], [x1, y2, z2]];
      default: return [[x2, y2, z2], [x2, y1, z2], [x2, y1, z1], [x2, y2, z1]];
    }
  }
  function defaultUv(d, a, b) {
    const [x1, y1, z1] = a, [x2, y2, z2] = b;
    switch (d) {
      case 0: return [x1, 16 - z2, x2, 16 - z1];
      case 1: return [x1, z1, x2, z2];
      case 2: return [16 - x2, 16 - y2, 16 - x1, 16 - y1];
      case 3: return [x1, 16 - y2, x2, 16 - y1];
      case 4: return [z1, 16 - y2, z2, 16 - y1];
      default: return [16 - z2, 16 - y2, 16 - z1, 16 - y1];
    }
  }
  function dirOf(v) {
    let best = 0, bd = -2;
    for (let i = 0; i < 6; i++) { const d = v[0] * DIRV[i][0] + v[1] * DIRV[i][1] + v[2] * DIRV[i][2]; if (d > bd) { bd = d; best = i; } }
    return best;
  }

  const hex = (n) => [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
  function tintOf(name, props) {
    if (/leaves$/.test(name)) return hex(name === 'birch_leaves' ? 0x80a755 : name === 'spruce_leaves' ? 0x619961 : 0x59ae30);
    if (/^(grass_block|short_grass|tall_grass|fern|large_fern|potted_fern|sugar_cane|bush)$/.test(name)) return hex(0x91bd59);
    if (name === 'vine') return hex(0x59ae30);
    if (name === 'lily_pad') return hex(0x208030);
    if (/_stem$/.test(name) && /melon|pumpkin/.test(name)) return hex(0x60a020);
    if (name === 'redstone_wire' || name === 'redstone') return props && Number(props.power) > 0 ? hex(0xfc3100) : hex(0x8c1a00);
    if (/^(water|water_cauldron)$/.test(name)) return hex(0x3f76e4);
    return null;
  }
  function hashColor(name) {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    const f = (n) => { const k = (n + h / 30) % 12; return 0.58 - 0.45 * 0.4 * Math.max(-1, Math.min(k - 3, 9 - k, 1)); };
    return [f(0), f(8), f(4)];
  }

  /** Elemente -> Quads (Koordinaten in Blockeinheiten 0..1, UV in Atlas-Pixeln) */
  function bake(elements, textures, o, out) {
    const rot = o.rot && (o.rot.x || o.rot.y || o.rot.z) ? o.rot : null;
    const off = o.offset;
    for (const el of elements) {
      for (const face in el.faces) {
        const d = DIRS.indexOf(face);
        if (d < 0) continue;
        const f = el.faces[face];
        const t = tex(texRef(f.texture, textures) || '');
        if (!t) continue;
        const P = faceVerts(d, el.from, el.to);
        const uv = f.uv || defaultUv(d, el.from, el.to);
        const cs = [[uv[0], uv[1]], [uv[0], uv[3]], [uv[2], uv[3]], [uv[2], uv[1]]];
        const r = (Math.round((f.rotation || 0) / 90) % 4 + 4) % 4;
        const cu = (uv[0] + uv[2]) / 2, cv = (uv[1] + uv[3]) / 2;
        const p = new Float32Array(12), u = new Float32Array(8);
        for (let i = 0; i < 4; i++) {
          const v = P[i];
          if (el.rotation) rotElement(v, el.rotation);
          if (rot) rotVariant(v, rot);
          for (let k = 0; k < 3; k++) p[i * 3 + k] = (v[k] + (off ? off[k] : 0)) / 16;
          const c = cs[(i + r) % 4];
          // minimal zur Mitte ziehen, damit keine Nachbartextur durchblutet
          u[i * 2] = t.x + (c[0] + (cu - c[0]) * 0.004) / 16 * t.w;
          u[i * 2 + 1] = t.y + (c[1] + (cv - c[1]) * 0.004) / 16 * t.h;
        }
        const ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2], bx = p[6] - p[3], by = p[7] - p[4], bz = p[8] - p[5];
        let nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        const nl = Math.hypot(nx, ny, nz) || 1;
        nx /= nl; ny /= nl; nz /= nl;
        const shade = el.shade === false ? 1 : nx * nx * 0.6 + nz * nz * 0.8 + ny * ny * (ny > 0 ? 1 : 0.5);
        const tint = o.color || (f.tintindex != null && tintOf(o.name, o.props)) || [1, 1, 1];
        let cull = -1;
        if (f.cullface && DIRS.includes(f.cullface)) {
          const cv3 = DIRV[DIRS.indexOf(f.cullface)].slice();
          if (rot) { const z = [0, 0, 0]; const q = [cv3[0] + 8, cv3[1] + 8, cv3[2] + 8]; rotVariant(q, rot); z[0] = q[0] - 8; z[1] = q[1] - 8; z[2] = q[2] - 8; cull = dirOf(z); } else cull = dirOf(cv3);
        }
        out.push({ p, uv: u, col: [tint[0] * shade, tint[1] * shade, tint[2] * shade], cull, layer: t.alpha === 2 ? 1 : 0 });
      }
    }
    return out;
  }

  const box = (texName, a, b, culled) => {
    const faces = {};
    for (const d of DIRS) faces[d] = culled ? { texture: texName, cullface: d } : { texture: texName };
    return [{ from: a || [0, 0, 0], to: b || [16, 16, 16], faces }];
  };

  // Truhen werden im Spiel als Entity gezeichnet -> eigenes Modell aus der Entity-Textur (Vorderseite = Norden)
  function chestTexture(name) {
    name = name.replace(/^waxed_/, '');
    let m;
    if (name === 'chest') return 'entity/chest/normal';
    if (name === 'trapped_chest') return 'entity/chest/trapped';
    if (name === 'ender_chest') return 'entity/chest/ender';
    if (name === 'copper_chest') return 'entity/chest/copper';
    if ((m = name.match(/^(exposed|weathered|oxidized)_copper_chest$/))) return 'entity/chest/copper_' + m[1];
    return null;
  }
  function chestElements() {
    const q = (a, b, c, d) => [a / 4, b / 4, c / 4, d / 4];
    const T = '#c';
    return [
      { from: [1, 0, 1], to: [15, 10, 15], faces: {
        north: { uv: q(42, 43, 56, 33), texture: T }, south: { uv: q(14, 43, 28, 33), texture: T },
        west: { uv: q(0, 43, 14, 33), texture: T }, east: { uv: q(28, 43, 42, 33), texture: T },
        down: { uv: q(14, 19, 28, 33), texture: T } } },
      { from: [1, 10, 1], to: [15, 14, 15], faces: {
        north: { uv: q(42, 18, 56, 14), texture: T }, south: { uv: q(14, 18, 28, 14), texture: T },
        west: { uv: q(0, 18, 14, 14), texture: T }, east: { uv: q(28, 18, 42, 14), texture: T },
        up: { uv: q(28, 0, 42, 14), texture: T } } },
      { from: [7, 7, 0], to: [9, 11, 1], faces: {
        north: { uv: q(1, 5, 3, 1), texture: T }, west: { uv: q(0, 5, 1, 1), texture: T }, east: { uv: q(3, 5, 4, 1), texture: T },
        up: { uv: q(1, 0, 3, 1), texture: T }, down: { uv: q(3, 0, 5, 1), texture: T } } },
    ];
  }
  // Ersatzformen für Blöcke, die im Spiel kein normales Modell haben (Schilder, Banner, Köpfe …)
  function fallbackElements(name, m) {
    const pt = texRef(m.textures.particle, m.textures);
    if (!pt || !tex(pt)) return null;
    if (/wall_(hanging_)?sign$/.test(name)) return { els: box(pt, [1, 5, 14], [15, 13, 16]), textures: {} };
    if (/sign$/.test(name)) return { els: box(pt, [1, 8, 7], [15, 16, 9]).concat(box(pt, [7, 0, 7], [9, 8, 9])), textures: {} };
    if (/banner$/.test(name)) return { els: box(pt, [2, 0, 7], [14, 16, 9]), textures: {} };
    if (/(head|skull)$/.test(name)) return { els: box(pt, [4, 0, 4], [12, 8, 12]), textures: {} };
    return { els: box(pt, null, null, true), textures: {}, full: true };
  }

  const WOOD = /^(stripped_)?(.+?)_(planks|log|wood|stem|hyphae|trapdoor|door|slab|stairs|sign|wall_sign|hanging_sign|wall_hanging_sign|shelf|fence_gate|fence|button|pressure_plate|leaves|sapling|boat|chest_boat)$/;
  /** Unbekannte Holzart (Mod / Datapack) -> Eichen-Variante als Platzhalter */
  function standIn(id, has) {
    const m = id.match(WOOD);
    if (!m) return null;
    const alt = (m[1] || '') + 'oak_' + m[3];
    return has(alt) ? alt : null;
  }

  function matchWhen(when, props) {
    if (when.OR) return when.OR.some((w) => matchWhen(w, props));
    if (when.AND) return when.AND.every((w) => matchWhen(w, props));
    for (const k in when) if (!String(when[k]).split('|').includes(String(props[k]))) return false;
    return true;
  }
  const first = (v) => (Array.isArray(v) ? v[0] : v);

  const FACING_Y = { north: 0, east: 90, south: 180, west: 270 };

  /** Block-State -> { quads, occl (verdeckt Nachbarn komplett), self (voller Würfel) } */
  function blockQuads(fullName, props) {
    props = props || {};
    let name = strip(fullName);
    const out = { name, quads: [], occl: false, self: false };
    if (NO_RENDER.has(name)) return out;
    const fallback = () => {
      bake(box('block/white_concrete', null, null, true), {}, { color: hashColor(name) }, out.quads);
      out.occl = out.self = true;
      return out;
    };
    if (!A.ok) return fallback();
    if (!A.states[name] && A.states[RENAMED[name]]) name = RENAMED[name];
    if (!A.states[name]) { const alt = standIn(name, (k) => A.states[k]); if (alt) name = alt; }
    const st = A.states[name];
    if (!st) return fallback();
    const applies = [];
    if (st.variants) {
      let hit = null;
      for (const key in st.variants) {
        if (key === '' || key.split(',').every((kv) => { const i = kv.indexOf('='); return String(props[kv.slice(0, i)]) === kv.slice(i + 1); })) { hit = st.variants[key]; break; }
      }
      if (!hit) hit = Object.values(st.variants)[0];
      if (hit) applies.push(first(hit));
    } else if (st.multipart) {
      for (const part of st.multipart) if (!part.when || matchWhen(part.when, props)) applies.push(first(part.apply));
    }
    for (const ap of applies) {
      const m = model(ap.model);
      const o = { rot: ap, name, props };
      let els = m.elements, textures = m.textures, full = false;
      const ct = chestTexture(name);
      if (ct && tex(ct)) { els = chestElements(); textures = { c: ct }; o.rot = { y: FACING_Y[props.facing] || 0 }; }
      else if (!els) {
        const fb = fallbackElements(name, m);
        if (!fb) continue;
        els = fb.els; textures = fb.textures; full = fb.full;
        if (!full) o.rot = { y: FACING_Y[props.facing] || 0 };
      }
      bake(els, textures, o, out.quads);
      if (applies.length === 1) {
        for (const el of els) {
          if (el.rotation || el.from.some((v) => v !== 0) || el.to.some((v) => v !== 16)) continue;
          out.self = true;
          out.occl = DIRS.every((d) => { const f = el.faces[d]; const t = f && tex(texRef(f.texture, textures) || ''); return t && t.alpha === 0; });
        }
      }
    }
    if (!out.quads.length && !applies.length) return fallback();
    return out;
  }

  /* ---------- Item-Icons ---------- */
  function collect(n, out, tr) {
    if (!n || typeof n !== 'object') return;
    const t = n.transformation && !Array.isArray(n.transformation) && n.transformation.translation;
    const off = t ? [(tr ? tr[0] : 0) + t[0] * 16, (tr ? tr[1] : 0) + t[1] * 16, (tr ? tr[2] : 0) + t[2] * 16] : tr;
    switch (strip(n.type || '')) {
      case 'model': out.push({ model: n.model, offset: off }); break;
      case 'special': out.push({ model: n.base, offset: tr }); break;
      case 'composite': (n.models || []).forEach((m) => collect(m, out, tr)); break;
      case 'select': collect(n.fallback || (n.cases && n.cases[0] && n.cases[0].model), out, tr); break;
      case 'condition': collect(n.on_false || n.on_true, out, tr); break;
      case 'range_dispatch': collect(n.fallback || (n.entries && n.entries[0] && n.entries[0].model), out, tr); break;
    }
  }
  const ALIAS = { '#planks': 'oak_planks', '#logs': 'oak_log' };
  // Items, die in neueren Versionen anders heissen
  const RENAMED = { chain: 'iron_chain', iron_chain: 'chain', grass: 'short_grass', short_grass: 'grass', scute: 'turtle_scute' };

  /** Item -> { flat: [texturen] } oder { quads, gui }, null wenn unbekannt */
  function itemIcon(rawId) {
    if (!A.ok) return null;
    let id = ALIAS[rawId] || strip(rawId);
    let standin = false;
    if (!A.items[id] && !A.states[id] && RENAMED[id]) id = RENAMED[id];
    if (!A.items[id] && !A.states[id]) {
      const alt = standIn(id, (k) => A.items[k] || A.states[k]);
      if (!alt) return null;
      id = alt; standin = true;
    }
    const parts = [];
    if (A.items[id]) collect(A.items[id].model, parts, null);
    if (!parts.length) {
      if (!A.states[id]) return null;
      const b = blockQuads(id, {});
      return b.quads.length ? { quads: b.quads, gui: null, standin } : null;
    }
    const quads = [];
    let gui = null;
    for (const part of parts) {
      const m = model(part.model);
      if (m.generated) {
        const layers = [];
        for (let i = 0; i < 6; i++) { const t = m.textures['layer' + i] && tex(texRef(m.textures['layer' + i], m.textures)); if (t) layers.push(t); }
        if (layers.length) return { flat: layers, tint: tintOf(id), standin };
        continue;
      }
      const o = { name: id, props: {}, offset: part.offset };
      let els = m.elements, textures = m.textures;
      const ct = chestTexture(id);
      if (ct && tex(ct)) { els = chestElements(); textures = { c: ct }; o.rot = { y: 180 }; }
      else if (!els) { const fb = fallbackElements(id, m); if (!fb) continue; els = fb.els; textures = fb.textures; }
      bake(els, textures, o, quads);
      gui = gui || m.gui;
    }
    return quads.length ? { quads, gui, standin } : null;
  }

  Object.assign(A, { load, tex, model, blockQuads, itemIcon, strip, NO_RENDER });
  A.ready = load();
  root.MCA = A;
})(self);
