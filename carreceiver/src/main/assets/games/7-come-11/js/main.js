// Boot, screen routing, menu, swipe-up charms sheet, rules, results.
import { Art } from './assets.js';
import { Sound, Haptics } from './audio.js';
import { Store } from './store.js';
import { $, $$, el, fmt, getJSON, wait } from './util.js';
import { installSprite, iconSvg } from './ui/cardview.js';
import { createGame } from './ui/game.js';
import { newMatch, recordGame, pointsFor, TARGETS } from './engine/match.js';

const screens = { splash: $('#splash'), menu: $('#menu'), game: $('#game'), result: $('#result') };
let text; let chars; let game;
let current = 'splash';
let match = null; // the match in progress: a race to 7 or 11 points over several games
let sheetOpen = false;
let sheetContext = 'menu';
const sel = { opponent: Store.get('opponent'), difficulty: Store.get('difficulty'), first: Store.get('first'), target: Store.get('target') };

function show(name) {
  current = name;
  for (const [k, s] of Object.entries(screens)) s.classList.toggle('active', k === name);
  if (name === 'menu' || name === 'splash') Sound.music('menu');
  else if (name === 'game') Sound.music('game');
  else Sound.music(null);
}

// ---------------------------------------------------------------- boot
async function boot() {
  installSprite();
  $('#rotate').textContent = '';
  try {
    [text, chars] = await Promise.all([getJSON('data/text.json'), getJSON('data/characters.json')]);
  } catch (e) {
    document.body.textContent = 'Could not load game data.';
    return;
  }
  $('#rotate').textContent = text.rotate;
  $('#loadtext').textContent = text.splash.loading;
  $('#touchBegin').textContent = text.splash.tap;
  await Art.init();
  Art.bind(document);                         // splash art first so the loader isn't over a black screen
  await Sound.init();
  Haptics.on = Store.get('vibration');
  Sound.vol.sfx = Store.get('sfx');
  Sound.vol.music = Store.get('music');
  Sound.applyVol();

  let a = 0; let b = 0;
  const bar = $('#loadbar');
  const upd = () => { bar.style.width = Math.round(((a + b) / 2) * 100) + '%'; };
  await Promise.all([
    Art.preload((p) => { a = p; upd(); }),
    Sound.preload((p) => { b = p; upd(); }),
  ]);
  Art.bind(document);
  $('#loadtext').textContent = text.splash.ready;
  await wait(250);
  $('#loader').style.opacity = 0;
  const btn = $('#touchBegin');
  btn.hidden = false;
  buildMenu();
  buildSheet();
  game = createGame({
    text, chars,
    onGameOver: showResult,
    onOpenSheet: () => openSheet('game'),
  });
  buildResult();
  const go = () => {
    Sound.unlock();
    Sound.sfx('tap');
    Sound.music('menu');
    show('menu');
  };
  btn.addEventListener('click', go, { once: true });
  window.__t7ready = true;
}

// unlock audio on the very first touch anywhere (browsers require a gesture)
window.addEventListener('pointerdown', () => Sound.unlock(), { passive: true });
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { Sound.suspend(); game && game.setPaused(true); }
  else { Sound.resume(); game && !sheetOpen && game.setPaused(false); }
});

