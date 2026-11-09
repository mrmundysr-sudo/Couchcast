// Procedural card faces (SVG symbols + CSS), with an optional per-card art override from art-config.
import { FORCE_NAMES, is13, rankOf, suitOf, valueOf } from '../engine/cards.js';
import { Art } from '../assets.js';

const NS = 'http://www.w3.org/2000/svg';
const SUIT_KEYS = ['sun', 'bloom', 'moon', 'thorn'];
const f = (n) => +n.toFixed(2);

function rays(n, r1, r2, w, cx = 50, cy = 50, a0 = -Math.PI / 2) {
  let d = '';
  for (let k = 0; k < n; k++) {
    const a = a0 + (k * 2 * Math.PI) / n;
    const l = a - w;
    const r = a + w;
    d += `M${f(cx + r1 * Math.cos(l))} ${f(cy + r1 * Math.sin(l))}L${f(cx + r2 * Math.cos(a))} ${f(cy + r2 * Math.sin(a))}L${f(cx + r1 * Math.cos(r))} ${f(cy + r1 * Math.sin(r))}Z`;
  }
  return d;
}
function spiral() {
  let d = 'M50 50';
  for (let t = 0; t < 4.6 * Math.PI; t += 0.25) {
    const r = 3 + t * 3.2;
    d += `L${f(50 + r * Math.cos(t))} ${f(50 + r * Math.sin(t))}`;
  }
  return d;
}
function leaves() {
  let s = '';
  for (let k = 0; k < 5; k++) {
    const t = 1.6 + k * 2.3;
    const r = 3 + t * 3.2;
    const x = 50 + r * Math.cos(t);
    const y = 50 + r * Math.sin(t);
    s += `<ellipse cx="${f(x)}" cy="${f(y)}" rx="9" ry="4.2" transform="rotate(${f((t * 180) / Math.PI + 40)} ${f(x)} ${f(y)})" style="fill:var(--accent)"/>`;
  }
  return s;
}
function petals(n, rx, ry, cy, fill, op) {
  let s = '';
  for (let k = 0; k < n; k++) s += `<ellipse cx="50" cy="${cy}" rx="${rx}" ry="${ry}" transform="rotate(${(360 / n) * k} 50 50)" style="fill:${fill}" opacity="${op}"/>`;
  return s;
}
function thistle() {
  let spikes = '';
  for (let k = 0; k < 9; k++) {
    const a = (-170 + k * 20) * Math.PI / 180;
    const bx = 50 + 8 * Math.cos(a);
    const by = 44 + 8 * Math.sin(a);
    spikes += `<path d="M${f(bx - 3 * Math.sin(a))} ${f(by + 3 * Math.cos(a))}L${f(50 + 38 * Math.cos(a))} ${f(42 + 38 * Math.sin(a))}L${f(bx + 3 * Math.sin(a))} ${f(by - 3 * Math.cos(a))}Z" style="fill:var(--accent)"/>`;
  }
  return `${spikes}<path d="M26 50Q50 90 74 50Q50 38 26 50Z" style="fill:var(--ink)"/><path d="M50 74V97M50 84Q34 82 28 70M50 88Q66 86 72 74" style="stroke:var(--ink);stroke-width:4;fill:none;stroke-linecap:round"/>`;
}

