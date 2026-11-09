// Art loader. Every visual is looked up by SLOT NAME in art/art-config.json, so any picture can be
// swapped by replacing the file (or editing the path in the config) - no code changes.
// Missing art never breaks the game: the UI has a drawn fallback for everything.
import { getJSON } from './util.js';

const BASE = 'art/';
const SUITS = ['sun', 'bloom', 'moon', 'thorn'];

export const Art = {
  cfg: null,
  slots: {},
  ok: new Set(),
  cardOk: new Set(),
  async init() {
    try { this.cfg = await getJSON(BASE + 'art-config.json'); } catch (e) { this.cfg = { slots: {}, cards: {} }; }
    this.slots = this.cfg.slots || {};
  },
  async preload(onProgress) {
    const names = Object.keys(this.slots);
    const cards = [];
    const c = this.cfg.cards || {};
    if (c.useOverrides) {
      for (let s = 0; s < 4; s++) for (let r = 1; r <= 13; r++) cards.push([s * 13 + r - 1, this.cardPath(s * 13 + r - 1)]);
      cards.push(['back', BASE + (c.overrideBack || 'cards/back.webp')]);
    }
    const total = names.length + cards.length;
    let n = 0;
    const load = async (url) => { const img = new Image(); img.src = url; await img.decode(); };
    await Promise.all([
      ...names.map(async (s) => { try { await load(BASE + this.slots[s]); this.ok.add(s); } catch (e) { /* missing */ } onProgress && onProgress(++n / total); }),
      ...cards.map(async ([id, url]) => { try { await load(url); this.cardOk.add(id); } catch (e) { /* missing */ } onProgress && onProgress(++n / total); }),
    ]);
  },
  cardPath(id) {
    const pat = (this.cfg.cards && this.cfg.cards.overridePattern) || 'cards/{suit}_{rank}.webp';
    return BASE + pat.replace('{suit}', SUITS[Math.floor(id / 13)]).replace('{rank}', (id % 13) + 1);
  },
  cardOverride(id) { return this.cardOk.has(id) ? this.cardPath(id) : null; },
  backOverride() { return this.cardOk.has('back') ? BASE + ((this.cfg.cards && this.cfg.cards.overrideBack) || 'cards/back.webp') : null; },
  url(slot) { return this.slots[slot] ? BASE + this.slots[slot] : null; },
  has(slot) { return this.ok.has(slot); },
  /** Slot for a character mood; falls back to the neutral picture if that mood has no art yet. */
  charSlot(id, mood) {
    const pat = this.cfg.characterMoodSlotPattern || 'character.{id}.{mood}';
    const s = pat.replace('{id}', id).replace('{mood}', mood);
    return this.has(s) ? s : pat.replace('{id}', id).replace('{mood}', 'neutral');
  },
  charUrl(id, mood = 'neutral') { return this.url(this.charSlot(id, mood)); },
  /** Fill every [data-art="slot"] element under root (background-image, or src for <img>). */
  bind(root = document) {
    root.querySelectorAll('[data-art]').forEach((n) => {
      const u = this.url(n.dataset.art);
      if (!u || !this.has(n.dataset.art)) return;
      if (n.tagName === 'IMG') n.src = u; else n.style.backgroundImage = `url("${u}")`;
    });
  },
};