// ---------------------------------------------------------------- menu
function buildMenu() {
  const M = text.menu;
  const m = $('#menu');
  m.innerHTML = '';
  const bg = el('div', 'bg');
  bg.dataset.art = 'table.background';
  bg.style.filter = 'blur(3px) brightness(.55) saturate(1.1)';
  bg.style.transform = 'scale(1.06)';
  m.appendChild(bg);
  m.appendChild(el('div', 'vignette'));
  const inner = el('div', 'menu-inner');
  inner.innerHTML = `
    <div class="m-title"><h1>Touch 7 <br>Come 11</h1><p>${M.title}</p></div>
    <div class="m-label">${M.opponent}</div>
    <div class="holder opp" id="oppHolder"></div>
    <div class="m-blurb" id="oppBlurb"></div>
    <div class="m-label">${M.difficulty}</div>
    <div class="holder diff" id="diffHolder"></div>
    <div class="m-first"><div class="m-label">${M.target}</div><div class="seg" id="targetSeg"></div></div>
    <div class="m-blurb" id="targetNote"></div>
    <div class="m-first"><div class="m-label">${M.first}</div><div class="seg" id="firstSeg"></div></div>
    <div class="m-actions">
      <button class="btn gold big" id="btnBegin">${M.begin}</button>
      <div class="m-links"><button class="btn ghost" id="btnHow">${M.how}</button></div>
      <div class="m-rec" id="rec"></div>
    </div>`;
  m.appendChild(inner);
  // outside the scrolling column, so the swipe-up grip stays pinned to the bottom of the screen
  m.insertAdjacentHTML('beforeend', '<div class="handle" id="menuHandle"><i></i></div>');
  Art.bind(m);

  const oh = $('#oppHolder');
  for (const id of ['witch', 'wizard']) {
    const c = chars[id];
    const b = el('button', 'ocard');
    b.dataset.id = id;
    b.innerHTML = `<img alt="" src="${Art.charUrl(id, 'neutral') || ''}" draggable="false"><div class="nm"><b>${c.name}</b><small>${c.title}</small></div>`;
    b.addEventListener('click', () => { Sound.sfx('select'); sel.opponent = id; Store.set('opponent', id); refreshMenu(); });
    oh.appendChild(b);
  }
  const dh = $('#diffHolder');
  ['beginner', 'experienced', 'professional'].forEach((d, k) => {
    const t = text.difficulty[d];
    const b = el('button', `dcard d${k}`);
    b.dataset.id = d;
    const sym = ['sun', 'moon', 'thorn'][k];
    b.innerHTML = `${iconSvg(sym)}<b>${t.name}</b><small>${t.sub}</small><span class="pips">${[0, 1, 2].map((p) => `<i class="${p <= k ? 'f' : ''}"></i>`).join('')}</span>`;
    b.addEventListener('click', () => { Sound.sfx('select'); sel.difficulty = d; Store.set('difficulty', d); refreshMenu(); });
    dh.appendChild(b);
  });
  const fs = $('#firstSeg');
  for (const f of ['me', 'fate', 'foe']) {
    const b = el('button', '', text.first[f]);
    b.dataset.id = f;
    b.addEventListener('click', () => { Sound.sfx('tap'); sel.first = f; Store.set('first', f); refreshMenu(); });
    fs.appendChild(b);
  }
  const ts = $('#targetSeg');
  for (const n of TARGETS) {
    const b = el('button', '', String(n));
    b.dataset.id = String(n);
    b.addEventListener('click', () => { Sound.sfx('tap'); sel.target = n; Store.set('target', n); refreshMenu(); });
    ts.appendChild(b);
  }
  $('#btnBegin').addEventListener('click', startMatch);
  $('#btnHow').addEventListener('click', () => { Sound.sfx('tap'); openRules(); });
  attachSwipe($('#menuHandle'), () => openSheet('menu'));
  refreshMenu();
}
function refreshMenu() {
  $$('#oppHolder .ocard').forEach((b) => b.classList.toggle('on', b.dataset.id === sel.opponent));
  $$('#diffHolder .dcard').forEach((b) => b.classList.toggle('on', b.dataset.id === sel.difficulty));
  $$('#firstSeg button').forEach((b) => b.classList.toggle('on', b.dataset.id === sel.first));
  $$('#targetSeg button').forEach((b) => b.classList.toggle('on', +b.dataset.id === sel.target));
  $('#targetNote').textContent = fmt(text.menu.targetNote, { n: sel.target });
  const c = chars[sel.opponent];
  $('#oppBlurb').textContent = `“${c.tagline}”`;
  const r = Store.getRecord(sel.opponent, sel.difficulty);
  $('#rec').textContent = `${text.menu.record}: ${r.w} ${text.menu.wins} · ${r.l} ${text.menu.losses}${r.d ? ` · ${r.d} ${text.menu.draws}` : ''}`;
}

// A new match: you choose who opens the first round (Fate = coin flip); after that the opener alternates.
async function startMatch() {
  const first = sel.first === 'me' ? 0 : sel.first === 'foe' ? 1 : (Math.random() < 0.5 ? 0 : 1);
  match = newMatch({ target: sel.target, first });
  await beginRound();
}

