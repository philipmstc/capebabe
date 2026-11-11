// Your view of the game: everything the server has told you (the Java client's Player.java), and the intents you send.
// Only what the server says changes it, apart from your own steps, which show right away and slide (prediction).
import { Content, key } from './content.js';
import { hueColor } from './term.js';

export const DIRS = { UP: [0, -1], DOWN: [0, 1], LEFT: [-1, 0], RIGHT: [1, 0] };
export const dirOf = (dx, dy) => dx === 0 && dy === -1 ? 'UP' : dx === 0 && dy === 1 ? 'DOWN' : dx === -1 && dy === 0 ? 'LEFT' : dx === 1 && dy === 0 ? 'RIGHT' : null;
export const ARROWS = { UP: '▲', DOWN: '▼', LEFT: '◀', RIGHT: '▶' };

const IDLE_RESET_MILLIS = 5 * 60 * 1000;
const WALK_STALL_TICKS = 3;

// ---- smooth movement ---------------------------------------------------------------------------------------------
// The server moves things a tile at a time on its ticks; between them, whatever moved slides. Each step has its own
// time: one of yours you predicted takes a tick (half running); one the server tells about takes a tick's worth
// shared by however many came that tick. Jumps of more than 2 tiles snap.
export class Glide {
  constructor(x, y) { this.reset(x, y); }

  reset(x, y) {
    this.x = this.fromX = this.toX = x;
    this.y = this.fromY = this.toY = y;
    this.start = 0; this.dur = 1;
    this.queue = [];
    this.stepsThisTick = 0;
  }

  push(x, y, dur) {
    this.queue.push({ x, y, dur });
  }

  /** Where it's heading in the end. */
  get lastX() { return this.queue.length ? this.queue[this.queue.length - 1].x : this.toX; }
  get lastY() { return this.queue.length ? this.queue[this.queue.length - 1].y : this.toY; }

  advance(now, tick) {
    for (;;) {
      const t = (now - this.start) / this.dur;
      if (t < 1) {
        this.x = this.fromX + (this.toX - this.fromX) * t;
        this.y = this.fromY + (this.toY - this.fromY) * t;
        return;
      }
      this.x = this.toX; this.y = this.toY;
      if (!this.queue.length) return;
      if (this.queue.length > 4) {
        const last = this.queue[this.queue.length - 1];
        this.reset(last.x, last.y);
        return;
      }
      const step = this.queue.shift();
      const far = Math.max(Math.abs(step.x - this.toX), Math.abs(step.y - this.toY)) > 2;
      this.start = Math.max(this.start + this.dur, now - 50);
      this.fromX = this.toX; this.fromY = this.toY;
      this.toX = step.x; this.toY = step.y;
      this.dur = far ? 1 : step.dur || tick / Math.max(1, this.stepsThisTick);
    }
  }
}

export class Game {
  constructor(meta, send) {
    this.meta = meta;
    this.tick = meta.tickMillis;
    this.send = send;
    this.content = new Content(meta);
    this.id = 0; this.name = ''; this.worldName = '';
    this.x = 0; this.y = 0; this.facing = 'DOWN';
    this.server = { x: 0, y: 0, facing: 'DOWN' };
    this.predicted = [];
    this.glide = new Glide(0, 0);
    this.colorIndex = 0; this.running = false; this.autoInvestigate = false;
    this.hp = 10; this.maxHp = 10;
    this.xp = new Map(); this.prestige = new Map(); this.bankedXp = new Map();
    this.inventory = new Map(); this.bank = new Map();
    this.goals = new Map();          // skill id -> { level, product }
    this.stats = new Map(); this.kills = new Map();
    this.weapon = null; this.armor = null;
    this.blessing = null; this.blessingLeft = 0; this.blessingStrength = 0;
    this.metMerchant = false; this.merchantCalling = false; this.capacityBonus = 0;
    this.oracle = null; this.oracleAt = 0;
    this.nodeStatus = new Map(); this.tried = new Set();
    this.lastActionAt = null;
    this.others = new Map();         // id -> { info, glide }
    this.currentAction = null; this.fightingId = null;
    this.serverTick = 0; this.lastTickAt = performance.now(); this.statsTick = 0;
    this.lastWalkProgress = 0;
    this.walkTarget = null; this.pendingBuilding = null;
    this.waypoints = []; this.buildingWaypoint = null;
    this.friends = []; this.friendWaypoint = null;
    this.highlighted = new Set();
    this.popups = [];                // floating text on the map
    this.log = [];                   // { text, kind, color, from }
    this.tracker = { skill: null, lastGain: 0, sessionXp: 0, active: false, activeSince: 0, activeMillis: 0 };
    this.bankOpen = false; this.stationOpen = false; this.churchOpen = false; this.merchantOpen = false; this.gymOpen = false;
    this.trophySlots = 0; this.gymAttacks = 0; this.displayedTrophies = [];
    this.bankSentAt = -1;
    this.inWorld = false;
    this.frames = 0;
    // display settings, kept in this browser
    this.showProductTiers = true; this.compactChat = false; this.itemSort = 'AS_GOT'; this.lightMode = false; this.skillSort = 'FOUND';
  }

