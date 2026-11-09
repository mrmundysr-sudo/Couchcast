// Settings + win/loss record, kept in localStorage (guarded: it can be missing or blocked).
const KEY = 't7c11.v1';
const defaults = {
  sfx: 0.9, music: 0.7, vibration: true, banter: true, lockBreak: true,
  opponent: 'witch', difficulty: 'experienced', first: 'fate', target: 11, record: {}, seenRules: false,
};
let data = { ...defaults };
try { Object.assign(data, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch (e) { /* no storage: run in memory */ }
const save = () => { try { localStorage.setItem(KEY, JSON.stringify(data)); } catch (e) { /* ignore */ } };

export const Store = {
  get: (k) => data[k],
  set(k, v) { data[k] = v; save(); },
  recordKey: (opp, diff) => `${opp}:${diff}`,
  getRecord(opp, diff) { return data.record[`${opp}:${diff}`] || { w: 0, l: 0, d: 0 }; },
  addResult(opp, diff, res) {
    const k = `${opp}:${diff}`;
    const r = data.record[k] || { w: 0, l: 0, d: 0 };
    r[res]++;
    data.record[k] = r;
    save();
  },
};
