// The live game screen: renders the engine state, handles input, animates events, runs the opponent.
import { cardLabel, FORCE_NAMES, is13, suitOf, topValue, valueOf } from '../engine/cards.js';
import { newGame, playCard, canPlay, previewEnd, endTurn, snapshot, restore, forceLegal, lockOptions, lockedPairs, lockPair, unlockPair, runStep } from '../engine/rules.js';
import { planTurn } from '../engine/ai.js';
import { playTargets } from '../engine/rules.js';
import { Art } from '../assets.js';
import { Sound, Haptics } from '../audio.js';
import { Store } from '../store.js';
import { $, $$, el, fmt, pick, signed, wait } from '../util.js';
import { backEl, cardEl } from './cardview.js';

const FORCE_FX = [
  { c: '#ff8a1f', c2: '#ffe28a', sfx: 'bustFlare' },
  { c: '#3fbf58', c2: '#c4f28f', sfx: 'bustOvergrowth' },
  { c: '#8c9cff', c2: '#ffffff', sfx: 'bustEclipse' },
  { c: '#b25be8', c2: '#ff9ad0', sfx: 'bustBramble' },
];

export function createGame({ text, chars, onGameOver, onOpenSheet }) {
  const root = $('#game');
  const slots = [];
  const meRow = $('#meRow');
  const foeRow = $('#foeRow');
  const handEl = $('#hand');
  const fxEl = $('#fx');
  const foeImg = $('#foeImg');
  const bubble = $('#bubble');
  const btnEnd = $('#btnEnd');
  const btnUndo = $('#btnUndo');
  const btnSeals = $('#btnSeals');
  const statusEl = $('#status');
  const T = text.game;

  let S = null;           // engine state
  let foe = null;         // character data
  let cfg = null;         // {opponent, difficulty, first}
  let token = 0;          // bumped whenever a game ends/aborts so old async loops stop
  let busy = true;
  let paused = false;
  let sel = null;         // selected hand card id
  let undo = [];
  let turnStartSnap = null;
  let handEls = new Map();
  let lastBanter = 0;
  let moodTimer = 0;
  let bubbleTimer = 0;
  let drag = null;
  let dims = { cw: 84, hcw: 92 };

  // ---------- build static pieces ----------
  for (let i = 0; i < 8; i++) {
    const s = el('div', 'slot');
    s.dataset.pile = i;
    s.addEventListener('click', () => onPile(i));
    slots[i] = s;
  }
  // human piles 0-3 (bottom), foe piles 4-7 (top)
  for (let i = 4; i < 8; i++) foeRow.appendChild(slots[i]);
  for (let i = 0; i < 4; i++) meRow.appendChild(slots[i]);

  $('#btnUndo').textContent = T.undo;
  $('#btnEnd').textContent = T.endTurn;
  btnSeals.textContent = T.seals;
  btnSeals.addEventListener('click', () => onSeals());
  btnEnd.addEventListener('click', () => onEnd());
  btnUndo.addEventListener('click', () => onUndo());
  $('#btnMenu').addEventListener('click', () => { Sound.sfx('tap'); onOpenSheet(); });
  $('#deck .lbl').textContent = T.deck;
  $('#spent .lbl').textContent = T.spent;

  const tick = async (ms) => { await wait(ms); while (paused) await wait(120); };
  const alive = (t) => t === token && S && !S.over;

  // ---------- layout ----------
  function resize() {
    const r = root.getBoundingClientRect();
    if (!r.width) return;
    const m = Math.round(r.width * 0.035);
    const g = Math.round(r.width * 0.022);
    const byW = (r.width - 2 * m - 3 * g) / 4;
    const byH = (r.height * 0.138) / 1.45;
    const cw = Math.floor(Math.min(byW, byH));
    dims = { cw, hcw: Math.floor(cw * 1.14) };
    root.style.setProperty('--cw', cw + 'px');
    root.style.setProperty('--hcw', dims.hcw + 'px');
    root.style.setProperty('--m', ((r.width - (4 * cw + 3 * g)) / 2) + 'px');
    if (S) layoutHand();
  }
  window.addEventListener('resize', resize);

  // ---------- rendering ----------
  function renderAll() {
    renderPiles();
    renderHand();
    renderFoeHand();
    renderCoins();
    renderMini();
  }

  function renderPiles() {
    for (let i = 0; i < 8; i++) renderPile(i);
    updateHints();
  }
  function renderPile(i) {
    const slot = slots[i];
    const pile = S.piles[i];
    slot.classList.toggle('sealed', !!S.lb[i]);
    slot.innerHTML = '';
    const pc = el('div', 'pc');
    if (!pile.length) {
      pc.appendChild(el('div', 'empty', '0'));
    } else {
      const under = Math.min(3, pile.length - 1);
      for (let k = under; k >= 1; k--) {
        const l = el('div', 'layer');
        l.style.transform = `translate(${(k % 2 ? 1 : -1) * 0.012}em, ${-0.018 * k}em) rotate(${(k % 2 ? 1.2 : -1.2) * k}deg)`;
        pc.appendChild(l);
      }
      pc.appendChild(cardEl(pile[pile.length - 1]));
    }
    slot.appendChild(pc);
    if (pile.length > 1) slot.appendChild(el('div', 'depth', String(pile.length - 1)));
    if (S.lb[i]) {
      const b = el('div', 'sealbadge');
      const slotName = S.lb[i] === 1 ? 'ui.coin7' : 'ui.coin11';
      if (Art.has(slotName)) b.style.backgroundImage = `url("${Art.url(slotName)}")`;
      else { b.classList.add('noart'); b.textContent = S.lb[i] === 1 ? '7' : '11'; }
      slot.appendChild(b);
    }
  }
  function slotRect(i) { return slots[i].getBoundingClientRect(); }

  function renderCoins() {
    for (const [p, id] of [[0, 'meCoins'], [1, 'foeCoins']]) {
      const box = $('#' + id);
      const had = new Set($$('.coin.lit', box).map((c) => c.dataset.bit));
      box.innerHTML = '';
      for (const [bit, name, lab] of [[1, 'ui.coin7', '7'], [2, 'ui.coin11', '11']]) {
        const c = el('div', 'coin');
        c.dataset.bit = bit;
        if (Art.has(name)) c.style.backgroundImage = `url("${Art.url(name)}")`; else { c.classList.add('noart'); c.textContent = lab; }
        if (S.done[p] & bit) { c.classList.add('lit'); if (had.has(String(bit))) c.style.animation = 'none'; }
        box.appendChild(c);
      }
    }
  }
  function renderMini() {
    $('#deck .lbl').textContent = `${T.deck} ${S.deck.length}`;
    $('#spent .lbl').textContent = `${T.spent} ${S.discard.length}`;
  }
  function renderFoeHand() {
    const box = $('#foeHand');
    const n = S.hands[1].length;
    box.innerHTML = '';
    const sp = 21;
    for (let k = 0; k < n; k++) {
      const c = backEl();
      const x = (k - (n - 1) / 2) * sp;
      c.style.transform = `translateX(${x}px) rotate(${(k - (n - 1) / 2) * 7}deg)`;
      c.style.left = `calc(50% - 11.5px)`;
      box.appendChild(c);
    }
  }
  function foeHandRect() {
    const cs = $$('#foeHand .card');
    const c = cs[cs.length - 1];
    if (c) return c.getBoundingClientRect();
    return $('#foeHand').getBoundingClientRect();
  }

  function renderHand(hiddenIds = []) {
    const ids = S.hands[0];
    for (const [id, e] of handEls) if (!ids.includes(id)) { e.remove(); handEls.delete(id); }
    for (const id of ids) {
      if (!handEls.has(id)) {
        const e = cardEl(id);
        e.classList.add('hcard');
        handEls.set(id, e);
        handEl.appendChild(e);
      }
      handEls.get(id).classList.toggle('hide', hiddenIds.includes(id));
    }
    layoutHand();
  }
  function layoutHand() {
    const ids = S.hands[0];
    const n = ids.length;
    const w = handEl.clientWidth || 360;
    const cw = dims.hcw;
    const spacing = n > 1 ? Math.min(cw * 1.02, (w - cw - 20) / (n - 1)) : 0;
    ids.forEach((id, k) => {
      const e = handEls.get(id);
      if (!e) return;
      const c = k - (n - 1) / 2;
      const selected = id === sel;
      const x = c * spacing;
      const y = Math.abs(c) * Math.abs(c) * 4 - (selected ? cw * 0.42 : 0);
      const r = selected ? 0 : c * 4.5;
      e.style.setProperty('--bt', `translate(${x}px,${y}px) rotate(${r}deg) scale(${selected ? 1.1 : 1})`);
      e.style.zIndex = selected ? 20 : k + 1;
      e.classList.toggle('sel', selected);
    });
    root.style.setProperty('--hcw', cw + 'px');
  }

  function updateHints() {
    for (let i = 0; i < 8; i++) {
      const slot = slots[i];
      slot.classList.remove('can', 'cover', 'bust', 'hover');
      const old = slot.querySelector('.hintTag');
      if (old) old.remove();
    }
    if (sel == null || busy || !S || S.over || S.turn !== 0) return;
    for (const t of playTargets(S, sel)) {
      const slot = slots[t.pile];
      const willBust = is13(sel) && (S.piles[t.pile].length > 0);
      slot.classList.add('can', willBust ? 'bust' : 'cover');
      const tag = el('div', 'hintTag', willBust ? (S.lb[t.pile] ? T.bust + '!' : T.bust) : (is13(sel) && !S.piles[t.pile].length ? T.base : T.cover));
      slot.appendChild(tag);
    }
  }
  function setStatus(t, pulse = false) {
    statusEl.textContent = t;
    statusEl.classList.toggle('pulse', pulse);
  }
  function toast(msg, bad = false, ms = 1700) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.toggle('bad', bad);
    t.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.remove('show'), ms);
  }

  // ---------- opponent presence ----------
  function setMood(m, ms = 2600) {
    foeImg.src = Art.charUrl(foe.id, m) || foeImg.src;
    foeImg.className = m && m !== 'neutral' ? 'm-' + m : '';
    clearTimeout(moodTimer);
    if (ms && m !== 'defeated') moodTimer = setTimeout(() => { foeImg.src = Art.charUrl(foe.id, 'neutral'); foeImg.className = ''; }, ms);
  }
  function say(line, { ms = 3000, force = false } = {}) {
    if (!line || (!Store.get('banter') && !force)) return;
    bubble.textContent = line;
    bubble.classList.add('show');
    clearTimeout(bubbleTimer);
    bubbleTimer = setTimeout(() => bubble.classList.remove('show'), ms + line.length * 22);
    lastBanter = Date.now();
  }
  function banter(kind, { chance = 1, mood = null, force = false, cooldown = 3500 } = {}) {
    if (mood) setMood(mood);
    if (!force && (Date.now() - lastBanter < cooldown || Math.random() > chance)) return;
    const lines = foe.banter[kind];
    if (lines && lines.length) say(pick(lines), { force });
  }

  // ---------- effects ----------
  function fly(id, from, to, { down = false, flip = false, ms = 440 } = {}) {
    const c = cardEl(id, { down });
    c.style.cssText = `--cw:${from.width}px;left:${from.left}px;top:${from.top}px;z-index:5`;
    fxEl.appendChild(c);
    const dx = to.left + (to.width - from.width) / 2 - from.left;
    const dy = to.top + (to.height - from.height) / 2 - from.top;
    const sc = to.width / from.width;
    const rot = (Math.random() * 10 - 5).toFixed(1);
    const a = c.animate([
      { transform: 'translate(0,0) scale(1) rotate(0deg)' },
      { transform: `translate(${dx * 0.55}px,${dy * 0.55 - 46}px) scale(${((1 + sc) / 2) * 1.16}) rotate(${rot}deg)`, offset: 0.55 },
      { transform: `translate(${dx}px,${dy}px) scale(${sc}) rotate(0deg)` },
    ], { duration: ms, easing: 'cubic-bezier(.25,.7,.3,1)', fill: 'forwards' });
    if (flip) {
      c.classList.add('down');
      requestAnimationFrame(() => requestAnimationFrame(() => c.classList.remove('down')));
    }
    return a.finished.then(() => c).catch(() => c);
  }
  function bustFx(i, force) {
    const fxs = FORCE_FX[force];
    const slot = slots[i];
    const b = el('div', `bfx f${force}`);
    b.style.cssText = `--c:${fxs.c};--c2:${fxs.c2};font-size:${dims.cw}px`;
    b.appendChild(el('div', 'flash'));
    b.appendChild(el('div', 'ring'));
    for (let k = 0; k < 18; k++) {
      const a = (k / 18) * Math.PI * 2 + Math.random() * 0.3;
      const d = dims.cw * (0.55 + Math.random() * 0.75);
      const s = el('div', 'spark');
      s.style.setProperty('--dx', Math.cos(a) * d + 'px');
      s.style.setProperty('--dy', Math.sin(a) * d + 'px');
      s.style.setProperty('--rot', Math.random() * 360 + 'deg');
      s.style.animationDelay = Math.random() * 0.08 + 's';
      b.appendChild(s);
    }
    slot.appendChild(b);
    root.classList.add('shake');
    setTimeout(() => root.classList.remove('shake'), 520);
    setTimeout(() => b.remove(), 1100);
    Sound.sfx(fxs.sfx);
    Haptics.pulse([30, 40, 70]);
  }
  function lightSlots(idx, cls) {
    for (const i of idx) { slots[i].classList.add(cls); setTimeout(() => slots[i].classList.remove(cls), 1000); }
  }

  /** Play out the visible side of a card play (state is already updated). */
  async function animatePlay(events, from, who, t) {
    const place = events.find((e) => e.t === 'place');
    const bust = events.find((e) => e.t === 'bust');
    const unseal = events.find((e) => e.t === 'unseal');
    Sound.sfx(who === 1 ? 'swoosh' : 'place', { gain: 0.7 });
    const clone = await fly(place.card, from, slotRect(place.pile), { flip: who === 1 });
    if (t !== token) { clone.remove(); return; }
    else if (!bust) { Sound.sfx('place'); Haptics.pulse(10); }
    if (bust) {
      bustFx(place.pile, bust.force);
      await wait(380);
      if (unseal) { Sound.sfx('unseal', { delay: 0.1 }); slots[place.pile].classList.add('cracked'); }
    }
    clone.remove();
    // update everything the play changed
    renderPile(place.pile);
    if (unseal) for (const j of unseal.piles) renderPile(j);
    renderCoins();
    renderMini();
    renderFoeHand();
    updateHints();
    slots[place.pile].classList.add('land');
    setTimeout(() => slots[place.pile].classList.remove('land', 'cracked'), 500);
    if (unseal) {
      toast(T.cracked);
      Haptics.pulse([40, 30, 40]);
    }
  }

  // ---------- turn flow ----------
  async function begin(conf) {
    cfg = conf;
    const t = ++token;
    foe = chars[conf.opponent];
    $('#foeName').textContent = foe.name;
    $('#foeTitle').textContent = foe.title;
    sel = null; undo = []; handEls.forEach((e) => e.remove()); handEls.clear();
    const first = conf.first === 'me' ? 0 : conf.first === 'foe' ? 1 : 'random';
    S = newGame({ rng: Math.random, first, lockBreak: Store.get('lockBreak') });
    window.__t7 = { get S() { return S; }, renderAll, toast };
    busy = true;
    resize();
    setMood('neutral', 0);
    bubble.classList.remove('show');
    foeImg.src = Art.charUrl(foe.id, 'neutral') || '';
    renderPiles();
    renderCoins();
    renderMini();
    renderFoeHand();
    renderHand();
    setStatus('');
    // deal: human hand flies from the deck
    await tick(500);
    if (t !== token) return;
    const ids = S.hands[0].slice();
    renderHand(ids);
    Sound.sfx('shuffle');
    await dealToHand(ids, t);
    banter('intro', { force: true });
    if (S.openSeals.length) {
      await tick(600);
      for (const o of S.openSeals) { lightSlots(o.piles, 'sealing'); Sound.sfx('seal', { delay: o.player * 0.18 }); }
      toast(T['seal' + S.openSeals[0].value] || T.sealed);
    }
    await tick(1500);
    if (t !== token) return;
    nextTurn(t);
  }
  async function dealToHand(ids, t) {
    const deck = $('#deck').getBoundingClientRect();
    for (const id of ids) {
      if (t !== token) return;
      const e = handEls.get(id);
      if (!e) continue;
      const to = e.getBoundingClientRect();
      Sound.sfx('draw', { gain: 0.8 });
      const from = { left: deck.left, top: deck.top, width: deck.width, height: deck.height };
      const c = await fly(id, from, to, { flip: true, ms: 380 });
      e.classList.remove('hide');
      c.remove();
      await wait(40);
    }
  }

  function nextTurn(t) {
    if (t !== token || S.over) return;
    if (S.turn === 0) humanTurn(t); else foeTurn(t);
  }

  function humanTurn(t) {
    busy = false;
    undo = [];
    turnStartSnap = snapshot(S);
    sel = null;
    updateHints();
    Sound.sfx('yourTurn');
    refreshControls();
    if (S.hands[0].length === 1) select(S.hands[0][0]);
  }
  function refreshControls() {
    const n = S.hands[0].length;
    const mine = S.turn === 0 && !busy && !S.over;
    btnUndo.disabled = !mine || !undo.length;
    btnEnd.disabled = !mine || n > 0;
    btnEnd.classList.toggle('ready', mine && n === 0);
    const canSeal = mine && (lockOptions(S, 0).length > 0 || lockedPairs(S, 0).length > 0);
    btnSeals.disabled = !canSeal;
    btnSeals.classList.toggle('ready', mine && lockOptions(S, 0).length > 0);
    if (S.over) setStatus('');
    else if (S.turn !== 0) setStatus(fmt(T.foeThinks, { name: foe.short }), true);
    else if (busy) setStatus(T.yourMove);
    else if (S.finalTurn && n > 0) setStatus(fmt(T.finalCastN, { n }), false);
    else if (S.finalTurn && S.done[0] !== 3) setStatus(T.finalReady, true);
    else if (S.finalTurn) setStatus(S.done[1] === 3 ? T.readyDouble : T.readyWin, true);
    else if (n > 1) setStatus(fmt(T.castN, { n }), false);
    else if (n === 1) setStatus(T.castOne);
    else setStatus(S.done[0] === 3 ? T.readyWin : T.readyEnd, true);
  }

  function select(id) {
    if (sel === id) { sel = null; Sound.sfx('deselect'); } else { sel = id; Sound.sfx('select'); Haptics.pulse(6); }
    layoutHand();
    updateHints();
  }

  function onPile(i) {
    if (busy || S.turn !== 0 || S.over) return;
    if (sel == null) { Sound.sfx('error', { gain: 0.5 }); toast(T.pickCard, false, 1100); return; }
    tryPlay(sel, i);
  }
  function tryPlay(card, pile) {
    const m = 'cover';
    if (!canPlay(S, card, pile, m)) {
      Sound.sfx('error');
      Haptics.pulse([20, 30, 20]);
      slots[pile].classList.add('err');
      setTimeout(() => slots[pile].classList.remove('err'), 400);
      layoutHand();
      return false;
    }
    doHumanPlay(card, pile, m);
    return true;
  }
  async function doHumanPlay(card, pile, m) {
    const t = token;
    busy = true;
    undo.push(snapshot(S));
    const e = handEls.get(card);
    const from = e.getBoundingClientRect();
    sel = null;
    e.style.visibility = 'hidden';
    updateHints();
    const res = playCard(S, card, pile, m);
    renderHand();
    await animatePlay(res.events, from, 0, t);
    if (t !== token) return;
    reactToEvents(res.events, 0);
    busy = false;
    refreshControls();
    if (S.hands[0].length === 1) select(S.hands[0][0]);
  }
  function onUndo() {
    if (busy || !undo.length || S.turn !== 0) return;
    Sound.sfx('pop');
    restore(S, undo.pop());
    sel = null;
    handEls.forEach((e) => e.remove());
    handEls.clear();
    renderAll();
    refreshControls();
  }

  // drag & drop from the hand
  handEl.addEventListener('pointerdown', (e) => {
    const c = e.target.closest('.hcard');
    if (!c || busy || S.turn !== 0) return;
    drag = { c, id: +c.dataset.id, x0: e.clientX, y0: e.clientY, moved: false };
    try { c.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
  });
  handEl.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const dx = e.clientX - drag.x0;
    const dy = e.clientY - drag.y0;
    if (!drag.moved && Math.hypot(dx, dy) > 9) {
      drag.moved = true;
      if (sel !== drag.id) { sel = drag.id; Sound.sfx('select'); }
      layoutHand();
      updateHints();
      drag.c.classList.add('dragging');
    }
    if (drag.moved) {
      drag.c.style.setProperty('--dx', dx + 'px');
      drag.c.style.setProperty('--dy', dy + 'px');
      const over = pileAt(e.clientX, e.clientY);
      slots.forEach((s, i) => s.classList.toggle('hover', i === over && s.classList.contains('can')));
    }
  });
  const endDrag = (e) => {
    if (!drag) return;
    const d = drag;
    drag = null;
    d.c.classList.remove('dragging');
    d.c.style.removeProperty('--dx');
    d.c.style.removeProperty('--dy');
    slots.forEach((s) => s.classList.remove('hover'));
    if (!d.moved) { if (e.type === 'pointerup') select(d.id); return; }
    const over = e.type === 'pointerup' ? pileAt(e.clientX, e.clientY) : -1;
    if (over >= 0) tryPlay(d.id, over);
    else layoutHand();
  };
  handEl.addEventListener('pointerup', endDrag);
  handEl.addEventListener('pointercancel', endDrag);
  function pileAt(x, y) {
    for (const n of document.elementsFromPoint(x, y)) {
      const s = n.closest && n.closest('.slot');
      if (s) return +s.dataset.pile;
    }
    // be forgiving: nearest slot within half a card
    let best = -1; let bd = dims.cw * 0.7;
    slots.forEach((s, i) => {
      const r = s.getBoundingClientRect();
      const d = Math.hypot(x - (r.left + r.width / 2), y - (r.top + r.height / 2));
      if (d < bd) { bd = d; best = i; }
    });
    return best;
  }

  // ---------- ending a turn ----------
  async function onEnd() {
    if (busy || S.turn !== 0 || S.over) return;
    const pv = previewEnd(S);
    if (!pv.legal) {
      Sound.sfx('error');
      Haptics.pulse([25, 30, 25]);
      toast(T.illegal[pv.reason] || T.illegal['cards-left'], true, 2300);
      if (pv.reason === 'force-on-top' || pv.reason === 'empty-pile') maybeOfferRelease();
      return;
    }
    const t = token;
    busy = true;
    refreshControls();
    Sound.sfx('tap');
    const res = endTurn(S, { rng: Math.random });
    await processEnd(res.events, 0, t);
    if (t !== token) return;
    if (!S.over) nextTurn(t);
  }

  /** Rare: if no ending is reachable from this turn's start, offer to release the stray Forces. */
  function maybeOfferRelease() {
    const probe = JSON.parse(JSON.stringify(turnStartSnap));
    const plan = planTurn(probe, 0, { wOpp: 0.5, oppSeal: 10, ownSeal: 10, forceRefill: 0.1 }, 'beginner', Math.random);
    if (plan.legal) return;
    const modal = $('#modal');
    modal.innerHTML = `<div class="panel"><h3>${T.stuck}</h3><p class="sub">${T.stuckHint}</p><button class="btn gold choice" id="relBtn">${T.stuck}</button><button class="btn ghost choice" id="relNo">${T.undo}</button></div>`;
    modal.classList.add('open');
    $('#relNo').onclick = () => modal.classList.remove('open');
    $('#relBtn').onclick = async () => {
      modal.classList.remove('open');
      const t = token;
      busy = true;
      forceLegal(S);
      renderPiles();
      const res = endTurn(S, { rng: Math.random, force: true });
      await processEnd(res.events, 0, t);
      if (t === token && !S.over) nextTurn(t);
    };
  }

  // ---------- locking and unlocking (your own turn, as often as you like) ----------
  function onSeals() {
    if (busy || S.turn !== 0 || S.over) return;
    Sound.sfx('tap');
    const lab = (p) => signed(topValue(S.piles[p]));
    const locks = lockOptions(S, 0);
    const unlocks = lockedPairs(S, 0);
    const modal = $('#modal');
    const rows = [];
    locks.forEach((o, k) => rows.push(`<button class="btn gold choice" data-act="lock" data-k="${k}">${fmt(T.lockOf, { v: o.value, a: lab(o.piles[0]), b: lab(o.piles[1]) })}</button>`));
    unlocks.forEach((u, k) => rows.push(`<button class="btn ghost choice" data-act="unlock" data-k="${k}">${fmt(T.unlockOf, { v: u.value })}</button>`));
    if (!rows.length) rows.push(`<p class="sub">${T.noSeals}</p>`);
    modal.innerHTML = `<div class="panel"><h3>${T.sealsTitle}</h3>${rows.join('')}<button class="btn ghost choice" data-act="close">${text.settings.resume}</button></div>`;
    modal.classList.add('open');
    const clear = () => slots.forEach((sl) => sl.classList.remove('can', 'cover'));
    const glow = (idx) => { clear(); idx.forEach((i) => slots[i].classList.add('can', 'cover')); };
    $$('button', modal).forEach((b) => {
      const o = b.dataset.act === 'lock' ? locks[+b.dataset.k] : b.dataset.act === 'unlock' ? unlocks[+b.dataset.k] : null;
      if (o) { b.addEventListener('pointerenter', () => glow(o.piles)); b.addEventListener('focus', () => glow(o.piles)); b.addEventListener('pointerleave', clear); }
      b.addEventListener('click', () => {
        clear();
        modal.classList.remove('open');
        if (b.dataset.act === 'lock') doHumanLock(o);
        else if (b.dataset.act === 'unlock') doHumanUnlock(o);
      });
    });
  }
  function doHumanLock(o) {
    undo.push(snapshot(S));
    const res = lockPair(S, o.piles, o.bit);
    if (!res.ok) { undo.pop(); return; }
    animateLock(res.events[0], 0);
    reactToEvents(res.events, 0);
    sel = null;
    renderPiles();
    renderCoins();
    refreshControls();
  }
  function doHumanUnlock(u) {
    undo.push(snapshot(S));
    const res = unlockPair(S, u.bit);
    if (!res.ok) { undo.pop(); return; }
    animateLock(res.events[0], 0);
    renderPiles();
    renderCoins();
    refreshControls();
  }
  /** The visible side of a lock or an unlock (state is already updated). */
  function animateLock(ev, who) {
    renderPiles();
    renderCoins();
    if (ev.t === 'seal') {
      lightSlots(ev.piles, 'sealing');
      Sound.sfx('seal');
      Haptics.pulse([20, 30, 60]);
      toast(T['seal' + ev.value]);
    } else {
      lightSlots(ev.piles, 'cracked');
      Sound.sfx('unseal');
      Haptics.pulse([20, 20]);
      toast(T.unlocked);
    }
  }

  async function processEnd(events, who, t) {
    for (const ev of events) {
      if (t !== token) return;
      if (ev.t === 'seal') {
        renderPiles();
        renderCoins();
        lightSlots(ev.piles, 'sealing');
        Sound.sfx('seal');
        Haptics.pulse([20, 30, 60]);
        toast(T['seal' + ev.value]);
        reactToEvents([ev], who);
        await tick(950);
      } else if (ev.t === 'reshuffle') {
        Sound.sfx('shuffle');
        toast(T.reshuffled);
        banter('reshuffle', { chance: 0.6 });
        renderMini();
        await tick(700);
      } else if (ev.t === 'draw') {
        if (ev.player === 0) {
          const ids = ev.cards.slice();
          renderHand(ids);
          renderMini();
          await dealToHand(ids, t);
        } else {
          renderFoeHand();
          renderMini();
          Sound.sfx('draw', { gain: 0.6 });
          await tick(250);
        }
      } else if (ev.t === 'finalTurn') {
        renderCoins();
        toast(ev.player === 0 ? fmt(T.finalFoe, { name: foe.short }) : fmt(T.finalFoeYours, { name: foe.short }), false, 2600);
        await tick(1200);
      } else if (ev.t === 'winCancelled') {
        renderAll();
        Sound.sfx('unseal');
        toast(T.winCancelled, false, 2400);
        await tick(1000);
      } else if (ev.t === 'gameover') {
        await finish(ev, t);
        return;
      }
    }
    renderAll();
  }

  function reactToEvents(events, who) {
    for (const ev of events) {
      if (ev.t === 'bust') {
        if (who === 1) {
          if (ev.owner === 0) banter('oppBustYou', { mood: 'smug', cooldown: 1500 });
          else banter('oppPlay', { chance: 0.2 });
        } else if (ev.owner === 1) banter('youBustOpp', { mood: 'angry', cooldown: 1500 });
      } else if (ev.t === 'unseal') {
        if (who === 1) banter('oppBreak', { mood: 'smug', force: true });
        else banter('youBreak', { mood: 'angry', force: true });
      } else if (ev.t === 'seal') {
        if (who === 1) banter('oppSeal', { mood: 'smug', force: true });
        else banter('youSeal', { mood: 'worried', force: true });
      } else if (ev.t === 'place' && who === 1) {
        banter('oppPlay', { chance: 0.12, cooldown: 6000 });
      }
    }
  }

  async function foeTurn(t) {
    busy = true;
    refreshControls();
    Sound.sfx('foeTurn');
    banter('thinking', { chance: 0.45 });
    const diff = cfg.difficulty;
    await tick(diff === 'beginner' ? 700 : 1000 + Math.random() * 500);
    if (t !== token) return;
    // a little mood from the scoreboard
    const lead = S.done[1] - S.done[0];
    if (lead > 0 && Math.random() < 0.3) banter('oppWinning', { mood: 'smug' });
    else if (lead < 0 && Math.random() < 0.3) banter('oppLosing', { mood: 'worried' });
    const res = planTurn(S, 1, foe.persona, diff, Math.random);
    for (const m of res.plan) {
      if (t !== token) return;
      if (m.type === 'play') {
        const from = foeHandRect();
        const r = playCard(S, m.card, m.pile, m.mode);
        if (!r.ok) break;
        await animatePlay(r.events, from, 1, t);
        if (t !== token) return;
        reactToEvents(r.events, 1);
        await tick(380);
      } else {
        const r = runStep(S, m);
        if (!r.ok) continue;
        animateLock(r.events[0], 1);
        reactToEvents(r.events, 1);
        await tick(950);
      }
    }
    if (t !== token) return;
    if (S.hands[1].length) emergencyFinish();
    if (!res.legal) forceLegal(S);
    renderPiles();
    let end = endTurn(S, { rng: Math.random });
    if (!end.ok) end = endTurn(S, { rng: Math.random, force: true });
    await processEnd(end.events, 1, t);
    if (t !== token) return;
    if (!S.over) nextTurn(t);
  }
  /** Safety net: dump any cards the plan could not place (never expected, but a stuck game is worse). */
  function emergencyFinish() {
    while (S.hands[1].length) {
      const c = S.hands[1][0];
      const tg = playTargets(S, c)[0];
      if (tg) playCard(S, c, tg.pile, tg.modes[0]); else S.discard.push(S.hands[1].shift());
    }
  }

  async function finish(ev, t) {
    busy = true;
    refreshControls();
    const winner = ev.winner;
    if (winner === 0) { setMood('defeated', 0); banter('lose', { force: true }); }
    else if (winner === 1) { setMood('happy', 0); banter('win', { force: true }); }
    else banter('draw', { force: true });
    renderAll();
    if (ev.reason === 'seals' || ev.reason === 'double') lightSlots([0, 1, 2, 3, 4, 5, 6, 7].filter((i) => S.lb[i]), 'sealing');
    await tick(2300);
    if (t !== token) return;
    onGameOver({
      winner, reason: ev.reason, foeId: foe.id, done: S.done.slice(),
      stats: { turns: S.turns[0], seals: S.stats.seals[0], busts: S.stats.busts[0] },
      line: winner === 0 ? pick(foe.banter.lose) : winner === 1 ? pick(foe.banter.win) : pick(foe.banter.draw),
    });
  }

  function abort() { token++; busy = true; root.classList.remove('shake'); fxEl.innerHTML = ''; $('#modal').classList.remove('open'); }
  function setPaused(p) { paused = p; }

  return { begin, abort, setPaused, resize, get state() { return S; } };
}
