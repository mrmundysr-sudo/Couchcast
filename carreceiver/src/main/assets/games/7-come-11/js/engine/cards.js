// Card model. A card is just an integer id 0..51: suit * 13 + (rank - 1).
// Suits: 0 Sun, 1 Bloom (positive)   2 Moon, 3 Thorn (negative).   Rank 13 is the "force" (bust) card.
export const SUITS = ['sun', 'bloom', 'moon', 'thorn'];
export const SUIT_SIGN = [1, 1, -1, -1];
export const FORCE_NAMES = ['Flare', 'Overgrowth', 'Eclipse', 'Bramble'];

export const cardId = (suit, rank) => suit * 13 + rank - 1;
export const suitOf = (id) => Math.floor(id / 13);
export const rankOf = (id) => (id % 13) + 1;
export const is13 = (id) => id % 13 === 12;
export const isPositive = (id) => SUIT_SIGN[suitOf(id)] > 0;
/** Signed value of a card (13s count as 0 - they have no value of their own). */
export const valueOf = (id) => (is13(id) ? 0 : SUIT_SIGN[suitOf(id)] * rankOf(id));
/** Value a pile contributes = its top card (empty pile = 0). */
export const topValue = (pile) => (pile.length ? valueOf(pile[pile.length - 1]) : 0);
export const topIs13 = (pile) => pile.length > 0 && is13(pile[pile.length - 1]);

export function shuffle(arr, rng = Math.random) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    const t = arr[i]; arr[i] = arr[j]; arr[j] = t;
  }
  return arr;
}

export function freshDeck(rng = Math.random) {
  const d = [];
  for (let i = 0; i < 52; i++) d.push(i);
  return shuffle(d, rng);
}

/** Small seedable RNG (mulberry32) for tests and reproducible games. */
export function seeded(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function cardLabel(id) {
  if (is13(id)) return FORCE_NAMES[suitOf(id)];
  const v = valueOf(id);
  return (v < 0 ? '−' : '+') + Math.abs(v);
}
