// A terminal-sized character grid on a canvas, so the page looks like the terminal client: 142 x 40 cells, two
// layers (panels, then popups / windows over them), the terminal's colours, box-drawing characters drawn as real
// lines so frames join up, and the clickable regions of each frame (Clicks.java).

// the terminal's size, and the desktop layout's (a phone's grid is sized to its screen: see main.js)
export const COLS = 142, ROWS = 40;

// Lanterna's Swing terminal colours (standard VGA), by Lanterna's names
export const PALETTE = {
  DEFAULT: '#c0c0c0', BLACK: '#000000', RED: '#aa0000', GREEN: '#00aa00', YELLOW: '#aa5500', BLUE: '#5555cc',
  MAGENTA: '#aa00aa', CYAN: '#00aaaa', WHITE: '#aaaaaa', BLACK_BRIGHT: '#555555', RED_BRIGHT: '#ff5555',
  GREEN_BRIGHT: '#55ff55', YELLOW_BRIGHT: '#ffff55', BLUE_BRIGHT: '#8888ff', MAGENTA_BRIGHT: '#ff55ff',
  CYAN_BRIGHT: '#55ffff', WHITE_BRIGHT: '#ffffff',
};
export const BACKGROUND = '#000000';

// Light mode (LightMode.java): every colour swapped for its opposite -- black <-> bright white, dark grey <-> white,
// each colour <-> its complement with dark and bright swapped -- so the default is black on white.
const INVERTED = {
  DEFAULT: 'BLACK', BLACK: 'WHITE_BRIGHT', WHITE_BRIGHT: 'BLACK', BLACK_BRIGHT: 'WHITE', WHITE: 'BLACK_BRIGHT',
  RED: 'CYAN_BRIGHT', CYAN_BRIGHT: 'RED', RED_BRIGHT: 'CYAN', CYAN: 'RED_BRIGHT', GREEN: 'MAGENTA_BRIGHT',
  MAGENTA_BRIGHT: 'GREEN', GREEN_BRIGHT: 'MAGENTA', MAGENTA: 'GREEN_BRIGHT', BLUE: 'YELLOW_BRIGHT', YELLOW_BRIGHT: 'BLUE',
  BLUE_BRIGHT: 'YELLOW', YELLOW: 'BLUE_BRIGHT',
};
let light = false;
export const setLight = on => { light = !!on; };
export const isLight = () => light;
export const color = c => {
  const name = c || 'DEFAULT';
  if (name[0] === '#') return light ? invertHex(name) : name;
  return PALETTE[light ? INVERTED[name] || name : name] || name;
};

