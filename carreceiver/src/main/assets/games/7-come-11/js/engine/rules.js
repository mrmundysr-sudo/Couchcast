// Touch 7 Come 11 - game state machine (rules as finalised in the rulebook, plus lock-breaking and player-chosen lock/unlock).
// Player 0 is the human, player 1 is the opponent. Everything here is pure game logic (no DOM, no audio),
// so the same code is unit-tested in Node and drives the real game.
import { freshDeck, shuffle, suitOf, topIs13 } from './cards.js';
import {
  HAND_SIZE, POP, applyFull, endLegality, ownPiles, playerDistance, sealOptions,
} from './core.js';

export { HAND_SIZE };

export function newGame({ rng = Math.random, first = 'random', lockBreak = true } = {}) {
  {
    const deck = freshDeck(rng);
    const piles = Array.from({ length: 8 }, () => [deck.pop()]);
    const hands = [[], []];
    for (let p = 0; p < 2; p++) for (let k = 0; k < HAND_SIZE; k++) hands[p].push(deck.pop());
    for (let i = 0; i < 8; i++) while (topIs13(piles[i])) piles[i].push(deck.pop()); // a dealt 13 is covered
    const lb = new Array(8).fill(0);
    const done = [0, 0];
    const openSeals = []; // nobody starts locked: players lock when they choose to
    const turn = first === 'random' ? (rng() < 0.5 ? 0 : 1) : first;
    return {
      piles, lb, done, hands, deck, discard: [], turn, first: turn, plays: 0, turns: [0, 0], reshuffles: 0,
      over: false, winner: null, endReason: null, lockBreak, redeals: 0, openSeals, finalTurn: false,
      stats: { busts: [0, 0], seals: [0, 0], breaks: [0, 0] },
    };
  }
}

export const snapshot = (s) => JSON.parse(JSON.stringify(s));
export function restore(state, snap) {
  const copy = JSON.parse(JSON.stringify(snap));
  for (const k of Object.keys(state)) delete state[k];
  Object.assign(state, copy);
}

/** Is it legal to play `card` on `pile` in `mode` right now?  (UI helper; does not change anything.) */
export function canPlay(state, card, pile, mode) {
  return !state.over && applyFull(state.piles, state.lb, state.done, card, pile, mode, state.lockBreak) !== null;
}

/** For the highlight logic: every {pile, modes[]} where `card` could go. */
export function playTargets(state, card) {
  const out = [];
  for (let pile = 0; pile < 8; pile++) {
    const modes = ['cover'].filter((m) => canPlay(state, card, pile, m));
    if (modes.length) out.push({ pile, modes });
  }
  return out;
}

/** Play a card from the current player's hand. Returns {ok, events} - events drive animation and sound. */
export function playCard(state, card, pile, mode) {
  if (state.over) return { ok: false, reason: 'over', events: [] };
  const p = state.turn;
  const hi = state.hands[p].indexOf(card);
  if (hi < 0) return { ok: false, reason: 'not-in-hand', events: [] };
  const r = applyFull(state.piles, state.lb, state.done, card, pile, mode, state.lockBreak);
  if (!r) return { ok: false, reason: 'illegal', events: [] };
  state.hands[p].splice(hi, 1);
  const events = [];
  const before = state.piles[pile].length;
  state.piles = r.piles;
  state.lb = r.lb;
  state.done = r.done;
  if (r.discarded.length) state.discard.push(...r.discarded);
  events.push({ t: 'place', player: p, card, pile, mode, kind: r.kind, handIndex: hi, depthBefore: before });
  if (r.kind === 'bust') {
    state.stats.busts[p]++;
    events.push({ t: 'bust', player: p, card, pile, force: suitOf(card), discarded: r.discarded, owner: pile >> 2 });
    if (r.broke) {
      state.stats.breaks[p]++;
      events.push({ t: 'unseal', player: p, owner: r.broke.owner, piles: r.broke.piles, bit: r.broke.bit, cracked: pile });
    }
  }
  state.plays++;
  return { ok: true, events };
}

/** Pairs the current player could lock right now: [{piles:[i,j], value, bit}]. (UI helper) */
export function lockOptions(state, p = state.turn) {
  if (state.over) return [];
  return sealOptions(state.piles, state.lb, state.done, p).options;
}

/** Which of the current player's targets are locked right now: [{bit, value, piles}]. (UI helper) */
export function lockedPairs(state, p = state.turn) {
  const out = [];
  for (const [bit, value] of [[1, 7], [2, 11]]) {
    if (state.done[p] & bit) out.push({ bit, value, piles: ownPiles(p).filter((i) => state.lb[i] === bit) });
  }
  return out;
}

/** Lock a pair of the current player's piles (two piles whose tops add to a target not yet locked).
 *  Free, any number of times, only on your own turn. */
export function lockPair(state, piles, bit) {
  if (state.over) return { ok: false, reason: 'over', events: [] };
  const p = state.turn;
  const opt = lockOptions(state, p).find((o) => o.bit === bit && o.piles.length === piles.length && o.piles.every((i) => piles.includes(i)));
  if (!opt) return { ok: false, reason: 'illegal', events: [] };
  for (const i of opt.piles) state.lb[i] = opt.bit;
  state.done[p] |= opt.bit;
  state.stats.seals[p]++;
  return { ok: true, events: [{ t: 'seal', player: p, piles: opt.piles, bit: opt.bit, value: opt.value }] };
}

