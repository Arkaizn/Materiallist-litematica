'use strict';
/* Litematica-Parser: .litematic (gzip-NBT) -> Materialliste. Keine Abhängigkeiten. */
const zlib = require('zlib');

function parseNBT(buf) {
  let p = 0;
  const T = {
    1() { return buf.readInt8(p++); },
    2() { const v = buf.readInt16BE(p); p += 2; return v; },
    3() { const v = buf.readInt32BE(p); p += 4; return v; },
    4() { const v = Number(buf.readBigInt64BE(p)); p += 8; return v; },
    5() { const v = buf.readFloatBE(p); p += 4; return v; },
    6() { const v = buf.readDoubleBE(p); p += 8; return v; },
    7() { const n = buf.readInt32BE(p); p += 4; const v = buf.subarray(p, p + n); p += n; return v; },
    8() { const n = buf.readUInt16BE(p); p += 2; const v = buf.toString('utf8', p, p + n); p += n; return v; },
    9() {
      const t = buf.readInt8(p++); const n = buf.readInt32BE(p); p += 4;
      const a = [];
      for (let i = 0; i < n; i++) a.push(T[t]());
      return a;
    },
    10() {
      const o = {};
      for (;;) {
        const t = buf.readInt8(p++);
        if (t === 0) break;
        const k = T[8]();
        o[k] = T[t]();
      }
      return o;
    },
    11() { const n = buf.readInt32BE(p); p += 4; const a = []; for (let i = 0; i < n; i++) a.push(T[3]()); return a; },
    12() { const n = buf.readInt32BE(p); p += 4; const v = { longs: buf.subarray(p, p + n * 8) }; p += n * 8; return v; },
  };
  if (buf.readInt8(p++) !== 10) throw new Error('Kein NBT-Compound am Anfang');
  T[8](); // Root-Name
  return T[10]();
}

const SKIP = new Set(['air', 'cave_air', 'void_air', 'water', 'lava', 'bubble_column', 'piston_head', 'moving_piston',
  'fire', 'soul_fire', 'nether_portal', 'end_portal', 'end_gateway', 'light', 'structure_void', 'powder_snow']);
const RENAME = {
  carrots: 'carrot', potatoes: 'potato', beetroots: 'beetroot', wheat: 'wheat_seeds', cocoa: 'cocoa_beans',
  melon_stem: 'melon_seeds', attached_melon_stem: 'melon_seeds', pumpkin_stem: 'pumpkin_seeds',
  attached_pumpkin_stem: 'pumpkin_seeds', sweet_berry_bush: 'sweet_berries', cave_vines: 'glow_berries',
  cave_vines_plant: 'glow_berries', kelp_plant: 'kelp', weeping_vines_plant: 'weeping_vines',
  twisting_vines_plant: 'twisting_vines', tall_seagrass: 'seagrass', bamboo_sapling: 'bamboo',
  big_dripleaf_stem: 'big_dripleaf', frosted_ice: 'ice', redstone_wire: 'redstone', tripwire: 'string',
};

/** Block-State -> Liste von [itemId, anzahl] */
function blockToItems(fullName, p) {
  p = p || {};
  if (fullName.includes(':') && !fullName.startsWith('minecraft:')) return [[fullName, 1]]; // Mod-Block
  let id = fullName.replace(/^minecraft:/, '');
  if (SKIP.has(id)) return [];
  if (p.half === 'upper') return [];                       // obere Hälfte von Türen / hohen Pflanzen
  if (p.part === 'head' && /_bed$/.test(id)) return [];    // Bett zählt nur einmal
  if (RENAME[id]) id = RENAME[id];
  id = id.replace(/wall_torch$/, 'torch');
  let m;
  if ((m = id.match(/^(.+)_wall_(sign|hanging_sign|banner|head|skull)$/))) id = m[1] + '_' + m[2];
  if (/_cauldron$/.test(id) && id !== 'cauldron') id = 'cauldron';
  if ((m = id.match(/^(.*candle)_cake$/)) || id === 'candle_cake') return [[m ? m[1] : 'candle', 1], ['cake', 1]];
  if ((m = id.match(/^potted_(.+)$/))) {
    let plant = m[1];
    if (plant === 'azalea_bush') plant = 'azalea';
    if (plant === 'flowering_azalea_bush') plant = 'flowering_azalea';
    return [['flower_pot', 1], [plant, 1]];
  }
  let mult = 1;
  if (p.type === 'double' && /_slab$/.test(id)) mult = 2;
  else if (id === 'snow' && p.layers) mult = Number(p.layers);
  else {
    const c = p.pickles || p.candles || p.eggs || p.flower_amount || p.segment_amount;
    if (c) mult = Number(c);
  }
  return [[id, mult]];
}

const MAX_UNPACKED = 512 * 1024 * 1024;
const MAX_VOLUME = 64e6;
const int = (v) => (Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : 0);