const hex = rgb => '#' + rgb.map(v => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
const rgbOf = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const invertHex = h => hex(rgbOf(h).map(v => 255 - v));

/** How bright a colour looks (relative luminance, 0 black to 1 white). */
function luminance(rgb) {
  const [r, g, b] = rgb.map(v => { const c = v / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

// as bright as the terminal's bright red (#ff5555): the darkest a skill's colour may look
const LEAST_LUMINANCE = luminance([255, 85, 85]);
const hues = new Map();

/**
 * A skill's colour from its hue (degrees): the pure hue a third of the way to white, as the terminal's bright colours
 * are -- and a hue that still looks darker than bright red (the blues) further, until it doesn't, so every skill
 * reads about as clearly. {@code dark}: half as bright, for outlines and dim marks.
 */
export function hueColor(hue, dark) {
  const id = hue + (dark ? 'd' : '');
  let made = hues.get(id);
  if (!made) {
    const h = ((hue % 360) + 360) % 360 / 60, x = 1 - Math.abs(h % 2 - 1);
    const pure = [[1, x, 0], [x, 1, 0], [0, 1, x], [0, x, 1], [x, 0, 1], [1, 0, x]][Math.floor(h) % 6];
    let toWhite = 1 / 3, rgb;
    do {
      rgb = pure.map(v => 255 * (v + (1 - v) * toWhite));
      toWhite += 0.02;
    } while (luminance(rgb) < LEAST_LUMINANCE && toWhite < 0.9);
    made = hex(dark ? rgb.map(v => v * 0.50) : rgb);
    hues.set(id, made);
  }
  return made;
}

/** A colour's dim version: a palette name's non-bright one, a skill's colour two thirds as bright. */
export function darker(c) {
  if (c && c[0] === '#') return hex(rgbOf(c).map(v => v * 0.66));
  return c && c.endsWith('_BRIGHT') && c !== 'BLACK_BRIGHT' ? c.replace('_BRIGHT', '') : c;
}
/** What's behind everything: black, or white in light mode. */
export const background = () => light ? PALETTE.WHITE_BRIGHT : BACKGROUND;

export const FONT = 'Consolas, "DejaVu Sans Mono", Menlo, "Liberation Mono", monospace';

export class Layer {
  constructor(transparent, cols = COLS, rows = ROWS) {
    this.transparent = transparent;
    this.cols = cols;
    this.rows = rows;
    const n = cols * rows;
    this.ch = new Array(n);
    this.fg = new Array(n);
    this.bg = new Array(n);
    this.bold = new Uint8Array(n);
    this.clear();
  }

  clear() {
    this.ch.fill(this.transparent ? null : ' ');
    this.fg.fill('DEFAULT');
    this.bg.fill(null);
    this.bold.fill(0);
  }

  set(x, y, ch, fg, bg, bold) {
    if (x < 0 || y < 0 || x >= this.cols || y >= this.rows) return;
    const i = y * this.cols + x;
    this.ch[i] = ch;
    this.fg[i] = fg;
    this.bg[i] = bg;
    this.bold[i] = bold ? 1 : 0;
  }
}

// What widgets draw with, like Lanterna's TextGraphics: a colour, background and boldness, then put / set / fill.
export class Painter {
  constructor(layer) {
    this.layer = layer;
    this.fg = 'DEFAULT';
    this.bg = null;
    this.bold = false;
  }

  set(x, y, ch) {
    this.layer.set(x, y, ch, this.fg, this.bg, this.bold);
    return this;
  }

  put(x, y, text) {
    text = String(text);
    for (let i = 0; i < text.length; i++) this.layer.set(x + i, y, text[i], this.fg, this.bg, this.bold);
    return this;
  }

  fill(x, y, w, h, ch = ' ') {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.layer.set(x + i, y + j, ch, this.fg, this.bg, this.bold);
    return this;
  }

  /** A single-line box with its corners at x, y and right, bottom (Widget.drawFrame). */
  frame(x, y, right, bottom) {
    for (let i = x + 1; i < right; i++) { this.set(i, y, '─'); this.set(i, bottom, '─'); }
    for (let j = y + 1; j < bottom; j++) { this.set(x, j, '│'); this.set(right, j, '│'); }
    this.set(x, y, '┌').set(right, y, '┐').set(x, bottom, '└').set(right, bottom, '┘');
    return this;
  }

  rect(r) {
    return this.frame(r.x, r.y, r.x + r.w - 1, r.y + r.h - 1);
  }
}

// Box drawing: per character, its arms [up, down, left, right]: 0 none, 1 single, 2 double.
const BOX = {};
function arms(chars, up, down, left, right) {
  for (const c of chars) BOX[c] = [up, down, left, right];
}
arms('─', 0, 0, 1, 1); arms('│', 1, 1, 0, 0);
arms('┌', 0, 1, 0, 1); arms('┐', 0, 1, 1, 0); arms('└', 1, 0, 0, 1); arms('┘', 1, 0, 1, 0);
arms('├', 1, 1, 0, 1); arms('┤', 1, 1, 1, 0); arms('┬', 0, 1, 1, 1); arms('┴', 1, 0, 1, 1);
arms('┼', 1, 1, 1, 1);
arms('═', 0, 0, 2, 2); arms('║', 2, 2, 0, 0);
arms('╔', 0, 2, 0, 2); arms('╗', 0, 2, 2, 0); arms('╚', 2, 0, 0, 2); arms('╝', 2, 0, 2, 0);
arms('╠', 2, 2, 0, 2); arms('╣', 2, 2, 2, 0); arms('╦', 0, 2, 2, 2); arms('╩', 2, 0, 2, 2);
arms('╬', 2, 2, 2, 2);
arms('╒', 0, 1, 0, 2); arms('╕', 0, 1, 2, 0); arms('╘', 1, 0, 0, 2); arms('╛', 1, 0, 2, 0);
arms('╓', 0, 2, 0, 1); arms('╖', 0, 2, 1, 0); arms('╙', 2, 0, 0, 1); arms('╜', 2, 0, 1, 0);
arms('╞', 1, 1, 0, 2); arms('╡', 1, 1, 2, 0); arms('╟', 2, 2, 0, 1); arms('╢', 2, 2, 1, 0);
arms('╤', 0, 1, 2, 2); arms('╧', 1, 0, 2, 2); arms('╥', 0, 2, 1, 1); arms('╨', 2, 0, 1, 1);
arms('╪', 1, 1, 2, 2); arms('╫', 2, 2, 1, 1);

/** Draws one box-drawing character into the cell at px, py (w x h), or returns false if it isn't one. */
export function drawBox(g, ch, px, py, w, h, fg) {
  const a = BOX[ch];
  if (!a) return false;
  const lw = Math.max(1, Math.round(w / 8));
  const gap = Math.max(2, Math.round(w / 4));
  const cx = Math.floor(px + w / 2), cy = Math.floor(py + h / 2);
  g.fillStyle = fg;
  const [up, down, left, right] = a;
  const vertical = (from, to, weight, ext) => {
    const top = Math.min(from, to), len = Math.abs(to - from);
    if (weight === 1) g.fillRect(cx - (lw >> 1), top - ext, lw, len + ext * 2);
    else {
      g.fillRect(cx - gap - (lw >> 1), top - ext, lw, len + ext * 2);
      g.fillRect(cx + gap - (lw >> 1), top - ext, lw, len + ext * 2);
    }
  };
  const horizontal = (from, to, weight, ext) => {
    const left = Math.min(from, to), len = Math.abs(to - from);
    if (weight === 1) g.fillRect(left - ext, cy - (lw >> 1), len + ext * 2, lw);
    else {
      g.fillRect(left - ext, cy - gap - (lw >> 1), len + ext * 2, lw);
      g.fillRect(left - ext, cy + gap - (lw >> 1), len + ext * 2, lw);
    }
  };
  // a double arm meeting a single one stops at the single line; doubles meeting each other reach the far rail
  const reachV = Math.max(left, right) === 2 ? gap : 0, reachH = Math.max(up, down) === 2 ? gap : 0;
  if (up) vertical(py, cy + (up === 2 ? -reachV : 0), up, up === 1 ? lw : 0);
  if (down) vertical(cy + (down === 2 ? reachV : 0), py + h, down, down === 1 ? lw : 0);
  if (left) horizontal(px, cx + (left === 2 ? -reachH : 0), left, left === 1 ? lw : 0);
  if (right) horizontal(cx + (right === 2 ? reachH : 0), px + w, right, right === 1 ? lw : 0);
  if (up === 2 && down === 0 && (left || right)) vertical(cy - gap, cy + (left === 2 || right === 2 ? gap : 0), 2, 0);
  return true;
}

// The clickable parts of the screen this frame, by layer (BASE < POPUP < MODAL < MENU); the topmost under the mouse
// wins. An "option" lights up (grey) under the mouse. A layer's "click off" runs when a click lands below it.
// MENU is a right-click menu: always over everything, windows (the bank...) included, as it's what you just opened.
export const BASE = 0, POPUP = 1, MODAL = 2, MENU = 3;

export class Clicks {
  constructor() { this.clear(); }

  clear() {
    this.regions = [];
    this.scrolls = [];
    this.offs = [];
  }

  add(layer, x, y, w, h, handler, option = false) {
    const region = { layer, x, y, w, h, handler, option };
    this.regions.push(region);
    return region;
  }

  option(layer, x, y, w, h, handler) {
    return this.add(layer, x, y, w, h, handler, true);
  }

  addScroll(layer, x, y, w, h, handler) {
    this.scrolls.push({ layer, x, y, w, h, handler });
  }

  addClickOff(layer, handler) {
    this.offs.push({ layer, handler });
  }

  at(x, y, list = this.regions) {
    let best = null;
    for (const r of list) {
      if (x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h && (!best || r.layer >= best.layer)) best = r;
    }
    return best;
  }

  topLayer() {
    return Math.max(BASE, ...this.regions.map(r => r.layer));
  }

  /** A click on cell (x, y) -- at pixel (px, py), for the map -- with button 0 left, 2 right. */
  click(button, x, y, px, py) {
    const hit = this.at(x, y);
    const layer = hit ? hit.layer : BASE;
    let handled = false;
    for (const off of this.offs) {
      if (off.layer > layer) { off.handler(); handled = true; }
    }
    if (hit && hit.handler) { hit.handler(button, x, y, px, py); handled = true; }
    return handled;
  }

  scroll(step, x, y) {
    const hit = this.at(x, y, this.scrolls);
    if (hit) hit.handler(step);
  }
}