  say(text, kind = 'INFO') {
    this.log.push({ text, kind, at: performance.now() });
    if (this.log.length > 500) this.log.shift();
  }

  // ---- events --------------------------------------------------------------------------------------------------
  apply(type, d) {
    const c = this.content;
    switch (type) {
      case 'Tick':
        this.serverTick = d.tick;
        this.lastTickAt = performance.now();
        this.glide.stepsThisTick = 0;
        for (const o of this.others.values()) o.glide.stepsThisTick = 0;
        break;
      case 'Welcome': this.welcome(d); break;
      case 'ContentAdded': c.apply(d.batch); break;
      case 'EntityEntered': this.others.set(d.entity.id, { info: d.entity, glide: new Glide(d.entity.position.x, d.entity.position.y) }); break;
      case 'EntityUpdated': { const o = this.others.get(d.entity.id); if (o) o.info = d.entity; break; }
      case 'EntityLeft': this.others.delete(d.id); break;
      case 'StepsCleared':
        this.server = { x: d.position.x, y: d.position.y, facing: d.facing };
        this.predicted = [];
        this.showPredicted(true);
        break;
      case 'EntityMoved':
        if (d.id === this.id) {
          this.lastWalkProgress = this.serverTick;
          this.server = { x: d.position.x, y: d.position.y, facing: d.facing };
          const first = this.predicted[0];
          if (first && first.x === d.position.x && first.y === d.position.y && first.facing === d.facing) {
            this.predicted.shift();
          } else {
            const hadPredictions = this.predicted.length > 0;
            this.predicted = [];
            if (hadPredictions) {
              this.showPredicted(true);
            } else {
              this.glide.stepsThisTick++;
              this.glide.push(d.position.x, d.position.y, 0);
              this.showPredicted(false);
            }
          }
        } else {
          const o = this.others.get(d.id);
          if (o) {
            o.info = { ...o.info, position: d.position, facing: d.facing };
            o.glide.stepsThisTick++;
            o.glide.push(d.position.x, d.position.y, 0);
          }
        }
        break;
      case 'ActionStarted':
        this.currentAction = d;
        this.trackerActive(true);
        break;
      case 'ActionStopped':
        this.currentAction = null;
        this.trackerActive(false);
        break;
      case 'XpGained': {
        const skill = c.skillsById.get(d.skillId);
        this.xp.set(d.skillId, d.totalXp);
        this.trackerRecord(skill, d.amount);
        if (d.at) this.popup(d.at.x, d.at.y, '+' + d.amount + 'xp', this.skillColor(skill), 0);
        break;
      }
      case 'OracleSaid': this.oracle = d; this.oracleAt = performance.now(); break;
      case 'CapacityBonus': this.capacityBonus = d.bonus; break;
      case 'GymChanged':
        this.trophySlots = d.trophySlots; this.gymAttacks = d.gymAttacks;
        this.displayedTrophies = d.displayed.map(id => c.products.get(id)).filter(Boolean);
        break;
      case 'MerchantCalls': this.metMerchant = true; this.merchantCalling = true; break;
      case 'BlessingChanged':
        this.blessing = d.recipeId < 0 ? null : c.recipes.get(d.recipeId);
        this.blessingLeft = d.left; this.blessingStrength = d.strength;
        break;
      case 'Prestiged':
        this.xp.set(d.skillId, 0);
        this.prestige.set(d.skillId, d.prestige);
        this.bankedXp.set(d.skillId, d.bankedXp);
        break;
      case 'LevelUp': this.levelUp(c.skillsById.get(d.skillId), d.from, d.to); break;
      case 'InventoryChanged': {
        const p = c.products.get(d.productId);
        if (d.count > 0 || this.inventory.has(p)) this.inventory.set(p, d.count);
        if (d.count <= 0) this.inventory.delete(p);
        break;
      }
      case 'BankChanged': {
        const p = c.products.get(d.productId);
        if (d.count <= 0) this.bank.delete(p); else this.bank.set(p, d.count);
        break;
      }
      case 'GoalSet':
        if (!d.goal) this.goals.delete(d.skillId);
        else this.goals.set(d.skillId, { level: d.goal.level, product: c.products.get(d.goal.productId) });
        break;
      case 'EquipmentChanged': {
        const item = d.productId < 0 ? null : c.products.get(d.productId);
        if (d.slot === 'WEAPON') this.weapon = item; else this.armor = item;
        break;
      }
      case 'NodeTried': this.tried.add(d.nodeId); break;
      case 'NodeChanged': this.nodeStatus.set(d.status.nodeId, d.status); break;
      case 'FightStarted': this.fightingId = d.monsterId; break;
      case 'FightEnded': this.fightingId = null; break;
      case 'ColorChanged': this.colorIndex = d.colorIndex; break;
      case 'RunningChanged': this.running = d.running; break;
      case 'AutoInvestigateChanged': this.autoInvestigate = d.on; break;
      case 'HpChanged': this.hp = d.hp; this.maxHp = d.maxHp; break;
      case 'Hit': {
        const me = d.targetId === this.id;
        let side = 0;
        if (d.from) side = Math.sign(d.at.x - d.from.x) || (me ? -1 : 1);
        this.popup(d.at.x, d.at.y, String(d.damage), d.damage === 0 ? 'BLUE' : 'RED', side, true);
        break;
      }
      case 'HomeChanged': this.lastActionAt = d.lastActionAt; break;
      case 'Message': this.say(d.text, d.kind || 'INFO'); break;
      case 'Chat': this.log.push({ text: d.text, kind: 'CHAT', from: d.from, colorIndex: d.colorIndex, at: performance.now() }); break;
      case 'Whisper':
        this.log.push({ text: d.text, kind: 'CHAT', from: d.from === this.name ? 'to ' + d.to : 'from ' + d.from, colorIndex: d.colorIndex, at: performance.now() });
        break;
      case 'Friends': this.friends = d.friends; break;
      case 'StatsChanged': this.setStats(d.stats); break;
    }
  }

