// The web client: logs in over a WebSocket to the same server as the terminal client and draws the game the way the
// terminal client does -- a 142 x 40 character grid (term.js) with the map inside it in pixels, so things slide
// between tiles and nodes and monsters are pixel pictures. Messages are {"type": ..., "data": ...} (Wire.java).
import { COLS, ROWS, Layer, Clicks, drawBox, color, background, setLight, FONT } from './term.js';
import { Game } from './game.js';
import { UI } from './ui.js';
import { Tutorial } from './tutorial.js';
import { drawSprite, pixelScale } from './sprites.js';

const metaReady = fetch('meta.json').then(r => r.json());
const $ = id => document.getElementById(id);
const canvas = $('screen');
const g = canvas.getContext('2d');

let socket = null, game = null, ui = null;
let base = new Layer(false), top = new Layer(true);
const clicks = new Clicks();

// The phone layout (ui.drawPhone) on a touch screen that's small, or with ?layout=phone (?layout=desktop: never).
const wanted = new URLSearchParams(location.search).get('layout');
const phone = wanted === 'phone' || (wanted !== 'desktop' && matchMedia('(pointer: coarse)').matches
  && Math.min(screen.width, screen.height) < 700);
let cols = COLS, rows = ROWS;
let cw = 10, ch = 21, fontPx = 18, offsetX = 0, offsetY = 0;
const mouse = { x: -1, y: -1, px: -1, py: -1 };

const send = (type, data = {}) => {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type, data }));
};

// ---- logging in --------------------------------------------------------------------------------------------------
$('login-form').addEventListener('submit', e => {
  e.preventDefault();
  $('error').textContent = 'Connecting...';
  connect($('name').value.trim(), $('password').value, $('world-password').value, false);
});

$('tutorial-link').addEventListener('click', e => {
  e.preventDefault();
  const name = $('name').value.trim();
  if (!name) { $('error').textContent = 'Pick a name first.'; $('name').focus(); return; }
  $('error').textContent = 'Starting the tutorial...';
  connect(name, null, null, true);
});

/**
 * Logs in to this server's world, or ({@code tutorial}) joins a tutorial world of your own on it (/tutorial: no
 * account, nothing kept). A new account is offered the tutorial once it's in.
 */
async function connect(name, password, worldPassword, tutorial) {
  const meta = await metaReady;
  if (socket) { const old = socket; socket = null; old.close(); }
  game = null; ui = null;
  // relative to the page, which may be under a lobby's /w/<world>/ (http -> ws, https -> wss)
  const ws = new WebSocket(new URL(tutorial ? 'tutorial' : 'ws', location.href).href.replace(/^http/, 'ws'));
  socket = ws;
  let newAccount = false;
  ws.onopen = () => {
    if (tutorial) send('Join', { name });
    else send('Login', { protocolVersion: meta.protocol, name, password });
  };
  ws.onmessage = m => {
    const { type, data } = JSON.parse(m.data);
    if (type === 'LoginAccepted') { newAccount = data.newAccount; send('EnterWorld', { worldPassword: worldPassword || null }); return; }
    if (type === 'LoginRefused') { $('error').textContent = data.reason; return; }
    if (type === 'Welcome') {
      game = new Game(meta, send);
      ui = new UI(game, () => ws.close());
      ui.phone = phone;
      ui.loadSettings();
      $('login').style.display = 'none';
      $('error').textContent = '';
      try { localStorage.setItem('capebabe.name', name); } catch (e) { /* fine */ }
      if (tutorial) {
        ui.tutorial = new Tutorial();
      } else if (newAccount) {
        ui.offer = true;
        ui.onTutorial = () => connect(name, null, null, true);
      }
    }
    if (game) game.apply(type, data);
    if (type === 'Welcome' && tutorial && !game.running) game.toggleRunning();
  };
  ws.onclose = () => {
    if (socket !== ws) return;
    const wasIn = game && game.inWorld;
    game = null; ui = null;
    $('login').style.display = 'grid';
    // a reason already given (logged in somewhere else) stays
    if (wasIn && !$('error').textContent) $('error').textContent = tutorial ? 'Tutorial over. Log in to join the world.' : 'Disconnected.';
    else if ($('error').textContent === 'Connecting...') $('error').textContent = "Couldn't connect.";
  };
}