const SYMBOLS = {
  sun: `<circle cx="50" cy="50" r="21" style="fill:var(--ink)"/><path d="${rays(12, 25, 48, 0.17)}" style="fill:var(--ink)"/><circle cx="50" cy="50" r="13" style="fill:var(--accent)"/><circle cx="50" cy="50" r="6.5" style="fill:var(--ink)"/>`,
  bloom: `${petals(8, 11, 22, 26, 'var(--ink)', 0.92)}${petals(8, 6, 12, 33, 'var(--accent)', 1)}<circle cx="50" cy="50" r="8" style="fill:var(--accent)"/><circle cx="50" cy="50" r="3.4" style="fill:var(--ink)"/>`,
  moon: `<path d="M64 8A44 44 0 1 0 64 92A35 35 0 1 1 64 8Z" style="fill:var(--ink)"/><path d="M78 30l2.6 6.4 6.8.5-5.2 4.4 1.7 6.7-5.9-3.7-5.9 3.7 1.7-6.7-5.2-4.4 6.8-.5Z" style="fill:var(--accent)"/>`,
  thorn: thistle(),
  flare: `<path d="${rays(16, 20, 49, 0.13)}" style="fill:var(--ink)"/><circle cx="50" cy="50" r="20" style="fill:var(--ink)"/><circle cx="50" cy="50" r="12" style="fill:var(--accent)"/><circle cx="50" cy="50" r="31" style="fill:none;stroke:var(--accent);stroke-width:2.4"/>`,
  overgrowth: `<path d="${spiral()}" style="fill:none;stroke:var(--ink);stroke-width:5;stroke-linecap:round;stroke-linejoin:round"/>${leaves()}<circle cx="50" cy="50" r="4.5" style="fill:var(--accent)"/>`,
  eclipse: `<path d="${rays(28, 33, 47, 0.06)}" style="fill:var(--accent)"/><circle cx="50" cy="50" r="30" style="fill:var(--ink)"/><circle cx="50" cy="50" r="32.5" style="fill:none;stroke:var(--accent);stroke-width:3"/><circle cx="58" cy="46" r="26" style="fill:#0b0a1c"/>`,
  bramble: `<path d="${rays(14, 25, 46, 0.12, 50, 50, 0)}" style="fill:var(--ink)"/><circle cx="50" cy="50" r="27" style="fill:none;stroke:var(--accent);stroke-width:6"/><circle cx="50" cy="50" r="12" style="fill:var(--ink)"/><circle cx="50" cy="50" r="5" style="fill:var(--accent)"/>`,
};
const FORCE_KEYS = ['flare', 'overgrowth', 'eclipse', 'bramble'];

/** Put the <symbol> sprite into the page (called once at boot). */
export function installSprite() {
  if (document.getElementById('t7-sprite')) return;
  const svg = document.createElementNS(NS, 'svg');
  svg.id = 't7-sprite';
  svg.setAttribute('width', '0');
  svg.setAttribute('height', '0');
  svg.style.position = 'absolute';
  svg.innerHTML = Object.entries(SYMBOLS).map(([k, v]) => `<symbol id="s-${k}" viewBox="0 0 100 100">${v}</symbol>`).join('');
  document.body.prepend(svg);
}

export const symbolFor = (id) => (is13(id) ? FORCE_KEYS[suitOf(id)] : SUIT_KEYS[suitOf(id)]);
const use = (key, cls = '') => `<svg class="${cls}" viewBox="0 0 100 100" aria-hidden="true"><use href="#s-${key}"/></svg>`;

function faceHTML(id) {
  const s = suitOf(id);
  const sym = symbolFor(id);
  const over = Art.cardOverride(id);
  if (over) return `<div class="face art"><img src="${over}" alt="" draggable="false"></div>`;
  if (is13(id)) {
    return `<div class="face force"><span class="corner tl"><b>13</b></span><span class="corner br"><b>13</b></span>${use(sym, 'emblem big')}<span class="fname">${FORCE_NAMES[s]}</span></div>`;
  }
  const v = valueOf(id);
  const label = (v < 0 ? '<i class="sg"></i>' : '') + Math.abs(v);
  return `<div class="face"><span class="corner tl"><b>${label}</b>${use(sym)}</span><span class="corner br"><b>${label}</b>${use(sym)}</span>${use(sym, 'emblem')}<span class="num">${label}</span><span class="rankmark">${rankOf(id)}</span></div>`;
}

/** Build a card element. opts.down shows the back. */
export function cardEl(id, { down = false } = {}) {
  const c = document.createElement('div');
  c.className = `card s${suitOf(id)}${is13(id) ? ' is13' : ''}${down ? ' down' : ''}`;
  c.dataset.id = id;
  const back = Art.backOverride();
  c.innerHTML = `<div class="inner">${faceHTML(id)}<div class="back${back ? ' art' : ''}">${back ? `<img src="${back}" alt="" draggable="false">` : '<i></i>'}</div></div>`;
  return c;
}

/** A face-down card with no identity (opponent hand, deck). */
export function backEl() {
  const c = document.createElement('div');
  c.className = 'card down blank';
  const back = Art.backOverride();
  c.innerHTML = `<div class="inner"><div class="face"></div><div class="back${back ? ' art' : ''}">${back ? `<img src="${back}" alt="" draggable="false">` : '<i></i>'}</div></div>`;
  return c;
}

export const iconSvg = use;
