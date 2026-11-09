// Pure rule helpers shared by the game state machine (rules.js) and the AI (ai.js).
// Boards are described by three plain values:
//   piles : array(8) of arrays of card ids, bottom -> top.  Player p owns piles 4p..4p+3.
//   lb    : array(8) - 0 if the pile is live, else the target bit it is sealed for (1 = the 7 pair, 2 = the 11 pair)
//   done  : [mask0, mask1] - bit 1 = 7 sealed, bit 2 = 11 sealed (3 = both = win when the turn ends)
// Seals are chosen by the player (lock / unlock during their own turn); only a 13 played on a sealed pile breaks one by force.
import { is13, topIs13, topValue } from './cards.js';

export const TARGETS = [
  { value: 7, bit: 1 },
  { value: 11, bit: 2 },
];
export const POP = [0, 1, 1, 2];
export const HAND_SIZE = 4;

export const ownPiles = (p) => [4 * p, 4 * p + 1, 4 * p + 2, 4 * p + 3];
export const remainingTargets = (done) => TARGETS.filter((t) => !(done & t.bit)).map((t) => t.value);

/** Play one card on one pile. The only mode is 'cover': the card goes on top, hiding whatever was showing.
 *  Returns null if illegal, otherwise {piles, lb, done, discarded, kind, broke}. Does not mutate inputs. */
export function applyFull(piles, lb, done, card, i, mode, lockBreak = true) {
  const pile = piles[i];
  const set = (np) => {
    const out = piles.slice();
    out[i] = np;
    return out;
  };
  if (lb[i]) {
    // A sealed pile is frozen - except that a 13 played on top can crack it open.
    if (!(lockBreak && is13(card) && mode === 'cover')) return null;
    const bit = lb[i];
    const owner = i >> 2;
    const nlb = lb.slice();
    const partners = [];
    for (let j = 4 * owner; j < 4 * owner + 4; j++) {
      if (nlb[j] === bit) {
        nlb[j] = 0;
        partners.push(j);
      }
    }
    const nd = done.slice();
    nd[owner] &= ~bit;
    return { piles: set([]), lb: nlb, done: nd, discarded: pile.concat([card]), kind: 'bust', broke: { bit, owner, piles: partners } };
  }
  if (is13(card)) {
    if (mode === 'cover') {
      if (pile.length) return { piles: set([]), lb, done, discarded: pile.concat([card]), kind: 'bust', broke: null };
      return { piles: set([card]), lb, done, discarded: [], kind: 'base', broke: null };
    }
    return null;
  }
  if (mode !== 'cover') return null;
  return { piles: set(pile.concat([card])), lb, done, discarded: [], kind: 'cover', broke: null };
}

/** Which pairs could the player seal right now? */
export function sealOptions(piles, lb, done, p) {
  const live = ownPiles(p).filter((i) => !lb[i]);
  const rem = TARGETS.filter((t) => !(done[p] & t.bit));
  if (!rem.length) return { options: [], win: null };
  const vals = live.map((i) => topValue(piles[i]));
  const ok = live.map((i) => !topIs13(piles[i]));
  const options = [];
  for (let a = 0; a < live.length; a++) {
    for (let b = a + 1; b < live.length; b++) {
      if (!ok[a] || !ok[b]) continue;
      const s = vals[a] + vals[b];
      for (const t of rem) if (s === t.value) options.push({ piles: [live[a], live[b]], value: t.value, bit: t.bit, a, b });
    }
  }
  let win = null;
  if (rem.length === 2) {
    for (const o1 of options) {
      for (const o2 of options) {
        if (o1.bit !== o2.bit && o1.a !== o2.a && o1.a !== o2.b && o1.b !== o2.a && o1.b !== o2.b) {
          win = [o1, o2];
          break;
        }
      }
      if (win) break;
    }
  } else if (options.length) {
    win = [options[0]]; // last target: sealing it wins
  }
  return { options, win, live, vals, rem };
}

/** Default choice when several pairs could be sealed: keep the best chance at the other target. */
export function bestSealOption(info, rng = Math.random) {
  const { options, live, vals, rem } = info;
  if (!options.length) return null;
  let best = null;
  let bestD = 1e9;
  const shuffled = options.slice().sort(() => rng() - 0.5);
  for (const o of shuffled) {
    let d = 0;
    if (rem.length === 2) {
      const rest = live.map((_, k) => k).filter((k) => k !== o.a && k !== o.b);
      const other = rem.find((t) => t.bit !== o.bit).value;
      d = Math.abs(vals[rest[0]] + vals[rest[1]] - other);
    }
    if (d < bestD) {
      bestD = d;
      best = o;
    }
  }
  return best;
}

/** Can the player legally finish their turn with this board?  Returns {legal, reason, sealed:Set} */
export function endLegality(piles, lb, done, p, sealedPiles = new Set()) {
  for (let i = 0; i < 8; i++) {
    if (lb[i] || sealedPiles.has(i)) continue;
    if (topIs13(piles[i])) return { legal: false, reason: 'force-on-top' };
  }
  for (const i of ownPiles(p)) {
    if (lb[i] || sealedPiles.has(i)) continue;
    if (!piles[i].length) return { legal: false, reason: 'empty-pile' };
  }
  return { legal: true, reason: null };
}

/** How far a player's live piles are from completing their remaining targets (lower = closer). */
export function distance(vals, targets) {
  const n = targets.length;
  if (n === 0) return 0;
  if (n === 1) return Math.abs(vals[0] + vals[1] - targets[0]);
  const [a, b, c, d] = vals;
  let best = 99;
  for (const [s1, s2] of [[a + b, c + d], [a + c, b + d], [a + d, b + c]]) {
    const v = Math.min(Math.abs(s1 - 7) + Math.abs(s2 - 11), Math.abs(s1 - 11) + Math.abs(s2 - 7));
    if (v < best) best = v;
  }
  return best;
}

export function playerDistance(piles, lb, done, p) {
  const live = ownPiles(p).filter((i) => !lb[i]);
  return distance(live.map((i) => topValue(piles[i])), remainingTargets(done[p]));
}

/** Every set of locks a player could apply right now: nothing, one pair, or one pair for each target
 *  (two disjoint pairs). Used by the AI to pick its final locks. */
export function lockSets(piles, lb, done, p) {
  const { options } = sealOptions(piles, lb, done, p);
  const sets = [[]];
  for (const o of options) sets.push([o]);
  for (let x = 0; x < options.length; x++) {
    for (let y = x + 1; y < options.length; y++) {
      const a = options[x];
      const b = options[y];
      if (a.bit === b.bit) continue;
      if (a.piles.some((i) => b.piles.includes(i))) continue;
      sets.push([a, b]);
    }
  }
  return sets;
}