async function beginRound() {
  Sound.unlock();
  Sound.sfx('tap');
  Haptics.pulse(15);
  if (!Store.get('seenRules')) { Store.set('seenRules', true); await openRules(true); }
  paintScoreChip();
  show('game');
  game.setPaused(false);
  game.begin({ opponent: sel.opponent, difficulty: sel.difficulty, first: match.nextFirst === 0 ? 'me' : 'foe' });
}

function paintScoreChip() {
  const chip = $('#matchChip');
  chip.textContent = match ? fmt(text.game.scoreChip, { a: match.scores[0], b: match.scores[1] }) : '';
  chip.title = match ? fmt(text.result.toTarget, { n: match.target }) : '';
}

// ---------------------------------------------------------------- result
function buildResult() {
  $('#btnToMenu').textContent = text.result.menu;
  const ab = $('#btnAgain');
  if (Art.has('ui.playAgain')) { ab.style.backgroundImage = `url("${Art.url('ui.playAgain')}")`; ab.setAttribute('aria-label', text.result.playAgain); }
  else { ab.classList.add('noart'); ab.textContent = text.result.playAgain; }
  ab.dataset.hasArt = Art.has('ui.playAgain') ? '1' : '';
  ab.addEventListener('click', () => { Sound.sfx('tap'); if (match && !match.over) beginRound(); else startMatch(); });
  $('#btnToMenu').addEventListener('click', () => { Sound.sfx('tap'); match = null; refreshMenu(); show('menu'); });
}
// "Play Again" art only fits a fresh match; between rounds the button is a plain "Next Round".
function setAgainButton(midMatch) {
  const ab = $('#btnAgain');
  const useArt = ab.dataset.hasArt === '1' && !midMatch;
  ab.classList.toggle('noart', !useArt);
  ab.style.backgroundImage = useArt ? `url("${Art.url('ui.playAgain')}")` : '';
  ab.textContent = useArt ? '' : midMatch ? text.result.nextRound : text.result.playAgain;
  ab.setAttribute('aria-label', midMatch ? text.result.nextRound : text.result.playAgain);
}
function showResult(r) {
  const R = text.result;
  const foeName = chars[r.foeId].name.split(' ')[0]; // first name keeps the score line short
  if (match) recordGame(match, { winner: r.winner, endReason: r.reason, done: r.done });
  const midMatch = !!match && !match.over;
  const win = r.winner === 0;
  const draw = r.winner === null;
  const bgSlot = win || draw ? 'result.win.background' : 'result.loss.background';
  const bg = $('#resBg');
  bg.style.backgroundImage = Art.has(bgSlot) ? `url("${Art.url(bgSlot)}")` : '';
  const banSlot = win ? 'result.win.banner' : 'result.loss.banner';
  const ban = $('#resBanner');
  if (!draw && Art.has(banSlot)) { ban.src = Art.url(banSlot); ban.style.display = ''; } else { ban.removeAttribute('src'); ban.style.display = 'none'; }
  $('#resTitle').textContent = match && match.over ? (match.winner === 0 ? R.matchWin : R.matchLose) : draw ? R.draw : win ? R.win : R.lose;
  let sub = '';
  if (r.reason === 'deck') sub = draw ? text.game.drawDeck : win ? text.game.youWinDeck : text.game.youLoseDeck;
  else if (r.reason === 'double') sub = text.game.doubleSeal;
  $('#resSub').textContent = sub;
  if (match) {
    const pts = match.history[match.history.length - 1].points;
    const parts = [];
    if (pts[0]) parts.push(fmt(R.points, { who: R.you, n: pts[0] }));
    if (pts[1]) parts.push(fmt(R.points, { who: foeName, n: pts[1] }));
    $('#resScore').textContent = fmt(R.score, { a: match.scores[0], b: match.scores[1], name: foeName });
    $('#resNext').textContent = `${parts.length ? parts.join(' · ') : R.noPoints} · ${match.suddenDeath ? R.suddenDeath : fmt(R.toTarget, { n: match.target })}`;
    $('#resOpen').textContent = midMatch ? (match.nextFirst === 0 ? R.opensYou : fmt(R.opensFoe, { name: foeName })) : '';
    // the consolation point, explained
    const lone = r.reason === 'seals' && pts[1 - r.winner] > 0;
    if (lone) $('#resSub').textContent = r.winner === 0 ? fmt(R.consolationFoe, { name: foeName }) : R.consolationYou;
    if (match.over) Store.addResult(sel.opponent, sel.difficulty, match.winner === 0 ? 'w' : 'l');
  } else { $('#resScore').textContent = ''; $('#resNext').textContent = ''; $('#resOpen').textContent = ''; }
  setAgainButton(midMatch);
  $('#resStats').textContent = fmt(R.stats, { t: r.stats.turns, s: r.stats.seals, f: r.stats.busts });
  const mood = win ? 'defeated' : draw ? 'neutral' : 'happy';
  $('#resFoe').src = Art.charUrl(r.foeId, mood) || '';
  $('#resFoe').style.filter = win ? 'grayscale(.5) brightness(.85)' : '';
  const b = $('#resBubble');
  b.textContent = r.line || '';
  b.classList.remove('show');
  show('result');
  Sound.sfx(win ? 'win' : draw ? 'pop' : 'lose', { jitter: 0 });
  Haptics.pulse(win ? [40, 40, 40, 40, 120] : [120]);
  if (Store.get('banter') && r.line) setTimeout(() => b.classList.add('show'), 900);
  refreshMenu();
}

