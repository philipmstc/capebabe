// Every panel of the terminal client (Layout.java), drawn on the character grid: the header, the tabs, the
// inventory and equipment, the map's frame, the right-hand tab (inventory item, map, skills, monsters, stats,
// settings), the message log and chat, the tile details, the progress bar, popups and the bank. The map itself
// (inside its frame) is drawn in pixels by MapView.
import { COLS, ROWS, Painter, BASE, POPUP, MODAL, MENU, darker } from './term.js';
import { tierMarks, short } from './content.js';
import { ARROWS, DIRS, skillHue } from './game.js';
import { PICTURE } from './tutorial.js';
import { MapView, edgeTowards, BOARD_WIDTH, BOARD_HEIGHT } from './mapview.js';
import { skillSprite, monsterSprite } from './sprites.js';

const R = (x, y, w, h) => ({ x, y, w, h, right: x + w - 1, bottom: y + h - 1 });
const LEFT_WIDTH = 24, HEADER_ROW = 1, HEADER_GAP = 1;
export const MAP = R(LEFT_WIDTH + 1, HEADER_ROW + 1, 2 * BOARD_WIDTH + 1, BOARD_HEIGHT + 2);
export const INVENTORY = R(0, MAP.y, LEFT_WIDTH, MAP.h - 4);
export const EQUIPMENT = R(0, INVENTORY.bottom + 1, LEFT_WIDTH, 4);
const RIGHT_X = MAP.right + 2, RIGHT_WIDTH = COLS - RIGHT_X;
const NAME_X = COLS - 53, HP_WIDTH = 12, HP_X = COLS - HP_WIDTH;
const XP = R(MAP.x, 0, NAME_X - MAP.x, 1);
export const TABS = R(RIGHT_X, MAP.y, RIGHT_WIDTH, MAP.h);
const BOTTOM_Y = MAP.bottom + 1;
export const LOG = R(0, BOTTOM_Y, MAP.right + 1, ROWS - BOTTOM_Y);
const DETAIL = R(RIGHT_X, BOTTOM_Y, RIGHT_WIDTH, ROWS - BOTTOM_Y);
const INVENTORY_ROWS = 5, CELL_WIDTH = 6, CELL_STEP_X = 7, CELL_STEP_Y = 4;
const BANK = R(MAP.x, INVENTORY.y, 2 * (INVENTORY.w + 3), MAP.h);
const BANK_CELL_WIDTH = CELL_WIDTH + 1, BANK_STEP_X = BANK_CELL_WIDTH + 1;

const textX = r => r.x + 2, textWidth = r => r.w - 4, firstRow = r => r.y + 1, hintRow = r => r.bottom - 1;
const fit = (text, width) => text.length <= width ? text : text.slice(0, Math.max(0, width - 1)) + '~';
const dim = c => darker(c);
const pad = (s, n) => String(s).padStart(n);
const comma = n => n.toLocaleString('en-US');
const SKILL_SORTS = { FOUND: 'found', TIER: 'tier', COLOR: 'color' };
const SKILL_TIERS = ['PRIMARY', 'SECONDARY', 'TERTIARY', 'COMBAT'];
const TYPE_COLOR = { PRIMARY: 'GREEN_BRIGHT', SECONDARY: 'BLUE_BRIGHT', TERTIARY: 'RED_BRIGHT', COMBAT: 'WHITE_BRIGHT' };
const TABS_LIST = [
  { id: 'INVENTORY', key: 'i', title: 'Inventory' },
  { id: 'SKILLS', key: '1', title: 'Skills' },
  { id: 'MONSTERS', key: '2', title: 'Monsters' },
  { id: 'STATS', key: '3', title: 'Stats' },
  { id: 'SETTINGS', key: '4', title: 'Settings' },
  { id: 'FRIENDS', key: '5', title: 'Friends' },
];
const SORTS = ['AS_GOT', 'SKILL', 'TYPE', 'TIER'];
const SORT_NAMES = { AS_GOT: 'as got', SKILL: 'skill', TYPE: 'type', TIER: 'tier' };

/** "1h 23m", "4m 05s" (XpTrackerView.duration) */
function duration(millis) {
  const s = Math.floor(millis / 1000), h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60);
  return h > 0 ? h + 'h ' + String(m).padStart(2, '0') + 'm' : m + 'm ' + String(s % 60).padStart(2, '0') + 's';
}

export class UI {
  constructor(game, logout) {
    this.game = game;
    this.logout = logout;
    this.map = new MapView(MAP);
    this.tab = 'SKILLS';
    this.mapTab = false;           // the Map tab (v) shows in the right panel instead
    this.inv = { selected: 0, top: 0, focused: false };
    this.skills = { index: 0, page: null, tier: 0, trail: [], details: false };
    this.monsters = { index: 0 };
    this.popup = null;             // { title, color, options: [{ label, note, action }], x, y, index }
    this.chat = null;              // the line being typed, or null
    this.chatTo = null;            // who it's a private message to, if anyone
    this.friendView = null;        // the friend whose skills the Friends tab is showing
    this.using = null;             // an item picked to use on a node
    this.hoverTile = null;
    this.bank = { side: 'INVENTORY', selected: [0, 0], top: [0, 0] };
  }

  // ---- drawing ---------------------------------------------------------------------------------------------------
  draw(base, top, clicks, now) {
    const game = this.game;
    const b = new Painter(base), t = new Painter(top);
    this.clicks = clicks;
    this.icons = [];
    this.topIcons = [];
    if (this.chat == null) this.chatTo = null;
    this.bars = [];
    this.invGrid = null;
    this.cols = base.cols;
    this.rows = base.rows;
    if (this.phone) this.drawPhone(b, t);
    else {
      this.map.rect = MAP;
      this.header(b);
      this.tabHeaders(b);
      this.inventory(b, INVENTORY);
      this.equipment(b, EQUIPMENT);
      this.mapFrame(b, t);
      this.rightPanel(b, TABS, this.mapTab ? 'MAP' : this.tab);
      this.log(b, LOG);
      this.detail(b);
    }
    this.progress(t);
    if (game.bankOpen) this.bankWindow(t);
    else if (game.stationOpen) this.stationWindow(t);
    else if (game.churchOpen) this.churchWindow(t);
    else if (game.merchantOpen) this.merchantWindow(t);
    else if (game.gymOpen) this.gymWindow(t);
    if (!game.churchOpen) this.church = null;
    if (!game.gymOpen) this.gym = null;
    if (!game.merchantOpen) this.merchant = null;
    this.tutorialBox(t);
    if (this.popup) this.drawPopup(t);
    if (this.offer) this.offerBox(t);
  }

  /**
   * The tutorial's box over the top of the map (TutorialView): its title in yellow and what to do, word-wrapped;
   * an explanation has "[ Got it ]" under it (Enter, or a click). Clicks on it don't go through to the map.
   */
  tutorialBox(p) {
    const tut = this.tutorial, game = this.game;
    if (!tut) return;
    const onSkills = this.shownTab() === 'SKILLS';
    tut.update(game, { skillPageOpen: onSkills && !!this.skills.page, followedLink: onSkills && this.skills.trail.length > 0 });
    const text = tut.text(game);
    if (!text.length) return;
    const M = this.map.rect;
    const left = M.x + 2, right = M.right - 2, width = right - left - 3;
    const lines = text.slice(1).flatMap(line => wrapMarked(line, width));
    const gotIt = tut.dismissable();
    const top = M.y + 1, bottom = top + 2 + lines.length + (gotIt ? 2 : 0);
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(left, top, right - left + 1, bottom - top + 1);
    p.frame(left, top, right, bottom);
    p.fg = 'YELLOW_BRIGHT'; p.bold = true; p.put(left + 2, top + 1, fit(text[0], width)); p.bold = false;
    p.fg = 'DEFAULT';
    // the guide's stand-ins for a picture (tutorial.js PICTURE): blank cells, the picture over them
    const pictured = tut.pictured(game);
    lines.forEach((line, i) => {
      p.put(left + 2, top + 2 + i, line.replaceAll(PICTURE, '  '));
      for (let at = line.indexOf(PICTURE); at >= 0 && pictured; at = line.indexOf(PICTURE, at + 2)) {
        this.iconsOnTop = true;
        this.skillIcon(left + 2 + at, top + 2 + i, pictured);
        this.iconsOnTop = false;
      }
    });
    this.clicks.add(POPUP, left, top, right - left + 1, bottom - top + 1, () => {});
    if (gotIt) {
      const label = '[ Got it ]', x = right - 1 - label.length;
      p.fg = 'WHITE_BRIGHT'; p.put(x, bottom - 1, label);
      this.clicks.option(POPUP, x, bottom - 1, label.length, 1, () => tut.dismiss());
    }
    p.bg = null;
  }

  /**
   * The phone layouts, under one line of you (name, total level, xp, hp), with the tab buttons along the bottom
   * (wrapping onto a second row if they must): one menu is always open -- Inventory (its grid, the item, your
   * equipment), Skills, Monsters, Stats, Settings, Map (points of interest, waypoints), Log (and chat) -- and the
   * buttons pick which. Sideways: your inventory and equipment always on the left, the map in the middle, the menu
   * on the right (no Inventory button). Upright: the menu on top, the map under it. A tap is a left-click; a long
   * press a right-click.
   */
  drawPhone(b, t) {
    const game = this.game, cols = this.cols, rows = this.rows;
    this.sideways = cols >= rows * 2;
    if (this.sideways) {
      if (!this.sheet || this.sheet === 'INVENTORY') this.sheet = 'SKILLS';
    } else if (!this.sheet || game.bankOpen || game.merchantOpen) this.sheet = game.bankOpen || game.merchantOpen ? 'INVENTORY' : this.sheet || 'INVENTORY';
    const bar = this.bottomBar(b);
    const top = 1, height = rows - top - bar;
    let map, menu;
    if (this.sideways) {
      // sideways: inventory and equipment, the map, the menu (as narrow as reads well, so the map gets the rest)
      const left = 16, rest = cols - left;   // the inventory three compact cells wide
      let mapWidth = rest - Math.max(30, Math.min(40, Math.floor(rest * 0.42)));
      if (mapWidth % 2 === 0) mapWidth--;
      const grid = R(0, top, left, height - 4);
      this.inventory(b, grid, true);
      b.fg = 'BLACK_BRIGHT'; b.put(grid.x + 1, grid.y, 'Inventory');
      this.equipment(b, R(0, grid.bottom + 1, left, 4));
      map = R(left, top, mapWidth, height);
      menu = R(map.right + 1, top, cols - map.right - 1, height);
    } else {
      // upright: the menu on top, the map under it
      const mapHeight = Math.max(9, Math.floor(height * 0.45));
      menu = R(0, top, cols, height - mapHeight);
      map = R(0, top + menu.h, cols % 2 ? cols : cols - 1, mapHeight);
    }
    this.map.rect = map;
    this.menuRect = menu;
    this.phoneHeader(b);
    this.mapFrame(b, t);
    this.recentLog(t);
    this.sheetPanel(b, menu);
  }

  /**
   * The xp the tracker's bar runs between: to the next level, or with a goal to that goal, from the last milestone
   * you've passed so it doesn't start over each level.
   */
  xpBar(skill) {
    const game = this.game, c = game.content, level = game.level(skill), goal = game.goals.get(skill.id);
    const table = game.meta.xpPerLevel, start = table[Math.max(0, level - 1)];
    if (goal && goal.level > level) {
      const passed = c.milestones(skill.type).filter(l => l <= level).pop();
      return { from: passed ? table[Math.max(0, passed - 1)] : 0, to: c.xpToFinish(goal.level - 1) };
    }
    return { from: start, to: level >= c.maxLevel ? start : c.xpToFinish(level) };
  }

  phoneHeader(p) {
    const game = this.game;
    let x = 0;
    p.fg = game.playerColor(game.colorIndex); p.bold = true; p.put(x, 0, game.name); p.bold = false;
    x += game.name.length;
    p.fg = 'YELLOW_BRIGHT'; p.put(x, 0, ' [Total Lv. ' + game.totalLevel() + ']');
    x += 14 + String(game.totalLevel()).length;
    const skill = game.tracker.skill;
    if (skill && x + 30 < this.cols - HP_WIDTH) {
      const level = game.level(skill), xp = game.experience(skill), { from, to } = this.xpBar(skill);
      this.skillIcon(x, 0, skill);
      p.fg = 'DEFAULT'; p.put(x + 3, 0, skill.name + ' Lv. ' + level);
      x += 5 + skill.name.length + 5 + String(level).length;
      const width = Math.min(16, this.cols - HP_WIDTH - x - 2), fraction = to <= from ? 1 : (xp - from) / (to - from);
      for (let i = 0; i < width; i++) base(p, x + i, 0, ' ', 'WHITE_BRIGHT', i < Math.round(fraction * width) ? 'GREEN' : 'BLACK_BRIGHT');
    }
    this.hpBar(p, this.cols - HP_WIDTH);
  }

  /** The last few messages, fading, over the bottom of the map. */
  recentLog(p) {
    const game = this.game, M = this.map.rect, now = performance.now();
    const recent = game.log.filter(e => !(game.compactChat && e.kind === 'ROUTINE')).slice(-3);
    recent.forEach((e, i) => {
      if (e.at && now - e.at > 10_000) return;
      const text = (e.kind === 'CHAT' ? e.from + ': ' : '') + e.text;
      p.fg = e.kind === 'CHAT' ? game.playerColor(e.colorIndex) : e.kind === 'ROUTINE' ? 'BLACK_BRIGHT' : e.kind === 'NOTICE' ? 'WHITE_BRIGHT' : 'DEFAULT';
      p.bg = null;
      p.put(M.x + 1, M.bottom - 3 + i - (recent.length - 3), fit(text, M.w - 2));
    });
  }

  /** The tab buttons along the bottom, wrapping onto more rows if they must; returns how many rows they took. */
  bottomBar(p) {
    const buttons = [...(this.sideways ? [] : [['INVENTORY', 'Inventory']]), ['SKILLS', 'Skills'], ['MONSTERS', 'Monsters'], ['STATS', 'Stats'],
      ['SETTINGS', 'Settings'], ['FRIENDS', 'Friends'], ['MAP', 'Map'], ['LOG', 'Log'], ['CHAT', 'Chat'], ['LOGOUT', 'Log Out']];
    const lines = [[]];
    let x = 0;
    for (const button of buttons) {
      const width = button[1].length + 2;
      if (x > 0 && x + width > this.cols) { lines.push([]); x = 0; }
      lines[lines.length - 1].push({ button, x });
      x += width + 1;
    }
    const first = this.rows - lines.length;
    lines.forEach((line, i) => {
      const y = first + i;
      for (const { button: [id, label], x: bx } of line) {
        const open = this.sheet === id;
        p.fg = id === 'CHAT' ? 'CYAN_BRIGHT' : id === 'LOGOUT' ? 'RED_BRIGHT' : open ? 'DEFAULT' : 'WHITE';
        p.bg = open ? 'BLACK_BRIGHT' : null; p.bold = open;
        p.put(bx, y, ' ' + label + ' ');
        p.bg = null; p.bold = false;
        this.clicks.option(BASE, bx, y, label.length + 2, 1, () => {
          if (id === 'CHAT') this.chat = '';
          else if (id === 'LOGOUT') this.openPopup('Log out?', 'WHITE_BRIGHT', [
            { label: 'Yes, log out', action: () => this.logout() }], bx, y - 5);
          else {
            if (id === 'SKILLS' && this.sheet === 'SKILLS') this.skillsHome();
            this.sheet = id;
          }
        });
      }
    });
    return lines.length;
  }

  /** The open menu. */
  sheetPanel(p, r) {
    if (this.sheet === 'LOG') {
      this.log(p, r);
      p.fg = 'BLACK_BRIGHT'; p.put(r.x + 2, r.y, 'Log');
      return;
    }
    if (this.sheet !== 'INVENTORY') return this.rightPanel(p, r, this.sheet);
    // the grid on the left, the picked item and your equipment beside it
    const grid = R(r.x, r.y, LEFT_WIDTH, r.h);
    this.inventory(p, grid);
    p.fg = 'BLACK_BRIGHT'; p.put(grid.x + 2, grid.y, 'Inventory');
    const side = R(grid.right + 1, r.y, r.w - LEFT_WIDTH, r.h);
    if (side.w < 20) return;
    const equipment = R(side.x, side.bottom - 3, side.w, 4);
    const item = R(side.x, side.y, side.w, side.h - 4);
    p.fg = 'DEFAULT'; p.rect(item);
    this.itemPanel(p, item);
    this.equipment(p, equipment);
  }