/** Palette-Indizes einer Region entpacken (Reihenfolge: y, z, x) */
function readRegion(reg) {
  const pal = reg.BlockStatePalette;
  const sz = reg.Size || {};
  const size = [Math.abs(int(sz.x)), Math.abs(int(sz.y)), Math.abs(int(sz.z))];
  const vol = size[0] * size[1] * size[2];
  if (!Array.isArray(pal) || !pal.length || !reg.BlockStates || !reg.BlockStates.longs || !(vol > 0)) return null;
  const bits = Math.max(2, 32 - Math.clz32(pal.length - 1));
  // Grössenangaben stammen aus der Datei: nichts anlegen, was die Daten gar nicht hergeben
  if (vol > MAX_VOLUME || pal.length > 65536 || reg.BlockStates.longs.length * 8 < vol * bits) throw new Error('Region ist zu groß oder beschädigt');
  const mask = bits >= 32 ? 0xffffffff : ((1 << bits) - 1) >>> 0;
  // Litematica packt Bits lückenlos über Long-Grenzen -> als 32-Bit-Wörter (little-endian Bitstrom) lesen
  const lb = reg.BlockStates.longs;
  const nl = lb.length / 8;
  const w = new Uint32Array(nl * 2 + 1);
  for (let k = 0; k < nl; k++) { w[2 * k] = lb.readUInt32BE(8 * k + 4); w[2 * k + 1] = lb.readUInt32BE(8 * k); }
  const idx = new Uint16Array(vol);
  for (let i = 0; i < vol; i++) {
    const o = i * bits;
    const w0 = Math.floor(o / 32);
    const s = o - w0 * 32;
    let v = w[w0] >>> s;
    if (s + bits > 32) v |= w[w0 + 1] << (32 - s);
    v = (v & mask) >>> 0;
    idx[i] = v < pal.length ? v : 0;
  }
  const pos = reg.Position || { x: 0, y: 0, z: 0 };
  const min = ['x', 'y', 'z'].map((a) => (int(sz[a]) < 0 ? int(pos[a]) + int(sz[a]) + 1 : int(pos[a])));
  return { pal, size, vol, idx, min };
}

function gunzipNBT(input) {
  let buf = input;
  if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf, { maxOutputLength: MAX_UNPACKED });   // Schutz vor Zip-Bomben
  const root = parseNBT(buf);
  if (!root.Regions) throw new Error('Keine gültige .litematic-Datei (Regions fehlen)');
  return root;
}

/** Blockpositionen für die 3D-Ansicht: je Region Palette + Indizes */
function parseVoxels(input, maxVolume = MAX_VOLUME) {
  const root = gunzipNBT(input);
  const regions = [];
  let total = 0;
  for (const reg of Object.values(root.Regions)) {
    const r = readRegion(reg);
    if (!r) continue;
    total += r.vol;
    if (total > maxVolume) throw new Error('Schematic ist zu groß für die 3D-Ansicht');
    regions.push({
      min: r.min, size: r.size,
      palette: r.pal.map((e) => ({ name: String(e.Name), props: e.Properties && typeof e.Properties === 'object' ? e.Properties : {} })),
      data: Buffer.from(r.idx.buffer, r.idx.byteOffset, r.idx.byteLength).toString('base64'),
    });
  }
  return { regions };
}

function parseLitematic(input) {
  const root = gunzipNBT(input);
  const counts = {};
  let totalBlocks = 0;
  for (const reg of Object.values(root.Regions)) {
    const r = readRegion(reg);
    if (!r) continue;
    const pal = r.pal;
    const pc = new Float64Array(pal.length);
    for (let i = 0; i < r.vol; i++) pc[r.idx[i]]++;
    for (let i = 0; i < pal.length; i++) {
      if (!pc[i]) continue;
      for (const [id, mult] of blockToItems(String(pal[i].Name), pal[i].Properties)) {
        counts[id] = (counts[id] || 0) + pc[i] * mult;
        totalBlocks += pc[i];
      }
    }
  }
  const md = root.Metadata || {};
  const es = md.EnclosingSize;
  return {
    name: String(md.Name || '').slice(0, 120),
    meta: {
      // alles aus der Datei auf den erwarteten Typ bringen, die Werte landen später in der Seite
      author: String(md.Author || '').slice(0, 80),
      description: String(md.Description || '').slice(0, 500),
      size: es ? `${int(es.x)}×${int(es.y)}×${int(es.z)}` : '',
      regions: int(md.RegionCount) || Object.keys(root.Regions).length,
      totalBlocks: int(md.TotalBlocks),
      dataVersion: int(root.MinecraftDataVersion),
    },
    items: Object.keys(counts).map((id) => ({ id, count: counts[id] })).sort((a, b) => b.count - a.count),
  };
}

module.exports = { parseLitematic, parseVoxels, parseNBT, blockToItems };
