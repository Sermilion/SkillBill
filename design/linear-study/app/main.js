/* Controller: state, rendering, keyboard model, command menu, overlays, live clocks.
 * Every action maps to a real desktop-app or runtime capability; feedback text mirrors the app's messages. */
(function () {
  'use strict';
  const S = window.SB, D = window.DATA, X = window.Screens, L = window.Library;
  const esc = S.esc;
  const root = document.getElementById('root');
  const toastRoot = document.getElementById('toast-root');
  let chatSeq = 1, queueSeq = 1, undoTimer = null;
  /* Run app (SONA-10): sample output and the pending step timers of the one run playing. */
  const R = D.APP_RUN;
  let appTimers = [];

  const st = {
    mode: modeFromHash(),
    view: 'work', prevView: 'work', section: null, filter: '', focus: 0, detailId: 'INF-7', propsOpen: true,
    historyTab: 'all', closed: new Set(), sideOpen: false,
    /* Specs pane: project, selected path (a bundle key, 'done/<key>', or a file under it), expanded rows, filter text, status
     * tab and whether the search field is open. */
    specsOpen: false, specProject: 'l-f2ea', specSel: 'SKILL-442', specOpen: new Set(['SKILL-442']), specQuery: '', specStatus: 'all', specSearch: false,
    /* Plan runs, per item: the Activity filter, Follow (unset follows), folded stage groups ('<id>|<stage>'), a tall
     * transcript, open briefing folds ('<id>:<fold>') and plans whose Implement was requested. */
    planFilter: {}, planFollow: {}, planShut: new Set(), planTxLong: new Set(), planOpen: new Set(), implemented: new Set(),
    termOpen: true, termHeight: 200, termRenaming: null, termSeq: 3, termActive: 1,
    termTabs: [
      { id: 1, directory: '~/IdeaProjects/skill-bill', name: 'skill-bill', branch: 'feat/SKILL-417-keep-ide-status-on-the-live-goal-phase' },
      { id: 2, directory: '~/dev/infra-terraform', name: 'infra-terraform', branch: 'feat/INF-7-remote-state-backend' },
    ],
    chosenAgent: {}, prereqs: new Set(), recovered: new Set(), secCollapse: {}, reasonOpen: new Set(),
    /* Subtask index: selection per goal ('3' or 'finish'), inline expansion per goal (narrow windows), open breakdowns, full activity feeds */
    subSel: {}, subOpen: {}, peekSel: {}, bdOpen: new Set(), feedAll: new Set(), narrow: false,
    settingsPage: 'general', agent: 'claude', cfgOpen: null, draft: null, matrix: JSON.parse(JSON.stringify(D.MATRIX)), phaseTiers: {},
    compaction: { enabled: true, window: 400000, pct: 70, phases: { review: { window: 600000, pct: 60 } } }, budget: D.BUDGET.map((r) => r.slice()),
    prefs: { dbPath: '', dbDraft: '', claudeDir: '~/.claude', retention: '24h', login: true, notify: Object.fromEntries(D.NOTIFICATIONS.map(([id, , on]) => [id, on])), specType: 'local', gradleWrapper: '', telemetry: 'anonymous', defaultAgent: null },
    popover: null, dialog: null, cmdk: null, toasts: [],
    /* New task: open chats, saved chats, repositories and popup state (seedNewTask). focusNext is an element id focused once after the next render. */
    nt: seedNewTask(), focusNext: null,
    /* QA device in the sidebar footer: which list the app found (data.js QA_DEVICES), the picked device ('auto' lets QA
     * choose), the popup's keyboard index and whether a refresh is running. qaOpen: open QA result rows and folds. */
    qaDev: { list: D.QA_DEVICES.live, selected: 'auto', kb: 0, checking: false },
    /* Run app (SONA-10), per goal id: { state (building, running, failed, contended, stopped), device, step, tab, at, holder }. */
    appRun: {}, build: Object.assign({}, D.BUILD.live), qaOpen: new Set(),
    usage: { provider: 'Codex', scenario: 'normal', override: null, last: null },
    /* Validation runs: open report parts (indexes) and open checks (ids) per item, unset until the first toggle so the
     * defaults apply. Verify runs: open steps, criteria rows and folds ("<id>:s:<step>", "<id>:c:<n>"), the criteria filter,
     * the items selected for fixing (key to route, chat or goal) and criterion marks (repo or spec) per item.
     * 0AC-85 opens with the mock's starting selection so the selection bar shows in use: F-001, #2 and the Empty address
     * book test (row 2) to chat, #6 to a goal, #3 and #4 marked Not in this repo. */
    valParts: {}, valChecks: {}, vOpen: new Set(), vFilter: {},
    /* Monitor runs (design/monitor-run): the Cycles cell whose panel is open per item ("c2.fix"; default: the run's live or
     * last step), and the log tails and folded groups the operator flipped from their defaults ("<id>|<cell>:<check>"). */
    monSel: {}, monOpen: new Set(), monFold: new Set(),
    vSel: { 'op-verify-085': { 'f:F-001': 'chat', 'c:2': 'chat', 't:2': 'chat', 'c:6': 'goal' } }, vMarks: { 'op-verify-085': { 'c:3': 'repo', 'c:4': 'repo' } },
  };
  let devTimer = null;
  const bootRoute = routeFromHash();
  if (bootRoute) applyRoute(bootRoute);
  relabelTabs();
  /* Below 1080px the details pane hides and the subtask inspector opens inside its row. */
  const narrowQuery = window.matchMedia('(max-width: 1080px)');
  st.narrow = narrowQuery.matches;
  narrowQuery.addEventListener('change', (e) => { st.narrow = e.matches; render(); });

  function modeFromHash() {
    const h = (location.hash || '').replace('#', '');
    return ['components', 'foundations', 'states'].includes(h) ? h : 'prototype';
  }
  /* #new-task opens the New task view; #new-task/queue, #new-task/picker and #new-task/resume also open that popup.
   * #new-task/changes/<variant> selects a chat with changes and opens its changes popup (none, few, many, resumed);
   * #new-task/state/changes shows the same chat as few with the popup closed.
   * #detail/<id> opens a work item's detail (#detail/ph-sb-qa for QA results, #detail/ph-sb-plan for a plan run).
   * #build/<state>/<type> opens SKILL-417 with its build in one of the build indicator scenarios (state: running, slow,
   * passed, failed, first, idle; type: android, kotlin, ts, go, rust, ios, none).
   * #qa-device opens the QA device popup; #qa-device/<list> opens it over one of the sample device lists (mixed, one,
   * unauth, offline, avdOnly, none, noAdb), the scenarios of QA device design. #specs opens the Specs pane over Work; #specs/<path> selects a bundle or a file
   * in it (#specs/SKILL-438, #specs/done/SKILL-446, #specs/SKILL-442/spec.md) and #specs/project/<id> picks a project. */
  function routeFromHash() {
    const [page, open, ...rest] = (location.hash || '').replace('#', '').split('/');
    if (['activity', 'changes', 'comments'].includes(page)) return { view: page, surfaceScenario: open || null };
    if (page === 'new-task') return { view: 'newtask', open: open === 'state' ? rest[0] === 'closing' ? 'closing' : null : open || null, scenario: open === 'state' ? rest[0] : null, variant: open === 'picker' || open === 'resume' || open === 'changes' ? rest[0] || null : null };
    if (page === 'detail' && open) return { view: 'detail', id: decodeURIComponent(open), open: null, inspector: rest[0], resultTab: rest[1] };
    if (page === 'build') return { view: 'detail', id: D.BUILD.focus, open: null, build: { state: open || D.BUILD.live.state, type: rest[0] || D.BUILD.live.type } };
    /* #run-app/<scenario> opens an Android sample goal with Run app in one state. On NEWS-168 (completed): ready, building,
     * running, failed, contended, stopped, noadb, adbfailed, problem, avd (a stopped AVD picked, so the run boots it first). monitoring opens
     * NEWS-174 (watching CI); blocked opens NEWS-176 and active opens NEWS-171, where the button is off. */
    if (page === 'run-app') {
      const scenario = open || 'ready';
      const id = { monitoring: 'NEWS-174', blocked: 'NEWS-176', active: 'NEWS-171' }[scenario] || 'NEWS-168';
      return { view: 'detail', id, open: null, runApp: { scenario, id } };
    }
    if (page === 'qa-device') return { view: 'work', open: 'devices', devices: open && open in D.QA_DEVICES.lists ? open : null };
    if (page === 'usage') return { view: 'work', open: 'usage', usageScenario: open in D.USAGE.scenarios ? open : 'normal' };
    if (page === 'specs') return open === 'project' ? { view: 'work', specs: true, project: rest[0] } : { view: 'work', specs: true, sel: open ? decodeURIComponent([open, ...rest].join('/')) : null };
    return null;
  }
  function applyRoute(r) {
    st.view = r.view;
    st.surfaceScenario = r.surfaceScenario || null;
    if (r.usageScenario) { st.usage.scenario = r.usageScenario; st.sideOpen = true; }
    if (r.view === 'newtask') {
      st.ntVariant = r.variant;
      if (r.scenario || r.variant) st.nt = seedNewTaskScenario(r.open === 'changes' ? 'changes-' + (r.variant || 'few') : r.scenario || 'fresh');
      if (r.open === 'picker' && r.variant) {
        const c = X.ntChat(st), pk = st.nt.pk;
        if (r.variant === 'firstRun') c.repo = null;
        if (r.variant === 'longBranch') { c.repo = 'payments-svc'; c.branch = D.LONG_BRANCH; }
        if (r.variant === 'manyRepos') st.nt.repos.push(...['billing-service', 'infra-terraform', 'mobile-gateway', 'notes-sync', 'push-relay', 'search-indexer', 'web-console', 'runtime-kotlin'].filter((name) => !st.nt.repos.some((repo) => repo.name === name)).map((name) => ({ name, parent: '~/Work', defaultBranch: 'main', local: [['develop', '1d']], remote: [] })));
        if (r.variant === 'noMatch') pk.filter.branch = 'no-such-branch';
        if (r.variant === 'loading') pk.branchState = 'loading';
        if (r.variant === 'branchError') pk.branchState = 'error';
        if (r.variant === 'badFolder') pk.addError = '~/Downloads/photos is not a git repository.';
      }
      if (r.open === 'resume' && r.variant === 'empty') st.nt.saved = [];
      if (r.open === 'resume' && r.variant === 'many') {
        const seed = st.nt.saved.find((chat) => chat.newTask.repositoryRoot === 'skill-bill');
        st.nt.saved.push(...Array.from({ length: 25 }, (_, i) => Object.assign({}, seed, { sessionId: 'demo-extra-' + i, title: `Saved chat ${i + 21}`, updatedMin: 2000 + i })));
      }
    }
    if (r.id) st.detailId = r.id;
    /* #detail/<id>/<subtask id | finish>/<phase> selects a subtask or Goal finish and opens one phase card in the details
     * pane. The older /finish/assessment and /finish/qa links open the goal_assessment and qa cards. */
    if (r.id && r.inspector) {
      st.subSel[r.id] = r.inspector; st.subOpen[r.id] = true;
      const phase = r.resultTab === 'assessment' ? 'goal_assessment' : r.resultTab;
      if (phase) st.peekSel[`${r.id}|${r.inspector}`] = phase;
      st.propsOpen = true; st.specsOpen = false;
    }
    if (r.build) st.build = r.build;
    if (r.runApp) seedRunApp(r.runApp.scenario, r.runApp.id);
    if (r.devices) { st.qaDev.list = r.devices; st.qaDev.selected = 'auto'; }
    if (r.specs) {
      st.specsOpen = true;
      if (r.project) { st.specProject = r.project; st.specSel = null; }
      if (r.sel) { st.specSel = r.sel; const sp = X.specOf(r.sel); if (sp) { st.specProject = sp.project; if (r.sel !== X.specPath(sp)) st.specOpen.add(X.specPath(sp)); if (sp.folder) st.specOpen.add(sp.folder); } }
    }
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
    /* Every open chat (home, transcript, replying with a queue), then the picker, queue, open chats and agent popups, the
     * close confirmation and the resume palette. */
    run('New task', () => {
      const queued = Math.max(0, st.nt.chats.findIndex((c) => c.queue.length));
      const chats = st.nt.chats.map((c, i) => as({ view: 'newtask', termOpen: false, nt: Object.assign({}, st.nt, { active: i }) }, () => X.app(st))).join('');
      return chats + as({ view: 'newtask', nt: Object.assign({}, st.nt, { active: queued }) }, () =>
        ['target', 'queue', 'changes', 'chats', 'nt-agent'].map((t) => S.Popover({ x: 0, y: 0, flush: t === 'target' || t === 'queue' || t === 'changes', body: popoverBody({ type: t }) })).join('')
        + dialogBody({ type: 'chat-close', arg: st.nt.chats[queued].id })
        + as({ dialog: { type: 'resume', q: '', active: 0, showAll: false } }, () => dialogBody(st.dialog)));
    });
    run('Work', () => as({ view: 'work', termOpen: false }, () => X.app(st)));
    const everyOpen = { has: () => true };
    run('Detail', () => [['INF-7'], ['DSN-31'], ['NEWS-176'], ['SKILL-417'], ['ATL-402'], ['SKILL-416'], ['ph-rk-review'], ['ph-atl-pr'], ['op-verify'], ['ph-atl-qa'], ['ph-sb-qa'], ['ph-pay-qa'], ['ph-sb-qa-gaps'], ['ph-sb-qa-left'], ['ph-sb-qa-docs'], ['ph-sb-qa-legacy'],
      ['ph-sb-validation'], ['ph-rk-validation'], ['ph-sb-val-limit'], ['ph-sb-validation-done'], ['ph-sb-val-413'], ['ph-sb-val-411'], ['ph-za-validation'], ['ph-sb-val-running'], ['ph-sb-val-waiting'], ['ph-sb-val-repairs'], ['ph-za-val-report'], ['op-verify-confirm'], ['op-verify-nocrit'], ['op-verify-fixes'], ['op-verify-085'], ['op-verify-stopped'], ['op-verify-superseded'], ['ph-sb-plan'], ['ph-atl-plan'], ['ph-news-plan'], ['ph-pay-plan'], ['ph-rk-plan'], ['ph-sb-plan-done'], ['ph-sb-monitor'], ['ph-sb-mon-ci'], ['ph-sb-mon-publish'], ['ph-sb-mon-conflict'], ['ph-sb-mon-wait'], ['ph-sb-mon-green'], ['ph-sb-mon-cap'], ['ph-sb-mon-unresolved'], ['ph-sb-mon-pr-edit'], ['ph-sb-mon-merged']].map(([id]) => as({ view: 'detail', detailId: id, termOpen: false, narrow: false, propsOpen: true, specsOpen: false, secCollapse: { 'incident-evidence': false, 'verify-intake': false }, qaOpen: everyOpen, vOpen: everyOpen, valParts: new Proxy({}, { get: () => everyOpen }), valChecks: new Proxy({}, { get: () => everyOpen }), vSel: { 'op-verify-085': { 'f:F-001': 'chat', 'c:6': 'goal' } }, vMarks: { 'op-verify-085': { 'c:3': 'repo' } } }, () => X.app(st))).join('')
      + as({ view: 'detail', detailId: 'SKILL-416', termOpen: false, narrow: false, propsOpen: true, specsOpen: false, subSel: { 'SKILL-416': 'finish' } }, () => X.app(st))
      + as({ view: 'detail', detailId: 'INF-7', termOpen: false, narrow: true }, () => X.app(st)));
    run('History', () => as({ view: 'history', termOpen: false }, () => X.app(st)));
    /* Every project state, a bundle in each run state, a filter that matches nothing and the open search field; then the
     * spec and panel menus. */
    run('Specs pane', () => D.specs.map((sp) => as({ specsOpen: true, specSel: X.specPath(sp), specOpen: new Set([X.specPath(sp), 'done']) }, () => X.specsPane(st))).join('')
      + ['l-9c1b', 'l-51d0', 'l-a7e2'].map((id) => as({ specsOpen: true, specProject: id, specSel: null }, () => X.specsPane(st))).join('')
      + as({ specsOpen: true, specQuery: 'zzz', specSearch: true }, () => X.specsPane(st))
      + ['spec', 'spec-panel'].map((t) => S.Popover({ x: 0, y: 0, body: popoverBody({ type: t, arg: 'SKILL-442' }) })).join(''));
    run('Terminal', () => X.terminal(st));
    [['general', 'General'], ['notifications', 'Notifications'], ['models', 'Models'], ['compaction', 'Compaction'], ['project', 'Project'], ['telemetry', 'Telemetry and agents']].forEach(([id, label]) => {
      const open = { models: ['phase:review', { target: { kind: 'phase', id: 'review' }, model: 'claude-opus-5-5', effort: 'xhigh', tier: 'reasoning' }], compaction: ['cmpphase:review', { target: { kind: 'cmpphase', id: 'review' }, window: '600000', pct: '60' }], project: ['budget:max_parent_packet_bytes', { target: { kind: 'budget', id: 'max_parent_packet_bytes' }, value: '1048576' }] }[id] || [null, null];
      run('Settings · ' + label, () => as({ view: 'settings', settingsPage: id, cfgOpen: open[0], draft: open[1] }, () => X.app(st)));
    });
    run('Command menu', () => S.CommandPalette({ context: S.Label({ text: 'INF-7', dot: false }), list: S.CommandList(commandGroups(), 0) }));
    run('Menus', () => ['agent', 'item', 'project', 'claude'].map((t) => S.Popover({ x: 0, y: 0, body: popoverBody({ type: t, arg: 'INF-7' }) })).join(''));
    run('QA device', () => S.Popover({ x: 0, y: 0, flush: true, cls: 'sb-pop--dev', role: 'dialog', body: popoverBody({ type: 'devices' }) }));
    run('Usage limits', () => as({ view: 'work' }, () => S.UsagePanel(X.usageData(st))));
    run('Dialogs', () => [dialogBody({ type: 'recover', arg: 'DSN-31' }), dialogBody({ type: 'purge', arg: 'SKILL-438' }), dialogBody({ type: 'spec-cancel', arg: 'SKILL-442' }), dialogBody({ type: 'spec-delete', arg: 'SKILL-444' }), dialogBody({ type: 'spec-export-live', arg: 'SKILL-442' }), dialogBody({ type: 'shortcuts' })].join(''));
    run('Toasts', () => S.ToastStack([S.Toast({ state: 'paused', title: 'Pause recorded', body: 'x' })]));
    run('Tooltip', () => S.Tooltip('Pause after subtask', 'P'));
    run('Tray', () => L.tray('goals') + L.tray('phases') + L.tray('operations'));
    run('Notifications', () => L.osNotifications());
  }

  /* ---------- rendering ---------- */
  function docBar() {
    return `<div class="doc-bar">${S.BrandMark(18)}<span class="doc-bar-ti">Sona</span><span class="doc-bar-sub">Linear study</span>
      <span class="l-grow"></span>${S.Segmented({ act: 'mode', value: st.mode, items: [{ id: 'prototype', label: 'Prototype' }, { id: 'states', label: 'States' }, { id: 'components', label: 'Components' }, { id: 'foundations', label: 'Foundations' }] })}</div>`;
  }
  function body() {
    if (st.mode === 'components') return L.components();
    if (st.mode === 'foundations') return L.foundations();
    if (st.mode === 'states') return L.states();
    return X.app(st);
  }
  function overlays() {
    let out = '';
    /* A `steady` popup plays its opening animation once, not again on each re-render (the QA device popup on Refresh and ↑ ↓). */
    if (st.popover) { const pc = st.popover; out += S.Popover({ x: pc.x, y: pc.y, body: popoverBody(pc), flush: pc.flush, role: pc.role, cls: [pc.cls, pc.steady && pc.drawn ? 'is-steady' : ''].filter(Boolean).join(' ') }); pc.drawn = true; }
    if (st.dialog) out += dialogBody(st.dialog);
    if (st.changeView) out += X.changesWindow(st.changeView);
    if (st.cmdk) {
      const it = contextItem();
      out += S.CommandPalette({ context: it ? S.Label({ text: X.rowKey(it), dot: false }) : '', query: st.cmdk.q, list: S.CommandList(filteredGroups(), st.cmdk.hl) });
    }
    return out;
  }
  function render() {
    const scrolls = {};
    root.querySelectorAll('[data-scroll]').forEach((el) => { scrolls[el.dataset.scroll] = el.scrollTop; });
    /* A [data-pin] scroller (a chat transcript) that sat at the bottom stays at the bottom. */
    const pinned = new Set();
    root.querySelectorAll('[data-pin]').forEach((el) => { if (el.scrollHeight - el.scrollTop - el.clientHeight < 24) pinned.add(el.dataset.scroll); });
    const tabsLeft = (root.querySelector('[data-ctabs]') || {}).scrollLeft;
    const ae = document.activeElement;
    const focusId = ae && ae.id && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA') ? ae.id : null;
    const caret = focusId && ae.type !== 'range' ? ae.selectionStart : null;
    const zone = !focusId && ae && ae.closest && ae.closest('[data-zone]');
    const zoneId = zone ? zone.dataset.zone : null;
    hideTip();
    root.innerHTML = docBar() + `<div class="doc-body">${body()}</div>` + overlays();
    root.querySelector('.doc-bar').inert = !!st.changeView;
    root.querySelector('.doc-body').inert = !!st.changeView;
    root.querySelectorAll('[data-scroll]').forEach((el) => { if (scrolls[el.dataset.scroll] != null) el.scrollTop = scrolls[el.dataset.scroll]; });
    root.querySelectorAll('[data-pin]').forEach((el) => { if (pinned.has(el.dataset.scroll) || scrolls[el.dataset.scroll] == null) el.scrollTop = el.scrollHeight; });
    if (st.cmdk) { const i = document.getElementById('cmdk-input'); if (i) { i.focus(); i.setSelectionRange(i.value.length, i.value.length); } }
    else if (st.termRenaming) { const i = document.getElementById('term-rename-in'); if (i) { i.focus(); i.select(); } }
    else if (focusId) { const el = document.getElementById(focusId); if (el) { el.focus(); if (caret != null && el.setSelectionRange) el.setSelectionRange(caret, caret); } }
    else if (zoneId) { const el = root.querySelector(`[data-zone="${zoneId}"]`); if (el) el.focus({ preventScroll: true }); }
    placePopover();
    /* A dialog focuses its default button, or `focus` (the resume palette's filter). A popover focuses `focus` or its first
     * item, unless focus is already inside it. */
    if (st.dialog) { const b = document.getElementById(st.dialog.focus || 'dialog-primary'); if (b && document.activeElement !== b) b.focus(); }
    else if (st.popover) {
      const pop = root.querySelector('[data-pop]');
      if (pop && !pop.contains(document.activeElement)) { const m = (st.popover.focus && document.getElementById(st.popover.focus)) || pop.querySelector('.sb-menu-item:not([disabled])'); if (m) m.focus(); }
    }
    if (st.focusNext) {
      const el = document.getElementById(st.focusNext);
      st.focusNext = null;
      if (el) { el.focus({ preventScroll: true }); if (el.setSelectionRange && el.value != null) el.setSelectionRange(el.value.length, el.value.length); }
    }
    fitChatTabs(tabsLeft);
    root.querySelectorAll('[data-inbox]').forEach(fadeInbox);
    fitReasons();
    fitPlanFeeds();
    fillTokenValues();
    tick();
  }
  /* A plan's Activity follows the newest row unless the reader scrolled up (Follow off); Jump to latest shows meanwhile. */
  function fitPlanFeeds() {
    root.querySelectorAll('[data-planfeed]').forEach((el) => { if (st.planFollow[el.dataset.planfeed] !== false) el.scrollTop = el.scrollHeight; });
  }
  function planFeedScrolled(el) {
    const id = el.dataset.planfeed;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 4;
    if ((st.planFollow[id] !== false) === atBottom) return;
    st.planFollow[id] = atBottom;
    const jump = el.parentElement.querySelector('.sb-pjump'); if (jump) jump.hidden = atBottom;
    const f = root.querySelector(`[data-act="plan-follow"][data-arg="${CSS.escape(id)}"]`); if (f) f.setAttribute('aria-pressed', String(atBottom));
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
    /* `right` lines the popup's right edge up with the anchor's (the changes popup over the composer's right side). */
    if (st.popover.right != null) x = Math.max(8, st.popover.right - r.width);
    if (x + r.width > innerWidth - 8) x = Math.max(8, innerWidth - r.width - 8);
    /* `above` opens over the anchor, as the composer's popups do. With `gap` the popup keeps exactly that space above the
     * anchor: it is measured by layout height, which the opening animation's scale does not shrink (the QA device popup). */
    if (st.popover.above) y = Math.max(8, st.popover.top - (st.popover.gap ? pop.offsetHeight : r.height) - (st.popover.gap || 4));
    else if (y + r.height > innerHeight - 8) y = Math.max(8, (st.popover.top || y) - r.height - 8);
    pop.style.left = x + 'px'; pop.style.top = y + 'px';
  }
  /* Chat tabs: keep the strip's scroll, bring the selected tab into view, fade clipped edges, and show the count button only
   * while tabs are clipped, with a mark when a clipped tab is replying or has a new reply. */
  function fitChatTabs(left) {
    const ts = root.querySelector('[data-ctabs]');
    if (!ts) return;
    if (left != null) ts.scrollLeft = left;
    const on = ts.querySelector('.sb-ctab.on');
    if (on) { const a = on.getBoundingClientRect(), b = ts.getBoundingClientRect(); if (a.left < b.left) ts.scrollLeft -= b.left - a.left; else if (a.right > b.right) ts.scrollLeft += a.right - b.right; }
    const over = ts.scrollWidth > ts.clientWidth + 1;
    ts.classList.toggle('fade-l', over && ts.scrollLeft > 1);
    ts.classList.toggle('fade-r', over && ts.scrollLeft + ts.clientWidth < ts.scrollWidth - 1);
    const more = root.querySelector('[data-ctabs-more]');
    if (!more) return;
    more.hidden = !over;
    const box = ts.getBoundingClientRect();
    const clipped = [...ts.querySelectorAll('[data-tab]')].filter((el) => { const r = el.getBoundingClientRect(); return r.right > box.right - 8 || r.left < box.left + 8; }).map((el) => el.dataset.status);
    const mark = more.querySelector('.sb-cmark');
    if (mark) mark.outerHTML = S.ChatStatusMark(clipped.includes('unread') ? 'unread' : clipped.includes('replying') ? 'replying' : null);
  }
  /* Home lists fade the edge that has more rows behind it. */
  function fadeInbox(el) {
    const over = el.scrollHeight > el.clientHeight + 1;
    el.classList.toggle('fade-t', over && el.scrollTop > 1);
    el.classList.toggle('fade-b', over && el.scrollTop + el.clientHeight < el.scrollHeight - 1);
  }
  function fillTokenValues() {
    root.querySelectorAll('[data-token]').forEach((el) => { el.textContent = getComputedStyle(document.documentElement).getPropertyValue(el.dataset.token).trim(); });
  }
  function renderToasts() { toastRoot.innerHTML = st.toasts.length ? S.ToastStack(st.toasts.map((t) => S.Toast(Object.assign({ close: { act: 'toast-close', arg: t.id } }, t)))) : ''; }
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
    /* Build bars grow with their clocks: elapsed over the usual time (BuildStrip). */
    document.querySelectorAll('[data-bar-of]').forEach((el) => { el.style.width = Math.min(100, ((+el.dataset.barMs + now - +el.dataset.barT0) / +el.dataset.barOf) * 100) + '%'; });
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
  /* After a toggle re-renders, focus returns to the control that has the same action and argument. */
  function refocus(a, arg) { const el = root.querySelector(`[data-act="${a}"][data-arg="${CSS.escape(arg)}"]`); if (el) el.focus({ preventScroll: true }); }
  /* Scroll an element into view after a jump and move focus to its toggle. */
  function revealEl(id, a, arg) { const el = document.getElementById(id); if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' }); if (a) refocus(a, arg); }
  function copyText(text) {
    try { const p = navigator.clipboard && navigator.clipboard.writeText(text); if (p && p.catch) p.catch(() => {}); } catch (e) { /* clipboard refused */ }
    toast({ icon: 'copy', title: 'Copied', body: text.length > 90 ? text.slice(0, 90) + '…' : text });
  }
  function go(view, extra) {
    if (view === 'settings' && st.view !== 'settings') st.prevView = st.view;
    st.view = view; st.focus = 0; st.sideOpen = false; st.popover = null;
    Object.assign(st, extra || {});
    if (view === 'newtask') { const c = X.ntChat(st); c.unread = false; st.focusNext = 'composer-' + c.id; }
    if (st.mode !== 'prototype') { st.mode = 'prototype'; history.replaceState(null, '', '#prototype'); }
    render();
  }
  function openChanges(work, scope, file) {
    st.changeReturnFocus = document.activeElement?.id || null;
    st.changeView = { work, scope, file };
    st.popover = null; st.dialog = null;
    st.focusNext = 'changes-close';
    render();
  }
  function closeChanges() {
    st.changeView = null;
    st.focusNext = st.changeReturnFocus;
    render();
  }
  root.addEventListener('design-open-changes', (event) => openChanges(event.detail.work, event.detail.scope));
  root.addEventListener('design-escape', () => { if (st.changeView) closeChanges(); });
  function changesKeys(event) {
    if (!st.changeView) return false;
    if (event.key === 'Escape') { event.preventDefault(); closeChanges(); return true; }
    if (event.key !== 'Tab') {
      if (['j', 'k', '[', ']', 'a', 's', '/'].includes(event.key)) {
        event.preventDefault();
        root.querySelector('[embedded="window"]').shadowRoot.dispatchEvent(new CustomEvent('design-change-key', { detail: { key: event.key } }));
      }
      return true;
    }
    const buttons = [];
    function collect(node) {
      node.querySelectorAll('button:not([disabled]),input:not([disabled]),textarea:not([disabled]),[tabindex="0"],sb-design-surface').forEach((element) => {
        if (element.shadowRoot) collect(element.shadowRoot);
        else if (element.getClientRects().length) buttons.push(element);
      });
    }
    collect(root.querySelector('.sb-changes-dialog'));
    let active = document.activeElement;
    while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
    if (event.shiftKey && active === buttons[0]) { event.preventDefault(); buttons.at(-1)?.focus(); }
    else if (!event.shiftKey && active === buttons.at(-1)) { event.preventDefault(); buttons[0]?.focus(); }
    return true;
  }

  function openItem(id) { st.detailId = id; go('detail'); }

  /* ---------- Run app (SONA-10) ---------- */
  /* One terminal tab per goal, reused on every run: "Run app · <key>", directory the goal checkout. */
  function appRunTab(it) {
    let tab = st.termTabs.find((t) => t.runApp === it.id);
    if (!tab) {
      tab = { id: st.termSeq++, runApp: it.id, directory: X.repoOf(it.repo).path, name: `Run app · ${it.id}`, branch: it.branch, log: [] };
      st.termTabs.push(tab); relabelTabs();
    }
    tab.log = []; tab.live = true;
    return tab;
  }
  const now = () => new Date().toTimeString().slice(0, 5);
  /* The ordered steps of one run: [delay ms, status step, lines]. */
  function appRunSteps(it, d, fail) {
    const steps = [[120, 'Preparing the checkout', R.head(it.id, d.wire)]];
    if (d.boots) steps.push([1100, `Booting ${d.name}`, R.boot(d.name)]);
    steps.push([150, `Leasing ${d.name}`, R.lease(d.name)]);
    if (fail) return steps.concat([[900, 'gradlew :app:installDebug', R.fail.slice(0, 2)], [900, 'gradlew :app:installDebug', R.fail.slice(2)], [200, null, R.release(d.name).concat([['er', 'Build failed. Nothing was installed.']])]]);
    return steps.concat([
      [700, 'gradlew :app:installDebug', R.gradle.slice(0, 3)],
      [1200, 'gradlew :app:installDebug', R.gradle.slice(3)],
      [600, 'Opening the app', R.launch(d.serial)],
      [300, null, R.release(d.name).concat([['ok', `Running on ${d.name}.`]])],
    ]);
  }
  function startAppRun(id, fail) {
    const it = item(id); if (!it || !X.runAppEligible(it)) return;
    const target = X.runAppTarget(st); if (!target.device) return;
    appTimers.forEach(clearTimeout); appTimers = [];
    const d = target.device, tab = appRunTab(it);
    st.termActive = tab.id; st.termOpen = true;
    st.appRun[id] = { state: 'building', device: d, step: 'Preparing the checkout', tab: tab.id };
    render();
    let at = 0;
    appRunSteps(it, d, fail).forEach(([delay, step, lines], i, all) => {
      at += delay;
      appTimers.push(setTimeout(() => {
        const run = st.appRun[id]; if (!run || run.state !== 'building') return;
        tab.log.push(...lines); if (step) run.step = step;
        if (i === all.length - 1) {
          tab.live = false;
          st.appRun[id] = Object.assign({}, run, { state: fail ? 'failed' : 'running', at: now() });
          toast(fail ? { state: 'failed', title: 'Build failed', body: `${it.id} did not build. Open the Run app tab for the log.` } : { state: 'done', title: `Running on ${d.name}`, body: `${it.id} · debug build opened on the device.` });
        }
        render();
      }, at));
    });
  }
  /* Static scenarios for the #run-app route and the States links. */
  function seedRunApp(scenario, id) {
    const it = X.lookup(id); if (!it) return;
    appTimers.forEach(clearTimeout); appTimers = [];
    delete st.appRun[it.id];
    st.qaDev.list = scenario === 'noadb' ? 'noAdb' : scenario === 'adbfailed' ? 'adbFailed' : 'mixed';
    st.qaDev.selected = scenario === 'avd' ? 'avd1' : 'p8';
    if (['ready', 'avd', 'noadb', 'adbfailed', 'monitoring', 'blocked', 'active'].includes(scenario)) return;
    const d = X.runAppTarget(st).device, tab = appRunTab(it);
    if (scenario === 'contended') { const leased = { id: 'emu1', name: 'Pixel_7_API_34', mono: true, wire: 'serial:emulator-5554' }; tab.log = R.head(it.id, leased.wire).concat(R.contended(leased.name, 'SKILL-514')); tab.live = false; st.appRun[it.id] = { state: 'contended', device: leased, holder: 'SKILL-514', tab: tab.id }; }
    else if (scenario === 'failed') { tab.log = R.head(it.id, d.wire).concat(R.lease(d.name), R.fail, R.release(d.name), [['er', 'Build failed. Nothing was installed.']]); tab.live = false; st.appRun[it.id] = { state: 'failed', device: d, tab: tab.id, at: '14:32' }; }
    else if (scenario === 'running') { tab.log = R.head(it.id, d.wire).concat(R.lease(d.name), R.gradle, R.launch(d.serial), R.release(d.name), [['ok', `Running on ${d.name}.`]]); tab.live = false; st.appRun[it.id] = { state: 'running', device: d, tab: tab.id, at: '14:32' }; }
    else if (scenario === 'stopped') { tab.log = R.head(it.id, d.wire).concat(R.lease(d.name), R.gradle.slice(0, 2), [['wn', 'Stopped by you.'], ...R.release(d.name)]); tab.live = false; st.appRun[it.id] = { state: 'stopped', device: d, tab: tab.id }; }
    else if (scenario === 'building') { tab.log = R.head(it.id, d.wire).concat(R.lease(d.name), R.gradle.slice(0, 2)); st.appRun[it.id] = { state: 'building', device: d, step: 'gradlew :app:installDebug', tab: tab.id }; }
    else if (scenario === 'problem') { tab.log = R.head(it.id, d.wire).concat([['er', 'Several app modules were found.']]); tab.live = false; st.appRun[it.id] = { state: 'problem', device: d, message: 'Several app modules: :app, :demo. Set module in .skill-bill/app-run.yaml.', tab: tab.id }; }
    st.termActive = tab.id; st.termOpen = true;
  }
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
      const spec = String(p.arg).startsWith('spec:') ? D.specs.find((x) => 'spec:' + x.key === p.arg) : null;
      const it = spec ? null : X.lookup(p.arg);
      const current = spec ? X.specAgent(st, spec) : st.chosenAgent[p.arg] || (it && it.runOptions && it.runOptions.agentId) || null;
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
    if (p.type === 'spec') { const sp = D.specs.find((x) => x.key === p.arg) || X.specOf(p.arg); return sp ? S.Menu({ items: X.specMenuItems(st, sp) }) : ''; }
    if (p.type === 'spec-panel') return S.Menu({ items: X.specPanelItems(st) });
    if (p.type === 'project') return S.Menu({ items: [{ head: 'Choose project' }, ...D.projects.map((pr) => ({ lead: S.RepoIcon(X.repoOf(pr.name)), label: pr.name, act: 'project-pick', arg: pr.id, checked: st.specProject === pr.id }))] });
    if (p.type === 'target') return X.targetPicker(st);
    if (p.type === 'queue') return X.queuePanel(st);
    if (p.type === 'changes') return X.changesPanel(st);
    if (p.type === 'chats') return X.chatMenu(st);
    if (p.type === 'chat-tab') return X.chatTabMenu(st, p.arg);
    if (p.type === 'devices') return X.devicePanel(st);
    if (p.type === 'usage') {
      const usage = X.usageData(st);
      return S.UsagePanel(usage);
    }
    if (p.type === 'nt-agent') return S.Menu({ items: [{ head: 'Agent' }, ...D.AGENT_CHOICES.map((a) => ({ lead: S.Avatar({ name: a, initials: D.AGENTS[a].initials, color: D.AGENTS[a].color, size: 'sm' }), label: a, end: D.MATRIX[a] ? esc(D.MATRIX[a].reasoning.model) : '', act: 'nt-agent-pick', arg: a, checked: X.ntChat(st).agent === a }))] });
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
    if (d.type === 'purge') return S.Dialog({ title: `Purge ${d.arg} run history?`, icon: S.Icon('rerun'), body: `<span>Purge deletes everything Sona recorded while running this spec, so it can start again from scratch.</span>${S.Ledger({ leftTitle: 'Deleted', left: ['Goal and subtask run history', 'Run evidence and task tracking', 'Checkpoint refs', 'Subtask progress, back to not started'], rightTitle: 'Kept', right: ['Every spec file in the folder', 'Your branch and its commits', 'Open pull requests'] })}<span>This can’t be undone. Afterwards the spec shows as not started and Start runs it from the first subtask.</span>`, actions: [S.Button({ label: 'Keep run history', variant: 'ghost', act: 'dialog-close', id: 'dialog-primary' }), S.Button({ label: `Purge ${d.arg}`, variant: 'danger-solid', act: 'purge-confirm', arg: d.arg })] });
    /* Specs: cancel, delete and the export guard share the Purge dialog's shape; the safe action has focus and Enter. */
    if (d.type === 'spec-cancel') return S.Dialog({ title: `Cancel ${d.arg}?`, icon: S.Icon('x'), body: '<span>The run stops and the goal is marked canceled. Its branch and commits stay.</span>', actions: [S.Button({ label: 'Keep running', variant: 'ghost', act: 'dialog-close', id: 'dialog-primary' }), S.Button({ label: 'Cancel goal', variant: 'danger-solid', act: 'spec-cancel-confirm', arg: d.arg })] });
    if (d.type === 'spec-delete') {
      const sp = D.specs.find((x) => x.key === d.arg);
      const pr = D.projects.find((x) => x.id === sp.project);
      return S.Dialog({ title: `Delete ${d.arg} spec files?`, icon: S.Icon('trash'), body: `<span>${esc(sp.title)}</span><span>This removes the spec folder and every file in it from disk.</span><span class="mono t-2">${esc(pr.store)}/${esc(sp.key.toLowerCase())}</span><span>Run history stays in the database. Use Purge to remove it.</span><span>This can’t be undone.</span>`, actions: [S.Button({ label: 'Keep spec files', variant: 'ghost', act: 'dialog-close', id: 'dialog-primary' }), S.Button({ label: 'Delete spec files', variant: 'danger-solid', act: 'spec-delete-confirm', arg: d.arg })] });
    }
    if (d.type === 'spec-export-live') return S.Dialog({ title: 'Pause before exporting', icon: S.Icon('pause'), body: '<span>This goal must be paused before it can be exported.</span>', actions: [S.Button({ label: 'Dismiss', variant: 'ghost', act: 'dialog-close', id: 'dialog-primary' }), S.Button({ label: 'Pause', act: 'spec-pause', arg: d.arg })] });
    if (d.type === 'shortcuts') return S.Dialog({ title: 'Keyboard shortcuts', icon: S.Icon('keyboard'), body: S.ShortcutGrid(SHORTCUTS), actions: [S.Button({ label: 'Done', variant: 'primary', act: 'dialog-close', id: 'dialog-primary' })] });
    /* Closing a chat that is replying asks first (NewTaskCloseDialog); Keep open has focus. */
    if (d.type === 'chat-close') {
      const c = chatById(d.arg);
      return S.Dialog({ title: `Close "${X.chatLabel(c)}"?`, icon: S.Icon('message'), body: `<span>${esc(c.agent)} is still replying. Closing stops the reply. The chat stays in Recent chats with everything up to this point.</span>`, actions: [S.Button({ label: 'Keep open', variant: 'ghost', act: 'dialog-close', id: 'dialog-primary' }), S.Button({ label: 'Stop and close', variant: 'danger-solid', act: 'chat-close-confirm', arg: c.id })] });
    }
    if (d.type === 'resume') return X.resumePalette(st);
    return '';
  }
  /* The app binds ⌘ or Ctrl with T, W, Tab and 1 to 9 for chats. Browsers keep those, so the prototype also takes Alt. */
  const SHORTCUTS = [['Command menu', '⌘ K'], ['Go to New task', 'G N'], ['New chat', '⌘ T'], ['Close chat', '⌘ W'], ['Next or previous chat', '⌘ Tab'], ['Resume a saved chat', '/resume'], ['Go to Work', 'G W'], ['Go to History', 'G H'], ['Toggle specs pane', 'G S'], ['Go to Settings', 'G ,'], ['Toggle terminal', '⌃ `'], ['New terminal tab', '⌃ ⇧ `'], ['Filter', '/'], ['Move down / up', 'J K'], ['Open', '↵'], ['Move in the Subtasks list', '↑ ↓'], ['Open the subtask spec file', '↵'], ['Pause after subtask', 'P'], ['Pause now', '⇧ P'], ['Resume', 'R'], ['Toggle details pane', '⌘ I'], ['Toggle sidebar', '['], ['Back / close', 'esc']];

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
    /* The selected spec's actions, the same list as its menu (desktop SpecsPaneModel.commandSection). */
    const sp = st.mode === 'prototype' && st.specsOpen ? X.specOf(st.specSel) : null;
    if (sp) groups.push({ head: sp.key, items: X.specMenuItems(st, sp).filter((x) => x !== '-' && !x.disabled).map((x) => ({ id: `${x.act}:${x.arg}`, icon: x.icon, label: x.label, kbd: x.kbd })) });
    if (st.mode === 'prototype' && st.view === 'newtask') {
      const c = X.ntChat(st);
      groups.push({ head: 'New task', items: [
        { id: 'chat-new', icon: 'plus', label: 'New chat', kbd: '⌘ T' },
        { id: 'chat-close:' + c.id, icon: 'x', label: `Close ${X.chatLabel(c)}`, kbd: '⌘ W' },
        c.repo && { id: 'resume-palette', icon: 'history', label: 'Resume a saved chat', kbd: '/resume' },
      ].filter(Boolean) });
    }
    groups.push({ head: 'Navigation', items: [
      { id: 'nav:newtask', icon: 'pencil', label: 'Go to New task', kbd: 'G N' },
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
      { id: 'qa-device', icon: 'phone', label: 'Choose QA device' },
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
    if (kind === 'qa-device') { if (st.mode !== 'prototype' || st.view === 'settings') go('work'); else render(); return A['dev-toggle'](); }
    if (A[kind]) return A[kind](arg);
    render();
  }

  /* ---------- New task: chats, simulated replies, queue, pickers ---------- */
  /* A chat continues a saved chat (its lines, agent, repository and base branch) or starts empty. Screenshots belong to the first message. */
  function chatFromSaved(x, o) {
    return Object.assign({
      id: chatSeq++, session: x ? x.sessionId : null, title: x ? x.title : '', agent: x ? x.agent : 'claude',
      repo: x ? x.newTask.repositoryRoot : 'skill-bill', branch: x ? x.newTask.baseBranch : null,
      lines: x ? x.lines.map((l, i) => Object.assign({}, l, i === 0 && x.images.length ? { images: x.images } : {})) : [],
      stream: null, unread: false, draft: '', queue: [], changes: null,
    }, o || {});
  }
  function seedNewTask() {
    const saved = D.CHAT_HISTORY.map((x) => Object.assign({}, x));
    const chats = D.NEW_TASK_CHATS.map((o) => {
      const x = o.session && saved.find((s) => s.sessionId === o.session);
      const c = chatFromSaved(x, x ? { unread: !!o.unread } : { repo: o.repo, agent: o.agent });
      if (o.extra) c.lines.push(...o.extra.map((l) => Object.assign({}, l)));
      if (o.queue) c.queue = o.queue.map((m) => Object.assign({ id: queueSeq++ }, m));
      if (o.reply) c.stream = { full: o.reply, shown: o.shown || 0 };
      if (o.changes) c.changes = D.CHAT_CHANGES[o.changes];
      return c;
    });
    return {
      chats, active: D.NEW_TASK_SELECTED, saved, closed: {}, discarded: {}, inboxSel: null, menuQ: '',
      repos: Object.entries(D.REPO_BRANCHES).map(([name, b]) => Object.assign(b, { name })),
      q: { kb: 0, undo: null, clearArmed: false },
      pk: { filter: { repo: '', branch: '' }, active: { repo: 0, branch: 0 }, branchState: 'ok', addError: null },
    };
  }
  function seedNewTaskScenario(scenario) {
    const nt = seedNewTask();
    const samples = nt.chats.filter((chat) => chat.lines.length);
    const replying = nt.chats.find((chat) => chat.stream);
    const empty = () => chatFromSaved(null, { repo: 'skill-bill', agent: 'claude' });
    const stopped = (chat) => Object.assign(chat, { stream: null, queue: [] });
    if (scenario === 'branch-read' || scenario === 'branch-edit') {
      const c = empty();
      c.currentBranch = 'feature/current';
      c.defaultWorktree = '/repo/main checkout';
      c.branchIntent = scenario === 'branch-edit';
      c.lines = [{ role: 'operator', text: c.branchIntent ? 'Fix the sharing flow' : 'Explain the sharing flow' },
        { role: 'agent', text: c.branchIntent ? 'Choose a branch before editing.' : 'I will inspect the sharing flow without changing files.' }];
      nt.chats = [c]; nt.active = 0;
    }
    if (scenario === 'fresh') { nt.chats = [empty()]; nt.active = 0; }
    if (scenario === 'two') { nt.chats = samples.slice(0, 2).map(stopped); nt.active = 0; }
    if (scenario === 'busy') { nt.chats = samples; nt.active = 0; }
    if (scenario === 'mixed') { nt.chats = ['skill-bill', 'newsreader', 'zero-account'].map((repo) => chatFromSaved(nt.saved.find((chat) => chat.newTask.repositoryRoot === repo))); nt.active = 1; }
    if (scenario === 'many' || scenario === 'lots') {
      const n = scenario === 'lots' ? 20 : 9;
      nt.chats = Array.from({ length: n }, (_, i) => chatFromSaved(nt.saved[i % nt.saved.length]));
      nt.active = scenario === 'lots' ? 3 : 4;
      nt.chats[n - 2].unread = true;
      nt.chats[n - 3].stream = { full: D.CHAT_REPLIES[0], shown: 40 };
    }
    /* Chat changes: the token cost chat with no changes yet or a few, the specs chat after a long turn, and the offline banner
     * chat resumed two days after it started. */
    if (scenario === 'changes' || scenario.startsWith('changes-')) {
      const v = scenario.slice(8) || 'few';
      nt.chats = samples.map(stopped);
      const pick = (session) => nt.chats.findIndex((chat) => chat.session === session);
      if (v === 'none' || v === 'few') { nt.active = pick('ses-token-cost'); nt.chats[nt.active].changes = v === 'few' ? D.CHAT_CHANGES.few : null; }
      if (v === 'many') { nt.active = pick('ses-specs-slow'); nt.chats[nt.active].lines.push({ role: 'agent', text: 'Done. The panel lists specs from the directory read first and fills in titles as each spec.md parses. Two commits so far; the tests for the batch reader are still uncommitted.' }); }
      if (v === 'resumed') {
        nt.active = pick('ses-offline-banner');
        const chat = nt.chats[nt.active];
        chat.unread = false; chat.changes = D.CHAT_CHANGES.resumed;
        chat.lines.push({ role: 'notice', text: 'Resumed. Started Nov 6.' }, { role: 'operator', text: 'add a test that the banner sits under the app bar' }, { role: 'agent', text: 'Added OfflineBannerPlacementTest. It checks the banner bounds start at the app bar bottom on phone and tablet widths.' });
      }
    }
    if (scenario === 'closing') { nt.chats = samples; nt.active = samples.indexOf(replying); }
    if (scenario === 'few') { nt.chats = [empty()]; nt.active = 0; nt.saved = nt.saved.filter((chat) => chat.newTask.repositoryRoot === 'skill-bill').slice(0, 3); nt.few = true; }
    if (['pending', 'firstRun', 'noAgent', 'noRepo', 'detectionFailed'].includes(scenario)) {
      nt.chats = [empty()]; nt.active = 0; nt.saved = []; nt.homeState = scenario === 'pending' ? 'pending' : 'firstRun';
      nt.readiness = { firstRun: 'Ready: claude, codex found, skill-bill selected', noAgent: 'Not ready: no agent found', noRepo: 'Not ready: choose a repository', detectionFailed: 'Readiness unknown: agent detection failed' }[scenario] || null;
      if (scenario === 'noRepo') nt.chats[0].repo = null;
    }
    return nt;
  }
  const ntCur = () => X.ntChat(st);
  const chatById = (id) => st.nt.chats.find((c) => c.id === +id) || st.nt.closed[id];
  const pickerInput = () => ({ repos: st.nt.repos, chat: ntCur(), pk: st.nt.pk });
  const resumeInput = () => ({ repo: ntCur().repo, saved: st.nt.saved, q: st.dialog.q, active: st.dialog.active, showAll: st.dialog.showAll });
  const focusComposer = () => { st.focusNext = 'composer-' + ntCur().id; };
  const refocusChanges = () => { st.focusNext = 'nt-changes'; };
  function selectChat(id) {
    const i = st.nt.chats.findIndex((c) => c.id === +id);
    if (i < 0) return;
    st.nt.active = i; st.nt.chats[i].unread = false; st.nt.inboxSel = null;
    Object.assign(st.nt.q, { undo: null, clearArmed: false });
    st.popover = null; focusComposer();
    render();
  }
  const blankChat = () => { const c = ntCur(); return chatFromSaved(null, { repo: c ? c.repo : 'skill-bill', agent: st.prefs.defaultAgent || (c ? c.agent : 'claude') }); };
  function removeChat(c) {
    const i = st.nt.chats.indexOf(c);
    st.nt.chats.splice(i, 1);
    if (!st.nt.chats.length) st.nt.chats.push(chatFromSaved(null, { repo: c.repo, agent: c.agent }));
    if (i < st.nt.active) st.nt.active--;
    st.nt.active = Math.min(st.nt.active, st.nt.chats.length - 1);
  }
  function closeChat(c) {
    stopReply(c); c.queue = [];
    removeChat(c);
    st.dialog = null; st.popover = null; focusComposer();
    render();
    if (c.lines.length) { st.nt.closed[c.id] = c; toast({ icon: 'message', title: `Closed "${X.chatLabel(c)}". It stays in Recent chats.`, actions: [S.Button({ label: 'Reopen', variant: 'ghost', size: 'sm', act: 'chat-reopen', arg: c.id })] }); }
  }
  function closeQueuePop() { if (st.popover && st.popover.type === 'queue') st.popover = null; Object.assign(st.nt.q, { undo: null, clearArmed: false }); }

  /* Simulated replies stream into [data-stream] without a re-render; the end of a reply re-renders, sends the first queued
   * message and starts the next reply. A reply that ends while its tab is not shown marks the tab with a new-reply dot. */
  const streamTimers = new Map();
  let pressing = false, streamDirty = false;
  document.addEventListener('pointerdown', () => { pressing = true; }, true);
  document.addEventListener('pointerup', () => { pressing = false; if (streamDirty) { streamDirty = false; setTimeout(render, 0); } }, true);
  function startReply(c, text) {
    c.stream = { full: text || D.CHAT_REPLIES[(c.id + c.lines.length) % D.CHAT_REPLIES.length], shown: 0 };
    runStream(c);
  }
  function runStream(c) {
    clearInterval(streamTimers.get(c.id));
    const t = setInterval(() => {
      const s = c.stream;
      if (!s) { clearInterval(t); streamTimers.delete(c.id); return; }
      s.shown = Math.min(s.full.length, s.shown + 2 + Math.floor(Math.random() * 3));
      if (s.shown < s.full.length) { paintStream(c); return; }
      clearInterval(t); streamTimers.delete(c.id);
      c.lines.push({ role: 'agent', text: s.full });
      c.stream = null;
      if (!(st.mode === 'prototype' && st.view === 'newtask' && ntCur() === c)) c.unread = true;
      if (c.queue.length) {
        c.lines.push({ role: 'operator', text: c.queue.shift().text });
        if (ntCur() === c) { st.nt.q.kb = Math.max(0, st.nt.q.kb - 1); if (!c.queue.length) closeQueuePop(); }
        startReply(c);
      }
      if (pressing) streamDirty = true; else render();
    }, 110);
    streamTimers.set(c.id, t);
  }
  function paintStream(c) {
    const el = root.querySelector(`[data-stream="${c.id}"]`);
    if (!el) return;
    const tr = el.closest('[data-pin]');
    const atBottom = tr && tr.scrollHeight - tr.scrollTop - tr.clientHeight < 48;
    el.textContent = c.stream.full.slice(0, c.stream.shown);
    if (atBottom) tr.scrollTop = tr.scrollHeight;
  }
  function stopReply(c) {
    if (!c.stream) return;
    clearInterval(streamTimers.get(c.id)); streamTimers.delete(c.id);
    c.lines.push({ role: 'agent', text: c.stream.full.slice(0, c.stream.shown) });
    c.lines.push({ role: 'notice', text: 'Stopped.' });
    c.stream = null;
  }
  /* Send now ends the reply and sends the first queued message. */
  function sendNow(c) {
    stopReply(c);
    c.lines.push({ role: 'operator', text: c.queue.shift().text });
    if (!c.queue.length) closeQueuePop();
    startReply(c); focusComposer(); render();
  }
  /* Picking a repository keeps the picker open and moves to the branch filter; picking a branch closes it. */
  function choosePick(list, i) {
    const c = ntCur(), pk = st.nt.pk;
    const it = X.pickerItems(pickerInput())[list][i];
    if (!it) return;
    if (list === 'repo') {
      if (it.kind === 'add') return addRepository();
      if (it.id !== c.repo) c.branch = null;
      c.repo = it.id; pk.addError = null; pk.filter.branch = ''; pk.active.repo = i; pk.active.branch = 0; pk.branchState = 'ok';
      if (st.popover) st.popover.focus = 'pk-filter-branch';
      st.focusNext = 'pk-filter-branch';
      return render();
    }
    c.branch = it.kind === 'default' ? null : it.id;
    closeTarget('composer-' + c.id);
  }
  /* The app opens a folder chooser here; the prototype adds a sample repository and picks it. */
  function addRepository() {
    const name = `new-repo-${st.nt.repos.length + 1}`;
    const repo = { name, parent: '~/IdeaProjects', defaultBranch: 'main', local: [['develop', '1d']], remote: [] };
    D.REPO_BRANCHES[name] = repo; st.nt.repos.push(repo);
    choosePick('repo', X.pickerItems(pickerInput()).repo.findIndex((x) => x.id === name));
  }
  function closeTarget(focus) { st.popover = null; st.focusNext = focus || 'nt-target'; render(); }
  const reveal = (sel) => root.querySelectorAll(sel).forEach((el) => el.scrollIntoView({ block: 'nearest' }));
  function cycleChat(d) { const n = st.nt.chats.length; selectChat(st.nt.chats[(st.nt.active + d + n) % n].id); }
  /* Home lists: the selected row lives in st.nt.inboxSel and is painted in place while arrows move it. */
  function paintInbox(list, i) {
    const rows = [...list.querySelectorAll('.sb-inbox-row')];
    if (!rows.length) return;
    i = Math.max(0, Math.min(i, rows.length - 1));
    st.nt.inboxSel = { list: list.id, i };
    rows.forEach((el, n) => { el.classList.toggle('sel', n === i); el.setAttribute('aria-selected', n === i); });
    rows[i].scrollIntoView({ block: 'nearest' });
  }
  /* Keys for New task, checked before the shell's: the resume palette, the picker and queue popups, chat shortcuts, Enter in
   * the composer, and the home lists. Returns true when it handled the key. */
  function newTaskKeys(e) {
    if (st.mode !== 'prototype' || st.view !== 'newtask' || st.cmdk) return false;
    const key = e.key, p = st.popover, d = st.dialog, mod = e.metaKey || e.ctrlKey;
    const down = key === 'ArrowDown', up = key === 'ArrowUp';
    if (d && d.type === 'resume') {
      if (down || up) { e.preventDefault(); A['resume-move'](down ? 1 : -1); return true; }
      if (key === 'Enter') { e.preventDefault(); A['resume-open'](); return true; }
      if (key === 'Escape') { e.preventDefault(); A['resume-close'](); return true; }
      return false;
    }
    if (p && p.type === 'target') {
      if (key === 'Escape') { e.preventDefault(); closeTarget(); return true; }
      const list = e.target.dataset && e.target.dataset.pick;
      if (!list) return false;
      if (down || up) {
        e.preventDefault();
        const n = X.pickerItems(pickerInput())[list].length;
        if (n) st.nt.pk.active[list] = (st.nt.pk.active[list] + (down ? 1 : -1) + n) % n;
        render(); reveal('[data-pop] .sb-menu-item.hl'); return true;
      }
      if (key === 'Enter') { e.preventDefault(); choosePick(list, st.nt.pk.active[list]); return true; }
      return false;
    }
    if (p && p.type === 'changes' && key === 'Escape') { e.preventDefault(); st.popover = null; refocusChanges(); render(); return true; }
    if (p && p.type === 'queue') {
      const c = ntCur(), q = st.nt.q;
      if (key === 'Escape') { e.preventDefault(); closeQueuePop(); focusComposer(); render(); return true; }
      if (!c.queue.length) return false;
      if (down || up) { e.preventDefault(); q.kb = (q.kb + (down ? 1 : -1) + c.queue.length) % c.queue.length; st.focusNext = 'queue-list'; render(); reveal('.sb-qrow.kb'); return true; }
      if (key === 'Delete' || key === 'Backspace') { e.preventDefault(); A['q-remove'](c.queue[Math.min(q.kb, c.queue.length - 1)].id); return true; }
      if (mod && e.code === 'KeyZ') { e.preventDefault(); A['q-undo'](); return true; }
      return false;
    }
    if (d || p) return false;
    if ((e.altKey || mod) && !(e.altKey && mod)) {
      const digit = /^Digit[1-9]$/.test(e.code) ? +e.code.slice(5) : null;
      if (e.code === 'KeyT') { e.preventDefault(); A['chat-new'](); return true; }
      if (e.code === 'KeyW') { e.preventDefault(); A['chat-close'](ntCur().id); return true; }
      if (key === 'Tab' || e.code === 'BracketRight' || e.code === 'BracketLeft') { e.preventDefault(); cycleChat(e.shiftKey || e.code === 'BracketLeft' ? -1 : 1); return true; }
      if (digit) { e.preventDefault(); const c = st.nt.chats[Math.min(digit, st.nt.chats.length) - 1]; if (c) selectChat(c.id); return true; }
    }
    if (e.target.dataset && e.target.dataset.input === 'composer' && key === 'Enter' && !e.shiftKey && !e.altKey && !mod) { e.preventDefault(); A['chat-send'](); return true; }
    const list = e.target.closest && e.target.closest('[data-inbox]');
    if (list && !e.altKey && !mod) {
      const rows = [...list.querySelectorAll('.sb-inbox-row')];
      const i = st.nt.inboxSel && st.nt.inboxSel.list === list.id ? st.nt.inboxSel.i : 0;
      if (!rows.length) return false;
      if (down || up) { e.preventDefault(); paintInbox(list, i + (down ? 1 : -1)); return true; }
      if (key === 'Enter') { e.preventDefault(); rows[i].click(); return true; }
      if (key === 'Delete' || key === 'Backspace') { const x = rows[i].querySelector('[data-act="saved-discard"]'); if (x) { e.preventDefault(); A['saved-discard'](x.dataset.arg); } return true; }
    }
    return false;
  }
  /* Opens the popup a #new-task/… route names. */
  function openRoute(open) {
    if (open === 'queue') { const i = st.nt.chats.findIndex((c) => c.queue.length); if (i >= 0) { st.nt.active = i; render(); A['q-toggle'](null, document.getElementById('nt-queue')); } }
    else if (open === 'changes') { if (document.getElementById('nt-changes')) A['chg-toggle'](null, document.getElementById('nt-changes')); }
    else if (open === 'picker') { const i = st.nt.chats.findIndex((c) => X.chatEmpty(c)); if (i >= 0) { st.nt.active = i; render(); A['pk-toggle'](null, document.getElementById('nt-target')); } }
    else if (open === 'resume') {
      A['resume-palette']();
      if (st.ntVariant === 'noMatch') st.dialog.q = 'no-such-saved-chat';
      if (st.ntVariant === 'cli') st.dialog.active = Math.max(0, X.resumeListing(resumeInput()).shown.findIndex((chat) => !chat.lines.length));
      render();
    }
    else if (open === 'closing') A['chat-close'](ntCur().id);
    else if (open === 'devices') A['dev-toggle']();
    else if (open === 'usage') A['usage-toggle']();
  }

  /* ---------- QA device popup (QA device design variant A) ---------- */
  /* The list is read when the popup opens and on Refresh (R); nothing polls in the background. ↑ ↓ skip devices that cannot
   * be picked, Enter picks, Esc closes and returns focus to the switcher. */
  function closeDevices() { st.popover = null; st.focusNext = 'qa-device'; render(); }
  function deviceKeys(e) {
    const p = st.popover;
    if (st.mode !== 'prototype' || !p || p.type !== 'devices' || st.cmdk || st.dialog) return false;
    const key = e.key, q = st.qaDev, opts = X.deviceOptions(X.deviceList(st));
    if (key === 'Escape') { e.preventDefault(); closeDevices(); return true; }
    if (key === 'ArrowDown' || key === 'ArrowUp') {
      e.preventDefault();
      const step = key === 'ArrowDown' ? 1 : -1;
      for (let i = 1; i <= opts.length; i++) { const n = (q.kb + step * i + opts.length) % opts.length; if (X.devicePickable(opts[n])) { q.kb = n; break; } }
      st.focusNext = 'device-list'; render(); reveal('.sb-devopt.kb'); return true;
    }
    if (key === 'Enter' && opts[q.kb]) { e.preventDefault(); A['dev-pick'](opts[q.kb].id); return true; }
    if (key.toLowerCase() === 'r' && !e.metaKey && !e.ctrlKey && !e.altKey) { e.preventDefault(); A['dev-refresh'](); return true; }
    return false;
  }

  /* ---------- actions ---------- */
  const item = (id) => X.lookup(id);
  /* ---------- Specs pane helpers ---------- */
  const specBy = (k) => D.specs.find((x) => x.key === k);
  function toggleSpec(path) { st.specOpen.has(path) ? st.specOpen.delete(path) : st.specOpen.add(path); render(); }
  function focusSpecTree() { const t = root.querySelector('[data-zone="specs"]'); if (t) t.focus({ preventScroll: true }); revealSpecRow(); }
  function revealSpecRow() { const r = root.querySelector('.sb-sidepane .sb-tree-row.focus'); if (r) r.scrollIntoView({ block: 'nearest' }); }
  function openSpecFile(sp, path) {
    const rest = path.slice(X.specPath(sp).length + 1);
    const m = rest.match(/^sub\/(\d+)$/);
    A['open-file'](`${X.specProject(st).store}/${sp.key.toLowerCase()}/${m ? `spec_subtask_${m[1]}.md` : rest}`);
  }
  /* The agent menu opens on the bar's agent pill; with `startAfter` the pick starts the run. */
  function openSpecAgent(sp, startAfter) {
    const pill = document.getElementById('spec-agent-pill');
    const pos = pill ? anchorPos(pill) : { x: innerWidth - 300, y: innerHeight - 200, top: innerHeight - 200 };
    st.popover = Object.assign({ type: 'agent', arg: 'spec:' + sp.key, startAfter, above: true }, pos); render();
  }
  /* Keys while the spec tree or the bar has focus (desktop SpecsPane, SpecsTree): ↑ ↓ move between rows, → ← expand, enter
   * and collapse, J K move between specs, Enter opens (a spec opens its spec.md), R P A F run the actions, / opens the
   * filter, Shift+F10 or the menu key opens the spec menu, Esc clears the selection. */
  function specKeys(e) {
    const ae = document.activeElement;
    if (!st.specsOpen || st.mode !== 'prototype' || !ae || !ae.closest || !ae.closest('[data-zone="specs"], .sb-specbar')) return false;
    if (e.metaKey || e.ctrlKey || e.altKey) return false;
    const rows = X.specRows, key = e.key, i = rows.indexOf(st.specSel);
    const sp = X.specOf(st.specSel);
    const go = (path) => { if (!path) return; st.specSel = path; render(); focusSpecTree(); };
    const done = () => { e.preventDefault(); return true; };
    if (key === 'ArrowDown') { go(rows[Math.min(rows.length - 1, i + 1)] || rows[0]); return done(); }
    if (key === 'ArrowUp') { go(rows[Math.max(0, i - 1)] || rows[0]); return done(); }
    if (key === 'j' || key === 'k') {
      const bundles = rows.filter((r) => { const b = X.specOf(r); return b && X.specPath(b) === r; });
      const cur = bundles.indexOf(sp ? X.specPath(sp) : null);
      go(bundles[cur < 0 ? (key === 'j' ? 0 : bundles.length - 1) : Math.max(0, Math.min(bundles.length - 1, cur + (key === 'j' ? 1 : -1)))]); return done();
    }
    if (key === 'ArrowRight' && st.specSel && !st.specOpen.has(st.specSel) && (st.specSel === 'done' || (sp && X.specPath(sp) === st.specSel))) { toggleSpec(st.specSel); focusSpecTree(); return done(); }
    if (key === 'ArrowLeft' && st.specSel) {
      if (st.specOpen.has(st.specSel)) { toggleSpec(st.specSel); focusSpecTree(); return done(); }
      if (sp && st.specSel !== X.specPath(sp)) { go(X.specPath(sp)); return done(); }
      if (sp && sp.folder) { go(sp.folder); return done(); }
      return done();
    }
    if (key === 'Enter') { if (st.specSel === 'done') toggleSpec('done'); else if (sp) openSpecFile(sp, st.specSel === X.specPath(sp) ? X.specPath(sp) + '/spec.md' : st.specSel); return done(); }
    if (key === '/') { A['spec-search'](); return done(); }
    if (key === 'Escape') { st.specSel = null; render(); focusSpecTree(); return done(); }
    if ((key === 'ContextMenu' || (key === 'F10' && e.shiftKey)) && sp) { const r = root.querySelector('.sb-sidepane .sb-tree-row.focus') || ae; st.popover = Object.assign({ type: 'spec', arg: sp.key }, anchorPos(r), { x: r.getBoundingClientRect().left + 40 }); render(); return done(); }
    if (!sp || e.shiftKey) return false;
    if (key === 'r') { A['spec-start'](sp.key); return done(); }
    if (key === 'p') { A['spec-pause'](sp.key); return done(); }
    if (key === 'a') { A['spec-agent'](sp.key); return done(); }
    if (key === 'f') { A['spec-fav'](sp.key); focusSpecTree(); return done(); }
    return false;
  }
  /* Spec menus: ↑ ↓ move between enabled items, the item's letter (R P A F) runs it, Esc closes. */
  function specMenuKeys(e) {
    const p = st.popover;
    if (!p || (p.type !== 'spec' && p.type !== 'spec-panel')) return false;
    const items = [...root.querySelectorAll('[data-pop] .sb-menu-item:not([disabled])')];
    const i = items.indexOf(document.activeElement);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); const n = items.length; items[(i + (e.key === 'ArrowDown' ? 1 : -1) + n) % n].focus(); return true; }
    const hit = !e.metaKey && !e.ctrlKey && items.find((x) => x.dataset.key === e.key.toLowerCase());
    if (hit) { e.preventDefault(); hit.click(); return true; }
    return false;
  }
  const A = {
    noop() {},
    'changes-open'(id) { openChanges(id || st.detailId); },
    'changes-close'() { closeChanges(); },
    surface(name) {
      if (name === 'changes') { openChanges(st.detailId); return; }
      if (location.hash === '#' + name) { st.mode = 'prototype'; applyRoute({ view: name }); render(); }
      else location.hash = name;
    },
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
    /* Phase tabs in the details pane: open that phase's card and keep focus on its tab. */
    'peek-pill'(arg) {
      const [id, key, phase] = arg.split('|');
      st.peekSel[`${id}|${key}`] = phase;
      render();
      const el = root.querySelector(`[data-act="peek-pill"][data-arg="${CSS.escape(arg)}"]`);
      if (el) el.focus({ preventScroll: true });
    },
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
    'usage-toggle'(a, el) {
      if (st.popover && st.popover.type === 'usage') return A['usage-close']();
      const anchor = el || document.getElementById('usage-card');
      if (!anchor) return;
      st.popover = Object.assign({ type: 'usage', above: true, gap: 8, flush: true, steady: true, role: 'dialog', focus: 'usage-close' }, anchorPos(anchor));
      render();
    },
    'usage-close'() { st.popover = null; st.focusNext = 'usage-card'; render(); },
    /* Tabs always work. In a run or chat the pick holds until that context's agent changes. */
    'usage-provider'(provider) {
      if (!D.USAGE.providers.includes(provider)) return;
      const usage = X.usageData(st);
      if (usage.contextAgent !== null) st.usage.override = { agent: usage.contextAgent, provider };
      else st.usage.provider = provider;
      st.usage.last = provider;
      st.focusNext = 'usage-provider-' + provider; render();
    },
    /* The agent new chats, goals and phases start with. The active chip clears it. */
    'usage-default-agent'(agent) {
      if (!D.AGENT_CHOICES.includes(agent)) return;
      st.prefs.defaultAgent = st.prefs.defaultAgent === agent ? null : agent;
      st.focusNext = 'usage-default-' + agent; render();
    },
    'usage-refresh'() {
      st.usage.scenario = 'normal';
      st.focusNext = 'usage-close'; render();
    },
    'layer-close-self'(a, el, e) { if (e.target === el) { st.dialog = null; st.cmdk = null; render(); } },
    'dialog-close'() { st.dialog = null; render(); },

    /* run controls */
    'pause-soft'(id) { const it = item(id); st.popover = null; if (!it || !X.pauseEligible(it)) return render(); it.pauseRequested = true; (it.flowEvents ||= []).push({ name: 'Pause requested', status: 'paused', t: 'Waiting for the next checkpoint.' }); render(); toast({ state: 'paused', title: 'Pause recorded', body: 'The goal pauses at its next checkpoint.' }); },
    'pause-now'(id) { const it = item(id); st.popover = null; if (!it || !X.controllable(it)) return render(); Object.assign(it, { lifecycle: 'paused', live: false, pauseRequested: false, liveness: 'idle', reason: 'operator_pause: Paused from the desktop app.', runOptions: Object.assign({}, it.runOptions, { canLaunch: true, launchLabel: 'Resume' }) }); (it.flowEvents ||= []).push({ name: 'Paused', status: 'paused', t: 'Paused from the desktop app.' }); render(); toast({ state: 'paused', title: 'Stop confirmed', body: 'The goal runner has exited.' }); },
    launch(id) {
      const it = item(id); st.popover = null;
      if (!it || !it.runOptions || !it.runOptions.canLaunch) return render();
      const agent = st.chosenAgent[id] || it.runOptions.agentId;
      if (!agent) { const btn = root.querySelector(`[data-act="agent-menu"][data-arg="${id}"]`); if (btn) return A['agent-menu'](id, btn); }
      Object.assign(it, { lifecycle: 'active', live: true, pauseRequested: false, finishedAt: null, finishedAgo: null, liveness: 'live', reason: null, incident: null, agent, section: it.kind === 'goal' ? 'goals' : it.kind === 'operation' ? 'operations' : 'phases', runOptions: Object.assign({}, it.runOptions, { canLaunch: false, canCancel: true }) });
      (it.flowEvents ||= []).push({ name: 'Run resumed', t: `Continuing at ${it.step}.` });
      render(); toast({ state: 'active', frac: 0.2, title: `${it.runOptions.launchLabel || 'Resume'} requested`, body: `Starts the installed ${agent} harness.` });
    },
    'agent-menu'(id, el) { st.popover = Object.assign({ type: 'agent', arg: id }, anchorPos(el)); render(); },
    'agent-pick'(arg) {
      const [id, agent] = arg.split('|'); const after = st.popover && st.popover.startAfter;
      st.chosenAgent[id] = agent; st.popover = null; render();
      if (after && id.startsWith('spec:')) A['spec-start'](id.slice(5));
    },
    cancel(id) { const it = item(id); st.popover = null; if (!it || !it.runOptions || !it.runOptions.canCancel) return render(); Object.assign(it, { lifecycle: 'canceled', section: 'finished', live: false, liveness: 'idle', reason: null, incident: null, pauseRequested: false, runOptions: Object.assign({}, it.runOptions, { canCancel: false, canLaunch: true, launchLabel: 'Run again' }) }); (it.flowEvents ||= []).push({ name: 'Canceled', status: 'canceled', t: 'Branch and commits retained.' }); render(); toast({ state: 'canceled', title: 'Cancel recorded', body: `${X.rowKey(it)} is marked canceled. Its branch and commits stay.` }); },
    prereq(key) { st.prereqs.has(key) ? st.prereqs.delete(key) : st.prereqs.add(key); render(); },
    recover(id) { const it = item(id); if (!it || !X.recoveryOffered(st, it)) return; st.dialog = { type: 'recover', arg: id }; render(); },
    'recover-confirm'(id) { st.recovered.add(id); st.dialog = null; render(); toast({ icon: 'refresh', title: 'Recovery request finished', body: 'Incident details were refreshed.' }); },
    copy(t) { st.popover = null; render(); copyText(t); },
    toast(t) { if (/Changes window|repair diff| in the diff/i.test(t)) { openChanges(st.detailId); return; } st.popover = null; render(); toast({ icon: 'checkCircle', title: t }); },
    'open-file'(f) { toast({ icon: 'external', title: 'Opened in its default app', body: f }); },
    'term-here'(id) { st.popover = null; openTerminalFor(item(id)); render(); },

    /* specs pane (specs pane design variant A; desktop SpecsPaneModel) */
    'specs-toggle'() { st.specsOpen = !st.specsOpen; st.popover = null; render(); },
    /* A bundle or file row selects; a folder toggles too; a file opens in its default app. */
    'spec-select'(path) {
      st.specSel = path; st.popover = null;
      const sp = X.specOf(path);
      if (path === 'done' || (!sp && !path.includes('/'))) { toggleSpec(path); return; }
      st.focusNext = null; render(); focusSpecTree();
      if (sp && path !== X.specPath(sp)) openSpecFile(sp, path);
    },
    'spec-toggle'(path) { toggleSpec(path); },
    'spec-collapse'() { st.specOpen.clear(); st.popover = null; render(); },
    'spec-status'(s) { st.specStatus = s; render(); },
    'spec-clear'() { st.specQuery = ''; st.specStatus = 'all'; st.specSearch = false; render(); },
    'spec-search'() { st.specSearch = true; st.focusNext = 'spec-q'; render(); },
    'spec-search-close'() { st.specSearch = false; st.specQuery = ''; render(); focusSpecTree(); },
    'spec-retry'() { toast({ icon: 'refresh', title: 'Retrying', body: 'Reading the spec store again.' }); },
    'spec-menu'(path, el) { st.specSel = path; render(); const btn = root.querySelector(`[data-act="spec-menu"][data-arg="${CSS.escape(path)}"]`) || el; st.popover = Object.assign({ type: 'spec', arg: X.specOf(path).key }, anchorPos(btn)); render(); },
    'spec-bar-menu'(path, el) { st.popover = Object.assign({ type: 'spec', arg: X.specOf(path).key, above: true }, anchorPos(el)); render(); },
    'spec-panel-menu'(a, el) { st.popover = Object.assign({ type: 'spec-panel' }, anchorPos(el)); render(); },
    'spec-open'(k) { const sp = specBy(k); st.popover = null; render(); if (sp) openSpecFile(sp, X.specPath(sp) + '/spec.md'); },
    'spec-reveal'(k) { const sp = specBy(k); st.popover = null; render(); toast({ icon: 'folder', title: 'Revealed the spec folder', body: `${X.specProject(st).store}/${sp.key.toLowerCase()}` }); },
    'spec-reveal-store'() { st.popover = null; render(); toast({ icon: 'folder', title: 'Revealed the specs folder', body: X.specProject(st).store }); },
    'spec-import'() { st.popover = null; render(); toast({ icon: 'file', title: 'Import work', body: 'Opens a file chooser for a work archive, then asks to import it.' }); },
    'spec-fav'(k) { const sp = specBy(k); st.popover = null; if (sp) sp.fav = !sp.fav; render(); },
    'spec-export'(k) {
      const sp = specBy(k); st.popover = null;
      if (sp.run === 'running' || sp.run === 'pausing') { st.dialog = { type: 'spec-export-live', arg: k }; return render(); }
      render(); toast({ icon: 'checkCircle', title: `Exported ${k} to ~/Downloads/${k}-work.zip` });
    },
    /* Start with no agent chosen opens the agent menu first and starts once one is picked (SpecsPaneModel.start). */
    'spec-start'(k) {
      const sp = specBy(k); st.popover = null;
      if (!sp || !X.specControl(sp).canStart) return render();
      const agent = X.specAgent(st, sp);
      if (!agent) { render(); return openSpecAgent(sp, true); }
      const label = X.specControl(sp).startLabel;
      Object.assign(sp, { run: 'running', ever: true, agent });
      render(); toast({ state: 'active', frac: 0.15, title: `${label} requested`, body: `${k} starts on ${agent}. It appears under Goal runs.` });
    },
    'spec-pause'(k) {
      const sp = specBy(k); st.popover = null; st.dialog = null;
      if (!sp || !X.specControl(sp).canPause) return render();
      sp.run = 'pausing'; render();
      toast({ state: 'paused', title: 'Pause recorded', body: 'The goal stops after its current subtask.' });
      setTimeout(() => { if (sp.run === 'pausing') { sp.run = 'paused'; render(); } }, 3000);
    },
    'spec-agent'(k) { const sp = specBy(k); st.popover = null; render(); if (sp && X.specControl(sp).canStart) openSpecAgent(sp, false); },
    'spec-cancel'(k) { st.popover = null; if (X.specControl(specBy(k)).canCancel) st.dialog = { type: 'spec-cancel', arg: k }; render(); },
    'spec-cancel-confirm'(k) { const sp = specBy(k); sp.run = 'canceled'; st.dialog = null; render(); toast({ state: 'canceled', title: 'Cancel recorded', body: `${k} is marked canceled. Its branch and commits stay.` }); },
    'spec-purge'(k) { st.popover = null; if (X.specControl(specBy(k)).canPurge) st.dialog = { type: 'purge', arg: k }; render(); },
    'purge-confirm'(k) { const sp = specBy(k); if (sp) Object.assign(sp, { run: 'not_started', ever: false, status: 'pending', subState: sp.subState.map(() => 'pending') }); st.dialog = null; render(); toast({ icon: 'rerun', title: `${k} run history purged` }); },
    'spec-delete'(k) { st.popover = null; if (X.specControl(specBy(k)).canDelete) st.dialog = { type: 'spec-delete', arg: k }; render(); },
    'spec-delete-confirm'(k) { const i = D.specs.findIndex((x) => x.key === k); const path = X.specPath(D.specs[i]); D.specs.splice(i, 1); st.dialog = null; if (st.specSel && (st.specSel === path || st.specSel.startsWith(path + '/'))) st.specSel = null; render(); toast({ icon: 'trash', title: `${k} spec files deleted`, body: 'Reload the page to restore the sample.' }); },
    'project-menu'(a, el) { st.popover = Object.assign({ type: 'project' }, anchorPos(el)); render(); },
    'project-pick'(id) { st.specProject = id; st.specSel = null; st.popover = null; render(); },

    /* plan runs (plan run design) */
    'plan-filter'(arg) { const [id, f] = arg.split('|'); st.planFilter[id] = f; render(); },
    'plan-follow'(id) { st.planFollow[id] = st.planFollow[id] === false; render(); },
    'plan-jump'(id) { st.planFollow[id] = true; render(); },
    'plan-group'(arg) { st.planShut.has(arg) ? st.planShut.delete(arg) : st.planShut.add(arg); render(); },
    /* A stage cell opens Activity and scrolls its feed to that stage; a stage that has not started only says so. */
    'plan-stage'(arg) {
      const [id, stage] = arg.split('|');
      const it = item(id), i = X.PLAN_STAGES.findIndex((x) => x[0] === stage);
      if (!it || i > Math.min(it.planRun.stage, 2)) return toast({ icon: 'info', title: 'This stage has not started yet' });
      st.secCollapse['plan-activity'] = false; st.planShut.delete(arg); st.planFollow[id] = false; render();
      const feed = root.querySelector(`[data-planfeed="${CSS.escape(id)}"]`), g = document.getElementById(`plan-anc-${id}-${stage}`);
      /* Stage headers stick, so measure from the top of the feed, where each header sits at its own place. */
      if (feed && g) { feed.scrollTop = 0; feed.scrollTop = g.offsetTop; planFeedScrolled(feed); }
    },
    'plan-ev'(a, el) { el.classList.toggle('open'); },
    'plan-tx'(id) { st.planTxLong.has(id) ? st.planTxLong.delete(id) : st.planTxLong.add(id); render(); },
    'plan-fold'(key) { st.planOpen.has(key) ? st.planOpen.delete(key) : st.planOpen.add(key); render(); },
    /* "briefing" on a Phase started row opens Briefing and that stage's raw briefing. */
    'plan-brief'(arg) {
      const [id, stage] = arg.split('|');
      st.secCollapse['plan-briefing'] = false; st.planOpen.add(`${id}:raw-${stage}`); render();
      const el = document.getElementById(`plan-brief-${id}-${stage}`); if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    },
    'plan-implement'(id) {
      const it = item(id); if (!it || st.implemented.has(id)) return;
      st.implemented.add(id); render();
      toast({ state: 'active', frac: 0.1, title: 'Implement requested', body: `Starts a goal for ${it.issue} on ${it.agent}. It appears under Goal runs.` });
    },

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
    /* QA results: a finding, an untested area or a fold opens and closes in place; focus stays on its head. */
    'qa-toggle'(key) {
      st.qaOpen.has(key) ? st.qaOpen.delete(key) : st.qaOpen.add(key);
      render();
      const el = root.querySelector(`[data-act="qa-toggle"][data-arg="${CSS.escape(key)}"]`);
      if (el) el.focus({ preventScroll: true });
    },

    /* Monitor run: a Cycles cell opens its step's panel; a failed check opens its log tail; a group folds to one line. */
    'mon-cell'(arg) { const [id, key] = arg.split('|'); st.monSel[id] = key; render(); refocus('mon-cell', arg); },
    'mon-log'(key) { st.monOpen.has(key) ? st.monOpen.delete(key) : st.monOpen.add(key); render(); refocus('mon-log', key); },
    'mon-fold'(key) { st.monFold.has(key) ? st.monFold.delete(key) : st.monFold.add(key); render(); },

    /* Validation run: report parts and checks open in place; the flag strip opens a part and scrolls to it. */
    'val-part'(arg) {
      const [id, i] = arg.split('|'); const it = item(id); if (!it) return;
      const open = st.valParts[id] || (st.valParts[id] = X.valDefaultParts(it));
      open.has(+i) ? open.delete(+i) : open.add(+i);
      render(); refocus('val-part', arg);
    },
    'val-all'(id) {
      const it = item(id); const r = it && X.shapeReport(it.valRun.result); if (!r) return;
      const open = st.valParts[id] || X.valDefaultParts(it);
      st.valParts[id] = open.size === r.parts.length ? new Set() : new Set(r.parts.map((_, i) => i));
      render();
    },
    'val-jump'(arg) {
      const [id, i] = arg.split('|'); const it = item(id); if (!it) return;
      (st.valParts[id] || (st.valParts[id] = X.valDefaultParts(it))).add(+i);
      render(); revealEl(`val-part-${i}`, 'val-part', arg);
    },
    /* Run again on a finished or canceled validation run starts a new run on the same branch; this one stays as it is. */
    'val-again'(id) { const it = item(id); if (!it || !it.valRun) return; toast({ state: 'active', frac: 0.1, title: 'Run again requested', body: `Starts a new validation run on ${it.branch} with ${it.agent || 'the default agent'}. This run stays as it is.` }); },
    'val-check'(arg) {
      const [id, check] = arg.split('|'); const it = item(id); if (!it) return;
      const open = st.valChecks[id] || (st.valChecks[id] = X.valDefaultChecks(it));
      open.has(check) ? open.delete(check) : open.add(check);
      render(); refocus('val-check', arg);
    },

    /* Verify run: steps, criteria rows and folds open in place; a count in the outcome card jumps to its evidence. */
    'v-toggle'(key) { st.vOpen.has(key) ? st.vOpen.delete(key) : st.vOpen.add(key); render(); refocus('v-toggle', key); },
    'v-filter'(arg) { const [id, f] = arg.split('|'); st.vFilter[id] = f; render(); },
    'v-jump'(arg) {
      const [id, to] = arg.split('|');
      if (to === 'criteria') { st.secCollapse['verify-criteria'] = false; render(); return revealEl('sec-criteria'); }
      st.secCollapse['verify-steps'] = false; st.vOpen.add(`${id}:s:${to}`); render(); revealEl('vstep-' + to, 'v-toggle', `${id}:s:${to}`);
    },
    /* Fix selection: a checkbox adds an item on its default route; the switch changes the route; a mark takes a criterion out. */
    'vf-sel'(arg) {
      const [id, key] = arg.split('|'); const it = item(id); if (!it) return;
      const x = X.fixItems(it.verifyRun).find((y) => y.key === key); if (!x) return;
      const sel = st.vSel[id] || (st.vSel[id] = {});
      key in sel ? delete sel[key] : (sel[key] = x.route);
      render(); refocus('vf-sel', arg);
    },
    'vf-route'(arg) { const [id, key, route] = arg.split('|'); const sel = st.vSel[id]; if (sel && key in sel) sel[key] = route; render(); },
    'vf-all'(arg) {
      const [id, kind] = arg.split('|'); const it = item(id); if (!it) return;
      const marks = st.vMarks[id] || {};
      const list = X.fixItems(it.verifyRun).filter((x) => x.kind === kind && !marks[x.key]);
      const sel = st.vSel[id] || (st.vSel[id] = {});
      const all = list.every((x) => x.key in sel);
      list.forEach((x) => { if (all) delete sel[x.key]; else if (!(x.key in sel)) sel[x.key] = x.route; });
      render(); refocus('vf-all', arg);
    },
    'vf-mark'(arg) {
      const [id, key, mark] = arg.split('|');
      const marks = st.vMarks[id] || (st.vMarks[id] = {});
      if (mark === 'none') delete marks[key]; else { marks[key] = mark; if (st.vSel[id]) delete st.vSel[id][key]; }
      render();
    },
    'vf-clear'(id) { st.vSel[id] = {}; render(); },
    'vf-chat'(id) {
      const it = item(id); if (!it) return;
      const f = X.verifyFixText(st, it, 'chat');
      f.items.forEach((x) => delete st.vSel[id][x.key]);
      render(); toast({ icon: 'bot', title: `Opened agent chat on ${f.target}`, body: `One brief with ${f.items.map((x) => x.label).join(', ')}. This Verify run stays as it is.` });
    },
    /* Start goal: a New task chat on the verified branch with the goal intake filled in; the operator types the issue key. */
    'vf-goal'(id) {
      const it = item(id); if (!it) return;
      const f = X.verifyFixText(st, it, 'goal');
      if (!D.REPO_BRANCHES[f.repo]) { const repo = { name: f.repo, parent: X.repoOf(f.repo).path.replace(/\/[^/]*$/, ''), defaultBranch: 'main', local: [[f.target, 'now']], remote: [] }; D.REPO_BRANCHES[f.repo] = repo; st.nt.repos.push(repo); }
      const c = chatFromSaved(null, { repo: f.repo, branch: f.target, draft: f.text });
      st.nt.chats.push(c); st.nt.active = st.nt.chats.length - 1;
      f.items.forEach((x) => delete st.vSel[id][x.key]);
      go('newtask');
      toast({ icon: 'play', title: 'Goal intake ready in New task', body: `${f.items.map((x) => x.label).join(', ')} with their evidence, based on ${f.target}. Type the issue key, then start.` });
    },
    /* Verify run controls: confirm the parked criteria, stop, resume at the stopped step, adjust, discard, run again. */
    'verify-confirm'(id) {
      const it = item(id); const r = it && it.verifyRun; if (!r || !r.criteria.length || X.verifyState(r) !== 'parked') return;
      r.status = 'running'; r.current = 'gather_diff';
      r.steps.forEach((s) => { if (s.id === 'extract_criteria') s.status = 'completed'; if (s.id === 'gather_diff') { s.status = 'running'; s.attempts = 1; } });
      Object.assign(it, { lifecycle: 'active', live: true, section: 'operations', step: 'gather_diff', summary: `Step 3 of ${r.steps.length} · Gather diff`, runOptions: Object.assign({}, it.runOptions, { canCancel: true }) });
      render(); toast({ state: 'active', frac: 0.3, title: 'Criteria confirmed', body: `Verify ${r.issueKey} continues at Gather diff and audits ${r.criteria.length} criteria.` });
    },
    'verify-stop'(id) {
      const it = item(id); const r = it && it.verifyRun; if (!r || X.verifyState(r) !== 'running') return;
      const now = Date.now();
      r.status = 'abandoned'; r.staleReason = null; r.finishedAt = new Date(now).toISOString(); r.startedAt = new Date(now - (it.goalMs || 0) - (now - D.T0)).toISOString();
      const s = r.steps.find((x) => x.status === 'running');
      Object.assign(it, { lifecycle: 'canceled', live: false, runOptions: Object.assign({}, it.runOptions, { canCancel: false }) });
      render(); toast({ state: 'canceled', title: 'Stop recorded', body: `Verify ${r.issueKey} stopped at ${X.VSTEP[s ? s.id : r.current]}. Finished steps keep their results.` });
    },
    'verify-resume'(id) {
      const it = item(id); const r = it && it.verifyRun; if (!r || X.verifyState(r) !== 'stopped') return;
      const s = r.steps.find((x) => x.status === 'running' || x.status === 'failed' || (x.status === 'pending' && x.attempts > 0));
      r.status = 'running'; r.finishedAt = null; r.staleReason = null;
      if (s) { s.status = 'running'; s.attempts = Math.max(1, s.attempts); r.current = s.id; }
      Object.assign(it, { lifecycle: 'active', live: true, section: 'operations', runOptions: Object.assign({}, it.runOptions, { canCancel: true, canLaunch: false }) });
      render(); toast({ state: 'active', frac: 0.5, title: `Resumed from ${X.VSTEP[r.current]}`, body: 'Earlier steps keep their results.' });
    },
    'verify-adjust'(id) { const it = item(id); toast({ icon: 'pencil', title: 'Adjust the intake in chat', body: `Opens agent chat with the intake of Verify ${it ? it.verifyRun.issueKey : ''}. Paste the criteria or point it at a spec file, then start verify again.` }); },
    'verify-discard'(id) {
      const it = item(id); const r = it && it.verifyRun; if (!r || X.verifyState(r) !== 'parked') return;
      r.status = 'abandoned'; r.finishedAt = new Date().toISOString();
      Object.assign(it, { lifecycle: 'canceled', live: false, runOptions: Object.assign({}, it.runOptions, { canCancel: false }) });
      render(); toast({ state: 'canceled', title: 'Verify discarded', body: `Verify ${r.issueKey} is canceled. Nothing was audited.` });
    },
    'verify-again'(id) { const it = item(id); const r = it && it.verifyRun; if (!r) return; toast({ state: 'active', frac: 0.1, title: 'Verify again requested', body: `Starts a new Verify run on ${r.target} with the same intake. This run stays as it is.` }); },

    /* QA device: the switcher opens the popup above itself and reads the list again */
    'dev-toggle'(a, el) {
      if (st.popover && st.popover.type === 'devices') return closeDevices();
      const anchor = el || document.getElementById('qa-device');
      if (!anchor) return;
      st.qaDev.kb = Math.max(0, X.deviceOptions(X.deviceList(st)).findIndex((d) => d.id === st.qaDev.selected));
      st.popover = Object.assign({ type: 'devices', above: true, gap: 8, flush: true, cls: 'sb-pop--dev', steady: true, role: 'dialog', focus: 'device-list' }, anchorPos(anchor));
      A['dev-refresh'](true);
    },
    'dev-refresh'(quiet) {
      st.qaDev.checking = true; render();
      clearTimeout(devTimer);
      devTimer = setTimeout(() => { st.qaDev.checking = false; if (st.popover && st.popover.type === 'devices') render(); }, quiet === true ? 250 : 600);
    },
    'dev-pick'(id) {
      const d = X.deviceOptions(X.deviceList(st)).find((x) => x.id === id);
      if (!d || !X.devicePickable(d)) return;
      st.qaDev.selected = id; closeDevices();
    },
    'feed-all'(id) { st.feedAll.add(id); render(); },

    /* Run app (SONA-10): build and open the goal's app on the QA pick; the chevron opens the device popup under itself. */
    'app-run'(id) { startAppRun(id, false); },
    'app-run-stop'(id) {
      const run = st.appRun[id]; if (!run || run.state !== 'building') return;
      appTimers.forEach(clearTimeout); appTimers = [];
      const tab = st.termTabs.find((t) => t.id === run.tab);
      if (tab) { tab.log.push(['wn', 'Stopped by you.'], ...R.release(run.device.name)); tab.live = false; }
      st.appRun[id] = Object.assign({}, run, { state: 'stopped' }); render();
    },
    'app-run-device'(id, el) {
      if (st.popover && st.popover.type === 'devices') return closeDevices();
      const anchor = el || document.getElementById(`app-run-dev-${id}`);
      if (!anchor) return;
      st.qaDev.kb = Math.max(0, X.deviceOptions(X.deviceList(st)).findIndex((d) => d.id === st.qaDev.selected));
      st.popover = Object.assign({ type: 'devices', runApp: id, gap: 4, flush: true, cls: 'sb-pop--dev', steady: true, role: 'dialog', focus: 'device-list' }, anchorPos(anchor));
      A['dev-refresh'](true);
    },

    /* New task: open chats (NewTaskChats) */
    'chat-select'(id) { selectChat(id); },
    'chat-new'() { const c = blankChat(); st.nt.chats.push(c); selectChat(c.id); },
    'chat-close'(id) { const c = chatById(id); st.popover = null; if (!c) return render(); if (c.stream) { st.dialog = { type: 'chat-close', arg: c.id }; return render(); } closeChat(c); },
    'chat-close-confirm'(id) { const c = chatById(id); if (c) closeChat(c); },
    'chat-reopen'(id) { const c = st.nt.closed[id]; if (!c) return; delete st.nt.closed[id]; st.nt.chats.push(c); st.toasts = []; renderToasts(); if (st.view !== 'newtask') go('newtask'); selectChat(c.id); },
    'chat-discard'(id) { const c = (id != null && chatById(id)) || ntCur(); stopReply(c); st.nt.saved = st.nt.saved.filter((x) => x.sessionId !== c.session); removeChat(c); st.popover = null; focusComposer(); render(); },
    'chat-menu'(a, el) { st.nt.menuQ = ''; st.popover = Object.assign({ type: 'chats', focus: st.nt.chats.length > 8 ? 'chat-menu-q' : null }, anchorPos(el)); render(); },
    /* Send; while a reply runs, Queue; with an empty draft and messages queued, Send now. "/resume" opens the resume palette. */
    'chat-send'() {
      const c = ntCur(), text = c.draft.trim();
      if (text === '/resume') { c.draft = ''; return A['resume-palette'](); }
      if (c.stream) {
        if (text) { c.queue.push({ id: queueSeq++, text }); c.draft = ''; }
        else if (c.queue.length) return sendNow(c);
        focusComposer(); return render();
      }
      if (!text || !c.repo) return;
      if (!c.title) {
        c.title = text.length > 60 ? text.slice(0, 58) + '…' : text;
        c.session = 'ses-new-' + c.id;
        st.nt.saved.unshift({ sessionId: c.session, agent: c.agent, updatedMin: 0, title: c.title, lines: c.lines, images: [], newTask: { repositoryRoot: c.repo, baseBranch: c.branch } });
      }
      c.lines.push({ role: 'operator', text }); c.draft = ''; st.popover = null;
      focusComposer(); render();
      setTimeout(() => { if (!c.stream) { startReply(c); render(); } }, 400);
    },
    /* Stop ends the reply and returns queued messages to the draft, in order, ahead of what is typed. */
    'chat-stop'() { const c = ntCur(); stopReply(c); c.draft = [...c.queue.map((m) => m.text), c.draft].filter((t) => t.trim()).join('\n\n'); c.queue = []; closeQueuePop(); focusComposer(); render(); },
    'chat-quick'(text) { ntCur().draft = text; focusComposer(); render(); },
    /* A suggested action clicked while a reply runs joins the queue as an action; otherwise it is sent. */
    'chat-suggested'(text) { const c = ntCur(); if (c.stream) { c.queue.push({ id: queueSeq++, text, action: true }); focusComposer(); return render(); } c.draft = text; A['chat-send'](); },
    'chat-default-branch'() {
      const c = ntCur();
      c.checkoutRoot = c.defaultWorktree || c.checkoutRoot;
      c.currentBranch = (D.REPO_BRANCHES[c.repo] || {}).defaultBranch || 'main';
      c.branch = ''; c.branchIntent = false;
      c.lines.push({ role: 'agent', text: `Selected ${c.checkoutRoot || c.repo}. Earlier conversation and unsent text are retained.` });
      render();
    },
    'chat-current-base'() { const c = ntCur(); c.branch = c.currentBranch; c.branchIntent = false; render(); },
    'nt-agent-menu'(a, el) { st.popover = Object.assign({ type: 'nt-agent', above: true }, anchorPos(el)); render(); },
    'nt-agent-pick'(a) { ntCur().agent = a; st.popover = null; focusComposer(); render(); },

    /* repository and base branch picker */
    'pk-toggle'(a, el) {
      if (st.popover && st.popover.type === 'target') return closeTarget();
      const c = ntCur(), pk = st.nt.pk;
      pk.filter = { repo: '', branch: '' }; pk.addError = null;
      const items = X.pickerItems(pickerInput());
      pk.active.repo = Math.max(0, items.repo.findIndex((i) => i.id === c.repo));
      pk.active.branch = Math.max(0, items.branch.findIndex((i) => (c.branch ? i.id === c.branch : i.kind === 'default')));
      st.popover = Object.assign({ type: 'target', above: true, flush: true, role: 'dialog', focus: st.nt.repos.length > 7 ? 'pk-filter-repo' : 'pk-list-repo' }, anchorPos(el || document.getElementById('nt-target')));
      render(); reveal('[data-pop] .sb-menu-item.hl');
    },
    'pk-pick'(arg) { const [list, i] = arg.split('|'); choosePick(list, +i); },
    'pk-add'() { addRepository(); },
    'pk-retry'() { st.nt.pk.branchState = 'ok'; if (st.popover) st.popover.focus = 'pk-filter-branch'; st.focusNext = 'pk-filter-branch'; render(); },

    'toast-close'(id) { st.toasts = st.toasts.filter((t) => t.id !== id); renderToasts(); },

    /* chat changes: the pill opens the file list; Open diff, a file or "N more files" opens the Changes window */
    'chg-toggle'(a, el) {
      if (st.popover && st.popover.type === 'changes') { st.popover = null; return render(); }
      const anchor = el || document.getElementById('nt-changes');
      st.popover = Object.assign({ type: 'changes', above: true, flush: true, role: 'dialog', right: anchor.getBoundingClientRect().right }, anchorPos(anchor));
      render();
    },
    'chg-open'(path) {
      const chat = ntCur();
      const context = window.DesignFlow.registerChat(chat, X.chatLabel(chat));
      openChanges(context, null, path);
      st.changeReturnFocus = 'nt-changes';
    },

    /* queued messages */
    'q-toggle'(a, el) {
      if (st.popover && st.popover.type === 'queue') { closeQueuePop(); focusComposer(); return render(); }
      Object.assign(st.nt.q, { kb: 0, undo: null, clearArmed: false });
      st.popover = Object.assign({ type: 'queue', above: true, flush: true, role: 'dialog', focus: 'queue-list' }, anchorPos(el || document.getElementById('nt-queue')));
      render();
    },
    'q-remove'(id) {
      const c = ntCur(), q = st.nt.q, i = c.queue.findIndex((m) => m.id === +id);
      if (i < 0) return;
      const [msg] = c.queue.splice(i, 1);
      q.clearArmed = false;
      if (!c.queue.length) { closeQueuePop(); focusComposer(); return render(); }
      q.kb = Math.min(i, c.queue.length - 1);
      q.undo = { chat: c.id, msg, index: i, text: `Removed message ${i + 1}` };
      clearTimeout(undoTimer);
      undoTimer = setTimeout(() => { if (q.undo && q.undo.msg === msg) { q.undo = null; render(); } }, 5000);
      st.focusNext = 'queue-list'; render();
    },
    'q-undo'() {
      const q = st.nt.q, u = q.undo, c = u && chatById(u.chat);
      if (!c) return;
      const at = Math.min(u.index, c.queue.length);
      c.queue.splice(at, 0, u.msg);
      q.kb = at; q.undo = null; st.focusNext = 'queue-list'; render();
    },
    /* Clear all asks once more before it empties the queue. */
    'q-clear'() { const q = st.nt.q; if (!q.clearArmed) { q.clearArmed = true; return render(); } ntCur().queue = []; closeQueuePop(); focusComposer(); render(); },

    /* home lists and saved chats: an open chat selects its tab, any other loads into the empty chat or a new tab */
    'saved-open'(session) {
      const open = st.nt.chats.find((c) => c.session === session);
      if (open) return selectChat(open.id);
      const x = st.nt.saved.find((s) => s.sessionId === session);
      if (!x) return;
      const fresh = chatFromSaved(x);
      if (!fresh.lines.length) fresh.lines = [{ role: 'notice', text: `Resumed the ${x.agent} CLI session` }, { role: 'agent', text: 'Picking this up where we left it.' }];
      if (X.chatEmpty(ntCur())) st.nt.chats[st.nt.active] = fresh; else st.nt.chats.push(fresh);
      st.dialog = null;
      if (st.view !== 'newtask') go('newtask');
      selectChat(fresh.id);
    },
    'saved-discard'(session) {
      const i = st.nt.saved.findIndex((x) => x.sessionId === session);
      if (i < 0) return;
      const [gone] = st.nt.saved.splice(i, 1);
      st.nt.discarded[session] = { entry: gone, index: i };
      render();
      toast({ icon: 'trash', title: `Discarded ${gone.title}`, actions: [S.Button({ label: 'Undo', variant: 'ghost', size: 'sm', act: 'saved-undo', arg: session })] });
    },
    'saved-undo'(session) { const d = st.nt.discarded[session]; if (!d) return; delete st.nt.discarded[session]; st.nt.saved.splice(Math.min(d.index, st.nt.saved.length), 0, d.entry); st.toasts = []; renderToasts(); render(); },

    /* /resume palette (ResumePalette) */
    'resume-palette'() { if (st.view !== 'newtask') go('newtask'); if (!ntCur().repo) return; st.popover = null; st.dialog = { type: 'resume', q: '', active: 0, showAll: false, focus: 'resume-q' }; render(); },
    /* Click selects a chat and shows its preview; a double click resumes it. */
    'resume-pick'(i, el, e) { if (e && e.detail > 1) return A['resume-open'](i); st.dialog.active = +i; render(); },
    'resume-move'(d) { const n = X.resumeListing(resumeInput()).shown.length; st.dialog.active = Math.max(0, Math.min(n - 1, st.dialog.active + d)); render(); reveal('.sb-rrow.active'); },
    'resume-open'(i) { if (i != null) st.dialog.active = +i; const x = X.resumeListing(resumeInput()).shown[st.dialog.active]; if (!x) return; st.dialog = null; A['saved-open'](x.sessionId); },
    'resume-more'() { st.dialog.showAll = true; render(); },
    'resume-close'() { st.dialog = null; focusComposer(); render(); },
    'resume-close-self'(a, el, e) { if (e.target === el) A['resume-close'](); },
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
    if (el.dataset.ctx.startsWith('chat:')) {
      /* Shift+F10 and the menu key fire contextmenu with no pointer position, so the menu opens under the tab. */
      const at = e.clientX || e.clientY ? { x: e.clientX, y: e.clientY, top: e.clientY } : anchorPos(el);
      st.popover = Object.assign({ type: 'chat-tab', arg: el.dataset.ctx.slice(5) }, at); render(); return;
    }
    if (el.dataset.ctx.startsWith('spec:')) {
      const path = el.dataset.ctx.slice(5), sp = X.specOf(path);
      st.specSel = path;
      st.popover = { type: 'spec', arg: sp.key, x: e.clientX, y: e.clientY, top: e.clientY }; render(); return;
    }
    const id = el.dataset.ctx.slice(5);
    const i = +el.dataset.row; if (!isNaN(i)) st.focus = i;
    st.popover = { type: 'item', arg: id, x: e.clientX, y: e.clientY, top: e.clientY }; render();
  });
  root.addEventListener('input', (e) => {
    const t = e.target, k = t.dataset.input;
    if (t.id === 'cmdk-input') { st.cmdk.q = t.value; st.cmdk.hl = 0; renderCmdkList(); return; }
    /* The composer keeps its draft per chat and relabels Send / Queue / Send now in place. */
    if (k === 'composer') { const c = chatById(t.dataset.chat); if (c) { c.draft = t.value; const b = document.getElementById('composer-send'); if (b) b.outerHTML = X.ntSendButton(c); } return; }
    if (k === 'pk-filter') { st.nt.pk.filter[t.dataset.pick] = t.value; st.nt.pk.active[t.dataset.pick] = 0; render(); return; }
    if (k === 'chat-menu-q') { st.nt.menuQ = t.value; render(); return; }
    if (k === 'resume-q') { st.dialog.q = t.value; st.dialog.active = 0; render(); return; }
    if (k === 'work-filter') { st.filter = t.value; st.focus = 0; render(); }
    if (k === 'spec-q') { st.specQuery = t.value; render(); }
    if (k === 'ed-model') { st.draft.model = t.value; render(); }
    if (k === 'ed-effort') { st.draft.effort = t.value; }
    if (k === 'ed-field') { const had = st.draft.error; st.draft[t.id.slice(3)] = t.value; st.draft.error = null; if (had) render(); }
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
  /* Phase tabs: arrow keys move between the phases that open a card; Home and End jump to the first and last. */
  root.addEventListener('keydown', (e) => {
    const tab = e.target.closest && e.target.closest('[data-act="peek-pill"]');
    if (!tab || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
    const tabs = [...tab.closest('[role="tablist"]').querySelectorAll('[data-act="peek-pill"]')];
    let i = tabs.indexOf(tab);
    i = e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : Math.max(0, Math.min(tabs.length - 1, i + (e.key === 'ArrowRight' ? 1 : -1)));
    e.preventDefault(); e.stopPropagation();
    A['peek-pill'](tabs[i].dataset.arg);
  }, true);
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
  /* While the queue is open, hovering a row moves its highlight there. */
  root.addEventListener('mouseover', (e) => {
    if (!st.popover || st.popover.type !== 'queue') return;
    const r = e.target.closest && e.target.closest('.sb-qrow');
    if (!r || +r.dataset.qi === st.nt.q.kb) return;
    st.nt.q.kb = +r.dataset.qi;
    root.querySelectorAll('.sb-qrow').forEach((x) => { const on = +x.dataset.qi === st.nt.q.kb; x.classList.toggle('kb', on); x.setAttribute('aria-selected', on); });
  });
  /* Home lists select their first row when focus arrives by keyboard and drop the selection when focus leaves. */
  root.addEventListener('focusin', (e) => {
    const l = e.target.matches && e.target.matches('[data-inbox]:focus-visible') ? e.target : null;
    if (l && (!st.nt.inboxSel || st.nt.inboxSel.list !== l.id)) paintInbox(l, 0);
  });
  root.addEventListener('focusout', () => setTimeout(() => {
    const a = document.activeElement;
    if (st.nt.inboxSel && !(a && a.closest && a.closest('[data-inbox]'))) { st.nt.inboxSel = null; root.querySelectorAll('.sb-inbox-row.sel').forEach((r) => { r.classList.remove('sel'); r.setAttribute('aria-selected', false); }); }
  }, 0));
  /* Scroll events do not bubble: catch the tab strip and home lists on the way down. A vertical wheel scrolls the tab strip sideways. */
  root.addEventListener('scroll', (e) => {
    const t = e.target;
    if (!t.matches) return;
    if (t.matches('[data-ctabs]')) fitChatTabs();
    else if (t.matches('[data-inbox]')) fadeInbox(t);
    else if (t.matches('[data-planfeed]')) planFeedScrolled(t);
  }, true);
  root.addEventListener('wheel', (e) => {
    const ts = e.target.closest && e.target.closest('[data-ctabs]');
    if (ts && Math.abs(e.deltaY) > Math.abs(e.deltaX)) { ts.scrollLeft += e.deltaY; e.preventDefault(); }
  }, { passive: false });
  window.addEventListener('hashchange', () => {
    st.changeView = null;
    st.mode = modeFromHash();
    const r = routeFromHash();
    if (r) {
      if (r.scenario || r.variant) { streamTimers.forEach(clearInterval); streamTimers.clear(); }
      applyRoute(r); st.popover = null; st.dialog = null;
    }
    render();
    if (r && r.open) openRoute(r.open);
    if (r && (r.scenario || r.variant)) st.nt.chats.forEach((chat) => { if (chat.stream) runStream(chat); });
  });
  window.addEventListener('resize', () => { if (st.popover) { st.popover = null; render(); } });

  let gPending = false, gTimer = null;
  document.addEventListener('keydown', (e) => {
    if (changesKeys(e)) return;
    const mod = e.metaKey || e.ctrlKey;
    const key = e.key;
    if (!st.popover && e.target.id === 'usage-card' && (key === 'Enter' || key === ' ')) { e.preventDefault(); return A['usage-toggle'](); }
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
    if (deviceKeys(e)) return;
    if (st.popover && st.popover.type === 'usage') {
      if (key === 'Escape') { e.preventDefault(); return A['usage-close'](); }
      if (key === 'Tab') {
        const controls = [...root.querySelectorAll('[data-pop] button:not([disabled])')];
        const index = controls.indexOf(document.activeElement);
        e.preventDefault();
        controls[(index + (e.shiftKey ? -1 : 1) + controls.length) % controls.length].focus();
      }
      return;
    }
    if (newTaskKeys(e)) return;
    if (specMenuKeys(e)) return;
    if (!st.popover && !st.dialog && specKeys(e)) return;
    if (key === 'Escape' && st.specSearch && e.target.id === 'spec-q') { e.preventDefault(); return A['spec-search-close'](); }
    if (key === 'Escape') {
      if (st.dialog) { st.dialog = null; return render(); }
      if (st.popover) { st.popover = null; return render(); }
      if (st.draft) return A['ed-cancel']();
      if (st.sideOpen) { st.sideOpen = false; return render(); }
      if (/INPUT|TEXTAREA/.test(e.target.tagName)) { e.target.blur(); return; }
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
      if (k === 'n') return A.nav('newtask');
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
  st.nt.chats.forEach((c) => { if (c.stream) runStream(c); });
  if (bootRoute && bootRoute.open) openRoute(bootRoute.open);
})();