  /** Your hp: "[  5 / 10  ]", red as far as it goes, the numbers in bright white over it. */
  hpBar(p, x) {
    const game = this.game, inner = HP_WIDTH - 2;
    const filled = Math.ceil((game.maxHp <= 0 ? 0 : Math.max(0, Math.min(1, game.hp / game.maxHp))) * inner - 1e-9);
    let text = game.hp + ' / ' + game.maxHp;
    if (text.length > inner) text = game.hp + '/' + game.maxHp;
    const start = (inner - text.length) >> 1;
    base(p, x, 0, '[', 'BLACK_BRIGHT', null);
    for (let i = 0; i < inner; i++) base(p, x + 1 + i, 0, text[i - start] || ' ', 'WHITE_BRIGHT', i < filled ? 'RED' : null, true);
    base(p, x + 1 + inner, 0, ']', 'BLACK_BRIGHT', null);
  }

  /** A hint in a phone's words: tap for click, hold for right-click. */
  hint(text) {
    return this.phone ? text.replace(/right-click|\[right\]/g, m => m === '[right]' ? '[hold]' : 'hold')
      .replace(/left-click|click/g, 'tap') : text;
  }

  /** Enter on an explanation: "Got it". */
  dismissTutorial() {
    const tut = this.tutorial;
    if (tut && tut.dismissable() && tut.text(this.game).length) { tut.dismiss(); return true; }
    return false;
  }

  /** An account's first login: "Would you like a tutorial? <Yes> <No>" (TutorialOffer); y / Enter, n / Esc. */
  offerBox(p) {
    const M = this.map.rect;
    const w = Math.min(40, M.w - 2), h = 7, x = M.x + ((M.w - w) >> 1), y = M.y + ((M.h - h) >> 1);
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(x, y, w, h);
    p.rect(R(x, y, w, h));
    p.fg = 'YELLOW_BRIGHT'; p.bold = true; p.put(x + 2, y + 2, 'Would you like a tutorial?'); p.bold = false;
    this.clicks.add(MODAL, x, y, w, h, () => {});
    const yes = '<Yes>', no = '<No>', ry = y + 4;
    p.fg = 'GREEN_BRIGHT'; p.put(x + 8, ry, yes);
    p.fg = 'DEFAULT'; p.put(x + w - 8 - no.length, ry, no);
    this.clicks.option(MODAL, x + 8, ry, yes.length, 1, () => this.answerOffer(true));
    this.clicks.option(MODAL, x + w - 8 - no.length, ry, no.length, 1, () => this.answerOffer(false));
    p.bg = null;
  }

  answerOffer(yes) {
    this.offer = false;
    if (yes && this.onTutorial) this.onTutorial();
  }

  /** The top row: the xp tracker over the map, then your name, total level, hp and Log Out (PlayerDetailView). */
  header(p) {
    const game = this.game, c = game.content;
    const skill = game.tracker.skill;
    if (skill) {
      let x = XP.x;
      this.skillIcon(x, 0, skill); x += 3;
      const level = game.level(skill), goal = game.goals.get(skill.id);
      p.fg = 'DEFAULT'; p.put(x, 0, skill.name); x += skill.name.length + 1;
      const lv = 'Lv. ' + level + (goal && goal.level > level ? '→' + goal.level : '');
      p.fg = 'YELLOW_BRIGHT'; p.put(x, 0, lv); x += lv.length + 1;
      const xp = game.experience(skill), { from, to } = this.xpBar(skill);
      const width = 26, fraction = to <= from ? 1 : Math.max(0, Math.min(1, (xp - from) / (to - from)));
      const label = xp + '/' + to, start = (width - label.length) >> 1;
      for (let i = 0; i < width; i++) {
        const ch = label[i - start] || ' ';
        base(p, x + i, 0, ch, 'WHITE_BRIGHT', i < Math.round(fraction * width) ? 'GREEN' : 'BLACK_BRIGHT');
      }
      x += width + 1;
      const rate = game.xpPerHour();
      if (rate) {
        p.fg = 'DEFAULT'; p.put(x, 0, short(rate) + '/h'); x += short(rate).length + 3;
        const left = (to - xp) / rate * 3_600_000;
        p.fg = 'BLACK_BRIGHT'; p.put(x, 0, '~' + (left < 60_000 ? Math.ceil(left / 1000) + 's' : duration(left).replace(/ \d+s$/, '')));
      }
    }
    // name, total level, hp
    if (game.blessing) {
      const marks = tierMarks(game.blessing.product.tier);
      p.fg = 'WHITE_BRIGHT'; p.set(NAME_X - 5, 0, '┼');
      p.fg = game.skillColor(game.blessing.skill);
      p.set(NAME_X - 4, 0, marks[0]); p.set(NAME_X - 1, 0, marks[1]);
      this.skillIcon(NAME_X - 3, 0, game.blessing.skill);
    }
    p.fg = game.playerColor(game.colorIndex); p.bold = true;
    p.put(NAME_X, 0, pad(game.name, 24)); p.bold = false;
    p.fg = 'YELLOW_BRIGHT'; p.put(NAME_X + 24, 0, (' [Total Lv. ' + game.totalLevel() + ']').padEnd(16));
    this.hpBar(p, HP_X);
    p.fg = 'RED_BRIGHT'; p.put(COLS - 8, 1, 'Log Out');
    this.clicks.option(BASE, COLS - 8, 1, 7, 1, () => this.openPopup('Log out?', 'WHITE_BRIGHT', [
      { label: 'Yes, log out', action: () => this.logout() }], COLS - 30, 2));
  }

  tabHeaders(p) {
    const header = (x, k, title, selected, onClick) => {
      p.fg = 'BLACK_BRIGHT'; p.bg = null; p.put(x, HEADER_ROW, k);
      if (selected) { p.fg = 'DEFAULT'; p.bg = 'BLACK_BRIGHT'; p.bold = true; }
      p.put(x + k.length + 1, HEADER_ROW, title);
      p.bg = null; p.bold = false;
      this.clicks.option(BASE, x, HEADER_ROW, k.length + 1 + title.length, 1, onClick);
    };
    header(INVENTORY.x + 1, 'i', 'Inventory', this.tab === 'INVENTORY' && !this.mapTab, () => this.pick('INVENTORY'));
    header(MAP.x + 1, 'v', 'Map', this.mapTab, () => { this.mapTab = true; });
    let x = MAP.right + 2;
    for (const tab of TABS_LIST.slice(1)) {
      header(x, tab.key, tab.title, this.tab === tab.id && !this.mapTab, () => this.pick(tab.id));
      x += tab.key.length + 1 + tab.title.length + HEADER_GAP;
    }
  }

  /** A skill's or monster's pixel picture over cells x and x + 1 of row y (instead of its symbol), under any popup. */
  icon(x, y, sprite) {
    (this.iconsOnTop ? this.topIcons : this.icons).push({ x, y, sprite });
  }

  skillIcon(x, y, skill) {
    this.icon(x, y, skillSprite(this.game.worldName, skill, this.game.skillColor(skill), this.game.skillColor(skill, true)));
    return true;
  }

  monsterIcon(x, y, type) {
    this.icon(x, y, monsterSprite(this.game.worldName, type, this.game.monsterColor(type)));
  }

  pick(tab) {
    this.mapTab = false;
    if (tab === 'SKILLS' && this.tab === 'SKILLS') this.skillsHome();
    this.tab = tab;
    this.inv.focused = tab === 'INVENTORY';
  }

  // ---- the inventory (InventoryPanel) -------------------------------------------------------------------------------
  /** Cell rows that fit in an item grid's frame. */
  gridRows(r) {
    return Math.max(1, Math.floor((r.h - 1) / CELL_STEP_Y));
  }