  welcome(w) {
    const c = this.content;
    for (const batch of w.content) c.apply(batch);
    const you = w.you;
    this.id = you.id; this.name = you.name; this.worldName = w.worldName;
    this.server = { x: you.position.x, y: you.position.y, facing: you.facing };
    this.predicted = [];
    this.showPredicted(true);
    this.colorIndex = you.colorIndex; this.running = you.running; this.autoInvestigate = you.autoInvestigate;
    this.bank.clear();
    for (const [id, n] of Object.entries(you.bank || {})) this.bank.set(c.products.get(+id), n);
    this.weapon = you.weaponId < 0 ? null : c.products.get(you.weaponId);
    this.armor = you.armorId < 0 ? null : c.products.get(you.armorId);
    this.blessing = you.blessingRecipeId < 0 ? null : c.recipes.get(you.blessingRecipeId);
    this.blessingLeft = you.blessingLeft; this.blessingStrength = you.blessingStrength;
    this.metMerchant = you.metMerchant;
    this.setStats(you.stats);
    this.xp.clear();
    for (const [id, n] of Object.entries(you.xp || {})) this.xp.set(+id, n);
    this.prestige.clear(); this.bankedXp.clear();
    for (const [id, n] of Object.entries(you.prestige || {})) this.prestige.set(+id, n);
    for (const [id, n] of Object.entries(you.bankedXp || {})) this.bankedXp.set(+id, n);
    this.inventory.clear();
    for (const [id, n] of Object.entries(you.inventory || {})) if (n > 0) this.inventory.set(c.products.get(+id), n);
    this.goals.clear();
    for (const g of you.goals || []) this.goals.set(g.skillId, { level: g.level, product: c.products.get(g.productId) });
    for (const id of you.triedNodes || []) this.tried.add(id);
    this.lastActionAt = you.lastActionAt;
    this.others.clear();
    for (const o of w.others) this.others.set(o.id, { info: o, glide: new Glide(o.position.x, o.position.y) });
    for (const s of w.nodes) this.nodeStatus.set(s.nodeId, s);
    this.hp = you.hp; this.maxHp = you.maxHp;
    this.serverTick = w.tick; this.statsTick = w.tick; this.lastTickAt = performance.now();
    this.inWorld = true;
    this.say('Welcome to ' + this.worldName + ', ' + this.name + '!', 'NOTICE');
  }