try { $('name').value = localStorage.getItem('capebabe.name') || ''; } catch (e) { /* fine */ }

// ---- sizing: whole pixels a cell, about twice as tall as wide, like the terminal's ----------------------------------
// The desktop layout is the terminal's 142 x 40, scaled to fit. A phone's grid is as many cells as fit with text
// big enough to read (rows at least 16 css pixels), so its map is as big as the screen allows.
function resize() {
  const ratio = devicePixelRatio || 1;
  const w = innerWidth * ratio, h = innerHeight * ratio;
  if (phone) {
    // rows at least 16 css pixels; upright there's room for more of them
    rows = Math.max(14, Math.min(innerHeight > innerWidth ? 60 : 26, Math.floor(innerHeight / 16)));
    ch = Math.floor(h / rows);
    cw = Math.max(4, Math.round(ch / 2.1));
    cols = Math.floor(w / cw);
  } else {
    cols = COLS; rows = ROWS;
    cw = Math.max(4, Math.floor(Math.min(w / COLS, h / (ROWS * 2.1))));
    ch = Math.round(cw * 2.1);
  }
  fontPx = Math.round(cw * 1.75);
  canvas.width = Math.round(w);
  canvas.height = Math.round(h);
  canvas.style.width = innerWidth + 'px';
  canvas.style.height = innerHeight + 'px';
  offsetX = Math.floor((w - cw * cols) / 2);
  offsetY = Math.floor((h - ch * rows) / 2);
  if (base.cols !== cols || base.rows !== rows) {
    base = new Layer(false, cols, rows);
    top = new Layer(true, cols, rows);
  }
}
addEventListener('resize', resize);
resize();

// ---- input ---------------------------------------------------------------------------------------------------------
const MOVES = { w: [0, -1], ArrowUp: [0, -1], s: [0, 1], ArrowDown: [0, 1], a: [-1, 0], ArrowLeft: [-1, 0], d: [1, 0], ArrowRight: [1, 0] };
const held = [];
const moveKey = e => MOVES[e.key] ? e.key : MOVES[e.key.toLowerCase()] ? e.key.toLowerCase() : null;

addEventListener('keydown', e => {
  if (!game || document.activeElement.tagName === 'INPUT') return;
  if (e.key === 'Enter' && !ui.offer && !ui.popup && ui.dismissTutorial()) { e.preventDefault(); return; }
  if ((e.key === '/' || e.key === 'Enter') && !ui.offer && !ui.popup) { e.preventDefault(); ui.chat = ''; syncChat(); return; }
  const move = moveKey(e);
  if (move && ui.chat == null && !ui.popup) {
    e.preventDefault();
    if (!held.includes(move)) held.push(move);
    return;
  }
  if (ui.key(e)) e.preventDefault();
});
addEventListener('keyup', e => {
  const move = moveKey(e);
  const i = held.indexOf(move);
  if (i >= 0) held.splice(i, 1);
});
addEventListener('blur', () => { held.length = 0; });

// chat: typed into a hidden input (so pasting and phone keyboards work), shown on the log's last line
const chatInput = $('chat-input');
chatInput.addEventListener('input', () => { if (ui && ui.chat != null) ui.chat = chatInput.value; });
chatInput.addEventListener('keydown', e => {
  if (!ui) return;
  if (e.key === 'Enter') { e.preventDefault(); ui.chat = chatInput.value; ui.sendChat(); }
  else if (e.key === 'Escape') { e.preventDefault(); ui.chat = null; }
  e.stopPropagation();
});
/** Keeps the hidden input in step with the chat line opening and closing. */
function syncChat() {
  if (ui && ui.chat != null) {
    if (document.activeElement !== chatInput) { chatInput.value = ui.chat; chatInput.focus(); }
  } else if (document.activeElement === chatInput) {
    chatInput.value = '';
    chatInput.blur();
  }
}