  /**
   * The inventory grid. {@code compact} (a phone sideways): 4 x 2 cells, ".#:" over a 3-wide count ("100", "10k"),
   * no "+P" (a prestige version's count is yellow instead, a blessing's white) -- three across in 16 columns.
   */
  inventory(p, r, compact) {
    const width = compact ? 4 : CELL_WIDTH, stepX = compact ? 5 : CELL_STEP_X, stepY = compact ? 3 : CELL_STEP_Y;
    const game = this.game, INVENTORY_ROWS = compact ? Math.max(1, Math.floor((r.h - 1) / stepY)) : this.gridRows(r);
    const INVENTORY_COLUMNS = Math.max(1, Math.floor((r.w - (compact ? 1 : 3)) / stepX));
    p.fg = 'DEFAULT'; p.rect(r);
    const slots = game.slots(game.inventory);
    const inv = this.inv;
    inv.selected = Math.max(0, Math.min(inv.selected, slots.length - 1));
    const row = Math.floor(inv.selected / INVENTORY_COLUMNS);
    if (row < inv.top) inv.top = row; else if (row >= inv.top + INVENTORY_ROWS) inv.top = row - INVENTORY_ROWS + 1;
    const totalRows = Math.ceil(slots.length / INVENTORY_COLUMNS);
    inv.top = Math.max(0, Math.min(inv.top, Math.max(0, totalRows - INVENTORY_ROWS)));
    const xs = [], ys = [];
    for (let c = 0; c < INVENTORY_COLUMNS; c++) xs.push(r.x + (compact ? 1 : 2) + c * stepX);
    for (let rr = 0; rr < INVENTORY_ROWS; rr++) ys.push(r.y + (compact ? 1 : 2) + rr * stepY);
    cellLines(p, r, xs, ys);
    this.invGrid = { r, xs, ys, width };
    for (let rr = 0; rr < INVENTORY_ROWS; rr++) {
      for (let c = 0; c < INVENTORY_COLUMNS; c++) {
        const index = (inv.top + rr) * INVENTORY_COLUMNS + c;
        const selected = this.shownTab() === 'INVENTORY' && index === inv.selected;
        if (index >= slots.length) { p.bg = null; p.fill(xs[c], ys[rr], width, 2); continue; }
        p.bg = selected ? 'BLACK_BRIGHT' : null;
        if (compact) this.compactCell(p, xs[c], ys[rr], slots[index].product, slots[index].count, selected);
        else this.cell(p, xs[c], ys[rr], slots[index].product, String(slots[index].count), CELL_WIDTH, selected);
        p.bg = null;
        this.clicks.option(BASE, xs[c], ys[rr], width, 2, (button, cx, cy) => {
          this.pick('INVENTORY');
          inv.selected = index;
          if (button === 0) this.useItem(slots[index].product);
          else this.itemPopup(slots[index], xs[c] + width, ys[rr]);
        });
      }
    }
    // AFK crafting stops at half: that's the cap to show, highlighted
    const cap = game.afkCrafting() ? Math.floor(game.inventorySlots() / 2) : game.inventorySlots();
    const used = slots.length + '/', usedX = r.x + (compact ? 1 : 2);
    p.fg = slots.length >= cap ? 'RED_BRIGHT' : 'BLACK_BRIGHT';
    p.put(usedX, r.bottom, used);
    if (game.afkCrafting()) { p.fg = 'WHITE_BRIGHT'; p.bg = 'RED'; }
    p.put(usedX + used.length, r.bottom, String(cap));
    p.bg = null;
    scrollbar(p, r.right, r.y, r.bottom, inv.top, INVENTORY_ROWS, totalRows);
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h, step => {
      inv.selected = Math.max(0, Math.min(slots.length - 1, inv.selected + step * INVENTORY_COLUMNS));
      inv.top = Math.max(0, inv.top + step);
    });
  }

  /** One item: its symbol with tier marks (a drop: a trophy mark), "+P" / a blessing's cross, and the count under it. */
  cell(p, x, y, product, count, width, selected) {
    const game = this.game, c = game.content;
    const recipe = product.blesses || c.madeBy(product);
    const monster = recipe ? null : c.droppedBy(product);
    const bright = game.itemColor(product), dark = dim(bright);
    p.bold = selected;
    p.fg = 'DEFAULT'; p.put(x, y, ' '.repeat(width));
    this.symbol(p, x, y, product);
    if (product.blesses) { p.fg = 'WHITE_BRIGHT'; p.set(x + 4, y, '┼'); }
    else if (product.prestige > 0) {
      const plus = '+' + product.prestige;
      p.fg = 'YELLOW_BRIGHT'; p.put(x + 4, y, x + 4 + plus.length <= x + width ? plus : '+*');
    }
    const number = 'x' + count;
    p.fg = 'DEFAULT'; p.put(x, y + 1, ' '.repeat(Math.max(0, width - number.length)));
    p.fg = selected ? 'WHITE' : 'BLACK_BRIGHT'; p.put(x + width - number.length, y + 1, 'x');
    p.fg = 'WHITE'; p.put(x + width - number.length + 1, y + 1, count);
    p.bold = false;
  }

  /** A compact cell: ".#:" over the count right-aligned in 3 ("100", "10k"). */
  compactCell(p, x, y, product, count, selected) {
    p.bold = selected;
    p.fg = 'DEFAULT'; p.put(x, y, '    '); p.put(x, y + 1, '    ');
    this.symbol(p, x, y, product);
    const number = count < 1000 ? String(count) : count < 1e6 ? Math.floor(count / 1000) + 'k' : Math.floor(count / 1e6) + 'M';
    p.fg = product.blesses ? 'WHITE_BRIGHT' : product.prestige > 0 ? 'YELLOW_BRIGHT' : 'WHITE';
    p.put(x + 4 - number.length, y + 1, number);
    p.bold = false;
  }

  /** Four cells: an item's picture (its skill's, a drop's monster's) with its tier marks either side; a drop "Ψ". */
  symbol(p, x, y, product) {
    const game = this.game, c = game.content;
    const recipe = product.blesses || c.madeBy(product);
    const monster = recipe ? null : c.droppedBy(product);
    const bright = game.itemColor(product);
    const tiered = product.blesses ? recipe.product : (product.base || product);
    const marks = monster ? 'Ψ ' : tiered.tier >= 0 ? tierMarks(tiered.tier) : '  ';
    p.fg = dim(bright); p.set(x, y, marks[0]); p.set(x + 3, y, marks[1]);
    if (monster) this.monsterIcon(x + 1, y, monster);
    else if (recipe) this.skillIcon(x + 1, y, recipe.skill);
    else { p.fg = bright; p.set(x + 1, y, '?'); }
  }

  useItem(product) {
    if (product.blesses) { this.game.useBlessing(product); return; }
    this.using = product;
    if (!this.phone) this.mapTab = true;
  }

  itemPopup(slot, x, y) {
    const game = this.game, product = slot.product;
    const options = [];
    if (product.blesses) options.push({ label: 'Use blessing', action: () => game.useBlessing(product) });
    else if (game.content.droppedBy(product)) options.push({ label: 'Use', action: () => this.useItem(product) });
    else {
      options.push({ label: 'Equip weapon', action: () => game.equip('WEAPON', product) });
      options.push({ label: 'Equip armor', action: () => game.equip('ARMOR', product) });
      options.push({ label: 'Equip both', action: () => { game.equip('WEAPON', product); game.equip('ARMOR', product); } });
      options.push({ label: 'Use', action: () => this.useItem(product) });
    }
    if (product === game.weapon || product === game.armor) {
      options.push({ label: 'Unequip', action: () => {
        if (product === game.weapon) game.unequip('WEAPON');
        if (product === game.armor) game.unequip('ARMOR');
      } });
    }
    options.push({ label: 'Drop stack', keepOpen: true, action: () => this.openPopup(product.name, game.itemColor(product), [
      { label: "Drop it? It's gone for good", color: 'RED_BRIGHT', action: () => game.discard(product, slot.count) }], x, y, 1) });
    this.openPopup(product.name, game.itemColor(product), options, x, y);
  }

  equipment(p, r) {
    const game = this.game;
    p.fg = 'DEFAULT'; p.rect(r);
    // narrow (a phone's compact inventory): "Wpn" / "Arm"
    const narrow = textWidth(r) < 16;
    const row = (y, name, item, nothing, slot) => {
      let x = textX(r);
      if (narrow) name = name === 'Weapon' ? 'Wpn' : 'Arm';
      p.fg = 'YELLOW'; p.put(x, y, name); x += narrow ? 4 : 7;
      if (!item) { p.fg = 'BLACK_BRIGHT'; p.put(x, y, nothing); return; }
      this.symbol(p, x, y, item); x += 4;
      const count = short(game.inventory.get(item) || 0);
      const plus = item.prestige > 0 ? ' +' + item.prestige : '';
      const gap = x + plus.length + 2 + count.length <= textX(r) + textWidth(r);
      p.fg = 'YELLOW_BRIGHT'; p.put(x, y, plus); x += plus.length + (gap ? 1 : 0);
      p.fg = 'BLACK_BRIGHT'; p.put(x, y, 'x');
      p.fg = 'WHITE'; p.put(x + 1, y, count);
      this.clicks.option(BASE, textX(r), y, textWidth(r), 1, () => game.unequip(slot));
    };
    row(firstRow(r), 'Weapon', game.weapon, 'fists', 'WEAPON');
    row(firstRow(r) + 1, 'Armor', game.armor, 'none', 'ARMOR');
  }

  // ---- the map's frame (MapView): where you are, a blessing, hp, and arrows to what's off screen ------------------
  mapFrame(b, t) {
    const game = this.game, r = this.map.rect;
    b.fg = 'DEFAULT'; b.rect(r);
    const where = '(' + game.x + ',' + game.y + ')' + (game.running ? ' run' : '');
    b.fg = 'BLACK_BRIGHT'; b.put(r.x + 2, r.y, where);
    if (game.blessing) {
      b.fg = 'WHITE_BRIGHT';
      b.put(r.x + 2 + where.length, r.y, ' blessed ' + (game.blessingStrength > 1 ? 'x' + game.blessingStrength + ' ' : '') + game.blessingLeft);
    }
    if (game.content.combatSkill()) {
      const hp = 'hp ' + game.hp + '/' + game.maxHp;
      b.fg = game.hp * 3 <= game.maxHp ? 'RED_BRIGHT' : 'GREEN';
      b.put(r.right - 1 - hp.length, r.y, hp);
    }
    // home (where you last made something, else the origin) and waypoints, when off screen
    const home = game.lastActionAt || { x: 0, y: 0 };
    if (!this.map.inView(home, game)) {
      const e = edgeTowards(r, game, home);
      t.fg = game.lastActionAt ? 'YELLOW_BRIGHT' : 'WHITE_BRIGHT'; t.bg = null;
      t.set(e.x, e.y, { UP: '↑', DOWN: '↓', LEFT: '←', RIGHT: '→' }[e.side]);
    }
    const marker = (target, fg) => {
      if (this.map.inView(target, game)) return;
      const e = edgeTowards(r, game, target);
      const horizontal = e.side === 'UP' || e.side === 'DOWN';
      t.fg = fg; t.bg = null;
      const line = horizontal ? '═' : '║';
      if (horizontal) { t.set(e.x - 1, e.y, line); t.set(e.x + 1, e.y, line); }
      else { t.set(e.x, e.y - 1, line); t.set(e.x, e.y + 1, line); }
      t.set(e.x, e.y, { UP: '↑', DOWN: '↓', LEFT: '←', RIGHT: '→' }[e.side]);
    };
    for (const node of game.waypoints) marker(node, game.skillColor(node.recipe.skill));
    if (game.friendTarget()) marker(game.friendTarget(), 'CYAN_BRIGHT');
    if (game.buildingWaypoint) marker(game.nearestTile(game.buildingWaypoint), 'WHITE_BRIGHT');
    if (this.using) {
      t.fg = 'YELLOW_BRIGHT'; t.bg = null;
      t.put(r.x + 2, r.bottom, ' Using ' + this.using.name + ' on... (click a node, Esc cancels) ');
    }
    // clicks on the map: left does the obvious thing, right opens its menu (MapMenu.clickTile)
    this.clicks.add(BASE, r.x + 1, r.y + 1, r.w - 2, r.h - 2, (button, cx, cy, px, py) => this.clickMap(button, px, py));
  }

  clickMap(button, px, py) {
    const game = this.game, c = game.content;
    const tile = this.map.tileAt(px, py, game, this.cw, this.ch);
    if (!tile) return;
    if (this.using) {
      if (button === 0) game.useItem(this.using, tile.x, tile.y);
      this.using = null;
      return;
    }
    const here = game.othersAt(tile.x, tile.y);
    const monster = here.find(e => e.kind === 'MONSTER');
    const other = here.find(e => e.kind === 'PLAYER');
    const type = monster ? c.monstersById.get(monster.monsterTypeId) : null;
    const node = c.nodesAt.get(tile.x + ',' + tile.y);
    const building = c.solidBuildingAt(tile.x, tile.y);
    const cx = Math.floor(px / this.cw), cy = Math.floor(py / this.ch);
    if (button === 0) {
      if (monster) game.fight(monster.id);
      else if (node) game.craft(tile.x, tile.y, false);
      else if (building) game.useBuilding(building);
      else game.walkTo(tile.x, tile.y);
      return;
    }
    const walk = { label: 'Walk here', action: () => game.walkTo(tile.x, tile.y) };
    if (monster && type) {
      this.openPopup(monster.name + ' (Lv ' + type.level + ')', game.monsterColor(type), [
        { label: 'Fight', action: () => game.fight(monster.id) }, walk], cx + 2, cy - 1);
    } else if (other) {
      const friended = game.friends.some(f => f.name === other.name);
      this.openPopup(other.name + ' (Lv ' + Math.max(1, other.combatLevel) + ')' + (other.afk ? ' AFK' : ''), game.playerColor(other.colorIndex), [
        { label: 'Trade' }, { label: 'Fight' },
        friended ? { label: 'Unfriend', action: () => game.removeFriend(other.name) } : { label: 'Friend', action: () => game.addFriend(other.name) },
        walk], cx + 2, cy - 1);
    } else if (node) {
      const name = node.recipe.product.name, made = game.madePerCraft(node.recipe);
      const cost = node.recipe.components.length ? node.recipe.components.map(k => k.count + ' x ' + k.product.name).join(', ') : null;
      this.openPopup(name, game.nodeColor(node), [
        { label: 'Craft ' + name + (made > 1 ? ' (x' + made + ')' : ''), note: cost, action: () => game.craft(tile.x, tile.y, false) },
        { label: 'AFK ' + name, action: () => game.craft(tile.x, tile.y, true) }, walk], cx + 2, cy - 1);
    } else if (tile.x === game.x && tile.y === game.y) {
      this.openPopup(game.name + '  Total Lv. ' + game.totalLevel() + '  Combat Lv. ' + game.combatLevel(), game.playerColor(game.colorIndex), [
        { label: 'Broadcast position', action: () => game.broadcastPosition() }], cx + 2, cy - 1);
    } else if (building) {
      this.openPopup(building.info.label, 'YELLOW_BRIGHT', [{ label: 'Use', action: () => game.useBuilding(building) }, walk], cx + 2, cy - 1);
    } else {
      game.walkTo(tile.x, tile.y);
    }
  }

  // ---- the right-hand panel: the selected tab -----------------------------------------------------------------
  /** Which tab's showing: the Map tab, or the selected one (on a phone, the open sheet's). */
  shownTab() {
    return this.phone ? this.sheet : this.mapTab ? 'MAP' : this.tab;
  }

  rightPanel(p, r, tab) {
    p.fg = 'DEFAULT'; p.rect(r);
    const title = tab === 'MAP' ? 'Map' : TABS_LIST.find(t => t.id === tab).title;
    p.fg = 'BLACK_BRIGHT'; p.put(textX(r), r.y, title);
    if (tab === 'MAP') return this.mapPanel(p, r);
    switch (tab) {
      case 'INVENTORY': return this.itemPanel(p, r);
      case 'SKILLS': return this.skills.page ? (this.skills.details ? this.skillPage(p, r, this.skills.page) : this.skillOverview(p, r, this.skills.page))
        : this.skillsPage(p, r);
      case 'MONSTERS': return this.monsterPanel(p, r);
      case 'STATS': return this.statsPanel(p, r);
      case 'SETTINGS': return this.settingsPanel(p, r);
      case 'FRIENDS': return this.friendsPanel(p, r);
    }
  }

  itemPanel(p, r) {
    const game = this.game, c = game.content;
    let y = firstRow(r);
    const slots = game.slots(game.inventory);
    const slot = slots[Math.min(this.inv.selected, slots.length - 1)];
    if (!slot) { p.fg = 'BLACK_BRIGHT'; p.put(textX(r), y, 'Nothing in your inventory yet.'); return; }
    const item = slot.product;
    p.fg = game.itemColor(item); p.bold = true; p.put(textX(r), y++, fit(item.name, textWidth(r))); p.bold = false;
    p.fg = 'DEFAULT'; p.put(textX(r), y++, fit('you have ' + (game.inventory.get(item) || 0) + ', stacks of ' + game.stackSize(item), textWidth(r)));
    const recipe = c.madeBy(item), monster = c.droppedBy(item);
    if (recipe) {
      p.fg = 'BLACK_BRIGHT'; p.put(textX(r), y++, fit('made by ' + recipe.skill.name + ' [Lv. ' + recipe.levelNeeded + ']', textWidth(r)));
      const usedIn = c.craftingSkills().filter(s => s.line && s.line.recipes.some(rc => rc.components.some(k => k.product === (item.base || item))));
      if (usedIn.length) p.put(textX(r), y++, fit('used by ' + usedIn.map(s => s.name).join(', '), textWidth(r)));
    } else if (monster) {
      p.fg = 'BLACK_BRIGHT'; p.put(textX(r), y++, fit('dropped by ' + monster.name + ' -- a trophy', textWidth(r)));
    }
    if (item.prestige > 0) {
      p.fg = 'YELLOW_BRIGHT';
      p.put(textX(r), y++, fit('prestige ' + item.prestige + ': +' + game.meta.prestigeDamage * item.prestige + ' damage as a weapon', textWidth(r)));
    }
    if (item === game.weapon || item === game.armor) {
      p.fg = 'YELLOW'; p.put(textX(r), y++, 'equipped as your ' + (item === game.weapon ? 'weapon' : '') + (item === game.weapon && item === game.armor ? ' and ' : '') + (item === game.armor ? 'armor' : ''));
    }
    p.fg = 'BLACK_BRIGHT';
    p.put(textX(r), hintRow(r), fit(this.hint('[click] use  [right] more'), textWidth(r)));
  }

  /** The Map tab: points of interest, then your waypoints (MapMenu). */
  mapPanel(p, r) {
    const game = this.game;
    let row = firstRow(r);
    if (this.using) {
      p.fg = 'YELLOW_BRIGHT'; p.put(textX(r), row++, fit('Using ' + this.using.name + ' on...', textWidth(r)));
      row++;
    }
    // "Bank 12 steps (2,13)": the place only where it fits
    const where = (name, at) => {
      const text = name + ' ' + game.stepsTo(at) + ' steps', place = ' (' + at.x + ',' + at.y + ')';
      return text.length + place.length <= textWidth(r) - 2 ? text + place : fit(text, textWidth(r) - 2);
    };
    p.fg = 'DEFAULT'; p.put(textX(r), row++, 'Points of interest');
    let any = false;
    for (const kind of ['CHURCH', 'BANK', 'MERCHANT', 'GYM', 'STATION']) {
      const nearest = game.nearestBuilding(kind);
      if (!nearest || row >= hintRow(r)) continue;
      any = true;
      const at = game.nearestTile(nearest), heading = nearest === game.buildingWaypoint;
      p.fg = heading ? 'WHITE_BRIGHT' : 'WHITE'; p.bold = heading;
      p.put(textX(r) + 2, row, where(nearest.info.label, at));
      p.bold = false;
      this.clicks.option(BASE, textX(r), row++, textWidth(r), 1, () => game.toggleBuildingWaypoint(nearest));
    }
    if (!any) { p.fg = 'BLACK_BRIGHT'; p.put(textX(r) + 2, row++, 'none found yet'); }
    row++;
    p.fg = 'DEFAULT'; p.put(textX(r), row++, 'Waypoints');
    if (!game.waypoints.length && !game.buildingWaypoint) {
      p.fg = 'BLACK_BRIGHT';
      for (const line of wrap(this.hint("none yet: click a tier on a skill's page"), textWidth(r) - 2)) p.put(textX(r) + 2, row++, line);
    }
    if (game.buildingWaypoint && row < hintRow(r)) {
      const at = game.nearestTile(game.buildingWaypoint);
      p.fg = 'WHITE_BRIGHT';
      p.put(textX(r) + 2, row++, where(game.buildingWaypoint.info.label, at));
    }
    for (const node of game.waypoints) {
      if (row >= hintRow(r)) break;
      this.skillIcon(textX(r), row, node.recipe.skill);
      p.fg = 'DEFAULT';
      p.put(textX(r) + 2, row++, where(node.recipe.product.name, node));
    }
    p.fg = 'BLACK_BRIGHT';
    p.put(textX(r), hintRow(r), fit(this.hint('[click] map: walk  [right] more'), textWidth(r)));
  }

  // ---- skills (SkillsMenu) --------------------------------------------------------------------------------------------
  skillRow(p, r, skill, y, nameWidth, selected) {
    const game = this.game;
    this.skillIcon(textX(r), y, skill);
    const level = game.level(skill);
    p.fg = 'DEFAULT'; p.bold = selected;
    p.put(textX(r) + 2, y, '[' + pad(level, 3) + ' / ' + game.content.maxLevel + ']');
    p.bold = false;
    p.fg = TYPE_COLOR[skill.type]; p.set(textX(r) + 14, y, '•');
    const x = textX(r) + 16;
    p.fg = 'DEFAULT'; p.bold = selected; p.put(x, y, skill.name); p.bold = false;
    const prestige = game.getPrestige(skill);
    if (prestige > 0) { p.fg = 'YELLOW_BRIGHT'; p.put(x + skill.name.length, y, ' +' + prestige); }
    const barX = x + nameWidth + 1, width = textX(r) + textWidth(r) - barX;
    if (width < 4) return;
    const c = game.content, xp = game.experience(skill), max = level >= c.maxLevel;
    const from = game.meta.xpPerLevel[Math.max(0, level - 1)], to = max ? from : c.xpToFinish(level);
    const fraction = max ? 1 : to <= from ? 0 : Math.max(0, Math.min(1, (xp - from) / (to - from)));
    let text = max ? (skill.type === 'COMBAT' ? 'MAX' : 'MAX - p on its page to prestige') : xp + '/' + to;
    const inner = width - 2;
    if (text.length > inner) text = max ? 'MAX' : short(xp) + '/' + short(to);
    const filled = Math.round(fraction * inner);
    base(p, barX, y, '[', 'BLACK_BRIGHT', null);
    for (let i = 0; i < inner; i++) base(p, barX + 1 + i, y, text[i - (inner - text.length)] || ' ', 'WHITE_BRIGHT', i < filled ? 'GREEN' : null);
    base(p, barX + 1 + inner, y, ']', 'BLACK_BRIGHT', null);
  }

  nameWidth() {
    const game = this.game;
    return Math.max(6, ...game.content.skills.map(s => (s.name + (game.getPrestige(s) > 0 ? ' +' + game.getPrestige(s) : '')).length));
  }

  skillList(p, r) {
    const game = this.game, skills = this.sortedSkills(), s = this.skills;
    const rows = r.h - 2;
    const start = Math.max(0, Math.min(s.index - (rows >> 1), skills.length - (rows - 1)));
    const nameWidth = this.nameWidth();
    for (let i = start; i < Math.min(skills.length, start + rows - 1); i++) {
      const y = firstRow(r) + (i - start), skill = skills[i];
      const selected = i === s.index;
      if (selected) { p.fg = 'WHITE_BRIGHT'; p.set(textX(r) - 1, y, '>'); }
      this.skillRow(p, r, skill, y, nameWidth, selected);
      this.clicks.option(BASE, textX(r), y, textWidth(r), 1, (button, cx) => {
        s.index = i;
        if (button === 0) { s.page = skill; s.tier = 0; s.details = false; return; }
        this.openPopup(skill.name, game.skillColor(skill), [
          { label: 'Open page', action: () => { s.page = skill; s.tier = 0; s.details = false; } },
          { label: 'Share', action: () => game.shareSkill(skill) }], cx, y + 1);
      });
    }
    if (!skills.length) { p.fg = 'BLACK_BRIGHT'; p.put(textX(r), firstRow(r), 'No skills yet -- walk around to discover some.'); }
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h, step => { s.index = Math.max(0, Math.min(skills.length - 1, s.index + step)); });
    p.fg = 'BLACK_BRIGHT';
    p.put(textX(r), hintRow(r), fit(this.hint('[click] its page  [right] share'), textWidth(r)));
  }

  /** Your friends: on or not, combat and total level. Click one for what you can do with them. */
  friendsPanel(p, r) {
    const game = this.game, viewing = game.friends.find(f => f.name === this.friendView);
    if (viewing) return this.friendSkills(p, r, viewing);
    let y = firstRow(r);
    if (!game.friends.length) {
      p.fg = 'BLACK_BRIGHT';
      for (const line of wrap(this.hint('none yet: right-click a player to friend them'), textWidth(r))) p.put(textX(r), y++, line);
    }
    for (const friend of game.friends) {
      if (y >= hintRow(r)) break;
      const rowY = y;
      p.fg = friend.online ? 'GREEN_BRIGHT' : 'BLACK_BRIGHT'; p.set(textX(r), y, '\u2022');
      p.fg = friend.online ? 'WHITE_BRIGHT' : 'BLACK_BRIGHT'; p.put(textX(r) + 2, y, fit(friend.name, 14));
      p.fg = friend.online ? 'DEFAULT' : 'BLACK_BRIGHT';
      p.put(textX(r) + 17, y, 'Cb ' + friend.combatLevel);
      p.put(textX(r) + 25, y, 'Lv ' + friend.totalLevel);
      const here = friend.name === game.friendWaypoint;
      this.clicks.option(BASE, textX(r), y++, textWidth(r), 1, () => this.openPopup(friend.name, 'WHITE_BRIGHT', [
        { label: 'Message', action: friend.online ? () => {
          this.chatTo = friend.name; this.chat = '';
          if (this.phone) this.sheet = 'LOG';
        } : null },
        { label: here ? 'Clear waypoint' : 'Waypoint', action: here ? () => { game.friendWaypoint = null; }
          : friend.position ? () => { game.friendWaypoint = friend.name; } : null },
        { label: 'View skills', action: () => { this.friendView = friend.name; } },
        { label: 'Remove friend', color: 'RED_BRIGHT', action: () => game.removeFriend(friend.name) }], textX(r) + 4, rowY + 1));
    }
  }

  /** A friend's skill levels, laid out like your Skills list. */
  friendSkills(p, r, friend) {
    const game = this.game, skills = this.sortedSkills(), c = game.content, s = this.skills;
    p.fg = 'WHITE_BRIGHT'; p.put(textX(r), firstRow(r), fit(friend.name + '  Cb ' + friend.combatLevel + '  Lv ' + friend.totalLevel, textWidth(r)));
    const rows = hintRow(r) - firstRow(r) - 2;
    s.friendTop = Math.max(0, Math.min(s.friendTop || 0, skills.length - rows));
    skills.slice(s.friendTop, s.friendTop + rows).forEach((skill, i) => {
      const y = firstRow(r) + 2 + i, prestige = friend.prestige[skill.id] || 0;
      this.skillIcon(textX(r), y, skill);
      p.fg = 'DEFAULT'; p.put(textX(r) + 3, y, '[' + pad(friend.levels[skill.id] || 1, 3) + ' / ' + c.maxLevel + ']');
      p.fg = TYPE_COLOR[skill.type]; p.set(textX(r) + 15, y, '\u2022');
      p.fg = 'DEFAULT'; p.put(textX(r) + 17, y, skill.name);
      if (prestige > 0) { p.fg = 'YELLOW_BRIGHT'; p.put(textX(r) + 17 + skill.name.length, y, ' +' + prestige); }
    });
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h, step => { s.friendTop = (s.friendTop || 0) + step; });
    p.fg = 'WHITE'; p.put(textX(r), hintRow(r), '[\u2190]');
    this.clicks.option(BASE, textX(r), hintRow(r), 3, 1, () => { this.friendView = null; });
  }

  /** The skills, as a list or a grid, with the sort button on the frame: discovery order, tier, colour, and round. */
  skillsPage(p, r) {
    const game = this.game, label = '[sort: ' + SKILL_SORTS[game.skillSort] + ']', x = r.right - 1 - label.length;
    p.fg = 'WHITE'; p.put(x, r.y, label);
    this.clicks.option(BASE, x, r.y, label.length, 1, () => {
      const sorts = Object.keys(SKILL_SORTS);
      game.skillSort = sorts[(sorts.indexOf(game.skillSort) + 1) % sorts.length];
      this.saveSettings();
    });
    return this.skillsAsGrid() ? this.skillGrid(p, r) : this.skillList(p, r);
  }

  /** The skills in the order picked; ties in the order they were found. */
  sortedSkills() {
    const game = this.game, skills = game.content.skills;
    if (game.skillSort === 'TIER') return [...skills].sort((a, b) => SKILL_TIERS.indexOf(a.type) - SKILL_TIERS.indexOf(b.type));
    if (game.skillSort === 'COLOR') return [...skills].sort((a, b) => skillHue(a.colorIndex) - skillHue(b.colorIndex));
    return skills;
  }

  /** Skills as a grid (Settings): a phone's default, the desktop's is the list. */
  skillsAsGrid() {
    return this.game.skillGrid ?? this.phone;
  }

  /**
   * The skills as a grid (a phone's default; Settings > Skills as): each its picture, "Lv. 53" under it ("MAX" at the top; yellow once
   * prestiged) and a thin green bar of the way to the next level. A tap opens its page, holding it Details / Share;
   * dragging scrolls.
   */
  skillGrid(p, r) {
    const game = this.game, c = game.content, skills = this.sortedSkills(), s = this.skills;
    const cellWidth = 8, stepX = cellWidth + 1, stepY = 4;
    const columns = Math.max(1, Math.floor((r.w - 2 + 1) / stepX));
    const visibleRows = Math.max(1, Math.floor((r.h - 3) / stepY));
    const totalRows = Math.ceil(skills.length / columns);
    s.gridTop = Math.max(0, Math.min(s.gridTop || 0, totalRows - visibleRows));
    const left = r.x + 1 + ((r.w - 2 - (columns * stepX - 1)) >> 1);
    for (let row = 0; row < visibleRows; row++) {
      for (let col = 0; col < columns; col++) {
        const i = (s.gridTop + row) * columns + col;
        if (i >= skills.length) break;
        const skill = skills[i], x = left + col * stepX, y = r.y + 1 + row * stepY;
        this.skillIcon(x + 3, y, skill);
        const level = game.level(skill), max = level >= c.maxLevel;
        // its type's bullet (green primary, blue secondary, red tertiary, white combat), then its level
        const label = max ? 'MAX' : 'Lv. ' + level;
        const gap = label.length + 2 <= cellWidth ? ' ' : '';
        const lx = x + ((cellWidth - label.length - 1 - gap.length) >> 1);
        p.fg = TYPE_COLOR[skill.type]; p.set(lx, y + 1, '•');
        p.fg = game.getPrestige(skill) > 0 ? 'YELLOW_BRIGHT' : 'DEFAULT';
        p.put(lx + 1 + gap.length, y + 1, label);
        const xp = game.experience(skill), from = game.meta.xpPerLevel[Math.max(0, level - 1)];
        const to = max ? from : c.xpToFinish(level);
        this.bars.push({ x, y: y + 2, w: cellWidth, fraction: max ? 1 : to <= from ? 0 : Math.max(0, Math.min(1, (xp - from) / (to - from))) });
        this.clicks.option(BASE, x, y, cellWidth, 3, (button, cx) => {
          s.index = i;
          if (button === 0) { s.page = skill; s.tier = 0; s.details = false; return; }
          this.openPopup(skill.name, game.skillColor(skill), [
            { label: 'Open page', action: () => { s.page = skill; s.tier = 0; s.details = false; } },
            { label: 'Share', action: () => game.shareSkill(skill) }], cx, y + 3);
        });
      }
    }
    if (!skills.length) { p.fg = 'BLACK_BRIGHT'; p.put(textX(r), firstRow(r), fit('No skills yet -- walk around to discover some.', textWidth(r))); }
    scrollbar(p, r.right, r.y, r.bottom, s.gridTop, visibleRows, totalRows);
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h, step => { s.gridTop = (s.gridTop || 0) + step; });
    p.fg = 'BLACK_BRIGHT';
    p.put(textX(r), hintRow(r), fit(this.hint('[click] its page  [right] share'), textWidth(r)));
  }

  /** The milestone a goal at {@code level} is about (Goal.toward / what): "skill3-1 unlocks", "skill3-0 x2". */
  goalWhat(skill, level) {
    const c = this.game.content;
    const recipes = skill.line ? skill.line.recipes : [];
    const unlock = recipes.find(rc => rc.levelNeeded === level);
    if (unlock) return { product: unlock.product, text: unlock.product.name + ' unlocks' };
    for (const rc of recipes) {
      for (const times of [2, 3]) {
        if (c.yieldLevel(skill.type, rc.tier, times) === level) return { product: rc.product, text: rc.product.name + ' x' + times };
      }
    }
    const best = recipes.filter(rc => rc.levelNeeded <= level).pop();
    return { product: best ? best.product : null, text: best ? best.product.name : '' };
  }

  skillPage(p, r, skill) {
    const game = this.game, c = game.content, s = this.skills;
    let y = firstRow(r);
    this.skillRow(p, r, skill, y++, this.nameWidth(), true);
    const origin = c.origins.get(skill.id);
    p.fg = TYPE_COLOR[skill.type]; p.put(textX(r), y, skill.type);
    p.fg = 'BLACK_BRIGHT';
    p.put(textX(r) + skill.type.length + 2, y++, fit(origin ? 'found by ' + origin.by + ' on ' + new Date(origin.at).toLocaleDateString()
      : 'found by ? (before this was kept)', textWidth(r) - skill.type.length - 2));
    const prestige = game.getPrestige(skill), lifetime = game.lifetimeExperience(skill), virtual = c.virtualLevelFor(lifetime);
    p.fg = prestige > 0 ? 'YELLOW_BRIGHT' : 'BLACK_BRIGHT';
    p.put(textX(r), y++, fit(comma(lifetime) + ' total xp' + (prestige > 0 ? '  [prestige ' + prestige + ']' : '')
      + (virtual > game.level(skill) ? '  virtual Lv. ' + virtual : ''), textWidth(r)));
    if (skill.type !== 'COMBAT' && skill.line) this.goalRow(p, r, skill, y++);
    const level = game.level(skill);
    if (this.canPrestige(skill)) {
      p.fg = 'YELLOW_BRIGHT'; p.put(textX(r), y, fit(this.phone ? '[tap] prestige: revert to Lv. 1?' : '[p] prestige: revert to Lv. 1?', textWidth(r)));
      const promptY = y;
      this.clicks.option(BASE, textX(r), y, 32, 1, () => this.askPrestige(skill, textX(r), promptY + 1));
    }
    y++;
    p.fg = 'DEFAULT'; p.bold = true; p.put(textX(r), y++, 'Recipes'); p.bold = false;
    if (!skill.line) {
      p.fg = 'BLACK_BRIGHT'; p.put(textX(r), y, 'none (trained by fighting)');
    } else {
      const tiers = skill.line.recipes;
      s.tier = Math.max(0, Math.min(tiers.length - 1, s.tier));
      const picked = tiers[s.tier];
      // - Each costs 2 x skill3, 1 x skill5 of same tier
      let x = textX(r);
      const right = textX(r) + textWidth(r);
      p.fg = 'BLACK_BRIGHT';
      if (!picked.components.length) p.put(x, y, fit('- Raw: costs nothing', right - x));
      else {
        p.put(x, y, '- Each costs '); x += 13;
        picked.components.forEach((k, i) => {
          const makes = c.madeBy(k.product.base || k.product);
          const text = (i > 0 ? ', ' : '') + k.count + ' x ';
          const name = makes ? makes.skill.name : k.product.name;
          if (x + text.length + name.length > right) return;
          p.fg = 'BLACK_BRIGHT'; p.put(x, y, text); x += text.length;
          p.fg = makes ? game.skillColor(makes.skill) : 'DEFAULT'; p.put(x, y, name);
          if (makes) this.clicks.option(BASE, x, y, name.length, 1, () => this.skillLink(skill, makes.skill, makes.tier));
          x += name.length;
        });
        p.fg = 'BLACK_BRIGHT';
        if (x + 13 <= right) p.put(x, y, ' of same tier');
      }
      y++;
      // - Used by: skill2, skill3
      const usedBy = c.craftingSkills().filter(o => o !== skill && o.line && o.line.recipes.some(rc => rc.components.some(k => {
        const m = c.madeBy(k.product); return m && m.skill === skill;
      })));
      x = textX(r);
      p.fg = 'BLACK_BRIGHT';
      if (!usedBy.length) p.put(x, y, '- Used by: nothing yet');
      else {
        p.put(x, y, '- Used by: '); x += 11;
        usedBy.forEach((o, i) => {
          const text = i > 0 ? ', ' : '';
          if (x + text.length + o.name.length > right) return;
          p.fg = 'BLACK_BRIGHT'; p.put(x, y, text); x += text.length;
          p.fg = game.skillColor(o); p.put(x, y, o.name);
          this.clicks.option(BASE, x, y, o.name.length, 1, () => this.skillLink(skill, o));
          x += o.name.length;
        });
      }
      y++;
      p.fg = 'BLACK_BRIGHT'; p.put(textX(r), y++, '      x1     x2    x3');
      const color = game.skillColor(skill);
      for (let tt = 0; tt < tiers.length && y < hintRow(r) - 2; tt++, y++) {
        const recipe = tiers[tt], here = tt === s.tier;
        if (here) { p.fg = 'WHITE_BRIGHT'; p.set(textX(r) - 1, y, '>'); }
        p.bold = here;
        const waypoint = game.hasWaypoint(recipe);
        const marks = waypoint ? '[]' : tierMarks(tt);
        p.fg = waypoint ? color : dim(color);
        p.set(textX(r), y, marks[0]); p.set(textX(r) + 3, y, marks[1]);
        this.skillIcon(textX(r) + 1, y, skill);
        const at = [recipe.levelNeeded, c.yieldLevel(skill.type, tt, 2), c.yieldLevel(skill.type, tt, 3)];
        [8, 15, 21].forEach((rightX, col) => {
          const text = at[col] < 0 ? '-' : String(at[col]);
          const reached = at[col] >= 0 && level >= at[col];
          p.fg = reached ? 'GREEN_BRIGHT' : here ? 'WHITE' : 'BLACK_BRIGHT';
          p.put(textX(r) + rightX - text.length, y, text);
        });
        p.bold = false;
        const rowY = y;
        this.clicks.option(BASE, textX(r), y, Math.min(textWidth(r), 26), 1, (button, cx) => {
          s.tier = tt;
          if (button === 0) { game.toggleWaypoint(recipe); return; }
          const options = [{ label: waypoint ? 'Clear waypoint' : 'Set waypoint', action: () => game.toggleWaypoint(recipe) }];
          if (game.waypoints.some(n => n.recipe !== recipe) || game.buildingWaypoint) {
            options.push({ label: 'Clear other waypoints', action: () => game.clearOtherWaypoints(recipe) });
          }
          options.push({ label: game.highlighted.has(recipe) ? 'Unmark nodes' : 'Mark all nodes', action: () => game.toggleHighlight(recipe) });
          this.openPopup(recipe.product.name, color, options, textX(r) + 26, rowY + 1);
        });
      }
      const made = game.madePerCraft(picked);
      p.fg = color; p.put(textX(r), ++y, fit(picked.product.name + (made > 1 ? '  x' + made + ' a craft' : ''), textWidth(r)));
    }
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h, step => { s.tier += step; });
    p.fg = 'BLACK_BRIGHT';
    // a tier sets a waypoint, a cost opens its skill
    p.put(textX(r), hintRow(r) - 1, fit(this.hint('[click] tier: waypoint'), textWidth(r)));
    p.put(textX(r) + 5, hintRow(r), fit(this.hint('[right] more'), textWidth(r) - 5));
    this.skillPageButtons(p, r, false);
  }

  /** At Lv. 100 a crafting skill can start over, its items worth more (GameCharacter.prestige). */
  canPrestige(skill) {
    return skill.type !== 'COMBAT' && this.game.level(skill) >= this.game.content.maxLevel;
  }

  /** "Prestige skill3? Back to Lv. 1, its items get more valuable" -- Yes / Cancel. */
  askPrestige(skill, x, y) {
    this.openPopup('Prestige ' + skill.name + '? Back to Lv. 1, its items get more valuable.', 'YELLOW_BRIGHT', [
      { label: 'Yes, prestige ' + skill.name, color: 'YELLOW_BRIGHT', action: () => this.game.prestigeSkill(skill) }], x, y);
  }

  /** Back a step: Details to the page, a page to the one you came from (or the list). */
  skillBack() {
    const s = this.skills;
    if (s.details) { s.details = false; return; }
    s.page = s.trail.length ? s.trail.pop() : null;
  }

  /** The skills list, nothing to go back to (clicking Skills again). */
  skillsHome() {
    const s = this.skills;
    s.page = null; s.trail = []; s.details = false;
  }

  /** From {@code from}'s page to {@code to}'s (its overview), {@code from} kept to go back to. */
  skillLink(from, to, tier) {
    const s = this.skills;
    s.trail.push(from); s.page = to; s.details = false;
    if (tier != null) s.tier = tier;
  }

  /**
   * A skill's page: its picture, name and total xp; its type and level; then one graph -- what it's made from (a
   * picture a unit, one with "xN" past 3) joining into it, the skills it's used for, and in a column past those the
   * skills that use them; each arrow with its count, a dotted stub where the skill needs something else too, "x"
   * where nothing uses it. Every picture opens that skill. [←] back, [details] the full page (skillPage).
   */
  skillOverview(p, r, skill) {
    const game = this.game, c = game.content, s = this.skills;
    const x0 = textX(r), right = x0 + textWidth(r);
    let y = firstRow(r);
    const color = game.skillColor(skill);
    this.skillIcon(x0, y, skill);
    const xp = comma(game.lifetimeExperience(skill)) + ' xp';
    p.fg = color; p.bold = true; p.put(x0 + 3, y, fit(skill.name, Math.max(1, textWidth(r) - 4 - xp.length))); p.bold = false;
    p.fg = game.getPrestige(skill) > 0 ? 'YELLOW_BRIGHT' : 'BLACK_BRIGHT'; p.put(right - xp.length, y++, xp);
    const type = skill.type[0] + skill.type.slice(1).toLowerCase();
    p.fg = TYPE_COLOR[skill.type]; p.put(x0, y, type);
    const lv = 'Lv. ' + game.level(skill) + ' / ' + c.maxLevel;
    p.fg = 'WHITE'; p.put(right - lv.length, y++, lv);
    if (this.canPrestige(skill)) {
      p.fg = 'YELLOW_BRIGHT'; p.put(x0, y, fit(this.phone ? '[tap] prestige: revert to Lv. 1?' : '[p] prestige: revert to Lv. 1?', textWidth(r)));
      const promptY = y;
      this.clicks.option(BASE, x0, y, 32, 1, () => this.askPrestige(skill, x0, promptY + 1));
      y++;
    }
    y++;
    const bottom = hintRow(r) - 1;
    const icon = (x, iy, target) => {
      this.skillIcon(x, iy, target);
      if (target !== skill) this.clicks.option(BASE, x, iy, 2, 1, () => this.skillLink(skill, target));
    };
    if (!skill.line) {
      p.fg = 'BLACK_BRIGHT'; p.put(x0, y + 1, 'Trained by fighting.');
      this.skillPageButtons(p, r, true);
      return;
    }
    // the graph: what it's made from on the left, joining into it; on the right the skills that use it, and in a
    // column past those the skills that use them, each fed by its own trunk. An arrow's count shows past 1; a dotted
    // stub dropping into it means the skill needs something else too; x: used by nothing
    const tierOf = o => o.line.recipes[Math.max(0, Math.min(o.line.recipes.length - 1, s.tier))];
    const makerOf = k => { const m = c.madeBy(k.product.base || k.product); return m ? m.skill : null; };
    const makers = o => tierOf(o).components.map(makerOf).filter(Boolean);
    const users = from => c.craftingSkills().filter(o => o !== from && o.line && makers(o).includes(from));
    const count = (from, to) => tierOf(to).components.filter(k => makerOf(k) === from).reduce((n, k) => n + k.count, 0);
    const label = n => n > 1 ? 'x' + n : '';
    const sources = [];
    for (const k of tierOf(skill).components) {
      const from = makerOf(k);
      if (k.count <= 3) for (let i = 0; i < k.count; i++) sources.push({ skill: from, label: '' });
      else sources.push({ skill: from, label: 'x' + k.count });
    }
    const first = users(skill).map(u => ({ skill: u, label: label(count(skill, u)), other: makers(u).some(m => m !== skill), users: users(u) }));
    const feeders = first.filter(u => u.users.length);
    const second = [];
    for (const u of feeders) {
      for (const w of u.users) {
        let entry = second.find(e => e.skill === w);
        if (!entry) second.push(entry = { skill: w, feeders: [] });
        entry.feeders.push(u);
      }
    }
    second.forEach(w => { w.label = w.feeders.length === 1 ? label(count(w.feeders[0].skill, w.skill)) : ''; });
    // columns: sources, the skill and its name, the trunk to its users, them, a trunk each for theirs, those
    const srcLabel = Math.max(0, ...sources.map(src => src.label.length));
    const startX = x0 + 3 + (srcLabel ? srcLabel + 1 : 0), centerX = sources.length ? startX + 4 : x0;
    const width1 = Math.max(0, ...first.map(u => u.label.length)), width2 = Math.max(0, ...second.map(w => w.label.length));
    const layout = (nameW, deep) => {
      const outX = centerX + 3 + (nameW ? nameW + 1 : 0), bx1 = outX + 1, u1X = bx1 + width1 + 4;
      const trunk2 = u1X + 3, u2X = trunk2 + feeders.length + width2 + 3;
      return { outX, bx1, u1X, trunk2, u2X, end: !first.length ? outX + 2 : deep && second.length ? u2X + 4 : u1X + 4 };
    };
    let deep = true, nameW = Math.min(skill.name.length, Math.max(0, right - layout(0, true).end - 1));
    if (second.length && nameW < Math.min(skill.name.length, 4)) {
      deep = false;
      nameW = Math.min(skill.name.length, Math.max(0, right - layout(0, false).end - 1));
    }
    const L = layout(nameW, deep);
    // used directly and through one of the others too: once, in the far column, a straight line the other joins
    if (deep) {
      for (const u of first) {
        u.through = u.users.length ? null : second.find(w => w.skill === u.skill);
        if (u.through) { u.through.direct = u; u.other = false; }
      }
    }
    second.forEach(w => {
      w.other = makers(w.skill).some(m => !(w.direct && m === skill) && !w.feeders.some(f => f.skill === m));
      if (w.direct) w.label = '';
    });
    // rows (from 0, moved down to fit after): an arrow every other row, a stub on the row above it
    first.forEach((u, i) => { u.row = (first[0].other ? 1 : 0) + 2 * i; });
    const ty = first.length ? Math.round((first[0].row + first[first.length - 1].row) / 2) : 0;
    if (deep) {
      const want = w => w.feeders.reduce((n, f) => n + f.row, 0) / w.feeders.length;
      second.sort((a, b) => want(a) - want(b));
      const taken = second.filter(w => w.direct).map(w => w.row = w.direct.row);
      for (const w of second.filter(w => !w.direct)) {
        w.row = Math.round(want(w));
        while (taken.some(row => Math.abs(row - w.row) < 2)) w.row++;
        taken.push(w.row);
      }
    }
    const srcRows = sources.map((_, i) => ty - (sources.length - 1) + 2 * i);
    const tops = [ty, ...srcRows, ...first.map(u => u.row - (u.other ? 1 : 0)), ...(deep ? second.map(w => w.row - (w.other ? 1 : 0)) : [])];
    const shift = y + 1 - Math.min(...tops);
    const at = v => v + shift;
    const graphEnd = hintRow(r) - 3;   // under it, always room for Find nearest
    const clip = (gx, gy) => gy > y && gy < graphEnd && gx + 1 < right;
    const gIcon = (gx, gy, o) => { if (o && clip(gx, gy)) icon(gx, gy, o); };
    const paths = [], marks = [];
    const arrow = (toX, toY, text, other) => {
      marks.push({ x: toX - 1, y: toY, text: '►', fg: 'BLACK_BRIGHT' });
      if (text) marks.push({ x: toX - 2 - text.length, y: toY, text, fg: 'WHITE' });
      if (other) {
        paths.push({ cells: [[toX - 2, toY - 1], [toX - 2, toY]] });
        marks.push({ x: toX - 2, y: toY - 1, text: '⋮', fg: 'BLACK_BRIGHT' });
      }
    };
    // made from
    if (sources.length) {
      const trunkX = startX + 1;
      sources.forEach((src, i) => paths.push({ cells: [...lPath(startX, at(srcRows[i]), trunkX, at(srcRows[i]), true),
        ...lPath(trunkX, at(srcRows[i]), trunkX, at(ty), true).slice(1), ...lPath(trunkX, at(ty), centerX - 2, at(ty), true).slice(1)] }));
      marks.push({ x: centerX - 1, y: at(ty), text: '►', fg: 'BLACK_BRIGHT' });
    }
    // used by: out, down the trunk, along to each
    const route = (fromX, fromY, trunkX, toX, toY) => paths.push({ cells: [...lPath(fromX, fromY, trunkX, fromY, true),
      ...lPath(trunkX, fromY, trunkX, toY, true).slice(1), ...lPath(trunkX, toY, toX - 2, toY, true).slice(1)] });
    for (const u of first) {
      if (u.through) {
        route(L.outX, at(ty), L.bx1, L.u2X, at(u.row));
        if (u.label) marks.push({ x: L.u1X - 2 - u.label.length, y: at(u.row), text: u.label, fg: 'WHITE' });
        continue;
      }
      route(L.outX, at(ty), L.bx1, L.u1X, at(u.row));
      arrow(L.u1X, at(u.row), u.label, u.other);
      gIcon(L.u1X, at(u.row), u.skill);
      if (!u.users.length) marks.push({ x: L.u1X + 2, y: at(u.row), text: '─x', fg: 'BLACK_BRIGHT' });
      else if (!deep) marks.push({ x: L.u1X + 2, y: at(u.row), text: '─…', fg: 'BLACK_BRIGHT' });
    }
    if (!first.length) marks.push({ x: L.outX, y: at(ty), text: '─x', fg: 'BLACK_BRIGHT' });
    // and what uses those: each feeder's own trunk across to the column
    if (deep) {
      for (const w of second) {
        for (const f of w.feeders) route(L.u1X + 2, at(f.row), L.trunk2 + feeders.indexOf(f), L.u2X, at(w.row));
        arrow(L.u2X, at(w.row), w.label, w.other);
        gIcon(L.u2X, at(w.row), w.skill);
        if (!users(w.skill).length) marks.push({ x: L.u2X + 2, y: at(w.row), text: '─x', fg: 'BLACK_BRIGHT' });
      }
    }
    drawTracks(p, paths, 'WHITE', clip);
    for (const m of marks) if (clip(m.x, m.y)) { p.fg = m.fg; p.put(m.x, m.y, m.text); }
    sources.forEach((src, i) => {
      gIcon(x0, at(srcRows[i]), src.skill);
      if (clip(x0, at(srcRows[i]))) { p.fg = 'WHITE'; p.put(x0 + 3, at(srcRows[i]), src.label); }
    });
    // the skill itself
    gIcon(centerX, at(ty), skill);
    if (nameW) { p.fg = color; p.bold = true; p.put(centerX + 3, at(ty), fit(skill.name, nameW)); p.bold = false; }
    const lowest = Math.max(...tops.map(at), ...first.map(u => at(u.row)), ...(deep ? second.map(w => at(w.row)) : []));
    let findY = Math.min(lowest + 2, graphEnd);
    if (!sources.length && findY + 2 <= graphEnd) { p.fg = 'BLACK_BRIGHT'; p.put(x0, findY, 'raw: costs nothing'); findY += 2; }
    // a waypoint to the nearest node of each tier, without going into Details
    p.fg = 'DEFAULT'; p.put(x0, findY, 'Find nearest:');
    skill.line.recipes.forEach((recipe, tier) => {
      const fx = x0 + tier * 5, waypoint = game.hasWaypoint(recipe), marks = waypoint ? '[]' : tierMarks(tier);
      p.fg = waypoint ? color : dim(color);
      p.set(fx, findY + 1, marks[0]); p.set(fx + 3, findY + 1, marks[1]);
      this.skillIcon(fx + 1, findY + 1, skill);
      this.clicks.option(BASE, fx, findY + 1, 4, 1, () => game.toggleWaypoint(recipe));
    });
    this.skillPageButtons(p, r, true);
  }

  /** The bottom row: [←] back on the left, and on a page (not Details) [details] on the right. */
  skillPageButtons(p, r, details) {
    const back = '[←]';
    p.fg = 'WHITE'; p.put(textX(r), hintRow(r), back);
    this.clicks.option(BASE, textX(r), hintRow(r), back.length, 1, () => this.skillBack());
    if (details) {
      const label = '[details]', x = textX(r) + textWidth(r) - label.length;
      p.fg = 'WHITE'; p.put(x, hintRow(r), label);
      this.clicks.option(BASE, x, hintRow(r), label.length, 1, () => { this.skills.details = true; });
    }
  }

  goalRow(p, r, skill, y) {
    const game = this.game, c = game.content;
    const level = game.level(skill);
    const goal = game.goals.get(skill.id);
    const next = c.milestones(skill.type).find(l => l > level);
    const current = goal && goal.level > level;
    let text;
    if (current) {
      p.fg = 'YELLOW_BRIGHT';
      text = 'Goal: < Lv. ' + goal.level + ' > ' + this.goalWhat(skill, goal.level).text + '   [x] clear';
    } else {
      p.fg = 'BLACK_BRIGHT';
      text = (goal ? 'Goal: Lv. ' + goal.level + ' reached' : 'Goal: none') + (next ? '  [g] Lv. ' + next + ': ' + this.goalWhat(skill, next).text : '');
    }
    p.put(textX(r), y, fit(text, textWidth(r)));
    this.clicks.option(BASE, textX(r), y, Math.min(textWidth(r), text.length), 1, (button, cx) => {
      if (button !== 0) {
        this.openPopup('Goal', 'YELLOW_BRIGHT', [
          { label: 'Next milestone', action: () => next && game.setGoal(skill, next) },
          { label: 'Clear goal', action: () => goal && game.setGoal(skill, 0) }], cx, y + 1);
        return;
      }
      if (!current) { if (next) game.setGoal(skill, next); return; }
      const at = cx - textX(r);
      const milestones = c.milestones(skill.type).filter(l => l > level);
      const i = milestones.indexOf(goal.level);
      if (at >= text.indexOf('[x]')) game.setGoal(skill, 0);
      else if (at === 6 && i > 0) game.setGoal(skill, milestones[i - 1]);
      else if (at === text.indexOf('>') && i >= 0 && i < milestones.length - 1) game.setGoal(skill, milestones[i + 1]);
    });
  }

  // ---- monsters (MonstersMenu) ---------------------------------------------------------------------------------------
  monsterPanel(p, r) {
    const game = this.game, c = game.content, m = this.monsters;
    const monsters = c.monsters;
    let y = firstRow(r);
    if (!monsters.length) { p.fg = 'BLACK_BRIGHT'; p.put(textX(r), y, 'No monsters found yet.'); return; }
    m.index = Math.max(0, Math.min(m.index, monsters.length - 1));
    const nameWidth = Math.max(...monsters.map(t => t.name.length));
    const detailRows = 6;
    const lastListRow = hintRow(r) - detailRows - 2;
    const start = Math.max(0, Math.min(m.index - 3, monsters.length - (lastListRow - y + 1)));
    for (let i = start; i < monsters.length && y <= lastListRow; i++, y++) {
      const type = monsters[i], selected = i === m.index;
      if (selected) { p.fg = 'WHITE_BRIGHT'; p.set(textX(r) - 1, y, '>'); p.bold = true; }
      this.monsterIcon(textX(r), y, type);
      p.fg = selected ? 'WHITE_BRIGHT' : 'DEFAULT';
      const stats = type.name.padEnd(nameWidth) + '  Lv. ' + String(type.level).padEnd(3) + ' hp ' + String(type.maxHp).padEnd(4)
        + ' hit ' + String(type.maxHit).padEnd(3) + ' kills ' + short(game.kills.get(type.id) || 0).padEnd(5);
      p.put(textX(r) + 2, y, fit(stats, textWidth(r) - 2));
      p.bold = false;
      const mine = type.drops.filter(d => game.name && d.foundBy === game.name).length;
      if (type.drops.length && textX(r) + 2 + stats.length + 6 <= textX(r) + textWidth(r)) {
        p.fg = mine > 0 ? 'YELLOW_BRIGHT' : 'BLACK_BRIGHT';
        p.put(textX(r) + 2 + stats.length, y, 'Ψ' + mine + '/' + type.drops.length);
      }
      this.clicks.option(BASE, textX(r), y, textWidth(r), 1, () => { m.index = i; });
    }
    const type = monsters[m.index];
    y = hintRow(r) - detailRows;
    p.fg = game.monsterColor(type); p.bold = true; p.put(textX(r), y++, fit(type.name, textWidth(r))); p.bold = false;
    p.fg = 'DEFAULT';
    p.put(textX(r), y++, fit('Lv. ' + type.level + ', hp ' + type.maxHp + ', hits up to ' + type.maxHit + ', roams ' + type.roamRadius + ' tiles', textWidth(r)));
    p.fg = 'BLACK_BRIGHT';
    for (const d of type.drops.slice(0, 3)) {
      p.put(textX(r), y++, fit('  drop ' + d.product.name + ' -- ' + (d.foundBy ? 'found by ' + d.foundBy : 'not found yet'), textWidth(r)));
    }
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h, step => { m.index += step; });
  }

  statsPanel(p, r) {
    const game = this.game;
    let y = firstRow(r);
    const row = (label, value) => {
      p.fg = 'DEFAULT'; p.put(textX(r), y, label);
      p.fg = 'WHITE_BRIGHT'; p.put(textX(r) + 24, y++, value);
    };
    row('Total level', String(game.totalLevel()));
    row('Total xp', comma(game.totalExperience()));
    row('Trophies found', String(game.trophies()));
    row('On display at the Gym', String(game.displayedTrophies.length));
    row('Gym attacks left', comma(game.gymAttacks));
    y++;
    for (const stat of game.meta.stats) {
      const value = stat.name === 'TIME_PLAYED' ? game.timePlayedTicks() : game.stats.get(stat.name) || 0;
      row(stat.label, stat.ticks ? duration(value * game.tick) : comma(value));
    }
  }

  settingsPanel(p, r) {
    const game = this.game;
    let y = firstRow(r);
    const names = game.meta.playerColorNames;
    const option = (label, value, onClick, valueColor = 'WHITE_BRIGHT') => {
      p.fg = 'DEFAULT'; p.put(textX(r), y, label);
      p.fg = valueColor; p.put(textX(r) + 22, y, value);
      this.clicks.option(BASE, textX(r), y++, textWidth(r), 1, onClick);
    };
    const on = b => b ? 'on' : 'off';
    option('Running', on(game.running), () => game.toggleRunning());
    option('Auto-investigate', on(game.autoInvestigate), () => game.toggleAutoInvestigate());
    option('Colour', names[game.colorIndex] || String(game.colorIndex), () => game.setColor(game.colorIndex + 1), game.playerColor(game.colorIndex));
    option('Contrast tiers', on(game.showProductTiers), () => { game.showProductTiers = !game.showProductTiers; this.saveSettings(); });
    option('Sort items', SORT_NAMES[game.itemSort], () => { game.itemSort = SORTS[(SORTS.indexOf(game.itemSort) + 1) % SORTS.length]; this.saveSettings(); });
    option('Compact chat', on(game.compactChat), () => { game.compactChat = !game.compactChat; this.saveSettings(); });
    option('Light mode', on(game.lightMode), () => { game.lightMode = !game.lightMode; this.saveSettings(); });
    option('Skills as', this.skillsAsGrid() ? 'grid' : 'list', () => { game.skillGrid = !this.skillsAsGrid(); this.saveSettings(); });
    if (this.phone && document.fullscreenEnabled) {
      option('Full screen', on(!!document.fullscreenElement), () => {
        if (document.fullscreenElement) document.exitFullscreen();
        else document.documentElement.requestFullscreen().then(() => screen.orientation && screen.orientation.lock
          && screen.orientation.lock('landscape').catch(() => {})).catch(() => {});
      });
    } else if (this.phone && !navigator.standalone) {
      // an iPhone's Safari has no full screen for pages: a web app on the home screen is the way
      option('Full screen', 'how?', () => game.say('iPhone: Share > Add to Home Screen', 'NOTICE'));
    }
    p.fg = 'BLACK_BRIGHT';
  }

  saveSettings() {
    const game = this.game;
    try {
      localStorage.setItem('capebabe.settings', JSON.stringify({
        showProductTiers: game.showProductTiers, itemSort: game.itemSort, compactChat: game.compactChat, lightMode: game.lightMode, skillGrid: game.skillGrid, skillSort: game.skillSort }));
    } catch (e) { /* private window: just for this visit */ }
  }

  loadSettings() {
    try {
      const saved = JSON.parse(localStorage.getItem('capebabe.settings') || '{}');
      Object.assign(this.game, saved);
    } catch (e) { /* nothing saved */ }
  }

  // ---- the message log and chat (MessageLogView) ----------------------------------------------------------------
  log(p, r) {
    const game = this.game;
    p.fg = 'DEFAULT'; p.rect(r);
    const width = r.w - 2;
    const lines = [];
    for (const entry of game.log) {
      if (game.compactChat && entry.kind === 'ROUTINE') continue;
      const prefix = entry.kind === 'CHAT' ? entry.from + ': ' : '';
      const full = prefix + entry.text;
      for (let i = 0; i < full.length || i === 0; i += width) lines.push({ entry, text: full.slice(i, i + width), first: i === 0, prefixLen: prefix.length });
    }
    // the wheel scrolls back through it; a new message doesn't move what you're reading
    const rows = r.h - 3;
    const back = Math.max(0, Math.min(this.logBack || 0, lines.length - rows));
    if (back > 0 && this.logLines != null) this.logBack = back + Math.max(0, lines.length - this.logLines);
    this.logBack = Math.max(0, Math.min(this.logBack || 0, lines.length - rows));
    this.logLines = lines.length;
    const shown = lines.slice(Math.max(0, lines.length - rows - this.logBack), lines.length - this.logBack);
    this.clicks.addScroll(BASE, r.x, r.y, r.w, r.h - 1, step => { this.logBack = (this.logBack || 0) - step * 3; });
    scrollbar(p, r.right, r.y, r.bottom, Math.max(0, lines.length - rows - this.logBack), rows, lines.length);
    if (this.logBack > 0) {
      const more = ' ' + this.logBack + ' more below -- scroll down ';
      p.fg = 'YELLOW'; p.put(r.right - 1 - more.length, r.bottom, more);
      this.clicks.option(BASE, r.right - 1 - more.length, r.bottom, more.length, 1, () => { this.logBack = 0; });
    }
    shown.forEach((line, i) => {
      const y = r.y + 1 + i, x = r.x + 1, e = line.entry;
      if (e.kind === 'CHAT' && line.first) {
        p.fg = game.playerColor(e.colorIndex); p.put(x, y, e.from);
        p.fg = 'DEFAULT'; p.put(x + e.from.length, y, line.text.slice(e.from.length));
      } else {
        p.fg = e.kind === 'ROUTINE' ? 'BLACK_BRIGHT' : e.kind === 'NOTICE' ? 'WHITE_BRIGHT' : 'DEFAULT';
        p.put(x, y, line.text);
      }
    });
    const y = r.bottom - 1;
    if (this.chat != null) {
      p.fg = 'WHITE_BRIGHT';
      const shownText = fit(this.chat, width - 4);
      p.put(r.x + 1, y, (this.chatTo ? 'to ' + this.chatTo + '> ' : '> ') + shownText + (Math.floor(performance.now() / 500) % 2 ? '_' : ' '));
    } else {
      p.fg = 'BLACK_BRIGHT'; p.put(r.x + 1, y, '[/] chat  (/ again for a command, e.g. //1)');
      this.clicks.option(BASE, r.x + 1, y, 8, 1, () => { this.chat = ''; });
    }
  }

  // ---- the tile you're pointing at, or the one ahead (MapDetailView) ---------------------------------------------------
  detail(p) {
    const game = this.game, c = game.content, r = DETAIL;
    p.fg = 'DEFAULT'; p.rect(r);
    const ahead = !this.hoverTile;
    const target = this.hoverTile || game.ahead;
    p.fg = 'BLACK_BRIGHT'; p.put(textX(r), r.y, (ahead ? 'Ahead' : 'Tile') + ' (' + target.x + ',' + target.y + ')');
    let row = firstRow(r);
    const last = hintRow(r);
    for (const other of game.othersAt(target.x, target.y)) {
      const type = other.kind === 'MONSTER' ? c.monstersById.get(other.monsterTypeId) : null;
      p.fg = type ? game.monsterColor(type) : game.playerColor(other.colorIndex);
      if (type) this.monsterIcon(textX(r), row, type); else p.set(textX(r), row, ARROWS[other.facing] || '@');
      p.put(textX(r) + 2, row, fit(other.name, textWidth(r) - 2));
      p.fg = 'BLACK_BRIGHT';
      p.put(textX(r) + 3 + other.name.length, row++, fit((type ? 'Lv. ' + type.level : 'Lv. ' + Math.max(1, other.combatLevel)) + '  hp ' + other.hp + '/' + other.maxHp, textWidth(r) - 3 - other.name.length));
      if (type && row <= last) {
        p.put(textX(r) + 2, row++, fit('  hits up to ' + type.maxHit + (type.drops.length ? '  drops found: ' + type.drops.length : ''), textWidth(r) - 2));
      }
    }
    const node = c.nodesAt.get(target.x + ',' + target.y);
    const building = c.buildingsAt.get(target.x + ',' + target.y);
    if (building && row <= last) {
      if (c.walkableAt(building, target.x, target.y)) { p.fg = 'DEFAULT'; p.put(textX(r), row, 'Train tracks'); return; }
      p.fg = 'YELLOW_BRIGHT'; p.put(textX(r), row++, fit(building.info.label, textWidth(r)));
      p.fg = 'BLACK_BRIGHT'; p.put(textX(r) + 2, row++, fit(building.info.action, textWidth(r) - 2));
      return;
    }
    if (!node) {
      if (row === firstRow(r)) { p.fg = 'BLACK_BRIGHT'; p.put(textX(r), row, 'Nothing here'); }
      return;
    }
    const recipe = node.recipe;
    this.skillIcon(textX(r), row, recipe.skill);
    const lines = [
      recipe.skill.name + ' [Lv. ' + recipe.levelNeeded + ']',
      '  makes ' + recipe.product.name + ' (+' + recipe.expGained + 'xp)',
      '  needs ' + (recipe.components.length ? recipe.components.map(k => (k.count > 1 ? k.count + 'x ' : '') + k.product.name).join(', ') : 'nothing'),
    ];
    const status = game.status(node);
    const firstStatus = lines.length;
    if (node.maxCharges !== 0) lines.push(status.depleted ? '  empty -- respawning' : '  ' + status.charges + '/' + node.maxCharges + ' left');
    const capacity = node.capacity === 0 ? 0 : node.capacity + game.capacityBonus;
    if (capacity !== 0 || status.users > 0) lines.push('  in use: ' + status.users + (capacity === 0 ? '' : '/' + capacity));
    const locked = game.lockedFor(node);
    if (locked) lines.push(game.tried.has(node.id) ? '  locked -- g to add goal' : '  locked');
    p.fg = 'DEFAULT';
    for (let i = 0; i < lines.length && row <= last; i++) {
      if (i === firstStatus) p.fg = 'BLACK_BRIGHT';
      if (locked && i === lines.length - 1) p.fg = 'RED';
      p.put(textX(r) + 2, row++, fit(lines[i], textWidth(r) - 2));
    }
  }

  // ---- the progress bar (ProgressBarView): what you're doing, or the monster you're fighting ----------------------
  progress(p) {
    const game = this.game;
    const monster = game.fightingMonster();
    let fraction, label, fill;
    if (monster) {
      fraction = monster.maxHp === 0 ? 0 : monster.hp / monster.maxHp;
      label = monster.name + ' ' + monster.hp + '/' + monster.maxHp;
      fill = 'RED';
    } else if (game.currentAction) {
      fraction = game.actionFraction();
      label = game.actionLabel();
      fill = 'GREEN';
    } else return;
    const M = this.map.rect, width = Math.min(31, M.w - 4);
    const r = R(M.x + ((M.w - width) >> 1), M.bottom - 1, width, 1), filled = Math.round(fraction * r.w);
    label = label.slice(0, r.w);
    const start = (r.w - label.length) >> 1;
    for (let i = 0; i < r.w; i++) base(p, r.x + i, r.y, label[i - start] || ' ', 'WHITE_BRIGHT', i < filled ? fill : 'BLACK_BRIGHT');
  }

  // ---- popups (PopupMenu) ---------------------------------------------------------------------------------------
  openPopup(title, color, options, x, y, index = 0) {
    this.popup = { title, color, options: [...options, { label: 'Cancel', color: 'BLACK_BRIGHT' }], x, y, index };
  }

  choose(i) {
    const popup = this.popup;
    const option = popup.options[i];
    this.popup = null;
    if (option && option.action) option.action();
  }

  drawPopup(p) {
    const pop = this.popup;
    const rows = 1 + pop.options.length + pop.options.filter(o => o.note).length;
    const width = Math.max(pop.title.length, ...pop.options.map(o => Math.max(o.label.length + 2, o.note ? o.note.length + 4 : 0)));
    const x = Math.max(0, Math.min(pop.x, this.cols - width - 3)), y = Math.max(0, Math.min(pop.y, this.rows - rows - 2));
    p.fg = 'DEFAULT'; p.bg = 'BLACK';
    p.fill(x, y, width + 2, rows + 2);
    p.rect(R(x, y, width + 2, rows + 2));
    // pictures in a window under this one stay under it
    this.topIcons = this.topIcons.filter(i => i.x + 1 < x || i.x > x + width + 1 || i.y < y || i.y > y + rows + 1);
    this.clicks.addClickOff(MENU, () => { this.popup = null; });
    this.clicks.add(MENU, x, y, width + 2, rows + 2, () => {});
    let row = y + 1;
    p.fg = pop.color; p.bold = true; p.put(x + 1, row++, pop.title); p.bold = false;
    pop.options.forEach((option, i) => {
      const selected = i === pop.index;
      p.fg = option.color || (selected ? 'WHITE_BRIGHT' : option.action ? 'DEFAULT' : 'BLACK_BRIGHT');
      p.bold = selected;
      p.put(x + 1, row, (selected ? '> ' : '  ') + option.label);
      p.bold = false;
      const height = option.note ? 2 : 1;
      this.clicks.option(MENU, x + 1, row, width, height, () => this.choose(i)).popupIndex = i;
      row++;
      if (option.note) { p.fg = 'BLACK_BRIGHT'; p.put(x + 5, row++, fit(option.note, width - 4)); }
    });
    p.bg = null;
  }

  // ---- the bank (BankView): your inventory and the bank side by side; click to move a stack ----------------------
  bankWindow(p) {
    const game = this.game, r = this.phone ? this.map.rect : BANK;
    const BANK_COLUMNS = Math.max(1, Math.floor((r.w - 4) / BANK_STEP_X));
    const INVENTORY_ROWS = Math.max(1, Math.min(5, Math.floor((r.h - 5) / CELL_STEP_Y)));
    this.clicks.addClickOff(MODAL, () => { game.bankOpen = false; });
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(r.x, r.y, r.w, r.h);
    p.rect(r);
    p.fg = 'YELLOW_BRIGHT'; p.bold = true; p.put(r.x + 2, r.y, 'Bank'); p.bold = false;
    if (game.bankBusy()) { p.fg = 'YELLOW'; p.put(r.x + 7, r.y, ' moving... '); }
    this.clicks.add(MODAL, r.x, r.y, r.w, r.h, () => {});
    // a stack (as many as one of yours holds), 10 stacks, or all of it -- each no more than there is
    const moving = (verb, product, have) => {
      const stack = game.stackSize(product), move = verb === 'Deposit' ? game.deposit : game.withdraw;
      return [['Stack', stack], ['10 Stacks', 10 * stack], ['All', have]].map(([label, n]) =>
        ({ label: verb + ' ' + label, action: () => move.call(game, product, Math.min(n, have)) }));
    };
    this.iconsOnTop = true;   // over the window, not under it
    const slots = game.slots(game.bank).map(s => ({ product: s.product, count: game.bank.get(s.product) }))
      .filter((s, i, all) => all.findIndex(o => o.product === s.product) === i);
    const xs = [], ys = [];
    for (let c = 0; c < BANK_COLUMNS; c++) xs.push(r.x + 3 + c * BANK_STEP_X);
    for (let rr = 0; rr < INVENTORY_ROWS; rr++) ys.push(r.y + 2 + rr * CELL_STEP_Y);
    if (!slots.length) { p.fg = 'BLACK_BRIGHT'; p.put(xs[0], ys[0], 'empty'); }
    else cellLines(p, r, xs, ys);
    const top = this.bank.top[1];
    for (let rr = 0; rr < INVENTORY_ROWS; rr++) {
      for (let c = 0; c < BANK_COLUMNS; c++) {
        const index = (top + rr) * BANK_COLUMNS + c;
        if (index >= slots.length) continue;
        const slot = slots[index];
        p.bg = 'BLACK';
        this.cell(p, xs[c], ys[rr], slot.product, short(slot.count), BANK_CELL_WIDTH, false);
        this.clicks.option(MODAL, xs[c], ys[rr], BANK_CELL_WIDTH, 2, (button) => {
          if (button === 0) moving('Withdraw', slot.product, slot.count)[0].action();
          else this.openPopup(slot.product.name, game.itemColor(slot.product), moving('Withdraw', slot.product, slot.count), xs[c] + BANK_CELL_WIDTH, ys[rr]);
        });
      }
    }
    this.iconsOnTop = false;
    p.bg = 'BLACK';
    p.fg = 'BLACK_BRIGHT';
    p.put(r.x + 2, r.bottom, slots.length + ' items');
    const hints = ['[click] take a stack / store all', '[right] how many' + (this.phone ? '' : '  [Esc] close')];
    hints.forEach((h, k) => p.put(r.x + 2, r.bottom - hints.length + k, fit(this.hint(h), r.w - 4)));
    this.clicks.addScroll(MODAL, r.x, r.y, r.w, r.h, step => { this.bank.top[1] = Math.max(0, top + step); });
    // inventory cells deposit while the bank's open
    const inv = game.slots(game.inventory);
    if (!this.invGrid) { p.bg = null; return; }
    const { r: ir, xs: ixs, ys: iys, width: iw } = this.invGrid;
    this.clicks.add(MODAL, ir.x, ir.y, ir.w, ir.h, () => {});
    for (let rr = 0; rr < iys.length; rr++) {
      for (let c = 0; c < ixs.length; c++) {
        const index = (this.inv.top + rr) * ixs.length + c;
        if (index >= inv.length) continue;
        const slot = inv[index];
        this.clicks.option(MODAL, ixs[c], iys[rr], iw, 2, (button) => {
          const have = game.inventory.get(slot.product) || 0;
          if (button === 0) game.deposit(slot.product, have);
          else this.openPopup(slot.product.name, game.itemColor(slot.product), moving('Deposit', slot.product, have), ixs[c] + iw, iys[rr]);
        });
      }
    }
    p.bg = null;
  }

  simpleWindow(p, title, lines, close) {
    const M = this.map.rect, inset = Math.min(6, Math.max(1, (M.w - 40) >> 1));
    const r = R(M.x + inset, M.y + Math.min(4, 1), M.w - 2 * inset, Math.min(M.h - 2, lines.length + 4));
    this.clicks.addClickOff(MODAL, close);
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(r.x, r.y, r.w, r.h);
    p.rect(r);
    p.fg = 'YELLOW_BRIGHT'; p.bold = true; p.put(r.x + 2, r.y, title); p.bold = false;
    this.clicks.add(MODAL, r.x, r.y, r.w, r.h, () => {});
    lines.forEach((line, i) => {
      const y = r.y + 2 + i;
      p.fg = line.color || (line.action ? 'DEFAULT' : 'BLACK_BRIGHT');
      p.put(r.x + 2, y, fit(line.text, r.w - 4));
      if (line.action) this.clicks.option(MODAL, r.x + 2, y, r.w - 4, 1, line.action);
    });
    p.bg = null;
  }

  /** The stations, and where you can ride from the one you're at (Stations.java: each has lines to its 2 nearest). */
  stationWindow(p) {
    const game = this.game, c = game.content;
    const close = () => { game.stationOpen = false; };
    const line = c.buildings.filter(b => b.kind === 'STATION');
    const platform = b => ({ x: b.x + 2, y: b.y + b.height });
    const d2 = (a, b) => { const pa = platform(a), pb = platform(b); return (pa.x - pb.x) ** 2 + (pa.y - pb.y) ** 2; };
    const neighbours = from => line.filter(b => b !== from).sort((a, b) => d2(a, from) - d2(b, from) || line.indexOf(a) - line.indexOf(b)).slice(0, 2);
    // lines to the 2 nearest, plus the shortest between any pieces that leaves apart (Stations.bridges)
    const piece = line.map((_, i) => i);
    const join = (a, b) => piece.forEach((p, i) => { if (p === b) piece[i] = a; });
    line.forEach((b, i) => neighbours(b).forEach(near => join(piece[i], piece[line.indexOf(near)])));
    const bridges = [];
    while (new Set(piece).size > 1) {
      let best = null;
      for (let i = 0; i < line.length; i++) {
        for (let j = i + 1; j < line.length; j++) {
          if (piece[i] !== piece[j] && (!best || d2(line[i], line[j]) < best.d)) best = { i, j, d: d2(line[i], line[j]) };
        }
      }
      bridges.push([line[best.i], line[best.j]]);
      join(piece[best.i], piece[best.j]);
    }
    const connected = (a, b) => a !== b && (neighbours(a).includes(b) || neighbours(b).includes(a)
      || bridges.some(pair => pair.includes(a) && pair.includes(b)));
    const from = line.find(b => b.tiles.some(t => !t.walkable && Math.max(Math.abs(t.x - game.x), Math.abs(t.y - game.y)) <= 1));
    // the fare to station N: one item made by skill N, any tier or prestige (Stations.fareSkill)
    const fareSkill = index => {
      const skills = c.craftingSkills();
      for (let i = 0; i < skills.length; i++) {
        const skill = skills[(index + i) % skills.length];
        if (skill.line && skill.line.recipes.length) return skill;
      }
      return null;
    };
    const holds = skill => [...game.inventory.entries()].some(([item, n]) => {
      const recipe = n > 0 && c.madeBy(item);
      return recipe && recipe.skill === skill;
    });
    const rows = line.map((b, index) => {
      const name = 'tation' + index;
      if (b === from) return { text: '  ' + name.padEnd(14) + "you're here" };
      if (!from || !connected(from, b)) return { text: '  ' + name.padEnd(14) + 'no direct line' };
      const skill = fareSkill(index);
      return { text: '  ' + name.padEnd(14) + (skill ? '1 x any ' + skill.name : 'free'),
        color: skill ? game.skillColor(skill, !holds(skill)) : null, action: () => { game.ride(b.id); close(); } };
    });
    this.simpleWindow(p, 'Train station', [
      { text: 'Choose destination' },
      ...(line.length > 1 ? rows : [{ text: 'No other stations yet: wander far from the line and you may come across one.' }]),
      { text: '  Close', action: close, color: 'BLACK_BRIGHT' }], close);
    this.lineMap(p, this.phone ? this.menuRect : TABS, line, connected, from);
  }

  /**
   * The line map over the tabs (a phone: over the menu) while the station's open (StationView.drawLineMap): every
   * station as its number where it is, scaled to fit, lines between connected ones metro-map style -- each an L, the
   * way round that runs through fewer stations, joined where they meet. Here bright yellow, where you can go white.
   */
  lineMap(p, box, line, connected, here) {
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(box.x, box.y, box.w, box.h);
    p.rect(box);
    p.fg = 'BLACK_BRIGHT'; p.put(box.x + 2, box.y, 'Line map');
    this.clicks.add(MODAL, box.x, box.y, box.w, box.h, () => {});
    if (!line.length) { p.bg = null; return; }
    const platform = b => ({ x: b.x + 2, y: b.y + b.height });
    const left = box.x + 3, top = box.y + 2, width = box.w - 6, height = box.h - 5;
    const at = line.map(platform);
    const minX = Math.min(...at.map(a => a.x)), maxX = Math.max(...at.map(a => a.x));
    const minY = Math.min(...at.map(a => a.y)), maxY = Math.max(...at.map(a => a.y));
    const spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
    const scale = Math.min((width - 1) / (2 * spanX), (height - 1) / spanY);
    const padX = ((width - 1) - 2 * spanX * scale) / 2, padY = ((height - 1) - spanY * scale) / 2;
    const cols = at.map(a => left + Math.round(padX + (a.x - minX) * 2 * scale));
    const rows = at.map(a => top + Math.round(padY + (a.y - minY) * scale));
    // the scale: ticks on the bottom and left edges at round numbers of tiles, far enough apart to label
    const roundUp = tiles => { const power = 10 ** Math.floor(Math.log10(tiles)); return [1, 2, 5, 10].map(n => n * power).find(n => n >= tiles); };
    const stepX = roundUp(9 / (2 * scale)), stepY = roundUp(3 / scale);
    for (let wx = Math.ceil((minX - (padX + 2) / (2 * scale)) / stepX) * stepX; ; wx += stepX) {
      const col = left + Math.round(padX + (wx - minX) * 2 * scale), text = String(wx);
      if (col + text.length >= box.right) break;
      if (col <= box.x) continue;
      p.fg = 'BLACK_BRIGHT'; p.set(col, box.bottom, '┴'); p.put(col + 1, box.bottom, text);
    }
    for (let wy = Math.ceil((minY - (padY + 1) / scale) / stepY) * stepY; ; wy += stepY) {
      const row = top + Math.round(padY + (wy - minY) * scale);
      if (row >= box.bottom - 1) break;
      if (row <= box.y) continue;
      p.fg = 'BLACK_BRIGHT'; p.set(box.x, row, '├'); p.put(box.x + 1, row, String(wy));
    }
    const path = (a, b, alongFirst) => lPath(cols[a], rows[a], cols[b], rows[b], alongFirst);
    const crossings = (a, b, alongFirst) => path(a, b, alongFirst).filter(([x, y]) =>
      cols.some((cx, i) => i !== a && i !== b && cx === x && rows[i] === y)).length;
    const paths = [];
    for (let a = 0; a < line.length; a++) {
      for (let b = a + 1; b < line.length; b++) {
        if (connected(line[a], line[b])) {
          paths.push({ cells: path(a, b, crossings(a, b, true) <= crossings(a, b, false)), lit: line[a] === here || line[b] === here });
        }
      }
    }
    drawTracks(p, paths, 'WHITE', (x, y) => x > box.x && x < box.right && y > box.y && y < box.bottom);
    const label = i => i < 10 ? String(i) : i < 36 ? String.fromCharCode(97 + i - 10) : '*';
    line.forEach((station, i) => {
      p.fg = station === here ? 'YELLOW_BRIGHT' : here && connected(here, station) ? 'WHITE_BRIGHT' : 'BLACK_BRIGHT';
      p.bold = true; p.set(cols[i], rows[i], label(i)); p.bold = false;
    });
    const you = here ? line.indexOf(here) : -1;
    p.fg = 'BLACK_BRIGHT';
    p.put(box.x + 2, box.bottom - 1, fit((you >= 0 ? "you're at " + label(you) + '  ' : '') + 'N = tationN', box.w - 4));
    p.bg = null;
  }

  /**
   * The Gym: train (a membership's attacks a swing), or put a trophy (any monster drop) on display there for good,
   * for +1 inventory slot or 5,000 attacks; and how many are on display.
   */
  gymWindow(p) {
    const game = this.game, c = game.content;
    if (!this.gym) this.gym = {};
    const g = this.gym;
    const close = () => { game.gymOpen = false; };
    const color = item => game.itemColor(item);
    const lines = [];
    if (g.trophy) {
      const item = g.trophy;
      lines.push({ text: 'Put ' + item.name + ' on display, for:' });
      lines.push({ text: '  +1 inventory slot (' + game.inventorySlots() + ' now)', action: () => { game.displayTrophy(item, true); g.trophy = null; } });
      lines.push({ text: '  5,000 Gym attacks (' + comma(game.gymAttacks) + ' now)', action: () => { game.displayTrophy(item, false); g.trophy = null; } });
      lines.push({ text: '  Back', action: () => { g.trophy = null; }, color: 'BLACK_BRIGHT' });
      this.simpleWindow(p, 'Gym', lines, close);
      return;
    }
    lines.push({ text: 'Membership: ' + comma(game.gymAttacks) + ' attacks' });
    lines.push(game.gymAttacks > 0 ? { text: '  Train (AFK)', action: () => game.train() }
      : { text: '  Train: needs a membership' });
    lines.push({ text: '' });
    const held = [...game.inventory.entries()].filter(([item, n]) => n > 0 && c.droppedBy(item));
    lines.push({ text: held.length ? 'Put a trophy on display:' : 'No trophies (monster drops) to show.' });
    for (const [item, n] of held) lines.push({ text: '  ' + item.name + ' (you have ' + short(n) + ')', color: color(item), action: () => { g.trophy = item; } });
    if (game.displayedTrophies.length) {
      lines.push({ text: '' });
      lines.push({ text: 'On display: ' + game.displayedTrophies.length });
    }
    lines.push({ text: '  Close', action: close, color: 'BLACK_BRIGHT' });
    this.simpleWindow(p, 'Gym', lines, close);
  }

  /**
   * The church (ChurchView): Heal; Pray for a node -- which skill, which of its recipes (any tier), what to pay with
   * (100 x the tier of the tier below's item, plain or a prestige version, or 1000 of any one item); Tithe -- give
   * any of an item. Under the list, your blessing and the oracle's word on the next new skill. Esc goes back a step.
   */
  churchRows() {
    const game = this.game, c = game.content, ch = this.church;
    const rows = [];
    const itemColor = item => { const r = c.madeBy(item); return r ? game.skillColor(r.skill) : 'DEFAULT'; };
    const held = [...game.inventory.entries()].filter(([, n]) => n > 0);
    const go = step => { ch.step = step; ch.selected = 0; };
    switch (ch.step) {
      case 'MAIN':
        rows.push({ label: 'Heal (' + game.hp + '/' + game.maxHp + ' hp)', action: () => { game.heal(); game.churchOpen = false; } });
        rows.push({ label: 'Pray for a node', action: () => go('SKILL') });
        rows.push({ label: 'Tithe', action: held.length ? () => go('TITHE') : null });
        break;
      case 'SKILL':
        for (const skill of c.craftingSkills()) rows.push({ label: skill.name, color: game.skillColor(skill), action: () => { ch.skill = skill; go('RECIPE'); } });
        break;
      case 'RECIPE':
        for (const recipe of ch.skill.line ? ch.skill.line.recipes : []) {
          const locked = game.level(ch.skill) < recipe.levelNeeded;
          rows.push({ label: recipe.product.name.padEnd(16) + ' Lv. ' + recipe.levelNeeded,
            color: game.skillColor(ch.skill, locked), action: () => { ch.recipe = recipe; go('PAY'); } });
        }
        break;
      case 'PAY': {
        const recipe = ch.recipe;
        const below = recipe.tier > 0 ? recipe.line.recipes[recipe.tier - 1].product : null;
        const isBelow = item => below && (item.base || item) === below;
        const cost = item => this.prayerCost(recipe, isBelow(item));
        const payment = item => {
          const have = game.inventory.get(item) || 0;
          return { label: cost(item) + ' x ' + item.name + ' (you have ' + short(have) + ')', color: itemColor(item),
            action: have < cost(item) ? null : () => { game.pray(recipe, item); game.churchOpen = false; } };
        };
        if (below) {
          rows.push(payment(below));
          for (const [item] of held) if (item.prestige > 0 && isBelow(item)) rows.push(payment(item));
        }
        for (const [item, n] of held) if (n >= cost(item) && !isBelow(item)) rows.push(payment(item));
        break;
      }
      case 'TITHE':
        for (const [item, n] of held) rows.push({ label: item.name + ' (you have ' + short(n) + ')', color: itemColor(item), action: () => { ch.tithing = item; go('TITHE_AMOUNT'); } });
        break;
      case 'TITHE_AMOUNT': {
        const have = game.inventory.get(ch.tithing) || 0;
        const tithe = n => () => { game.tithe(ch.tithing, n); go('MAIN'); };
        for (const n of [1, 10, 100, 1000]) if (n < have) rows.push({ label: String(n), action: tithe(n) });
        rows.push({ label: 'All (' + have + ')', action: have > 0 ? tithe(have) : null });
        break;
      }
    }
    return rows;
  }

  /** No node for it anywhere yet: 100 of the tier below or 500 of anything, else 100 a tier or 1000 (Prayer.cost). */
  prayerCost(recipe, tierBelow) {
    const found = [...this.game.content.nodes.values()].some(node => node.recipe === recipe);
    return tierBelow ? (found ? 100 * recipe.tier : 100) : (found ? 1000 : 500);
  }

  /** The skills as a grid of pictures and names, or a skill's tiers as a row: the church's picking steps. */
  churchPicks(p, x, y, width, top, height) {
    const game = this.game, ch = this.church, rows = this.churchRows();
    const pick = (i, cx, cy, w) => this.clicks.option(MODAL, cx, cy, w, 1, () => {
      ch.selected = i;
      if (rows[i] && rows[i].action) rows[i].action();
    });
    if (ch.step === 'SKILL') {
      const cell = 12, across = Math.max(1, Math.floor((width - 4) / cell)), skills = game.content.craftingSkills();
      const fits = Math.max(1, Math.ceil(height / 2));
      const scroll = Math.max(0, Math.floor(ch.selected / across) - fits + 1);
      skills.forEach((skill, i) => {
        const row = Math.floor(i / across) - scroll;
        if (row < 0 || row >= fits) return;
        const cx = x + 2 + (i % across) * cell, cy = top + row * 2;
        this.skillIcon(cx, cy, skill);
        p.fg = game.skillColor(skill); p.bold = i === ch.selected;
        p.put(cx + 3, cy, fit(skill.name, cell - 4)); p.bold = false;
        pick(i, cx, cy, cell - 1);
      });
      return;
    }
    // the tiers, marked like Find nearest; the one picked spelled out under them
    const skill = ch.skill, color = game.skillColor(skill), recipes = skill.line ? skill.line.recipes : [];
    recipes.forEach((recipe, tier) => {
      const fx = x + 2 + tier * 5, marks = tierMarks(tier);
      p.bg = tier === ch.selected ? 'BLACK_BRIGHT' : 'BLACK';
      p.put(fx, top, '    ');
      p.fg = dim(color); p.set(fx, top, marks[0]); p.set(fx + 3, top, marks[1]);
      p.bg = 'BLACK';
      this.skillIcon(fx + 1, top, skill);
      pick(tier, fx, top, 4);
    });
    const recipe = recipes[ch.selected];
    if (!recipe) return;
    const locked = game.level(skill) < recipe.levelNeeded;
    p.fg = game.skillColor(skill, locked);
    p.put(x + 2, top + 2, fit(recipe.product.name + '  Lv. ' + recipe.levelNeeded, width - 4));
  }

  churchBack() {
    const ch = this.church;
    const back = { MAIN: null, SKILL: 'MAIN', RECIPE: 'SKILL', PAY: 'RECIPE', TITHE: 'MAIN', TITHE_AMOUNT: 'TITHE' }[ch.step];
    if (!back) { this.game.churchOpen = false; return; }
    ch.step = back;
    ch.selected = 0;
  }

  churchWindow(p) {
    const game = this.game;
    if (!this.church) this.church = { step: 'MAIN', selected: 0 };
    const ch = this.church;
    const rows = this.churchRows();
    ch.selected = Math.max(0, Math.min(ch.selected, rows.length - 1));
    const M = this.map.rect;
    const width = Math.min(50, M.w - 2), x = M.x + ((M.w - width) >> 1), y = M.y + (M.h > 20 ? 3 : 1);
    const title = { MAIN: 'Church', SKILL: 'Pray for a node: which skill?', RECIPE: (ch.skill || {}).name + ': which node?',
      PAY: 'Pray for ' + (ch.recipe ? ch.recipe.product.name : '') + ', paying:', TITHE: 'Tithe: give what?',
      TITHE_AMOUNT: 'Tithe ' + (ch.tithing ? ch.tithing.name : '') + ': how many?' }[ch.step];
    const notes = [];
    const grid = ch.step === 'SKILL' || ch.step === 'RECIPE';
    const across = Math.max(1, Math.floor((width - 4) / 12));
    const body = ch.step === 'SKILL' ? Math.ceil(rows.length / across) * 2 - 1 : ch.step === 'RECIPE' ? 3 : rows.length;
    if (ch.step === 'MAIN') {
      notes.push(game.blessing ? 'Blessed' + (game.blessingStrength > 1 ? ' x' + game.blessingStrength : '') + ': '
        + game.blessing.product.name + ', ' + game.blessingLeft + ' steps left' : 'No blessing.');
      notes.push(...wrap('Praying gives you a blessing to use when you like: for 100 steps the next node near you is it, twice as likely.', width - 4));
      notes.push('');
      notes.push(...wrap(oracleLine(game.oracleNow()), width - 4));
    } else if (ch.step === 'PAY' && rows.every(r => !r.action)) {
      const recipe = ch.recipe, below = recipe.tier > 0 ? recipe.line.recipes[recipe.tier - 1].product : null;
      notes.push(...wrap('You need ' + (below ? this.prayerCost(recipe, true) + ' x ' + below.name + ', or ' : '')
        + this.prayerCost(recipe, false) + ' of any one item.', width - 4));
    }
    const height = Math.min(M.h - (M.h > 20 ? 6 : 2), body + notes.length + (notes.length ? 6 : 5));
    this.clicks.addClickOff(MODAL, () => { game.churchOpen = false; });
    this.clicks.add(MODAL, x, y, width, height, () => {});
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(x, y, width, height);
    p.rect(R(x, y, width, height));
    p.fg = 'YELLOW_BRIGHT'; p.bold = true; p.put(x + 2, y + 1, fit(title, width - 4)); p.bold = false;
    const visible = Math.max(1, height - (notes.length ? 6 : 5) - notes.length);
    const first = Math.max(0, Math.min(ch.selected - visible + 1, rows.length - visible));
    if (grid) {
      this.iconsOnTop = true;   // over the window, not under it
      this.churchPicks(p, x, y + 2, width, y + 2, visible);
      this.iconsOnTop = false;
    }
    if (!grid) for (let i = first; i < Math.min(rows.length, first + visible); i++) {
      const row = rows[i], ry = y + 2 + (i - first), selected = i === ch.selected;
      p.fg = row.action ? row.color || 'DEFAULT' : 'BLACK_BRIGHT';
      p.bold = selected;
      p.put(x + 2, ry, fit((selected ? '> ' : '  ') + row.label, width - 4));
      p.bold = false;
      this.clicks.option(MODAL, x + 1, ry, width - 2, 1, () => { ch.selected = i; if (row.action) row.action(); });
    }
    p.fg = 'BLACK_BRIGHT';
    let ny = y + 2 + Math.min(visible, body) + 1;
    for (const note of notes) p.put(x + 2, ny++, fit(note, width - 4));
    const hint = (this.phone ? '[tap] ' : '[Esc] ') + (ch.step === 'MAIN' ? 'close' : 'back');
    p.put(x + 2, y + height - 2, fit(hint, width - 4));
    this.clicks.option(MODAL, x + 2, y + height - 2, hint.length, 1, () => this.churchBack());
    this.clicks.addScroll(MODAL, x, y, width, height, step => { ch.selected = Math.max(0, Math.min(rows.length - 1, ch.selected + step)); });
    p.bg = null;
  }

  /**
   * The Merch Ant: "I crave prestige..." and two boxes, empty until you pick a prestige item from your inventory --
   * then what you'd give (all of it) ==> what you'd get (1 + its prestige of the plain one each), and Trade.
   */
  merchantWindow(p) {
    const game = this.game;
    if (!this.merchant) this.merchant = { item: null, note: null };
    const m = this.merchant;
    const close = () => { game.merchantOpen = false; };
    if (m.item && !(game.inventory.get(m.item) > 0)) m.item = null;
    const M = this.map.rect, width = Math.min(34, M.w - 2), height = Math.min(M.h - 2, 13);
    const r = R(M.x + ((M.w - width) >> 1), M.y + 1, width, height);
    this.clicks.addClickOff(MODAL, close);
    p.bg = 'BLACK'; p.fg = 'DEFAULT';
    p.fill(r.x, r.y, r.w, r.h);
    p.rect(r);
    p.fg = 'YELLOW_BRIGHT'; p.bold = true; p.put(r.x + 2, r.y, 'Merch Ant'); p.bold = false;
    this.clicks.add(MODAL, r.x, r.y, r.w, r.h, () => {});
    p.fg = 'WHITE'; p.put(r.x + 2, r.y + 2, fit('"I crave prestige..."', r.w - 4));
    // the two boxes
    const have = m.item ? game.inventory.get(m.item) : 0, back = m.item ? have * (1 + m.item.prestige) : 0;
    const count = n => String(n).length <= 6 ? String(n) : short(n);
    const box = (x, item, n, plus) => {
      p.fg = 'BLACK_BRIGHT'; p.rect(R(x, r.y + 4, 8, 4));
      if (!item) return;
      this.symbol(p, x + 1, r.y + 5, item);
      if (plus) { p.fg = 'YELLOW_BRIGHT'; p.put(x + 5, r.y + 5, ('+' + item.prestige).slice(0, 2)); }
      p.fg = 'WHITE'; p.put(x + 7 - count(n).length, r.y + 6, count(n));
    };
    const x0 = r.x + ((r.w - 23) >> 1);
    this.iconsOnTop = true;   // over the window, not under it
    box(x0, m.item, have, true);
    p.fg = 'DEFAULT'; p.put(x0 + 10, r.y + 5, '==>');
    box(x0 + 15, m.item && m.item.base, back, false);
    this.iconsOnTop = false;
    const y = r.y + 11;
    if (m.item) {
      const trade = '[ Trade ]';
      p.fg = 'GREEN_BRIGHT'; p.bold = true; p.put(r.x + 2, y, trade); p.bold = false;
      this.clicks.option(MODAL, r.x + 2, y, trade.length, 1, () => { game.trade(m.item, have); m.item = null; });
    } else {
      p.fg = 'BLACK_BRIGHT';
      wrap(m.note || this.hint('[click] a prestige item in your inventory'), r.w - 4).slice(0, 2)
        .forEach((line, i) => p.put(r.x + 2, r.y + 9 + i, line));
    }
    p.fg = 'BLACK_BRIGHT'; p.put(r.right - 7, y, 'Close');
    this.clicks.option(MODAL, r.right - 7, y, 5, 1, close);
    // inventory cells pick what to trade while the ant's open
    if (this.invGrid) {
      const inv = game.slots(game.inventory), { r: ir, xs, ys, width: iw } = this.invGrid;
      this.clicks.add(MODAL, ir.x, ir.y, ir.w, ir.h, () => {});
      for (let rr = 0; rr < ys.length; rr++) {
        for (let c = 0; c < xs.length; c++) {
          const slot = inv[(this.inv.top + rr) * xs.length + c];
          if (!slot) continue;
          this.clicks.option(MODAL, xs[c], ys[rr], iw, 2, (button) => {
            const item = slot.product;
            if (!(item.prestige > 0)) { m.note = 'Only prestige items, please.'; return; }
            m.item = item; m.note = null;
            if (button === 0) return;
            const held = game.inventory.get(item) || 0, stack = game.stackSize(item);
            this.openPopup(item.name, game.itemColor(item), [['Trade Stack', stack], ['Trade 10 Stacks', 10 * stack], ['Trade All', held]]
              .map(([label, n]) => ({ label, action: () => { game.trade(item, Math.min(n, held)); m.item = null; } })), xs[c] + iw, ys[rr]);
          });
        }
      }
    }
    p.bg = null;
  }

  /** Enter on the chat line: "/1 ..." runs an admin command, anything else is said to everyone. */
  sendChat() {
    const text = (this.chat || '').trim(), to = this.chatTo;
    this.chat = null;
    if (to) { if (text) this.game.whisper(to, text); return; }
    if (text.startsWith('/')) {
      const [command, ...args] = text.slice(1).split(/\s+/);
      if (command) this.game.admin(command, args);
    } else if (text) this.game.chat(text);
  }

  // ---- keys -------------------------------------------------------------------------------------------------------
  /** Returns true if the key was used. */
  key(e) {
    const game = this.game;
    const k = e.key;
    if (this.chat != null) return false;   // the chat input has the keys
    if (this.offer) {
      const l = k.toLowerCase();
      if (l === 'y' || k === 'Enter') this.answerOffer(true);
      else if (l === 'n' || k === 'Escape') this.answerOffer(false);
      return true;
    }
    if (this.popup) {
      const pop = this.popup;
      if (k === 'ArrowUp') pop.index = Math.max(0, pop.index - 1);
      else if (k === 'ArrowDown') pop.index = Math.min(pop.options.length - 1, pop.index + 1);
      else if (k === 'Enter') this.choose(pop.index);
      else if (k === 'Escape') this.popup = null;
      return true;
    }
    if (game.churchOpen && this.church && (k === 'ArrowUp' || k === 'ArrowDown' || k === 'ArrowLeft' || k === 'ArrowRight' || k === 'Enter')) {
      const rows = this.churchRows(), ch = this.church;
      const across = ch.step === 'SKILL' ? Math.max(1, Math.floor((Math.min(50, this.map.rect.w - 2) - 4) / 12)) : 1;
      const step = k === 'ArrowUp' ? -across : k === 'ArrowDown' ? across : k === 'ArrowLeft' ? -1 : 1;
      if (k !== 'Enter' && (ch.step === 'SKILL' || ch.step === 'RECIPE' || k === 'ArrowUp' || k === 'ArrowDown')) {
        ch.selected = Math.max(0, Math.min(rows.length - 1, ch.selected + step));
      }
      else if (k === 'Enter' && rows[ch.selected] && rows[ch.selected].action) rows[ch.selected].action();
      return true;
    }
    if (k === 'Escape') {
      if (game.churchOpen) this.churchBack();
      else if (game.gymOpen && this.gym && this.gym.trophy) this.gym.trophy = null;
      else if (game.bankOpen || game.stationOpen || game.merchantOpen || game.gymOpen) {
        game.closeWindows();
      } else if (this.using) this.using = null;
      else if (this.friendView) this.friendView = null;
      else if (this.tab === 'SKILLS' && this.skills.page && !this.mapTab) this.skillBack();
      else this.mapTab = false;
      return true;
    }
    if (k === '/' || k === 'Enter') { this.chat = ''; return true; }
    const lower = k.toLowerCase();
    if (lower === 'q') { game.perform(); return true; }
    if (lower === 'r') { game.toggleRunning(); return true; }
    if (lower === 'f') { game.send('Attack'); return true; }
    if (lower === 'g') {
      if (this.tab === 'SKILLS' && this.skills.page && !this.mapTab) {
        const skill = this.skills.page, next = game.content.milestones(skill.type).find(l => l > game.level(skill));
        if (next) game.setGoal(skill, next);
      } else game.send('AddGoal');
      return true;
    }
    if (lower === 'p' && this.shownTab() === 'SKILLS' && this.skills.page && this.canPrestige(this.skills.page)) {
      this.askPrestige(this.skills.page, MAP.right + 4, 8);
      return true;
    }
    if (lower === 'i') { this.pick('INVENTORY'); return true; }
    if (lower === 'v') { this.mapTab = !this.mapTab; return true; }
    const tab = TABS_LIST.find(t => t.key === k);
    if (tab) { this.pick(tab.id); return true; }
    return false;
  }
}

