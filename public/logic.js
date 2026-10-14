/* Shared logic (browser + Node): recipes, breakdown down to raw materials, stacks, categories */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.MC = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry',
    'pale_oak', 'bamboo', 'crimson', 'warped', 'poplar'];
  const COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
    'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'];

  const short = (id) => String(id).trim().replace(/^minecraft:/, '');
  const R = (out, inn, via) => ({ out, in: inn, via: via || null });

  // Items that count as a "raw material" (no recipe) and can serve as the base for slabs/stairs/walls
  const RAWS = new Set(['cobblestone', 'cobbled_deepslate', 'andesite', 'granite', 'diorite', 'sandstone',
    'red_sandstone', 'blackstone', 'prismarine', 'tuff', 'mud_bricks', 'end_stone', 'purpur_block']);

  const EXPLICIT = {
    '#planks': R(4, { '#logs': 1 }),
    stick: R(4, { '#planks': 2 }),
    // Redstone
    piston: R(1, { '#planks': 3, cobblestone: 4, iron_ingot: 1, redstone: 1 }),
    sticky_piston: R(1, { piston: 1, slime_ball: 1 }),
    slime_block: R(1, { slime_ball: 9 }),
    redstone_block: R(1, { redstone: 9 }),
    redstone_torch: R(1, { redstone: 1, stick: 1 }),
    comparator: R(1, { redstone_torch: 3, quartz: 1, stone: 3 }),
    repeater: R(1, { redstone_torch: 2, redstone: 1, stone: 3 }),
    observer: R(1, { cobblestone: 6, redstone: 2, quartz: 1 }),
    dispenser: R(1, { cobblestone: 7, bow: 1, redstone: 1 }),
    dropper: R(1, { cobblestone: 7, redstone: 1 }),
    hopper: R(1, { iron_ingot: 5, chest: 1 }),
    bow: R(1, { stick: 3, string: 3 }),
    lever: R(1, { stick: 1, cobblestone: 1 }),
    stone_button: R(1, { stone: 1 }),
    stone_pressure_plate: R(1, { stone: 2 }),
    tripwire_hook: R(2, { iron_ingot: 1, stick: 1, '#planks': 1 }),
    tnt: R(1, { gunpowder: 5, sand: 4 }),
    note_block: R(1, { '#planks': 8, redstone: 1 }),
    rail: R(16, { iron_ingot: 6, stick: 1 }),
    powered_rail: R(6, { gold_ingot: 6, stick: 1, redstone: 1 }),
    detector_rail: R(6, { iron_ingot: 6, stone_pressure_plate: 1, redstone: 1 }),
    activator_rail: R(6, { iron_ingot: 6, stick: 2, redstone_torch: 1 }),
    minecart: R(1, { iron_ingot: 5 }),
    redstone_lamp: R(1, { redstone: 4, glowstone: 1 }),
    glowstone: R(1, { glowstone_dust: 4 }),
    copper_bulb: R(4, { copper_ingot: 3, blaze_rod: 1, redstone: 1 }),
    lightning_rod: R(1, { copper_ingot: 3 }),
    // Furniture / light
    chest: R(1, { '#planks': 8 }),
    trapped_chest: R(1, { chest: 1, tripwire_hook: 1 }),
    crafting_table: R(1, { '#planks': 4 }),
    furnace: R(1, { cobblestone: 8 }),
    ladder: R(3, { stick: 7 }),
    bookshelf: R(1, { '#planks': 6, book: 3 }),
    torch: R(4, { coal: 1, stick: 1 }),
    soul_torch: R(4, { coal: 1, stick: 1, soul_soil: 1 }),
    lantern: R(1, { iron_nugget: 8, torch: 1 }),
    soul_lantern: R(1, { iron_nugget: 8, soul_torch: 1 }),
    chain: R(1, { iron_nugget: 2, iron_ingot: 1 }),
    candle: R(1, { string: 1, honeycomb: 1 }),
    // Metal
    iron_nugget: R(9, { iron_ingot: 1 }),
    iron_block: R(1, { iron_ingot: 9 }),
    gold_block: R(1, { gold_ingot: 9 }),
    copper_block: R(1, { copper_ingot: 9 }),
    cut_copper: R(4, { copper_block: 4 }),
    diamond_block: R(1, { diamond: 9 }),
    emerald_block: R(1, { emerald: 9 }),
    lapis_block: R(1, { lapis_lazuli: 9 }),
    coal_block: R(1, { coal: 9 }),
    iron_door: R(3, { iron_ingot: 6 }),
    iron_trapdoor: R(1, { iron_ingot: 4 }),
    iron_bars: R(16, { iron_ingot: 6 }),
    bucket: R(1, { iron_ingot: 3 }),
    // Stone / glass
    stone: R(1, { cobblestone: 1 }, 'smelt'),
    smooth_stone: R(1, { stone: 1 }, 'smelt'),
    stone_bricks: R(4, { stone: 4 }),
    polished_deepslate: R(4, { cobbled_deepslate: 4 }),
    deepslate_bricks: R(4, { polished_deepslate: 4 }),
    deepslate_tiles: R(4, { deepslate_bricks: 4 }),
    quartz_block: R(1, { quartz: 4 }),
    glass: R(1, { sand: 1 }, 'smelt'),
    glass_pane: R(16, { glass: 6 }),
    shulker_box: R(1, { shulker_shell: 2, chest: 1 }),
  };

  const SPECIAL_NAMES = { '#planks': 'Any Planks', '#logs': 'Any Logs' };

  /* ---------- Villager trades (Java 1.14+ prices; the real price varies with level, demand and discounts) ---------- */
  // [villager, item, count, inputs]. Villagers selling items for emeralds, and buying items for one emerald.
  const SELLS = [
    ['farmer', 'bread', 6, 1], ['farmer', 'pumpkin_pie', 4, 1], ['farmer', 'apple', 4, 1], ['farmer', 'cookie', 18, 3], ['farmer', 'cake', 1, 1],
    ['farmer', 'golden_carrot', 3, 3], ['farmer', 'glistering_melon_slice', 3, 4],
    ['leatherworker', 'leather_chestplate', 1, 7], ['leatherworker', 'leather_leggings', 1, 3], ['leatherworker', 'leather_helmet', 1, 5],
    ['leatherworker', 'leather_boots', 1, 4], ['leatherworker', 'leather_horse_armor', 1, 6], ['leatherworker', 'saddle', 1, 6],
    ['fisherman', 'cooked_cod', 6, { emerald: 1, cod: 6 }], ['fisherman', 'cod_bucket', 1, 3], ['fisherman', 'campfire', 1, 2],
    ['fisherman', 'cooked_salmon', 6, { emerald: 1, salmon: 6 }],
    ['librarian', 'bookshelf', 1, 9], ['librarian', 'lantern', 1, 1], ['librarian', 'glass', 4, 1], ['librarian', 'clock', 1, 5],
    ['librarian', 'compass', 1, 4], ['librarian', 'name_tag', 1, 20],
    ['armorer', 'iron_boots', 1, 4], ['armorer', 'iron_leggings', 1, 7], ['armorer', 'iron_helmet', 1, 5], ['armorer', 'iron_chestplate', 1, 9],
    ['armorer', 'bell', 1, 36], ['armorer', 'chainmail_leggings', 1, 3], ['armorer', 'chainmail_boots', 1, 1], ['armorer', 'chainmail_helmet', 1, 1],
    ['armorer', 'chainmail_chestplate', 1, 4], ['armorer', 'shield', 1, 5],
    ['toolsmith', 'stone_axe', 1, 1], ['toolsmith', 'stone_shovel', 1, 1], ['toolsmith', 'stone_pickaxe', 1, 1], ['toolsmith', 'stone_hoe', 1, 1], ['toolsmith', 'bell', 1, 36],
    ['weaponsmith', 'iron_axe', 1, 3], ['weaponsmith', 'bell', 1, 36],
    ['fletcher', 'arrow', 16, 1], ['fletcher', 'flint', 10, { emerald: 1, gravel: 10 }], ['fletcher', 'bow', 1, 2], ['fletcher', 'crossbow', 1, 3],
    ['cartographer', 'map', 1, 7], ['cartographer', 'item_frame', 1, 7],
    ['cleric', 'redstone', 2, 1], ['cleric', 'lapis_lazuli', 1, 1], ['cleric', 'glowstone', 1, 4], ['cleric', 'ender_pearl', 1, 5], ['cleric', 'experience_bottle', 1, 3],
    ['mason', 'brick', 10, 1], ['mason', 'chiseled_stone_bricks', 4, 1], ['mason', 'polished_andesite', 4, 1], ['mason', 'polished_diorite', 4, 1],
    ['mason', 'polished_granite', 4, 1], ['mason', 'terracotta', 1, 1], ['mason', 'quartz_pillar', 1, 1], ['mason', 'quartz_block', 1, 1],
    ['butcher', 'rabbit_stew', 1, 1], ['butcher', 'cooked_porkchop', 5, 1], ['butcher', 'cooked_chicken', 8, 1],
    ['shepherd', 'shears', 1, 2], ['shepherd', 'painting', 3, 2],
    ['wandering_trader', 'sand', 8, 1], ['wandering_trader', 'red_sand', 4, 1], ['wandering_trader', 'slime_ball', 1, 4], ['wandering_trader', 'blue_ice', 1, 6],
  ];
  for (const c of COLORS) {
    SELLS.push(['mason', c + '_terracotta', 1, 1], ['mason', c + '_glazed_terracotta', 1, 1], ['cartographer', c + '_banner', 1, 3],
      ['shepherd', c + '_wool', 1, 1], ['shepherd', c + '_carpet', 4, 1], ['shepherd', c + '_bed', 1, 3], ['shepherd', c + '_banner', 1, 3]);
  }
  const BUYS = [
    ['farmer', 'wheat', 20], ['farmer', 'potato', 26], ['farmer', 'carrot', 22], ['farmer', 'beetroot', 15], ['farmer', 'pumpkin', 6], ['farmer', 'melon', 4],
    ['leatherworker', 'leather', 6], ['leatherworker', 'flint', 26], ['leatherworker', 'rabbit_hide', 9], ['leatherworker', 'turtle_scute', 4],
    ['fisherman', 'string', 20], ['fisherman', 'coal', 10], ['fisherman', 'cod', 15], ['fisherman', 'salmon', 13], ['fisherman', 'tropical_fish', 6], ['fisherman', 'pufferfish', 4],
    ['librarian', 'paper', 24], ['librarian', 'book', 4], ['librarian', 'ink_sac', 5],
    ['armorer', 'coal', 15], ['armorer', 'iron_ingot', 4], ['armorer', 'lava_bucket', 1], ['armorer', 'diamond', 1],
    ['toolsmith', 'coal', 15], ['toolsmith', 'iron_ingot', 4], ['toolsmith', 'flint', 30], ['toolsmith', 'diamond', 1],
    ['weaponsmith', 'coal', 15], ['weaponsmith', 'iron_ingot', 4], ['weaponsmith', 'flint', 24], ['weaponsmith', 'diamond', 1],
    ['fletcher', 'stick', 32], ['fletcher', 'flint', 26], ['fletcher', 'string', 14], ['fletcher', 'feather', 24], ['fletcher', 'tripwire_hook', 8],
    ['cartographer', 'paper', 24], ['cartographer', 'glass_pane', 11], ['cartographer', 'compass', 1],
    ['cleric', 'rotten_flesh', 32], ['cleric', 'gold_ingot', 3], ['cleric', 'rabbit_foot', 2], ['cleric', 'turtle_scute', 4], ['cleric', 'glass_bottle', 9], ['cleric', 'nether_wart', 22],
    ['mason', 'clay_ball', 10], ['mason', 'stone', 20], ['mason', 'granite', 16], ['mason', 'andesite', 16], ['mason', 'diorite', 16], ['mason', 'quartz', 12],
    ['butcher', 'chicken', 14], ['butcher', 'porkchop', 7], ['butcher', 'rabbit', 4], ['butcher', 'mutton', 7], ['butcher', 'beef', 10],
    ['butcher', 'dried_kelp_block', 10], ['butcher', 'sweet_berries', 10],
    ['shepherd', 'white_wool', 18],
  ];
  const VILLAGERS = { wandering_trader: 'Wandering Trader' };
  const villagerName = (v) => VILLAGERS[v] || v.charAt(0).toUpperCase() + v.slice(1);
  /** All trades that give this item: [{ key, villager, out, in, label }] */
  const TRADES = {};
  const addTrade = (villager, id, out, inn, key) => {
    const t = { key, villager, out, in: inn, via: 'trade with ' + villagerName(villager) };
    (TRADES[id] = TRADES[id] || []).push(t);
  };
  for (const [v, id, out, inn] of SELLS) addTrade(v, id, out, typeof inn === 'number' ? { emerald: inn } : inn, 'trade:' + v);
  for (const [v, id, n] of BUYS) addTrade(v, 'emerald', 1, { [id]: n }, `trade:${v}:${id}`);
  const tradesFor = (id) => TRADES[id] || [];

  /* ---------- Where to find raw materials ---------- */
  const WHERE = [
    [/^cobblestone$/, 'Mine stone anywhere in the Overworld (or a cobblestone generator)'],
    [/^stone$/, 'Mine stone with Silk Touch, or smelt cobblestone'],
    [/^(cobbled_deepslate|deepslate)$/, 'Mine deepslate below Y=0'],
    [/^(andesite|diorite|granite)$/, 'Patches in stone all over the Overworld'],
    [/^tuff$/, 'Below Y=16 near deepslate, and in trial chambers'],
    [/^calcite$/, 'Amethyst geodes and stony peaks'],
    [/^(dirt|coarse_dirt|grass_block|podzol|rooted_dirt)$/, 'Overworld surface (grass block and podzol need Silk Touch)'],
    [/^(sand|sandstone)$/, 'Deserts and beaches'],
    [/^(red_sand|red_sandstone)$/, 'Badlands'],
    [/^gravel$/, 'Rivers, oceans, gravel patches underground'],
    [/^(clay_ball|clay)$/, 'Rivers, lakes and lush caves (clay blocks under water)'],
    [/^(ice|packed_ice|blue_ice|snowball|snow_block|powder_snow_bucket)$/, 'Frozen and snowy biomes, icebergs'],
    [/^obsidian$/, 'Pour water on lava, mine with a diamond pickaxe; ruined portals'],
    [/^(netherrack|nether_bricks?|nether_brick)$/, 'Nether'],
    [/^(soul_sand|soul_soil)$/, 'Nether: soul sand valley'],
    [/^(basalt|blackstone)$/, 'Nether: basalt deltas (blackstone also in bastions)'],
    [/^(glowstone_dust|glowstone)$/, 'Nether: glowstone clusters on the ceiling (drops 2–4 dust, Silk Touch for blocks); witches drop dust'],
    [/^quartz$/, 'Nether: nether quartz ore'],
    [/^nether_wart$/, 'Nether fortresses (farm it on soul sand)'],
    [/^(magma_cream|magma_block)$/, 'Nether: magma cubes / basalt deltas'],
    [/^blaze_rod$/, 'Nether fortresses: blazes'],
    [/^ghast_tear$/, 'Nether: ghasts'],
    [/^(end_stone|purpur_block|chorus_fruit)$/, 'The End'],
    [/^shulker_shell$/, 'End cities: shulkers'],
    [/^(prismarine_shard|prismarine_crystals|prismarine|sponge|wet_sponge|sea_lantern)$/, 'Ocean monuments (guardians)'],
    [/^coal$/, 'Coal ore (common, mostly above Y=0)'],
    [/^(iron_ingot|raw_iron|iron_nugget)$/, 'Iron ore (smelt raw iron), or an iron golem farm'],
    [/^(gold_ingot|raw_gold|gold_nugget)$/, 'Gold ore (lots in badlands), or a gold farm with piglins'],
    [/^(copper_ingot|raw_copper)$/, 'Copper ore, lots in dripstone caves'],
    [/^diamond$/, 'Diamond ore, best around Y=-58'],
    [/^emerald$/, 'Emerald ore in mountains (rare) – easier: sell items to villagers (choose a trade)'],
    [/^lapis_lazuli$/, 'Lapis ore, best around Y=0'],
    [/^redstone$/, 'Redstone ore deep underground (best below Y=-32); witches drop it'],
    [/^amethyst_shard$/, 'Amethyst geodes'],
    [/^(netherite_scrap|ancient_debris)$/, 'Nether: ancient debris around Y=15'],
    [/(_log|_wood|^#logs)$/, 'Trees in the Overworld'],
    [/(_stem|_hyphae)$/, 'Nether: crimson and warped forests'],
    [/^string$/, 'Spiders, cobwebs (mineshafts), or a spider farm'],
    [/^slime_ball$/, 'Slimes in swamps and slime chunks'],
    [/^(honeycomb|honey_bottle)$/, 'Bee nests (use shears / a glass bottle)'],
    [/^gunpowder$/, 'Creepers, or a creeper farm'],
    [/^(bone|bone_meal)$/, 'Skeletons, or a composter'],
    [/^ender_pearl$/, 'Endermen'],
    [/^(feather|chicken)$/, 'Chickens'],
    [/^(leather|beef)$/, 'Cows'],
    [/^(white_wool|mutton)$/, 'Sheep (shears give more wool)'],
    [/^(ink_sac)$/, 'Squid'], [/^glow_ink_sac$/, 'Glow squid'],
    [/^(wheat|wheat_seeds|carrot|potato|beetroot)$/, 'Farm it (villages have farms)'],
    [/^(sugar_cane|bamboo|cactus|kelp|vine|lily_pad)$/, 'Grows naturally, easy to farm'],
    [/^(pumpkin|melon)$/, 'Farm it, or find it in the wild'],
    [/(mushroom)$/, 'Dark places, swamps, mushroom fields'],
    [/^(moss_block|azalea|flowering_azalea)$/, 'Lush caves'],
    [/^(mud|mangrove_roots)$/, 'Mangrove swamps (mud: water bottle on dirt)'],
    [/^(terracotta)$/, 'Badlands'],
    [/_dye$/, 'Crafted from flowers and other plants'],
    [/^rotten_flesh$/, 'Zombies'],
    [/^sweet_berries$/, 'Taiga'],
  ];
  function whereOf(id) {
    const s = short(id);
    for (const [re, text] of WHERE) if (re.test(s)) return text;
    return '';
  }

  function logOf(w) {
    if (w === 'crimson' || w === 'warped') return w + '_stem';
    if (w === 'bamboo') return 'bamboo_block';
    return w + '_log';
  }

  function baseFor(b) {
    for (const c of [b, b + 's', b + '_block']) if (EXPLICIT[c] || RAWS.has(c)) return c;
    if (/(brick|tile)$/.test(b)) return b + 's';
    return b;
  }

  function makeCtx(opts, ids) {
    opts = opts || {};
    const woods = new Set(WOODS);
    (opts.woods || []).forEach((w) => woods.add(w));
    (ids || []).forEach((id) => {
      const m = short(id).replace(/^stripped_/, '').match(/^(.+)_(log|stem|planks)$/);
      if (m) woods.add(m[1]);
    });
    return { custom: opts.custom || {}, methods: opts.methods || {}, stop: opts.stop instanceof Set ? opts.stop : new Set(opts.stop || []), woods };
  }

  /** How the player wants to get an item: 'craft', 'gather' or a trade key (see tradesFor). Empty = default (craft if possible). */
  function recipeFor(id, ctx) {
    ctx = ctx || makeCtx();
    const how = ctx.methods && Object.prototype.hasOwnProperty.call(ctx.methods, id) ? ctx.methods[id] : '';
    if (how === 'gather') return null;
    if (how.startsWith('trade:')) {
      const t = tradesFor(id).find((x) => x.key === how);
      if (t) return R(t.out, t.in, t.via);
    }
    return defaultRecipe(id, ctx);
  }

  /** The recipe without the player's choice */
  function defaultRecipe(id, ctx) {
    if (ctx.custom && Object.prototype.hasOwnProperty.call(ctx.custom, id)) {
      const c = ctx.custom[id];
      return c ? R(c.out || 1, c.in || {}, c.via) : null;
    }
    if (EXPLICIT[id]) return EXPLICIT[id];
    let m;
    if ((m = id.match(/^stripped_(.+)$/))) return R(1, { [m[1]]: 1 }, 'strip');
    if ((m = id.match(/^waxed_(.+)$/))) return R(1, { [m[1] === 'copper' ? 'copper_block' : m[1]]: 1, honeycomb: 1 }, 'wax');
    if ((m = id.match(/^(exposed|weathered|oxidized)_(.+)$/))) return R(1, { [m[2] === 'copper' ? 'copper_block' : m[2]]: 1 }, 'oxidize');
    for (const c of COLORS) {
      if (id === c + '_concrete') return R(1, { [c + '_concrete_powder']: 1 }, 'add water');
      if (id === c + '_concrete_powder') return R(8, { sand: 4, gravel: 4, [c + '_dye']: 1 });
    }
    if ((m = id.match(/^(.+?)_(planks|trapdoor|door|slab|stairs|sign|hanging_sign|shelf|fence_gate|fence|button|pressure_plate|boat|wood)$/)) && ctx.woods.has(m[1])) {
      const w = m[1];
      const planks = w + '_planks';
      const log = logOf(w);
      switch (m[2]) {
        case 'planks': return R(w === 'bamboo' ? 2 : 4, { [log]: 1 });
        case 'trapdoor': return R(2, { [planks]: 6 });
        case 'door': return R(3, { [planks]: 6 });
        case 'slab': return R(6, { [planks]: 3 });
        case 'stairs': return R(4, { [planks]: 6 });
        case 'sign': return R(3, { [planks]: 6, stick: 1 });
        case 'hanging_sign': return R(6, { ['stripped_' + log]: 6, chain: 2 });
        case 'shelf': return R(6, { ['stripped_' + log]: 6 });
        case 'fence': return R(3, { [planks]: 4, stick: 2 });
        case 'fence_gate': return R(1, { [planks]: 2, stick: 4 });
        case 'button': return R(1, { [planks]: 1 });
        case 'pressure_plate': return R(1, { [planks]: 2 });
        case 'boat': return R(1, { [planks]: 5 });
        case 'wood': return R(3, { [log]: 4 });
      }
    }
    if ((m = id.match(/^(.+)_(slab|stairs|wall)$/))) {
      const base = baseFor(m[1]);
      if (m[2] === 'slab') return R(6, { [base]: 3 });
      if (m[2] === 'stairs') return R(4, { [base]: 6 });
      return R(6, { [base]: 6 });
    }
    return null;
  }

  function getRecipe(id, ctx) {
    if (ctx.stop.has(id)) return null;
    return recipeFor(id, ctx);
  }

  /** demand: {id: count}. Returns raw materials + crafting steps (rounding up only after aggregating per intermediate product). */
  function resolve(demand, opts) {
    const ids0 = Object.keys(demand).filter((k) => demand[k] > 0);
    const ctx = makeCtx(opts, ids0);
    const depth = {};
    const rec = {};
    const visiting = new Set();
    function dep(id) {
      if (id in depth) return depth[id];
      if (visiting.has(id)) return 0;
      visiting.add(id);
      const r = getRecipe(id, ctx);
      rec[id] = r;
      let d = 0;
      if (r) for (const k in r.in) d = Math.max(d, dep(k) + 1);
      visiting.delete(id);
      depth[id] = d;
      return d;
    }
    ids0.forEach(dep);
    const order = Object.keys(depth).sort((a, b) => depth[b] - depth[a]);
    const need = {};
    ids0.forEach((k) => (need[k] = demand[k]));
    const raw = {};
    const crafts = {};
    for (const id of order) {
      const n = need[id] || 0;
      if (n <= 0) continue;
      const r = rec[id];
      if (!r) { raw[id] = (raw[id] || 0) + n; continue; }
      const c = Math.ceil(n / r.out);
      crafts[id] = { crafts: c, produced: c * r.out, needed: n, surplus: c * r.out - n, recipe: r };
      for (const k in r.in) need[k] = (need[k] || 0) + c * r.in[k];
    }
    return { raw, crafts };
  }

  // Group names: every matching type in stock counts
  const TAGS = { '#planks': /_planks$/, '#logs': /(_log|_stem|_wood|_hyphae)$/ };

  /**
   * Plan with stock: demand = what is still needed, stock = what is available (raw material, intermediate or final product alike).
   * Stock covers the direct demand first, the rest is used for crafting.
   * Returns a node per item { need, final, have, used, missing, crafts, can (craftable now), inputs } and ready-made lists.
   */
  function plan(demand, stock, opts) {
    stock = stock || {};
    const ids0 = Object.keys(demand).filter((k) => demand[k] > 0);
    const ctx = makeCtx(opts, ids0.concat(Object.keys(stock)));
    const depth = {}, rec = {}, visiting = new Set();
    function dep(id) {
      if (id in depth) return depth[id];
      if (visiting.has(id)) return 0;
      visiting.add(id);
      const r = getRecipe(id, ctx);
      rec[id] = r;
      let d = 0;
      if (r) for (const k in r.in) d = Math.max(d, dep(k) + 1);
      visiting.delete(id);
      depth[id] = d;
      return d;
    }
    ids0.forEach(dep);
    const isTag = (id) => (TAGS[id] ? 1 : 0);
    const order = Object.keys(depth).sort((a, b) => depth[b] - depth[a] || isTag(a) - isTag(b));
    const need = {}, left = {}, nodes = {}, tagFrom = {};
    ids0.forEach((k) => (need[k] = demand[k]));
    for (const k in stock) if (stock[k] > 0) left[k] = stock[k];
    for (const id of order) {
      const n = need[id] || 0;
      const own = left[id] || 0;
      const extra = TAGS[id] ? Object.keys(left).filter((k) => left[k] > 0 && TAGS[id].test(k)) : [];
      const have = own + extra.reduce((s, k) => s + left[k], 0);
      const used = Math.min(n, have);
      let u = used - Math.min(used, own);
      left[id] = own - Math.min(used, own);
      for (const k of extra) { const t = Math.min(u, left[k]); if (t) { left[k] -= t; u -= t; (tagFrom[id] = tagFrom[id] || {})[k] = t; } }
      const r = rec[id] || null;
      const final = demand[id] > 0 ? demand[id] : 0;
      const node = { id, depth: depth[id], need: n, final, have, used, missing: n - used, recipe: r, crafts: 0, produced: 0, can: 0, inputs: [], uses: [],
        build: Math.min(final, used), forCraft: used - Math.min(final, used), surplus: have - used };
      if (r && node.missing > 0) {
        node.crafts = Math.ceil(node.missing / r.out);
        node.produced = node.crafts * r.out;
        for (const k in r.in) need[k] = (need[k] || 0) + node.crafts * r.in[k];
      }
      nodes[id] = node;
    }
    // What can be done right now? Stock that isn't reserved for the direct demand gets distributed.
    const pool = {};
    for (const id of order) pool[id] = Math.max(0, nodes[id].used - Math.min(nodes[id].final, nodes[id].used));
    const crafting = order.filter((id) => nodes[id].crafts > 0);
    for (const id of crafting) {
      const nd = nodes[id];
      let can = nd.crafts;
      for (const k in nd.recipe.in) {
        const per = nd.recipe.in[k], want = nd.crafts * per;
        nd.inputs.push({ id: k, need: want, have: Math.min(pool[k] || 0, want) });
        nodes[k].uses.push({ id, amount: want });
        can = Math.min(can, Math.floor((pool[k] || 0) / per));
      }
      nd.can = can;
      if (can) for (const k in nd.recipe.in) pool[k] -= can * nd.recipe.in[k];
    }
    const byNeed = (a, b) => nodes[b].need - nodes[a].need || (a < b ? -1 : 1);
    const raws = order.filter((id) => !nodes[id].recipe && nodes[id].need > 0).sort(byNeed);
    const res = {
      nodes, tagFrom,
      // closest to the finished block first: freshly crafted intermediates get processed further right away
      craftNow: crafting.filter((id) => nodes[id].can > 0),
      waiting: crafting.filter((id) => nodes[id].can < nodes[id].crafts).reverse(),
      gather: raws.filter((id) => nodes[id].missing > 0),
      done: order.filter((id) => nodes[id].need > 0 && nodes[id].missing === 0).sort(byNeed),
    };
    res.next = res.craftNow.length ? { type: 'craft', id: res.craftNow[0], count: nodes[res.craftNow[0]].can * nodes[res.craftNow[0]].recipe.out, crafts: nodes[res.craftNow[0]].can, via: nodes[res.craftNow[0]].recipe.via }
      : res.gather.length ? { type: 'gather', id: res.gather[0], count: nodes[res.gather[0]].missing }
      : ids0.length ? { type: 'build' } : { type: 'done' };
    return res;
  }

  /** Stock of a project: sync from the game, manually entered values take precedence */
  function effStock(p) {
    const s = Object.assign({}, p.sync && p.sync.stock);
    for (const k in p.stock || {}) s[k] = p.stock[k];
    return s;
  }
  const placedOf = (p) => (p.sync && p.sync.placed) || {};
  /** What is still needed: list minus blocks already placed */
  function demandOf(p) {
    const pl = placedOf(p), d = {};
    for (const i of p.items) { const n = i.count - (pl[i.id] || 0); if (n > 0) d[i.id] = n; }
    return d;
  }
  /** Next step as text (for the mod and the website) */
  function nextText(next) {
    if (next.type === 'craft') return `${next.via ? next.via.charAt(0).toUpperCase() + next.via.slice(1) : 'Craft'}: ${next.count}× ${displayName(next.id)}`;
    if (next.type === 'gather') return `Gather: ${displayName(next.id)} (${next.count} left)`;
    if (next.type === 'build') return 'All materials are here – time to build';
    return 'Done';
  }

  /** Tree for a single item (the root is always broken down). */
  function tree(id, count, opts) {
    const ctx = makeCtx(opts, [id]);
    function go(id, n, path, isRoot) {
      const r = isRoot ? recipeFor(id, ctx) : getRecipe(id, ctx);
      if (!r || path.includes(id)) return { id, count: n, children: [] };
      const c = Math.ceil(n / r.out);
      return {
        id, count: n, crafts: c, produced: c * r.out, out: r.out, via: r.via,
        children: Object.keys(r.in).map((k) => go(k, c * r.in[k], path.concat(id), false)),
      };
    }
    return go(id, count, [], true);
  }

  function hasRecipe(id, opts) { return !!recipeFor(id, makeCtx(opts, [id])); }
  function canCraft(id, opts) { return !!defaultRecipe(id, makeCtx(opts, [id])); }

  function stackSize(id) {
    id = short(id);
    if (id === 'bucket' || /(^|_)(sign|hanging_sign|banner)$/.test(id)) return 16;
    if (['ender_pearl', 'snowball', 'egg', 'honey_bottle', 'armor_stand'].includes(id)) return 16;
    if (/_(sword|pickaxe|axe|shovel|hoe|helmet|chestplate|leggings|boots|boat|raft|minecart|bed)$/.test(id)) return 1;
    if (/(shulker_box|potion|_bucket)$/.test(id)) return 1;
    if (/^(minecart|cake|saddle|elytra|shield|bow|crossbow|fishing_rod|flint_and_steel|shears|trident|mace|totem_of_undying|music_disc_.*)$/.test(id)) return 1;
    return 64;
  }

  function displayName(id) {
    if (SPECIAL_NAMES[id]) return SPECIAL_NAMES[id];
    const s = short(id).replace(/^[a-z0-9_.-]+:/, '');
    return s.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
  }

  const CATS = [
    ['Redstone', /redstone|piston|comparator|repeater|observer|hopper|dropper|dispenser|lever|button|pressure_plate|rail|tripwire|copper_bulb|lamp|target|daylight|note_block|slime|honey|tnt/],
    ['Lighting', /lantern|torch|glowstone|candle|shroomlight|sea_lantern/],
    ['Glass', /glass/],
    ['Metal & Ore', /^(iron|gold|copper|diamond|emerald|netherite|coal|lapis|amethyst|quartz|chain)|_ingot|_nugget|raw_/],
    ['Wood & Furniture', /planks|_log$|_wood$|_stem$|trapdoor|_sign|shelf|fence|_door|stripped|bamboo|chest|barrel|crafting|ladder|^stick$|bookshelf|boat|#/],
    ['Stone & Earth', /stone|deepslate|brick|cobble|andesite|granite|diorite|tuff|concrete|obsidian|basalt|blackstone|sandstone|terracotta|prismarine|end_stone|netherrack|soul_soil|dirt|sand|gravel|calcite|slab|stairs|wall/],
  ];
  function categoryOf(id) {
    const s = short(id);
    for (const [name, re] of CATS) if (re.test(s)) return name;
    return 'Other';
  }
  const CATEGORY_NAMES = CATS.map((c) => c[0]).concat('Other');

  function hueOf(id) {
    let h = 0;
    const s = short(id);
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
    return h;
  }

  /** Merge/normalize an item list -> [{id,count}] */

  function normalizeItems(list) {
    const m = {};
    for (const it of list || []) {
      if (!it || !it.id) continue;
      const id = short(it.id);
      const n = Math.round(Number(it.count));
      if (!id || !(n > 0)) continue;
      m[id] = (m[id] || 0) + n;
    }
    return Object.keys(m).map((id) => ({ id, count: m[id] }));
  }

  const slotsFor = (id, n) => Math.ceil(n / stackSize(id));

  return { WOODS, TAGS, short, recipeFor, tradesFor, whereOf, canCraft, resolve, plan, effStock, placedOf, demandOf, nextText, tree, hasRecipe, stackSize, displayName, categoryOf, CATEGORY_NAMES, hueOf, normalizeItems, slotsFor };
});
