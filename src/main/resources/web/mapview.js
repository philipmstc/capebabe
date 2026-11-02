// The map (MapView.java), drawn in pixels inside its frame so things slide between tiles: the dots, nodes as their
// skill's pixel picture with tier marks either side, buildings as their terminal pictures, players as arrows,
// monsters as their pixel pictures, waypoints in brackets of their skill's colour, the hovered tile, and floating text.
import { color, drawBox, FONT } from './term.js';
import { tierMarks, key } from './content.js';
import { ARROWS } from './game.js';
import { skillSprite, monsterSprite, drawSprite, pixelScale } from './sprites.js';

// the terminal's map: 29 x 23 tiles (a phone's map is as big as its screen allows)
export const BOARD_WIDTH = 29, BOARD_HEIGHT = 23;

export class MapView {
  constructor(rect) {
    this.rect = rect;            // the frame, in cells (2 columns a tile, 1 row)
  }

  /** The tile column / row your tile is drawn in, counting from the frame's inside. */
  get centerColumn() { return ((this.rect.w - 1) >> 1) >> 1; }
  get centerRow() { return (this.rect.h - 2) >> 1; }

  /** The camera: your drawn position, so the map slides with you. */
  camera(game) {
    return { x: game.glide.x, y: game.glide.y };
  }

  /** Pixel centre of world tile (wx, wy) with the camera at cam. */
  center(wx, wy, cam, cw, ch) {
    const x0 = (this.rect.x + 1) * cw, y0 = (this.rect.y + 1) * ch;
    return {
      x: x0 + (wx - cam.x + this.centerColumn) * 2 * cw + cw / 2,
      y: y0 + (wy - cam.y + this.centerRow) * ch + ch / 2,
    };
  }

  /** The world tile under pixel (px, py), or null if that's not on the map. */
  tileAt(px, py, game, cw, ch) {
    const r = this.rect;
    if (px < (r.x + 1) * cw || px >= (r.x + r.w - 1) * cw || py < (r.y + 1) * ch || py >= (r.y + r.h - 1) * ch) return null;
    const cam = this.camera(game);
    const x0 = (r.x + 1) * cw, y0 = (r.y + 1) * ch;
    return {
      x: Math.round((px - x0 - cw / 2) / (2 * cw) - this.centerColumn + cam.x),
      y: Math.round((py - y0 - ch / 2) / ch - this.centerRow + cam.y),
    };
  }

  /** Whether world tile p is inside the view around the camera (for edge markers). */
  inView(p, game) {
    const cam = this.camera(game);
    const columns = (this.rect.w - 1) >> 1, rows = this.rect.h - 2;
    return p.x - cam.x >= -this.centerColumn && p.x - cam.x < columns - this.centerColumn
      && p.y - cam.y >= -this.centerRow && p.y - cam.y < rows - this.centerRow;
  }

