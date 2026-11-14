/* Sona — Linear study · component library
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
    image: '<rect width="18" height="18" x="3" y="3" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21"/>',
    ban: '<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
    undo: '<path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"/>',
    message: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
    arrowRight: '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
    chevronsUpDown: '<path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/>',
    phone: '<rect width="14" height="20" x="5" y="2" rx="2"/><path d="M12 18h.01"/>',
    emulator: '<path d="M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8"/><path d="M10 19v-3.96 3.15"/><path d="M7 19h5"/><rect width="6" height="10" x="16" y="12" rx="2"/>',
    devices: '<rect width="10" height="14" x="3" y="8" rx="2"/><path d="M5 4a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2h-2.4"/><path d="M8 18h.01"/>',
    wifi: '<path d="M12 20h.01"/><path d="M2 8.82a15 15 0 0 1 20 0"/><path d="M5 12.859a10 10 0 0 1 14 0"/><path d="M8.5 16.429a5 5 0 0 1 7 0"/>',
    minus: '<path d="M5 12h14"/>',
    /* Monitor run glyphs (design/monitor-run): a repair, a push, a merge, a PR field edit and the CI loop. */
    wrench: '<path d="M14 6a4 4 0 0 0 5 5l-9 9-3-3 9-9a4 4 0 0 1-2-2z"/>',
    upload: '<path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M4 20h16"/>',
    merge: '<circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 7v10"/><path d="M6 7c0 4 5 5 10 5"/>',
    tag: '<path d="M3 12V4h8l10 10-8 8z"/><circle cx="7.5" cy="7.5" r="1"/>',
    loop: '<path d="m17 2 3 3-3 3"/><path d="M4 11V9a4 4 0 0 1 4-4h12"/><path d="m7 22-3-3 3-3"/><path d="M20 13v2a4 4 0 0 1-4 4H4"/>',
    gap: '<circle cx="12" cy="12" r="8" stroke-dasharray="3 3.2"/>',
    /* QA results glyphs, drawn as QA results design draws them: an open finding, something left behind, a report or run
     * details row, and the check, file, image, copy and open marks on its rows and chips. */
    video: '<path d="m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5"/><rect x="2" y="6" width="14" height="12" rx="2"/>',
    videoOff: '<path d="M10.66 6H14a2 2 0 0 1 2 2v2.5l5.248-3.062A.5.5 0 0 1 22 7.87v8.196"/><path d="M16 16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2"/><path d="m2 2 20 20"/>',
    qaBug: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4.5"/><path d="M12 16h.01"/>',
    qaLeft: '<path d="M12 4l9 16H3z"/><path d="M12 10v4"/><path d="M12 17h.01"/>',
    qaDoc: '<path d="M6 4h12v16H6z"/><path d="M9 9h6M9 13h6M9 17h3"/>',
    qaCheck: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    qaFile: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
    qaImage: '<rect x="4" y="5" width="16" height="14" rx="2"/><circle cx="9" cy="10" r="1.5"/><path d="M20 16l-5-5-8 8"/>',
    qaCopy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/>',
    qaOpen: '<path d="M14 4h6v6"/><path d="M20 4l-9 9"/><path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4"/>',
    star: '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"/>',
    pr: '<circle cx="6" cy="6" r="2.5"/><circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="M6 8.5v7M18 15.5V9a3 3 0 0 0-3-3h-4"/><path d="m13 3.5-2.5 2.5L13 8.5"/>',
    list: '<path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01"/>',
    flag: '<path d="M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528"/>',
  };
  function Icon(name, size) {
    track('Icon');
    return `<svg class="${cx('sb-icon', size && 'sb-icon--' + size)}" viewBox="0 0 24 24" aria-hidden="true">${P[name] || ''}</svg>`;
  }

  /* ---------- Brand mark ---------- */
  let brandMarkId = 0;
  function BrandMark(size) {
    track('BrandMark');
    const s = size || 20;
    const clip = `sona-ping-${++brandMarkId}`;
    return `<svg class="sb-mark" style="width:${s}px;height:${s}px" viewBox="0 0 100 100" aria-hidden="true">
      <defs><clipPath id="${clip}"><circle cx="50" cy="50" r="44"/></clipPath></defs>
      <g clip-path="url(#${clip})" fill="none" stroke="currentColor">
        <circle cx="18.9" cy="81.1" r="13" stroke-width="26"/>
        <circle cx="18.9" cy="81.1" r="47" stroke-width="22"/>
        <circle cx="18.9" cy="81.1" r="80" stroke-width="22"/>
      </g></svg>`;
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
    const c = o.monochrome ? 'var(--fg)' : `var(--st-${(STATES[state] || STATES.pending).tone})`;
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
      case 'canceled': inner = `<circle cx="7" cy="7" r="5.5" fill="none" stroke="${c}" stroke-width="1.5"/><path d="M4.9 4.9l4.2 4.2M9.1 4.9l-4.2 4.2" stroke="${c}" stroke-width="1.5" stroke-linecap="round"/>`; break;
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
    return `<button type="button" class="${cls}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}${tipAttrs(o.tip, o.tipKbd)}${o.id ? ` id="${esc(o.id)}"` : ''}>${o.icon ? Icon(o.icon) : ''}${o.label ? `<span>${esc(o.label)}</span>` : ''}${o.trail ? Icon(o.trail) : ''}${o.count != null ? `<span class="sb-btn-n">${esc(o.count)}</span>` : ''}${o.kbd ? Kbd(o.kbd) : ''}</button>`;
  }
  function IconButton(o) {
    track('IconButton');
    const cls = cx('sb-ibtn', o.size === 'sm' && 'sb-ibtn--sm', o.boxed && 'sb-ibtn--boxed', o.on && 'on', o.cls);
    return `<button type="button" class="${cls}"${act(o.act, o.arg)}${tipAttrs(o.label, o.kbd)}>${Icon(o.icon)}</button>`;
  }

  /* ---------- Tooltip (rendered into the shared tooltip layer) ---------- */
  function Tooltip(text, kbd, o) {
    track('Tooltip');
    const inner = `<span>${esc(text)}</span>${kbd ? Kbd(kbd) : ''}`;
    /* `o.shown` draws the bubble in place (the catalog); otherwise the content goes into the floating tip. */
    return o && o.shown ? `<span class="sb-tip is-shown">${inner}</span>` : inner;
  }
  /* A faint caption above a group of menu rows: "Finished in the last hour", a command menu group. */
  function MenuHead(text) { track('MenuHead'); return `<div class="sb-menu-head">${esc(text)}</div>`; }

  /* ---------- IdTag, Label, Avatar ---------- */
  function IdTag(key) { track('IdTag'); return `<span class="sb-id">${esc(key)}</span>`; }
  /* Machine ids (workflow, invocation, commit, evidence reference): first 8 characters plus an ellipsis, or whole when that
   * saves nothing; full value in the tooltip and on the copy button. `shown` is the part to shorten when a prefix repeats the row. */
  const shortId = (id, keep) => { const k = keep || 8; const s = String(id); return s.length > k + 1 ? s.slice(0, k) + '…' : s; };
  function ShortId(o) {
    track('ShortId');
    return `<span class="sb-sid"><span class="sb-sid-tx"${tipAttrs(o.value)}>${esc(shortId(o.shown != null ? o.shown : o.value))}</span>${IconButton({ icon: 'copy', label: `Copy ${o.label || 'id'}`, act: 'copy', arg: o.value, size: 'sm' })}</span>`;
  }
  /* Shown in the view header when the last refresh failed and the screen keeps its older data. */
  function StaleIndicator() { track('StaleIndicator'); return `<span class="sb-stale"${tipAttrs('Showing stale work data')}>Stale</span>`; }
  function Label(o) {
    track('Label');
    const style = o.color ? ` style="--c:${o.color}"` : '';
    return `<span class="${cx('sb-label', o.mono && 'sb-label--mono', o.tint && 'sb-label--tint')}"${style}${tipAttrs(o.tip)}>${o.dot === false ? '' : `<i></i>`}${o.icon ? Icon(o.icon, 'xs') : ''}${esc(o.text)}</span>`;
  }
  function Avatar(o) {
    track('Avatar');
    return `<span class="${cx('sb-avatar', o.size === 'sm' && 'sb-avatar--sm', o.solid && 'sb-avatar--solid')}" style="--c:${o.color}"${tipAttrs(o.tip || o.name)}>${esc(o.initials || (o.name || '?').slice(0, 1).toUpperCase())}</span>`;
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
    return `<div class="sb-seg" role="tablist">${o.items.map((it) => `<button type="button" role="tab" aria-selected="${it.id === o.value}" class="${it.id === o.value ? 'on' : ''}"${act(o.act, it.arg == null ? it.id : it.arg)}${it.disabled ? ' disabled' : ''}${tipAttrs(it.tip)}>${it.icon ? Icon(it.icon, 'sm') : ''}${it.dot ? '<span class="sb-qlive-dot" aria-hidden="true"></span>' : ''}${esc(it.label)}</button>`).join('')}</div>`;
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
  /* `mixed` (part of a group selected) shows a dash; `disabled` (a marked criterion) draws a dashed box, with a faint dash
   * when `marked`. */
  function Checkbox(o) {
    track('Checkbox');
    return `<button type="button" role="checkbox" aria-checked="${o.mixed ? 'mixed' : !!o.on}" class="${cx('sb-check', (o.on || o.mixed) && 'on', o.disabled && 'is-off')}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}${o.label ? ` aria-label="${esc(o.label)}"` : ''}${o.tip ? ` data-tip="${esc(o.tip)}"` : ''}>${o.mixed || o.marked ? Icon('minus') : o.on ? Icon('check') : ''}</button>`;
  }

  /* ---------- Input, Field ---------- */
  function Input(o) {
    track('Input');
    const input = `<input class="${cx('sb-input', o.mono && 'sb-input--mono', o.error && 'is-error')}" id="${esc(o.id)}" value="${esc(o.value || '')}" placeholder="${esc(o.placeholder || '')}"${o.input ? ` data-input="${esc(o.input)}"` : ''} autocomplete="off" spellcheck="false"${o.style ? ` style="${esc(o.style)}"` : ''}>`;
    const wrapped = o.icon ? `<div class="sb-input-wrap">${Icon(o.icon, 'sm')}${input}</div>` : input;
    /* `width` sizes the input's box (a filter in a header, a short numeric field). */
    return o.width ? `<div class="sb-input-box" style="width:${esc(o.width)}">${wrapped}</div>` : wrapped;
  }
  function Field(o) {
    track('Field');
    return `<div class="sb-field"${o.width ? ` style="width:${esc(o.width)}"` : ''}><label for="${esc(o.for)}">${esc(o.label)}</label>${o.control}${o.help ? `<small>${o.help}</small>` : ''}</div>`;
  }

  /* ---------- Property list (sidebar of a detail view) ---------- */
  /* One 28px line per property: a label column sized to the longest label (--prop-label-w, ellipsis as a fallback) and a
   * one-line value. `mono` sets the label in mono for phase names. `sub` goes under the value (a Breakdown). Rows whose
   * value is null, empty or zero are left out by the caller, never drawn blank. */
  function PropertyList(rows) {
    track('PropertyList');
    return `<dl class="sb-props">${join(rows.map((r) => r && `<div class="sb-prop"><dt class="${r.mono ? 'mono' : ''}" title="${esc(r.label)}">${esc(r.label)}</dt><dd class="${r.act ? 'is-btn' : ''}"${act(r.act, r.arg)}${tipAttrs(r.tip, r.kbd)}>${r.value}</dd>${r.sub ? `<div class="sb-prop-sub">${r.sub}</div>` : ''}</div>`))}</dl>`;
  }
  /* A count broken down by key (phase runs per phase, relaunch causes): one 22px row per key, readable sans labels within 192px,
   * the count right-aligned and tabular, largest first. At most `max` rows (default 4), then a "+N more" ghost toggle. */
  function Breakdown(o) {
    track('Breakdown');
    const max = o.max || 4;
    const rows = o.rows.filter((r) => r[1] > 0).sort((a, b) => b[1] - a[1]);
    const shown = o.open ? rows : rows.slice(0, max);
    const more = rows.length > max ? `<button type="button" class="sb-bd-more"${act(o.act, o.arg)} aria-expanded="${!!o.open}">${o.open ? 'Show less' : `+${rows.length - max} more`}</button>` : '';
    return `<div class="sb-bd">${shown.map(([k, n]) => `<div class="sb-bd-row"><span title="${esc(k)}">${esc(k)}</span><b>${n}</b></div>`).join('')}${more}</div>`;
  }

  /* ---------- Menu ---------- */
  function MenuItem(o) {
    track('MenuItem');
    /* `sub` is a second line under the label: why the item is off, or the path an item acts on. `key` is the letter that
     * runs it while the menu has focus (main.js menuKeys). */
    const label = o.sub ? `<span class="sb-menu-tx"><span class="trunc">${o.html || esc(o.label)}</span><small>${esc(o.sub)}</small></span>` : `<span class="trunc">${o.html || esc(o.label)}</span>`;
    return `<button type="button" class="${cx('sb-menu-item', o.hl && 'hl', o.danger && 'is-danger', o.sub && 'has-sub')}"${act(o.act, o.arg)}${o.disabled ? ' disabled' : ''}${o.idx != null ? ` data-idx="${o.idx}"` : ''}${o.key ? ` data-key="${esc(o.key)}"` : ''}>${o.lead || (o.icon ? Icon(o.icon) : '')}${label}${o.end || o.kbd || o.checked ? `<span class="end">${o.end || ''}${o.checked ? Icon('check', 'sm') : ''}${o.kbd ? Kbd(o.kbd) : ''}</span>` : ''}</button>`;
  }
  function Menu(o) {
    track('Menu');
    const body = o.items.map((it) => {
      if (it === '-') return '<div class="sb-menu-sep"></div>';
      if (it.head) return MenuHead(it.head);
      return MenuItem(it);
    }).join('');
    return `${o.search ? `<div class="sb-menu-search">${Icon('search', 'sm')}${esc(o.search)}</div>` : ''}${body}`;
  }
  function Popover(o) {
    track('Popover');
    /* `flush` drops the 4px padding and the 340px cap for panels that lay out their own content (repository picker, queue). */
    return `<div class="sb-layer" data-act="layer-close"></div><div class="${cx('sb-pop', o.flush && 'sb-pop--flush', o.cls)}" role="${o.role || 'menu'}" style="left:${o.x}px;top:${o.y}px${o.width ? `;width:${o.width}px` : ''}" data-pop>${o.body}</div>`;
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
    const html = groups.filter((g) => g.items.length).map((g) => MenuHead(g.head) + g.items.map((it) => MenuItem(Object.assign({}, it, { idx: i, hl: i++ === hl, act: 'cmdk-run', arg: it.id }))).join('')).join('');
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
  /* A toast: lead icon, title, body, optional actions, and × (`close`: { act, arg }) to dismiss it before it times out. */
  function Toast(o) {
    track('Toast');
    const lead = o.system ? BrandMark(18) : (o.state ? StatusIcon(o.state, o.frac) : Icon(o.icon || 'checkCircle'));
    return `<div class="sb-toast" role="status">${lead}<div class="sb-toast-tx">${o.app ? `<div class="sb-toast-app"><span>${esc(o.app)}</span><span>${esc(o.when || 'now')}</span></div>` : ''}<div class="sb-toast-ti">${esc(o.title)}</div>${o.body ? `<div class="sb-toast-bd">${esc(o.body)}</div>` : ''}</div>${o.actions ? `<div class="sb-toast-ac">${join(o.actions)}</div>` : ''}${o.close ? IconButton({ icon: 'x', label: 'Dismiss', act: o.close.act, arg: o.close.arg, size: 'sm', cls: 'sb-toast-x' }) : ''}</div>`;
  }

  function ToastStack(items) { track('ToastStack'); return `<div class="sb-toasts">${join(items)}</div>`; }

  /* ---------- Callout ---------- */
  const TONES = { warn: ['var(--st-blocked)', 'warn'], error: ['var(--st-failed)', 'alert'], info: ['var(--st-planning)', 'info'], success: ['var(--st-done)', 'checkCircle'], neutral: ['var(--fg-faint)', 'info'] };
  function Callout(o) {
    track('Callout');
    const [c, ic] = TONES[o.tone || 'info'];
    /* `tinted` sets the title in the tone color (a blocked plan run's "Subtask 4 needs your decision"). */
    return `<div class="sb-callout" style="--c:${c}">${Icon(o.icon || ic)}<div class="sb-callout-tx">${o.title ? `<div class="${cx('sb-callout-ti', o.tinted && 'is-tinted')}">${esc(o.title)}</div>` : ''}${o.body ? `<div class="sb-callout-bd">${o.body}</div>` : ''}${o.actions ? `<div class="sb-callout-ac">${join(o.actions)}</div>` : ''}</div></div>`;
  }
  function Hint(o) {
    track('Hint');
    const [c, ic] = TONES[o.tone || 'neutral'];
    return `<div class="sb-hint" style="--c:${c}">${Icon(o.icon || ic)}${o.fold ? ExpandableText({ text: o.text, html: o.html }) : `<span>${o.html || esc(o.text)}</span>`}</div>`;
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
    return `<nav class="sb-side" aria-label="Main">${o.top}<div class="sb-side-items" data-scroll="sidebar">${join(o.items)}</div><div class="sb-side-foot">${join(o.footer)}</div></nav>`;
  }

  /* Green while most of the window is left, yellow around half, red as it runs out. Mixed in Oklab like the app's lerp. */
  function usageColor(used) {
    const left = Math.max(0, Math.min(100, 100 - used));
    return left >= 50
      ? `color-mix(in oklab, var(--st-done) ${(left - 50) * 2}%, var(--data-1))`
      : `color-mix(in oklab, var(--data-1) ${left * 2}%, var(--st-failed))`;
  }
  /* One window. The card puts the countdown after the label; `detailed` rows give the reset its own line with the clock time. */
  function UsageWindow(o) {
    track('UsageWindow');
    const used = Math.max(0, Math.min(100, o.used));
    const label = !o.detailed && o.resetIn ? `${o.label} · ${o.resetIn}` : o.label;
    const reset = o.detailed && o.resetIn ? (o.resetIn === 'now' ? 'Resetting' : ['Resets in ' + o.resetIn, o.resetAt].filter(Boolean).join(' · ')) : '';
    return `<span class="sb-usage-window" style="--usage-color:${usageColor(o.used)}"><span class="sb-usage-line"><span class="trunc">${esc(label)}</span><b>${esc(o.used)}%</b></span><span class="sb-usage-track" role="meter" aria-label="${esc(o.label + ', ' + o.used + '% used')}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${used}"><i style="width:${used}%"></i></span>${reset ? `<span class="sb-usage-reset">${esc(reset)}</span>` : ''}</span>`;
  }

  /* Fixed height: the agent name and two window slots, whatever the state. A message fills the first slot when there are no windows. */
  function UsageCard(o) {
    track('UsageCard');
    const windows = (o.windows || []).slice(0, 2);
    const slots = [0, 1].map((i) => `<span class="sb-usage-slot">${windows[i] ? UsageWindow(windows[i]) : i === 0 && o.message ? `<span class="sb-usage-empty">${esc(o.message)}</span>` : ''}</span>`).join('');
    return `<button type="button" id="${esc(o.id || 'usage-card')}" class="sb-usage-card"${act(o.act, o.arg)} aria-haspopup="dialog" aria-expanded="${!!o.expanded}" aria-label="${esc(o.provider + ' usage limits. View details')}"><span class="sb-usage-provider" style="--c:${o.color || 'var(--fg)'}"><b>${esc(o.provider)}</b>${Icon('chevronRight', 'xs')}</span>${slots}</button>`;
  }

  /* Provider tabs, every window with when it resets, the default agent and Refresh / Dashboard. Nothing else. */
  function UsagePanel(o) {
    track('UsagePanel');
    const id = esc(o.id || 'usage');
    const tabs = o.providers.map((p) => `<button type="button" id="${id}-provider-${esc(p)}"${act('usage-provider', p)} aria-pressed="${o.provider === p}" class="${o.provider === p ? 'on' : ''}" style="--c:${(o.colors || {})[p] || 'var(--fg)'}">${esc(p)}</button>`).join('');
    const body = (o.windows || []).length
      ? join(o.windows.map((w) => UsageWindow(Object.assign({}, w, { detailed: true }))))
      : `<p class="sb-usage-empty" role="status">${esc(o.message || '')}</p>`;
    const agents = (o.agents || []).map((a) => `<button type="button" id="${id}-default-${esc(a.name)}"${act('usage-default-agent', a.name)} role="radio" aria-checked="${o.defaultAgent === a.name}" class="${o.defaultAgent === a.name ? 'on' : ''}" style="--c:${a.color}"${tipAttrs(o.defaultAgent === a.name ? 'Default for new work. Click to clear' : 'Use ' + a.label + ' for new chats, goals and phases')}>${Avatar({ name: a.name, initials: a.initials, color: a.color, size: 'sm', tip: a.label })}<span>${esc(a.label)}</span></button>`).join('');
    const defaults = agents ? `<div class="sb-usage-default"><span>Default agent</span><div class="sb-usage-tabs sb-usage-agents" role="radiogroup" aria-label="Default agent for new chats, goals and phases">${agents}</div></div>` : '';
    return `<section class="sb-usage-panel" aria-labelledby="${id}-title"><div class="sb-usage-panel-head"><b id="${id}-title">Usage limits</b>${IconButton({ icon: 'x', label: 'Close usage details', act: 'usage-close', id: id + '-close', size: 'sm' })}</div><div class="sb-usage-tabs" role="group" aria-label="Provider">${tabs}</div><div class="sb-usage-panel-body">${body}</div>${defaults}<div class="sb-usage-panel-foot">${Button({ icon: 'refresh', label: o.signedOut ? 'Recheck sign-in' : 'Refresh', act: 'usage-refresh', size: 'sm', variant: 'ghost', disabled: o.loading })}${Button({ label: 'Dashboard', act: 'noop', size: 'sm', variant: 'ghost' })}</div></section>`;
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
    return `<div class="sb-doc-hd">${o.eyebrow ? `<div class="l-row g-2 l-wrap">${join(o.eyebrow)}</div>` : ''}<h1 class="sb-doc-ti">${esc(o.title)}</h1>${o.sub ? `<div class="sb-doc-sub">${o.sub}</div>` : ''}${o.actions ? `<div class="l-row g-2 l-wrap sb-doc-acts">${join(o.actions)}</div>` : ''}</div>`;
  }
  /* With `toggle` (an action) the title, count and a faint chevron form one button that collapses the body; the end slot stays.
   * `peek` shows under the header while collapsed instead of nothing (a plan run's Briefing teaser). */
  function Section(o) {
    track('Section');
    const id = o.id ? ` id="${esc(o.id)}"` : '';
    /* `badge` (HTML) follows the title, before the chevron: the QA verdict. `cls` scopes a section's own metrics. */
    const head = `<span>${esc(o.title)}</span>${o.count != null ? `<span class="n">${o.count}</span>` : ''}${o.badge || ''}`;
    const title = o.toggle
      ? `<button type="button" class="sb-sec-tg" aria-expanded="${!o.collapsed}"${act(o.toggle, o.arg)}>${head}${Icon(o.collapsed ? 'chevronRight' : 'chevronDown')}</button>`
      : head;
    return `<section class="${cx('sb-sec', o.cls)}"${id}><div class="sb-sec-hd">${o.lead || ''}${title}${o.end ? `<span class="end">${join(o.end)}</span>` : ''}</div>${o.collapsed ? (o.peek || '') : o.body}</section>`;
  }
  function Box(children) { track('Box'); return `<div class="sb-box">${join(children)}</div>`; }
  function MetricStrip(items) {
    track('MetricStrip');
    return `<div class="sb-metrics">${items.filter(Boolean).map((m) => `<div class="sb-metric"><span>${esc(m.label)}</span><b>${m.value}${m.unit ? `<small>${esc(m.unit)}</small>` : ''}</b>${m.note ? `<em>${m.note}</em>` : ''}</div>`).join('')}</div>`;
  }
  /* One line of the Subtasks index. It never expands: `act`/`arg` select it and open the details panel. `selected` (the
   * panel's subject) wins over `current` (the running subtask's tint). A chevron at the end shows on hover and while selected. */
  function SubtaskRow(o) {
    track('SubtaskRow');
    return `<div class="${cx('sb-sub', o.current && 'is-current', o.selected && 'is-selected', o.state === 'pending' && 'is-pending', o.act && 'is-btn')}" role="option" aria-selected="${!!o.selected}"><div class="sb-sub-row"${act(o.act, o.arg)}>${IdTag(o.num)}${StatusIcon(o.state, o.frac)}<b>${esc(o.title)}</b>${join(o.meta)}${o.act ? `<span class="sb-sub-go" aria-hidden="true">${Icon('chevronRight', 'sm')}</span>` : ''}</div></div>`;
  }
  /* The Subtasks index: a Box that takes keyboard focus (↑/↓ select and open the details panel, Enter opens the spec file). */
  function SubtaskList(o) {
    track('SubtaskList');
    return `<div class="sb-box sb-sublist" tabindex="0" role="listbox" aria-label="${esc(o.label || 'Subtasks')}" data-zone="subtasks">${join(o.rows)}</div>`;
  }
  /* A subtask reference ("02") as a button that selects that subtask; an optional edge reads "02 optional". */
  function SubtaskChip(o) {
    track('SubtaskChip');
    return `<button type="button" class="${cx('sb-chip', o.optional && 'is-optional')}"${act(o.act, o.arg)}${tipAttrs(o.tip)}>${o.lead || ''}<span class="mono">${esc(o.num)}</span>${o.optional ? '<em>optional</em>' : ''}</button>`;
  }
  /* File paths in compact finding rows: the last two segments after "…/" once the path passes 28 characters. */
  const shortPath = (p) => { const s = String(p); if (s.length <= 28) return s; const seg = s.split('/'); return seg.length > 2 ? '…/' + seg.slice(-2).join('/') : '…' + s.slice(-27); };
  /* The selected subtask, in the details panel: title, block reason, properties, then Review. */
  const VERB = { done: 'Done', no_change: 'No change', active: 'In progress', blocked: 'Blocked', pending: 'Pending', skipped: 'Skipped', paused: 'Paused', failed: 'Failed' };
  const STATE_COLOR = (s) => `var(--st-${(STATES[s] || STATES.pending).tone})`;
  function SubtaskInspector(o) {
    track('SubtaskInspector');
    const chips = (list) => `<span class="sb-chips">${list.map((c) => SubtaskChip(c)).join('')}</span>`;
    const props = PropertyList([
      o.commit ? { label: 'Commit', value: ShortId({ value: o.commit, label: 'commit' }) } : null,
      o.spec ? { label: 'Spec file', value: `${Icon('fileText', 'sm')}<span class="trunc">${esc(o.spec)}</span>`, act: o.specAct, arg: o.specArg, tip: 'Open the spec file', kbd: '↵' } : null,
      o.deps && o.deps.length ? { label: 'Depends on', value: chips(o.deps) } : null,
      o.neededBy && o.neededBy.length ? { label: 'Needed by', value: chips(o.neededBy) } : null,
      o.validation ? { label: 'Validation', value: `<i class="sb-dot" style="--c:${o.validation.passed ? 'var(--st-done)' : 'var(--st-failed)'}"></i><span class="tnum">${o.validation.passed ? 'passed' : 'failed'} · ${o.validation.checks} checks</span>` } : null,
    ]);
    const passes = (o.reviews || []).map((p) => `<div class="sb-insp-pass"><div class="sb-insp-line"><b>Pass ${p.n}</b><span>· ${esc(p.verdict)}</span><span class="tnum">· ${p.open} unresolved</span></div>${p.findings.length ? VerifyDoc([Box(p.findings.map(([sev, text, where], i) => FindingRow({ ref: `F-${String(i + 1).padStart(3, '0')}`, severity: sev, title: text, hint: shortPath(where) })))]) : ''}</div>`).join('');
    const review = `<div class="sb-insp-sec"><div class="sb-insp-hd">Review</div>${passes || '<span class="sb-insp-none">No review yet</span>'}${o.audit ? `<div class="sb-insp-line"><b>Audit</b><span>· ${esc(o.audit.verdict)}</span></div>${o.audit.summary ? ExpandableText({ text: o.audit.summary, cls: 'sb-insp-note' }) : ''}` : ''}</div>`;
    /* `phases` (the Phases tabs and their PhasePeek card) follows the properties. When the review phase has a card, `noReview`
     * drops the Review block: its passes and findings live in that card. The audit keeps its own section, as in the app. */
    const audit = o.audit ? `<div class="sb-insp-sec"><div class="sb-insp-hd">Audit</div><div class="sb-insp-line"><b>Verdict</b><span>· ${esc(o.audit.verdict)}</span></div>${o.audit.summary ? ExpandableText({ text: o.audit.summary, cls: 'sb-insp-note' }) : ''}</div>` : '';
    /* `reviewHtml` and `auditHtml` (ReviewSection, AuditSection) replace the compact Review block when the screen has them. */
    const results = o.reviewHtml != null || o.auditHtml != null ? `${o.reviewHtml || ''}${o.auditHtml || ''}` : o.noReview ? audit : review;
    return `<div class="sb-insp">${o.title ? `<div class="sb-insp-ti">${esc(o.title)}</div>` : ''}${o.blocked ? Hint({ tone: 'warn', text: o.blocked, fold: true }) : ''}${props}${o.phases || ''}${results}</div>`;
  }
  /* With `o.tabs` (the details pane) the steps are tabs: a step with `act` opens its PhasePeek card, `selected` draws a ring
   * outside the state color, a running step leads with a breathing dot and a step without `act` stays inert. Arrow keys,
   * Home and End move between the steps that open (main.js). `skipped` strikes the label through. */
  function PhasePipeline(steps, o) {
    track('PhasePipeline');
    o = o || {};
    /* With every card folded, the first pill that opens takes the tab stop. */
    const anySel = steps.some((x) => x.act && x.selected), firstCan = steps.find((x) => x.act);
    const step = (s) => {
      const lead = s.state === 'done' ? Icon('check') : o.tabs && s.state === 'now' ? '<span class="sb-step-live"></span>' : '';
      const label = `${lead}${esc(s.label)}${s.count ? ` · ${esc(s.count)}` : ''}`;
      if (!o.tabs) return s.act ? `<button type="button" class="${cx('sb-step', 'is-link', s.state, s.goal && 'goal')}"${act(s.act, s.arg)}${tipAttrs(s.tip)}>${label}</button>` : `<span class="${cx('sb-step', s.state, s.goal && 'goal')}"${tipAttrs(s.tip)}>${label}</span>`;
      const can = !!s.act;
      return `<button type="button" role="tab" class="${cx('sb-step', 'goal', s.state, can && 'can')}" aria-selected="${!!(can && s.selected)}"${o.expand && can ? ` aria-expanded="${!!s.selected}"` : ''} tabindex="${can && (s.selected || (!anySel && s === firstCan)) ? 0 : -1}"${can ? act(s.act, s.arg) : ' aria-disabled="true"'} data-peek-pill="${esc(s.label)}" title="${esc(s.tip || (can ? `Show ${s.label}` : s.state === 'skipped' ? `${s.label} was skipped` : `${s.label} has not started`))}">${label}</button>`;
    };
    return `<div class="${cx('sb-pipe', o.tabs && 'sb-pipe--tabs')}"${o.tabs ? ` role="tablist" aria-label="${esc(o.label || 'Phases')}"` : ''}>${steps.map((s, i) => (i && !s.goalStart ? '<span class="sb-pipe-sep"></span>' : (s.goalStart ? '<span style="width:10px"></span>' : '')) + step(s)).join('')}</div>`;
  }

  /* ---------- Phase peek: the card under the details-pane phase tabs (design/phase-peek) ---------- */
  const PEEK_TONE = { done: 'var(--st-done)', now: 'var(--st-active)', fail: 'var(--st-failed)', wait: 'var(--st-blocked)', skipped: 'var(--fg-faint)', pending: 'var(--st-pending)' };
  const PEEK_WORD = { done: 'Done', now: 'Running', fail: 'Failed', wait: 'Blocked', skipped: 'Skipped', pending: 'Pending' };
  const PEEK_STATUS = { done: 'done', now: 'active', fail: 'failed', wait: 'blocked', skipped: 'skipped', pending: 'pending' };
  /* The selected phase: a header (status, title, phase id, state word, agent and model, round, time, duration) with an edge
   * in `tone` (defaults to the state), an optional tinted `why` strip, then `parts` (PeekPart HTML), an Activity timeline
   * and a `foot` button that opens the phase's full screen. */
  function PhasePeek(o) {
    track('PhasePeek');
    const tone = o.tone || o.state;
    const meta = [
      o.agent ? `<span class="who">${o.agent}${esc(o.model || '')}</span>` : '',
      o.round ? `<span>${esc(o.round)}</span>` : '', o.when ? `<span>${esc(o.when)}</span>` : '',
    ].filter(Boolean).join('<i>·</i>');
    const head = `<div class="sb-pk-hd" style="--tone:${PEEK_TONE[tone] || PEEK_TONE.pending}"><span class="st">${StatusIcon(PEEK_STATUS[o.state] || 'pending', 0.5)}</span><span class="nm">${esc(o.title)}<span class="id">${esc(o.id)}</span></span><span class="w">${esc(o.word || PEEK_WORD[o.state] || '')}</span><div class="mt">${meta}</div><span class="du">${esc(o.dur || '')}</span></div>`;
    const why = o.why ? `<div class="sb-pk-part sb-pk-why" style="--tone:${PEEK_TONE[tone] || PEEK_TONE.pending}"><div class="sb-pk-tx">${o.why}</div></div>` : '';
    const foot = o.foot ? `<div class="sb-pk-ft"><button type="button" class="sb-pk-btn"${act(o.foot.act, o.foot.arg)}>${esc(o.foot.label)}${Icon(o.foot.external ? 'external' : 'arrowRight', 'xs')}</button></div>` : '';
    return `<div class="sb-pk" role="tabpanel" aria-label="${esc(o.title)}">${head}${why}${join(o.parts)}${o.activity && o.activity.length ? PeekPart({ title: 'Activity', body: EventLines(o.activity.map(([t, html, tone]) => [t, html, PEEK_TONE[tone] || 'var(--border-strong)'])) }) : ''}${foot}</div>`;
  }
  function AuditResult(o) {
    track('AuditResult');
    return `<div class="sb-audit-result"><div class="sb-audit-verdict">${o.verdict}${o.end ? `<span class="end">${esc(o.end)}</span>` : ''}</div>${join(o.body)}</div>`;
  }
  /* One part of a PhasePeek: a small caps title with an `end` slot (text or a control), then `body`. `live` tints it for Now. */
  function PeekPart(o) {
    track('PeekPart');
    return `<div class="${cx('sb-pk-part', o.live && 'is-live')}"><div class="${cx('sb-pk-ti', o.control && 'has-ctl')}">${esc(o.title)}${o.end ? `<span class="end">${o.end}</span>` : ''}</div>${join(o.body)}</div>`;
  }
  /* A line in Now: a pulse (`live`) or an icon, the text (HTML the caller escaped), an optional `keep` tail that never
   * truncates (+12 −4) and how long ago. */
  function PeekSignal(o) {
    track('PeekSignal');
    const tx = o.keep ? `<span class="tx split"><span class="trunc">${o.html}</span><span class="keep">${o.keep}</span></span>` : `<span class="${cx('tx', o.wrap && 'wrap')}">${o.html}</span>`;
    return `<div class="sb-pk-sig"><span class="lead">${o.live ? '<span class="pulse"></span>' : Icon(o.icon || 'pencil', 'xs')}</span>${tx}<span class="ago">${esc(o.ago || '')}</span></div>`;
  }
  function PeekCmd(text) { track('PeekCmd'); return `<div class="sb-pk-cmd">${Icon('terminal', 'xs')}<span>${esc(text)}</span></div>`; }
  /* Equal cells, one per check: ok, bad, warn, run (breathing) or empty. */
  function PeekCells(cells) { track('PeekCells'); return `<div class="sb-pk-cells">${cells.map((c) => `<i class="${esc(c)}"></i>`).join('')}</div>`; }
  /* A row of numbers: [value, label, tone (ok, bad, warn)]. */
  function PeekStats(items) {
    track('PeekStats');
    return `<div class="sb-pk-stats" style="--n:${items.length}">${items.map(([v, l, t]) => `<div><b class="${esc(t || '')}">${esc(v)}</b><span>${esc(l)}</span></div>`).join('')}</div>`;
  }
  /* What a phase produced: an icon, the text (HTML), an `end` note. With `act` the row is a button with an arrow (or the
   * external mark) on hover. */
  function PeekOutput(o) {
    track('PeekOutput');
    const inner = `${Icon(o.icon || 'file', 'xs')}<span class="tx">${o.html}</span><span class="end">${esc(o.end || '')}${o.act ? Icon(o.external ? 'external' : 'arrowRight', 'xs') : ''}</span>`;
    return o.act ? `<button type="button" class="sb-pk-out is-btn"${act(o.act, o.arg)}${o.tip ? ` title="${esc(o.tip)}"` : ''}>${inner}</button>` : `<div class="sb-pk-out"${o.tip ? ` title="${esc(o.tip)}"` : ''}>${inner}</div>`;
  }
  const PEEK_SEV = { blocker: 'var(--st-failed)', major: 'var(--st-blocked)', high: 'var(--st-blocked)', medium: 'var(--data-1)', minor: 'var(--fg-muted)', low: 'var(--fg-muted)', nit: 'var(--fg-faint)' };
  const PEEK_FSTATE = { fixed: ['Fixed', 'var(--st-done)'], open: ['Open', 'var(--fg-faint)'], refuted: ['Refuted', 'var(--fg-faint)'] };

  /* A check or criterion: ok (green check), bad (red cross), run (spinner) or waiting (ring); the name, its command and time. */
  function PeekCheck(o) {
    track('PeekCheck');
    const g = o.state === 'ok' ? `<span class="g ok">${Icon('check', 'xs')}</span>` : o.state === 'bad' ? `<span class="g bad">${Icon('x', 'xs')}</span>` : o.state === 'run' ? '<span class="spin"></span>' : '<span class="ring"></span>';
    return `<div class="sb-pk-chk"${o.cmd ? ` title="${esc(o.cmd)}"` : ''}>${g}<span class="tx">${esc(o.name)}${o.cmd ? `<em>${esc(o.cmd)}</em>` : ''}</span><span class="w">${esc(o.time || '')}</span></div>`;
  }
  /* Label and value pairs on one wrapping line ("Exit all findings resolved"). Values are HTML. */
  function PeekFacts(items) { track('PeekFacts'); return `<div class="sb-pk-facts">${items.map(([k, v]) => `<span>${esc(k)} <b>${v}</b></span>`).join('')}</div>`; }

  /* The floating details panel. It lies over the document from the right edge of the work body and never moves the layout.
   * Header: `status` ({ state, frac }) as a StatusIcon, the title, the state's word in its color, `actions`, then Close.
   * The left edge is a resize grip (drag, ←/→ while focused, double-click resets). `width` is the requested CSS width ("640px", or "min(800px, 70%)" by default);
   * CSS clamps it to the work body minus 64px, and below 760px the panel fills the body and the grip goes away. */
  function DetailsPanel(o) {
    track('DetailsPanel');
    const st = o.status ? `${StatusIcon(o.status.state, o.status.frac)}` : '';
    const word = o.status ? `<span class="sb-pane-st" style="--c:${STATE_COLOR(o.status.state)}">${esc(o.status.label || VERB[o.status.state] || o.status.state)}</span>` : '';
    return `<aside class="sb-pane" style="--w:${esc(o.width)}" aria-label="${esc(o.label || o.title)}"><div class="sb-pane-grip" data-pane-grip role="separator" aria-orientation="vertical" aria-label="Resize details panel" aria-valuemin="${+o.min}" tabindex="0"${tipAttrs('Drag to resize', '← →')}></div><div class="sb-pane-hd">${st}<b>${esc(o.title)}</b>${word}<span class="l-grow"></span>${join(o.actions)}${IconButton({ icon: 'x', label: 'Close details', kbd: 'Esc', act: o.closeAct, size: 'sm' })}</div><div class="sb-pane-body" data-scroll="pane">${o.body}</div></aside>`;
  }
  /* Titled group in the details pane: 11.5px faint title. With `status` ({ state }) the title leads with that StatusIcon
   * and ends with the state's word in its color ("Blocked"); `end` is a free end slot. */
  function PaneSection(o) {
    track('PaneSection');
    const lead = o.status ? StatusIcon(o.status.state, o.status.frac) : '';
    const end = o.status ? `<span class="sb-pane-st" style="--c:${STATE_COLOR(o.status.state)}">${esc(o.status.label || VERB[o.status.state] || o.status.state)}</span>` : (o.end || '');
    return `<section class="sb-pane-sec">${o.title ? `<div class="sb-pane-ti">${lead}<span>${esc(o.title)}</span>${end ? `<span class="end">${end}</span>` : ''}</div>` : ''}${o.body}</section>`;
  }

  /* ---------- Spec tree ---------- */
  /* `toggle` ({ act, arg, label }) makes the caret its own button that expands the row while a click on the row selects it.
   * `trail` ends the row: a count that gives way to hover-only buttons (`.hover-only`) on hover and on the selected row.
   * `ctx` opens a menu on right-click; `path` names the row for keyboard moves. */
  function TreeRow(o) {
    track('TreeRow');
    const caret = o.toggle
      ? `<button type="button" class="caret"${act(o.toggle.act, o.toggle.arg)} aria-label="${esc(o.toggle.label)}" tabindex="-1">${Icon('chevronRight')}</button>`
      : `<span class="caret">${o.expandable ? Icon('chevronRight') : ''}</span>`;
    const tree = o.path != null ? ` role="treeitem" aria-selected="${!!o.focus}"${o.expandable || o.toggle ? ` aria-expanded="${!!o.open}"` : ''} data-path="${esc(o.path)}"` : '';
    return `<div class="${cx('sb-tree-row', o.open && 'open', o.focus && 'focus', o.file && 'is-file', o.muted && 'is-muted', o.child && 'is-child')}" style="--lvl:${o.level || 0}"${act(o.act, o.arg)} data-row="${o.rowIdx}"${o.ctx ? ` data-ctx="${esc(o.ctx)}"` : ''}${tree}>${caret}${o.lead}${o.id ? IdTag(o.id) : ''}<b${o.tip ? ` title="${esc(o.tip)}"` : ''}>${esc(o.title)}</b>${join(o.meta)}${o.count != null ? `<span class="n">${esc(o.count)}</span>` : ''}${o.trail != null ? `<span class="trail">${o.trail}</span>` : ''}</div>`;
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
  /* Without `label` it is the model-matrix row (phase column, tier, value). With `label` it reads like a SettingRow: label and
   * description on the left, the mono value on the right. Either way a click expands `editor` in place under the row. */
  function ConfigRow(o) {
    track('ConfigRow');
    const end = `${join(o.tags)}${Icon(o.open ? 'chevronUp' : 'pencil', 'sm')}`;
    const row = o.label
      ? `<div class="${cx('sb-cfg', 'sb-cfg--set', o.open && 'open', o.override && 'is-override')}"${act(o.act, o.arg)}><div class="sb-sr-tx"><b>${esc(o.label)}</b>${o.desc ? `<span>${o.desc}</span>` : ''}</div><span class="val">${esc(o.value)}</span>${end}</div>`
      : `<div class="${cx('sb-cfg', o.open && 'open', o.override && 'is-override')}"${act(o.act, o.arg)}><span class="ph">${esc(o.phase)}</span>${o.tier ? TierBadge(o.tier) : ''}<span class="val">${esc(o.value)}</span>${end}</div>`;
    return row + (o.editor ? `<div class="sb-cfg-ed">${o.editor}</div>` : '');
  }

  /* ---------- Side pane (Specs) ---------- */
  function SidePane(o) {
    track('SidePane');
    return `<aside class="sb-sidepane" aria-label="${esc(o.title)}"><div class="sb-sidepane-hd"><b>${esc(o.title)}</b>${o.head || ''}<span class="l-grow"></span>${join(o.actions)}</div>${o.bar ? `<div class="sb-filters">${o.bar}</div>` : ''}${o.body}</aside>`;
  }

  /* ---------- Small text and layout parts screens compose with ---------- */
  /* Lines added and removed, green and red: "+12 −4". */
  function Counts(o) { track('Counts'); return `<span class="sb-counts tnum">${o.ins != null ? `<span class="i">+${esc(o.ins)}</span>` : ''}${o.del != null ? `<span class="d">−${esc(o.del)}</span>` : ''}</span>`; }
  /* Text that is happening now: the active green with a slow pulse ("live", a running check's signal). */
  function LiveText(text) { track('LiveText'); return `<span class="sb-livetx">${esc(text)}</span>`; }
  /* A 6px dot in a color: an agent, a state. */
  function Dot(color) { track('Dot'); return `<i class="sb-dot" style="--c:${color}"></i>`; }
  /* A muted note: text an empty or waiting place shows ("No phase has run yet.", "Runs after implementation"). `dashed`
   * frames it (results not written yet); `pad` gives it room inside a Box. */
  function Note(o) { track('Note'); return `<div class="${cx('sb-note', o.dashed && 'is-dashed', o.pad && 'is-pad', o.small && 'is-small')}">${o.html || esc(o.text)}</div>`; }
  /* A titled block inside the subtask inspector: an 11.5px caption with an end slot, then the body (Review, Audit). */
  function InspectorSection(o) { track('InspectorSection'); return `<div class="sb-insp-sec"><div class="sb-insp-hd">${esc(o.title)}${o.end ? `<span class="end">${esc(o.end)}</span>` : ''}</div>${o.body}</div>`; }
  /* A 22px line: the lead in 12px/500, then "· part" pieces in fg-muted ("Pass 2 · Changes requested · 2 open"). */
  function InspectorLine(o) { track('InspectorLine'); return `<div class="sb-insp-line">${o.lead ? `<b>${esc(o.lead)}</b>` : ''}${(o.parts || []).filter(Boolean).map((p, i) => `<span class="${i ? 'tnum' : ''}">· ${p.html || esc(p)}</span>`).join('')}</div>`; }
  /* Phase pills and the open phase card under a caption: a subtask's Phases, Goal finish. */
  function PhaseBlock(o) { track('PhaseBlock'); return `<div class="sb-peek-sec">${o.title ? `<div class="sb-pane-ti"><span>${esc(o.title)}</span>${o.note ? `<span class="end">${esc(o.note)}</span>` : ''}</div>` : ''}${o.tabs}${o.card || Note({ text: o.none, small: true })}</div>`; }
  /* A bordered button that picks something: the Specs project, the Claude directory. */
  function PickerButton(o) { track('PickerButton'); return `<button type="button" class="sb-pick"${act(o.act, o.arg)}${o.id ? ` id="${esc(o.id)}"` : ''} aria-haspopup="${o.popup || 'menu'}"${o.expanded != null ? ` aria-expanded="${!!o.expanded}"` : ''}${tipAttrs(o.tip)}>${o.lead || (o.icon ? Icon(o.icon) : '')}<span class="${cx(o.mono && 'mono', 'trunc')}">${esc(o.label)}</span>${Icon('chevronDown', 'xs')}</button>`; }
  /* A range slider in the accent color. */
  function Range(o) { track('Range'); return `<input type="range" class="sb-range" id="${esc(o.id)}" min="${o.min}" max="${o.max}" value="${o.value}"${o.input ? ` data-input="${esc(o.input)}"` : ''} aria-label="${esc(o.label || '')}">`; }
  /* A terminal prompt: the directory, ➜, the name, the branch, a blinking cursor; `lines` are [tone, text] output lines. */
  function ShellPrompt(o) {
    track('ShellPrompt');
    if (o.lines) return o.lines.map(([c, t]) => (c ? `<span class="${esc(c)}">${esc(t)}</span>` : esc(t))).join('\n') + (o.cursor ? '\n<span class="cur"></span>' : '');
    return `<span class="m">${esc(o.dir)}</span>\n<span class="p">➜</span>  <span class="d">${esc(o.name)}</span>${o.branch ? ` <span class="b">(${esc(o.branch)})</span>` : ''} <span class="cur"></span>`;
  }
  /* The app frame: sidebar (or the settings navigation) beside the inset main panel. */
  function AppShell(o) { track('AppShell'); return `<div class="${cx('sb-app', o.sideOpen && 'side-open')}">${o.side}<main class="sb-main">${o.main}</main></div>`; }
  /* A view inside the main panel: header, body, the docked terminal, and the Specs pane on the right. */
  function WorkArea(o) { track('WorkArea'); return `<div class="sb-work"><div class="sb-work-main">${o.header}<div class="sb-work-body">${o.body}</div>${o.terminal || ''}</div>${o.side || ''}</div>`; }
  /* A scrolling region; `doc` centers the 780px document column, `settings` the settings column. `id` keeps its scroll
   * position across renders; `zone` names it for keyboard focus. */
  function ScrollArea(o) { track('ScrollArea'); const inner = o.doc ? `<div class="sb-doc">${o.body}</div>` : o.settings ? `<div class="sb-set">${o.body}</div>` : o.body; return `<div class="sb-scroll"${o.id ? ` data-scroll="${esc(o.id)}"` : ''}${o.zone ? ` data-zone="${esc(o.zone)}"` : ''}${o.tabIndex != null ? ` tabindex="${o.tabIndex}"` : ''}${o.role ? ` role="${esc(o.role)}"` : ''}${o.label ? ` aria-label="${esc(o.label)}"` : ''}>${inner}</div>`; }
  /* A detail view: the document column with the floating details panel over its right edge. */
  function DetailSplit(o) { track('DetailSplit'); return `<div class="sb-split">${ScrollArea({ id: 'detail', doc: true, body: o.doc })}${o.pane || ''}</div>`; }

  /* ---------- Expandable text: long runtime text folded to three lines ---------- */
  /* Folds to `lines` (default 3) with an ellipsis. Show more (a small ghost button) appears 2px under the text only when it
   * overflows (main.js fitReasons checks after each render); open, it reads Show less. With `act`/`arg` the caller keeps `open`
   * in its state; without, the toggle flips the fold in place (main.js 'fold-toggle') and a re-render folds it again.
   * `cls` styles the text element; `html` replaces `text` for markup the caller already escaped. */
  function foldButton(open, a, arg) {
    return Button({ label: open ? 'Show less' : 'Show more', icon: open ? 'chevronUp' : 'chevronDown', variant: 'ghost', size: 'sm', act: a || 'fold-toggle', arg });
  }
  function ExpandableText(o) {
    track('ExpandableText');
    const lines = o.lines && o.lines !== 3 ? ` style="--fold:${o.lines}"` : '';
    return `<div class="sb-fold"><p class="${cx('sb-fold-tx', o.cls, !o.open && 'is-folded')}"${o.open ? '' : ' data-fold'}${lines}>${o.html != null ? o.html : esc(o.text)}</p><div class="sb-fold-more"${o.open ? '' : ' hidden'}>${foldButton(!!o.open, o.act, o.arg)}</div></div>`;
  }

  /* ---------- Reason block: long runtime text with copy ---------- */
  /* An ExpandableText under a label and Copy reason (`copyLabel` renames it: Copy intake). `act`/`arg` toggle `open`. */
  function ReasonBlock(o) {
    track('ReasonBlock');
    return `<div class="sb-reason" style="--c:${o.color || 'var(--fg-faint)'}"><div class="sb-reason-hd"><span>${esc(o.label)}</span>${IconButton({ icon: 'copy', label: o.copyLabel || 'Copy reason', act: 'copy', arg: o.text, size: 'sm' })}</div>${ExpandableText({ text: o.text, cls: 'sb-reason-tx', open: o.open, act: o.act, arg: o.arg })}</div>`;
  }

  /* ---------- Checklist: prerequisites the operator confirms ---------- */
  function Checklist(o) {
    track('Checklist');
    return `<div class="sb-checklist">${o.items.map((it) => `<div class="sb-checklist-row">${Checkbox({ on: it.checked, act: o.act, arg: it.id, label: it.text })}<span>${esc(it.text)}</span></div>`).join('')}</div>`;
  }

  /* ---------- Finding row: review and assessment findings ---------- */
  const SEVERITY = { Blocker: 'var(--st-failed)', Major: 'var(--st-blocked)', Minor: 'var(--fg-faint)', High: 'var(--st-failed)', Medium: 'var(--st-blocked)', Low: 'var(--fg-faint)' };
  /* "Same code as F-001, #3 · File.kt:87": two fixable items point at one file and line, so one agent should get both. */
  const overlap = (html) => (html ? `<span class="sb-overlap">${Icon('link', 'xs')}${html}</span>` : '');


  /* ---------- Activity feed: one line per recorded event, oldest first, icons joined by a connector ---------- */
  /* entries: { lead (16px Avatar or StatusIcon), actor, action, object, mono (object is a path or phase), tip, ago }.
   * `hidden` earlier entries collapse behind a "Show N earlier" ghost toggle at the top (`act`/`arg`). */
  function fmtAgo(sec) {
    if (sec == null) return '';
    const s = Math.max(0, Math.round(sec));
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
    return `${Math.floor(s / 86400)}d ago`;
  }


  /* ---------- FindingRow: every finding in the app (review passes, goal assessment, Verify receipts, phase cards) ----------
   * The audit's row layout (CriterionRow): the ref in the number column, the severity Badge before the text (two lines
   * while closed), the state as the row Badge, and a chevron; open, `body` (location, lane, confidence, verification,
   * steps). Verify's selection passes `lead`, `end` and `hint` through. */
  const SEV_TONE = { blocker: 'failed', critical: 'failed', high: 'failed', major: 'blocked', medium: 'blocked', minor: 'neutral', low: 'faint', nit: 'faint', info: 'faint' };
  const FIND_TONE = { open: 'blocked', 'not fixed': 'blocked', fixing: 'active', fixed: 'done', accepted: 'active', declined: 'faint', 'false positive': 'faint', refuted: 'faint' };
  function FindingRow(o) {
    track('FindingRow');
    const sev = o.severity ? `${Badge({ text: o.severity, tone: SEV_TONE[String(o.severity).toLowerCase()] || 'faint' })} ` : '';
    const state = o.state ? { text: o.state, tone: o.stateTone || FIND_TONE[String(o.state).toLowerCase()] || 'faint' } : null;
    return CriterionRow({ id: o.id, n: o.ref || '', html: sev + (o.html || esc(o.title)), badge: state, act: o.act, arg: o.arg, open: o.open, body: o.body, select: o.lead != null, lead: o.lead, end: o.end, hint: o.hint });
  }
  /* ---------- Incident evidence: stacked so a long reference never squeezes the summary ---------- */
  function EvidenceRow(o) {
    track('EvidenceRow');
    const shown = String(o.ref).startsWith(o.kind + ':') ? String(o.ref).slice(o.kind.length + 1) : o.ref;
    return `<div class="sb-evi">${ShortId({ value: o.ref, shown, label: 'reference' })}${ExpandableText({ text: o.summary, cls: 'sb-evi-tx' })}<span class="sb-evi-kind">${esc(o.kind)}</span></div>`;
  }
  /* The faint caption as a toggle: "Evidence · N" and a chevron. Also Non-goals, Technical constraints and Rollout under a
   * Verify run's criteria; without `count` it reads the label alone. */
  function EvidenceToggle(o) {
    track('EvidenceToggle');
    return `<button type="button" class="sb-disc" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${esc(o.label)}${o.count != null ? ` · ${o.count}` : ''}${Icon(o.open ? 'chevronDown' : 'chevronRight')}</button>`;
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
    return `<div class="sb-tray"><div class="sb-tray-hd">${BrandMark(18)}<span>Sona</span></div><div class="sb-tray-stats">${o.stats.map((x) => `<div><b style="color:${x.color || 'var(--fg)'}">${esc(x.value)}</b><span>${esc(x.label)}</span></div>`).join('')}</div><div class="sb-tray-tabs">${o.tabs}</div><div class="sb-tray-body">${join(o.rows)}</div><div class="sb-tray-ft">${join(o.footer)}</div></div>`;
  }

  /* ---------- New task: open chats, transcript, composer, home, pickers ---------- */
  /* A chat's state in a 14px slot: 'replying' spins, 'unread' is an amber dot (a reply arrived while the tab was not shown). */
  function ChatStatusMark(status) {
    track('ChatStatusMark');
    if (status === 'replying') return `<span class="sb-cmark" role="img" aria-label="Replying"><span class="spin"></span></span>`;
    if (status === 'unread') return `<span class="sb-cmark" role="img" aria-label="New reply"><span class="dot"></span></span>`;
    return '<span class="sb-cmark"></span>';
  }
  /* The open chats as tabs: { tabs: [{ id, label, status, on, tip }], count }. The strip is the top of the New task screen, so
   * it leads with the sidebar toggle while the sidebar is a drawer. Tabs round on top and the selected one joins the panel.
   * Right-click, Shift+F10 or the menu key on a tab opens Close and Discard. "+" follows the last tab. The count button at the
   * far right shows only while tabs are clipped (main.js fitChatTabs) and carries a mark when a clipped tab is replying or has a
   * new reply. */
  function ChatTabs(o) {
    track('ChatTabs');
    const tabs = o.tabs.map((t) => `<div class="${cx('sb-ctab', t.on && 'on', t.status === 'unread' && 'unread')}" role="tab" tabindex="0" aria-selected="${!!t.on}" aria-haspopup="menu" data-act="chat-select" data-arg="${esc(t.id)}" data-tab="${esc(t.id)}" data-ctx="chat:${esc(t.id)}" data-status="${esc(t.status || '')}"${tipAttrs(t.tip || t.label)}>${ChatStatusMark(t.status)}<span class="label">${esc(t.label)}</span>${IconButton({ icon: 'x', label: `Close ${t.label}`, kbd: '⌘ W', act: 'chat-close', arg: t.id, size: 'sm' })}</div>`).join('');
    return `<div class="sb-ctabs" role="tablist" aria-label="Open chats">${IconButton({ icon: 'panelLeft', label: 'Toggle sidebar', kbd: '[', act: 'toggle-side', cls: 'sb-side-toggle' })}<div class="sb-ctabs-scroll" data-ctabs>${tabs}</div>${IconButton({ icon: 'plus', label: 'New chat', kbd: '⌘ T', act: 'chat-new', size: 'sm' })}<button type="button" class="sb-ctabs-more" data-act="chat-menu" data-ctabs-more hidden aria-label="All ${o.count} open chats" data-tip="All open chats">${ChatStatusMark(null)}${o.count}${Icon('chevronDown', 'sm')}</button></div>`;
  }
  /* A suggested next step: an action the agent offered (the first is `primary`), or a quick start under an empty composer.
   * `note` trails the label in faint text ("QA my branch on Pixel 8 Pro" once a QA device is picked). */
  function SuggestionChip(o) {
    track('SuggestionChip');
    return `<button type="button" class="${cx('sb-suggest', o.primary && 'is-primary')}"${act(o.act, o.arg)}>${o.icon ? Icon(o.icon, 'sm') : ''}${esc(o.label)}${o.note ? ` <span class="note">${esc(o.note)}</span>` : ''}</button>`;
  }
  /* One transcript entry. role 'operator' is a right-aligned bubble, 'agent' reads under the agent's Avatar and name with its
   * suggested actions, 'notice' is a centered faint line ("Stopped."). `streaming` adds a caret; main.js writes the growing text
   * into [data-stream] without a re-render. */
  /* A goal or plan the agent proposes (ChatActionButtons): a target tile, the change it names, then kind · issue key ·
   * repository and base branch, and Start goal or Start plan. An untitled start falls back to "New goal" or "New plan".
   * `starting` swaps the button for a spinner and "Starting…" while the start is in flight; `tip` is the instruction it sends. */
  function StartCard(o) {
    track('StartCard');
    const goal = o.kind !== 'plan';
    const end = o.starting
      ? `<span class="sb-startcard-go"><span class="sb-startcard-spin" aria-hidden="true"></span>Starting…</span>`
      : Button({ label: goal ? 'Start goal' : 'Start plan', variant: 'primary', size: 'sm', act: o.act, arg: o.arg, disabled: o.disabled });
    return `<div class="${cx('sb-startcard', o.starting && 'is-starting', o.disabled && !o.starting && 'is-disabled')}"${o.tip ? ` data-tip="${esc(o.tip)}"` : ''}><span class="sb-startcard-ic">${Icon('target')}</span><span class="sb-startcard-tx"><span class="t">${esc(o.title || (goal ? 'New goal' : 'New plan'))}</span><span class="s">${esc(o.sub)}</span></span>${end}</div>`;
  }
  function ChatMessage(o) {
    track('ChatMessage');
    if (o.role === 'notice') return `<div class="sb-msg-notice">${esc(o.text)}</div>`;
    if (o.role === 'operator') return `<div class="sb-msg sb-msg--you"><span class="sb-msg-tx">${o.images ? `<span class="sb-msg-att">${o.images}</span>` : ''}${esc(o.text)}</span></div>`;
    const text = o.streaming ? `<span data-stream="${esc(o.streamId)}">${esc(o.text)}</span><span class="sb-caret"></span>` : esc(o.text);
    return `<div class="sb-msg"><span class="sb-msg-who">${o.avatar || ''}${esc(o.agent)}</span><span class="sb-msg-tx">${text}</span>${o.actions && o.actions.length ? `<div class="sb-suggests">${join(o.actions)}</div>` : ''}</div>`;
  }
  /* The selected chat's messages in a 728px column that starts at the bottom and stays pinned there while replies stream. */
  function ChatTranscript(o) {
    track('ChatTranscript');
    return `<div class="sb-chat-tr" data-scroll="${esc(o.scroll)}" data-pin><div class="sb-chat-col">${join(o.messages)}</div></div>`;
  }
  /* A rounded composer pill. With `act` it is a button that opens a popup (`expanded`); without, plain text. Mono by default
   * for repository, branch and model; `sans` for counts ("3 messages queued"). `need` marks a choice still to make. */
  function ChatPill(o) {
    track('ChatPill');
    const inner = `${o.lead || (o.icon ? Icon(o.icon, 'xs') : '')}<span class="tx">${o.html != null ? o.html : esc(o.text)}</span>${o.chevron ? `<span class="chev">${Icon(o.chevron, 'xs')}</span>` : ''}`;
    const cls = cx('sb-cpill', o.sans && 'sb-cpill--sans', o.need && 'is-need');
    if (!o.act) return `<span class="${cls}"${o.tip ? ` title="${esc(o.tip)}"` : ''}>${inner}</span>`;
    return `<button type="button" class="${cls}"${act(o.act, o.arg)} aria-haspopup="dialog" aria-expanded="${!!o.expanded}"${o.id ? ` id="${esc(o.id)}"` : ''}${o.disabled ? ' disabled' : ''}${tipAttrs(o.tip)}>${inner}</button>`;
  }
  /* The input dock under the chat: optional quick starts (with an optional faint `hint` line under them), the textarea, the
   * queue slot, then pills on the left and Stop and Send on the right. Enter sends, Shift+Enter starts a new line (main.js). */
  function Composer(o) {
    track('Composer');
    return `<div class="sb-dock"><div class="sb-composer">${o.chips && o.chips.length ? `<div class="sb-suggests">${join(o.chips)}</div>${o.hint ? `<span class="sb-suggests-hint">${esc(o.hint)}</span>` : ''}` : ''}<textarea class="sb-composer-in" id="${esc(o.id)}" data-input="composer" data-chat="${esc(o.chatId)}" aria-label="${esc(o.label)}" placeholder="${esc(o.placeholder)}" spellcheck="false">${esc(o.value || '')}</textarea>${o.queue || ''}<div class="sb-composer-ft">${join(o.pills)}<span class="l-grow"></span>${join(o.actions)}</div></div></div>`;
  }
  /* The seven steps a goal run takes, with a sweep along the line. */
  function StepLine(steps) {
    track('StepLine');
    return `<div class="sb-steps" style="--n:${steps.length}" aria-label="Steps: ${esc(steps.join(', '))}"><span class="sb-steps-sweep"></span>${steps.map((s) => `<div class="sb-steps-st"><i></i><span>${esc(s)}</span></div>`).join('')}</div>`;
  }
  /* The empty chat's home: a 720px column centered in the space above the composer. */
  function NewTaskHome(o) {
    track('NewTaskHome');
    return `<div class="sb-home" data-scroll="${esc(o.scroll || 'nt-home')}"><div class="sb-home-col">${join(o.children)}</div></div>`;
  }
  /* A home list (Recent chats, Needs you). Five 34px rows show, then it scrolls and the edge with more rows fades
   * (main.js fitInbox). With focus, ↑/↓ select, Enter opens and Del discards a saved chat. */
  function InboxList(o) {
    track('InboxList');
    return `<div class="sb-inbox" id="${esc(o.id)}" role="listbox" tabindex="0" aria-label="${esc(o.label)}" data-inbox data-zone="${esc(o.id)}">${join(o.rows)}</div>`;
  }
  /* One home row: a saved chat (chat or image glyph, title, screenshot name, open and repository badges, age; × discards a
   * chat that is not open) or a blocked run (`need`: orange edge, key, reason, step). */
  function InboxRow(o) {
    track('InboxRow');
    const discard = o.discard ? IconButton({ icon: 'x', label: o.discard.label, act: o.discard.act, arg: o.discard.arg, size: 'sm' }) : '';
    return `<div class="${cx('sb-inbox-row', o.need && 'is-need', o.selected && 'sel')}" role="option" aria-selected="${!!o.selected}"${act(o.act, o.arg)}>
      <span class="${cx('ic', o.tone && 'is-' + o.tone)}">${Icon(o.icon || 'message', 'sm')}</span>${o.key ? `<span class="key">${esc(o.key)}</span>` : ''}
      <span class="ti"><b>${esc(o.title)}</b>${o.sub ? `<span class="${o.subMono ? 'mono' : ''}">${esc(o.sub)}</span>` : ''}</span>${join(o.badges)}
      <span class="${cx('end', discard && 'can-x')}"><span class="t tnum">${esc(o.time || '')}</span>${discard}</span></div>`;
  }
  /* A search field flush with the top of a popup or palette. `pick` names the list its arrow keys move; `end` is a scope pill. */
  function FilterBar(o) {
    track('FilterBar');
    return `<div class="${cx('sb-fbar', o.large && 'sb-fbar--lg')}">${Icon('search', 'sm')}<input class="sb-fbar-in" id="${esc(o.id)}" value="${esc(o.value || '')}" placeholder="${esc(o.placeholder)}" aria-label="${esc(o.label || o.placeholder)}"${o.input ? ` data-input="${esc(o.input)}"` : ''}${o.pick ? ` data-pick="${esc(o.pick)}"` : ''} autocomplete="off" spellcheck="false">${o.end || ''}</div>`;
  }
  /* The repository and base branch popup over the composer: two columns, each { title, filter (FilterBar), id, pick, rows
   * (MenuItems), focusable (a list without a filter takes focus itself), message { title, body }, loading, problem, foot }. */
  function pickerColumn(c) {
    const body = c.loading
      ? [62, 78, 54, 70].map((w) => `<div class="sb-tpick-skel"><i style="width:${w}%"></i></div>`).join('')
      : c.message ? `<div class="sb-tpick-msg"><b>${esc(c.message.title)}</b>${esc(c.message.body)}</div>` : join(c.rows);
    return `<div class="sb-tpick-col"><div class="sb-tpick-hd">${esc(c.title)}</div>${c.filter || ''}<div class="sb-tpick-list" id="${esc(c.id)}" role="listbox" aria-label="${esc(c.label || c.title)}"${c.focusable ? ` tabindex="0" data-zone="${esc(c.id)}" data-pick="${esc(c.pick)}"` : ''}${c.loading ? ' aria-busy="true"' : ''}>${body}</div>${c.problem ? `<div class="sb-tpick-problem">${c.problem}</div>` : ''}${c.foot ? `<div class="sb-tpick-foot">${c.foot}</div>` : ''}</div>`;
  }
  function TargetPicker(o) {
    track('TargetPicker');
    return `<section class="sb-tpick" aria-label="Repository and base branch">${pickerColumn(o.repo)}${pickerColumn(o.branch)}</section>`;
  }
  /* One queued message: its place in the send order (the first is Next), two lines of text, tags, and × to remove it. */
  function QueuedRow(o) {
    track('QueuedRow');
    const tags = [o.next ? Label({ text: 'Next', color: 'var(--accent)', tint: true, dot: false }) : '', o.images ? Label({ text: `${o.images} ${o.images === 1 ? 'image' : 'images'}`, icon: 'image', dot: false }) : '', o.action ? Label({ text: 'Action', icon: 'zap', dot: false }) : ''].filter(Boolean);
    return `<div class="${cx('sb-qrow', o.next && 'is-next', o.kb && 'kb')}" role="option" aria-selected="${!!o.kb}" data-qi="${o.index}"><span class="sb-qrow-n">${o.index + 1}</span><span class="sb-qrow-bd"><span class="tx">${esc(o.text)}</span>${tags.length ? `<span class="tags">${tags.join('')}</span>` : ''}</span>${IconButton({ icon: 'x', label: `Remove queued message ${o.index + 1}`, kbd: 'Del', act: o.act, arg: o.arg, size: 'sm' })}</div>`;
  }
  /* The queue behind the "N messages queued" pill, in send order. Clear all asks once more ("Remove all 3?"); a removal keeps
   * Undo for 5 seconds. */
  function QueuePanel(o) {
    track('QueuePanel');
    const clear = o.count > 1 ? Button({ label: o.clearArmed ? `Remove all ${o.count}?` : 'Clear all', variant: o.clearArmed ? 'danger' : 'ghost', size: 'sm', act: 'q-clear' }) : '';
    return `<div class="sb-queue" aria-label="Queued messages"><div class="sb-queue-hd"><b>Queued</b><span class="n">${o.count}</span><span class="l-grow"></span>${clear}</div>
      <div class="sb-queue-list" id="queue-list" role="listbox" tabindex="-1" aria-label="Queued messages" data-zone="queue">${join(o.rows)}</div>
      ${o.undo ? `<div class="sb-queue-undo" role="status"><span>${esc(o.undo)}</span><span class="l-grow"></span>${Button({ label: 'Undo', variant: 'ghost', size: 'sm', act: 'q-undo', kbd: '⌘ Z' })}</div>` : ''}
      <div class="sb-queue-ft"><span>Sends in order when the current reply ends</span><span class="l-grow"></span>${Kbd('↑ ↓ Del')}</div></div>`;
  }
  /* What this chat changed in its checkout, on the composer's right next to Send: file count and line totals. It opens
   * ChatChangesPanel. Absent until the chat has changed a file. `o`: { files, ins, del, expanded, act, since }. */
  function ChatChangesPill(o) {
    track('ChatChangesPill');
    const html = `${o.files} ${o.files === 1 ? 'file' : 'files'} <span class="ins">+${o.ins}</span> <span class="del">−${o.del}</span>`;
    return `<span class="sb-chg-anchor">${ChatPill({ icon: 'diff', html, chevron: 'chevronsUpDown', act: o.act, expanded: o.expanded, id: o.id, tip: `Files this chat changed since ${o.since}` })}</span>`;
  }
  const chgDir = (parts) => (parts.length > 3 ? `${parts[0]}/…/${parts.slice(-2).join('/')}` : parts.join('/'));
  /* One changed file: status letter (A added, M modified, D deleted, R renamed), file name, its folder shortened from the
   * middle, and its line counts. A click opens the Changes window on that file. */
  function ChatChangeRow(o) {
    track('ChatChangeRow');
    const parts = o.path.split('/');
    const name = parts.pop();
    const word = { A: 'Added', M: 'Modified', D: 'Deleted', R: 'Renamed' }[o.status];
    /* `selected` marks the file the Changes window shows; `badge` is its comment count ({ n, tone }); `multi` the number of
     * subtasks in scope that changed it; `noDir` drops the folder under a GroupHeader that already names it. */
    const badge = o.badge ? `<span class="${cx('cm', o.badge.tone && 'is-' + o.badge.tone)}" title="${esc(o.badge.tip || '')}">${Icon('message', 'xs')}${esc(o.badge.n)}</span>` : '';
    return `<button type="button" class="${cx('sb-chg-row', o.selected && 'is-sel')}" role="${o.selected != null ? 'option' : 'button'}"${o.selected != null ? ` aria-selected="${!!o.selected}"` : ''}${act(o.act, o.arg != null ? o.arg : o.path)} title="${esc(o.path)}"><span class="st is-${esc(o.status)}" aria-label="${word}">${esc(o.status)}</span><span class="nm"><b>${esc(name)}</b>${parts.length && !o.noDir ? `<span class="dir">${esc(chgDir(parts))}</span>` : ''}</span>${badge}${o.multi > 1 ? `<span class="multi" title="Changed by ${o.multi} subtasks in this scope">×${o.multi}</span>` : ''}<span class="num tnum">${o.ins ? `<span class="ins">+${o.ins}</span>` : ''}${o.del ? `<span class="del">−${o.del}</span>` : ''}</span></button>`;
  }
  /* ---------- Changes window and Diff comments ---------- */
  /* One column per subtask, its height by lines changed (square root, so small subtasks stay visible): done columns grey,
   * the running one amber, not-started ones dashed and empty; `sel` columns are in the chosen scope. Columns with `act`
   * are buttons; `data-strip-n` lets main.js drag a range across them. Numbers under the columns, every `every`th one. */
  function ChangeStrip(o) {
    track('ChangeStrip');
    const every = o.every || 1;
    const cols = o.cols.map((c) => `<button type="button" class="${cx('col', 'is-' + c.state, c.sel && 'is-sel')}" style="--h:${c.h}%"${c.act ? act(c.act, c.arg) : ' disabled'} data-strip-n="${c.n}" aria-label="${esc(c.label || `Subtask ${c.n}`)}"${tipAttrs(c.tip)}><i></i></button>`).join('');
    const nums = o.cols.map((c) => `<span class="${cx(c.sel && 'is-sel')}">${c.n === 1 || c.n % every === 0 ? c.n : ''}</span>`).join('');
    return `<div class="${cx('sb-cstrip', o.tall && 'is-tall')}" role="group" aria-label="${esc(o.label || 'Lines changed per subtask')}"><div class="cols">${cols}</div><div class="nums" aria-hidden="true">${nums}</div></div>`;
  }
  /* A unified diff. `rows`: { hunk, src } headers; { k: 'a' | 'd' | 'c', old, num (new), text, rail (thread state color),
   * targeted (the composer is open on it), add: { act, arg, label } for the + button } lines; { slot } HTML under a line
   * (threads and the composer); { note } when nothing was recorded. Lines carry data-line for the c shortcut. */
  function DiffView(o) {
    track('DiffView');
    const rows = o.rows.map((r) => {
      if (r.note) return `<div class="note">${esc(r.note)}</div>`;
      if (r.hunk) return `<div class="hunk">${esc(r.hunk)}<span>${esc(r.src || '')}</span></div>`;
      if (r.slot != null) return `<div class="slot">${r.slot}</div>`;
      const sign = r.k === 'a' ? '+' : r.k === 'd' ? '−' : ' ';
      return `<div class="${cx('ln', 'is-' + r.k, r.rail && 'has-rail', r.targeted && 'is-target')}"${r.rail ? ` style="--rail:${r.rail}"` : ''}${r.line ? ` data-line="${esc(r.line)}"` : ''}><span class="g">${r.old ?? ''}</span><span class="g">${r.num ?? ''}</span>${r.add ? `<button type="button" class="add"${act(r.add.act, r.add.arg)} aria-label="${esc(r.add.label)}"${tipAttrs(r.add.label, 'C')}>${Icon('plus', 'xs')}</button>` : '<span></span>'}<code>${esc(sign + r.text)}</code></div>`;
    }).join('');
    return `<div class="sb-diff">${rows}</div>`;
  }
  /* A text box with a hint and actions under it: a new comment, a reply, an edit. `input` names the data-input the screen
   * reads; `focus` takes focus after render. */
  function InlineComposer(o) {
    track('InlineComposer');
    return `<div class="sb-icomp"><textarea id="${esc(o.id)}" data-input="${esc(o.input)}"${o.arg ? ` data-arg="${esc(o.arg)}"` : ''} placeholder="${esc(o.placeholder || '')}" aria-label="${esc(o.label || o.placeholder || 'Comment')}"${o.focus ? ' data-autofocus' : ''}>${esc(o.value || '')}</textarea><div class="ft">${o.hint ? `<span class="hint">${o.hint}</span>` : '<span></span>'}${join(o.actions)}</div></div>`;
  }
  /* A review thread on a diff line. Closed threads fold to one line (state Label, "You: …", replies, outcome) that opens it.
   * Open: optional outdated original line, then each comment: avatar, author, source, time, its chips (Badge severity,
   * finding ref, state Label) and icon actions, an outcome Label (Fixed · sha) and the body. `foot` is the reply box,
   * Resolve / Decline / Reopen, a delete confirmation (MarkRow) or the pending note. */
  function CommentThread(o) {
    track('CommentThread');
    if (o.folded) return `<div class="${cx('sb-thread', 'is-folded', 'is-' + o.state, o.flash && 'is-flash')}" id="${esc(o.id)}"><button type="button" class="fold"${act(o.act, o.arg)} aria-expanded="false">${o.lead}${join(o.chips)}<span class="snip">${o.snippet}</span>${o.end ? `<span class="end">${o.end}</span>` : ''}${Icon('chevronRight', 'xs')}</button></div>`;
    const comments = o.comments.map((c, i) => `<div class="${cx('c', i && 'is-reply')}">${c.avatar}<div class="main"><div class="hd"><b>${esc(c.who)}</b>${c.src ? `<span class="src mono">${esc(c.src)}</span>` : ''}<span class="when">${esc(c.when)}</span><span class="l-grow"></span>${join(c.chips)}${join(c.actions)}</div>${c.outcome || ''}${c.body}</div></div>`).join('');
    return `<div class="${cx('sb-thread', 'is-' + o.state, o.flash && 'is-flash')}" id="${esc(o.id)}">${o.original ? `<div class="orig"><b>${esc(o.num)}</b><code>${esc(o.original)}</code></div>` : ''}${comments}${o.foot ? `<div class="foot">${o.foot}</div>` : ''}</div>`;
  }
  /* The Changes window body: a toolbar, an optional strip, the scope line, the file list beside the file header and diff
   * (with the review bar docked under the diff), and the keyboard footer. All slots take component HTML. */
  function ChangesFrame(o) {
    track('ChangesFrame');
    return `<div class="sb-cframe"><div class="tb">${join(o.toolbar)}</div>${o.strip ? `<div class="strip">${o.strip}</div>` : ''}<div class="scope">${join(o.scope)}</div><div class="split"><div class="files" role="listbox" aria-label="Changed files" data-scroll="cw-files">${o.files}</div><div class="pane"><div class="hd">${join(o.head)}</div><div class="diff" data-scroll="cw-diff">${o.diff}</div>${o.bar || ''}</div></div><div class="ft">${o.foot}</div></div>`;
  }
  /* Keyboard hints in a line: [[keys, label]]. The footer of the Changes window and the command menu. */
  function KeyHints(items) { track('KeyHints'); return `<div class="sb-keyhints">${items.map(([keys, label]) => `<span>${Kbd(keys)}${esc(label)}</span>`).join('')}</div>`; }
  /* A window over the app (or inline on a catalog page): title, a muted sub line, header actions, then the body. */
  function WindowSheet(o) {
    track('WindowSheet');
    const sheet = `<section class="sb-sheet" role="${o.inline ? 'region' : 'dialog'}"${o.inline ? '' : ' aria-modal="true"'} aria-labelledby="${esc(o.id)}-title"><header class="sb-sheet-hd"><div><h2 id="${esc(o.id)}-title">${esc(o.title)}</h2>${o.sub ? `<span class="t-muted">${esc(o.sub)}</span>` : ''}</div><span class="l-grow"></span>${join(o.actions)}</header>${o.body}</section>`;
    return o.inline ? `<div class="sb-sheet-inline">${sheet}</div>` : `<div class="sb-sheet-layer">${sheet}</div>`;
  }
  /* The popup behind ChatChangesPill: Changes with the count and totals and Open diff, a note on a resumed chat (its
   * baseline is the first session's, so edits made while it was closed count), at most `limit` files (the rest are one
   * "N more files" row into the Changes window), then since when, commits and uncommitted edits. */
  function ChatChangesPanel(o) {
    track('ChatChangesPanel');
    const limit = o.limit || 6;
    const rows = o.files.slice(0, limit).map((f) => ChatChangeRow(Object.assign({ act: o.act }, f)));
    const more = o.files.length - limit;
    const made = [o.commits ? `${o.commits} ${o.commits === 1 ? 'commit' : 'commits'}` : '', o.uncommitted ? 'uncommitted edits' : ''].filter(Boolean).join(' and ');
    return `<div class="sb-chg" aria-label="Changes in this chat"><div class="sb-chg-hd"><b>Changes</b><span class="n">${o.files.length} ${o.files.length === 1 ? 'file' : 'files'}</span><span class="tot tnum"><span class="ins">+${o.ins}</span> <span class="del">−${o.del}</span></span><span class="l-grow"></span>${Button({ label: 'Open diff', icon: 'external', variant: 'ghost', size: 'sm', act: o.act, arg: '' })}</div>
      ${o.resumed ? `<div class="sb-chg-note">${Icon('history', 'xs')}<span>Counted from when this chat started on ${esc(o.resumed)}. Edits made while it was closed are included.</span></div>` : ''}
      <div class="sb-chg-list" role="list">${rows.join('')}${more > 0 ? `<button type="button" class="sb-chg-more"${act(o.act, '')}>${more} more ${more === 1 ? 'file' : 'files'} in the diff${Icon('arrowRight', 'xs')}</button>` : ''}</div>
      <div class="sb-chg-ft"><span>Since ${esc(o.since)}${made ? ` · ${made}` : ''}</span></div></div>`;
  }
  /* One saved chat in the resume palette: title with screenshot and CLI glyphs and the filter match in amber, age, then branch
   * and agent. Click selects it, a double click resumes it. */
  function ResumeRow(o) {
    track('ResumeRow');
    return `<div class="${cx('sb-rrow', o.active && 'active')}" id="resume-${o.index}" role="option" aria-selected="${!!o.active}" data-act="resume-pick" data-arg="${o.index}"${o.tip ? ` title="${esc(o.tip)}"` : ''}>
      <div class="sb-rrow-ti">${o.cli ? Icon('terminal', 'xs') : ''}${o.images ? Icon('image', 'xs') : ''}<b>${o.titleHtml}</b>${o.images > 1 ? `<span class="more">+${o.images - 1}</span>` : ''}<span class="when tnum">${esc(o.when)}</span></div>
      <div class="sb-rrow-sub">${o.branch ? `<span class="mono">${o.branchHtml || esc(o.branch)}</span><span>·</span>` : ''}<i class="sb-dot" style="--c:${o.agentColor}"></i><span>${o.agentHtml || esc(o.agent)}</span></div></div>`;
  }
  /* /resume: a centered palette. Filter bar with the scope (the composer's repository) across the top, saved chats grouped by
   * day on the left, the selected chat's latest messages on the right, keys and the count in the footer. */
  function ResumePalette(o) {
    track('ResumePalette');
    const list = o.empty
      ? `<div class="sb-resume-note"><b>${esc(o.empty.title)}</b><span>${esc(o.empty.body)}</span></div>`
      : o.groups.map((g) => `<div class="sb-resume-group" role="presentation">${esc(g.label)}</div>${join(g.rows)}`).join('') + (o.more ? `<div class="sb-resume-more">${o.more}</div>` : '');
    const p = o.preview;
    const lines = !p ? '' : p.cli
      ? `<div class="sb-resume-cli">${Icon('terminal', 'sm')}<span>${esc(`This session lives in the ${p.cli} CLI. Its messages load when you resume it.`)}</span></div>`
      : p.lines.map((l) => `<div class="${cx('sb-resume-line', l.you && 'you')}"><span class="who">${esc(l.who)}</span>${(l.images || []).map((f) => ChatPill({ icon: 'image', text: f })).join('')}${l.text ? `<p>${esc(l.text)}</p>` : ''}</div>`).join('');
    const preview = p
      ? `<div class="sb-resume-pv-hd"><h2>${esc(p.title)}</h2>${PropertyList(p.facts)}</div><div class="sb-resume-pv-lines" data-scroll="resume-pv">${lines}</div>`
      : `<div class="sb-resume-note"><span>Pick a chat to see its latest messages.</span></div>`;
    return `<div class="sb-modal-layer" data-act="resume-close-self"><div class="sb-resume" role="dialog" aria-modal="true" aria-label="Resume a chat">
      ${o.filter}
      <div class="sb-resume-body"><div class="sb-resume-list" id="resume-list" role="listbox" aria-label="Saved chats" data-scroll="resume">${list}</div>
        <div class="sb-resume-pv">${preview}<div class="sb-resume-pv-ft">${Button({ label: 'Cancel', size: 'sm', act: 'resume-close' })}${Button({ label: 'Resume', variant: 'primary', size: 'sm', act: 'resume-open', disabled: !p })}</div></div></div>
      <div class="sb-resume-ft">${Kbd('↑ ↓')}<span>move</span>${Kbd('↵')}<span>resume</span>${Kbd('esc')}<span>close</span><span class="l-grow"></span><span class="tnum">${esc(o.count)}</span></div>
    </div></div>`;
  }

  /* ---------- QA results: what the operator reads first in a QA report ---------- */
  const QA_TONE = { failed: 'var(--st-failed)', blocked: 'var(--st-blocked)', done: 'var(--st-done)', faint: 'var(--fg-muted)' };
  /* The verdict in the QA section's end slot: an 8px dot and the word in the tone color. */
  function QaVerdict(o) {
    track('QaVerdict');
    return `<span class="sb-qav" style="--c:${QA_TONE[o.tone] || QA_TONE.faint}"><i></i>${esc(o.word)}</span>`;
  }

  /* One row in a QA group: an open finding, something left behind, an untested area or an Unblock entry. The head has a 14px
   * glyph in the tone color, the ref (QA-1) in mono, the title, the state word and a summary `line` (HTML the caller
   * escaped). With `act` the head is a button that opens the body (`fields` [label, html] then `chips`) and the summary
   * folds to two lines while closed. Without `act` the row is static and its body always shows. */
  /* Fix selection (a Verify run's criteria and QA findings) adds a column on each side: `lead` (the checkbox) and `end` (the
   * Chat or Goal switch); `hint` is the shared-code line under the summary and `extra` (HTML) closes the body (marks). */
  function QaItem(o) {
    track('QaItem');
    const btn = !!o.act;
    const head = `${Icon(o.glyph || 'alert', 'sm')}<span class="ti">${o.ref ? `<span class="ref">${esc(o.ref)}</span>` : ''}${esc(o.title)}</span><span class="st">${o.state ? `<span>${esc(o.state)}</span>` : ''}${btn ? Icon('chevronRight', 'xs') : ''}</span>${o.line ? `<span class="ln">${o.line}</span>` : ''}${o.hint ? `<span class="ln">${overlap(o.hint)}</span>` : ''}`;
    const fields = (o.fields || []).filter((f) => f[1]).map(([label, html]) => `<div class="sb-qitem-fd"><span>${esc(label)}</span><div>${html}</div></div>`).join('');
    const chips = (o.chips || []).filter(Boolean);
    const body = (!btn || o.open) && (fields || chips.length || o.extra) ? `<div class="sb-qitem-bd">${fields}${chips.length ? `<div class="sb-qchips">${chips.join('')}</div>` : ''}${o.extra || ''}</div>` : '';
    const sel = o.lead != null || o.end != null;
    return `<div class="${cx('sb-qitem', btn && 'is-btn', sel && 'sb-qitem--sel')}" style="--c:${QA_TONE[o.tone] || QA_TONE.faint}"${o.id ? ` id="${esc(o.id)}"` : ''}>${sel ? `<div class="sb-qitem-lead">${o.lead || ''}</div>` : ''}${btn ? `<button type="button" class="sb-qitem-hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${head}</button>` : `<div class="sb-qitem-hd">${head}</div>`}${sel ? `<div class="sb-qitem-end">${o.end || ''}</div>` : ''}${body}</div>`;
  }
  /* A folded row in the box under the groups: Fixed by QA, Passed, Run details, Full report. Closed it shows the label and a
   * one-line preview; open, the preview hides and `rows` ({ title, html, chips }) or `body` show under a rule. `before`
   * (HTML) sits above the head while closed: the first paragraph of a report that has no tagged checks. */
  /* Also a validation report's parts (Checks run, Risk): `count` ("3 items") sits before the chevron and `flag` marks a part
   * the report flags to read with an orange edge and glyph. `id` lets the flag strip scroll to it. */
  function QaFold(o) {
    track('QaFold');
    const rows = o.rows ? `<div class="sb-qfold-rows">${o.rows.map((r) => `<div class="sb-qfold-row"><b>${esc(r.title)}</b>${r.html || ''}${r.chips && r.chips.length ? `<div class="sb-qchips">${join(r.chips)}</div>` : ''}</div>`).join('')}</div>` : `<div class="sb-qfold-bd">${o.body || ''}${(o.chips || []).length ? `<div class="sb-qchips">${join(o.chips)}</div>` : ''}</div>`;
    return `<div class="${cx('sb-qfold', o.flag && 'is-flag', o.count && 'has-cnt')}" style="--c:${o.flag ? QA_TONE.blocked : QA_TONE[o.tone] || 'var(--fg-faint)'}"${o.id ? ` id="${esc(o.id)}"` : ''}>${!o.open && o.before ? `<div class="sb-qfold-pre">${o.before}</div>` : ''}<button type="button" class="sb-qfold-hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${Icon(o.icon || (o.flag ? 'warn' : 'qaCheck'), 'sm')}<b>${esc(o.label)}</b><span class="pv">${o.open ? '' : esc(o.preview || '')}</span>${o.count ? `<span class="cnt">${esc(o.count)}</span>` : ''}${Icon('chevronRight', 'sm')}</button>${o.open ? rows : ''}</div>`;
  }
  /* The QA results block, read top down: the sentence, the groups that need the reader (Needs you, Left behind, Not tested,
   * Unblock), each a labelled Box of QaItems with an optional `head` (Select all in a Verify run) and `foot`, then one Box of
   * QaFolds and the faint targets line. */
  /* The QA results typography and box borders around QA parts (QaResults uses it; the catalog wraps single parts). */
  function QaFrame(body) { track('QaFrame'); return `<div class="sb-qa">${body}</div>`; }
  function QaResults(o) {
    track('QaResults');
    const groups = (o.groups || []).filter((g) => g.items.length).map((g) => `<div class="sb-qgrp"><h3>${esc(g.label)}<span class="n">${g.items.length}</span></h3><div class="sb-box">${g.head ? `<div class="sb-qgrp-hd">${g.head}</div>` : ''}${join(g.items)}${g.foot ? `<div class="sb-qgrp-ft">${g.foot}</div>` : ''}</div></div>`).join('');
    const folds = (o.folds || []).filter(Boolean);
    /* targets: [kind, word] pairs, the kind in fg-muted ("Desktop" incomplete, capability missing), split by dots. */
    const targets = (o.targets || []).map(([kind, word]) => `<span><b>${esc(kind)}</b> ${esc(word)}</span>`).join('<span>·</span>');
    return `<div class="sb-qa"><p class="sb-qa-st">${esc(o.sentence)}</p>${groups}${folds.length ? `<div class="sb-box sb-qfolds">${folds.join('')}</div>` : ''}${targets ? `<div class="sb-qa-tg">${targets}</div>` : ''}</div>`;
  }

  /* ---------- QA live: goal QA while it runs ---------- */
  /* Sample pixels standing in for a frame of the app under test: a sidebar, a header, rows and an amber button. `dim` for a
   * frame that is no longer live (restarting, no capture, ended); `cursor` draws the pointer QA is driving. */
  const qaFrame = (dim, cursor) => `<span class="${cx('sb-qframe', dim && 'is-dim')}" aria-hidden="true"><span class="sb"><i class="on"></i><i></i><i></i><i class="w"></i><i></i></span><span class="mn"><i class="h"></i><i class="r"></i><i></i><i class="r"></i><i class="btn"></i><i class="r"></i></span>${cursor ? '<span class="cur"></span>' : ''}</span>`;
  /* The 16:10 preview box. Kinds: `later` (video icon, "Video starts at <stage>" and why), `waiting` (spinner, the display is
   * up and nothing has drawn), `live` (frames, a LIVE badge, the criterion and display), `reconnect` (last frame dimmed under
   * a spinner while QA restarts the display), `nocapture` (last frame dimmed, NO VIDEO and why), `none` (no display at
   * all) and `ended` (the last frame as a still with its time). */
  function QaPreview(o) {
    track('QaPreview');
    const empty = (lead, title, text) => `<div class="sb-qpv-empty">${lead}${title ? `<b>${esc(title)}</b>` : ''}${text ? `<p>${esc(text)}</p>` : ''}</div>`;
    const spin = '<span class="sb-qpv-spin" aria-hidden="true"></span>';
    const badge = (cls, html) => `<span class="${cx('sb-qpv-badge', cls)}">${html}</span>`;
    let inner;
    switch (o.kind) {
      case 'later': inner = empty(Icon('video'), o.title, o.text); break;
      case 'waiting': inner = empty(spin, o.title, o.text); break;
      case 'live': inner = `${qaFrame(false, true)}${badge('is-live', '<i></i>LIVE')}<span class="sb-qpv-cap"><span>${esc(o.caption)}</span><span>${esc(o.info)}</span></span>`; break;
      case 'reconnect': inner = `${qaFrame(true)}${badge('is-warn', `${Icon('warn', 'xs')}DISPLAY RESTARTING`)}<span class="sb-qpv-veil">${empty(spin, null, o.text)}</span>`; break;
      case 'nocapture': inner = `${qaFrame(true)}${badge('is-warn', `${Icon('videoOff', 'xs')}NO VIDEO`)}<span class="sb-qpv-veil"><p>${esc(o.text)}</p></span>`; break;
      case 'none': inner = empty(Icon('videoOff'), o.title, o.text); break;
      case 'ended': inner = `${qaFrame(true)}${badge('is-ended', `LAST FRAME · ${esc(o.at)}`)}`; break;
      default: inner = '';
    }
    return `<div class="sb-qpv" role="img" aria-label="${esc(o.label || 'QA live preview')}"${o.kind === 'live' ? '' : ' aria-live="polite"'}>${inner}</div>`;
  }
  /* The stages of one QA attempt. Each row: a check (done), a spinning ring (now), warn, a dash (skipped, struck through) or
   * an empty ring (pending); the name with a `video` tag (red while the preview shows frames) or `headless`; the duration or
   * "now"; and one detail `line` (HTML the caller escaped). `summary` replaces the list with one row after QA ends. */
  function QaStageList(o) {
    track('QaStageList');
    if (o.summary) return `<button type="button" class="sb-qstages is-sum" aria-expanded="false"${act(o.act, o.arg)}>${Icon('check', 'sm')}<span>${esc(o.summary)}</span><span class="t">${esc(o.time || '')}</span></button>`;
    const glyph = (k) => k === 'done' ? Icon('check', 'sm') : k === 'warn' ? Icon('warn', 'sm') : k === 'skip' ? Icon('minus', 'sm') : `<span class="${cx('ring', k === 'now' && 'is-now')}"></span>`;
    const tag = (s) => s.tag === 'video' ? `<span class="${cx('sb-qtag', s.tagOn && 'is-on')}" title="The preview shows video during this stage">${Icon('video', 'xs')}video</span>` : s.tag === 'headless' ? '<span class="sb-qtag" title="Runs without a display">headless</span>' : '';
    return `<ol class="sb-qstages" aria-label="QA stages">${o.stages.map((s) => `<li class="${cx('sb-qstage', 'is-' + s.state)}"><span class="g">${glyph(s.state)}</span><span class="nm">${esc(s.name)}${tag(s)}</span><span class="t">${esc(s.time || '')}</span>${s.line ? `<span class="ln">${s.line}</span>` : ''}</li>`).join('')}</ol>`;
  }
  /* Screenshots QA saved under evidence/, newest first, three across. `total` counts every file. */
  function QaShots(o) {
    track('QaShots');
    if (!o.files || !o.files.length) return '';
    return `<div class="sb-qshots"><div class="hd"><span>Screenshots · ${o.total}</span>${o.total > o.files.length ? '<span>newest first</span>' : ''}</div><div class="row">${o.files.map((f) => `<button type="button" class="sb-qshot"${act('open-file', 'evidence/' + f)}${tipAttrs('evidence/' + f)}><span class="th">${qaFrame(false, true)}</span><span class="cap">${esc(f)}</span></button>`).join('')}</div></div>`;
  }
  /* The goal QA fix loop as a strip: QA 1 with its findings, qa_fix, QA 2. */
  function QaRounds(o) {
    track('QaRounds');
    return `<div class="sb-qrounds" aria-label="Goal QA rounds">${o.rounds.map((r) => `<span class="${cx('sb-qround', 'is-' + r.state)}">${r.icon ? Icon(r.icon, 'xs') : ''}${esc(r.label)}</span>`).join('<i></i>')}</div>`;
  }
  /* Goal QA while it runs, top down: the stage (or verdict) word with "stage N of 7 · elapsed", the round and invocation,
   * target chips, the round strip, the preview, an optional hint (when an earlier run reached video), a callout, the
   * screenshots and the stage list. */
  function QaLive(o) {
    track('QaLive');
    const targets = (o.targets || []).map((t) => `<span class="sb-qtgt" style="--c:${QA_TONE[t.tone] || 'var(--st-active)'}"><i></i>${esc(t.label)}</span>`).join('');
    return `<div class="sb-qlive">
      <div class="sb-qlive-hd"><span class="w" style="--c:${QA_TONE[o.tone] || 'var(--st-active)'}"><i></i>${esc(o.word)}</span><span class="m">${esc(o.meta)}</span></div>
      <p class="sb-qlive-sub">${o.sub}</p>
      ${targets ? `<div class="sb-qtgts">${targets}</div>` : ''}
      ${o.rounds ? QaRounds({ rounds: o.rounds }) : ''}
      ${QaPreview(o.preview)}
      ${o.hint ? `<p class="sb-qlive-hint">${Icon('clock', 'xs')}<span>${esc(o.hint)}</span></p>` : ''}
      ${o.callout || ''}
      ${o.shots ? QaShots(o.shots) : ''}
      ${QaStageList(o.stages)}
    </div>`;
  }
  /* An agent report as text: headings, paragraphs, bullet and numbered lists (an item indented two or more spaces nests
   * under the one before; an indented plain line continues it), fenced blocks, `code` and **bold**. A path under a home or
   * temp directory shows as its file name, with the full path in the tooltip. Each plain line is its own paragraph. */
  function ReportText(text) {
    track('ReportText');
    const inline = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*(.+?)\*\*/g, '<b>$1</b>').replace(/(?:\/home\/|\/Users\/|\/tmp\/|~\/)[^\s`"'<>(),;&]*/g, (m) => {
      const p = m.replace(/[.:!?]+$/, '');
      return `<span class="file" title="${p}">${p.replace(/\/+$/, '').split('/').pop()}</span>${m.slice(p.length)}`;
    });
    const out = [];
    let list = null, fence = null, tableEnd = -1;
    const lines = String(text || '').split('\n');
    const cells = (line) => line.trim().replace(/^\||\|$/g, '').split('|').map((cell) => cell.trim());
    const flush = () => { if (list) { out.push(`<${list.tag}>${list.items.map((i) => `<li>${inline(i.text)}${i.sub.length ? `<ul>${i.sub.map((x) => `<li>${inline(x)}</li>`).join('')}</ul>` : ''}</li>`).join('')}</${list.tag}>`); list = null; } };
    lines.forEach((raw, index) => {
      if (index < tableEnd) return;
      if (fence) { if (/^\s*```/.test(raw)) { out.push(`<pre>${esc(fence.join('\n'))}</pre>`); fence = null; } else fence.push(raw); return; }
      const line = raw.trim();
      if (line.startsWith('```')) { flush(); fence = []; return; }
      if (!line) return flush();
      if (line.startsWith('|') && lines[index + 1] && cells(lines[index + 1]).length === cells(line).length && cells(lines[index + 1]).every((cell) => /^:?-{3,}:?$/.test(cell))) {
        flush();
        const headings = cells(line), records = [];
        tableEnd = index + 2;
        while (tableEnd < lines.length && lines[tableEnd].trim().startsWith('|')) {
          records.push(`<div class="sb-report-record"><dl>${cells(lines[tableEnd++]).map((cell, column) => `<dt>${inline(headings[column] || '')}</dt><dd>${inline(cell)}</dd>`).join('')}</dl></div>`);
        }
        out.push(records.join(''));
        return;
      }
      if (/^#+\s/.test(line)) { flush(); out.push(`<h4>${inline(line.replace(/^#+\s*/, ''))}</h4>`); return; }
      const m = raw.match(/^(\s*)(\d+[.)]|[-*])\s+(.*)$/);
      if (m) {
        if (m[1].length >= 2 && list) { list.items[list.items.length - 1].sub.push(m[3]); return; }
        const tag = /\d/.test(m[2]) ? 'ol' : 'ul';
        if (list && list.tag !== tag) flush();
        list = list || { tag, items: [] };
        list.items.push({ text: m[3], sub: [] });
        return;
      }
      if (list && /^\s/.test(raw)) { const last = list.items[list.items.length - 1]; if (last.sub.length) last.sub[last.sub.length - 1] += ' ' + line; else last.text += ' ' + line; return; }
      flush(); out.push(`<p>${inline(line)}</p>`);
    });
    if (fence) out.push(`<pre>${esc(fence.join('\n'))}</pre>`);
    flush();
    return `<div class="sb-report">${out.join('')}</div>`;
  }

  /* ---------- Run outcome: the card a validation or Verify run opens with, and the parts under it ---------- */
  const RUN_TONE = { merged: 'var(--data-5)', done: 'var(--st-done)', active: 'var(--st-active)', blocked: 'var(--st-blocked)', failed: 'var(--st-failed)', neutral: 'var(--fg-2)', faint: 'var(--fg-faint)', accent: 'var(--accent-text)' };
  /* A bar split into segments. `segs` [{ n, tone }] grow by n (a Verify score: green fine, amber needs a look, red blocking,
   * in that order); `equal` draws one 5px segment per check instead, where a running check breathes and a skipped one is
   * hatched. With no segment it draws the empty track, so bars in a row stay aligned. Tones: done, blocked, failed, active,
   * skipped, pending, faint. */
  function SegmentBar(o) {
    track('SegmentBar');
    const segs = (o.segs || []).filter((x) => x.n > 0);
    /* `tall` (10px) is the Activity timeline: a segment with `act` jumps to its group, `tip` names it. */
    return `<div class="${cx('sb-segbar', o.equal && 'is-equal', o.tall && 'is-tall', !segs.length && 'is-empty')}" role="${o.tall ? 'group' : 'img'}" aria-label="${esc(o.label || (segs.length ? 'Score breakdown' : 'No score recorded'))}">${segs.map((x) => `<i class="${cx('is-' + x.tone, x.act && 'is-btn')}" style="flex:${x.n} 1 0"${act(x.act, x.arg)}${x.tip ? ` title="${esc(x.tip)}"` : ''}></i>`).join('')}</div>`;
  }
  /* Counts after colored dots: "6 passed · 1 running · 1 pending", then an optional plain `note` ("2 failed attempts fixed"). */
  function CheckTally(o) {
    track('CheckTally');
    return `<div class="sb-tally">${o.items.map((x) => `<span style="--c:${x.color}"><i></i>${x.n} ${esc(x.word)}</span>`).join('')}${o.note ? `<span>${esc(o.note)}</span>` : ''}</div>`;
  }
  /* The card under a validation or Verify run's header. A 3px edge and a 32px round glyph in the tone (`live` pulses it), a
   * faint `label` line (HTML: "<b>Finished</b> · agent's summary below"), then either `verdict` (16px/600 in the tone: the
   * Verify verdict or step) or `head` (15px: the agent's first sentence), `text`, the running check's `cmd` ({ dir, cmd,
   * clock }), the agent `signal` line and `extra` (a CheckTally and SegmentBar). `when` ({ value, note }) sits top right.
   * `cells` ({ key, value, sub, bar, act, arg, tip }) form the strip under it; with `act` a cell is a button that jumps to
   * its evidence. */
  function RunOutcome(o) {
    track('RunOutcome');
    const cells = (o.cells || []).length ? `<div class="sb-out-cells">${o.cells.map((x) => {
      const inner = `<span class="k">${esc(x.key)}</span><b class="v">${x.value}</b>${x.sub != null ? `<span class="s">${x.sub}</span>` : ''}${x.bar ? SegmentBar(x.bar) : ''}`;
      return x.act ? `<button type="button" class="sb-out-cell is-btn"${act(x.act, x.arg)}${tipAttrs(x.tip)}>${inner}</button>` : `<div class="sb-out-cell">${inner}</div>`;
    }).join('')}</div>` : '';
    const cmd = o.cmd ? `<div class="sb-out-cmd">${o.cmd.dir && o.cmd.dir !== '.' ? `<span>${esc(o.cmd.dir)}</span>` : ''}<code>${esc(o.cmd.cmd)}</code>${o.cmd.clock || ''}</div>` : '';
    return `<section class="sb-out" style="--c:${RUN_TONE[o.tone] || RUN_TONE.faint}" aria-label="${esc(o.aria || 'Outcome')}">
      <div class="sb-out-top"><span class="${cx('sb-out-ic', o.live && 'is-live')}">${Icon(o.icon || 'info')}</span>
        <div class="sb-out-main">${o.label ? `<div class="sb-out-lb">${o.label}</div>` : ''}${o.verdict ? `<div class="${cx('sb-out-verdict', o.plain && 'is-plain')}">${esc(o.verdict)}</div>` : ''}${o.head ? `<p class="sb-out-hd">${o.head}</p>` : ''}${o.text ? `<p class="sb-out-tx">${o.text}</p>` : ''}${cmd}${o.signal ? `<div class="sb-out-sig"><i></i><span>${esc(o.signal)}</span></div>` : ''}${join(o.extra)}${o.note ? `<p class="sb-out-note">${Icon('info', 'xs')}<span>${esc(o.note)}</span></p>` : ''}</div>
        ${o.when ? `<div class="sb-out-when"><b class="tnum">${o.when.value}</b>${esc(o.when.note || '')}</div>` : ''}</div>${cells}</section>`;
  }
  /* One check a validation agent reported: a 13px glyph in the state color (a spinner while it runs, a ring while it
   * waits), the name, the working directory and command in faint mono, the reason inline for a blocked or skipped check,
   * then "attempt N" after a retry, the total time, the state word and, when it has more than one attempt or a note, a
   * chevron. Open, it lists every attempt: number, time, outcome, exit code, duration and the agent's note. */
  const CHECK = { passed: ['Passed', 'check', 'var(--st-done)'], failed: ['Failed', 'x', 'var(--st-failed)'], blocked: ['Blocked', 'warn', 'var(--st-blocked)'], skipped: ['Skipped', 'minus', 'var(--fg-faint)'], running: ['Running', null, 'var(--st-active)'], pending: ['Pending', null, 'var(--fg-faint)'] };
  function CheckRow(o) {
    track('CheckRow');
    const [word, icon, color] = CHECK[o.state] || CHECK.pending;
    const glyph = o.state === 'running' ? '<span class="spin" role="img" aria-label="Running"></span>' : icon ? `<span class="ic" style="--c:${color}">${Icon(icon)}</span>` : '<span class="ring" role="img" aria-label="Pending"></span>';
    const side = `<span class="sb-chk-side">${o.attempt ? `<span class="att">attempt ${o.attempt}</span>` : ''}<span>${o.time || ''}</span><span class="w">${word}</span>${o.act ? Icon('chevronRight', 'xs') : '<span class="sp"></span>'}</span>`;
    const head = `<span class="gl">${glyph}</span><span class="bd"><span class="nm">${esc(o.name)}</span><span class="cmd">${o.dir && o.dir !== '.' ? `<span class="dir">${esc(o.dir)}</span> ` : ''}${esc(o.cmd)}</span>${o.reason ? `<span class="why">${o.reason}</span>` : ''}</span>${side}`;
    const atts = o.act && o.open ? `<div class="sb-chk-atts">${o.attempts.map((x) => `<div class="sb-chk-att"><span class="no">#${x.n}</span><span>${esc(x.at)}</span><span style="color:${(CHECK[x.outcome] || CHECK.pending)[2]}">${(CHECK[x.outcome] || CHECK.pending)[0]}</span><span>${x.exit == null ? '' : 'exit ' + x.exit}</span><span>${x.time || ''}</span>${x.note ? `<span class="note">${x.note}</span>` : ''}</div>`).join('')}</div>` : '';
    return `<div class="${cx('sb-chk', 'is-' + o.state)}" style="--c:${color}">${o.act ? `<button type="button" class="sb-chk-hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${head}</button>` : `<div class="sb-chk-hd">${head}</div>`}${atts}</div>`;
  }
  /* A Verify run's steps on a rail. Each step: an 18px node (done, warn, bad, skip, stop, wait, run, pend), its number and
   * name, "attempt N" and a chevron when it has a receipt, then a summary line of { text, tone, mono } parts. A step with
   * `act` opens its receipt (`body`) in a raised card. */
  const NODE_GLYPH = { done: 'check', warn: 'warn', bad: 'x', skip: 'minus', stop: 'pause', wait: 'alert', cut: 'x' };
  function StepTimeline(o) {
    track('StepTimeline');
    return `<ol class="sb-vsteps">${o.steps.map((x) => {
      const btn = !!x.act;
      const sum = (x.sum || []).filter(Boolean).map((p) => `<span class="${cx(p.tone && 'is-' + p.tone, p.mono && 'mono')}">${esc(p.text)}</span>`).join('');
      const head = `<span class="nm"><span class="ix">${x.index}</span>${esc(x.name)}</span><span class="sd">${x.attempt ? `attempt ${x.attempt}` : ''}${btn ? Icon('chevronRight', 'xs') : ''}</span>${sum ? `<span class="sm">${sum}</span>` : ''}`;
      return `<li class="${cx('sb-vstep', 'is-' + x.node, x.open && 'open')}"${x.id ? ` id="${esc(x.id)}"` : ''}><span class="rail"><span class="node">${NODE_GLYPH[x.node] ? Icon(NODE_GLYPH[x.node]) : ''}</span></span><div class="card">${btn ? `<button type="button" class="hd" aria-expanded="${!!x.open}"${act(x.act, x.arg)}>${head}</button>` : `<div class="hd">${head}</div>`}${x.open && x.body ? `<div class="bd">${x.body}</div>` : ''}</div></li>`;
    }).join('')}</ol>`;
  }
  /* What a Verify run's change does, above its outcome card: a faint label (and `note`), then the text. `oneLine` keeps an
   * unfinished run's intent (its first criterion) to one line with the full text in the tooltip; `foot` follows. */
  function WorkSummary(o) {
    track('WorkSummary');
    return `<section class="sb-wsum" aria-label="${esc(o.label)}"><div class="sb-wsum-lb">${esc(o.label)}${o.note ? `<span>· ${esc(o.note)}</span>` : ''}</div><p class="${cx('sb-wsum-tx', o.oneLine && 'is-one')}"${o.oneLine && o.tip ? ` title="${esc(o.tip)}"` : ''}>${o.html}</p>${o.link || ''}${o.foot ? `<div class="sb-wsum-ft">${esc(o.foot)}</div>` : ''}</section>`;
  }
  function VerifyContext(o) {
    track('VerifyContext');
    const config = { 'non-goals': ['x', 'exclusion', 'exclusions'], constraints: ['shield', 'requirement', 'requirements'], rollout: ['arrowRight'] }[o.kind];
    const note = o.kind === 'rollout' ? 'Release approach' : `${o.items.length} ${config[o.items.length === 1 ? 1 : 2]}`;
    return `<section class="sb-vcontext is-${esc(o.kind)}"><button type="button" class="hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}><span class="glyph">${Icon(config[0])}</span><span class="copy"><span class="title">${esc(o.label)}</span>${!o.open ? `<span class="preview">${esc(o.preview)}</span>` : ''}<span class="note">${esc(note)}</span></span>${Icon(o.open ? 'chevronDown' : 'chevronRight', 'xs')}</button>${o.open ? `<div class="body">${o.items.map((html) => `<div class="entry"><div>${html}</div></div>`).join('')}</div>` : ''}</section>`;
  }

  /* "Select all N" as the first row of a selectable list: one checkbox for the whole group, mixed while part is selected.
   * `boxed` pads it as the first row inside a Box; `rule` draws a hairline under it (a receipt's findings and tests); `note`
   * trails it ("Keep rows are left out"). */
  function SelectAll(o) {
    track('SelectAll');
    const on = o.state === 'on', mixed = o.state === 'mixed';
    return `<div class="${cx('sb-selall', o.boxed && 'is-boxed', o.rule && 'is-rule')}"><button type="button" class="sb-selall-btn" role="checkbox" aria-checked="${mixed ? 'mixed' : on}"${act(o.act, o.arg)}><span class="${cx('sb-check', (on || mixed) && 'on')}">${mixed ? Icon('minus') : on ? Icon('check') : ''}</span>Select all<span class="n">${o.count}</span></button>${o.note ? `<span class="note">${esc(o.note)}</span>` : ''}</div>`;
  }
  /* The bar that sticks to the bottom of the document while items are selected for fixing: "N selected", their labels, the
   * shared-file note, then the actions (Fix N in chat, Start goal with N, Clear). */
  function SelectionBar(o) {
    track('SelectionBar');
    return `<div class="sb-selbar" role="region" aria-label="${esc(o.label || 'Selected')}"><div class="sb-selbar-tx"><b>${esc(o.title)}</b>${o.sub ? `<span class="sub">${esc(o.sub)}</span>` : ''}${o.note ? `<span class="note">${Icon('link', 'xs')}<span>${o.note}</span></span>` : ''}</div><div class="sb-selbar-ac">${join(o.actions)}</div></div>`;
  }

  /* ---------- Validation run document (validation run design) ---------- */
  /* The column a validation run renders in. It takes the Verify column's metrics (VerifyDoc: header rhythm, 12px buttons,
   * bordered boxes, the outcome card at a 1.45 line height) and sets the outcome card to the validation design: label 2px
   * above the head, text 6px under it at 1.55, the signal 8px, the tally 6px and the check bar 12px below. The flag
   * strip sits 12px under the card; each section 24px under the last. */
  function ValidationDoc(children) { track('ValidationDoc'); return `<div class="sb-vdoc sb-valdoc">${join(children)}</div>`; }
  /* The strip under a validation card when the report flags parts to read: a warning glyph, "The report flags N things
   * to read" and one button per flagged part (`jumps`: { label, act, arg }) that opens the part and scrolls to it. */
  function FlagStrip(o) {
    track('FlagStrip');
    return `<div class="sb-flagged" role="note">${Icon('warn', 'sm')}<div class="mn"><b>${esc(o.title)}</b><div class="jumps">${o.jumps.map((j) => `<button type="button" class="jump"${act(j.act, j.arg)}>${esc(j.label)}</button>`).join('')}</div></div></div>`;
  }
  /* A 16px tag in the tone's color on a 14% tint: "reported by the agent" beside the Checks title. Tones as Badge. */
  function Tag(o) { track('Tag'); return `<span class="sb-tag" style="--c:${VTONE[o.tone] || o.color || VTONE.faint}">${esc(o.text)}</span>`; }
  /* Text that acts like a link: 11.5px in the accent, underlined on hover. Expand all and Collapse all on a report. */
  function LinkButton(o) { track('LinkButton'); return `<button type="button" class="sb-linkbtn"${act(o.act, o.arg)}>${esc(o.label)}${o.icon ? Icon(o.icon, 'xs') : ''}</button>`; }
  /* One faint line inside a Box that holds a section's place: "Waiting for the agent to list its checks." */
  function BoxNote(o) { track('BoxNote'); return `<div class="sb-boxnote">${esc(o.text)}</div>`; }
  /* The text of a report part: consecutive lines join into a paragraph, numbered and bullet lists (an item indented two or
   * more spaces nests under the one before; an indented plain line continues it), `code` and **bold**. */
  function partText(text) {
    const inline = (x) => esc(x).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    let html = '', para = [], list = null;
    const flushPara = () => { if (para.length) { html += `<p>${inline(para.join(' '))}</p>`; para = []; } };
    const flushList = () => { if (list) { html += `<${list.tag}>${list.items.map((i) => `<li>${inline(i.text)}${i.sub.length ? `<ul>${i.sub.map((x) => `<li>${inline(x)}</li>`).join('')}</ul>` : ''}</li>`).join('')}</${list.tag}>`; list = null; } };
    String(text || '').split('\n').forEach((raw) => {
      const m = raw.match(/^(\s*)(\d+\.|[-*])\s+(.*)$/);
      if (!raw.trim()) return flushPara();
      if (m) {
        flushPara();
        if (m[1].length >= 2 && list) { list.items[list.items.length - 1].sub.push(m[3]); return; }
        const tag = /\d/.test(m[2]) ? 'ol' : 'ul';
        if (!list || list.tag !== tag) { flushList(); list = { tag, items: [] }; }
        list.items.push({ text: m[3], sub: [] });
      } else if (list && /^\s+/.test(raw)) {
        const last = list.items[list.items.length - 1];
        if (last.sub.length) last.sub[last.sub.length - 1] += ' ' + raw.trim(); else last.text += ' ' + raw.trim();
      } else { flushList(); para.push(raw.trim()); }
    });
    flushPara(); flushList();
    return html;
  }
  /* One part of a validation report, a row in a Box: a chevron, the part's title (`html`), a one-line `preview` and the
   * item `count`. `flag` draws a 2px orange edge on a part the report flags. Open, the preview hides and `text` shows
   * as paragraphs and lists. `id` lets the flag strip scroll to it. */
  function ReportPart(o) {
    track('ReportPart');
    return `<div class="${cx('sb-vpart', o.flag && 'is-flag')}"${o.id ? ` id="${esc(o.id)}"` : ''}><button type="button" class="hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${Icon('chevronRight', 'xs')}<span class="lb">${o.html != null ? o.html : esc(o.title)}</span><span class="pv">${esc(o.preview || '')}</span><span class="cnt">${esc(o.count || '')}</span></button>${o.open ? `<div class="bd">${partText(o.text)}</div>` : ''}</div>`;
  }
  /* One validation run on the same branch, a row in a Box: an 8px dot in the state color, the run number, its line ("this
   * run" after the current one, which sits on the selected tint), then the agent, start, active time and the state word.
   * With `act` the row opens that run. */
  function BranchRun(o) {
    track('BranchRun');
    const inner = `<span class="dot" style="--c:${o.color}"></span><span class="seq">#${esc(o.seq)}</span><span class="ln">${esc(o.line)}${o.current ? '<span class="here">this run</span>' : ''}</span><span class="sd"><span>${esc(o.agent)}</span><span>${esc(o.started)}</span><span>${esc(o.time)}</span><span class="st" style="color:${o.color}">${esc(o.word)}</span></span>`;
    return `<button type="button" class="${cx('sb-vrun', o.current && 'is-cur')}"${o.current ? ' aria-current="true"' : ''}${act(o.act, o.arg)}>${inner}</button>`;
  }


  /* ---------- Monitor run document (design/monitor-run) ---------- */
  /* The column a monitor run renders in. It is a ValidationDoc too, so the header, the outcome card, boxes and sections take
   * the validation metrics; .sb-mondoc adds what differs: the PR number before the title, a breathing signal dot, the
   * actions row inside the card and the cap pips in its last cell. */
  function MonitorDoc(children) { track('MonitorDoc'); return `<div class="sb-vdoc sb-valdoc sb-mondoc">${join(children)}</div>`; }
  const MON_TONE = { now: 'var(--st-active)', done: 'var(--st-done)', fail: 'var(--st-failed)', conflict: 'var(--st-blocked)', blocked: 'var(--st-blocked)', merged: 'var(--data-5)', stopped: 'var(--fg-faint)' };
  /* The repair cap as `cap` 10px squares, `used` of them filled orange; red once all are used ("3 of 3"). */
  function CapPips(o) {
    track('CapPips');
    const cap = o.cap || 3;
    return `<span class="${cx('sb-mon-pips', o.used >= cap && 'is-full')}" role="img" aria-label="${esc(`${o.used} of ${cap} failed repairs`)}">${Array.from({ length: cap }, (_, i) => `<i class="${i < o.used ? 'on' : ''}"></i>`).join('')}</span>`;
  }
  /* Buttons inside a RunOutcome, 12px under its text: Monitor again, Open worktree, Open failing job, Open PR. */
  function OutcomeActions(buttons) { track('OutcomeActions'); return `<div class="sb-mon-acts">${join(buttons)}</div>`; }
  /* One row per CI round of a monitor run and a column per step: CI (monitor), Repair (monitor_fix), Publish (commit_push).
   * A cell has a 2px edge in its tone, the result in a few words, one faint line (the head sha in mono, the failing check,
   * the agent and time) and, for CI, one small bar per check (passed, failed, running, queued). The live cell breathes a dot
   * after its word; the selected cell sits on the accent tint with a ring. `empty` cells hold a faint line ("Starts on a
   * failure", "After the fix", "Cap reached"). With `act` a cell is a button. `compact` drops the step ids and narrows the
   * cycle column for the details pane. `foot` is [icon, text] pairs under the grid. */
  function MonitorCycles(o) {
    track('MonitorCycles');
    const head = [[o.compact ? '#' : 'Cycle', null], ['CI', 'monitor'], ['Repair', 'monitor_fix'], ['Publish', 'commit_push']]
      .map(([t, id], i) => `<div class="${cx('gh', i && 'col')}">${esc(t)}${id && !o.compact ? `<span class="id">${id}</span>` : ''}</div>`).join('');
    const cell = (c, first) => {
      if (!c || c.empty != null) return `<div class="${cx('cell is-empty', first && 'first')}">${esc((c && c.empty) || '—')}</div>`;
      const bar = c.bar ? `<span class="bar">${c.bar.length ? c.bar.map((b) => `<i class="is-${esc(b)}"></i>`).join('') : '<i></i><i></i><i></i>'}</span>` : '';
      const inner = `<span class="w"><span>${esc(c.word)}</span></span><span class="s">${c.sub || ''}</span>${bar}`;
      const cls = cx('cell', first && 'first', c.live && 'is-live', c.sel && 'is-sel');
      const style = ` style="--c:${MON_TONE[c.tone] || 'var(--fg-faint)'}"`;
      return c.act ? `<button type="button" class="${cls}"${style}${act(c.act, c.arg)} aria-pressed="${!!c.sel}">${inner}</button>` : `<div class="${cls}"${style}>${inner}</div>`;
    };
    const rows = o.rows.map((r, i) => `<div class="${cx('gn', !i && 'first')}">${esc(r.n)}<small>${esc(r.at)}</small></div>${r.cells.map((c) => cell(c, !i)).join('')}`).join('');
    const foot = o.foot ? `<div class="sb-mon-gfoot">${o.foot.map(([icon, text]) => `<span>${Icon(icon, 'xs')}${esc(text)}</span>`).join('')}</div>` : '';
    return `<div class="${cx('sb-mon-grid', o.compact && 'is-compact')}" role="group" aria-label="${esc(o.label || 'Monitor cycles')}">${head}${rows}</div>${foot}`;
  }
  /* A bordered box with a raised head line: a 12px glyph, the bold title, a faint `sub` (HTML) and an `end` slot (links,
   * a badge). The CI board, a repair, its files, the local checks and the publish receipt each sit in one. */
  function MonitorBox(o) {
    track('MonitorBox');
    return `<div class="sb-box sb-mon-box"><div class="sb-mon-bh">${Icon(o.icon || 'info', 'xs')}<b>${esc(o.title)}</b>${o.sub ? `<span class="sub">${o.sub}</span>` : ''}${o.end ? `<span class="end">${join(o.end)}</span>` : ''}</div>${join(o.body)}</div>`;
  }
  /* The summary band at the top of a MonitorBox: lines (MonitorLine), a SegmentBar or ResolveBar and a CheckTally, 8px apart. */
  function MonitorSummary(children) { track('MonitorSummary'); return `<div class="sb-mon-sum">${join(children)}</div>`; }
  /* One line in a MonitorSummary: a 12px glyph, the text (HTML) and a faint `end` (the last and next poll, commits on the
   * base). `color` tints the whole line (orange for a conflicting PR, purple once merged). */
  function MonitorLine(o) {
    track('MonitorLine');
    return `<div class="sb-mon-line"${o.color ? ` style="color:${o.color}"` : ''}>${Icon(o.icon || 'commit', 'xs')}<span>${o.html}</span>${o.end ? `<span class="end">${o.end}</span>` : ''}</div>`;
  }

  /* A 16px tag in mono on a tint: "Repair outcome: fixed" (ok), "2 of 3 hunks left" (warn), "rerun" (ok). */
  function MonBadge(o) { track('MonBadge'); return `<span class="${cx('sb-mon-badge', o.tone && 'is-' + o.tone)}">${esc(o.text)}</span>`; }
  /* A small caps group label inside a box: Failed 1, Running 2, Waiting 1, Passed 3, Fixing, Conflicted files. With `fold`
   * ({ act, arg }) a chevron before it opens or folds the group. */
  function MonitorGroup(o) {
    track('MonitorGroup');
    return `<div class="sb-mon-group">${o.fold ? `<button type="button" class="fd" aria-expanded="${!!o.fold.open}"${act(o.fold.act, o.fold.arg)} aria-label="${esc(`${o.fold.open ? 'Fold' : 'Show'} ${o.label}`)}">${Icon('chevronRight', 'xs')}</button>` : ''}${esc(o.label)}${o.count != null ? `<span>${esc(o.count)}</span>` : ''}</div>`;
  }
  /* A folded group as one line: a green check, the names on one faint line and "show". The whole line opens the group. */
  function FoldLine(o) {
    track('FoldLine');
    return `<div class="sb-mon-row"><button type="button" class="sb-mon-rh"${act(o.act, o.arg)}><span class="gl"><span class="ic" style="--c:var(--st-done)">${Icon('check')}</span></span><span class="bd"><span class="sub is-flush">${esc(o.text)}</span></span><span class="side"><span class="w">show</span></span></button></div>`;
  }
  /* One CI check: a 13px glyph (check, cross, a spinner while running, a dashed ring while queued, a ring once unfinished at
   * the final read, a dash when skipped or cancelled), the name, its workflow and runner in faint text with "running",
   * "queued for a runner" or "unfinished at the final read", the failure in one line (`why`, HTML), then "rerun", the
   * time, an external mark that opens the job and, with a log, a chevron. Open, the LogTail shows under it. Failing rows
   * sit on a red tint, running rows on a green one. */
  const MCHK = { pass: ['Passed', 'check', 'var(--st-done)'], fail: ['Failed', 'x', 'var(--st-failed)'], skipping: ['Skipped', 'minus', 'var(--fg-faint)'], cancel: ['Cancelled', 'minus', 'var(--fg-faint)'], pending: ['Running'], queued: ['Queued'], stale: ['Unfinished'] };
  function MonitorCheckRow(o) {
    track('MonitorCheckRow');
    const [word, icon, color] = MCHK[o.state] || MCHK.queued;
    const glyph = o.state === 'pending' ? '<span class="spin"></span>' : o.state === 'queued' ? '<span class="ring is-dash"></span>' : o.state === 'stale' ? '<span class="ring"></span>' : `<span class="ic" style="--c:${color}">${Icon(icon)}</span>`;
    const tail = o.mono || o.plain ? '' : o.state === 'pending' ? ' · running' : o.state === 'queued' ? ' · queued for a runner' : o.state === 'stale' ? ' · unfinished at the final read' : '';
    const ext = o.ext ? `<span class="ext" role="button" tabindex="0"${act(o.ext.act, o.ext.arg)}${tipAttrs('Open job on GitHub')}>${Icon('external', 'xs')}</span>` : '';
    const side = `<span class="side">${o.rerun ? MonBadge({ text: 'rerun', tone: 'ok' }) : ''}<span class="w">${o.state === 'queued' && !o.time ? 'waiting' : esc(o.time || '')}</span>${ext}${o.act ? Icon('chevronRight', 'xs') : ''}</span>`;
    const main = `<span class="gl" title="${word}">${glyph}</span><span class="bd"><span class="nm">${esc(o.name)}</span><span class="${cx('sub', o.mono && 'is-mono')}">${esc(o.job || '')}${tail}</span>${o.why ? `<span class="why">${o.why}</span>` : ''}</span>${side}`;
    const head = o.act ? `<button type="button" class="sb-mon-rh" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${main}</button>` : `<div class="sb-mon-rh">${main}</div>`;
    return `<div class="${cx('sb-mon-row', 'is-' + o.state)}">${head}${o.open && o.log ? o.log : ''}</div>`;
  }
  /* The failed-job log tail the runtime fetches for the repair brief: a mono head ("Failed-job log tail · run N", Copy and
   * Full log), then the lines on an inset. Error lines sit on a red tint with a 2px red edge; context lines are faint. */
  function LogTail(o) {
    track('LogTail');
    return `<div class="sb-mon-log"><div class="hd">${Icon('terminal', 'xs')}<span class="lb">Failed-job log tail${o.run ? ` · run ${esc(o.run)}` : ''}</span><span class="end">${join(o.actions)}</span></div><pre>${o.lines.map(([cls, t]) => `<span class="${esc(cls || '')}">${esc(t)}</span>`).join('')}</pre></div>`;
  }
  /* What happens next, on an inset strip at the foot of a box: an arrow and one sentence (HTML). */
  function NextLine(o) { track('NextLine'); return `<div class="sb-mon-next">${Icon('arrowRight', 'xs')}<span>${o.html}</span></div>`; }
  /* The agent doing a repair: a 24px avatar in the agent's color, the line (its live signal in green, or its explanation
   * when finished, HTML), the agent and model under it, and an `action` (Watch agent) on the right. */
  function RepairAgent(o) {
    track('RepairAgent');
    return `<div class="sb-mon-agent"><span class="av" style="--c:${o.color}">${esc(o.initials)}</span><div class="mn"><div class="tx">${o.html}</div><div class="sub">${esc(o.sub)}</div></div><span class="ac">${o.action || ''}</span></div>`;
  }
  /* A file a repair changed, from the edit journal: a glyph (pencil while edited, image for a binary), the path in faint mono
   * with the file name in fg (ellipsis at the start), +/− or "binary", and how long ago; the file being edited now sits on
   * a green tint and says "editing". */
  function ChangedFileRow(o) {
    track('ChangedFileRow');
    const parts = String(o.path).split('/'), name = parts.pop();
    const stat = o.binary ? '<span class="faint">binary</span>' : `<span class="ins">+${esc(o.ins)}</span> <span class="del">−${esc(o.del)}</span>`;
    return `<div class="${cx('sb-mon-file', o.live && 'is-live')}">${Icon(o.icon || (o.live ? 'pencil' : o.binary ? 'image' : 'file'), 'xs')}<span class="path" title="${esc(o.path)}">${parts.length ? `<span class="dir"><bdi>${esc(parts.join('/'))}/</bdi></span>` : ''}<b>${esc(name)}</b></span><span class="st">${o.stat === false ? '' : stat}</span><span class="ago">${o.live ? 'editing' : esc(o.ago || '')}</span></div>`;
  }
  /* A bar of resolved (green) over still conflicted (orange) files. */
  function ResolveBar(o) {
    track('ResolveBar');
    return `<div class="sb-mon-res" role="img" aria-label="${esc(`${o.done} of ${o.total} resolved`)}"><i style="width:${(o.done / o.total) * 100}%"></i><i class="c" style="width:${((o.total - o.done) / o.total) * 100}%"></i></div>`;
  }
  /* Two short notes side by side on insets, each under a mono caps label: what this PR and the base changed in a conflicted
   * file, a PR field before and after an edit. */
  function SideBySide(o) {
    track('SideBySide');
    return `<div class="${cx('sb-mon-sides', o.flush && 'is-flush')}">${o.items.map(([k, html]) => `<div class="side"><b>${esc(k)}</b>${html}</div>`).join('')}</div>`;
  }
  /* A file in a merge repair: its glyph (green check resolved, orange warning conflicted, a spinner while the agent edits
   * it), the path, "claude is editing this file", its hunks as 8px squares (green resolved, orange open) and a badge
   * (resolved, "1 of 3 hunks left", "1 of 1 binary left"); `sides` (SideBySide) under it. */
  function ConflictFileRow(o) {
    track('ConflictFileRow');
    const parts = String(o.path).split('/'), name = parts.pop();
    const left = o.hunks.filter((h) => !h).length;
    const glyph = o.state === 'resolved' ? `<span class="ic" style="--c:var(--st-done)">${Icon('check')}</span>` : o.live ? '<span class="spin"></span>' : `<span class="ic" style="--c:var(--st-blocked)">${Icon('warn')}</span>`;
    const badge = o.state === 'resolved' ? MonBadge({ text: 'resolved', tone: 'ok' }) : MonBadge({ text: `${left} of ${o.hunks.length} ${o.binary ? 'binary' : o.hunks.length === 1 ? 'hunk' : 'hunks'} left`, tone: 'warn' });
    return `<div class="${cx('sb-mon-row', o.state === 'conflict' && 'is-conflict')}"><div class="sb-mon-rh"><span class="gl">${glyph}</span><span class="bd"><span class="path" title="${esc(o.path)}"><span class="dir"><bdi>${esc(parts.join('/'))}/</bdi></span><b>${esc(name)}</b></span>${o.live ? `<span class="sub is-live">${esc(o.liveText || 'The agent is editing this file')}</span>` : ''}</span><span class="side"><span class="hunks">${o.hunks.map((h) => `<i class="${h ? 'on' : ''}"></i>`).join('')}</span>${badge}</span></div>${o.sides || ''}</div>`;
  }
  /* A repair's publication on a rail: each step a 17px node (done check, now breathing, wait dashed), the title, its time,
   * then the detail (HTML) or a `commit`: the sha chip, its parents with what each is (PR head, main tip) and the message on
   * a 2px rule. */
  function PublishSteps(o) {
    track('PublishSteps');
    return `<div class="sb-mon-steps">${o.steps.map((x) => {
      const c = x.commit;
      const detail = c ? `${Chip({ label: c.sha, icon: 'commit', mono: true, act: c.act, arg: c.arg, tip: `Copy ${c.sha}` })} <span class="pars"><span class="faint">parent${c.parents.length > 1 ? 's' : ''}</span>${c.parents.map(([sha, note]) => `${Chip({ mono: true, label: sha })}<span class="faint">${esc(note)}</span>`).join('')}</span><div class="msg">${esc(c.msg)}</div>` : (x.html || '');
      return `<div class="${cx('st', 'is-' + x.state)}"><span class="nd">${x.state === 'done' ? Icon('check', 'xs') : x.state === 'fail' ? Icon('x', 'xs') : ''}</span><span class="ti">${esc(x.title)}</span><span class="wh">${esc(x.when || '')}</span><div class="dt">${detail}</div></div>`;
    }).join('')}</div>`;
  }
  /* ---------- EventLines: every list of timed events (Activity groups and run events, phase card activity, monitor and
   * validation activity) ----------
   * A row is [time, html, color] or { at | ago (seconds, read relative: "4m ago"), lead (a 16px Avatar or StatusIcon in place
   * of the dot) | color, html | actor, action, object (mono), tip }. A thin rail joins the markers. `o.hidden` folds that
   * many earlier rows behind "Show N earlier" (`o.act`, `o.arg`). */
  function EventLines(rows, o) {
    track('EventLines');
    o = o || {};
    const line = (r) => {
      const x = Array.isArray(r) ? { at: r[0], html: r[1], color: r[2] } : r;
      const at = x.at != null ? x.at : x.ago != null ? fmtAgo(x.ago) : '';
      const html = x.html != null ? x.html : [x.actor ? `<b>${esc(x.actor)}</b>` : '', x.action ? `<span>${esc(x.action)}</span>` : '', x.object ? `<b class="${x.mono ? 'mono' : ''}">${esc(x.object)}</b>` : ''].filter(Boolean).join(' ');
      const tip = x.tip || [x.actor, x.action, x.object].filter(Boolean).join(' ');
      return `<div class="ev"${tip ? ` title="${esc(tip)}"` : ''}><span class="t">${esc(at)}</span>${x.lead ? `<span class="m">${x.lead}</span>` : `<span class="d" style="--c:${x.color || 'var(--fg-faint)'}"></span>`}<span class="tx">${html}</span></div>`;
    };
    const more = o.hidden ? `<button type="button" class="more"${act(o.act, o.arg)}>${Icon('chevronUp', 'sm')}Show ${o.hidden} earlier</button>` : '';
    return `<div class="sb-events">${more}${rows.map(line).join('')}</div>`;
  }


  /* ---------- Verify run document (Verify run design) ---------- */
  /* The column a Verify run renders in. It keeps the mock's rhythm (header lines 8, 6 and 12px apart, the summary and the
   * outcome card 20px below, each section 24px below the last) instead of the document's 28px gap, and sets the shared parts
   * it holds (Button, Checkbox, Box, Segmented, RunOutcome) to the mock's metrics. */
  function VerifyDoc(children) { track('VerifyDoc'); return `<div class="sb-vdoc">${join(children)}</div>`; }
  const VTONE = { done: 'var(--st-done)', failed: 'var(--st-failed)', blocked: 'var(--st-blocked)', active: 'var(--st-active)', accent: 'var(--accent-text)', neutral: 'var(--fg-2)', faint: 'var(--fg-faint)' };
  /* The head of a Verify run: the status pill (a dot in the state's color), the issue key in mono and the Verify pill; the
   * 20px title; one line of facts, each a 12px glyph and its text (`mono` for the repository, branch and range, `link` in
   * the accent for an issue URL, `act` makes the text a button); the run controls; then `after` (the superseded Banner,
   * the Intake Fold). A validation run adds `pills` ({ word, tone }: the fresh pill) after the kind and sets the title as
   * `html` ("Validation on" and the branch in code). */
  function VerifyHeader(o) {
    track('VerifyHeader');
    const meta = (o.meta || []).filter(Boolean).map((m) => {
      const text = `<span class="${cx(m.mono && 'mono')}">${m.html != null ? m.html : esc(m.text)}</span>`;
      const inner = m.act ? `<button type="button" class="${cx('mi', m.link && 'is-link')}"${act(m.act, m.arg)}${tipAttrs(m.tip)}>${text}</button>` : text;
      return `<span class="it"${!m.act && m.tip ? ` title="${esc(m.tip)}"` : ''}>${Icon(m.icon, 'xs')}${inner}</span>`;
    }).join('');
    return `<header class="sb-vhd"><div class="chips"><span class="sb-vpill" style="--c:${VTONE[o.status.tone] || VTONE.faint}"><i></i><span>${esc(o.status.word)}</span></span><span class="key">${esc(o.key)}</span><span class="sb-vpill">${esc(o.kind || 'Verify')}</span>${(o.pills || []).filter(Boolean).map((x) => `<span class="sb-vpill"${x.tone ? ` style="--c:${VTONE[x.tone] || VTONE.faint}"` : ''}>${x.tone ? '<i></i>' : ''}<span>${esc(x.word)}</span></span>`).join('')}</div><h1 class="ti">${o.html != null ? o.html : esc(o.title)}</h1><div class="meta">${meta}</div>${o.actions ? `<div class="ac">${join(o.actions)}</div>` : ''}${join(o.after)}</header>`;
  }
  /* A tinted line: a 14px glyph, the bold `title` and the `body` as one paragraph, `actions` on the right. A superseded
   * Verify run says a newer run replaced it. */
  function Banner(o) {
    track('Banner');
    return `<div class="sb-banner">${Icon(o.icon || 'info', 'sm')}<div class="tx">${o.title ? `<b>${esc(o.title)}</b> ` : ''}${esc(o.body || '')}</div>${o.actions ? join(o.actions) : ''}</div>`;
  }
  /* A quiet disclosure: a chevron, the 12px label and a faint `count` or `note`. `strong` sets the label in fg 500 (Activity).
   * Open, it shows `items` (HTML) as a list or `body` (`inset` indents it under the label). Folds that follow each other
   * close up. Intake, Non-goals, Technical
   * constraints, Rollout and Activity in a Verify run. */
  /* `lead` (a StatusIcon) sits after the chevron; `end` (HTML) is a right-aligned meta slot: Activity's subtask groups. */
  function Fold(o) {
    track('Fold');
    const body = !o.open ? '' : o.items ? `<ul>${o.items.map((x) => `<li>${x}</li>`).join('')}</ul>` : `<div class="${cx('bd', o.inset && 'is-inset')}">${o.body || ''}</div>`;
    return `<div class="sb-vfold"${o.id ? ` id="${esc(o.id)}"` : ''}><button type="button" class="hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}>${Icon('chevronRight', 'xs')}${o.lead || ''}<span class="${cx('lb', o.strong && 'is-strong')}">${esc(o.label)}</span>${o.count != null ? `<span class="n">${esc(o.count)}</span>` : ''}${o.note && !o.open ? `<span class="n">${esc(o.note)}</span>` : ''}${o.end ? `<span class="end">${o.end}</span>` : ''}</button>${body}</div>`;
  }
  /* Text as the agent or the operator wrote it, on an inset with a 2px rule: the free-text intake, or what criteria
   * extraction returned when it found no numbered criteria. Keeps line breaks. */
  function Quote(o) { track('Quote'); return `<p class="sb-quote">${o.html != null ? o.html : esc(o.text)}</p>`; }
  /* A section of a Verify run: the 13px title, a faint count, an `end` slot (the criteria filter), an optional `sub` line,
   * then the body. It never collapses. Without `title` it only spaces its body (Activity). */
  function VerifySection(o) {
    track('VerifySection');
    const hd = o.title || o.html ? `<div class="hd"><h2>${o.html != null ? o.html : esc(o.title)}</h2>${o.count != null ? `<span class="n">${esc(o.count)}</span>` : ''}${o.end ? `<span class="end">${join(o.end)}</span>` : ''}</div>` : '';
    return `<section class="sb-vsec"${o.id ? ` id="${esc(o.id)}"` : ''}>${hd}${o.sub ? `<p class="sub">${esc(o.sub)}</p>` : ''}${join(o.body)}</section>`;
  }
  /* An 18px tag in mono capitals on a 14% tint of its tone: a criterion grade (Met, Partial, Missing, To confirm, Not
   * audited, a mark), a finding's severity, a test verdict, a plan line. Tones: done, blocked, failed, accent, faint. */
  function Badge(o) { track('Badge'); return `<span class="sb-badge" style="--c:${VTONE[o.tone] || VTONE.faint}">${esc(o.text)}</span>`; }
  /* One acceptance criterion: its number, the text (two lines while closed), the Badge and a chevron; open, `body`. With
   * `select` it gains a checkbox column (`lead`) and a 104px column for the Chat or Goal switch (`end`); `hint` is the
   * shared-code line under the text. `empty` draws one muted line instead ("Nothing in this filter."). */
  function CriterionRow(o) {
    track('CriterionRow');
    if (o.empty) return `<div class="sb-crit"><div class="hd is-empty"><span></span><span class="t-muted">${esc(o.empty)}</span></div></div>`;
    const head = `<button type="button" class="hd" aria-expanded="${!!o.open}"${act(o.act, o.arg)}><span class="num">${esc(o.n)}</span><span class="ti">${o.html}</span><span class="sd">${o.badge ? Badge(o.badge) : ''}${o.act ? Icon('chevronRight', 'xs') : ''}</span>${overlap(o.hint)}</button>`;
    return `<div class="${cx('sb-crit', o.select && 'is-sel', o.audit && 'is-audit')}"${o.id ? ` id="${esc(o.id)}"` : ''}>${o.select ? `<div class="lead">${o.lead || ''}</div>` : ''}${head}${o.select ? `<div class="end">${o.end || ''}</div>` : ''}${o.open && (!o.audit || o.body) ? `<div class="bd">${o.body || ''}</div>` : ''}</div>`;
  }
  /* A faint 11.5px label over its text (`html`) or its FileChips (`chips`): Evidence, What's missing, Not found, Expected,
   * Steps. */
  function FieldText(o) { track('FieldText'); return `<div class="sb-ftx"><div class="lb">${esc(o.label)}</div>${o.chips ? FileChips(o.chips) : ''}${o.html || ''}</div>`; }
  /* The line under a gap criterion that takes it out of fixes: "Not a code gap here?" with Not in this repo and Spec
   * question, or what the mark means and Clear mark. */
  function MarkRow(o) { track('MarkRow'); return `<div class="sb-marks"><span>${esc(o.text)}</span>${join(o.actions)}</div>`; }
  /* ---------- Chip: every file, commit and action chip (criterion evidence, finding files, QA evidence and actions, test
   * files, commit shas). A 22px chip on bg-raised; `mono` for paths and shas; `icon` leads it. With `act` it is a button;
   * without one it only names something (a Verify run's QA evidence, whose directory is not stored). */
  function Chip(o) {
    track('Chip');
    const inner = `${o.icon ? Icon(o.icon, 'xs') : ''}<span>${esc(o.label)}</span>`;
    const cls = cx('sb-fchip', o.mono && 'is-mono', !o.act && 'is-static');
    return o.act ? `<button type="button" class="${cls}"${act(o.act, o.arg)}${tipAttrs(o.tip)}>${inner}</button>` : `<span class="${cls}"${o.tip ? tipAttrs(o.tip) : ''}>${inner}</span>`;
  }

  /* FileChips in a wrapping row, 4px apart. */
  function FileChips(chips) { track('FileChips'); return `<div class="sb-fchips">${join(chips)}</div>`; }
  /* "Chat" or "Goal" for one selected item: how its fix runs. 18px buttons in an inset. */
  function RouteSwitch(o) {
    track('RouteSwitch');
    return `<span class="sb-route" role="group" aria-label="Fix with">${o.items.map((it) => `<button type="button" aria-pressed="${it.id === o.value}"${act(o.act, it.id)}${tipAttrs(it.tip)}>${esc(it.label)}</button>`).join('')}</span>`;
  }

  /* One row of the unit test value table: the verdict Badge in a 74px column; the area, its first two file chips (and "+N")
   * and the Chat or Goal switch; why, then "→" and the action. Selection adds the checkbox column (empty for Keep rows). */
  function TestValueRow(o) {
    track('TestValueRow');
    const sel = o.lead != null, files = (o.files || []).filter(Boolean);
    return `<div class="${cx('sb-tval', sel && 'is-sel')}">${sel ? `<span class="cb">${o.lead}</span>` : ''}<span>${Badge(o.badge)}</span><div class="wl"><span class="what">${esc(o.area)}</span><span class="fl">${files.slice(0, 2).join('')}${files.length > 2 ? `<span class="more">+${files.length - 2}</span>` : ''}</span>${o.end ? `<span class="end">${o.end}</span>` : ''}</div><div class="why">${o.why}</div><div class="act">→ ${o.action}</div>${overlap(o.hint)}</div>`;
  }
  /* Badge and text lines in a 74px grid: the test value plan (Keep, Rewrite, Delete, Add). */
  function BadgeGrid(rows) { track('BadgeGrid'); return `<div class="sb-bgrid">${rows.map((r) => `${Badge(r.badge)}<span>${r.html}</span>`).join('')}</div>`; }
  /* A faint 11.5px caption with a hairline to the right edge: Plan. */
  function MiniRule(text) { track('MiniRule'); return `<div class="sb-mrule">${esc(text)}</div>`; }
  /* Label and value pairs in a 112px label column: a step's inputs, the flag audit. [label, value, mono]. */
  function KeyValues(rows) { track('KeyValues'); return `<dl class="sb-kv">${rows.map(([k, v, mono]) => `<dt>${esc(k)}</dt><dd class="${cx(mono && 'mono')}">${mono ? esc(v) : ReportText(v)}</dd>`).join('')}</dl>`; }
  /* Checks as lines, each a green check or a red cross and its text: the flag audit. */
  function CheckLines(items) { track('CheckLines'); return `<div class="sb-chks">${items.map((c) => `<div class="it"><span style="--c:${c.ok ? 'var(--st-done)' : 'var(--st-failed)'}">${Icon(c.ok ? 'check' : 'x', 'xs')}</span>${esc(c.text)}</div>`).join('')}</div>`; }
  /* Changed files per directory: the path, a 6px bar against the largest and the count. Past `max` (7) one faint line sums
   * the rest. */
  function DirBars(o) {
    track('DirBars');
    const cap = o.max || 7, top = o.rows[0] ? o.rows[0][1] : 1, rest = o.rows.slice(cap);
    const rows = o.rows.slice(0, cap).map(([d, n]) => `<div class="it"><span class="p">${esc(d)}</span><span class="tr"><i style="width:${(n / top) * 100}%"></i></span><span class="c">${n}</span></div>`).join('');
    return `<div class="sb-dirs">${rows}${rest.length ? `<div class="it"><span class="p">${rest.length} more directories</span><span></span><span class="c">${rest.reduce((t, d) => t + d[1], 0)}</span></div>` : ''}</div>`;
  }
  /* Text in the accent that acts: Show the criteria, Show the graded criteria. */
  function TextLink(o) { track('TextLink'); return `<button type="button" class="sb-tlink"${act(o.act, o.arg)}>${esc(o.label)}</button>`; }
  /* A Verify run's QA step: the QA verdict (QaVerdict) above the shared QA results (QaResults), so it reads as a standalone
   * QA run does. */
  function VerifyQa(o) {
    track('VerifyQa');
    return `<div class="sb-vqa">${o.verdict}${o.body}</div>`;
  }

  /* ---------- QA device: the sidebar footer switcher and its popup ---------- */
  const PIP = { ready: 'var(--st-done)', warn: 'var(--st-blocked)', off: 'var(--fg-faint)', err: 'var(--st-failed)' };
  /* The last row of the sidebar: the device glyph with a state pip (ready, warn, off, err), the device name (mono for an AVD)
   * over one faint line, and an up-down chevron. It opens DevicePanel above itself. */
  function DeviceSwitcher(o) {
    track('DeviceSwitcher');
    return `<button type="button" class="sb-devsw" id="${esc(o.id || 'qa-device')}"${act(o.act, o.arg)} aria-haspopup="dialog" aria-expanded="${!!o.expanded}" data-tip="QA device" aria-label="${esc(`QA device: ${o.title}, ${o.sub}`)}"><span class="gl">${Icon(o.icon || 'phone', 'sm')}<i style="--c:${PIP[o.pip] || PIP.off}"></i></span><span class="tx"><b class="${o.mono ? 'mono' : ''}">${esc(o.title)}</b><span>${esc(o.sub)}</span></span>${Icon('chevronsUpDown', 'xs')}</button>`;
  }
  /* One device in the popup: glyph, name (mono for an AVD), a facts line (state dot and word, transport, Android version),
   * an optional `fix` line, and a tick on the selected one. A `disabled` device (unauthorized, offline, held by another run)
   * shows but cannot be picked. `kb` is the keyboard highlight. */
  function DeviceOption(o) {
    track('DeviceOption');
    const facts = [o.state ? `<span class="${cx('st', o.state.lease && 'is-lease')}"><i class="sb-dot" style="--c:${o.state.color}"></i>${esc(o.state.text)}</span>` : '', ...(o.facts || []).map((f) => `<span>${f.icon ? Icon(f.icon, 'xs') : ''}${esc(f.text)}</span>`)].filter(Boolean).join('<span class="sep">·</span>');
    return `<div class="${cx('sb-devopt', o.kb && 'kb', o.disabled && 'is-off')}" role="option" aria-selected="${!!o.selected}" aria-disabled="${!!o.disabled}" data-dev="${o.index}"${o.disabled ? '' : act(o.act, o.arg)}>${Icon(o.icon || 'phone', 'sm')}<span class="bd"><span class="${cx('nm', o.mono && 'mono')}">${esc(o.name)}</span><span class="mt">${facts}</span>${o.fix ? `<span class="fx">${esc(o.fix)}</span>` : ''}</span><span class="tk">${o.selected ? Icon('check', 'sm') : ''}</span></div>`;
  }
  /* The popup: "QA device", the connected count and Refresh (R; spins while `checking`), then `lead` (Let QA choose), a rule
   * and the groups ({ head, rows, empty }), or a `problem` ({ title, html }) when adb is missing; the footer says when the
   * list was read and shows the keys. */
  function DevicePanel(o) {
    track('DevicePanel');
    const body = o.problem
      ? `<div class="sb-devpop-msg"><b>${esc(o.problem.title)}</b><span>${o.problem.html}</span></div>`
      : `${o.lead || ''}<div class="sb-menu-sep"></div>${(o.groups || []).map((g) => `<div class="sb-devpop-grp" role="presentation">${esc(g.head)}</div>${g.rows.length ? join(g.rows) : `<div class="sb-devpop-empty">${esc(g.empty)}</div>`}`).join('')}`;
    return `<div class="sb-devpop" aria-label="QA device"><div class="sb-devpop-hd"><b>QA device</b>${o.sub ? `<span class="n">${esc(o.sub)}</span>` : ''}<span class="l-grow"></span>${IconButton({ icon: 'refresh', label: 'Refresh', kbd: 'R', act: o.refreshAct || 'dev-refresh', size: 'sm', cls: o.checking ? 'is-spinning' : '' })}</div>
      <div class="sb-devpop-list" id="device-list" role="listbox" tabindex="-1" aria-label="Devices" data-zone="devices">${body}</div>
      <div class="sb-devpop-ft"><span>${o.checking ? 'Checking…' : 'Checked just now'}</span><span class="l-grow"></span>${Kbd('↑ ↓ ↵')}</div></div>`;
  }

  /* ---------- Run app (SONA-10): build the goal's app and open it on the picked QA device ---------- */
  /* A split button in the goal's run actions. The main part runs on `device` (the sidebar QA pick, or the device Let QA choose
   * would take); the chevron opens DevicePanel under itself and changes the same sidebar pick. While a build runs the main part
   * reads Building… and the chevron becomes Stop. `disabled` carries the reason as a tooltip and as the status line.
   * o: { id, device: { name, mono, boots }, state (idle, building, running, failed, contended, off), step, ms, reason, expanded } */
  function RunAppButton(o) {
    track('RunAppButton');
    const busy = o.state === 'building';
    const off = o.state === 'off';
    const label = busy ? 'Building…' : 'Run app';
    const name = o.device ? o.device.name : 'No device';
    const main = `<button type="button" class="${cx('sb-runbtn-main', busy && 'is-busy')}"${busy || off ? ' disabled' : act('app-run', o.id)} data-tip="${esc(off ? o.reason : o.tip || `Build debug and open it on ${name}${o.device && o.device.boots ? ', booting it first' : ''}`)}">${busy ? '<span class="sb-runbtn-spin"></span>' : Icon('play')}<span>${esc(label)}</span><span class="sb-runbtn-sep">·</span><span class="${cx('sb-runbtn-dev', o.device && o.device.mono && 'mono')}">${esc(name)}</span></button>`;
    const side = busy
      ? `<button type="button" class="sb-runbtn-side is-stop"${act('app-run-stop', o.id)}${tipAttrs('Stop the build')} aria-label="Stop the build">${Icon('stop', 'xs')}</button>`
      : `<button type="button" class="sb-runbtn-side" id="app-run-dev-${esc(o.id)}"${act('app-run-device', o.id)} aria-haspopup="dialog" aria-expanded="${!!o.expanded}"${tipAttrs('Choose device')} aria-label="Choose device">${Icon('chevronDown', 'xs')}</button>`;
    return `<span class="${cx('sb-runbtn', off && 'is-off')}" role="group" aria-label="Run app">${main}${side}</span>`;
  }
  /* The line under the run actions for the last Run app: building (spinner, step, time), running (green), failed (red),
   * contended (orange, names the run holding the device) or the disabled reason. Log links open the run's terminal tab. */
  function RunAppStatus(o) {
    track('RunAppStatus');
    if (!o || !o.state || o.state === 'idle') return '';
    const tone = { building: 'var(--st-active)', running: 'var(--st-done)', failed: 'var(--st-failed)', contended: 'var(--st-blocked)', stopped: 'var(--fg-faint)', off: 'var(--fg-faint)' }[o.state];
    const log = o.tab ? LinkButton({ label: o.state === 'failed' ? 'Open log' : 'Show log', act: 'term-tab', arg: o.tab }) : '';
    const lead = o.state === 'building' ? '<span class="sb-runbtn-spin is-sm"></span>' : `<i class="sb-dot" style="--c:${tone}"></i>`;
    return `<span class="${cx('sb-runapp-st', 'is-' + o.state)}">${lead}<span>${o.html || esc(o.text)}</span>${log}</span>`;
  }

  /* ---------- Plan run: a standalone plan phase (plan run design; desktop PlanStatus, PlanNowCard, PlanActivity, PlanSubtasks) ---------- */
  /* One glyph per stage or unit state: queued (dashed ring), now (green spinner), quiet (slow orange spinner), done (green
   * check), blocked (orange warning). */
  function planGlyph(state) {
    if (state === 'done') return `<span class="sb-pgl-ic is-done">${Icon('check', 'xs')}</span>`;
    if (state === 'blocked') return `<span class="sb-pgl-ic is-blocked">${Icon('warn', 'xs')}</span>`;
    return `<span class="${cx('sb-pgl', 'is-' + (state || 'queued'))}"></span>`;
  }
  /* The plan's three stages, the only place that shows stage status and time. A cell is a button that scrolls Activity to
   * that stage. stages: [{ label, id, state (done, now, quiet, blocked, queued), time (HTML), act, arg, tip }]. */
  function StageStrip(o) {
    track('StageStrip');
    return `<div class="sb-stages">${o.stages.map((x) => `<button type="button" class="${cx('sb-stage', 'is-' + x.state)}"${act(x.act, x.arg)} title="${esc(x.tip || '')}"><span class="top">${planGlyph(x.state)}<span class="trunc">${esc(x.label)}</span></span><span class="bot">${esc(x.id)}<span class="tm">${x.time || ''}</span></span></button>`).join('')}</div>`;
  }
  /* "What now" and "is it alive" in one block: the status sentence, the latest step (`step` HTML after a 24px tile with
   * `icon`) and the pulse line. Tone `live` is green, `quiet` orange once the agent stops producing output, `plain` drops
   * the frame for a finished plan. pulse: [{ html, beat, quiet }]; a beat leads with a breathing dot. */
  function PlanNow(o) {
    track('PlanNow');
    const pulse = (o.pulse || []).map((p) => `<span class="${cx(p.beat && 'beat', p.quiet && 'is-quiet')}">${p.beat ? `<span>${p.html}</span>` : p.html}</span>`).join('');
    return `<section class="${cx('sb-now', 'is-' + (o.tone || 'live'))}" aria-label="Now"><div class="sb-now-st">${esc(o.sentence)}</div>${o.step ? `<div class="sb-now-step"><span class="g">${Icon(o.icon || 'bot', 'xs')}</span><span class="what">${o.step}</span></div>` : ''}<div class="sb-now-pulse">${pulse}</div></section>`;
  }
  /* Activity's tools in the section header: All, Files, Commands and Notes with counts, then Follow (green while on). */
  /* `tools` ({ icon, label, act, arg }) are plain ghost buttons in the same style: Copy on the Transcript. */
  function FeedFilter(o) {
    track('FeedFilter');
    const seg = o.items ? `<span class="seg" role="group" aria-label="Filter">${o.items.map((x) => `<button type="button"${act(o.act, x.arg)} aria-pressed="${x.id === o.value}">${esc(x.label)}${x.count != null ? ` <span class="n">${x.count}</span>` : ''}</button>`).join('')}</span>` : '';
    const follow = o.follow ? `<button type="button" class="ghost"${act(o.follow.act, o.follow.arg)} aria-pressed="${!!o.follow.on}">${Icon('arrowDown', 'xs')}Follow</button>` : '';
    const tools = (o.tools || []).map((t) => `<button type="button" class="ghost"${act(t.act, t.arg)}>${Icon(t.icon, 'xs')}${esc(t.label)}</button>`).join('');
    return `<span class="sb-ffilter">${seg}${follow}${tools}</span>`;
  }
  /* The plan's Activity: a ten-row window that scrolls inside and follows the newest row (main.js fitPlanFeeds). One sticky
   * header per stage reached (name, id, counts; a click folds it), then a row per event: time, kind glyph, text, duration.
   * rows: { time, kind, unit, verb, text, file { label, act, arg }, link { label, act, arg }, kids, dur, live }. A note or a
   * row with more files opens in place. `tail` ({ text, quiet }) closes a running feed; Jump to latest shows while the reader
   * scrolled up. */
  const FEED_GLYPH = { read: 'fileText', write: 'pencil', search: 'search', cmd: 'terminal', note: 'message', mcp: 'link', phase: 'play', err: 'warn' };
  function PlanFeed(o) {
    track('PlanFeed');
    const row = (r) => {
      const exp = r.kind === 'note' || (r.kids && r.kids.length);
      let x = r.unit ? `<span class="unit">#${esc(r.unit)}</span>` : '';
      if (r.kind === 'note') x += `<span class="prose">${esc(r.text)}</span>`;
      else if (r.kind === 'phase') x += `${esc(r.verb)}${r.text ? ` <span class="verb">· ${esc(r.text)}</span>` : ''}`;
      else if (r.kind === 'err') x += `${esc(r.verb)} <span class="verb">· ${esc(r.text)}</span>`;
      else x += `<span class="verb">${esc(r.verb)}</span> ${r.file ? `<button type="button" class="file"${act(r.file.act, r.file.arg)}>${esc(r.file.label)}</button>` : `<code>${esc(r.text)}</code>`}`;
      if (r.link) x += `<button type="button" class="link"${act(r.link.act, r.link.arg)}>${esc(r.link.label)}</button>`;
      if (r.kids && r.kids.length) x += ` <span class="more">+${r.kids.length} more files</span><span class="kids">${r.kids.map((k) => `<code>${esc(k)}</code>`).join('')}</span>`;
      return `<div class="${cx('sb-pev', 'is-' + r.kind, r.live && 'is-live', exp && 'is-exp')}"${exp ? ' data-act="plan-ev"' : ''}><span class="ts">${esc(r.time)}</span><span class="k">${Icon(FEED_GLYPH[r.kind] || 'info', 'xs')}</span><span class="x">${x}</span><span class="dur">${esc(r.dur || '')}</span></div>`;
    };
    const groups = o.groups.map((g) => `<button type="button" class="${cx('sb-pgrp', g.shut && 'shut')}" id="${esc(g.anchor)}"${act(g.act, g.arg)} aria-expanded="${!g.shut}">${Icon('chevronDown', 'xs')}<span class="name">${esc(g.label)}</span><span class="id">${esc(g.id)}</span><span class="sum">${esc(g.sum || '')}</span></button>${g.shut ? '' : g.rows.map(row).join('')}`).join('');
    const tail = o.tail ? `<div class="sb-ptail">${o.tail.quiet ? '' : '<span class="dots"><i></i><i></i><i></i></span>'}${esc(o.tail.text)}</div>` : '';
    return `<div class="sb-pfeed-wrap"><div class="sb-pfeed" data-planfeed="${esc(o.id)}" data-scroll="pfeed-${esc(o.id)}">${groups}${tail}</div><button type="button" class="sb-pjump"${act(o.jump, o.id)}${o.follow ? ' hidden' : ''}>${Icon('arrowDown', 'xs')}Jump to latest</button></div>`;
  }
  /* A faint caption row inside a Box: "SUBTASK SPECS · WAVE 1 OF 2", "RUN SETUP · SAME IN EVERY STAGE". */
  function BoxHead(text) { track('BoxHead'); return `<div class="sb-boxhd">${esc(text)}</div>`; }
  /* Every file the plan writes: the parent files, then one row per subtask spec under `head`, or `empty` with two ghost bars
   * before the work is split. rows: { state, num, title, file (mono), side (HTML), line (HTML), why (an orange block reason),
   * act, arg, tip }. A row with `act` (a saved file) is a button that opens it. */
  function PlanBundle(o) {
    track('PlanBundle');
    const row = (r) => {
      const inner = `<span class="gl">${planGlyph(r.state)}</span><span class="ti">${r.num != null ? `<span class="ref">${esc(r.num)}</span>` : ''}${esc(r.title)}</span><span class="sd">${r.side || ''}</span>${r.line ? `<span class="ln"><span class="${r.why ? 'why' : 'out'}">${r.line}</span></span>` : ''}`;
      const cls = cx('sb-pun', r.file && 'is-file', 'is-' + r.state);
      return `<div class="sb-pun-it">${r.act ? `<button type="button" class="${cls}"${act(r.act, r.arg)}${r.tip ? ` title="${esc(r.tip)}"` : ''}>${inner}</button>` : `<div class="${cls}">${inner}</div>`}</div>`;
    };
    const units = o.units ? BoxHead(o.head) + o.units.map(row).join('') : `<div class="sb-pbundle-empty"><span>${esc(o.empty)}</span><i style="width:72%"></i><i style="width:58%"></i></div>`;
    return `<div class="sb-box sb-pbundle">${o.files.map(row).join('')}${units}</div>`;
  }
  /* Raw agent output in mono. `short` is a three-line window, `long` 340px; both start at the newest line (data-pin) and close
   * with Expand / Show less. `raw` is a briefing's exact text, 220px from the top, with `note` and Copy under it. */
  function Transcript(o) {
    track('Transcript');
    const size = o.size || 'short';
    const lines = o.lines.map(([t, l]) => `<div>${t ? `<span class="t">${esc(t)}</span>` : ''}${esc(l) || ' '}</div>`).join('');
    const foot = size === 'raw'
      ? `<div class="sb-tx-bar"><span>${esc(o.note || '')}</span>${o.copy != null ? `<button type="button" class="sb-tx-ghost"${act('copy', o.copy)}>${Icon('copy', 'xs')}Copy</button>` : ''}</div>`
      : `<button type="button" class="sb-tx-more"${act(o.act, o.arg)}>${Icon('chevronDown', 'xs')}${size === 'long' ? 'Show less' : 'Expand'}</button>`;
    return `<div class="${cx('sb-tx', 'is-' + size)}"><div class="sb-tx-lines"${o.scroll ? ` data-scroll="${esc(o.scroll)}"${size === 'raw' ? '' : ' data-pin'}` : ''}>${lines}</div>${foot}</div>`;
  }
  /* Labelled facts with wrapping values, 96px label column: a briefing's run setup. rows: { label, html, faint, file
   * { label, act, arg }, list [[id, text]] }. */
  function FactList(o) {
    track('FactList');
    const val = (r) => (r.list ? `<ol>${r.list.map(([id, t]) => `<li><span class="id">${esc(id)}</span><span>${esc(t)}</span></li>`).join('')}</ol>` : r.file ? `<button type="button" class="file"${act(r.file.act, r.file.arg)}>${esc(r.file.label)}</button>` : r.html);
    return `<div class="sb-facts"><dl>${o.rows.filter(Boolean).map((r) => `<dt>${esc(r.label)}</dt><dd class="${r.faint ? 'faint' : ''}">${val(r)}</dd>`).join('')}</dl></div>`;
  }
  /* A folded part of a briefing: a chevron, the name (`mono` for a stage id), a one-line preview that hides while open, and
   * `meta` (a size). `off` draws a row that cannot open (a stage without a saved briefing). `body` shows open. */
  function BriefFold(o) {
    track('BriefFold');
    if (o.off) return `<div class="sb-bfold is-off"><span></span><span class="${cx('name', o.mono && 'mono')}">${esc(o.label)}</span><span class="pv">${esc(o.preview || '')}</span><span class="meta"></span></div>`;
    return `<button type="button" class="sb-bfold"${o.id ? ` id="${esc(o.id)}"` : ''}${act(o.act, o.arg)} aria-expanded="${!!o.open}">${Icon('chevronDown', 'xs')}<span class="${cx('name', o.mono && 'mono')}">${esc(o.label)}</span><span class="pv">${esc(o.preview || '')}</span><span class="meta">${esc(o.meta || '')}</span></button>${o.open && o.body ? o.body : ''}`;
  }
  /* A bulleted list under an open BriefFold: the items of one digest heading. */
  /* A string instead of items is a faint note in the same place (the digest before preplan finishes). */
  function BriefBody(items) { track('BriefBody'); return typeof items === 'string' ? `<div class="sb-bbody is-note">${esc(items)}</div>` : `<div class="sb-bbody"><ul>${items.map((x) => `<li>${x}</li>`).join('')}</ul></div>`; }
  /* Briefing, collapsed: one bar per stage scaled by briefing size and split into run setup and preplan digest, three digest
   * lines (verified code, top risk, what was not read), the run setup as pills, a fade and the call to read more. The whole
   * card expands the section. flow: [{ label, size, setup, digest (fractions), none (text) }]; legend: [{ text, cls }];
   * facts: [{ icon, tone (code, risk), key, html }]; pills: [{ text, strong }]. */
  function BriefingTeaser(o) {
    track('BriefingTeaser');
    const flow = o.flow.map((f) => `<span class="${cx('st', f.none && 'none')}"><span class="lab">${esc(f.label)}<span class="kb">${esc(f.none || f.size)}</span></span><span class="${cx('bar', f.none && 'empty')}">${f.none ? '' : `<i class="setup" style="width:${(f.setup * 100).toFixed(1)}%"></i>${f.digest ? `<i class="dig" style="width:${(f.digest * 100).toFixed(1)}%"></i>` : ''}`}</span></span>`).join('');
    return `<button type="button" class="sb-brief"${act(o.act, o.arg)} aria-label="Expand briefing"><span class="flow">${flow}</span><span class="legend">${o.legend.map((l) => `<span><i class="${l.cls}"></i>${esc(l.text)}</span>`).join('')}</span><span class="facts">${o.facts.map((f) => `<span class="${cx('f', f.tone && 'is-' + f.tone)}">${Icon(f.icon, 'xs')}<span>${f.key ? `<span class="k">${esc(f.key)}</span> ` : ''}${f.html}</span></span>`).join('')}</span><span class="pills">${o.pills.map((p) => `<span class="${cx('pill', p.strong && 'strong')}">${esc(p.text)}</span>`).join('')}</span><span class="fade"></span><span class="cta">${Icon('chevronDown', 'xs')}${esc(o.cta)}</span></button>`;
  }

  /* ---------- Specs pane (specs pane design, variant A; desktop SpecSelectionBar) ---------- */
  /* The selected spec docked at the bottom of the Specs pane: key, title, progress and the favorite star; the status and agent
   * pills, the actions button and the one primary action the runtime allows; then a note (the pause notice, or the reason
   * run controls are off). notes: [{ text, tone (error) }]. */
  function SpecBar(o) {
    track('SpecBar');
    return `<section class="sb-specbar" role="region" aria-label="${esc(o.key)} controls"><div class="ln"><span class="key">${esc(o.key)}</span><b title="${esc(o.title)}">${esc(o.title)}</b>${o.count ? `<span class="n">${esc(o.count)}</span>` : ''}${o.star || ''}</div><div class="ln">${join(o.pills)}<span class="l-grow"></span>${join(o.actions)}</div>${(o.notes || []).map((n) => `<div class="${cx('note', n.tone && 'is-' + n.tone)}">${esc(n.text)}</div>`).join('')}</section>`;
  }
  /* A property as a 26px pill: the status (StatusIcon and word) or the agent (Avatar and name). With `act` it is a button
   * with a chevron that opens a menu; `need` marks a choice still to make ("Choose agent"). */
  function PropPill(o) {
    track('PropPill');
    const inner = `${o.lead || ''}<span>${esc(o.text)}</span>`;
    if (!o.act) return `<span class="sb-ppill is-static" title="${esc(o.tip || '')}">${inner}</span>`;
    return `<button type="button" class="${cx('sb-ppill', o.need && 'is-need')}"${act(o.act, o.arg)} aria-haspopup="menu" aria-expanded="${!!o.expanded}" title="${esc(o.tip || '')}"${o.id ? ` id="${esc(o.id)}"` : ''}>${inner}${Icon('chevronDown', 'xs')}</button>`;
  }

  /* ---------- Build indicator ----------
   * A build is a build tool command the runtime found in the run's agent process tree, matched against the repository's
   * platform pack: { state: running | passed | failed, cmd, short, kind, tool, ms, usual, t0, pid, started, exit, ago, prev }.
   * `usual` is the median of the same command's last five runs in the repository; null before there is history. */
  const buildSlow = (b) => b.state === 'running' && b.usual && b.ms > b.usual;
  const buildTone = (b) => (b.state === 'failed' ? 'is-failed' : buildSlow(b) ? 'is-slow' : '');
  const buildClock = (b) => (b.state === 'running' ? Clock({ ms: b.ms, t0: b.t0, live: true, secs: true }) : `<span class="tnum">${fmtDur(b.ms, true)}</span>`);
  /* Work row meta: shows while a build runs (spinner, short command, ticking time) or after it failed. A passed build stays
   * in the run document. Orange once the build runs past its usual time. */
  function BuildChip(b) {
    track('BuildChip');
    if (b.state === 'failed') return `<span class="sb-build-chip is-failed" data-tip="${esc(b.cmd + ' exited ' + b.exit + ' after ' + fmtDur(b.ms, true))}">${Icon('x', 'xs')}${esc(b.short)}<span class="tnum">failed</span></span>`;
    const tip = `${b.cmd} · ${b.kind}${b.usual ? ' · usually ' + fmtDur(b.usual, true) : ''}`;
    return `<span class="${cx('sb-build-chip', buildTone(b))}" data-tip="${esc(tip)}"><span class="sb-build-spin" aria-hidden="true"></span>${esc(b.short)}${buildClock(b)}</span>`;
  }
  /* Chat composer, beside the activity label: a command the chat waits on, either left running in the background or running
   * for a few seconds in the current turn. Spinner, a short name such as "gradlew check", the ticking time; what it runs on
   * hover. It never shows the full command. */
  function CommandChip(c) {
    track('CommandChip');
    return `<span class="sb-build-chip" data-tip="${esc(c.detail)}"><span class="sb-build-spin" aria-hidden="true"></span>${esc(c.name)}${Clock({ ms: c.ms, t0: c.t0, live: true, secs: true })}</span>`;
  }
  /* Run document, right under the header: Building, Build passed or Build failed, the full command, the time with the usual
   * time or how far over it, a bar against the usual time while it runs, then project type, tool, pid, start, exit code and
   * the previous build of the same command. */
  function BuildStrip(b) {
    track('BuildStrip');
    const running = b.state === 'running';
    const lead = running ? `<span class="sb-build-spin" role="img" aria-label="Running"></span>` : StatusIcon(b.state === 'passed' ? 'done' : 'failed');
    const word = running ? 'Building' : b.state === 'passed' ? 'Build passed' : 'Build failed';
    let sub;
    if (running && b.usual) sub = buildSlow(b) ? `<small class="over">${fmtDur(b.ms - b.usual, true)} over usual</small>` : `<small>usually ${fmtDur(b.usual, true)}</small>`;
    else if (running) sub = '<small>first run, no usual time yet</small>';
    else sub = `<small>finished ${esc(b.ago)}</small>`;
    const bar = running && b.usual ? `<div class="sb-build-track" aria-hidden="true"><i data-bar-ms="${b.ms}" data-bar-t0="${b.t0}" data-bar-of="${b.usual}" style="width:${Math.min(100, (b.ms / b.usual) * 100)}%"></i></div>` : '';
    const meta = [`<b>${esc(b.kind)}</b>`, esc(b.tool), `pid ${esc(b.pid)}`, `started ${esc(b.started)}`];
    if (!running) meta.push(`exit ${esc(b.exit)}`);
    if (b.prev) meta.push(`previous ${StatusIcon(b.prev[0])} ${esc(b.prev[1])}, ${esc(b.prev[2])}`);
    return `<div class="${cx('sb-build', buildTone(b))}" role="status" aria-live="polite">
      <div class="sb-build-top">${lead}<span class="sb-build-word">${word}</span><code class="sb-build-cmd" title="${esc(b.cmd)}">${esc(b.cmd)}</code><span class="sb-build-time">${buildClock(b)}${sub}</span></div>
      ${bar}<div class="sb-build-meta">${meta.join('<span>·</span>')}</div></div>`;
  }

  window.SB = {
    esc, cx, join, fmtDur, fmtAgo, shortId, shortPath, SUB_VERB: VERB, track, inScope, usage, STATES, ICONS: Object.keys(P),
    Icon, BrandMark, StatusIcon, Kbd, Button, IconButton, Tooltip, IdTag, ShortId, StaleIndicator, Label, Avatar,
    ProgressRing, Clock, Segmented, ViewTabs, Toggle, Checkbox, Input, Field,
    PropertyList, MenuItem, Menu, Popover, CommandPalette, CommandList, Dialog, Ledger, ShortcutGrid,
    Toast, ToastStack, Callout, Hint, EmptyState, SidebarItem, SidebarSection, RepoIcon, Sidebar, WorkspaceSwitcher, UsageWindow, UsageCard, UsagePanel,
    ViewHeader, Pager, GroupHeader, ListRow, SidePane, ReasonBlock, ExpandableText, foldButton, Checklist, FindingRow, EvidenceRow, EvidenceToggle, TerminalPane,
    DocHeader, Section, Box, MetricStrip, SubtaskRow, SubtaskList, SubtaskChip, SubtaskInspector, PhasePipeline,
    PhasePeek, AuditResult, PeekPart, PeekSignal, PeekCmd, PeekCells, PeekStats, PeekOutput, PeekCheck, PeekFacts,
    PaneSection, DetailsPanel, MenuHead, QaFrame, Counts, LiveText, Dot, Note, InspectorSection, InspectorLine, PhaseBlock, PickerButton, Range, ShellPrompt, AppShell, WorkArea, ScrollArea, DetailSplit, ChangeStrip, ChangesFrame, DiffView, InlineComposer, CommentThread, KeyHints, WindowSheet, Breakdown, TreeRow, SettingsHeader, SettingsGroup, SettingRow, TierBadge,
    ConfigRow, TrayPopover,
    ChatStatusMark, ChatTabs, SuggestionChip, StartCard, ChatMessage, ChatTranscript, ChatPill, Composer, StepLine, NewTaskHome,
    InboxList, InboxRow, FilterBar, TargetPicker, QueuedRow, QueuePanel, ChatChangesPill, ChatChangeRow, ChatChangesPanel, ResumeRow, ResumePalette,
    QaVerdict, QaItem, QaFold, QaResults, QaLive, QaPreview, QaStageList, QaShots, QaRounds, ReportText, DeviceSwitcher, DeviceOption, DevicePanel,
    SegmentBar, CheckTally, RunOutcome, CheckRow, StepTimeline, WorkSummary, VerifyContext, SelectAll, SelectionBar,
    ValidationDoc, FlagStrip, Tag, LinkButton, BoxNote, ReportPart, BranchRun,
    VerifyDoc, VerifyHeader, Banner, Fold, Quote, VerifySection, Badge, CriterionRow, FieldText, MarkRow, Chip, FileChips, RouteSwitch,
    TestValueRow, BadgeGrid, MiniRule, KeyValues, CheckLines, DirBars, TextLink, VerifyQa,
    StageStrip, PlanNow, FeedFilter, PlanFeed, BoxHead, PlanBundle, Transcript, FactList, BriefFold, BriefBody, BriefingTeaser, SpecBar, PropPill,
    BuildChip, BuildStrip, CommandChip, RunAppButton, RunAppStatus,
    MonitorDoc, CapPips, OutcomeActions, MonitorCycles, MonitorBox, MonitorSummary, MonitorLine, MonBadge, MonitorGroup, FoldLine,
    MonitorCheckRow, LogTail, NextLine, RepairAgent, ChangedFileRow, ResolveBar, SideBySide, ConflictFileRow, PublishSteps, EventLines,
  };
})();