/** Unlock one of the current player's own locked pairs (bit 1 = the 7, 2 = the 11). The cards stay as they are
 *  and the piles can be played on again. Free, any number of times, only on your own turn. */
export function unlockPair(state, bit) {
  if (state.over) return { ok: false, reason: 'over', events: [] };
  const p = state.turn;
  if (!(state.done[p] & bit)) return { ok: false, reason: 'not-locked', events: [] };
  const piles = ownPiles(p).filter((i) => state.lb[i] === bit);
  for (const i of piles) state.lb[i] = 0;
  state.done[p] &= ~bit;
  return { ok: true, events: [{ t: 'unlock', player: p, piles, bit, value: bit === 1 ? 7 : 11 }] };
}

/** Run one step of an AI plan: {type:'play'|'lock'|'unlock', ...}. */
export function runStep(state, step) {
  if (step.type === 'unlock') return unlockPair(state, step.bit);
  if (step.type === 'lock') return lockPair(state, step.piles, step.bit);
  return playCard(state, step.card, step.pile, step.mode);
}

/** What would happen if the player ended their turn now? (UI helper) */
export function previewEnd(state) {
  const p = state.turn;
  if (state.over) return { legal: false, reason: 'over' };
  if (state.hands[p].length > 0) return { legal: false, reason: 'cards-left' };
  const lg = endLegality(state.piles, state.lb, state.done, p);
  if (!lg.legal) return { legal: false, reason: lg.reason };
  return { legal: true, reason: null, win: state.done[p] === 3 };
}

/** Finish the turn: check the win, refill the hand, hand over to the other player.
 *  Nothing locks by itself any more - locks are the player's own choice during the turn. */
export function endTurn(state, { rng = Math.random, force = false } = {}) {
  const p = state.turn;
  const events = [];
  const pv = previewEnd(state);
  if (!pv.legal && !(force && state.hands[p].length === 0 && !state.over)) return { ok: false, reason: pv.reason, events };
  state.turns[p]++;
  const opener = state.first;
  if (state.finalTurn) {
    // p is replying to the opener's seal-out. Judge the board as it stands now.
    state.finalTurn = false;
    const openerSealed = state.done[opener] === 3;
    if (state.done[p] === 3) {
      state.over = true;
      if (openerSealed) { state.winner = null; state.endReason = 'double'; } // both sealed out: a drawn round
      else { state.winner = p; state.endReason = 'seals'; } // a Force broke their seal, then p sealed out
      events.push({ t: 'gameover', winner: state.winner, reason: state.endReason });
      return { ok: true, events };
    }
    if (openerSealed) {
      state.over = true; state.winner = opener; state.endReason = 'seals';
      events.push({ t: 'gameover', winner: opener, reason: 'seals' });
      return { ok: true, events };
    }
    events.push({ t: 'winCancelled', player: opener }); // a seal was broken: the round goes on
  } else if (state.done[p] === 3) {
    if (p !== opener) {
      state.over = true; state.winner = p; state.endReason = 'seals';
      events.push({ t: 'gameover', winner: p, reason: 'seals' });
      return { ok: true, events };
    }
    // The opener sealed out: the other player gets one last turn to match it or break it.
    state.finalTurn = true;
  }
  // refill the hand
  const drawn = [];
  const hand = state.hands[p];
  while (hand.length < HAND_SIZE) {
    if (!state.deck.length) {
      if (state.discard.length) {
        state.deck = shuffle(state.discard.splice(0), rng);
        state.reshuffles++;
        events.push({ t: 'reshuffle' });
      } else break;
    }
    const c = state.deck.pop();
    hand.push(c);
    drawn.push(c);
  }
  events.push({ t: 'draw', player: p, cards: drawn });
  state.turn = 1 - p;
  if (state.finalTurn) events.push({ t: 'finalTurn', player: state.turn, opener: p });
  events.push({ t: 'turn', player: state.turn });
  if (!state.hands[state.turn].length) {
    if (state.finalTurn) { // no cards left to reply with: the opener's seal-out stands
      state.finalTurn = false;
      state.over = true; state.winner = p; state.endReason = 'seals';
      events.push({ t: 'gameover', winner: p, reason: 'seals' });
    } else finishByDeck(state, events);
  }
  return { ok: true, events };
}

function finishByDeck(state, events) {
  state.over = true;
  state.endReason = 'deck';
  const score = (p) => [POP[state.done[p]], -playerDistance(state.piles, state.lb, state.done, p)];
  const a = score(0);
  const b = score(1);
  if (a[0] !== b[0]) state.winner = a[0] > b[0] ? 0 : 1;
  else if (a[1] !== b[1]) state.winner = a[1] > b[1] ? 0 : 1;
  else state.winner = null;
  events.push({ t: 'gameover', winner: state.winner, reason: 'deck' });
}

/** Safety valve: if the player truly cannot finish (should be vanishingly rare), release stray 13s. */
export function forceLegal(state) {
  const events = [];
  for (let i = 0; i < 8; i++) {
    if (state.lb[i]) continue;
    while (topIs13(state.piles[i])) {
      const c = state.piles[i].pop();
      state.discard.push(c);
      events.push({ t: 'forced', pile: i, card: c });
    }
  }
  return events;
}

export function livePiles(state, p) {
  return ownPiles(p).filter((i) => !state.lb[i]);
}

export function sealedTargets(state, p) {
  return { seven: !!(state.done[p] & 1), eleven: !!(state.done[p] & 2) };
}