/** One cell straight onto a painter's layer (colours as given, not the painter's). */
function base(p, x, y, ch, fg, bg, bold) {
  p.layer.set(x, y, ch, fg, bg, bold);
}

/** Dark grey lines between cells inside a frame, joined to it (InventoryPanel.drawCellLines). */
function cellLines(p, frame, xs, ys) {
  const set = (x, y, ch) => p.layer.set(x, y, ch, 'BLACK_BRIGHT', p.bg, false);
  const bottom = frame.bottom;
  const end = Math.min(bottom, ys[ys.length - 1] + 2);
  const across = new Set(ys.map(y => y + 2).filter(y => y <= end));
  for (const y of across) {
    if (y === bottom) continue;
    for (let x = frame.x + 1; x < frame.right; x++) set(x, y, '─');
    set(frame.x, y, '├');
    set(frame.right, y, '┤');
  }
  for (let c = 1; c < xs.length; c++) {
    const x = xs[c] - 1;
    set(x, frame.y, '┬');
    for (let y = frame.y + 1; y < end; y++) set(x, y, across.has(y) ? '┼' : '│');
    set(x, end, '┴');
  }
}

/**
 * Lines metro-map style (StationView.Tracks): each path a list of [x, y] cells, every cell remembering which ways its
 * lines go (up 1, down 2, left 4, right 8) so where they meet they join -- corners, tees, crossings.
 * {@code paths}: [{ cells, lit }]; draws each cell grey, or {@code litColor} if a lit path goes through it.
 */
