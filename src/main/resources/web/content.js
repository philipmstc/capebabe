// The world's content as the Java client builds it (model/WorldContent.java), from the same batches in the same
// order, so names, split-off skills and tiers come out the same -- plus the game's tables (levels, tiers, stacks)
// from the server's meta.json. Positions are "x,y" keys.

export const key = (x, y) => x + ',' + y;

// Tiers.OLD_LEVEL_NEEDED: defaults old worlds were made with, moved to today's levels as the history is applied
const OLD_LEVEL_NEEDED = [[0], [20], [35, 40], [50, 60], [70, 85]];

export class Content {
  constructor(meta) {
    this.meta = meta;
    this.version = 0;
    this.skills = [];
    this.skillsById = new Map();
    this.products = new Map();
    this.lines = new Map();
    this.recipes = new Map();
    this.monsters = [];
    this.monstersById = new Map();
    this.nodes = new Map();
    this.nodesAt = new Map();
    this.buildings = [];
    this.buildingsAt = new Map();   // every tile of every building
    this.origins = new Map();
    this.splitFrom = new Map();     // split-off skill -> the skill it came from
    this.variants = new Map();      // "baseId/prestige" -> product
  }

  apply(batch) {
    if (batch.version !== this.version + 1) return;   // already have it (Welcome after ContentAdded)
    const list = name => batch[name] || [];
    if (batch.origin) {
      for (const def of [...list('skills'), ...list('nodes'), ...list('monsters')]) this.origins.set(def.id, batch.origin);
    }
    for (const def of list('skills')) {
      const clash = this.skills.find(s => this.splitFrom.has(s) && s.name === def.name);
      const skill = { id: def.id, name: def.name, type: def.type, colorIndex: def.colorIndex, symbol: def.symbol, line: null };
      this.skills.push(skill);
      this.skillsById.set(def.id, skill);
      if (clash) {
        clash.name = this.nextSkillName();
        if (clash.line) clash.line.recipes.forEach(r => this.name(r));
      }
    }
    for (const def of list('products')) {
      const base = def.prestige > 0 ? this.products.get(def.baseId) : null;
      const blesses = def.blessesRecipeId > 0 ? this.recipes.get(def.blessesRecipeId) : null;
      const product = {
        id: def.id, tier: def.tier, base, prestige: def.prestige || 0, blesses,
        name: base ? base.name + '+' + def.prestige : blesses ? 'blessing of ' + blesses.product.name : def.name,
      };
      this.products.set(def.id, product);
      if (base) this.variants.set(base.id + '/' + product.prestige, product);
    }
    for (const def of list('productLines')) {
      let skill = this.skillsById.get(def.skillId);
      if (skill.line) skill = this.splitOff(skill, def.id);   // one line a skill: an old world's extra line is a skill
      const line = { id: def.id, skill, recipes: [] };
      skill.line = line;
      this.lines.set(def.id, line);
    }
    for (const def of list('recipes')) {
      const line = this.lines.get(def.productLineId);
      const tier = line.recipes.length;
      const saved = def.levelNeeded;
      const components = [];
      for (const [productId, count] of Object.entries(def.components || {})) {
        components.push({ product: this.products.get(+productId), count });
      }
      const recipe = {
        id: def.id, skill: line.skill, line, tier, product: this.products.get(def.productId), expGained: def.expGained,
        levelNeeded: tier < OLD_LEVEL_NEEDED.length && OLD_LEVEL_NEEDED[tier].includes(saved) ? this.meta.levelNeeded[tier] : saved,
        components,
      };
      line.recipes.push(recipe);
      this.recipes.set(def.id, recipe);
      this.name(recipe);
    }
    for (const def of list('monsters')) {
      const monster = { ...def, drops: [] };
      this.monsters.push(monster);
      this.monstersById.set(def.id, monster);
    }
    for (const def of list('drops')) {
      this.monstersById.get(def.monsterId).drops.push({ product: this.products.get(def.productId), oneIn: def.oneIn, foundBy: def.foundBy });
    }
    for (const def of list('nodes')) {
      const node = { id: def.id, x: def.x, y: def.y, recipe: this.recipes.get(def.recipeId), capacity: def.capacity,
        maxCharges: def.maxCharges, respawnTicks: def.respawnTicks };
      this.nodes.set(node.id, node);
      this.nodesAt.set(key(node.x, node.y), node);
    }
    for (const def of list('buildings')) this.addBuilding(def);
    this.version = batch.version;
  }

  splitOff(from, lineId) {
    const split = { id: lineId, name: this.nextSkillName(), type: from.type, colorIndex: this.skills.length, symbol: from.symbol, line: null };
    this.skills.push(split);
    this.skillsById.set(lineId, split);
    this.splitFrom.set(split, from);
    return split;
  }

  nextSkillName() {
    let next = 0;
    for (const s of this.skills) {
      const m = /^skill(\d+)$/.exec(s.name);
      if (m) next = Math.max(next, +m[1] + 1);
    }
    return 'skill' + next;
  }

  // "skill3-2" whatever it was saved as, and its prestige versions and blessing to match
  name(recipe) {
    const tier = recipe.skill.line ? recipe.skill.line.recipes.indexOf(recipe) : -1;
    if (tier < 0) return;
    recipe.product.name = recipe.skill.name + '-' + tier;
    for (const p of this.products.values()) {
      if (p.base === recipe.product) p.name = recipe.product.name + '+' + p.prestige;
      if (p.blesses === recipe) p.name = 'blessing of ' + recipe.product.name;
    }
  }