  levelUp(skill, from, to) {
    this.say(skill.name + ' is now Lv. ' + to + '!', 'NOTICE');
    this.popups.push({ follow: true, text: ' ' + skill.name + ' Lv. ' + to + '! ', fg: 'BLACK', bg: this.skillColor(skill), at: performance.now(), life: 2500 });
    const unlocked = (skill.line ? skill.line.recipes : []).filter(r => r.levelNeeded > from && r.levelNeeded <= to).map(r => r.product.name);
    if (unlocked.length) this.say('Unlocked: ' + unlocked.join(', '), 'NOTICE');
    const goal = this.goals.get(skill.id);
    if (goal && goal.level > from && goal.level <= to) this.say('Goal complete: ' + this.describeGoal(skill, goal), 'NOTICE');
  }

  setStats(byName) {
    this.statsTick = this.serverTick;
    this.stats.clear(); this.kills.clear();
    for (const [name, value] of Object.entries(byName || {})) {
      if (name.startsWith('KILLS_')) this.kills.set(+name.slice(6), value);
      else this.stats.set(name, value);
    }
  }

  popup(x, y, text, fg, side, hit) {
    this.popups.push({ x, y, text, fg, side, hit, at: performance.now(), life: hit ? 1200 : 1500 });
  }

  // ---- the xp tracker (XpTracker.java) ----------------------------------------------------------------------------
  trackerRecord(skill, gained) {
    const t = this.tracker, now = performance.now();
    if (t.skill !== skill || now - t.lastGain > IDLE_RESET_MILLIS) {
      t.skill = skill; t.sessionXp = 0; t.activeMillis = 0; t.activeSince = now;
    } else {
      t.sessionXp += gained;
    }
    t.lastGain = now;
  }

  trackerActive(on) {
    const t = this.tracker, now = performance.now();
    if (on && !t.active) t.activeSince = now;
    else if (!on && t.active) t.activeMillis += now - t.activeSince;
    t.active = on;
  }

  xpPerHour() {
    const t = this.tracker;
    const elapsed = t.activeMillis + (t.active ? performance.now() - t.activeSince : 0);
    return !t.skill || t.sessionXp === 0 || elapsed <= 0 ? null : Math.floor(t.sessionXp * 3_600_000 / elapsed);
  }

  // ---- your position: the server's, plus the steps you've taken it hasn't confirmed ------------------------------
  showPredicted(snap) {
    const shown = this.predicted.length ? this.predicted[this.predicted.length - 1] : this.server;
    this.x = shown.x; this.y = shown.y; this.facing = shown.facing;
    if (snap) {
      const far = Math.max(Math.abs(this.glide.lastX - shown.x), Math.abs(this.glide.lastY - shown.y));
      if (far > 2) this.glide.reset(shown.x, shown.y);
      else if (far > 0) { this.glide.queue = []; this.glide.push(shown.x, shown.y, this.tick / 2); }
    }
  }

  /** The bank, station, church and Merch Ant windows all shut. */
  closeWindows() {
    this.bankOpen = this.stationOpen = this.churchOpen = this.merchantOpen = this.gymOpen = false;
  }