function drawTracks(p, paths, litColor, inside = () => true) {
  const ways = new Map(), lit = new Set(), key = (x, y) => x + ',' + y;
  const mark = ([x, y], way, on) => { ways.set(key(x, y), (ways.get(key(x, y)) || 0) | way); if (on) lit.add(key(x, y)); };
  for (const { cells, lit: on } of paths) {
    for (let k = 1; k < cells.length; k++) {
      const [fx, fy] = cells[k - 1], [tx, ty] = cells[k], dx = tx - fx, dy = ty - fy;
      mark(cells[k - 1], dx > 0 ? 8 : dx < 0 ? 4 : dy > 0 ? 2 : 1, on);
      mark(cells[k], dx > 0 ? 4 : dx < 0 ? 8 : dy > 0 ? 1 : 2, on);
    }
  }
  const glyph = w => ({ 15: '┼', 11: '├', 7: '┤', 14: '┬', 13: '┴', 10: '┌', 6: '┐', 9: '└', 5: '┘' })[w]
    || ((w & 3) ? '│' : '─');
  for (const [k, w] of ways) {
    const [x, y] = k.split(',').map(Number);
    if (!inside(x, y)) continue;
    p.fg = lit.has(k) ? litColor : 'BLACK_BRIGHT';
    p.set(x, y, glyph(w));
  }
}