// ---------------------------------------------------------------- charms sheet (swipe up)
function buildSheet() {
  const S = text.settings;
  const sh = $('#sheet');
  sh.innerHTML = `
    <div class="grip" id="grip"></div>
    <h3>${S.title}</h3>
    <div class="row-s"><span>${S.sfx}</span><input type="range" id="rngSfx" min="0" max="1" step="0.05"></div>
    <div class="row-s"><span>${S.music}</span><input type="range" id="rngMusic" min="0" max="1" step="0.05"></div>
    <div class="row-s"><span>${S.vibration}</span><button class="switch" id="swVib"></button></div>
    <div class="row-s"><span>${S.banter}</span><button class="switch" id="swBanter"></button></div>
    <div class="row-s"><span>${S.lockBreak}</span><button class="switch" id="swLock"></button></div>
    <div class="note" id="lockNote"></div>
    <div class="btns">
      <button class="btn ghost" id="shRules">${S.rules}</button>
      <button class="btn gold" id="shResume">${S.resume}</button>
      <button class="btn ghost" id="shLeave">${S.leave}</button>
    </div>`;
  $('#rngSfx').value = Store.get('sfx');
  $('#rngMusic').value = Store.get('music');
  $('#rngSfx').addEventListener('input', (e) => { Store.set('sfx', +e.target.value); Sound.setVol('sfx', +e.target.value); });
  $('#rngSfx').addEventListener('change', () => Sound.sfx('pop'));
  $('#rngMusic').addEventListener('input', (e) => { Store.set('music', +e.target.value); Sound.setVol('music', +e.target.value); });
  const sw = (id, key, after) => {
    const b = $(id);
    const paint = () => b.classList.toggle('on', !!Store.get(key));
    paint();
    b.addEventListener('click', () => {
      Store.set(key, !Store.get(key));
      paint();
      Sound.sfx(Store.get(key) ? 'toggleOn' : 'toggleOff');
      after && after(Store.get(key));
    });
  };
  sw('#swVib', 'vibration', (v) => { Haptics.on = v; Haptics.pulse(30); });
  sw('#swBanter', 'banter');
  sw('#swLock', 'lockBreak');
  $('#shResume').addEventListener('click', () => { Sound.sfx('tap'); closeSheet(); });
  $('#shRules').addEventListener('click', () => { Sound.sfx('tap'); openRules(); });
  $('#shLeave').addEventListener('click', () => { Sound.sfx('tap'); closeSheet(); game.abort(); match = null; show('menu'); });
  $('#scrim').addEventListener('click', closeSheet);
  attachSwipe($('#gameHandle'), () => openSheet('game'));
  // drag the sheet down to close
  const grip = $('#grip');
  let g = null;
  grip.addEventListener('pointerdown', (e) => { g = { y: e.clientY }; sh.classList.add('dragging'); grip.setPointerCapture(e.pointerId); });
  grip.addEventListener('pointermove', (e) => { if (g) sh.style.setProperty('--drag', Math.max(0, e.clientY - g.y) + 'px'); });
  const gEnd = (e) => {
    if (!g) return;
    const dy = e.clientY - g.y;
    g = null;
    sh.classList.remove('dragging');
    sh.style.removeProperty('--drag');
    if (dy > 70 || Math.abs(dy) < 6) closeSheet();
  };
  grip.addEventListener('pointerup', gEnd);
  grip.addEventListener('pointercancel', gEnd);
}
function openSheet(ctx) {
  if (sheetOpen) return;
  sheetContext = ctx;
  const inGame = ctx === 'game';
  $('#shLeave').style.display = inGame ? '' : 'none';
  $('#shResume').textContent = text.settings.resume;
  $('#swLock').classList.toggle('dis', inGame);
  $('#lockNote').textContent = inGame ? '' : '';
  sheetOpen = true;
  $('#sheet').classList.add('open');
  $('#sheet').setAttribute('aria-hidden', 'false');
  $('#scrim').classList.add('on');
  Sound.sfx('swoosh');
  if (inGame) game.setPaused(true);
}
function closeSheet() {
  if (!sheetOpen) return;
  sheetOpen = false;
  $('#sheet').classList.remove('open');
  $('#sheet').setAttribute('aria-hidden', 'true');
  $('#scrim').classList.remove('on');
  Sound.sfx('swoosh', { rate: 0.8 });
  game.setPaused(false);
}
/** A handle that opens something on a swipe up (or a tap). */
function attachSwipe(h, fn) {
  let y0 = null;
  h.addEventListener('pointerdown', (e) => { y0 = e.clientY; try { h.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ } });
  h.addEventListener('pointermove', (e) => { if (y0 !== null && y0 - e.clientY > 32) { y0 = null; fn(); } });
  h.addEventListener('pointerup', (e) => { if (y0 !== null && Math.abs(y0 - e.clientY) < 8) fn(); y0 = null; });
}

