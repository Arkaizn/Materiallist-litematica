'use strict';
/* Minimaler Server ohne Abhängigkeiten: statische UI + JSON-API + Upload (.litematic / .json) */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const { parseLitematic, parseVoxels, blockToItems } = require('./litematic');
const MC = require('./public/logic.js');

const PORT = Number(process.env.PORT) || 3000;
const DATA = process.env.DATA_DIR || path.join(__dirname, 'data');
const PROJ_DIR = path.join(DATA, 'projects');
const SCHEM_DIR = path.join(DATA, 'schematics');
const ASSET_DIR = path.join(DATA, 'cache', 'assets');
const PUBLIC = path.join(__dirname, 'public');
for (const d of [PROJ_DIR, SCHEM_DIR, ASSET_DIR]) fs.mkdirSync(d, { recursive: true });

// Texturen + Modelle für Icons und 3D-Ansicht (misode/mcmeta). Werden einmal geladen und in data/cache/assets abgelegt.
// MC_VERSION (z.B. 1.21.4) legt eine feste Version fest, sonst die jeweils neueste.
const SYNC_TOKEN = process.env.SYNC_TOKEN || '';
const MC_VERSION = process.env.MC_VERSION || '';
const ref = (branch) => (MC_VERSION ? `${MC_VERSION}-${branch}` : branch);
const ASSET_BASE = process.env.ASSET_BASE || 'https://raw.githubusercontent.com/misode/mcmeta';
const ASSETS = {
  'atlas.png': `${ref('atlas')}/all/atlas.png`,
  'atlas.json': `${ref('atlas')}/all/data.min.json`,
  'blockstates.json': `${ref('summary')}/assets/block_definition/data.min.json`,
  'models.json': `${ref('summary')}/assets/model/data.min.json`,
  'items.json': `${ref('summary')}/assets/item_definition/data.min.json`,
};

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon' };

const send = (res, code, obj) => {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
};
const fail = (res, code, msg) => send(res, code, { error: msg });
const wantsGzip = (req) => /\bgzip\b/.test(req.headers['accept-encoding'] || '');
function sendGz(req, res, obj) {
  const body = Buffer.from(JSON.stringify(obj));
  const head = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
  if (!wantsGzip(req)) { res.writeHead(200, head); return res.end(body); }
  res.writeHead(200, { ...head, 'Content-Encoding': 'gzip' });
  res.end(zlib.gzipSync(body));
}

const sameSecret = (a, b) => {
  const x = crypto.createHash('sha256').update(String(a || '')).digest(), y = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(x, y);
};

// Schutz vor fremden Webseiten: Ohne das könnte jede Seite, die du im Browser offen hast, Anfragen an http://localhost:3000 schicken
// (Projekte anlegen, Sync-Daten fälschen) oder per DNS-Rebinding sogar mitlesen.
const ALLOWED_HOSTS = (process.env.ALLOWED_HOSTS || '').split(',').map((h) => h.trim().toLowerCase()).filter(Boolean);
function hostAllowed(hostHeader) {
  const h = String(hostHeader || '').toLowerCase().replace(/:\d+$/, '').replace(/^\[|\]$/g, '');
  if (!h) return false;
  if (ALLOWED_HOSTS.includes('*') || ALLOWED_HOSTS.includes(h)) return true;
  // lokale Namen und IP-Adressen sind in Ordnung, öffentliche Domains nur, wenn sie in ALLOWED_HOSTS stehen
  return h === 'localhost' || /^[0-9.]+$/.test(h) || h.includes(':') || !h.includes('.') || /\.(local|lan|home|internal|home\.arpa|localhost)$/.test(h);
}
function requestAllowed(req) {
  if (!hostAllowed(req.headers.host)) return false;
  const origin = req.headers.origin;
  if (origin && origin !== 'null') { try { if (new URL(origin).host.toLowerCase() !== String(req.headers.host).toLowerCase()) return false; } catch { return false; } }
  else if (origin === 'null' && req.method !== 'GET') return false;
  const site = req.headers['sec-fetch-site'];
  if (site && site !== 'same-origin' && site !== 'none' && req.method !== 'GET') return false;
  return true;
}
const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
};