/** The cells from (ax, ay) to (bx, by): along then up/down ({@code alongFirst}), or up/down then along. */
function lPath(ax, ay, bx, by, alongFirst) {
  const cells = [[ax, ay]];
  let x = ax, y = ay;
  const ex = alongFirst ? bx : ax, ey = alongFirst ? ay : by;
  while (x !== ex || y !== ey) { x += Math.sign(ex - x); y += Math.sign(ey - y); cells.push([x, y]); }
  while (x !== bx || y !== by) { x += Math.sign(bx - x); y += Math.sign(by - y); cells.push([x, y]); }
  return cells;
}

/** A scrollbar thumb on a frame's right border (Widget.drawScrollbar). */
function scrollbar(p, x, top, bottom, first, visible, total) {
  const track = bottom - top - 1;
  if (total <= visible || track <= 0) return;
  const thumb = Math.max(1, Math.round(track * visible / total));
  const start = top + 1 + Math.round((track - thumb) * first / (total - visible));
  for (let y = start; y < start + thumb; y++) p.layer.set(x, y, '║', 'WHITE_BRIGHT', null, false);
}

/** Word-wraps {@code text} to lines of at most {@code width}. */
function wrap(text, width) {
  const lines = [];
  let line = '';
  for (const word of text.split(' ')) {
    if (line && line.length + 1 + word.length > width) { lines.push(line); line = ''; }
    line += (line ? ' ' : '') + word;
  }
  if (line) lines.push(line);
  return lines;
}