  draw(g, game, cw, ch, fontPx, hovered, now) {
    const r = this.rect;
    const clip = { x: (r.x + 1) * cw, y: (r.y + 1) * ch, w: (r.w - 2) * cw, h: (r.h - 2) * ch };
    g.save();
    g.beginPath();
    g.rect(clip.x, clip.y, clip.w, clip.h);
    g.clip();
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    const cam = this.camera(game);
    const c = game.content;
    const columns = (r.w - 1) >> 1, rows = r.h - 2;
    const x0 = Math.floor(cam.x - this.centerColumn) - 1, x1 = Math.ceil(cam.x - this.centerColumn + columns) + 1;
    const y0 = Math.floor(cam.y - this.centerRow) - 1, y1 = Math.ceil(cam.y - this.centerRow + rows) + 1;
    const scale = pixelScale(2 * cw, ch);
    const text = (ch2, px, py, fg, bold) => {
      if (ch2 === ' ' || ch2 == null) return;
      if (drawBox(g, ch2, px - cw / 2, py - ch / 2, cw, ch, color(fg))) return;
      g.font = (bold ? 'bold ' : '') + fontPx + 'px ' + FONT;
      g.fillStyle = color(fg);
      g.fillText(ch2, px, py + 1);
    };
    // the grid's dots: one dark grey square the size of a picture's pixel
    const pixel = (x, y, fg) => { g.fillStyle = color(fg); g.fillRect(x, y, scale, scale); };
    /** A node's tier marks either side of its picture, as the terminal's, a picture-pixel further out. */
    const pips = (px, py, tier, fg) => {
      const marks = tierMarks(tier);
      text(marks[0], px - cw - scale, py, fg);
      text(marks[1], px + cw + scale, py, fg);
    };
    const cellBg = (px, py, bg, cells = 1) => {
      g.fillStyle = color(bg);
      g.fillRect(Math.round(px - cw / 2 - (cells > 1 ? cw : 0)), Math.round(py - ch / 2), cw * cells, ch);
    };

    // the hovered tile: grey behind it (and its sides)
    if (hovered) {
      const p = this.center(hovered.x, hovered.y, cam, cw, ch);
      cellBg(p.x, p.y, 'BLACK_BRIGHT', 3);
    }

    // the ground and nodes, tile by tile
    for (let wy = y0; wy <= y1; wy++) {
      for (let wx = x0; wx <= x1; wx++) {
        const p = this.center(wx, wy, cam, cw, ch);
        const node = c.nodesAt.get(key(wx, wy));
        const building = c.buildingsAt.get(key(wx, wy));
        if (building) continue;   // drawn whole below
        const lit = hovered && hovered.x === wx && hovered.y === wy;
        // on the grey of the hovered tile, grey shows white (MapView.drawTile)
        const shade = fg => lit && fg === 'BLACK_BRIGHT' ? 'WHITE' : fg;
        if (node) {
          const fg = shade(game.nodeColor(node));
          const skill = node.recipe.skill;
          if (game.highlighted.has(node.recipe)) cellBg(p.x, p.y, game.skillColor(skill, true), 3);
          // outlined in the skill's dark colour, or grey when it's empty or dimmed (tried, and too high for you)
          const faded = game.status(node).depleted || (game.tried.has(node.id) && game.lockedFor(node));
          const outline = faded ? (lit ? 'BLACK' : 'BLACK_BRIGHT') : game.skillColor(skill, true);
          drawSprite(g, skillSprite(game.worldName, skill, fg, outline), p.x, p.y, scale);
          if (game.showProductTiers) pips(p.x, p.y, node.recipe.product.tier, fg);
          continue;
        }
        const origin = wx === 0 && wy === 0;
        const target = game.walkTarget && game.walkTarget.x === wx && game.walkTarget.y === wy;
        const tens = wx % 10 === 0 && wy % 10 === 0;
        const dot = target ? 'RED_BRIGHT' : tens ? 'WHITE_BRIGHT' : shade('BLACK_BRIGHT');
        if (origin) text('o', p.x, p.y, dot);
        else pixel(Math.round(p.x - scale / 2), Math.round(p.y - scale / 2), dot);
      }
    }

    // buildings: their pictures, a character a tile and one in each gap
    for (const b of c.buildings) {
      if (b.x + b.width < x0 - 2 || b.x > x1 + 2 || b.y + b.height < y0 - 1 || b.y > y1 + 1) continue;
      const picture = b.info.picture;
      for (let row = 0; row < picture.length; row++) {
        for (let col = 0; col < picture[row].length; col++) {
          let ch2 = picture[row][col];
          if (ch2 === ' ' || (col % 2 === 0 && ch2 === '.')) continue;
          const tx = b.x + (col >> 1), ty = b.y + row;
          const offset = col % 2 === 0 ? 0 : 1;
          const p = this.center(tx, ty, cam, cw, ch);
          const px = p.x + offset * cw;
          const keeper = b.info.keeper && ch2 === b.info.keeper;
          if (keeper && game.frames % 40 >= 34) ch2 = ch2.toUpperCase();
          const paint = b.info.paint.length ? (b.info.paint[row][col] || ' ').toLowerCase() : ' ';
          const fg = keeper ? 'RED' : paint === 'b' ? 'YELLOW' : paint === 'g' ? 'WHITE' : 'YELLOW_BRIGHT';
          text(ch2, px, p.y, fg);
        }
      }
    }

    // waypoints on screen: brackets either side, in the skill's colour
    for (const node of game.waypoints) {
      const p = this.center(node.x, node.y, cam, cw, ch), fg = game.skillColor(node.recipe.skill);
      text('[', p.x - cw, p.y, fg);
      text(']', p.x + cw, p.y, fg);
    }

    const friend = game.friendTarget();
    if (friend) {
      const p = this.center(friend.x, friend.y, cam, cw, ch);
      text('[', p.x - cw, p.y, 'CYAN_BRIGHT');
      text(']', p.x + cw, p.y, 'CYAN_BRIGHT');
    }

    // monsters, then other players, then you
    for (const o of game.others.values()) {
      if (o.info.kind !== 'MONSTER') continue;
      const type = c.monstersById.get(o.info.monsterTypeId);
      if (!type) continue;
      const p = this.center(Math.round(o.glide.x), Math.round(o.glide.y), cam, cw, ch);
      drawSprite(g, monsterSprite(game.worldName, type, game.monsterColor(type)), p.x, p.y, scale);
    }
    const flickerOff = Math.floor(game.frames / 6) % 2 === 0;
    for (const o of game.others.values()) {
      if (o.info.kind !== 'PLAYER') continue;
      const p = this.center(Math.round(o.glide.x), Math.round(o.glide.y), cam, cw, ch);
      text(ARROWS[o.info.facing] || ARROWS.DOWN, p.x, p.y, o.info.hp <= 0 && flickerOff ? 'BLACK_BRIGHT' : game.playerColor(o.info.colorIndex));
    }
    // the camera slides, but you (and everyone) hop a whole tile at a time, halfway through each step
    const me = this.center(Math.round(game.glide.x), Math.round(game.glide.y), cam, cw, ch);
    text(ARROWS[game.facing], me.x, me.y, game.hp <= 0 && flickerOff ? 'BLACK_BRIGHT' : game.playerColor(game.colorIndex), true);

    // floating text: xp and hits rise from where they happened, a level up follows you
    for (let i = game.popups.length - 1; i >= 0; i--) {
      const pop = game.popups[i];
      const age = (now - pop.at) / pop.life;
      if (age >= 1) { game.popups.splice(i, 1); continue; }
      const p = pop.follow ? me : this.center(pop.x, pop.y, cam, cw, ch);
      // a whole row at a time, like the terminal's
      const rise = pop.follow ? ch * 2 : ch * (1 + Math.floor(age * 3));
      const width = pop.text.length * cw;
      const px = pop.side < 0 ? p.x - cw - width / 2 : pop.side > 0 ? p.x + cw + width / 2 : p.x;
      const py = p.y - rise;
      g.font = 'bold ' + fontPx + 'px ' + FONT;
      if (pop.bg) {
        g.fillStyle = color(pop.bg);
        g.fillRect(Math.round(px - width / 2), Math.round(py - ch / 2), width, ch);
      }
      g.fillStyle = color(pop.fg);
      g.fillText(pop.text, px, py + 1);
    }
    g.restore();
  }
}

/** Which way, and where on the frame, an arrow to an off-screen tile goes (MapView.edgeTowards). */
export function edgeTowards(rect, game, target) {
  const cx = rect.x + 1 + (((rect.w - 1) >> 1) >> 1) * 2, cy = rect.y + 1 + ((rect.h - 2) >> 1);
  const dx = (target.x - game.glide.x) * 2, dy = target.y - game.glide.y;
  const left = rect.x, right = rect.x + rect.w - 1, top = rect.y, bottom = rect.y + rect.h - 1;
  const tx = dx === 0 ? Infinity : ((dx > 0 ? right : left) - cx) / dx;
  const ty = dy === 0 ? Infinity : ((dy > 0 ? bottom : top) - cy) / dy;
  if (tx < ty) {
    return { side: dx > 0 ? 'RIGHT' : 'LEFT', x: dx > 0 ? right : left, y: Math.max(top + 1, Math.min(bottom - 1, Math.round(cy + dy * tx))) };
  }
  return { side: dy > 0 ? 'DOWN' : 'UP', x: Math.max(left + 1, Math.min(right - 1, Math.round(cx + dx * ty))), y: dy > 0 ? bottom : top };
}