const assetJobs = {};
function fetchAsset(name) {
  const file = path.join(ASSET_DIR, name);
  if (fs.existsSync(file)) return Promise.resolve(file);
  if (!assetJobs[name]) {
    assetJobs[name] = (async () => {
      const r = await fetch(`${ASSET_BASE}/${ASSETS[name]}`, { signal: AbortSignal.timeout(60000) });
      if (!r.ok) throw new Error(`${name}: HTTP ${r.status}`);
      const buf = Buffer.from(await r.arrayBuffer());
      fs.writeFileSync(file + '.tmp', buf);
      if (name.endsWith('.json')) fs.writeFileSync(file + '.gz', zlib.gzipSync(buf));
      fs.renameSync(file + '.tmp', file);
      return file;
    })().finally(() => { delete assetJobs[name]; });
  }
  return assetJobs[name];
}
async function serveAsset(req, res, name) {
  if (!Object.prototype.hasOwnProperty.call(ASSETS, name)) { res.writeHead(404); return res.end('Not found'); }
  let file;
  try { file = await fetchAsset(name); } catch (e) {
    console.error('Assets konnten nicht geladen werden:', e.message);
    return fail(res, 502, 'Texturen konnten nicht geladen werden (keine Internetverbindung?)');
  }
  const head = { 'Content-Type': MIME[path.extname(name)] || 'image/png', 'Cache-Control': 'public, max-age=86400' };
  if (wantsGzip(req) && fs.existsSync(file + '.gz')) { file += '.gz'; head['Content-Encoding'] = 'gzip'; }
  res.writeHead(200, head);
  fs.createReadStream(file).pipe(res);
}