  moveBy(dx, dy) {
    // walking off closes a building's window, as in the terminal client
    this.closeWindows();
    this.walkTarget = null;
    this.pendingBuilding = null;
    const facing = dirOf(dx, dy);
    if (!facing || this.predicted.length >= this.meta.maxQueuedSteps) return false;
    const tx = this.x + dx, ty = this.y + dy;
    const blocked = this.content.blocked(tx, ty);
    const pose = { x: blocked ? this.x : tx, y: blocked ? this.y : ty, facing };
    this.predicted.push(pose);
    if (!blocked) this.glide.push(tx, ty, this.tick / (this.running ? 2 : 1));
    this.showPredicted(false);
    this.send('Move', { dx, dy });
    return true;
  }

  get ahead() {
    const [dx, dy] = DIRS[this.facing];
    return { x: this.x + dx, y: this.y + dy };
  }

  stepsTo(p) { return Math.max(Math.abs(p.x - this.x), Math.abs(p.y - this.y)); }

  partialTick() { return Math.min(1, Math.max(0, (performance.now() - this.lastTickAt) / this.tick)); }

  // 5 frames of the terminal's 60ms: the bar shows full that long before the item's done (Player.barFraction)
  actionFraction() {
    const a = this.currentAction;
    if (!a) return 0;
    const into = ((this.serverTick - a.startTick) % a.durationTicks + a.durationTicks) % a.durationTicks;
    const hold = 5 * this.meta.frameMillis / this.tick;
    const fill = Math.max(a.durationTicks / 2, a.durationTicks - hold);
    return Math.min(1, (into + this.partialTick()) / fill);
  }

  actionLabel() {
    const a = this.currentAction;
    if (!a) return null;
    const node = this.content.nodes.get(a.nodeId);
    if (!node || !a.label.startsWith(node.recipe.product.name)) return a.label;
    const made = this.madePerCraft(node.recipe);
    return (made > 1 ? made + ' x ' : '') + a.label;
  }

  fightingMonster() { return this.fightingId == null ? null : (this.others.get(this.fightingId) || {}).info || null; }

  othersAt(x, y) {
    const here = [];
    for (const o of this.others.values()) if (o.info.position.x === x && o.info.position.y === y) here.push(o.info);
    return here;
  }

  /** One frame: walking up to a building, reaching waypoints, dropping a stalled walk. */
  frame() {
    this.frames++;
    const ahead = this.ahead;
    if (this.pendingBuilding && ahead.x === this.pendingBuilding.x && ahead.y === this.pendingBuilding.y) {
      this.pendingBuilding = null;
      this.walkTarget = null;
      this.perform();
    } else if (this.pendingBuilding && !this.walkTarget) {
      this.pendingBuilding = null;
    }
    const reached = this.waypoints.filter(n => n.x === ahead.x && n.y === ahead.y);
    if (reached.length) {
      this.waypoints = this.waypoints.filter(n => !reached.includes(n));
      this.say("You've reached your waypoint.");
    }
    const b = this.buildingWaypoint;
    if (b && b.tiles.some(t => !t.walkable && t.x === ahead.x && t.y === ahead.y)) {
      this.say("You've reached the " + b.info.label + '.');
      this.buildingWaypoint = null;
    }
    const w = this.walkTarget;
    if (w && ((w.x === this.x && w.y === this.y) || (w.x === ahead.x && w.y === ahead.y) || this.serverTick - this.lastWalkProgress > WALK_STALL_TICKS)) {
      this.walkTarget = null;
    }
  }

  // ---- what you can work out ---------------------------------------------------------------------------------
  level(skill) { return this.content.levelFor(this.xp.get(skill.id) || 0); }
  experience(skill) { return this.xp.get(skill.id) || 0; }
  getPrestige(skill) { return this.prestige.get(skill.id) || 0; }
  lifetimeExperience(skill) { return (this.bankedXp.get(skill.id) || 0) + this.experience(skill); }
  stackLevel(skill) { return this.content.virtualLevelFor(this.lifetimeExperience(skill)); }
  combatLevel() { return Math.max(1, ...this.content.skills.filter(s => s.type === 'COMBAT').map(s => this.level(s))); }
  totalLevel() { return this.content.skills.reduce((n, s) => n + this.level(s) + this.getPrestige(s) * (this.content.maxLevel - 1), 0); }
  totalExperience() { return this.content.skills.reduce((n, s) => n + this.lifetimeExperience(s), 0); }
  trophies() { return this.content.trophies(this.name); }
  inventorySlots() { return this.meta.baseSlots + this.trophySlots; }
  /** Training at the Gym (the server's ActionStarted for it has no node). */
  training() { return !!this.currentAction && this.currentAction.nodeId === -1; }
  /** Crafting AFK: it stops once the inventory's half full (Stacks.afkCapacity). */
  afkCrafting() { return !!this.currentAction && !this.training() && this.currentAction.label.endsWith('(AFK)'); }