const FOLDS = ['', '', 'twofold', 'threefold', 'fourfold', 'fivefold', 'sixfold', 'sevenfold', 'eightfold', 'ninefold', 'tenfold'];

/** The church's line about the next new skill (ChurchView.oracle). */
function oracleLine(said) {
  const days = m => {
    m = Math.max(0, m);
    const two = n => String(n).padStart(2, '0');
    return two(Math.floor(m / 1440)) + ' days ' + two(Math.floor(m / 60) % 24) + ' hours ' + two(m % 60) + ' minutes';
  };
  if (!said || !said.known) return 'Our oracles are silent on when a new craft will dawn.';
  if (said.fold >= 2) {
    return 'The prophecy should have been fulfilled ' + (said.fold < FOLDS.length ? FOLDS[said.fold] : said.fold + '-fold')
      + ' by now... the new craft is ' + days(-said.minutes) + ' late.';
  }
  if (said.minutes <= 0) return 'The prophecy foretold that the new craft should have arrived ' + days(-said.minutes) + ' ago...';
  return 'Our oracles suggest that the dawn of a new craft is due in: ' + days(said.minutes);
}

/** Wrapped to {@code width}; a "marker  <-- what it is" line keeps its marker column, the rest hanging under it. */
function wrapMarked(line, width) {
  const arrow = line.indexOf('<-- ');
  if (arrow < 0) return wrap(line, width);
  const head = line.slice(0, arrow + 4);
  return wrap(line.slice(arrow + 4), Math.max(10, width - head.length)).map((rest, i) => (i === 0 ? head : ' '.repeat(head.length)) + rest);
}