/** Holding a direction keeps you going: a step (two running) asked for ahead of the server. */
function walk() {
  const k = held[held.length - 1];
  if (!k || !game || ui.chat != null || ui.popup) return;
  if (game.predicted.length < (game.running ? 2 : 1)) game.moveBy(...MOVES[k]);
}

function locate(e) {
  const ratio = devicePixelRatio || 1;
  mouse.px = e.clientX * ratio - offsetX;
  mouse.py = e.clientY * ratio - offsetY;
  mouse.x = Math.floor(mouse.px / cw);
  mouse.y = Math.floor(mouse.py / ch);
}
canvas.addEventListener('mousemove', locate);
canvas.addEventListener('mouseleave', () => { mouse.x = mouse.y = -1; });
canvas.addEventListener('contextmenu', e => e.preventDefault());
canvas.addEventListener('mousedown', e => {
  if (!game || performance.now() - lastTouch < 800) return;   // a touch's mouse echo
  locate(e);
  e.preventDefault();
  click(e.button === 2 ? 2 : 0, mouse.x, mouse.y, mouse.px, mouse.py);
});

/**
 * Handles a click on what's on screen now: the clickable regions are worked out again first, so a click straight
 * after something opened (a menu, before its first frame) goes to it, not to what was there before.
 */
function click(button, x, y, px, py) {
  if (!game || !game.inWorld) return;
  base.clear(); top.clear(); clicks.clear();
  ui.draw(base, top, clicks, performance.now());
  clicks.click(button, x, y, px, py);
}

// Touch: a tap is a left-click, a press held half a second a right-click (the menus); dragging cancels.
const LONG_PRESS_MILLIS = 450;
let lastTouch = 0, press = null;
canvas.addEventListener('pointerdown', e => {
  if (e.pointerType === 'mouse' || !game) return;
  lastTouch = performance.now();
  locate(e);
  const at = { x: mouse.x, y: mouse.y, px: mouse.px, py: mouse.py, clientX: e.clientX, clientY: e.clientY };
  press = { at, timer: setTimeout(() => {
    press = null;
    click(2, at.x, at.y, at.px, at.py);
  }, LONG_PRESS_MILLIS) };
});
// Dragging a finger scrolls whatever's under where it started, as the wheel would: a notch for each row's height
// moved (up scrolls on, like pushing the page up); it's no tap then.
canvas.addEventListener('pointermove', e => {
  if (e.pointerType === 'mouse' || !press) return;
  if (!press.dragging) {
    if (Math.hypot(e.clientX - press.at.clientX, e.clientY - press.at.clientY) <= 12) return;
    clearTimeout(press.timer);
    press.dragging = true;
    press.lastY = press.at.clientY;
  }
  const row = ch / (devicePixelRatio || 1);
  while (Math.abs(e.clientY - press.lastY) >= row) {
    const up = e.clientY < press.lastY;
    clicks.scroll(up ? 1 : -1, press.at.x, press.at.y);
    press.lastY += up ? -row : row;
  }
});
canvas.addEventListener('pointerup', e => {
  if (e.pointerType === 'mouse' || !press) return;
  lastTouch = performance.now();
  clearTimeout(press.timer);
  const { at, dragging } = press;
  press = null;
  if (!dragging) click(0, at.x, at.y, at.px, at.py);
  mouse.x = mouse.y = -1;   // no hover on a touch screen
});
canvas.addEventListener('pointercancel', () => { if (press) { clearTimeout(press.timer); press = null; } });
canvas.addEventListener('wheel', e => {
  if (!game) return;
  locate(e);
  e.preventDefault();
  clicks.scroll(Math.sign(e.deltaY), mouse.x, mouse.y);
}, { passive: false });