  stackSize(product) {
    const recipe = this.content.madeBy(product);
    const skill = recipe ? recipe.skill : this.content.combatSkill();
    return skill ? Math.max(1, this.stackLevel(skill)) : 1;
  }

  madePerCraft(recipe) { return this.content.yield(recipe.skill.type, recipe.tier, this.level(recipe.skill)); }
  status(node) { return this.nodeStatus.get(node.id) || { nodeId: node.id, charges: node.maxCharges, depleted: false, users: 0 }; }
  lockedFor(node) { return this.level(node.recipe.skill) < node.recipe.levelNeeded; }

  /** A skill's own colour (DamageColor.skillHue), or its dark version. */
  skillColor(skill, dark) {
    return hueColor(skillHue(skill.colorIndex), dark);
  }

  /** A node's colour on the map: grey when empty, dim when you've tried it and it's too high for you. */
  nodeColor(node) {
    if (this.status(node).depleted) return 'BLACK_BRIGHT';
    return this.skillColor(node.recipe.skill, this.tried.has(node.id) && this.lockedFor(node));
  }

  monsterColor(type) {
    if (type.colorIndex === 6) return 'WHITE';
    if (type.colorIndex === 7) return 'BLACK_BRIGHT';
    const hues = ['RED', 'YELLOW', 'GREEN', 'CYAN', 'BLUE', 'MAGENTA'];
    return hues[((type.colorIndex % 6) + 6) % 6] + (type.id % 2 === 1 ? '' : '_BRIGHT');
  }

  playerColor(index) {
    const colors = ['CYAN_BRIGHT', 'GREEN_BRIGHT', 'YELLOW_BRIGHT', 'MAGENTA_BRIGHT', 'RED_BRIGHT', 'BLUE_BRIGHT',
      'WHITE_BRIGHT', 'CYAN', 'GREEN', 'YELLOW', 'MAGENTA', 'RED'];
    return colors[((index % colors.length) + colors.length) % colors.length];
  }

  /** The item's colour: its skill's, its monster's, or plain. */
  itemColor(product) {
    const recipe = product.blesses || this.content.madeBy(product);
    if (recipe) return this.skillColor(recipe.skill);
    const monster = this.content.droppedBy(product);
    return monster ? this.monsterColor(monster) : 'DEFAULT';
  }

  /** Every item in stacks of its stack size, in the sort's order (InventoryPanel.slots). */
  slots(items) {
    const out = [];
    for (const [product, count] of this.sorted(items)) {
      const stack = this.stackSize(product);
      for (let left = count; left > 0; left -= stack) out.push({ product, count: Math.min(stack, left) });
    }
    return out;
  }

  sorted(items) {
    const entries = [...items.entries()].filter(([, n]) => n > 0);
    if (this.itemSort === 'AS_GOT') return entries;
    const c = this.content;
    const info = p => {
      const recipe = c.madeBy(p);
      const skill = recipe ? recipe.skill : null;
      const number = skill ? +(/(\d+)$/.exec(skill.name) || [0, 1e6])[1] : 1e6;
      const types = ['PRIMARY', 'SECONDARY', 'TERTIARY', 'COMBAT'];
      return { drop: recipe ? 0 : 1, number, tier: Math.max(0, p.tier), type: skill ? types.indexOf(skill.type) : 9, prestige: p.prestige };
    };
    const by = {
      SKILL: (a, b) => a.drop - b.drop || a.number - b.number || a.tier - b.tier || a.prestige - b.prestige,
      TYPE: (a, b) => a.drop - b.drop || a.type - b.type || a.number - b.number || a.tier - b.tier || a.prestige - b.prestige,
      TIER: (a, b) => a.drop - b.drop || a.tier - b.tier || a.number - b.number || a.prestige - b.prestige,
    }[this.itemSort];
    return entries.sort((a, b) => by(info(a[0]), info(b[0])));
  }

