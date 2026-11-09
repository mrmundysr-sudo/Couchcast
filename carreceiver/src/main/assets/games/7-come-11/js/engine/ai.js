// Opponent AI: beam search over the 4 cards of a turn, scored by a "personality" evaluation.
// The AI only ever looks at what a human could see (the top cards) plus its own hand.
import { is13, topIs13, topValue } from './cards.js';
import { POP, TARGETS, applyFull, distance, endLegality, lockSets, ownPiles, remainingTargets } from './core.js';

/** How strong the opponent plays. width = search breadth, sloppy = chance to settle for a worse plan. */
export const DIFFICULTIES = {
  beginner: { width: 14, sloppy: 0.4, topK: 7, oppMul: 0.35, noise: 0.5 },
  experienced: { width: 60, sloppy: 0.12, topK: 3, oppMul: 0.85, noise: 0.12 },
  professional: { width: 150, sloppy: 0, topK: 1, oppMul: 1.0, noise: 0.01 },
};

/** Personality knobs (see data/characters.json): how much they care about hurting you vs building. */
export const DEFAULT_PERSONA = { wOpp: 0.6, oppSeal: 12, ownSeal: 12, forceRefill: 0.2 };

function evalBoard(piles, lb, done, p, prm) {
  const q = 1 - p;
  const ownLive = ownPiles(p).filter((i) => !lb[i]);
  const oppLive = ownPiles(q).filter((i) => !lb[i]);
  const od = distance(ownLive.map((i) => topValue(piles[i])), remainingTargets(done[p]));
  const pd = distance(oppLive.map((i) => topValue(piles[i])), remainingTargets(done[q]));
  let pen = 0;
  for (const i of ownLive) {
    if (topIs13(piles[i])) pen += 4;
    if (!piles[i].length) pen += 1;
  }
  let oppEmpty = 0;
  for (const i of oppLive) {
    if (topIs13(piles[i])) pen += 4;
    if (!piles[i].length) oppEmpty++;
  }
  // Reply turn: the opponent has already sealed out and wins unless a seal is broken or matched.
  const replyBreak = prm.replying && done[q] !== 3 ? 60 : 0;
  return -od + prm.wOpp * pd + prm.ownSeal * POP[done[p]] - prm.oppSeal * POP[done[q]] - pen + prm.forceRefill * oppEmpty + replyBreak;
}

/** Pick the best legal set of locks to finish the turn with (nothing, one pair, or both pairs) and score it. */
function scoreFinal(piles, lb, done, p, prm) {
  let best = null;
  for (const locks of lockSets(piles, lb, done, p)) {
    const nlb = lb.slice();
    const nd = done.slice();
    const sealed = new Set();
    for (const o of locks) {
      for (const i of o.piles) { nlb[i] = o.bit; sealed.add(i); }
      nd[p] |= o.bit;
    }
    if (!endLegality(piles, lb, done, p, sealed).legal) continue;
    if (nd[p] === 3) return { score: 1e6 + (nd[1 - p] === 3 ? 0 : 1000), locks }; // sealing out beats drawing on a reply turn
    const s = evalBoard(piles, nlb, nd, p, prm);
    if (!best || s > best.score) best = { score: s, locks };
  }
  return best;
}

/** An unlock that is followed by locking the very same piles again, with nothing played on them, does nothing. */
function dropNoopRelock(state, p, plan) {
  const un = plan.find((m) => m.type === 'unlock');
  if (!un) return plan;
  const was = ownPiles(p).filter((i) => state.lb[i] === un.bit);
  const re = plan.find((m) => m.type === 'lock' && m.bit === un.bit);
  const same = re && re.piles.length === was.length && re.piles.every((i) => was.includes(i));
  const touched = plan.some((m) => m.type === 'play' && was.includes(m.pile));
  return same && !touched ? plan.filter((m) => m !== un && m !== re) : plan;
}

/**
 * Decide the whole turn for player p.  Returns {plan:[step], legal:boolean, score}, where a step is one of
 *   {type:'unlock', bit}  - free one of my own locked pairs first (only when that leads somewhere better)
 *   {type:'play', card, pile, mode}
 *   {type:'lock', piles, bit, value}  - locks chosen at the end of the turn
 * `persona` = personality knobs, `difficulty` = key of DIFFICULTIES, rng = () => [0,1).
 */
