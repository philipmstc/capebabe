// Pixel-art identicons: a 9x9 picture for each skill (its nodes), 7x7 for each kind of monster, worked out from the world's
// name and the thing's id and name, so everyone sees the same one and nothing's stored or sent. Drawn at a whole
// number of screen pixels per picture pixel, with no smoothing, so they stay crisp like the terminal's text.
import { color, isLight } from './term.js';

// the biggest picture (a skill's); a monster's is MONSTER x MONSTER
const SIZE = 9, MONSTER = 7;

// FNV-1a, 32 bits: the same in any language (unlike Java's hashCode), so the Java client could draw these too.
export function fnv(text) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash;
}

function bits(seed, count) {
  const out = [];
  for (let h = fnv(seed); out.length < count; h = fnv(seed + ':' + h)) {
    for (let b = 0; b < 32 && out.length < count; b++) out.push((h >>> b) & 1);
  }
  return out;
}

const cache = new Map();

/** A {@code size} x {@code size} picture as a canvas; {@code pixels(set)} calls set(x, y, colour) for each lit pixel. */
function picture(id, size, pixels) {
  id += isLight() ? '/light' : '';
  let canvas = cache.get(id);
  if (!canvas) {
    canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    const g = canvas.getContext('2d');
    pixels((x, y, c) => { g.fillStyle = color(c); g.fillRect(x, y, 1, 1); });
    cache.set(id, canvas);
  }
  return canvas;
}

// The shape by skill type, round the middle (4, 4), its outline the pixels at its edge: primary the whole square
// (sharp corners); secondary a squircle (the square less its 4 corner pixels); tertiary a rounded diamond, nearly
// an octagon. The primary's corner cut only bites past 9x9 (|d| reaches 5 at 11x11).
const MIDDLE = SIZE >> 1;
function inside(type, x, y) {
  const dx = x - MIDDLE, dy = y - MIDDLE;
  if (type === 'SECONDARY') return dx * dx + dy * dy <= 26;
  if (type === 'TERTIARY') return Math.abs(dx) + Math.abs(dy) <= 5;
  return Math.abs(dx) <= 5 && Math.abs(dy) <= 5 && (Math.abs(dx) != 5 || Math.abs(dy) != 5);
}

/**
 * A skill's picture: its outline in {@code dark}, and inside it a mirrored pattern in {@code bright} (the
 * skill's colours, or the greys of an empty node).
 */
const drawEdges = true;
/** What a skill's picture grows from: its name as it was first saved ("skill3", before names got a space), so pictures don't change. */
function seedName(skill) {
  return skill.name.replace(/^skill (\d+)$/, 'skill$1');
}

export function skillSprite(world, skill, bright, dark) {
  return picture(['skill', world, skill.id, seedName(skill), bright, dark].join('/'), SIZE, set => {
    let pattern = bits(world + '/skill/' + skill.id + '/' + seedName(skill), 15);
    if (pattern.filter(Boolean).length < 4) pattern = pattern.map(b => 1 - b);
    for (let y = 0; y < SIZE; y++) {
      for (let x = 0; x < SIZE; x++) {
        if (!inside(skill.type, x, y)) continue;
        const edge = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([ox, oy]) => {
          const nx = x + ox, ny = y + oy;
          return nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE || !inside(skill.type, nx, ny);
        });
        if (edge && drawEdges) set(x, y, dark);
        // the pattern goes on top: over the outline, where they meet
        if (Math.abs(x - MIDDLE) <= 2 && Math.abs(y - MIDDLE) <= 2) {
          const mx = x <= MIDDLE ? x : SIZE - 1 - x;   // mirrored left to right
          if (pattern[(y - MIDDLE + 2) * 3 + (mx - MIDDLE + 2)]) set(x, y, bright);
        }
      }
    }
  });
}

/** A monster's picture: a 7x7 "invader", mirrored, always with a spine, two eyes and some legs, in its colour. */
export function monsterSprite(world, type, body) {
  return picture(['monster', world, type.id, type.name, body].join('/'), MONSTER, set => {
    const b = bits(world + '/monster/' + type.id + '/' + type.name, 28);
    const grid = [];
    for (let y = 0; y < MONSTER; y++) {
      grid.push([]);
      for (let x = 0; x < 4; x++) grid[y].push(b[y * 4 + x]);
    }
    for (let y = 1; y < 6; y++) grid[y][3] = 1;                       // spine
    grid[1][1] = grid[3][1] = grid[2][0] = grid[2][2] = 1;             // round the eye
    grid[2][1] = 2;                                                    // the eye
    grid[0][0] = 0;
    if (!grid[6][0] && !grid[6][1] && !grid[6][2]) grid[6][1] = 1;    // a leg
    for (let y = 0; y < MONSTER; y++) {
      for (let x = 0; x < MONSTER; x++) {
        const v = grid[y][x <= 3 ? x : 6 - x];
        if (v) set(x, y, v === 2 ? 'WHITE_BRIGHT' : body);
      }
    }
  });
}

/** Screen pixels per picture pixel for a tile {@code w} x {@code h}: as big as the biggest picture fits, with room. */
export function pixelScale(w, h) {
  return Math.max(1, Math.floor((Math.min(w, h) - 2) / SIZE));
}

/** Draws a picture centred on cx, cy at {@code scale}. */
export function drawSprite(g, sprite, cx, cy, scale) {
  const s = sprite.width * scale;
  g.imageSmoothingEnabled = false;
  g.drawImage(sprite, Math.round(cx - s / 2), Math.round(cy - s / 2), s, s);
}