  describeGoal(skill, goal) {
    const product = goal.product;
    const recipe = product ? this.content.madeBy(product) : null;
    let what = '';
    if (recipe) {
      if (recipe.levelNeeded === goal.level) what = product.name + ' unlocks';
      else {
        const times = [2, 3].find(t => this.content.yieldLevel(skill.type, recipe.tier, t) === goal.level);
        what = product.name + (times ? ' x' + times : '');
      }
    }
    return 'reach ' + skill.name + ' Lv. ' + goal.level + (what ? ' (' + what + ')' : '');
  }

  // ---- intents ---------------------------------------------------------------------------------------------------
  walkTo(x, y) {
    this.walkTarget = { x, y };
    this.lastWalkProgress = this.serverTick;
    this.send('WalkTo', { target: { x, y } });
  }

  perform() {
    const b = this.content.solidBuildingAt(this.ahead.x, this.ahead.y);
    if (b) this.closeWindows();
    if (b && b.kind === 'BANK') this.bankOpen = true;
    else if (b && b.kind === 'STATION') this.stationOpen = true;
    else if (b && b.kind === 'CHURCH') this.churchOpen = true;
    else if (b && b.kind === 'MERCHANT' && this.metMerchant) this.merchantOpen = true;
    // the Gym: a window (train, trophies on display); q while training just stops
    else if (b && b.kind === 'GYM' && !this.training()) { this.gymOpen = true; return; }
    this.send('Perform');
  }

  train() { this.gymOpen = false; this.send('Perform'); }
  displayTrophy(item, forSlot) { this.send('DisplayTrophy', { productId: item.id, forSlot }); }

  /** Walk up to a building and use it, as q would, once you're facing it (Player.useBuilding). */
  useBuilding(building) {
    let best = null, bestSteps = Infinity;
    for (const t of building.tiles) {
      if (t.walkable) continue;
      for (const [dx, dy] of Object.values(DIRS)) {
        const bx = t.x + dx, by = t.y + dy;
        if (!this.content.blocked(bx, by) && this.stepsTo({ x: bx, y: by }) < bestSteps) {
          best = t; bestSteps = this.stepsTo({ x: bx, y: by });
        }
      }
    }
    if (!best) return;
    this.walkTo(best.x, best.y);
    this.pendingBuilding = { x: best.x, y: best.y };
  }

  craft(x, y, afk) { this.send('Craft', { target: { x, y }, afk }); }
  fight(id) { this.pendingBuilding = null; this.send('Fight', { monsterId: id }); }
  equip(slot, product) { this.send('Equip', { slot, productId: product.id }); }
  unequip(slot) { this.send('Unequip', { slot }); }
  discard(product, count) { this.send('Discard', { productId: product.id, count }); }
  useItem(product, x, y) { this.send('UseItem', { productId: product.id, target: { x, y } }); }
  useBlessing(product) { this.send('UseBlessing', { productId: product.id }); }
  deposit(product, count) { this.bankSentAt = this.serverTick; this.send('Deposit', { productId: product.id, count }); }
  withdraw(product, count) { this.bankSentAt = this.serverTick; this.send('Withdraw', { productId: product.id, count }); }
  bankBusy() { return this.serverTick <= this.bankSentAt; }
  setGoal(skill, level) { this.send('SetGoal', { skillId: skill.id, level }); }
  prestigeSkill(skill) { this.send('Prestige', { skillId: skill.id }); }
  shareSkill(skill) { this.send('ShareSkill', { skillId: skill.id }); }
  broadcastPosition() { this.send('SharePosition'); }
  setColor(index) { this.colorIndex = ((index % 12) + 12) % 12; this.send('SetColor', { colorIndex: this.colorIndex }); }
  toggleRunning() { this.running = !this.running; this.send('SetRunning', { running: this.running }); }
  toggleAutoInvestigate() { this.autoInvestigate = !this.autoInvestigate; this.send('SetAutoInvestigate', { on: this.autoInvestigate }); }
  chat(text) { this.send('Chat', { text }); }
  whisper(name, text) { this.send('Whisper', { name, text }); }
  addFriend(name) { this.send('AddFriend', { name }); }
  removeFriend(name) { if (this.friendWaypoint === name) this.friendWaypoint = null; this.send('RemoveFriend', { name }); }
  /** Where the friend you've a waypoint on is, if they're in the world. */
  friendTarget() {
    const friend = this.friends.find(f => f.name === this.friendWaypoint);
    return friend ? friend.position : null;
  }
  admin(command, args) { this.send('Admin', { command, args }); }
  heal() { this.send('Heal'); }
  pray(recipe, payment) { this.send('Pray', { recipeId: recipe.id, paymentProductId: payment.id }); }
  tithe(product, count) { this.send('Tithe', { productId: product.id, count }); }
  ride(stationId) { this.send('Ride', { stationId }); }
  buyTickets(count) { this.send('BuyTickets', { count }); }
  trade(product, count) { this.send('Trade', { productId: product.id, count }); }