export function planTurn(state, p, persona = DEFAULT_PERSONA, difficulty = 'professional', rng = Math.random) {
  const diff = DIFFICULTIES[difficulty] || DIFFICULTIES.professional;
  const prm = {
    wOpp: persona.wOpp * diff.oppMul,
    oppSeal: persona.oppSeal * (diff.oppMul > 0.5 ? 1 : 0.4),
    ownSeal: persona.ownSeal,
    forceRefill: persona.forceRefill * diff.oppMul,
    replying: !!state.finalTurn && state.done[1 - p] === 3,
  };
  const lockBreak = state.lockBreak;
  const hand = state.hands[p].slice();

  // Starting boards: as it stands, plus (if I hold a lock) the same board with that lock released.
  const starts = [{ prefix: [], lb: state.lb, done: state.done }];
  for (const t of TARGETS) {
    if (!(state.done[p] & t.bit)) continue;
    const lb = state.lb.map((v, i) => (v === t.bit && (i >> 2) === p ? 0 : v));
    const done = state.done.slice();
    done[p] &= ~t.bit;
    starts.push({ prefix: [{ type: 'unlock', bit: t.bit }], lb, done });
  }

  const search = (width, start) => {
    let beam = [{ score: 0, piles: state.piles, lb: start.lb, done: start.done, rest: hand, plan: [] }];
    for (let step = 0; step < hand.length; step++) {
      const cand = new Map();
      for (const b of beam) {
        for (let ci = 0; ci < b.rest.length; ci++) {
          const card = b.rest[ci];
          const nrest = b.rest.slice(0, ci).concat(b.rest.slice(ci + 1));
          for (let i = 0; i < 8; i++) {
            for (const mode of ['cover']) {
              const r = applyFull(b.piles, b.lb, b.done, card, i, mode, lockBreak);
              if (!r) continue;
              let key = nrest.join(',');
              for (let k = 0; k < 8; k++) key += '/' + r.piles[k].join('.') + (r.lb[k] ? '#' : '');
              const sc = evalBoard(r.piles, r.lb, r.done, p, prm) + rng() * diff.noise;
              const prev = cand.get(key);
              if (!prev || prev.score < sc) cand.set(key, { score: sc, piles: r.piles, lb: r.lb, done: r.done, rest: nrest, plan: b.plan.concat([{ type: 'play', card, pile: i, mode }]) });
            }
          }
        }
      }
      if (!cand.size) break;
      const all = Array.from(cand.values());
      // the last card is never pruned: every finished board gets a legality check
      beam = step === hand.length - 1 ? all : all.sort((x, y) => y.score - x.score).slice(0, width);
    }
    const finals = [];
    for (const b of beam) {
      const f = scoreFinal(b.piles, b.lb, b.done, p, prm);
      if (!f) continue;
      const locks = f.locks.map((o) => ({ type: 'lock', piles: o.piles, bit: o.bit, value: o.value }));
      finals.push({ score: f.score + rng() * diff.noise * 0.2, plan: start.prefix.concat(b.plan, locks), win: f.score >= 1e6 });
    }
    return { finals, fallback: beam.sort((x, y) => y.score - x.score)[0] && { plan: start.prefix.concat(beam[0].plan), score: beam[0].score } };
  };

  const run = (width) => {
    const finals = [];
    let fallback = null;
    starts.forEach((st, k) => {
      const r = search(k === 0 ? width : Math.max(8, Math.round(width / 2)), st); // the "unlock first" lines get a narrower look
      finals.push(...r.finals);
      if (!fallback || (r.fallback && k === 0)) fallback = r.fallback;
    });
    return { finals, fallback };
  };

  let res = run(diff.width);
  if (!res.finals.length) res = run(Math.max(diff.width * 3, 200));   // widen once before giving up
  const { finals, fallback } = res;
  if (!finals.length) return { plan: fallback ? fallback.plan : [], legal: false, score: -1e9 };
  finals.sort((x, y) => y.score - x.score);
  let pick = finals[0];
  if (!pick.win || diff.sloppy > 0.3) {
    if (rng() < diff.sloppy) pick = finals[Math.min(finals.length - 1, Math.floor(rng() * Math.min(diff.topK, finals.length)))];
  }
  return { plan: dropNoopRelock(state, p, pick.plan), legal: true, score: pick.score };
}

/** Quick helper for banter/tests: is this plan going to bust something of the opponent's? */
export function planSummary(state, p, plan) {
  let busts = 0;
  for (const m of plan) if (m.type === 'play' && is13(m.card) && m.mode === 'cover' && state.piles[m.pile].length) busts++;
  return { busts };
}
