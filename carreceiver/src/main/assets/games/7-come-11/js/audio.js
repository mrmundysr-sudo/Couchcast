// Web Audio manager: decoded SFX buffers, looping music with crossfades, user-controlled volumes.
// Sounds are loaded by NAME from audio/audio-config.json (ogg first, m4a fallback) so they can be swapped.
import { getJSON } from './util.js';

export const Sound = {
  ctx: null, cfg: null, buf: {}, sfxBus: null, musicBus: null, master: null,
  vol: { sfx: 0.9, music: 0.7 }, cur: null, curName: null, ready: false,

  async init() {
    try { this.cfg = await getJSON('audio/audio-config.json'); } catch (e) { this.cfg = { sfx: {}, music: {}, gain: {} }; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ctx = new AC();
    this.master = this.ctx.createGain();
    this.sfxBus = this.ctx.createGain();
    this.musicBus = this.ctx.createGain();
    const comp = this.ctx.createDynamicsCompressor();
    this.sfxBus.connect(this.master);
    this.musicBus.connect(this.master);
    this.master.connect(comp);
    comp.connect(this.ctx.destination);
    this.applyVol();
  },
  pickFile(files) {
    const a = document.createElement('audio');
    const ogg = a.canPlayType && a.canPlayType('audio/ogg; codecs="vorbis"');
    for (const f of files) { if (f.endsWith('.ogg') && !ogg) continue; return f; }
    return files[files.length - 1];
  },
  async load(name, files) {
    try {
      const r = await fetch('audio/' + this.pickFile(files));
      const ab = await r.arrayBuffer();
      this.buf[name] = await new Promise((res, rej) => this.ctx.decodeAudioData(ab, res, rej));
    } catch (e) { /* a missing sound is silent, never fatal */ }
  },
  async preload(onProgress) {
    if (!this.ctx) return;
    const jobs = [];
    for (const [n, f] of Object.entries(this.cfg.sfx || {})) jobs.push(() => this.load('sfx:' + n, f));
    for (const [n, m] of Object.entries(this.cfg.music || {})) jobs.push(() => this.load('music:' + n, m.files));
    let done = 0;
    await Promise.all(jobs.map(async (j) => { await j(); onProgress && onProgress(++done / jobs.length); }));
    this.ready = true;
  },
  unlock() { if (this.ctx && this.ctx.state !== 'running') this.ctx.resume().catch(() => {}); },
  applyVol() {
    if (!this.ctx) return;
    const g = this.cfg.gain || {};
    const t = this.ctx.currentTime;
    this.sfxBus.gain.setTargetAtTime(this.vol.sfx * (g.sfx ?? 0.9), t, 0.02);
    this.musicBus.gain.setTargetAtTime(this.vol.music * (g.music ?? 1), t, 0.05);
  },
  setVol(kind, v) { this.vol[kind] = v; this.applyVol(); },

  sfx(name, { rate = 1, gain = 1, jitter = 0.035, delay = 0 } = {}) {
    const b = this.buf['sfx:' + name];
    if (!this.ctx || !b || this.vol.sfx <= 0) return;
    const s = this.ctx.createBufferSource();
    s.buffer = b;
    s.playbackRate.value = rate * (1 + (Math.random() * 2 - 1) * jitter);
    const g = this.ctx.createGain();
    g.gain.value = gain;
    s.connect(g);
    g.connect(this.sfxBus);
    s.start(this.ctx.currentTime + delay);
  },
  music(name) {
    if (!this.ctx || this.curName === name) return;
    const t = this.ctx.currentTime;
    if (this.cur) {
      const old = this.cur;
      old.g.gain.cancelScheduledValues(t);
      old.g.gain.setValueAtTime(old.g.gain.value, t);
      old.g.gain.linearRampToValueAtTime(0, t + 1.1);
      setTimeout(() => { try { old.s.stop(); } catch (e) { /* already stopped */ } }, 1300);
      this.cur = null;
    }
    this.curName = name;
    const b = this.buf['music:' + name];
    const conf = (this.cfg.music || {})[name] || {};
    if (!b) return;
    const s = this.ctx.createBufferSource();
    s.buffer = b;
    s.loop = conf.loop !== false;
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(conf.gain ?? 0.5, t + 1.4);
    s.connect(g);
    g.connect(this.musicBus);
    s.start(t);
    this.cur = { s, g };
  },
  stopMusic() { this.music(null); },
  suspend() { if (this.ctx && this.ctx.state === 'running') this.ctx.suspend().catch(() => {}); },
  resume() { this.unlock(); },
};

/** Short vibration pulses (Android WebView supports navigator.vibrate). */
export const Haptics = {
  on: true,
  pulse(p) { if (this.on && navigator.vibrate) { try { navigator.vibrate(p); } catch (e) { /* ignore */ } } },
};