  /** The nearest node making {@code recipe} that isn't empty. */
  nearestNode(recipe) {
    let best = null, steps = Infinity;
    for (const node of this.content.nodes.values()) {
      if (node.recipe !== recipe || this.status(node).depleted) continue;
      const s = this.stepsTo(node);
      if (s < steps || (s === steps && node.id < best.id)) { best = node; steps = s; }
    }
    return best;
  }

  hasWaypoint(recipe) { return this.waypoints.some(n => n.recipe === recipe); }

  toggleWaypoint(recipe) {
    if (this.hasWaypoint(recipe)) {
      this.waypoints = this.waypoints.filter(n => n.recipe !== recipe);
      this.say('Waypoint cleared.');
      return;
    }
    const node = this.nearestNode(recipe);
    if (!node) { this.say('No known node makes ' + recipe.product.name + ' yet.'); return; }
    this.waypoints.push(node);
    this.say('Waypoint: ' + recipe.product.name + ' at (' + node.x + ',' + node.y + '), ' + this.stepsTo(node) + ' steps away.');
  }

  clearOtherWaypoints(recipe) {
    const had = this.waypoints.some(n => n.recipe !== recipe) || this.buildingWaypoint;
    this.waypoints = this.waypoints.filter(n => n.recipe === recipe);
    this.buildingWaypoint = null;
    if (had) this.say('Other waypoints cleared.');
  }

  nearestTile(building) {
    let best = null;
    for (const t of building.tiles) if (!t.walkable && (!best || this.stepsTo(t) < this.stepsTo(best))) best = t;
    return best || { x: building.x, y: building.y };
  }

  nearestBuilding(kind) {
    let best = null;
    for (const b of this.content.buildings) {
      if (b.kind === kind && (!best || this.stepsTo(this.nearestTile(b)) < this.stepsTo(this.nearestTile(best)))) best = b;
    }
    return best;
  }

  toggleBuildingWaypoint(building) {
    if (this.buildingWaypoint === building) {
      this.buildingWaypoint = null;
      this.say('Waypoint cleared.');
      return;
    }
    this.buildingWaypoint = building;
    const at = this.nearestTile(building);
    this.say('Waypoint: ' + building.info.label + ' at (' + at.x + ',' + at.y + '), ' + this.stepsTo(at) + ' steps away.');
  }

  toggleHighlight(recipe) {
    if (this.highlighted.has(recipe)) this.highlighted.delete(recipe); else this.highlighted.add(recipe);
  }

  /** The oracle's word, its minutes counted on since it came (Player.oracle); null: none yet. */
  oracleNow() {
    const o = this.oracle;
    if (!o || !o.known) return o;
    return { ...o, minutes: o.minutes - Math.floor((performance.now() - this.oracleAt) / 60_000) };
  }

  timePlayedTicks() { return (this.stats.get('TIME_PLAYED') || 0) + Math.max(0, this.serverTick - this.statsTick); }
}

export { key };

/**
 * Skill colour index {@code i}'s hue in degrees (DamageColor.skillHue -- what it fights as): the first six 60 apart
 * from red, then each six in the middle of the biggest gaps left: 30, 90 ...; 15, 75 ...; 45, 105 ...; 7.5 ...
 */
export function skillHue(i) {
  let ring = Math.floor(i / 6), offset = 0;
  for (let place = 30; ring > 0; ring >>= 1, place /= 2) offset += (ring & 1) * place;
  return (offset + 60 * (((i % 6) + 6) % 6)) % 360;
}