  addBuilding(def) {
    const kind = this.meta.buildings[def.kind];
    const picture = kind.picture, paint = kind.paint;
    const width = (picture[0].length + 1) >> 1, height = picture.length;
    const building = { id: def.id, kind: def.kind, x: def.x, y: def.y, width, height, info: kind, tiles: [] };
    for (let dy = 0; dy < height; dy++) {
      for (let dx = 0; dx < width; dx++) {
        if (picture[dy][2 * dx] === '.') continue;
        const walkable = paint.length > 0 && /[a-z]/.test(paint[dy][2 * dx] || ' ');
        const tile = { x: def.x + dx, y: def.y + dy, walkable };
        building.tiles.push(tile);
        this.buildingsAt.set(key(tile.x, tile.y), building);
      }
    }
    this.buildings.push(building);
  }

  /** The character drawn at a building's tile ({@code offset} 0), or in the gap left (-1) / right (1) of it. */
  glyph(building, x, y, offset) {
    const row = building.info.picture[y - building.y];
    const c = 2 * (x - building.x) + offset;
    return c < 0 || c >= row.length ? ' ' : row[c];
  }

  paintAt(building, x, y, offset) {
    const paint = building.info.paint;
    if (!paint.length) return ' ';
    const row = paint[y - building.y];
    const c = 2 * (x - building.x) + offset;
    return c < 0 || c >= row.length ? ' ' : row[c];
  }

  walkableAt(building, x, y) {
    return /[a-z]/.test(this.paintAt(building, x, y, 0));
  }

  solidBuildingAt(x, y) {
    const b = this.buildingsAt.get(key(x, y));
    return b && !this.walkableAt(b, x, y) ? b : null;
  }

  blocked(x, y) {
    const b = this.buildingsAt.get(key(x, y));
    return this.nodesAt.has(key(x, y)) || (b && !this.walkableAt(b, x, y));
  }

  craftingSkills() { return this.skills.filter(s => s.type !== 'COMBAT'); }
  combatSkill() { return this.skills.find(s => s.type === 'COMBAT') || null; }

  madeBy(product) {
    const root = product.base || product;
    for (const r of this.recipes.values()) if (r.product === root) return r;
    return null;
  }

  droppedBy(product) {
    for (const m of this.monsters) if (m.drops.some(d => d.product === product)) return m;
    return null;
  }

  trophies(name) {
    let n = 0;
    for (const m of this.monsters) for (const d of m.drops) if (name && d.foundBy === name) n++;
    return n;
  }

  // ---- the tables ---------------------------------------------------------------------------------------------
  get maxLevel() { return this.meta.xpPerLevel.length; }

  levelFor(xp) {
    const table = this.meta.xpPerLevel;
    let level = 1;
    while (level < table.length && xp >= table[level]) level++;
    return level;
  }

  virtualLevelFor(xp) {
    const table = this.meta.xpPerLevel;
    let level = this.levelFor(xp);
    if (level < table.length) return level;
    let reached = table[table.length - 1];
    for (;;) {
      const cost = Math.floor(1047 * Math.pow(2, level / 10));
      if (xp < reached + cost) return level;
      reached += cost;
      level++;
    }
  }

  xpToFinish(level) {
    const table = this.meta.xpPerLevel;
    return table[Math.min(level, table.length - 1)];
  }

  /** The level {@code tier} makes {@code times} (2 or 3) a craft from, or -1. */
  yieldLevel(type, tier, times) {
    const table = this.meta.yieldLevels[type] || this.meta.yieldLevels.PRIMARY;
    return tier < 0 || tier >= table.length ? -1 : table[tier][times - 2];
  }

  yield(type, tier, level) {
    let made = 1;
    for (const times of [2, 3]) {
      const at = this.yieldLevel(type, tier, times);
      if (at >= 0 && level >= at) made++;
    }
    return made;
  }

  /** Every level something happens at in a skill of {@code type}, and the max (Tiers.milestones). */
  milestones(type) {
    const levels = new Set(this.meta.levelNeeded.filter(l => l > 0));
    for (let t = 0; t < this.meta.levelNeeded.length; t++) {
      for (const times of [2, 3]) {
        const at = this.yieldLevel(type, t, times);
        if (at >= 0) levels.add(at);
      }
    }
    levels.add(this.maxLevel);
    return [...levels].sort((a, b) => a - b);
  }
}

// The tier marks either side of a symbol, as the map draws them: 1 ". ", 2 "..", 3 ".:", 4 "::"
export function tierMarks(tier) {
  const left = tier > 3 ? ':' : tier > 0 ? '.' : ' ';
  const right = tier > 2 ? ':' : tier > 1 ? '.' : ' ';
  return left + right;
}

// Big counts in a few characters, rounded down (ShortNumbers): 999, 1.1k, 10.2k, 123k, 1.2M, ... then B and T
export function short(n) {
  if (n < 1000) return String(n);
  const units = 'kMBT';
  let scale = 1, unit = -1;
  while (unit < units.length - 1 && n >= scale * 1000) { scale *= 1000; unit++; }
  const tenths = Math.floor(n / Math.max(1, scale / 10));
  return (tenths >= 1000 ? String(Math.floor(tenths / 10)) : Math.floor(tenths / 10) + '.' + (tenths % 10)) + units[unit];
}