function readBody(req, limit = 64 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > limit) { reject(new Error('Datei zu groß')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

const fileOf = (id) => path.join(PROJ_DIR, id + '.json');
const schemOf = (id) => path.join(SCHEM_DIR, id + '.litematic');
const validId = (id) => /^[a-f0-9]{12}$/.test(id);
const loadProject = (id) => { try { return migrate(JSON.parse(fs.readFileSync(fileOf(id), 'utf8'))); } catch { return null; } };
// Früher gab es getrennte Zähler für Checkliste und Grundmaterialien, jetzt einen gemeinsamen Bestand
function migrate(p) {
  if (!p.stock) {
    p.stock = {};
    for (const src of [p.collected, p.rawCollected]) for (const k in src || {}) if (src[k] > 0) p.stock[k] = (p.stock[k] || 0) + src[k];
  }
  delete p.collected; delete p.rawCollected; delete p.subtractCollected;
  return p;
}
const allProjects = () => fs.readdirSync(PROJ_DIR).filter((f) => f.endsWith('.json')).map((f) => loadProject(f.slice(0, -5))).filter(Boolean);
const customRecipes = () => { try { return JSON.parse(fs.readFileSync(path.join(DATA, 'recipes.custom.json'), 'utf8')); } catch { return {}; } };
const cleanCounts = (o) => {
  const out = {};
  for (const k in o || {}) { const n = Math.round(Number(o[k])); if (n >= 0 && n < 1e9) out[MC.short(k)] = (out[MC.short(k)] || 0) + n; }
  return out;
};
const saveProject = (p) => { const f = fileOf(p.id); fs.writeFileSync(f + '.tmp', JSON.stringify(p)); fs.renameSync(f + '.tmp', f); };

function summary(p) {
  const total = p.items.reduce((s, i) => s + i.count, 0);
  const st = MC.effStock(p), pl = MC.placedOf(p);
  const done = p.items.reduce((s, i) => s + Math.min((st[i.id] || 0) + (pl[i.id] || 0), i.count), 0);
  return { id: p.id, name: p.name, updated: p.updated, types: p.items.length, total, done, source: p.source && p.source.type, syncTime: (p.sync && p.sync.time) || 0 };
}

function newProject(name, items, source) {
  const now = Date.now();
  return {
    id: crypto.randomBytes(6).toString('hex'), name: name || 'Neues Projekt', created: now, updated: now,
    source: source || { type: 'manuell' }, items: MC.normalizeItems(items),
    stock: {}, stopItems: [], rawIncludeDone: false, notes: '',
  };
}

function itemsFromJson(data) {
  if (Array.isArray(data)) return { name: '', items: data };
  if (data && Array.isArray(data.items)) return { name: data.name || '', items: data.items };
  if (data && typeof data === 'object') {  // {id: count}
    return { name: '', items: Object.keys(data).filter((k) => typeof data[k] === 'number').map((id) => ({ id, count: data[id] })) };
  }
  throw new Error('JSON-Format nicht erkannt (erwartet: {"name":…, "items":[{"id":…,"count":…}]})');
}

async function handleApi(req, res, url) {
  const parts = url.pathname.split('/').filter(Boolean); // ['api', ...]
  const m = req.method;

  if (parts[1] === 'projects' && parts.length === 2) {
    if (m === 'GET') {
      const list = allProjects().map(summary).sort((a, b) => b.updated - a.updated);
      return send(res, 200, list);
    }
    if (m === 'POST') {
      const b = JSON.parse((await readBody(req, 10e6)).toString() || '{}');
      const p = newProject(b.name, b.items || [], { type: 'manuell' });
      saveProject(p);
      return send(res, 201, p);
    }
  }

  if (parts[1] === 'projects' && parts.length === 3) {
    const id = parts[2];
    if (!validId(id)) return fail(res, 400, 'Ungültige ID');
    const p = loadProject(id);
    if (!p) return fail(res, 404, 'Projekt nicht gefunden');
    if (m === 'GET') return send(res, 200, { ...p, has3d: fs.existsSync(schemOf(id)) });
    if (m === 'DELETE') { fs.unlinkSync(fileOf(id)); fs.rmSync(schemOf(id), { force: true }); return send(res, 200, { ok: true }); }
    if (m === 'PUT') {
      const b = JSON.parse((await readBody(req, 20e6)).toString() || '{}');
      if (typeof b.name === 'string' && b.name.trim()) p.name = b.name.trim().slice(0, 120);
      if (Array.isArray(b.items)) p.items = MC.normalizeItems(b.items);
      if (b.stock && typeof b.stock === 'object') p.stock = cleanCounts(b.stock);
      if (b.clearSync === true) delete p.sync;
      if (Array.isArray(b.stopItems)) p.stopItems = b.stopItems.map(String);
      if (typeof b.rawIncludeDone === 'boolean') p.rawIncludeDone = b.rawIncludeDone;
      if (typeof b.notes === 'string') p.notes = b.notes.slice(0, 20000);
      p.updated = Date.now();
      saveProject(p);
      return send(res, 200, summary(p));
    }
  }

  if (parts[1] === 'projects' && parts.length === 4) {
    const id = parts[2];
    if (!validId(id)) return fail(res, 400, 'Ungültige ID');
    if (!fs.existsSync(fileOf(id))) return fail(res, 404, 'Projekt nicht gefunden');
    // Blockpositionen für die 3D-Ansicht
    if (parts[3] === 'voxels' && m === 'GET') {
      if (!fs.existsSync(schemOf(id))) return fail(res, 404, 'Zu diesem Projekt ist keine .litematic-Datei gespeichert');
      try { return sendGz(req, res, parseVoxels(fs.readFileSync(schemOf(id)))); }
      catch (e) { return fail(res, 422, e.message); }
    }
    // .litematic nachträglich an ein bestehendes Projekt hängen (Liste und Fortschritt bleiben unverändert)
    if (parts[3] === 'schematic' && m === 'POST') {
      const buf = await readBody(req);
      try { parseVoxels(buf); } catch (e) { return fail(res, 422, 'Datei konnte nicht gelesen werden: ' + e.message); }
      fs.writeFileSync(schemOf(id), buf);
      return send(res, 200, { ok: true });
    }
  }

  if (parts[1] === 'upload' && m === 'POST') {
    const filename = path.basename(url.searchParams.get('filename') || 'upload');
    const ext = path.extname(filename).toLowerCase();
    const base = filename.replace(/\.[^.]+$/, '');
    const buf = await readBody(req);
    try {
      let p, schem = null;
      if (ext === '.litematic') {
        const r = parseLitematic(buf);
        p = newProject(r.name || base, r.items, { type: 'litematic', filename, meta: r.meta });
        schem = buf;
      } else if (ext === '.json') {
        const r = itemsFromJson(JSON.parse(buf.toString('utf8')));
        const nm = r.name && r.name !== 'Unnamed' ? r.name : base;
        p = newProject(nm, r.items, { type: 'json', filename });
      } else {
        return fail(res, 400, 'Nur .litematic und .json werden unterstützt');
      }
      if (!p.items.length) return fail(res, 422, 'Keine Items in der Datei gefunden');
      saveProject(p);
      if (schem) fs.writeFileSync(schemOf(p.id), schem);
      return send(res, 201, summary(p));
    } catch (e) {
      return fail(res, 422, `„${filename}“ konnte nicht gelesen werden: ${e.message}`);
    }
  }

  if (parts[1] === 'custom-recipes' && m === 'GET') return send(res, 200, customRecipes());

  // Sync aus dem Spiel (Mod): Bestand aus Kisten/Inventar und bereits gesetzte Blöcke je Schematic
  if (parts[1] === 'sync' && m === 'POST') {
    if (SYNC_TOKEN && !sameSecret(req.headers['x-sync-token'], SYNC_TOKEN)) return fail(res, 401, 'Falsches Sync-Token');
    const b = JSON.parse((await readBody(req, 20e6)).toString() || '{}');
    const stock = b.stock && typeof b.stock === 'object' ? cleanCounts(b.stock) : null;
    const norm = (s) => String(s || '').toLowerCase().replace(/\.litematic$/, '').replace(/[\s_\-]+/g, ' ').trim();
    const projects = allProjects();
    const custom = customRecipes();
    const matched = [], unmatched = [];
    for (const pl of Array.isArray(b.placements) ? b.placements : []) {
      const names = [pl.file, pl.name].map(norm).filter((n) => n && n !== 'unnamed');
      const hits = pl.project ? projects.filter((p) => p.id === pl.project)
        : projects.filter((p) => names.includes(norm(p.source && p.source.filename)) || names.includes(norm(p.name)));
      if (!hits.length) { unmatched.push(pl.name || pl.file || '?'); continue; }
      const placed = {};
      for (const e of Array.isArray(pl.placed) ? pl.placed : []) {
        for (const [id, mult] of blockToItems(String(e.name), e.props || {})) placed[id] = (placed[id] || 0) + mult * (Number(e.count) || 0);
      }
      // Bestand je Platzierung (markierte Kisten), sonst der gemeinsame
      const st = pl.stock && typeof pl.stock === 'object' ? cleanCounts(pl.stock) : stock;
      for (const p of hits) {
        const prev = p.sync || {};
        // Handeinträge gelten, bis sich der Wert im Spiel ändert. Dann ist wieder das Spiel massgebend.
        if (st && prev.stock) for (const k in p.stock) if ((st[k] || 0) !== (prev.stock[k] || 0)) delete p.stock[k];
        p.sync = { time: Date.now(), player: String(b.player || '').slice(0, 40), world: String(b.world || '').slice(0, 80), placement: String(pl.name || '').slice(0, 120),
          stock: st || prev.stock || {}, placed: Array.isArray(pl.placed) ? placed : prev.placed || {} };
        saveProject(p);
        const plan = MC.plan(MC.demandOf(p), MC.effStock(p), { custom, stop: p.stopItems });
        const sum = summary(p);
        matched.push({ project: p.id, name: p.name, placement: pl.name || '', percent: sum.total ? Math.round(sum.done / sum.total * 100) : 0, next: plan.next, text: MC.nextText(plan.next) });
      }
    }
    return send(res, 200, { ok: true, matched, unmatched });
  }
  return fail(res, 404, 'Unbekannter Endpunkt');
}

function serveStatic(req, res, url) {
  let rel = decodeURIComponent(url.pathname);
  if (rel === '/') rel = '/index.html';
  const file = path.normalize(path.join(PUBLIC, rel));
  if (!file.startsWith(PUBLIC + path.sep)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  for (const k in SECURITY_HEADERS) res.setHeader(k, SECURITY_HEADERS[k]);
  try {
    if (!requestAllowed(req)) return fail(res, 403, 'Anfrage von fremder Herkunft abgelehnt (siehe ALLOWED_HOSTS in der README)');
    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url);
    if (url.pathname.startsWith('/assets/')) return await serveAsset(req, res, url.pathname.slice(8));
    serveStatic(req, res, url);
  } catch (e) {
    console.error(e);
    if (!res.headersSent) fail(res, 500, e.message);
  }
}).listen(PORT, () => console.log(`Minecraft Materialliste läuft auf http://localhost:${PORT}  (Daten: ${DATA})`));