// ---- drawing -----------------------------------------------------------------------------------------------------
function frame(now) {
  requestAnimationFrame(frame);
  g.setTransform(1, 0, 0, 1, 0, 0);
  setLight(game && game.lightMode);
  g.fillStyle = background();
  g.fillRect(0, 0, canvas.width, canvas.height);
  if (!game || !game.inWorld) return;
  walk();
  syncChat();
  game.frame();
  game.glide.advance(now, game.tick);
  for (const o of game.others.values()) o.glide.advance(now, game.tick);

  base.clear(); top.clear(); clicks.clear();
  ui.cw = cw; ui.ch = ch;
  ui.hoverTile = ui.map.tileAt(mouse.px, mouse.py, game, cw, ch);
  ui.draw(base, top, clicks, now);
  // what's under the mouse lights up, if it's something to click
  const hovered = clicks.at(mouse.x, mouse.y);
  if (hovered && hovered.option) {
    const layer = hovered.layer > 0 ? top : base;
    for (let y = hovered.y; y < hovered.y + hovered.h; y++) {
      for (let x = hovered.x; x < hovered.x + hovered.w; x++) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
        const i = y * cols + x;
        if (layer.ch[i] == null) layer.ch[i] = ' ';
        layer.bg[i] = 'BLACK_BRIGHT';
      }
    }
  }
  canvas.style.cursor = hovered ? 'pointer' : 'default';
  if (hovered && hovered.popupIndex != null && ui.popup) ui.popup.index = hovered.popupIndex;

  g.translate(offsetX, offsetY);
  paint(base);
  ui.map.draw(g, game, cw, ch, fontPx, ui.popup || game.bankOpen ? null : ui.hoverTile, now);
  const scale = pixelScale(2 * cw, ch);
  for (const icon of ui.icons) drawSprite(g, icon.sprite, (icon.x + 1) * cw, icon.y * ch + ch / 2, scale);
  // thin progress bars (the phone's skill grid): a dark track, green as far as it's got
  const thin = Math.max(2, Math.round(ch / 7));
  for (const bar of ui.bars) {
    const x = bar.x * cw + 1, y = bar.y * ch + Math.round((ch - thin) / 2), w = bar.w * cw - 2;
    g.fillStyle = color('BLACK_BRIGHT');
    g.fillRect(x, y, w, thin);
    g.fillStyle = color('GREEN');
    g.fillRect(x, y, Math.round(w * bar.fraction), thin);
  }
  paint(top);
  for (const icon of ui.topIcons) drawSprite(g, icon.sprite, (icon.x + 1) * cw, icon.y * ch + ch / 2, scale);
}

function paint(layer) {
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  const n = layer.cols * layer.rows, w = layer.cols;
  for (let i = 0; i < n; i++) {
    const bg = layer.bg[i];
    if (bg && layer.ch[i] != null) {
      g.fillStyle = color(bg);
      g.fillRect((i % w) * cw, Math.floor(i / w) * ch, cw, ch);
    }
  }
  let font = null;
  for (let i = 0; i < n; i++) {
    const c = layer.ch[i];
    if (c == null || c === ' ') continue;
    const x = (i % w) * cw, y = Math.floor(i / w) * ch;
    const fg = color(layer.fg[i]);
    if (drawBox(g, c, x, y, cw, ch, fg)) continue;
    const wanted = (layer.bold[i] ? 'bold ' : '') + fontPx + 'px ' + FONT;
    if (wanted !== font) { g.font = wanted; font = wanted; }
    g.fillStyle = fg;
    g.fillText(c, x + cw / 2, y + ch / 2 + 1);
  }
}

requestAnimationFrame(frame);

// for poking at from the browser's console while developing
window.capebabe = { get game() { return game; }, get ui() { return ui; }, clicks };
