/* Skill Bill — Linear study · component library
 * Every component is a function that returns an HTML string and records where it was used.
 * Screens (app/screens.js) may only call these functions plus the layout primitives in components.css. */
(function () {
  'use strict';

  /* ---------- infrastructure ---------- */
  const usage = {};
  let scope = null;
  function track(name) { if (scope) (usage[name] = usage[name] || new Set()).add(scope); }
  function inScope(name, fn) { const prev = scope; scope = name; try { return fn(); } finally { scope = prev; } }

  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const cx = (...xs) => xs.filter(Boolean).join(' ');
  const join = (xs) => (Array.isArray(xs) ? xs.flat(Infinity).filter((x) => x !== false && x != null).join('') : (xs || ''));
  /* act + arg become data-act / data-arg for the delegated event handler */
  function act(a, arg) { return a ? ` data-act="${esc(a)}"${arg != null ? ` data-arg="${esc(arg)}"` : ''}` : ''; }
  function tipAttrs(tip, kbd) { return tip ? ` data-tip="${esc(tip)}"${kbd ? ` data-kbd="${esc(kbd)}"` : ''} aria-label="${esc(tip)}"` : ''; }

  function fmtDur(ms, secs) {
    if (ms == null) return '—';
    const s = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
    if (h) return `${h}h ${String(m).padStart(2, '0')}m`;
    if (m) return secs ? `${m}m ${String(r).padStart(2, '0')}s` : `${m}m`;
    return `${r}s`;
  }

  /* ---------- Icon ---------- */
  const P = {
    search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
    inbox: '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
    target: '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>',
    workflow: '<rect width="8" height="8" x="3" y="3" rx="2"/><path d="M7 11v4a2 2 0 0 0 2 2h4"/><rect width="8" height="8" x="13" y="13" rx="2"/>',
    file: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/>',
    fileText: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    folder: '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
    history: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
    sliders: '<path d="M20 7h-9"/><path d="M14 17H5"/><circle cx="17" cy="17" r="3"/><circle cx="7" cy="7" r="3"/>',
    plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
    compose: '<path d="M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.375 2.625a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4Z"/>',
    chevronDown: '<path d="m6 9 6 6 6-6"/>',
    chevronRight: '<path d="m9 18 6-6-6-6"/>',
    chevronLeft: '<path d="m15 18-6-6 6-6"/>',
    chevronUp: '<path d="m18 15-6-6-6 6"/>',
    filter: '<path d="M3 6h18"/><path d="M7 12h10"/><path d="M10 18h4"/>',
    display: '<line x1="21" x2="14" y1="4" y2="4"/><line x1="10" x2="3" y1="4" y2="4"/><line x1="21" x2="12" y1="12" y2="12"/><line x1="8" x2="3" y1="12" y2="12"/><line x1="21" x2="16" y1="20" y2="20"/><line x1="12" x2="3" y1="20" y2="20"/><line x1="14" x2="14" y1="2" y2="6"/><line x1="8" x2="8" y1="10" y2="14"/><line x1="16" x2="16" y1="18" y2="22"/>',
    more: '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    copy: '<rect width="14" height="14" x="8" y="8" rx="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" x2="20" y1="19" y2="19"/>',
    pause: '<rect x="14" y="4" width="4" height="16" rx="1"/><rect x="6" y="4" width="4" height="16" rx="1"/>',
    stop: '<rect width="16" height="16" x="4" y="4" rx="2"/>',
    play: '<polygon points="6 3 20 12 6 21 6 3"/>',
    rerun: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/>',
    trash: '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/>',
    branch: '<line x1="6" x2="6" y1="3" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/>',
    commit: '<circle cx="12" cy="12" r="3"/><line x1="3" x2="9" y1="12" y2="12"/><line x1="15" x2="21" y1="12" y2="12"/>',
    cpu: '<rect width="16" height="16" x="4" y="4" rx="2"/><rect width="6" height="6" x="9" y="9" rx="1"/><path d="M15 2v2M15 20v2M2 15h2M2 9h2M20 15h2M20 9h2M9 2v2M9 20v2"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
    external: '<path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
    warn: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    alert: '<circle cx="12" cy="12" r="10"/><line x1="12" x2="12" y1="8" y2="12"/><line x1="12" x2="12.01" y1="16" y2="16"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
    checkCircle: '<circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2m-7.07-14.07 1.41 1.41m11.32 11.32 1.41 1.41M2 12h2m16 0h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>',
    moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
    command: '<path d="M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3"/>',
    arrowUp: '<path d="m5 12 7-7 7 7"/><path d="M12 19V5"/>',
    arrowDown: '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
    arrowLeft: '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
    panelLeft: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M9 3v18"/>',
    panelRight: '<rect width="18" height="18" x="3" y="3" rx="2"/><path d="M15 3v18"/>',
    refresh: '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
    zap: '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
    link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
    repo: '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
    gauge: '<path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/>',
    monitor: '<rect width="20" height="14" x="2" y="3" rx="2"/><line x1="8" x2="16" y1="21" y2="21"/><line x1="12" x2="12" y1="17" y2="21"/>',
    user: '<circle cx="12" cy="8" r="5"/><path d="M20 21a8 8 0 0 0-16 0"/>',
    eye: '<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/>',
    pencil: '<path d="M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z"/>',
    diff: '<path d="M12 3v14"/><path d="M5 10h14"/><path d="M5 21h14"/>',
    bot: '<path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/>',
    keyboard: '<path d="M10 8h.01"/><path d="M12 12h.01"/><path d="M14 8h.01"/><path d="M16 12h.01"/><path d="M18 8h.01"/><path d="M6 8h.01"/><path d="M7 16h10"/><path d="M8 12h.01"/><rect width="20" height="16" x="2" y="4" rx="2"/>',
    layers: '<path d="m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z"/><path d="m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65"/><path d="m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"/>',
    shield: '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
  };
  function Icon(name, size) {
    track('Icon');
    return `<svg class="${cx('sb-icon', size && 'sb-icon--' + size)}" viewBox="0 0 24 24" aria-hidden="true">${P[name] || ''}</svg>`;
  }

  /* ---------- Brand mark ---------- */
  function BrandMark(size) {
    track('BrandMark');
    const s = size || 20;
    return `<svg class="sb-mark" style="width:${s}px;height:${s}px" viewBox="0 0 64 64" aria-hidden="true">
      <defs><linearGradient id="sbg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f4c430"/><stop offset="1" stop-color="#8f6d05"/></linearGradient></defs>
      <rect x="1" y="1" width="62" height="62" rx="15" fill="url(#sbg)"/>
      <text x="58" y="56" text-anchor="end" font-family="JetBrains Mono, monospace" font-weight="800" font-size="27" letter-spacing="-1" fill="#0b0b0d">SB</text>
      <circle cx="51" cy="14" r="5" fill="#60d394" stroke="#0b0b0d" stroke-opacity=".35" stroke-width="1"/></svg>`;
  }

  /* ---------- Status icon: shape carries the state, color reinforces it ---------- */
  const STATES = {
    pending: { label: 'Pending', tone: 'pending' },
    planning: { label: 'Planning', tone: 'planning' },
    active: { label: 'Active', tone: 'active' },
    paused: { label: 'Paused', tone: 'paused' },
    blocked: { label: 'Blocked', tone: 'blocked' },
    failed: { label: 'Failed', tone: 'failed' },
    done: { label: 'Completed', tone: 'done' },
    no_change: { label: 'Completed, no change', tone: 'done' },
    canceled: { label: 'Canceled', tone: 'pending' },
    skipped: { label: 'Skipped', tone: 'pending' },
    stale: { label: 'Stale', tone: 'pending' },
  };
  function StatusIcon(state, frac, opts) {
    track('StatusIcon');
    const o = opts || {};
    const c = `var(--st-${(STATES[state] || STATES.pending).tone})`;
    const ink = 'var(--bg-panel)';
    let inner;
    switch (state) {
      case 'planning': inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5" stroke-dasharray="2.4 1.9"/>`; break;
      case 'stale': inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5" stroke-dasharray="0.6 2.6" stroke-linecap="round"/>`; break;
      case 'active': {
        const f = Math.max(0.12, Math.min(1, frac == null ? 0.5 : frac));
        inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5"/><circle cx="7" cy="7" r="1.75" fill="none" stroke="${c}" stroke-width="3.5" stroke-dasharray="${(f * 11).toFixed(2)} 11" transform="rotate(-90 7 7)"/>`;
        break;
      }
      case 'paused': inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5"/><rect x="4.9" y="4.6" width="1.4" height="4.8" rx=".5" fill="${c}"/><rect x="7.7" y="4.6" width="1.4" height="4.8" rx=".5" fill="${c}"/>`; break;
      case 'blocked': inner = `<circle cx="7" cy="7" r="6.25" fill="${c}"/><rect x="6.3" y="3.4" width="1.4" height="4.4" rx=".7" fill="${ink}"/><circle cx="7" cy="9.9" r=".85" fill="${ink}"/>`; break;
      case 'failed': inner = `<circle cx="7" cy="7" r="6.25" fill="${c}"/><path d="M4.9 4.9l4.2 4.2M9.1 4.9l-4.2 4.2" stroke="${ink}" stroke-width="1.5" stroke-linecap="round"/>`; break;
      case 'done': inner = `<circle cx="7" cy="7" r="6.25" fill="${c}"/><path d="M4.3 7.2l1.8 1.8 3.6-3.8" fill="none" stroke="${ink}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`; break;
      case 'no_change': inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5"/><path d="M4.6 7.2l1.6 1.6 3.2-3.4" fill="none" stroke="${c}" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>`; break;
      case 'canceled': inner = `<circle cx="7" cy="7" r="6.25" fill="${c}"/><path d="M4.9 4.9l4.2 4.2M9.1 4.9l-4.2 4.2" stroke="${ink}" stroke-width="1.5" stroke-linecap="round"/>`; break;
      case 'skipped': inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5" stroke-dasharray="2.4 1.9"/><path d="M4.6 9.4l4.8-4.8" stroke="${c}" stroke-width="1.4" stroke-linecap="round"/>`; break;
      default: inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5"/>`;
    }
    const label = (STATES[state] || STATES.pending).label;
    return `<svg class="${cx('sb-status', o.lg && 'sb-status--lg')}" viewBox="0 0 14 14" role="img" aria-label="${esc(label)}">${inner}</svg>`;
  }

  /* ---------- Kbd ---------- */
  function Kbd(keys) {
    track('Kbd');
    if (!keys) return '';
    const parts = Array.isArray(keys) ? keys : String(keys).split(' ');
    return `<span class="sb-kbds">${parts.map((k) => `<kbd class="sb-kbd">${esc(k)}</kbd>`).join('')}</span>`;
  }

  /* ---------- Button / IconButton ---------- */
  function Button(o) {
    track('Button');
    const v = o.variant || 'secondary';
    const cls = cx('sb-btn', v !== 'secondary' && 'sb-btn--' + v, o.size === 'sm' && 'sb-btn--sm');
    return `<button type="button" class="${cls}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}${tipAttrs(o.tip, o.tipKbd)}${o.id ? ` id="${esc(o.id)}"` : ''}>${o.icon ? Icon(o.icon) : ''}${o.label ? `<span>${esc(o.label)}</span>` : ''}${o.kbd ? Kbd(o.kbd) : ''}</button>`;
  }
  function IconButton(o) {
    track('IconButton');
    const cls = cx('sb-ibtn', o.size === 'sm' && 'sb-ibtn--sm', o.boxed && 'sb-ibtn--boxed', o.on && 'on', o.cls);
    return `<button type="button" class="${cls}"${act(o.act, o.arg)}${tipAttrs(o.label, o.kbd)}>${Icon(o.icon)}</button>`;
  }

  /* ---------- Tooltip (rendered into the shared tooltip layer) ---------- */
  function Tooltip(text, kbd) {
    track('Tooltip');
    return `<span>${esc(text)}</span>${kbd ? Kbd(kbd) : ''}`;
  }

  /* ---------- IdTag, Label, Avatar ---------- */
  function IdTag(key) { track('IdTag'); return `<span class="sb-id">${esc(key)}</span>`; }
  function Label(o) {
    track('Label');
    const style = o.color ? ` style="--c:${o.color}"` : '';
    return `<span class="${cx('sb-label', o.mono && 'sb-label--mono', o.tint && 'sb-label--tint')}"${style}${tipAttrs(o.tip)}>${o.dot === false ? '' : `<i></i>`}${o.icon ? Icon(o.icon, 'xs') : ''}${esc(o.text)}</span>`;
  }
  function Avatar(o) {
    track('Avatar');
    return `<span class="${cx('sb-avatar', o.size === 'sm' && 'sb-avatar--sm')}" style="--c:${o.color}"${tipAttrs(o.tip || o.name)}>${esc(o.initials || (o.name || '?').slice(0, 1).toUpperCase())}</span>`;
  }

  /* ---------- Progress ---------- */
  function ProgressRing(n, m, opts) {
    track('ProgressRing');
    const o = opts || {};
    const f = m ? n / m : 0;
    const C = 2 * Math.PI * 5.5;
    const color = o.color || (f >= 1 ? 'var(--st-done)' : 'var(--accent)');
    return `<span class="sb-ring"${tipAttrs(o.tip)}><svg viewBox="0 0 14 14" aria-hidden="true"><circle cx="7" cy="7" r="5.5" fill="none" stroke="var(--border-strong)" stroke-width="2"/><circle cx="7" cy="7" r="5.5" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-dasharray="${(f * C).toFixed(2)} ${C.toFixed(2)}"/></svg>${o.text === false ? '' : `${n}/${m}`}</span>`;
  }

  /* ---------- Clock: live durations tick in place, no re-render ---------- */
  function Clock(o) {
    track('Clock');
    if (o.ms == null) return '<span class="tnum">—</span>';
    return `<span class="tnum"${o.live ? ` data-tick data-ms="${o.ms}" data-t0="${o.t0}"${o.secs ? ' data-secs' : ''}` : ''}>${fmtDur(o.ms, o.secs)}</span>`;
  }

  /* ---------- Segmented, ViewTabs ---------- */
  function Segmented(o) {
    track('Segmented');
    return `<div class="sb-seg" role="tablist">${o.items.map((it) => `<button type="button" role="tab" class="${it.id === o.value ? 'on' : ''}"${act(o.act, it.id)}${it.disabled ? ' disabled' : ''}${tipAttrs(it.tip)}>${it.icon ? Icon(it.icon, 'sm') : ''}${esc(it.label)}</button>`).join('')}</div>`;
  }
  function ViewTabs(o) {
    track('ViewTabs');
    return `<div class="sb-vtabs" role="tablist">${o.items.map((it) => `<button type="button" role="tab" class="${cx('sb-vtab', it.id === o.value && 'on')}"${act(o.act, it.id)}>${it.icon ? Icon(it.icon, 'sm') : ''}${esc(it.label)}${it.count != null ? `<span class="n">${it.count}</span>` : ''}${it.attention ? '<span class="att"></span>' : ''}</button>`).join('')}</div>`;
  }

  /* ---------- Toggle, Checkbox ---------- */
  function Toggle(o) {
    track('Toggle');
    return `<button type="button" role="switch" aria-checked="${!!o.on}" class="${cx('sb-toggle', o.on && 'on')}"${act(o.act, o.arg)}${o.label ? ` aria-label="${esc(o.label)}"` : ''}></button>`;
  }
  function Checkbox(o) {
    track('Checkbox');
    return `<button type="button" role="checkbox" aria-checked="${!!o.on}" class="${cx('sb-check', o.on && 'on')}"${act(o.act, o.arg)}${o.label ? ` aria-label="${esc(o.label)}"` : ''}>${o.on ? Icon('check') : ''}</button>`;
  }

  /* ---------- Input, Field ---------- */
  function Input(o) {
    track('Input');
    const input = `<input class="${cx('sb-input', o.mono && 'sb-input--mono', o.error && 'is-error')}" id="${esc(o.id)}" value="${esc(o.value || '')}" placeholder="${esc(o.placeholder || '')}"${o.input ? ` data-input="${esc(o.input)}"` : ''} autocomplete="off" spellcheck="false"${o.style ? ` style="${esc(o.style)}"` : ''}>`;
    return o.icon ? `<div class="sb-input-wrap">${Icon(o.icon, 'sm')}${input}</div>` : input;
  }
  function Field(o) {
    track('Field');
    return `<div class="sb-field"><label for="${esc(o.for)}">${esc(o.label)}</label>${o.control}${o.help ? `<small>${o.help}</small>` : ''}</div>`;
  }

  /* ---------- Property list (sidebar of a detail view) ---------- */
  function PropertyList(rows) {
    track('PropertyList');
    return `<dl class="sb-props">${join(rows.map((r) => r && `<div class="sb-prop"><dt>${esc(r.label)}</dt><dd class="${r.act ? 'is-btn' : ''}"${act(r.act, r.arg)}${tipAttrs(r.tip, r.kbd)}>${r.value}</dd></div>`))}</dl>`;
  }

  /* ---------- Menu ---------- */
  function MenuItem(o) {
    track('MenuItem');
    return `<button type="button" class="${cx('sb-menu-item', o.hl && 'hl', o.danger && 'is-danger')}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}${o.idx != null ? ` data-idx="${o.idx}"` : ''}>${o.lead || (o.icon ? Icon(o.icon) : '')}<span class="trunc">${o.html || esc(o.label)}</span>${o.end || o.kbd || o.checked ? `<span class="end">${o.end || ''}${o.checked ? Icon('check', 'sm') : ''}${o.kbd ? Kbd(o.kbd) : ''}</span>` : ''}</button>`;
  }
  function Menu(o) {
    track('Menu');
    const body = o.items.map((it) => {
      if (it === '-') return '<div class="sb-menu-sep"></div>';
      if (it.head) return `<div class="sb-menu-head">${esc(it.head)}</div>`;
      return MenuItem(it);
    }).join('');
    return `${o.search ? `<div class="sb-menu-search">${Icon('search', 'sm')}${esc(o.search)}</div>` : ''}${body}`;
  }
  function Popover(o) {
    track('Popover');
    return `<div class="sb-layer" data-act="layer-close"></div><div class="sb-pop" role="menu" style="left:${o.x}px;top:${o.y}px${o.width ? `;width:${o.width}px` : ''}" data-pop>${o.body}</div>`;
  }

  /* ---------- Command palette ---------- */
  function CommandPalette(o) {
    track('CommandPalette');
    return `<div class="sb-modal-layer" data-act="layer-close-self"><div class="sb-cmdk" role="dialog" aria-label="Command menu">
      ${o.context ? `<div class="sb-cmdk-ctx">${o.context}</div>` : ''}
      <input class="sb-cmdk-in" id="cmdk-input" placeholder="${esc(o.placeholder || 'Type a command or search…')}" value="${esc(o.query || '')}" autocomplete="off" spellcheck="false">
      <div class="sb-cmdk-list" id="cmdk-list">${o.list}</div>
      <div class="sb-cmdk-foot"><span>${Kbd('↑ ↓')} Move</span><span>${Kbd('↵')} Run</span><span>${Kbd('esc')} Close</span></div>
    </div></div>`;
  }
  function CommandList(groups, hl) {
    track('CommandList');
    let i = 0;
    const html = groups.filter((g) => g.items.length).map((g) => `<div class="sb-menu-head">${esc(g.head)}</div>` + g.items.map((it) => MenuItem(Object.assign({}, it, { idx: i, hl: i++ === hl, act: 'cmdk-run', arg: it.id }))).join('')).join('');
    return html || `<div class="sb-cmdk-empty">No commands match</div>`;
  }

  /* ---------- Dialog ---------- */
  function Dialog(o) {
    track('Dialog');
    return `<div class="sb-modal-layer" data-act="layer-close-self"><div class="sb-dialog" role="dialog" aria-modal="true" aria-label="${esc(o.title)}">
      <div class="sb-dialog-hd">${o.icon || ''}<span>${esc(o.title)}</span></div>
      <div class="sb-dialog-bd">${o.body}</div>
      <div class="sb-dialog-ft">${join(o.actions)}</div>
    </div></div>`;
  }
  function Ledger(o) {
    track('Ledger');
    const col = (h, items, ic, color) => `<div><h4>${esc(h)}</h4><ul>${items.map((t) => `<li><span style="color:${color}">${Icon(ic, 'sm')}</span>${esc(t)}</li>`).join('')}</ul></div>`;
    return `<div class="sb-ledger">${col(o.leftTitle, o.left, 'trash', 'var(--st-failed)')}${col(o.rightTitle, o.right, 'check', 'var(--st-done)')}</div>`;
  }
  function ShortcutGrid(rows) {
    track('ShortcutGrid');
    return `<div class="sb-shortcuts">${rows.map(([label, keys]) => `<div><span>${esc(label)}</span>${Kbd(keys)}</div>`).join('')}</div>`;
  }

  /* ---------- Toast (also used for OS notifications) ---------- */
  function Toast(o) {
    track('Toast');
    const lead = o.system ? BrandMark(18) : (o.state ? StatusIcon(o.state, o.frac) : Icon(o.icon || 'checkCircle'));
    return `<div class="sb-toast" role="status">${lead}<div class="sb-toast-tx">${o.app ? `<div class="sb-toast-app"><span>${esc(o.app)}</span><span>${esc(o.when || 'now')}</span></div>` : ''}<div class="sb-toast-ti">${esc(o.title)}</div>${o.body ? `<div class="sb-toast-bd">${esc(o.body)}</div>` : ''}</div>${o.actions ? `<div class="sb-toast-ac">${join(o.actions)}</div>` : ''}</div>`;
  }

  function ToastStack(items) { track('ToastStack'); return `<div class="sb-toasts">${join(items)}</div>`; }

  /* ---------- Callout ---------- */
  const TONES = { warn: ['var(--st-blocked)', 'warn'], error: ['var(--st-failed)', 'alert'], info: ['var(--st-planning)', 'info'], success: ['var(--st-done)', 'checkCircle'], neutral: ['var(--fg-faint)', 'info'] };
  function Callout(o) {
    track('Callout');
    const [c, ic] = TONES[o.tone || 'info'];
    return `<div class="sb-callout" style="--c:${c}">${Icon(o.icon || ic)}<div class="sb-callout-tx">${o.title ? `<div class="sb-callout-ti">${esc(o.title)}</div>` : ''}${o.body ? `<div class="sb-callout-bd">${o.body}</div>` : ''}${o.actions ? `<div class="sb-callout-ac">${join(o.actions)}</div>` : ''}</div></div>`;
  }
  function Hint(o) {
    track('Hint');
    const [c, ic] = TONES[o.tone || 'neutral'];
    return `<div class="sb-hint" style="--c:${c}">${Icon(o.icon || ic)}<span>${o.html || esc(o.text)}</span></div>`;
  }

  /* ---------- Empty state ---------- */
  function EmptyState(o) {
    track('EmptyState');
    return `<div class="sb-empty"><div class="sb-empty-ic">${Icon(o.icon || 'inbox', 'lg')}</div><div class="sb-empty-ti">${esc(o.title)}</div>${o.body ? `<div class="sb-empty-bd">${o.body}</div>` : ''}${o.actions ? `<div class="sb-empty-ac">${join(o.actions)}</div>` : ''}</div>`;
  }

  /* ---------- Shell: sidebar ---------- */
  function SidebarItem(o) {
    track('SidebarItem');
    const lead = o.lead || (o.icon ? Icon(o.icon) : '');
    const end = o.attention ? `<span class="att">${o.attention}</span>` : (o.count != null ? `<span class="n">${o.count}</span>` : '');
    return `<button type="button" class="${cx('sb-nav', o.on && 'on', o.child && 'sb-nav--child')}"${act(o.act, o.arg)}${tipAttrs(o.tip, o.kbd)}>${lead}<span class="trunc">${esc(o.label)}</span>${end}</button>`;
  }
  function SidebarSection(o) {
    track('SidebarSection');
    return `<button type="button" class="${cx('sb-nav-sec', o.closed && 'closed')}"${act(o.act, o.arg)}>${esc(o.label)}${Icon('chevronDown')}</button>${o.closed ? '' : join(o.items)}`;
  }
  function RepoIcon(repo) {
    track('RepoIcon');
    return `<span class="sb-repo-ic" style="--c:${repo.color}">${esc(repo.name.slice(0, 1))}</span>`;
  }
  function Sidebar(o) {
    track('Sidebar');
    return `<nav class="sb-side" aria-label="Main">${o.top}${join(o.items)}<div class="sb-side-foot">${join(o.footer)}</div></nav>`;
  }
  function WorkspaceSwitcher(o) {
    track('WorkspaceSwitcher');
    return `<div class="sb-ws"><span class="sb-ws-btn">${BrandMark(20)}<span class="trunc">${esc(o.name)}</span></span><span class="l-grow"></span>${join(o.actions)}</div>`;
  }

  /* ---------- Shell: view header, filter bar ---------- */
  function ViewHeader(o) {
    track('ViewHeader');
    const crumbs = o.crumbs.map((c, i) => {
      const last = i === o.crumbs.length - 1;
      const inner = `${c.lead || ''}${esc(c.label)}`;
      return (i ? Icon('chevronRight') : '') + (last ? `<span class="cur">${inner}</span>` : `<button type="button"${act(c.act, c.arg)}>${inner}</button>`);
    }).join('');
    return `<header class="sb-vh">${IconButton({ icon: 'panelLeft', label: 'Toggle sidebar', kbd: '[', act: 'toggle-side', cls: 'sb-side-toggle' })}<div class="sb-crumbs">${crumbs}</div>${o.middle ? `<div class="sb-vh-mid">${o.middle}</div>` : ''}<div class="sb-vh-end">${join(o.actions)}</div></header>`;
  }
  function Pager(o) {
    track('Pager');
    return `<span class="sb-pager">${o.index + 1} / ${o.total}${IconButton({ icon: 'chevronUp', label: 'Previous', kbd: 'K', act: 'pager', arg: -1, size: 'sm' })}${IconButton({ icon: 'chevronDown', label: 'Next', kbd: 'J', act: 'pager', arg: 1, size: 'sm' })}</span>`;
  }
  /* ---------- List ---------- */
  function GroupHeader(o) {
    track('GroupHeader');
    return `<div class="sb-gh">${o.lead || ''}<span>${esc(o.label)}</span><span class="n">${o.count}</span>${o.end ? `<span class="end">${join(o.end)}</span>` : ''}</div>`;
  }
  /* One list row: issues, goals, phases, history entries. */
  function ListRow(o) {
    track('ListRow');
    return `<div class="${cx('sb-row', o.compact && 'sb-row--compact', o.focus && 'focus', o.selected && 'sel', o.faded && 'faded')}" role="row"${act(o.act, o.arg)}${o.ctx ? ` data-ctx="${esc(o.ctx)}"` : ''}${o.rowIdx != null ? ` data-row="${o.rowIdx}"` : ''}>
      ${o.selectable ? Checkbox({ on: o.selected, act: 'select', arg: o.arg, label: 'Select' }) : ''}
      ${o.id ? `<span class="sb-row-id">${IdTag(o.id)}</span>` : ''}${o.status || ''}
      <span class="sb-row-ti"><b>${esc(o.title)}</b>${o.sub ? `<span>${o.sub}</span>` : ''}</span>
      <span class="sb-row-meta">${join(o.meta)}</span>${o.time != null ? `<span class="sb-row-time">${o.time}</span>` : ''}
    </div>`;
  }
  /* ---------- Detail document ---------- */
  function DocHeader(o) {
    track('DocHeader');
    return `<div class="sb-doc-hd">${o.eyebrow ? `<div class="l-row g-2 l-wrap">${join(o.eyebrow)}</div>` : ''}<h1 class="sb-doc-ti">${esc(o.title)}</h1>${o.sub ? `<div class="sb-doc-sub">${o.sub}</div>` : ''}${o.actions ? `<div class="l-row g-2 l-wrap">${join(o.actions)}</div>` : ''}</div>`;
  }
  function Section(o) {
    track('Section');
    return `<section class="sb-sec"><div class="sb-sec-hd">${o.lead || ''}<span>${esc(o.title)}</span>${o.count != null ? `<span class="n">${o.count}</span>` : ''}${o.end ? `<span class="end">${join(o.end)}</span>` : ''}</div>${o.body}</section>`;
  }
  function Box(children) { track('Box'); return `<div class="sb-box">${join(children)}</div>`; }
  function MetricStrip(items) {
    track('MetricStrip');
    return `<div class="sb-metrics">${items.filter(Boolean).map((m) => `<div class="sb-metric"><span>${esc(m.label)}</span><b>${m.value}${m.unit ? `<small>${esc(m.unit)}</small>` : ''}</b>${m.note ? `<em>${m.note}</em>` : ''}</div>`).join('')}</div>`;
  }
  function SubtaskRow(o) {
    track('SubtaskRow');
    return `<div class="${cx('sb-sub', o.current && 'is-current', o.state === 'pending' && 'is-pending')}"><div class="sb-sub-row">${IdTag(o.num)}${StatusIcon(o.state, o.frac)}<b>${esc(o.title)}</b>${join(o.meta)}</div>${o.body ? `<div class="sb-sub-body">${o.body}</div>` : ''}</div>`;
  }
  function PhasePipeline(steps) {
    track('PhasePipeline');
    return `<div class="sb-pipe">${steps.map((s, i) => (i && !s.goalStart ? '<span class="sb-pipe-sep"></span>' : (s.goalStart ? '<span style="width:10px"></span>' : '')) + `<span class="${cx('sb-step', s.state, s.goal && 'goal')}"${tipAttrs(s.tip)}>${s.state === 'done' ? Icon('check') : ''}${esc(s.label)}${s.count ? ` · ${esc(s.count)}` : ''}</span>`).join('')}</div>`;
  }
  function PaneSection(o) {
    track('PaneSection');
    return `<div class="sb-pane-sec">${o.title ? `<div class="sb-pane-ti"><span>${esc(o.title)}</span>${o.end || ''}</div>` : ''}${o.body}</div>`;
  }

  /* ---------- Spec tree ---------- */
  function TreeRow(o) {
    track('TreeRow');
    const caret = o.expandable ? `<span class="caret">${Icon('chevronRight')}</span>` : '<span class="caret"></span>';
    return `<div class="${cx('sb-tree-row', o.open && 'open', o.focus && 'focus', o.file && 'is-file', o.muted && 'is-muted')}" style="--lvl:${o.level || 0}"${act(o.act, o.arg)} data-row="${o.rowIdx}">${caret}${o.lead}${o.id ? IdTag(o.id) : ''}<b>${esc(o.title)}</b>${join(o.meta)}</div>`;
  }

  /* ---------- Settings ---------- */
  function SettingsHeader(o) { track('SettingsHeader'); return `<div class="sb-set-hd"><h1>${esc(o.title)}</h1>${o.body ? `<p>${o.body}</p>` : ''}</div>`; }
  function SettingsGroup(o) {
    track('SettingsGroup');
    return `<section class="sb-sg">${o.title ? `<div class="sb-sg-hd">${esc(o.title)}${o.end ? `<span class="end">${join(o.end)}</span>` : ''}</div>` : ''}<div class="sb-sg-box">${join(o.rows)}</div>${o.foot ? `<div class="t-faint" style="font-size:var(--t-xs)">${o.foot}</div>` : ''}</section>`;
  }
  function SettingRow(o) {
    track('SettingRow');
    return `<div class="${cx('sb-sr', o.stack && 'sb-sr--stack')}">${o.label ? `<div class="sb-sr-tx"><b>${o.labelHtml || esc(o.label)}</b>${o.desc ? `<span>${o.desc}</span>` : ''}</div>` : ''}${o.control ? `<div class="sb-sr-ctl">${o.control}</div>` : ''}</div>`;
  }
  function TierBadge(tier) {
    track('TierBadge');
    const r = tier === 'reasoning';
    return `<span class="${cx('sb-tier', r ? 'r' : 'i')}"${tipAttrs(r ? 'Reasoning tier' : 'Implementation tier')}>${r ? 'R' : 'I'}</span>`;
  }
  function ConfigRow(o) {
    track('ConfigRow');
    return `<div class="${cx('sb-cfg', o.open && 'open', o.override && 'is-override')}"${act(o.act, o.arg)}><span class="ph">${esc(o.phase)}</span>${o.tier ? TierBadge(o.tier) : ''}<span class="val">${esc(o.value)}</span>${join(o.tags)}${Icon(o.open ? 'chevronUp' : 'pencil', 'sm')}</div>${o.editor ? `<div class="sb-cfg-ed">${o.editor}</div>` : ''}`;
  }

  /* ---------- Side pane (Specs) ---------- */
  function SidePane(o) {
    track('SidePane');
    return `<aside class="sb-sidepane" aria-label="${esc(o.title)}"><div class="sb-sidepane-hd"><b>${esc(o.title)}</b>${o.head || ''}<span class="l-grow"></span>${join(o.actions)}</div>${o.body}</aside>`;
  }

  /* ---------- Reason block: long runtime text with copy ---------- */
  function ReasonBlock(o) {
    track('ReasonBlock');
    return `<div class="sb-reason" style="--c:${o.color || 'var(--fg-faint)'}"><div class="sb-reason-hd"><span>${esc(o.label)}</span>${IconButton({ icon: 'copy', label: 'Copy reason', act: 'copy', arg: o.text, size: 'sm' })}</div><p>${esc(o.text)}</p></div>`;
  }

  /* ---------- Checklist: prerequisites the operator confirms ---------- */
  function Checklist(o) {
    track('Checklist');
    return `<div class="sb-checklist">${o.items.map((it) => `<div class="sb-checklist-row">${Checkbox({ on: it.checked, act: o.act, arg: it.id, label: it.text })}<span>${esc(it.text)}</span></div>`).join('')}</div>`;
  }

  /* ---------- Finding row: review, QA and assessment findings ---------- */
  const SEVERITY = { Blocker: 'var(--st-failed)', Major: 'var(--st-blocked)', Minor: 'var(--fg-faint)', High: 'var(--st-failed)', Medium: 'var(--st-blocked)', Low: 'var(--fg-faint)' };
  function FindingRow(o) {
    track('FindingRow');
    return `<div class="sb-finding">${o.severity ? `<span class="sb-finding-sev" style="--c:${SEVERITY[o.severity] || 'var(--fg-faint)'}">${esc(o.severity)}</span>` : ''}${o.ref ? IdTag(o.ref) : ''}<div class="sb-finding-tx"><b>${esc(o.title)}</b>${o.meta ? `<span>${o.meta}</span>` : ''}</div>${o.state ? Label({ text: o.state, color: o.stateColor || 'var(--fg-faint)', dot: true }) : ''}</div>`;
  }

  /* ---------- Terminal pane ---------- */
  function TerminalPane(o) {
    track('TerminalPane');
    const tabs = o.tabs.map((t) => `<div class="${cx('sb-ttab', t.id === o.activeId && 'on')}"${act('term-tab', t.id)} data-dbl="term-rename">${o.renaming === t.id ? `<input class="sb-ttab-in" id="term-rename-in" value="${esc(t.customName || t.label)}" data-input="term-rename" aria-label="Tab name">` : `<span>${esc(t.customName || t.label)}</span>`}${IconButton({ icon: 'x', label: `Close ${t.customName || t.label} tab`, act: 'term-close-tab', arg: t.id, size: 'sm' })}</div>`).join('');
    return `<section class="sb-term" style="height:${o.height}px" aria-label="Terminal">
      <div class="sb-term-grip" data-grip aria-label="Resize terminal"></div>
      <div class="sb-term-bar">${Icon('terminal', 'sm')}<div class="sb-term-tabs">${tabs}</div>${IconButton({ icon: 'plus', label: 'New terminal tab', kbd: '⌃ ⇧ `', act: 'term-new', size: 'sm' })}<span class="l-grow"></span>${IconButton({ icon: 'chevronUp', label: 'Make terminal taller', act: 'term-resize', arg: 80, size: 'sm' })}${IconButton({ icon: 'chevronDown', label: 'Make terminal shorter', act: 'term-resize', arg: -80, size: 'sm' })}${IconButton({ icon: 'x', label: 'Close terminal', kbd: '⌃ `', act: 'term-toggle', size: 'sm' })}</div>
      <div class="sb-term-out" data-scroll="term">${o.body}</div></section>`;
  }

  /* ---------- Tray popover ---------- */
  function TrayPopover(o) {
    track('TrayPopover');
    return `<div class="sb-tray"><div class="sb-tray-hd">${BrandMark(18)}<span>Skill Bill</span></div><div class="sb-tray-stats">${o.stats.map((x) => `<div><b style="color:${x.color || 'var(--fg)'}">${esc(x.value)}</b><span>${esc(x.label)}</span></div>`).join('')}</div><div class="sb-tray-tabs">${o.tabs}</div><div class="sb-tray-body">${join(o.rows)}</div><div class="sb-tray-ft">${join(o.footer)}</div></div>`;
  }

  window.SB = {
    esc, cx, join, fmtDur, track, inScope, usage, STATES, ICONS: Object.keys(P),
    Icon, BrandMark, StatusIcon, Kbd, Button, IconButton, Tooltip, IdTag, Label, Avatar,
    ProgressRing, Clock, Segmented, ViewTabs, Toggle, Checkbox, Input, Field,
    PropertyList, MenuItem, Menu, Popover, CommandPalette, CommandList, Dialog, Ledger, ShortcutGrid,
    Toast, ToastStack, Callout, Hint, EmptyState, SidebarItem, SidebarSection, RepoIcon, Sidebar, WorkspaceSwitcher,
    ViewHeader, Pager, GroupHeader, ListRow, SidePane, ReasonBlock, Checklist, FindingRow, TerminalPane,
    DocHeader, Section, Box, MetricStrip, SubtaskRow, PhasePipeline,
    PaneSection, TreeRow, SettingsHeader, SettingsGroup, SettingRow, TierBadge,
    ConfigRow, TrayPopover,
  };
})();
