// Match play: a race to 7 or 11 points across several games. Pure data, no DOM.
//
// Points per game:
//   - winning by sealing both targets:                      2
//   - winning when the deck runs dry:                       1
//   - losing a seal-out game while still holding one seal:  1 (a consolation point)
//   - both players sealed out (opener + reply turn):        2 each
//   - a deck-out draw, or a deck-out loss:                  0
// A player who ends their own turn with both targets sealed wins on the spot - unless they opened the round, in which
// case the other player gets one last turn: matching the seal-out draws the round (2 each), and a Force that breaks
// a seal cancels the win and play goes on. So the loser of a seal-out game holds at most one seal.
//
// The match ends when someone has reached the target and leads. If both reach it in the same game, the higher total
// wins; if they are level, play goes on (sudden death) until one player is ahead. The player who opens the first
// game is chosen by the caller; after that the opening seat alternates every game.

import { POP } from './core.js';

export const TARGETS = [7, 11];
export const POINTS = { seals: 2, deck: 1, draw: 0, consolation: 1, double: 2 };

/** @param {{target?: number, first?: 0|1}} opts first = who opens game 1 (0 = you, 1 = the foe). */
export function newMatch({ target = 11, first = 0 } = {}) {
  return {
    target: TARGETS.includes(target) ? target : 11,
    scores: [0, 0],
    games: 0,
    nextFirst: first === 1 ? 1 : 0,
    over: false,
    winner: null,
    suddenDeath: false, // both players have reached the target and are level
    history: [], // [{ winner, reason, points, first }]
  };
}

/**
 * Points a finished game is worth, as [you, foe].
 * `game` needs { winner, endReason } and, for the consolation point, `done` (each player's sealed-target bitmask).
 */
export function pointsFor(game) {
  const pts = [0, 0];
  if (game.endReason === 'double') return [POINTS.double, POINTS.double]; // both sealed out: 2 each
  if (game.winner !== 0 && game.winner !== 1) return pts;
  const w = game.winner;
  const l = 1 - w;
  if (game.endReason === 'seals') {
    pts[w] = POINTS.seals;
    if (game.done && POP[game.done[l]] >= 1) pts[l] = POINTS.consolation;
  } else {
    pts[w] = POINTS.deck;
  }
  return pts;
}

/**
 * Record a finished game into the match (mutates and returns it).
 * `game` needs { winner, endReason } and, for the consolation point, `done`. Flips the opener for the next game.
 */
export function recordGame(match, game) {
  if (match.over) return match;
  const pts = pointsFor(game);
  match.scores[0] += pts[0];
  match.scores[1] += pts[1];
  match.games++;
  match.history.push({ winner: game.winner, reason: game.endReason, points: pts, first: match.nextFirst });
  match.nextFirst = 1 - match.nextFirst;
  const top = Math.max(match.scores[0], match.scores[1]);
  match.suddenDeath = false;
  if (top >= match.target) {
    if (match.scores[0] === match.scores[1]) {
      match.suddenDeath = true; // level at or past the target: keep playing until someone leads
    } else {
      match.over = true;
      match.winner = match.scores[0] > match.scores[1] ? 0 : 1;
    }
  }
  return match;
}
