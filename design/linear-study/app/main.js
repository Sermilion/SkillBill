/* Controller: state, rendering, keyboard model, command menu, overlays, live clocks.
 * Every action maps to a real desktop-app or runtime capability; feedback text mirrors the app's messages. */
(function () {
  'use strict';
  const S = window.SB, D = window.DATA, X = window.Screens, L = window.Library;
  const esc = S.esc;
  const root = document.getElementById('root');
  const toastRoot = document.getElementById('toast-root');

  const st = {
    mode: modeFromHash(),
    view: 'work', prevView: 'work', section: null, filter: '', focus: 0, detailId: 'INF-7', propsOpen: true,
    historyTab: 'all', closed: new Set(), sideOpen: false,
    specsOpen: false, specProject: 'l-f2ea', specSel: 'SKILL-442', specOpen: new Set(['SKILL-442']), specQuery: '', specStatus: 'all', specRuns: {}, specConfirmCancel: null,
    termOpen: true, termHeight: 200, termRenaming: null, termSeq: 3, termActive: 1,
    termTabs: [
      { id: 1, directory: '~/IdeaProjects/skill-bill', name: 'skill-bill', branch: 'feat/SKILL-417-keep-ide-status-on-the-live-goal-phase' },
      { id: 2, directory: '~/dev/infra-terraform', name: 'infra-terraform', branch: 'feat/INF-7-remote-state-backend' },
    ],
    chosenAgent: {}, prereqs: new Set(), recovered: new Set(), opDecision: {}, secCollapse: {}, reasonOpen: new Set(),
    /* Subtask index: selection per goal ('3' or 'finish'), inline expansion per goal (narrow windows), open breakdowns, full activity feeds */
    subSel: {}, subOpen: {}, bdOpen: new Set(), feedAll: new Set(), narrow: false,
    settingsPage: 'general', agent: 'claude', cfgOpen: null, draft: null, matrix: JSON.parse(JSON.stringify(D.MATRIX)), phaseTiers: {},
    compaction: { enabled: true, window: 400000, pct: 70, phases: { review: { window: 600000, pct: 60 } } }, budget: D.BUDGET.map((r) => r.slice()),
    prefs: { dbPath: '', dbDraft: '', claudeDir: '~/.claude', retention: '24h', login: true, notify: Object.fromEntries(D.NOTIFICATIONS.map(([id, , on]) => [id, on])), specType: 'local', gradleWrapper: '', telemetry: 'anonymous' },
    popover: null, dialog: null, cmdk: null, toasts: [],
  };
  relabelTabs();
  /* Below 1080px the details pane hides and the subtask inspector opens inside its row. */
  const narrowQuery = window.matchMedia('(max-width: 1080px)');
  st.narrow = narrowQuery.matches;
  narrowQuery.addEventListener('change', (e) => { st.narrow = e.matches; render(); });

  function modeFromHash() {
    const h = (location.hash || '').replace('#', '');
    return h === 'components' || h === 'foundations' ? h : 'prototype';
  }
  /* A tab is named after its project; the name is numbered only while another tab shows it. */
  function relabelTabs() {
    const seen = {};
    st.termTabs.forEach((t) => { seen[t.name] = (seen[t.name] || 0) + 1; });
    const n = {};
    st.termTabs.forEach((t) => { n[t.name] = (n[t.name] || 0) + 1; t.label = seen[t.name] > 1 ? `${t.name} ${n[t.name]}` : t.name; });
  }

  /* ---------- usage census: render every screen once inside a named scope ---------- */
  function collectUsage() {
    const run = (name, fn) => S.inScope(name, fn);
    const as = (patch, fn) => { const keep = {}; Object.keys(patch).forEach((k) => { keep[k] = st[k]; st[k] = patch[k]; }); try { return fn(); } finally { Object.assign(st, keep); } };
    run('Work', () => as({ view: 'work', termOpen: false }, () => X.app(st)));
    run('Detail', () => [['INF-7'], ['DSN-31'], ['NEWS-176'], ['SKILL-417'], ['ATL-402'], ['SKILL-416'], ['ph-rk-review'], ['ph-atl-pr'], ['op-verify'], ['ph-atl-qa']].map(([id]) => as({ view: 'detail', detailId: id, termOpen: false, narrow: false, propsOpen: true, specsOpen: false, secCollapse: { 'incident-evidence': false } }, () => X.app(st))).join('')
      + as({ view: 'detail', detailId: 'SKILL-416', termOpen: false, narrow: false, propsOpen: true, specsOpen: false, subSel: { 'SKILL-416': 'finish' } }, () => X.app(st))
      + as({ view: 'detail', detailId: 'INF-7', termOpen: false, narrow: true }, () => X.app(st)));
    run('History', () => as({ view: 'history', termOpen: false }, () => X.app(st)));
    run('Specs pane', () => as({ specsOpen: true, specSel: 'SKILL-442', specConfirmCancel: 'SKILL-442' }, () => X.specsPane(st)) + as({ specsOpen: true, specSel: 'SKILL-442/1', specProject: 'l-9c1b' }, () => X.specsPane(st)));
    run('Terminal', () => X.terminal(st));
    [['general', 'General'], ['notifications', 'Notifications'], ['models', 'Models'], ['compaction', 'Compaction'], ['project', 'Project'], ['telemetry', 'Telemetry and agents']].forEach(([id, label]) => {
      const open = { models: ['phase:review', { target: { kind: 'phase', id: 'review' }, model: 'claude-opus-5-5', effort: 'xhigh', tier: 'reasoning' }], compaction: ['cmpphase:review', { target: { kind: 'cmpphase', id: 'review' }, window: '600000', pct: '60' }], project: ['budget:max_parent_packet_bytes', { target: { kind: 'budget', id: 'max_parent_packet_bytes' }, value: '1048576' }] }[id] || [null, null];
      run('Settings · ' + label, () => as({ view: 'settings', settingsPage: id, cfgOpen: open[0], draft: open[1] }, () => X.app(st)));
    });
    run('Command menu', () => S.CommandPalette({ context: S.Label({ text: 'INF-7', dot: false }), list: S.CommandList(commandGroups(), 0) }));
    run('Menus', () => ['agent', 'item', 'project', 'claude'].map((t) => S.Popover({ x: 0, y: 0, body: popoverBody({ type: t, arg: 'INF-7' }) })).join(''));
    run('Dialogs', () => [dialogBody({ type: 'recover', arg: 'DSN-31' }), dialogBody({ type: 'purge', arg: 'SKILL-438' }), dialogBody({ type: 'shortcuts' })].join(''));
    run('Toasts', () => S.ToastStack([S.Toast({ state: 'paused', title: 'Pause recorded', body: 'x' })]));
    run('Tooltip', () => S.Tooltip('Pause after subtask', 'P'));
    run('Tray', () => L.tray('goals') + L.tray('phases') + L.tray('operations'));
    run('Notifications', () => L.osNotifications());
  }

  /* ---------- rendering ---------- */
  function docBar() {
    return `<div class="doc-bar">${S.BrandMark(18)}<span class="doc-bar-ti">Skill Bill</span><span class="doc-bar-sub">Linear study</span>
      <span class="l-grow"></span>${S.Segmented({ act: 'mode', value: st.mode, items: [{ id: 'prototype', label: 'Prototype' }, { id: 'components', label: 'Components' }, { id: 'foundations', label: 'Foundations' }] })}</div>`;
  }
  function body() {
    if (st.mode === 'components') return L.components();
    if (st.mode === 'foundations') return L.foundations();
    return X.app(st);
  }
  function overlays() {
    let out = '';
    if (st.popover) out += S.Popover({ x: st.popover.x, y: st.popover.y, body: popoverBody(st.popover) });
    if (st.dialog) out += dialogBody(st.dialog);
    if (st.cmdk) {
      const it = contextItem();
      out += S.CommandPalette({ context: it ? S.Label({ text: X.rowKey(it), dot: false }) : '', query: st.cmdk.q, list: S.CommandList(filteredGroups(), st.cmdk.hl) });
    }
    return out;
  }
  function render() {
    const scrolls = {};
    root.querySelectorAll('[data-scroll]').forEach((el) => { scrolls[el.dataset.scroll] = el.scrollTop; });
    const ae = document.activeElement;
    const focusId = ae && ae.id && ae.tagName === 'INPUT' ? ae.id : null;
    const caret = focusId && ae.type !== 'range' ? ae.selectionStart : null;
    const zone = !focusId && ae && ae.closest && ae.closest('[data-zone]');
    const zoneId = zone ? zone.dataset.zone : null;
    hideTip();
    root.innerHTML = docBar() + `<div class="doc-body">${body()}</div>` + overlays();
    root.querySelectorAll('[data-scroll]').forEach((el) => { if (scrolls[el.dataset.scroll] != null) el.scrollTop = scrolls[el.dataset.scroll]; });
    if (st.cmdk) { const i = document.getElementById('cmdk-input'); if (i) { i.focus(); i.setSelectionRange(i.value.length, i.value.length); } }
    else if (st.termRenaming) { const i = document.getElementById('term-rename-in'); if (i) { i.focus(); i.select(); } }
    else if (focusId) { const el = document.getElementById(focusId); if (el) { el.focus(); if (caret != null && el.setSelectionRange) el.setSelectionRange(caret, caret); } }
    else if (zoneId) { const el = root.querySelector(`[data-zone="${zoneId}"]`); if (el) el.focus({ preventScroll: true }); }
    placePopover();
    if (st.dialog) { const b = document.getElementById('dialog-primary'); if (b) b.focus(); }
    else if (st.popover) { const m = root.querySelector('[data-pop] .sb-menu-item:not([disabled])'); if (m) m.focus(); }
    fitReasons();
    fillTokenValues();
    tick();
  }
  /* A folded ExpandableText offers Show more only when its lines overflow. */
  function fitReasons() {
    root.querySelectorAll('[data-fold]').forEach((p) => { const more = p.nextElementSibling; if (more) more.hidden = p.scrollHeight <= p.clientHeight + 1; });
  }
  function placePopover() {
    const pop = root.querySelector('[data-pop]');
    if (!pop || !st.popover) return;
    const r = pop.getBoundingClientRect();
    let x = st.popover.x, y = st.popover.y;
    if (x + r.width > innerWidth - 8) x = Math.max(8, innerWidth - r.width - 8);
    if (y + r.height > innerHeight - 8) y = Math.max(8, (st.popover.top || y) - r.height - 8);
    pop.style.left = x + 'px'; pop.style.top = y + 'px';
  }
  function fillTokenValues() {
    root.querySelectorAll('[data-token]').forEach((el) => { el.textContent = getComputedStyle(document.documentElement).getPropertyValue(el.dataset.token).trim(); });
  }
  function renderToasts() { toastRoot.innerHTML = st.toasts.length ? S.ToastStack(st.toasts.map((t) => S.Toast(t))) : ''; }
  function toast(t) {
    const id = Math.random().toString(36).slice(2);
    st.toasts = st.toasts.concat([Object.assign({ id }, t)]).slice(-3);
    renderToasts();
    setTimeout(() => { st.toasts = st.toasts.filter((x) => x.id !== id); renderToasts(); }, 5000);
  }

  /* ---------- live clocks ---------- */
  function tick() {
    const now = Date.now();
    document.querySelectorAll('[data-tick]').forEach((el) => { el.textContent = S.fmtDur(+el.dataset.ms + (now - +el.dataset.t0), el.hasAttribute('data-secs')); });
  }
  setInterval(tick, 1000);

  /* ---------- tooltips ---------- */
  let tipTimer = null, tipEl = null, tipFor = null;
  function hideTip() { clearTimeout(tipTimer); if (tipEl) { tipEl.remove(); tipEl = null; } tipFor = null; }
  document.addEventListener('mouseover', (e) => {
    const el = e.target.closest && e.target.closest('[data-tip]');
    if (el === tipFor) return;
    hideTip();
    if (!el) return;
    tipFor = el;
    tipTimer = setTimeout(() => {
      tipEl = document.createElement('div');
      tipEl.className = 'sb-tip';
      tipEl.innerHTML = S.Tooltip(el.dataset.tip, el.dataset.kbd);
      document.body.appendChild(tipEl);
      const r = el.getBoundingClientRect(), t = tipEl.getBoundingClientRect();
      let x = r.left + r.width / 2 - t.width / 2, y = r.bottom + 6;
      x = Math.max(8, Math.min(x, innerWidth - t.width - 8));
      if (y + t.height > innerHeight - 8) y = r.top - t.height - 6;
      tipEl.style.left = x + 'px'; tipEl.style.top = y + 'px';
    }, 450);
  });

  /* ---------- helpers ---------- */
  function contextItem() {
    if (st.mode !== 'prototype') return null;
    if (st.view === 'detail') return X.lookup(st.detailId);
    if (st.view === 'work' || st.view === 'history') { const r = X.rows[st.focus]; return r ? X.lookup(r.slice(5)) : null; }
    return null;
  }
  function anchorPos(el) { const r = el.getBoundingClientRect(); return { x: r.left, y: r.bottom + 4, top: r.top }; }
  function copyText(text) {
    try { const p = navigator.clipboard && navigator.clipboard.writeText(text); if (p && p.catch) p.catch(() => {}); } catch (e) { /* clipboard refused */ }
    toast({ icon: 'copy', title: 'Copied', body: text.length > 90 ? text.slice(0, 90) + '…' : text });
  }
  function go(view, extra) {
    if (view === 'settings' && st.view !== 'settings') st.prevView = st.view;
    st.view = view; st.focus = 0; st.sideOpen = false; st.popover = null;
    Object.assign(st, extra || {});
    if (st.mode !== 'prototype') { st.mode = 'prototype'; history.replaceState(null, '', '#prototype'); }
    render();
  }
  function openItem(id) { st.detailId = id; go('detail'); }
  function openTerminalFor(it) {
    const r = X.repoOf(it ? it.repo : 'skill-bill');
    const id = st.termSeq++;
    st.termTabs.push({ id, directory: r.path, name: r.name, branch: it && it.branch });
    relabelTabs();
    st.termActive = id; st.termOpen = true;
  }

  /* ---------- popovers ---------- */
  function popoverBody(p) {
    if (p.type === 'agent') {
      const it = String(p.arg).startsWith('spec:') ? null : X.lookup(p.arg);
      const current = st.chosenAgent[p.arg] || (it && it.runOptions && it.runOptions.agentId) || (it ? null : 'claude');
      return S.Menu({ items: [{ head: 'Run on' }, ...D.AGENT_CHOICES.map((a) => ({ lead: S.Avatar({ name: a, initials: D.AGENTS[a].initials, color: D.AGENTS[a].color, size: 'sm' }), label: a, act: 'agent-pick', arg: `${p.arg}|${a}`, checked: current === a }))] });
    }
    if (p.type === 'item') {
      const it = X.lookup(p.arg);
      if (!it) return '';
      const ro = it.runOptions || {};
      return S.Menu({ items: [
        { icon: 'external', label: 'Open', kbd: '↵', act: 'open', arg: it.id },
        { icon: 'terminal', label: 'Open terminal here', act: 'term-here', arg: it.id },
        it.reason ? { icon: 'copy', label: 'Copy reason', act: 'copy', arg: it.reason } : null,
        it.kind === 'goal' ? '-' : null,
        it.kind === 'goal' ? { icon: 'pause', label: it.pauseRequested ? 'Pause requested' : 'Pause after subtask', kbd: 'P', act: 'pause-soft', arg: it.id, disabled: !X.pauseEligible(it) } : null,
        it.kind === 'goal' ? { icon: 'stop', label: 'Pause', kbd: '⇧ P', act: 'pause-now', arg: it.id, danger: true, disabled: !X.controllable(it) } : null,
        X.resumable(it) ? { icon: 'play', label: ro.launchLabel || 'Resume', kbd: 'R', act: 'launch', arg: it.id, disabled: !ro.canLaunch } : null,
        { icon: 'x', label: 'Cancel', act: 'cancel', arg: it.id, danger: true, disabled: !ro.canCancel },
      ].filter(Boolean) });
    }
    if (p.type === 'project') return S.Menu({ items: [{ head: 'Choose project' }, ...D.projects.map((pr) => ({ lead: S.RepoIcon(X.repoOf(pr.name)), label: pr.name, act: 'project-pick', arg: pr.id, checked: st.specProject === pr.id }))] });
    if (p.type === 'claude') return S.Menu({ items: [{ head: 'Claude config directories found' }, ...D.CLAUDE_DIRS.map((d) => ({ icon: 'folder', html: `<span class="mono">${esc(d)}</span>`, label: d, act: 'claude-pick', arg: d, checked: st.prefs.claudeDir === d }))] });
    return '';
  }

  /* ---------- dialogs ---------- */
  function dialogBody(d) {
    if (d.type === 'recover') {
      const it = X.lookup(d.arg);
      const pv = it.incident.preview;
      return S.Dialog({ title: pv.label, icon: S.Icon('refresh'), body: S.PropertyList([{ label: 'Effects', value: esc(pv.effects) }, { label: 'Workflow', value: S.ShortId({ value: pv.workflow, label: 'workflow id' }) }, { label: 'Phase', value: `<span class="mono">${esc(pv.phase)}</span>` }]), actions: [S.Button({ label: 'Cancel', variant: 'ghost', act: 'dialog-close' }), S.Button({ label: 'Apply and resume', variant: 'primary', act: 'recover-confirm', arg: d.arg, id: 'dialog-primary' })] });
    }
    if (d.type === 'purge') return S.Dialog({ title: `Purge ${d.arg}?`, icon: S.Icon('trash'), body: `<span>Purge deletes everything Skill Bill recorded while running this spec, so it can start again from scratch.</span>${S.Ledger({ leftTitle: 'Deleted', left: ['Goal and subtask run history', 'Run evidence and task tracking', 'Checkpoint refs', 'Subtask progress, back to not started'], rightTitle: 'Kept', right: ['Every spec file in the folder', 'Your branch and its commits', 'Open pull requests'] })}<span>This can’t be undone. Afterwards the spec shows as not started and Start runs it from the first subtask.</span>`, actions: [S.Button({ label: 'Keep run history', variant: 'ghost', act: 'dialog-close', id: 'dialog-primary' }), S.Button({ label: `Purge ${d.arg}`, variant: 'danger-solid', act: 'purge-confirm', arg: d.arg })] });
    if (d.type === 'shortcuts') return S.Dialog({ title: 'Keyboard shortcuts', icon: S.Icon('keyboard'), body: S.ShortcutGrid(SHORTCUTS), actions: [S.Button({ label: 'Done', variant: 'primary', act: 'dialog-close', id: 'dialog-primary' })] });
    return '';
  }
  const SHORTCUTS = [['Command menu', '⌘ K'], ['Go to Work', 'G W'], ['Go to History', 'G H'], ['Toggle specs pane', 'G S'], ['Go to Settings', 'G ,'], ['Toggle terminal', '⌃ `'], ['New terminal tab', '⌃ ⇧ `'], ['Filter', '/'], ['Move down / up', 'J K'], ['Open', '↵'], ['Move in the Subtasks list', '↑ ↓'], ['Open the subtask spec file', '↵'], ['Pause after subtask', 'P'], ['Pause now', '⇧ P'], ['Resume', 'R'], ['Toggle details pane', '⌘ I'], ['Toggle sidebar', '['], ['Back / close', 'esc']];

  /* ---------- command menu ---------- */
  function commandGroups() {
    const it = contextItem();
    const groups = [];
    if (it) {
      const ro = it.runOptions || {};
      groups.push({ head: X.rowKey(it), items: [
        st.view !== 'detail' && { id: 'open:' + it.id, icon: 'external', label: `Open ${X.rowKey(it)}`, kbd: '↵' },
        X.pauseEligible(it) && { id: 'pause-soft:' + it.id, icon: 'pause', label: 'Pause after subtask', kbd: 'P' },
        X.controllable(it) && { id: 'pause-now:' + it.id, icon: 'stop', label: 'Pause now', kbd: '⇧ P' },
        X.resumable(it) && ro.canLaunch && { id: 'launch:' + it.id, icon: 'play', label: ro.launchLabel || 'Resume', kbd: 'R' },
        ro.canCancel && { id: 'cancel:' + it.id, icon: 'x', label: 'Cancel' },
        it.reason && { id: 'copy:' + it.reason, icon: 'copy', label: 'Copy reason' },
        { id: 'term-here:' + it.id, icon: 'terminal', label: `Open terminal in ${it.repo}` },
      ].filter(Boolean) });
    }
    groups.push({ head: 'Navigation', items: [
      { id: 'nav:work', icon: 'target', label: 'Go to Work', kbd: 'G W' },
      ...X.SECTIONS.map(([id, label, state]) => ({ id: 'section:' + id, lead: S.StatusIcon(state, 0.5), label: `Go to ${label}` })),
      { id: 'nav:history', icon: 'history', label: 'Go to History', kbd: 'G H' }, { id: 'nav:settings', icon: 'sliders', label: 'Go to Settings', kbd: 'G ,' },
      { id: 'mode:components', icon: 'layers', label: 'Open component library' },
    ] });
    groups.push({ head: 'Panes', items: [
      { id: 'specs', icon: 'fileText', label: st.specsOpen ? 'Close specs pane' : 'Open specs pane', kbd: 'G S' },
      { id: 'term', icon: 'terminal', label: st.termOpen ? 'Close terminal' : 'Open terminal', kbd: '⌃ `' },
      { id: 'term-new', icon: 'plus', label: 'New terminal tab', kbd: '⌃ ⇧ `' },
      { id: 'refresh', icon: 'refresh', label: 'Refresh' },
    ] });
    groups.push({ head: 'Work', items: D.items.map((x) => ({ id: 'open:' + x.id, lead: X.statusIcon(x), label: `${X.rowKey(x)} ${x.title} ${x.repo}`, html: `<span class="t-faint" style="margin-right:8px">${esc(X.rowKey(x))}</span>${esc(x.title)}`, end: esc(x.repo) })) });
    groups.push({ head: 'Config', items: [{ id: 'cfg:models', icon: 'cpu', label: 'Configure models' }, { id: 'cfg:compaction', icon: 'gauge', label: 'Configure compaction' }, { id: 'shortcuts', icon: 'keyboard', label: 'Keyboard shortcuts', kbd: '?' }] });
    return groups;
  }
  function filteredGroups() {
    const terms = (st.cmdk && st.cmdk.q || '').toLowerCase().trim().split(/\s+/).filter(Boolean);
    const groups = commandGroups().map((g) => ({ head: g.head, items: g.items.filter((it) => terms.every((t) => (it.label + ' ' + g.head).toLowerCase().includes(t))) }));
    st.cmdk.flat = groups.flatMap((g) => g.items);
    return groups;
  }
  function renderCmdkList() {
    const list = document.getElementById('cmdk-list');
    if (!list) return;
    list.innerHTML = S.CommandList(filteredGroups(), st.cmdk.hl);
    const hl = list.querySelector('.hl'); if (hl) hl.scrollIntoView({ block: 'nearest' });
  }
  function runCommand(id) {
    st.cmdk = null;
    const i = id.indexOf(':');
    const kind = i < 0 ? id : id.slice(0, i), arg = i < 0 ? null : id.slice(i + 1);
    if (kind === 'nav') return go(arg);
    if (kind === 'section') return A.section(arg);
    if (kind === 'open') return openItem(arg);
    if (kind === 'copy') { render(); return copyText(arg); }
    if (kind === 'mode') return A.mode(arg);
    if (kind === 'specs') return A['specs-toggle']();
    if (kind === 'term') return A['term-toggle']();
    if (kind === 'cfg') { st.settingsPage = arg; return go('settings'); }
    if (A[kind]) return A[kind](arg);
    render();
  }

  /* ---------- actions ---------- */
  const item = (id) => X.lookup(id);
  const A = {
    noop() {},
    mode(m) { st.mode = m; st.popover = null; history.replaceState(null, '', '#' + m); render(); const sc = root.querySelector('[data-scroll="lib"],[data-scroll="found"]'); if (sc) sc.scrollTop = 0; },
    anchor(id) { const el = document.getElementById(id); if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' }); },
    nav(v) { go(v, v === 'work' ? { section: null } : {}); },
    section(id) { go('work', { section: id }); },
    open(id) { openItem(id); },
    'settings-back'() { go(st.prevView || 'work'); },
    'settings-page'(id) { st.settingsPage = id; st.cfgOpen = null; st.draft = null; render(); },
    refresh() { const it = st.view === 'detail' && item(st.detailId); if (it && it.refreshFailed) { it.refreshFailed = false; } render(); },
    /* key|default: a toggle sticks per section key, so it holds while paging between items */
    /* Goal assessment order; it holds while paging, like section collapse. */
    'assess-order'(id) { st.assessOrder = id; render(); },
    'sec-collapse'(arg) { const [key, def] = arg.split('|'); const now = key in st.secCollapse ? st.secCollapse[key] : def === '1'; st.secCollapse[key] = !now; render(); },
    /* An ExpandableText without caller state: flip the fold in place; the next render folds it again. */
    'fold-toggle'(a, el) {
      const fold = el.closest('.sb-fold'); const p = fold && fold.querySelector('.sb-fold-tx');
      if (!p) return;
      const open = p.classList.toggle('is-folded') === false;
      if (open) p.removeAttribute('data-fold'); else p.setAttribute('data-fold', '');
      el.outerHTML = S.foldButton(open);
    },
    'reason-toggle'(key) { st.reasonOpen.has(key) ? st.reasonOpen.delete(key) : st.reasonOpen.add(key); render(); },
    'filter-clear'() { st.filter = ''; render(); },
    'history-tab'(t) { st.historyTab = t; st.focus = 0; render(); },
    'toggle-props'() { st.propsOpen = !st.propsOpen; render(); },
    'toggle-side'() { st.sideOpen = !st.sideOpen; render(); },
    'sec-toggle'(id) { st.closed.has(id) ? st.closed.delete(id) : st.closed.add(id); render(); },
    pager(dir) { const live = D.items.filter((x) => x.section !== 'finished'); const i = live.findIndex((x) => x.id === st.detailId); st.detailId = live[(i + +dir + live.length) % live.length].id; st.reasonOpen.clear(); render(); },
    'layer-close'() { st.popover = null; render(); },
    'layer-close-self'(a, el, e) { if (e.target === el) { st.dialog = null; st.cmdk = null; render(); } },
    'dialog-close'() { st.dialog = null; render(); },

    /* run controls */
    'pause-soft'(id) { const it = item(id); st.popover = null; if (!it || !X.pauseEligible(it)) return render(); it.pauseRequested = true; render(); toast({ state: 'paused', title: 'Pause recorded', body: 'The goal pauses at its next checkpoint.' }); },
    'pause-now'(id) { const it = item(id); st.popover = null; if (!it || !X.controllable(it)) return render(); Object.assign(it, { lifecycle: 'paused', live: false, pauseRequested: false, liveness: 'idle', reason: 'operator_pause: Paused from the desktop app.', runOptions: Object.assign({}, it.runOptions, { canLaunch: true, launchLabel: 'Resume' }) }); render(); toast({ state: 'paused', title: 'Stop confirmed', body: 'The goal runner has exited.' }); },
    launch(id) {
      const it = item(id); st.popover = null;
      if (!it || !it.runOptions || !it.runOptions.canLaunch) return render();
      const agent = st.chosenAgent[id] || it.runOptions.agentId;
      if (!agent) { const btn = root.querySelector(`[data-act="agent-menu"][data-arg="${id}"]`); if (btn) return A['agent-menu'](id, btn); }
      Object.assign(it, { lifecycle: 'active', live: true, liveness: 'live', reason: null, incident: null, agent, section: it.kind === 'goal' ? 'goals' : it.kind === 'operation' ? 'operations' : 'phases', runOptions: Object.assign({}, it.runOptions, { canLaunch: false }) });
      render(); toast({ state: 'active', frac: 0.2, title: `${it.runOptions.launchLabel || 'Resume'} requested`, body: `Starts the installed ${agent} harness.` });
    },
    'agent-menu'(id, el) { st.popover = Object.assign({ type: 'agent', arg: id }, anchorPos(el)); render(); },
    'agent-pick'(arg) { const [id, agent] = arg.split('|'); st.chosenAgent[id] = agent; st.popover = null; render(); },
    cancel(id) { const it = item(id); st.popover = null; if (!it || !it.runOptions || !it.runOptions.canCancel) return render(); Object.assign(it, { lifecycle: 'canceled', live: false, pauseRequested: false, runOptions: Object.assign({}, it.runOptions, { canCancel: false, canLaunch: true, launchLabel: 'Run again' }) }); render(); toast({ state: 'canceled', title: 'Cancel recorded', body: `${X.rowKey(it)} is marked canceled. Its branch and commits stay.` }); },
    'op-decision'(v) { const id = st.detailId; st.opDecision[id] = Object.assign({ instructions: '', recorded: false }, st.opDecision[id], { decision: v }); render(); },
    'op-record'(id) { const d = Object.assign({ decision: 'retry_fix', instructions: '' }, st.opDecision[id]); if (d.decision === 'retry_fix' && !d.instructions.trim()) return; st.opDecision[id] = Object.assign(d, { recorded: true }); render(); toast({ icon: 'checkCircle', title: 'Decision recorded', body: 'Resume the goal to apply it.' }); },
    prereq(key) { st.prereqs.has(key) ? st.prereqs.delete(key) : st.prereqs.add(key); render(); },
    recover(id) { const it = item(id); if (!it || !X.recoveryOffered(st, it)) return; st.dialog = { type: 'recover', arg: id }; render(); },
    'recover-confirm'(id) { st.recovered.add(id); st.dialog = null; render(); toast({ icon: 'refresh', title: 'Recovery request finished', body: 'Incident details were refreshed.' }); },
    copy(t) { st.popover = null; render(); copyText(t); },
    toast(t) { st.popover = null; render(); toast({ icon: 'checkCircle', title: t }); },
    'open-file'(f) { toast({ icon: 'external', title: 'Opened in its default app', body: f }); },
    'term-here'(id) { st.popover = null; openTerminalFor(item(id)); render(); },

    /* specs pane */
    'specs-toggle'() { st.specsOpen = !st.specsOpen; st.popover = null; render(); },
    'spec-click'(path) {
      const isFile = /\.(md|yaml)$/.test(path) || /\/\d+$/.test(path);
      st.specSel = path; st.specConfirmCancel = null;
      if (isFile) { render(); return A['open-file'](path.split('/').pop().match(/^\d+$/) ? `subtask ${+path.split('/').pop() + 1} spec` : path); }
      st.specOpen.has(path) ? st.specOpen.delete(path) : st.specOpen.add(path); render();
    },
    'spec-collapse'() { st.specOpen.clear(); render(); },
    'spec-status'(s) { st.specStatus = s; render(); },
    'spec-clear'() { st.specQuery = ''; st.specStatus = 'all'; render(); },
    'spec-start'(k) { st.specRuns[k] = 'running'; render(); toast({ state: 'active', frac: 0.15, title: `Started ${k}`, body: `On ${st.chosenAgent['spec:' + k] || 'claude'}. It appears under Goal runs.` }); },
    'spec-pause'(k) { st.specRuns[k] = 'pausing'; render(); },
    'spec-cancel'(k) { st.specConfirmCancel = k; render(); },
    'spec-keep'() { st.specConfirmCancel = null; render(); },
    'spec-cancel-confirm'(k) { st.specRuns[k] = 'canceled'; st.specConfirmCancel = null; render(); },
    'spec-purge'(k) { st.dialog = { type: 'purge', arg: k }; render(); },
    'purge-confirm'(k) { const sp = D.specs.find((s) => s.key === k); if (sp) { sp.done = 0; sp.status = 'pending'; sp.subState = sp.subState.map(() => 'pending'); } st.specRuns[k] = 'not_started'; st.dialog = null; render(); },
    'project-menu'(a, el) { st.popover = Object.assign({ type: 'project' }, anchorPos(el)); render(); },
    'project-pick'(id) { st.specProject = id; st.specSel = null; st.popover = null; render(); },

    /* terminal pane */
    'term-toggle'() { st.termOpen = !st.termOpen; if (st.termOpen && !st.termTabs.length) openTerminalFor(contextItem()); render(); },
    'term-new'() { openTerminalFor(contextItem()); render(); },
    'term-tab'(id) { st.termActive = +id; render(); },
    'term-close-tab'(id) { const i = st.termTabs.findIndex((t) => t.id === +id); st.termTabs.splice(i, 1); relabelTabs(); if (st.termActive === +id) st.termActive = st.termTabs.length ? st.termTabs[Math.max(0, i - 1)].id : null; if (!st.termTabs.length) st.termOpen = false; render(); },
    'term-resize'(d) { st.termHeight = Math.max(120, Math.min(600, st.termHeight + +d)); render(); },

    /* settings */
    'db-save'() { const v = st.prefs.dbDraft; if (v && !v.trim()) return; st.prefs.dbPath = v; render(); toast({ icon: 'checkCircle', title: 'Saved' }); },
    'claude-menu'(a, el) { st.popover = Object.assign({ type: 'claude' }, anchorPos(el)); render(); },
    'claude-pick'(d) { st.prefs.claudeDir = d; st.popover = null; render(); },
    'pref-retention'(v) { st.prefs.retention = v; render(); },
    'pref-toggle'(k) { st.prefs[k] = !st.prefs[k]; render(); },
    'pref-notify'(k) { st.prefs.notify[k] = !st.prefs.notify[k]; render(); },
    'pref-spectype'(v) { st.prefs.specType = v; render(); },
    'pref-telemetry'(v) { st.prefs.telemetry = v; render(); },
    'cmp-toggle'() { st.compaction.enabled = !st.compaction.enabled; render(); },
    'cfg-agent'(a) { if (!D.AGENTS[a].modelCapable) return; st.agent = a; st.cfgOpen = null; st.draft = null; render(); },
    'cfg-open'(key) {
      if (st.cfgOpen === key) { st.cfgOpen = null; st.draft = null; return render(); }
      const [kind, id] = key.split(':');
      if (kind === 'budget' || kind === 'cmpphase') {
        st.cfgOpen = key;
        if (kind === 'budget') st.draft = { target: { kind, id }, value: String(st.budget.find((r) => r[0] === id)[2]) };
        else { const o = st.compaction.phases[id]; st.draft = { target: { kind, id }, window: String(o.window), pct: String(o.pct) }; }
        render();
        const inp = root.querySelector('.sb-cfg-ed input'); if (inp) { inp.focus(); inp.setSelectionRange(inp.value.length, inp.value.length); }
        return;
      }
      const m = st.matrix[st.agent];
      const dir = kind === 'tier' ? m[id] : (m.phases[id] || m[X.tierOf(st, id)]);
      st.cfgOpen = key;
      st.draft = { target: { kind, id }, model: dir.model, effort: dir.effort, tier: kind === 'phase' ? X.tierOf(st, id) : null };
      render();
      const inp = document.getElementById('ed-model'); if (inp) { inp.focus(); inp.setSelectionRange(inp.value.length, inp.value.length); }
    },
    'ed-tier'(t) { st.draft.tier = t; if (!st.matrix[st.agent].phases[st.draft.target.id]) { const d = st.matrix[st.agent][t]; st.draft.model = d.model; st.draft.effort = d.effort; } render(); },
    'ed-cancel'() { st.cfgOpen = null; st.draft = null; render(); },
    'ed-save'() {
      const d = st.draft; if (!d) return;
      if (d.target.kind === 'budget') {
        const v = d.value.trim();
        if (!/^\d+$/.test(v)) { d.error = 'Enter a whole number that fits this field.'; return render(); }
        if (+v <= 0) { d.error = 'Byte limits must be positive.'; return render(); }
        st.budget.find((r) => r[0] === d.target.id)[2] = +v;
        st.cfgOpen = null; st.draft = null; render();
        return toast({ icon: 'checkCircle', title: 'Saved to config.yaml', body: `review_context_budget.${d.target.id}: ${v}` });
      }
      if (d.target.kind === 'cmpphase') {
        const w = parseInt(d.window.replace(/[^0-9]/g, ''), 10), p = parseInt(d.pct, 10);
        if (!(w > 0) || !(p >= 1 && p <= 100)) { d.error = 'Enter a positive window and a trigger percent from 1 to 100.'; return render(); }
        if (Math.floor(w / 100) * p < 200000) { d.error = 'The trigger must be at least 200,000 tokens.'; return render(); }
        st.compaction.phases[d.target.id] = { window: w, pct: p };
        st.cfgOpen = null; st.draft = null; render();
        return toast({ icon: 'checkCircle', title: 'Saved to config.json', body: `compaction.phases.${d.target.id}: ${w.toLocaleString('en-US')} tokens · ${p}%` });
      }
      if (!d.model.trim()) return;
      const m = st.matrix[st.agent];
      const val = { model: d.model.trim(), effort: (d.effort || '').trim() || null };
      if (d.target.kind === 'tier') m[d.target.id] = val;
      else {
        const id = d.target.id;
        const def = D.PHASE_GROUPS.flatMap((g) => g[1]).find((p) => p[0] === id)[1];
        if (d.tier === def) delete st.phaseTiers[id]; else st.phaseTiers[id] = d.tier;
        const td = m[d.tier];
        if (td.model === val.model && td.effort === val.effort) delete m.phases[id]; else m.phases[id] = val;
      }
      st.cfgOpen = null; st.draft = null; render();
      toast({ icon: 'checkCircle', title: 'Saved to config.json', body: `${d.target.id}: ${val.model}${val.effort ? ' · ' + val.effort : ''}` });
    },
    'ed-remove'() { const id = st.draft.target.id; delete st.compaction.phases[id]; st.cfgOpen = null; st.draft = null; render(); toast({ icon: 'trash', title: `Removed the ${id} override`, body: `${id} uses the compaction setting above.` }); },
    'ed-reset'() { const id = st.draft.target.id; delete st.matrix[st.agent].phases[id]; st.cfgOpen = null; st.draft = null; render(); toast({ icon: 'rerun', title: `${id} uses the ${X.tierOf(st, id)} tier again` }); },

    cmdk() { st.popover = null; st.cmdk = { q: '', hl: 0 }; render(); },
    'cmdk-run'(id) { runCommand(id); },
    shortcuts() { st.popover = null; st.dialog = { type: 'shortcuts' }; render(); },
    'tray-open'(id) { openItem(id); },

    /* subtask index and inspector */
    'sub-select'(arg) {
      const [id, key] = arg.split('|');
      const it = item(id);
      if (!it) return;
      const same = X.selectedSub(st, it) === key;
      if (same && !X.paneShown(st)) st.subOpen[id] = st.subOpen[id] === false;
      else { st.subSel[id] = key; st.subOpen[id] = true; }
      render(); revealSub();
    },
    'sub-move'(d) {
      const it = item(st.detailId);
      const keys = it ? X.subKeys(it) : [];
      if (!keys.length) return;
      const i = keys.indexOf(X.selectedSub(st, it));
      st.subSel[it.id] = keys[Math.max(0, Math.min(keys.length - 1, i + +d))]; st.subOpen[it.id] = true;
      render(); revealSub();
    },
    'sub-open'() {
      const it = item(st.detailId);
      const key = it && X.selectedSub(st, it);
      const s = key && key !== 'finish' && it.subtasks.find((x) => String(x.id) === key);
      if (s) A['open-file'](`${it.id}/${s.spec}`);
    },
    'bd-toggle'(key) { st.bdOpen.has(key) ? st.bdOpen.delete(key) : st.bdOpen.add(key); render(); },
    'feed-all'(id) { st.feedAll.add(id); render(); },
  };

  function revealSub() { const el = root.querySelector('.sb-sub.is-selected'); if (el) el.scrollIntoView({ block: 'nearest' }); }

  /* ---------- events ---------- */
  root.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]');
    if (!el || el.disabled) return;
    if (el.dataset.act === 'term-close-tab') e.stopPropagation();
    const fn = A[el.dataset.act];
    if (fn) fn(el.dataset.arg, el, e);
  });
  root.addEventListener('dblclick', (e) => {
    const el = e.target.closest('[data-dbl="term-rename"]');
    if (!el) return;
    st.termRenaming = +el.dataset.arg; render();
  });
  toastRoot.addEventListener('click', (e) => { const el = e.target.closest('[data-act]'); if (el && A[el.dataset.act]) A[el.dataset.act](el.dataset.arg, el, e); });
  root.addEventListener('contextmenu', (e) => {
    const el = e.target.closest('[data-ctx]');
    if (!el) return;
    e.preventDefault();
    const id = el.dataset.ctx.slice(5);
    const i = +el.dataset.row; if (!isNaN(i)) st.focus = i;
    st.popover = { type: 'item', arg: id, x: e.clientX, y: e.clientY, top: e.clientY }; render();
  });
  root.addEventListener('input', (e) => {
    const t = e.target, k = t.dataset.input;
    if (t.id === 'cmdk-input') { st.cmdk.q = t.value; st.cmdk.hl = 0; renderCmdkList(); return; }
    if (k === 'work-filter') { st.filter = t.value; st.focus = 0; render(); }
    if (k === 'spec-q') { st.specQuery = t.value; render(); }
    if (k === 'ed-model') { st.draft.model = t.value; render(); }
    if (k === 'ed-effort') { st.draft.effort = t.value; }
    if (k === 'ed-field') { const had = st.draft.error; st.draft[t.id.slice(3)] = t.value; st.draft.error = null; if (had) render(); }
    if (k === 'op-instructions') { const id = st.detailId; st.opDecision[id] = Object.assign({ decision: 'retry_fix', recorded: false }, st.opDecision[id], { instructions: t.value }); render(); }
    if (k === 'db-path') { st.prefs.dbDraft = t.value; render(); }
    if (k === 'gradle') { st.prefs.gradleWrapper = t.value; render(); }
  });
  root.addEventListener('change', (e) => {
    const k = e.target.dataset.input;
    if (k === 'cmp-pct') { st.compaction.pct = +e.target.value; render(); }
    if (k === 'cmp-window') { const n = parseInt(e.target.value.replace(/[^0-9]/g, ''), 10); if (n > 0) st.compaction.window = n; render(); }
  });
  function commitRename(input, keep) {
    const tab = st.termTabs.find((t) => t.id === st.termRenaming);
    if (tab && keep) { const v = input.value.trim(); tab.customName = v || null; }
    st.termRenaming = null; render();
  }
  root.addEventListener('keydown', (e) => {
    if (e.target.id !== 'term-rename-in') return;
    if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); commitRename(e.target, true); }
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); commitRename(e.target, false); }
  }, true);
  root.addEventListener('focusout', (e) => { if (e.target.id === 'term-rename-in' && st.termRenaming) commitRename(e.target, true); });

  /* drag the terminal's top edge to resize it */
  root.addEventListener('mousedown', (e) => {
    if (!e.target.matches('[data-grip]')) return;
    e.preventDefault();
    const pane = e.target.parentElement, y0 = e.clientY, h0 = st.termHeight;
    const move = (ev) => { st.termHeight = Math.max(120, Math.min(600, h0 + (y0 - ev.clientY))); pane.style.height = st.termHeight + 'px'; };
    const up = () => { document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up); };
    document.addEventListener('mousemove', move); document.addEventListener('mouseup', up);
  });
  /* In the command menu, hovering a row moves the highlight there. */
  root.addEventListener('mouseover', (e) => {
    if (!st.cmdk) return;
    const el = e.target.closest && e.target.closest('#cmdk-list [data-idx]');
    if (!el || +el.dataset.idx === st.cmdk.hl) return;
    st.cmdk.hl = +el.dataset.idx;
    root.querySelectorAll('#cmdk-list .sb-menu-item').forEach((x) => x.classList.toggle('hl', +x.dataset.idx === st.cmdk.hl));
  });
  window.addEventListener('hashchange', () => { st.mode = modeFromHash(); render(); });
  window.addEventListener('resize', () => { if (st.popover) { st.popover = null; render(); } });

  let gPending = false, gTimer = null;
  document.addEventListener('keydown', (e) => {
    const mod = e.metaKey || e.ctrlKey;
    const key = e.key;
    if (mod && key.toLowerCase() === 'k') { e.preventDefault(); if (st.cmdk) { st.cmdk = null; render(); } else A.cmdk(); return; }
    if (st.cmdk) {
      const n = (st.cmdk.flat || []).length;
      if (key === 'ArrowDown') { e.preventDefault(); st.cmdk.hl = n ? (st.cmdk.hl + 1) % n : 0; renderCmdkList(); }
      else if (key === 'ArrowUp') { e.preventDefault(); st.cmdk.hl = n ? (st.cmdk.hl - 1 + n) % n : 0; renderCmdkList(); }
      else if (key === 'Enter') { e.preventDefault(); const it = st.cmdk.flat && st.cmdk.flat[st.cmdk.hl]; if (it) runCommand(it.id); }
      else if (key === 'Escape') { e.preventDefault(); st.cmdk = null; render(); }
      return;
    }
    if (e.ctrlKey && (e.code === 'Backquote')) { e.preventDefault(); return e.shiftKey ? A['term-new']() : A['term-toggle'](); }
    if (key === 'Escape') {
      if (st.dialog) { st.dialog = null; return render(); }
      if (st.popover) { st.popover = null; return render(); }
      if (st.draft) return A['ed-cancel']();
      if (st.specConfirmCancel) return A['spec-keep']();
      if (st.sideOpen) { st.sideOpen = false; return render(); }
      if (/INPUT/.test(e.target.tagName)) { e.target.blur(); return; }
      if (st.view === 'detail') return go('work');
      if (st.view === 'settings') return go(st.prevView || 'work');
      return;
    }
    /* Enter runs the dialog's default button, which also has focus: Apply and resume, Done, or Keep run history in Purge. */
    if (st.dialog) { if (key === 'Enter') { e.preventDefault(); const b = document.getElementById('dialog-primary'); if (b) b.click(); } return; }
    if (st.popover) return;
    const typing = /INPUT|TEXTAREA|SELECT/.test(e.target.tagName);
    if (typing) { if (key === 'Enter' && mod && st.draft) { e.preventDefault(); A['ed-save'](); } return; }
    if (st.mode !== 'prototype') return;

    if (gPending) {
      gPending = false; clearTimeout(gTimer);
      const k = key.toLowerCase();
      if (k === 'w') return A.nav('work');
      if (k === 'h') return A.nav('history');
      if (k === 's') return A['specs-toggle']();
      if (k === ',') return A.nav('settings');
      return;
    }
    if (key === 'g' && !mod) { gPending = true; gTimer = setTimeout(() => { gPending = false; }, 1200); return; }

    const it = contextItem();
    if (mod && key.toLowerCase() === 'i') { e.preventDefault(); return A['toggle-props'](); }
    if (mod) return;
    if (key === '?') return A.shortcuts();
    if (key === '[') return A['toggle-side']();
    if (key === '/') { const f = document.getElementById('work-filter') || document.getElementById('history-filter'); if (f) { e.preventDefault(); f.focus(); } return; }
    if (key === 'p' && it) return A['pause-soft'](it.id);
    if (key === 'P' && e.shiftKey && it) return A['pause-now'](it.id);
    if (key === 'r' && it) return A.launch(it.id);

    /* While the Subtasks list has focus, ↑/↓ move the selection and Enter opens the subtask's spec file. J/K still page. */
    const inSubs = st.view === 'detail' && document.activeElement && document.activeElement.closest && document.activeElement.closest('[data-zone="subtasks"]');
    if (inSubs && (key === 'ArrowDown' || key === 'ArrowUp')) { e.preventDefault(); return A['sub-move'](key === 'ArrowDown' ? 1 : -1); }
    if (inSubs && key === 'Enter') { e.preventDefault(); return A['sub-open'](); }
    const down = key === 'j' || key === 'ArrowDown', up = key === 'k' || key === 'ArrowUp';
    if (st.view === 'detail' && (down || up)) { e.preventDefault(); return A.pager(down ? 1 : -1); }
    const rows = X.rows;
    if (!rows.length || (st.view !== 'work' && st.view !== 'history')) return;
    if (down || up) { e.preventDefault(); st.focus = Math.max(0, Math.min(rows.length - 1, st.focus + (down ? 1 : -1))); render(); const el = root.querySelector('.sb-row.focus'); if (el) el.scrollIntoView({ block: 'nearest' }); return; }
    if (key === 'Enter' && rows[st.focus]) return openItem(rows[st.focus].slice(5));
  });

  /* ---------- boot ---------- */
  collectUsage();
  render();
})();