// ---------------------------------------------------------------- rules
function openRules(first = false) {
  return new Promise((resolve) => {
    const pages = text.rules.pages;
    let k = 0;
    const modal = $('#modal');
    const draw = () => {
      const p = pages[k];
      modal.innerHTML = `<div class="panel"><h3>${p.h}</h3>${p.b.map((x) => `<p>${x}</p>`).join('')}
        <div class="dots">${pages.map((_, i) => `<i class="${i === k ? 'on' : ''}"></i>`).join('')}</div>
        <div class="nav"><button class="btn ghost" id="rPrev" ${k === 0 ? 'style="visibility:hidden"' : ''}>‹</button>
        <button class="btn gold" id="rNext">${k === pages.length - 1 ? (first ? text.menu.begin : 'OK') : '›'}</button></div></div>`;
      $('#rPrev').onclick = () => { Sound.sfx('tap'); k--; draw(); };
      $('#rNext').onclick = () => {
        Sound.sfx('tap');
        if (k === pages.length - 1) { modal.classList.remove('open'); resolve(); } else { k++; draw(); }
      };
    };
    draw();
    modal.classList.add('open');
  });
}

// Android back button (Capacitor). Guarded so the page also runs in a plain browser.
try {
  const cap = window.Capacitor;
  if (cap && cap.registerPlugin) {
    const App = cap.registerPlugin('App');
    App.addListener('backButton', () => {
      if ($('#modal').classList.contains('open')) { $('#modal').classList.remove('open'); return; }
      if (sheetOpen) { closeSheet(); return; }
      if (current === 'game') { openSheet('game'); return; }
      if (current === 'result') { show('menu'); return; }
      if (current === 'menu') { App.exitApp(); }
    });
    App.addListener('pause', () => Sound.suspend());
    App.addListener('resume', () => Sound.resume());
  }
} catch (e) { /* not running under Capacitor */ }

boot();
