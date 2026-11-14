/* Sona mobile study · screens
 * Composed from ds/components.js (MB) and the shared desktop marks (SB) only.
 * render(st) returns { body, over } for one phone. State lives in app/main.js. */
(function () {
  'use strict';
  const M = window.MB;
  const SB = window.SB;
  const D = window.MD;
  const { esc, Icon, StatusIcon } = SB;

  /* ---------- Lookups ---------- */
  const runOf = (st, id) => { const r = D.RUNS.find((x) => x.id === id); return r && Object.assign({}, r, st.runs[id] || {}); };
  const runs = (st) => D.RUNS.map((r) => runOf(st, r.id));
  const machine = (st) => D.MACHINES.find((m) => m.id === st.machine);
  const allChats = (st) => (st.extraChats || []).concat(D.CHATS);
  const chatOf = (st, id) => { const c = allChats(st).find((x) => x.id === id); return c && Object.assign({}, c, st.chats[id] || {}); };
  const needsYou = (st) => runs(st).filter((r) => r.needs && ['blocked', 'failed', 'paused'].includes(r.state));
  const NEEDS_ORDER = { Blocked: 0, Failed: 1, Decision: 2, 'Confirm criteria': 3, Paused: 4 };
  const toneOf = (r) => ({ Blocked: 'blocked', Failed: 'failed', Decision: 'accent', 'Confirm criteria': 'accent', Paused: 'paused' }[r.needs]);
  const kindLabel = (r) => (r.kind === 'goal' ? null : r.op || r.phaseName);
  const keyOf = (r) => (r.kind === 'phase' ? r.phaseName : r.id);

  const TABS = [
    { id: 'needs', icon: 'inbox', label: 'Needs you' },
    { id: 'work', icon: 'layers', label: 'Work' },
    { id: 'chats', icon: 'message', label: 'Chats' },
    { id: 'plans', icon: 'fileText', label: 'Plans' },
    { id: 'settings', icon: 'sliders', label: 'Settings' },
  ];
  function tabBar(st) {
    const n = needsYou(st).length;
    return M.TabBar({ active: st.tab, tabs: TABS.map((t) => Object.assign({}, t, { badge: t.id === 'needs' && n ? n : null })) });
  }

  /* Connection line under large titles, and the banner when the link is not healthy. */
  function freshness(st) {
    const m = machine(st);
    if (st.conn === 'connecting') return `${Icon('monitor', 'xs')}<span>${esc(m.name)}</span><span>·</span><span>Connecting…</span>`;
    if (st.conn === 'unreachable') return `${Icon('monitor', 'xs')}<span>${esc(m.name)}</span><span>·</span><span>Data from 14:02</span>`;
    return `${Icon('monitor', 'xs')}<span>${esc(m.name)}</span><span>·</span><span>Updated ${esc(st.updated || m.updated)}</span>`;
  }
  function connBanner(st) {
    const m = machine(st);
    if (st.conn === 'connecting') return M.Banner({ tone: 'planning', spin: true, text: `Connecting to ${esc(m.name)} over SSH…` });
    if (st.conn === 'unreachable') return M.Banner({ tone: 'warn', icon: 'wifi', text: `Can't reach ${esc(m.name)}. Showing what it reported at 14:02.`, action: { label: 'Retry', act: 'retry' } });
    return '';
  }

  /* ---------- Board rows ---------- */
  function runLine(r) {
    if (r.state === 'blocked' && r.incident) return esc(r.incident.diagnosis) + ' · ' + esc(r.incident.cause.split('.')[0]);
    if (r.state === 'failed' && r.reason) return esc(r.reason);
    return esc(r.step);
  }
  function workRow(st, r, o) {
    const opts = o || {};
    return M.WorkRow({
      act: 'push', arg: 'detail:' + r.id, state: r.state, frac: r.frac, key: keyOf(r), title: r.title,
      badge: opts.badge ? r.needs : null, tone: opts.badge ? toneOf(r) : null,
      time: r.state === 'active' ? r.elapsed : r.finished || r.since || r.pausedAt || r.started,
      line: runLine(r),
      meta: [M.Repo(r.repo), M.Agent(r.agent, { sm: true }), r.subtasks && r.kind === 'goal' ? SB.ProgressRing(r.subtasks.filter((s) => s[0] === 'done').length, r.subtasks.length) : '', r.build && r.state === 'active' ? M.Tag({ text: 'Building', tone: 'accent', icon: 'wrench' }) : '', kindLabel(r) ? M.Tag({ text: kindLabel(r), mono: true }) : ''],
    });
  }

  /* ---------- Tab roots ---------- */
  function needsScreen(st) {
    const items = needsYou(st).sort((a, b) => NEEDS_ORDER[a.needs] - NEEDS_ORDER[b.needs]);
    const all = runs(st);
    const running = all.filter((r) => r.state === 'active').length;
    const done = all.filter((r) => r.today).length;
    const nav = M.NavBar({ large: true, title: 'Needs you', sub: freshness(st), left: M.NavButton({ icon: 'monitor', label: 'Machines', act: 'sheet', arg: 'machines' }), actions: [M.NavButton({ icon: 'search', label: 'Search', act: 'toast', arg: 'Search filters the board by key, title or branch.' })] });
    const summary = M.Chips([M.Chip({ text: `${running} running`, lead: StatusIcon('active', 0.5), act: 'tab', arg: 'work' }), M.Chip({ text: `${done} finished today`, lead: StatusIcon('done'), act: 'tab', arg: 'work' })]);
    const body = items.length
      ? [M.Group({ title: 'Waiting on you', count: items.length, rows: items.map((r) => workRow(st, r, { badge: true })) })]
      : [M.Empty({ icon: 'checkCircle', title: 'Nothing needs you', body: 'Blocked, failed and paused work shows up here, along with Verify runs waiting for your criteria.' })];
    return { body: nav + connBanner(st) + M.Scroll({ tabs: true, pull: 'Pull to refresh', body: [summary, body] }) + tabBar(st) };
  }

  function workScreen(st) {
    const seg = M.Segmented({ act: 'work-seg', value: st.workSeg, options: [['active', 'Active'], ['history', 'History']] });
    const nav = M.NavBar({ large: true, title: 'Work', sub: freshness(st), actions: [M.NavButton({ icon: 'search', label: 'Filter', act: 'toast', arg: 'Filter by key, title or branch.' })], below: `<div class="mb-section-pad" style="padding-bottom:8px">${seg}</div>` });
    let body;
    if (st.workSeg === 'history') {
      const win = `<div class="mb-section-pad" style="padding-top:4px">${M.Segmented({ act: 'hist-win', value: st.histWin, options: [['6h', '6 hours'], ['24h', '24 hours'], ['7d', '7 days']] })}</div>`;
      const keep = st.histWin === '6h' ? ['Today'] : st.histWin === '24h' ? ['Today', 'Yesterday'] : ['Today', 'Yesterday', 'This week'];
      const groups = keep.map((day) => {
        const rows = D.HISTORY.filter((h) => h[6] === day).map(([state, key, title, repo, agent, time]) => M.WorkRow({ act: D.RUNS.find((r) => r.id === key) ? 'push' : 'toast', arg: D.RUNS.find((r) => r.id === key) ? 'detail:' + key : 'History keeps the run summary. Full detail opens for runs still in the store.', state, key, title, time, meta: [M.Repo(repo), M.Agent(agent, { sm: true })] }));
        return M.Group({ title: day, count: rows.length, rows });
      });
      body = [win, groups];
    } else {
      const all = runs(st);
      const live = (k) => all.filter((r) => r.kind === k && !r.today && r.state !== 'done');
      body = [
        M.Group({ title: 'Goal runs', count: live('goal').length, rows: live('goal').map((r) => workRow(st, r)) }),
        M.Group({ title: 'Standalone phases', count: live('phase').length, rows: live('phase').map((r) => workRow(st, r)) }),
        M.Group({ title: 'Operations', count: live('operation').length, rows: live('operation').map((r) => workRow(st, r)) }),
        M.Group({ title: 'Finished today', count: all.filter((r) => r.today).length, rows: all.filter((r) => r.today).map((r) => workRow(st, r)) }),
      ];
    }
    return { body: nav + connBanner(st) + M.Scroll({ tabs: true, body }) + tabBar(st) };
  }

  /* Chats: New task chats (start new work) and Runs (one chat per run, bound to its checkout), as on desktop. */
  const runChatFor = (st, runId) => allChats(st).find((c) => c.run === runId);
  function chatRow(st, c0) {
    const c = chatOf(st, c0.id);
    const last = c.messages.length ? c.messages[c.messages.length - 1][1].replace(/`/g, '') : 'No messages yet.';
    const r = c.run && runOf(st, c.run);
    return M.ChatRow({ act: 'push', arg: 'chat:' + c.id, agent: c.agent, title: c.title, time: c.time, last, status: c.status, changes: c.changes, repo: M.Repo(c.repo), run: r ? { key: keyOf(r), state: r.state, frac: r.frac } : null });
  }
  function chatsScreen(st) {
    const all = allChats(st);
    const nt = all.filter((c) => !c.run);
    const rc = all.filter((c) => c.run);
    const seg = M.Segmented({ act: 'chat-seg', value: st.chatSeg, options: [['new', 'New task', nt.length], ['runs', 'Runs', rc.length]] });
    const nav = M.NavBar({ large: true, title: 'Chats', sub: freshness(st), actions: [M.NavButton({ icon: 'search', label: 'Search chats', act: 'toast', arg: 'Search matches title, branch and agent.' })], below: `<div class="mb-section-pad" style="padding-bottom:8px">${seg}</div>` });
    const body = st.chatSeg === 'runs'
      ? [M.Group({ title: 'Run chats', count: rc.length, rows: rc.map((c) => chatRow(st, c)), foot: "Each run has one chat, opened from the run. It reads the run's records and works in the run's checkout." })]
      : [M.Group({ title: 'Recent', count: nt.length, rows: nt.map((c) => chatRow(st, c)) })];
    return { body: nav + connBanner(st) + M.Scroll({ tabs: true, body }) + (st.chatSeg === 'runs' ? '' : M.Fab({ label: 'New task', act: 'push', arg: 'newchat' })) + tabBar(st) };
  }

  function plansScreen(st) {
    const list = D.PLANS['skill-bill'];
    const count = (s) => list.filter((p) => p.status === s).length;
    const seg = M.Segmented({ act: 'plans-seg', value: st.plansSeg, options: [['all', 'All', list.length], ['active', 'Active', count('active')], ['pending', 'Pending', count('pending')], ['done', 'Done', count('done')]] });
    const repo = `<button type="button" class="mb-target" data-act="sheet" data-arg="plan-repo">${SB.RepoIcon(D.REPOS['skill-bill'])}<span>skill-bill</span>${Icon('chevronDown', 'xs')}</button>`;
    const nav = M.NavBar({ large: true, title: 'Plans', sub: repo, below: `<div class="mb-section-pad" style="padding-bottom:8px">${seg}</div>` });
    const shown = list.filter((p) => st.plansSeg === 'all' || p.status === st.plansSeg);
    return { body: nav + connBanner(st) + M.Scroll({ tabs: true, body: shown.map((p) => M.PlanRow(Object.assign({ act: 'push', arg: 'plan:' + p.key }, p))) }) + tabBar(st) };
  }

  function settingsScreen(st) {
    const nav = M.NavBar({ large: true, title: 'Settings' });
    const machines = M.Group({ inset: true, title: 'Machines', rows: [D.MACHINES.map((m) => M.MachineCard(Object.assign({}, m, { state: m.id === st.machine ? st.conn : m.state, current: m.id === st.machine, act: 'push', arg: 'machine:' + m.id }))), M.Row({ lead: Icon('plus'), title: '<span class="t-accent">Add machine</span>', act: 'onboard', arg: 'host' })], foot: 'Sona reads each machine over SSH when you open the app or pull to refresh. Work keeps running there.' });
    const usage = M.Group({ inset: true, title: 'Usage on studio', rows: D.USAGE.map((u) => M.UsageBlock(Object.assign({ isDefault: u.agent === st.defaultAgent }, u))) });
    const a = D.AGENTS[st.defaultAgent];
    const prefs = M.Group({ inset: true, title: 'Defaults', rows: [
      M.Row({ lead: Icon('bot'), title: 'Default agent', trail: `${M.Agent(a.name, { sm: true })}<span>${esc(a.name)}</span>`, chevron: true, act: 'sheet', arg: 'default-agent' }),
      M.Row({ lead: Icon('bell'), title: 'Notifications', trail: st.pushOn ? 'On' : 'Off', chevron: true, act: 'push', arg: 'notifications' }),
    ], foot: 'New chats and Run again start on the default agent.' });
    const about = M.Group({ inset: true, title: 'About', rows: [M.Row({ lead: Icon('phone'), title: 'Sona for mobile', trail: '1.0 (1)' }), M.Row({ lead: Icon('monitor'), title: 'Sona on studio', trail: '<span class="mono">0.42.0</span>' })], foot: 'Models, compaction, project and telemetry settings stay in the desktop app.' });
    return { body: nav + M.Scroll({ tabs: true, body: [machines, usage, prefs, about] }) + tabBar(st) };
  }

  /* ---------- Run detail ---------- */
  /* ---------- Run detail (desktop 9.2 plus the details pane), top to bottom in the desktop's order ---------- */
  const LIFE_COLOR = { active: 'var(--st-active)', paused: 'var(--st-paused)', blocked: 'var(--st-blocked)', failed: 'var(--st-failed)', done: 'var(--st-done)', canceled: 'var(--st-pending)' };
  const collapsed = (st, key, def) => (key in st.sec ? st.sec[key] : !!def);
  const sec = (st, key, o, def) => M.Section(Object.assign({}, o, { collapsed: collapsed(st, key, def), toggle: 'sec', arg: key + '|' + (def ? 1 : 0) }));
  const EXEC_STATE = { completed: 'done', failed: 'failed', interrupted: 'paused', paused: 'paused', blocked: 'blocked', canceled: 'canceled', active: 'active' };

  function detailHeader(st, r) {
    const chips = M.Chips2([
      M.StatePill({ state: r.state, frac: r.frac, text: r.needs === 'Confirm criteria' ? 'Awaiting confirmation' : null }),
      r.stalled ? M.Tag({ text: 'Stalled', tone: 'blocked' }) : '',
      `<span class="mono t-faint" style="font-size:11.5px">${esc(keyOf(r))}</span>`,
      r.issue && r.kind !== 'goal' ? `<span class="mono t-faint" style="font-size:11.5px">${esc(r.issue)}</span>` : '',
      M.Tag({ text: r.kind === 'goal' ? 'goal' : r.kind === 'phase' ? 'phase' : 'operation' }),
      r.state === 'done' ? '' : M.Tag({ text: st.conn === 'unreachable' ? 'stale' : 'fresh', tone: st.conn === 'unreachable' ? 'blocked' : null }),
    ]);
    const loc = `<div class="mb-loc"><button type="button" class="mb-loc-b" data-act="toast" data-arg="The checkout is on ${esc(machine(st).name)}: ${esc(r.path || '')}">${Icon('folder', 'xs')}<span>${esc(r.path || r.repo)}</span></button>${r.branch ? `<button type="button" class="mb-loc-b" data-act="toast" data-arg="Opens ${esc(r.branch)} on the repository's web host.">${Icon('branch', 'xs')}<span>${esc(r.branch)}</span></button>` : '<span class="t-faint">no branch</span>'}</div>`;
    return `<div class="mb-runhead">${chips}<h2 class="mb-runhead-title">${esc(r.title)}</h2>${loc}</div>`;
  }
  function metricsFor(r) {
    const m = r.metrics || {};
    const finished = ['done', 'failed', 'canceled'].includes(r.state);
    if (r.kind !== 'goal') return M.Metrics([{ label: r.kind === 'operation' ? 'Operation elapsed' : 'Phase elapsed', value: esc(r.elapsed), note: r.state === 'active' ? 'ticking' : 'last known duration' }, { label: 'Step', value: esc(r.phase), note: esc(r.step) }]);
    const done = r.subtasks.filter((x) => ['done', 'no_change'].includes(x[0])).length;
    return M.Metrics([
      m.stage ? { label: 'Goal stage', value: esc(m.stage) } : finished || r.current == null ? { label: 'Subtasks', value: String(done), unit: '/ ' + r.subtasks.length, note: 'complete' } : { label: 'Subtask', value: String(r.current + 1), unit: '/ ' + r.subtasks.length, note: `${done} complete · ${r.subtasks.length - done - 1} pending` },
      { label: 'Phase', value: `<span class="mono" style="font-size:15px">${esc(r.phase)}</span>`, note: m.exec || ' ' },
      finished ? { label: 'Total run time', value: esc(r.elapsed), note: 'finished ' + (r.finished || '') } : { label: 'Goal active', value: esc(r.elapsed), note: r.state === 'active' ? 'ticking' : 'last known duration' },
      !finished && m.subActive ? { label: 'Subtask active', value: esc(m.subActive), note: r.state === 'active' ? 'ticking' : 'last known duration' } : null,
    ]);
  }
  function incidentSection(st, r) {
    const inc = r.incident;
    const decision = r.awaitsDecision ? decisionBody(r) : '';
    if (!inc) return decision ? sec(st, 'decision', { title: 'Operator decision', icon: 'flag', body: decision }) : '';
    const recovered = !!st.runs[r.id] && st.runs[r.id].incident === null;
    const evOpen = !collapsed(st, 'evidence', true);
    const body = [
      M.Card({ tone: 'blocked', icon: 'alert', title: inc.heading, body: [M.KV([['Cause', esc(inc.cause)], ['Next', esc(inc.next)], ['Certainty', esc(inc.certainty)]])] }).replace('mb-card mb-card--blocked', 'mb-card mb-card--blocked mb-card--flush'),
      `<div class="mb-pad"><div class="mb-group-h" style="position:static;padding:0;height:28px">Prerequisites</div>${inc.prereqs.map(([kind, label]) => `<div class="mb-crit" style="padding:8px 0">${M.Checkbox({ on: !!st.recover[r.id], act: 'recover-check', arg: r.id })}<span class="mb-crit-t">${esc(label)}<br><span class="mono t-faint" style="font-size:11px">${esc(kind)}</span></span></div>`).join('')}</div>`,
      decision,
      `<button type="button" class="mb-sec-h" data-act="sec" data-arg="evidence|1" style="min-height:36px">${Icon(evOpen ? 'chevronDown' : 'chevronRight', 'xs')}<span class="t-faint" style="font-weight:500">Evidence · ${inc.evidence.length}</span></button>`,
      evOpen ? inc.evidence.map((e) => M.EvidenceRow(e)).join('') : '',
      `<div class="mb-pad t-muted" style="font-size:13px">Report: ${esc(inc.report)}</div>`,
    ];
    return sec(st, 'incident', { title: 'Incident', icon: 'alert', tone: 'blocked', end: `<span class="t-blocked">${esc(recovered ? 'Recovering' : inc.heading)}</span>`, body });
  }
  function decisionBody(r) {
    const cmd = `sona goal operator-decision ${r.id} --subtask ${r.current + 1} --decision retry_fix|accept_and_advance|abandon_subtask`;
    return `<div class="mb-pad">${M.Hint({ tone: null, icon: 'info', text: `Subtask ${r.current + 1} finished with no code changes and is waiting for your decision. Settle it in chat, or run this command on ${'the machine'} and then resume the goal. Pass --instructions with retry_fix.` }).replace('mb-hint"', 'mb-hint" style="margin:0"')}</div>
      <div class="mb-cmdline"><span>${esc(cmd)}</span><button type="button" aria-label="Copy decision command" data-act="copy" data-arg="${esc(cmd)}">${Icon('copy', 'xs')}</button></div>
      <div class="mb-pad" style="padding-top:0">${M.Button({ label: 'Decide in chat', icon: 'message', full: true, act: 'run-chat', arg: r.id })}</div>`;
  }
  function pipelineSection(st, r) {
    const list = r.finishing ? D.FINISH_PHASES.map((p) => [p, r.finish[p]]) : r.phases ? D.SUBTASK_PHASES.map((p) => [p, r.phases[p]]) : null;
    if (!list) return '';
    return sec(st, 'pipeline', { title: 'Phase pipeline', icon: 'workflow', end: r.finishing ? 'Goal finish' : 'subtask ' + (r.current + 1), body: `<div class="mb-pad">${M.PhaseStrip({ phases: list })}</div>` });
  }
  function changesSection(st, r) {
    if (!r.changes) return '';
    const log = r.log || [];
    const cols = r.subtasks.map(([s0, t], i) => {
      const g = log.find((x) => x.n === i + 1);
      const lines = g ? (g.state === 'active' ? 140 : 60 + i * 30) : 0;
      return { state: g ? g.state : 'pending', h: g ? Math.min(100, 20 + lines / 3) : 0, i, t };
    });
    const strip = `<div class="mb-strip" aria-label="Lines changed per subtask; a column opens that subtask">${cols.map((c) => `<button type="button" class="is-${c.state}" style="height:${c.h || 4}%" data-act="push" data-arg="subtask:${r.id}:${c.i}" aria-label="Subtask ${c.i + 1}"></button>`).join('')}</div>`;
    const cur = r.state === 'active' && !r.finishing && r.subtasks[r.current];
    return sec(st, 'changes', { title: 'Changes', icon: 'diff', count: `${r.changes.files} files · <span class="t-done">+${r.changes.add}</span> <span class="t-failed">−${r.changes.del}</span> · ${r.subtasks.length} subtasks`, body: [
      `<div class="mb-pad">${strip}</div>`,
      cur ? M.Row({ lead: StatusIcon('active', 0.5), title: `<span class="tnum t-faint">${String(r.current + 1).padStart(2, '0')}</span> ${esc(cur[1])}`, sub: 'Running subtask · 2 files · +76 −25', chevron: true, act: 'push', arg: 'subtask:' + r.id + ':' + r.current }) : '',
      `<div class="mb-pad">${M.Button({ label: 'Open changes', trail: 'chevronRight', full: true, act: 'push', arg: 'changes:' + r.id })}</div>`] });
  }
  function activitySection(st, r) {
    const parts = [];
    const log = r.log || [];
    if (log.length) {
      const blocksOnly = st.actFilter === 'blocks';
      const blocks = log.reduce((n, g) => n + (g.blocks || 0), 0);
      const fixes = (r.attempts && Object.values(r.attempts.fixIterations || {}).reduce((a, b) => a + b, 0)) || 0;
      const phases = log.reduce((n, g) => n + g.phases.length, 0);
      const allOpen = log.every((g) => !collapsed(st, 'act-' + g.n, !(g.running || g.blocks)));
      parts.push(`<div class="mb-sec-tools">${M.Segmented({ act: 'act-filter', value: blocksOnly ? 'blocks' : 'all', options: [['all', 'All'], ['blocks', 'Blocks', blocks]] })}<button type="button" class="mb-linkbtn" data-act="act-all" data-arg="${r.id}:${allOpen ? 'close' : 'open'}">${allOpen ? 'Collapse all' : 'Expand all'}</button></div>`);
      parts.push(`<div class="mb-pad" style="padding-top:6px">${M.Metrics([{ label: 'Phase time', value: esc(r.elapsed), note: 'no long gaps' }, { label: 'Blocks', value: String(blocks), note: blocks ? 'stopped for help' : 'none' }, { label: 'Subtasks', value: `${log.filter((g) => g.state === 'done').length}`, unit: '/ ' + r.subtasks.length, note: `${phases} phases · ${fixes} fix${fixes === 1 ? '' : 'es'}` }, { label: 'Models', value: `<span style="font-size:14px">${esc(r.model || '—')}</span>`, note: 'only model' }]).replace('mb-metrics"', 'mb-metrics" style="margin:0"')}</div>`);
      parts.push(`<div class="mb-pad" style="padding-top:0">${M.Timeline({ groups: log.map((g) => ({ state: g.state, weight: parseInt(g.dur, 10) || 1, act: 'act-open', arg: r.id + ':' + g.n })) })}<div class="mb-ex-s" style="margin-top:6px">${esc(r.started)} → ${['active'].includes(r.state) ? 'now' : esc(r.updated || '')}</div></div>`);
      parts.push(`<div class="mb-minirule">Today</div>`);
      log.filter((g) => !blocksOnly || g.blocks).forEach((g) => {
        const open = blocksOnly || !collapsed(st, 'act-' + g.n, !(g.running || g.blocks));
        const rows = [];
        let loop = [];
        const flush = () => {
          if (loop.length > 1) rows.push(M.Fold({ open: !collapsed(st, 'loop-' + g.n, false), act: 'sec', arg: 'loop-' + g.n + '|0', lead: '<i class="mb-dot mb-dot--wait"></i>', title: `${loop[0][1]} loop`, note: '', meta: `${loop.map((x) => x[1]).join(', ')} · ${loop[0][0]}`, body: loop.map(row) }));
          else loop.forEach((x) => rows.push(row(x)));
          loop = [];
        };
        const row = ([t, ph, tone, txt]) => M.EventLine({ ago: t, lead: `<i class="mb-dot mb-dot--${tone}"></i>`, html: tone === 'wait' && /blocked/.test(txt) ? `<b>Blocked</b> · <span class="mono">${esc(ph)}</span> · ${esc(txt.replace(/^blocked · /, ''))}` : `<span class="mono">${esc(ph)}</span> · ${esc(txt)} · ${M.Agent(g.agents[0], { sm: true })} <span class="mono t-faint">${esc(r.model || '')}</span>` });
        g.phases.forEach((x, i) => {
          const same = loop.length && loop[0][1] === x[1];
          if (!same) flush();
          if (blocksOnly && !(x[2] === 'wait' || x[2] === 'fail')) return;
          loop.push(x);
          if (i === g.phases.length - 1) flush();
        });
        flush();
        parts.push(M.Fold({ open, act: 'sec', arg: 'act-' + g.n + '|' + (g.running || g.blocks ? 0 : 1), lead: StatusIcon(g.state, 0.5), title: `${String(g.n).padStart(2, '0')}  ${g.title}`, note: g.running ? 'running' : g.blocks ? g.blocks + ' block' + (g.blocks > 1 ? 's' : '') : g.state === 'blocked' ? 'blocked' : '', meta: `${g.start} · ${g.dur} · ${g.agents.join(', ')}${g.sha ? ' · ' + g.sha : ''}`, body: rows.length ? rows : ['<div class="mb-pad t-faint">No blocks in this subtask.</div>'] }));
      });
      if (r.kind === 'goal' && !blocksOnly) {
        const ran = r.finish ? Object.entries(r.finish).filter(([, v]) => v !== 'pending') : [];
        parts.push(M.Fold({ open: !collapsed(st, 'act-finish', !r.finishing), act: 'sec', arg: 'act-finish|' + (r.finishing ? 0 : 1), lead: Icon('flag', 'sm'), title: '··  Goal finish', note: r.finishing ? 'running' : '', meta: ran.length ? `${ran.length} of ${D.FINISH_PHASES.length} phases` : 'Not started.', body: ran.length ? ran.map(([ph, v]) => M.EventLine({ ago: '', lead: `<i class="mb-dot mb-dot--${v === 'done' ? 'done' : 'now'}"></i>`, html: `<span class="mono">${ph}</span> · ${v === 'done' ? 'done' : 'running'}` })) : ['<div class="mb-pad t-faint">Not started.</div>'] }));
      }
      if (blocksOnly && !blocks) parts.push('<div class="mb-pad t-faint">No blocks in this run.</div>');
    }
    const ev = [];
    (r.executions || []).forEach((e, i) => {
      const who = (r.executions.length > 1 ? 'Run ' + (i + 1) : r.kind === 'goal' ? 'Goal run' : keyOf(r));
      ev.push({ ago: e.startAgo, lead: StatusIcon('active', 0.12), html: `<b>${esc(who)}</b> started` });
      if (e.end) ev.push({ ago: e.endAgo, lead: StatusIcon(EXEC_STATE[e.life] || 'pending', 1), html: `<b>${esc(who)}</b> ${esc(e.life)} <span class="mono">${esc(e.result || '')}</span>` });
    });
    const l = r.latest || {};
    if (l.edit) ev.push({ ago: l.edit.ago, lead: M.Agent(r.agent, { sm: true }), html: `<b>${esc(r.agent)}</b> edited <span class="mono">${esc(l.edit.path)} +${l.edit.ins} −${l.edit.del}</span>` });
    if (l.event) ev.push({ ago: l.event.ago, lead: StatusIcon(r.state, r.frac), html: `<b>${esc(l.event.phase)} · ${esc(l.event.role)}</b> ${esc(l.event.summary)}` });
    if (l.activity) ev.push({ ago: l.activity.ago, lead: M.Agent(r.agent, { sm: true }), html: `<b>${esc(r.agent)}</b> ${esc(l.activity.label)}` });
    if (!ev.length && r.activity) r.activity.forEach(([t, ph, txt]) => ev.push({ ago: t, lead: '<i class="mb-dot"></i>', html: `<b>${esc(ph)}</b> ${esc(txt)}` }));
    if (ev.length && st.actFilter !== 'blocks') {
      const all = !!st.sec['ev-all-' + r.id];
      const rows = ev.reverse().slice(0, all ? ev.length : 8).map((e) => M.EventLine(e)).join('') + (ev.length > 8 && !all ? `<div class="mb-pad"><button type="button" class="mb-linkbtn" data-act="sec" data-arg="ev-all-${r.id}|0">Show ${ev.length - 8} earlier</button></div>` : '');
      parts.push(log.length ? M.Fold({ open: !collapsed(st, 'goal-activity', false), act: 'sec', arg: 'goal-activity|0', lead: Icon('history', 'sm'), title: r.kind === 'goal' ? 'Goal activity' : 'Activity', note: '', meta: ev.length + ' events', body: rows }) : rows);
    }
    return parts.length ? sec(st, 'activity', { title: 'Activity', icon: 'history', count: log.length ? log.reduce((n, g) => n + g.phases.length, 0) + ' phases' : ev.length, body: parts }) : '';
  }
  function executionSection(st, r) {
    const id = (v, label) => `<span class="l-row g-2"><span class="mono">${esc(v.length > 9 ? v.slice(0, 8) + '…' : v)}</span><button type="button" class="mb-prop-link" data-act="copy" data-arg="${esc(v)}">Copy ${esc(label)}</button></span>`;
    const rows = [
      r.workflow ? ['Workflow', id(r.workflow, 'id')] : null,
      r.invocation ? ['Invocation', id(r.invocation, 'id')] : null,
      ['Agent', `<span class="l-row g-1">${M.Agent(r.agent, { sm: true })}<span>${esc(r.agent)}</span></span>`],
      r.model ? ['Model', esc(r.model + (r.effort ? ' · ' + r.effort : ''))] : null,
      r.liveness ? ['Liveness', esc(r.liveness)] : null,
      ['Started', esc(r.started)],
      r.updated ? ['Updated', esc(r.updated)] : null,
    ].filter(Boolean);
    return sec(st, 'execution', { title: 'Execution', icon: 'cpu', body: `<div class="mb-pad" style="padding-top:2px;padding-bottom:2px">${M.KV(rows)}</div>` });
  }
  function runsSection(st, r) {
    const a = r.attempts;
    if (!a) return '';
    const bd = (label, key, map) => (Object.values(map || {}).reduce((x, y) => x + y, 0) ? M.Breakdown({ label, map, act: 'bd', arg: r.id + ':' + key, open: !!st.bd[r.id + ':' + key] }) : '');
    const rows = [bd('Runs', 'runs', a.phaseRuns), bd('Fixes', 'fixes', a.fixIterations), bd('Relaunches', 'relaunches', a.relaunches),
      a.findings ? `<div class="mb-bd" style="cursor:default"><span class="mb-bd-l">Findings</span><span class="mb-bd-v tnum">${a.findings}</span><span class="t-faint" style="font-size:11.5px">in scope</span></div>` : '',
      a.kills ? `<div class="mb-bd" style="cursor:default"><span class="mb-bd-l">Kills</span><span class="mb-bd-v tnum">${a.kills}</span><span class="t-faint" style="font-size:11.5px">supervisor</span></div>` : ''].join('');
    return rows ? sec(st, 'runs', { title: 'Runs and fixes', icon: 'loop', body: rows }) : '';
  }
  function subtasksSection(st, r) {
    if (!r.subtasks) return '';
    const done = r.subtasks.filter((x) => ['done', 'no_change'].includes(x[0])).length;
    const peek = D.hasPeek(r.id);
    const shas = (r.log || []).reduce((m, g) => Object.assign(m, { [g.n - 1]: g.sha }), {});
    const rows = r.subtasks.map(([s0, t], i) => M.SubtaskRow({ state: s0, n: i + 1, title: t, sha: shas[i], note: s0 === 'no_change' ? 'no change' : i === r.current && r.state === 'active' && !r.finishing && r.metrics ? r.metrics.subActive : null, current: i === r.current && !r.finishing && r.state !== 'paused', phase: r.phase, act: peek ? 'push' : null, arg: 'subtask:' + r.id + ':' + i }));
    if (r.kind === 'goal') {
      const ran = r.finish ? Object.values(r.finish).filter((x) => x !== 'pending').length : 0;
      rows.push(M.SubtaskRow({ finish: true, title: 'Goal finish', note: r.finishing ? `${r.phase} · ${ran}/${D.FINISH_PHASES.length}` : `${ran}/${D.FINISH_PHASES.length}`, act: peek ? 'push' : null, arg: 'subtask:' + r.id + ':finish' }));
    }
    return sec(st, 'subtasks', { title: 'Subtasks', icon: 'list', count: `${done}/${r.subtasks.length}`, body: rows }, r.subtasks.length > 10);
  }
  function detailScreen(st, id) {
    const r = runOf(st, id);
    const backLabel = { needs: 'Needs you', work: 'Work', chats: 'Chats', plans: 'Plans', settings: 'Settings' }[st.tab];
    const live = runs(st).filter((x) => !x.today && x.state !== 'done');
    const idx = live.findIndex((x) => x.id === r.id);
    const pager = idx >= 0 ? M.Pager({ compact: true, act: 'detail-go', index: idx, total: live.length, prev: idx > 0 ? live[idx - 1].id : null, next: idx < live.length - 1 ? live[idx + 1].id : null }) : '';
    const rc = runChatFor(st, r.id);
    const rcLive = rc && ['replying', 'waiting'].includes(chatOf(st, rc.id).status);
    const nav = M.NavBar({ back: backLabel, title: keyOf(r), actions: [pager, M.NavButton({ icon: 'message', label: 'Run chat', act: 'run-chat', arg: r.id, dot: rcLive }), M.NavButton({ icon: 'more', label: 'More', act: 'sheet', arg: 'actions:' + r.id })] });
    const doc = window.MSK.doc(st, r, { sec, collapsed, detailHeader, metricsFor, activitySection, executionSection, runsSection });
    const parts = doc ? [doc] : [
      detailHeader(st, r),
      window.MSK.runApp(r),
      r.build && r.state === 'active' ? `<div class="mb-section-pad" style="margin-top:4px">${M.BuildChip(r.build)}</div>` : '',
      r.pauseRequested ? `<div style="margin-top:12px">${M.Hint({ icon: 'pause', text: 'Pause recorded. The goal pauses at its next checkpoint.' })}</div>` : '',
      r.reason ? M.Reason({ label: 'Run reason', text: r.reason, color: LIFE_COLOR[r.state], open: !!st.sec['reason-' + r.id], long: r.reason.length > 120, toggle: 'sec', arg: 'reason-' + r.id + '|0' }) : '',
      incidentSection(st, r),
      metricsFor(r),
      subtasksSection(st, r),
      r.kind === 'goal' ? pipelineSection(st, r) : '',
      r.kind === 'goal' ? changesSection(st, r) : '',
      activitySection(st, r),
      executionSection(st, r),
      runsSection(st, r),
    ];
    parts.push('<div class="mb-gap"></div>');
    return { body: nav + connBanner(st) + M.Scroll({ bar: true, body: parts }) + actionBar(st, r) };
  }

  /* The one primary action the runtime allows for this state, then More. */
  function actionBar(st, r) {
    const more = M.Button({ icon: 'more', act: 'sheet', arg: 'actions:' + r.id, label: '' });
    if (st.conn === 'unreachable') return M.ActionBar({ note: 'Refresh the detail before using run controls.', buttons: [M.Button({ label: 'Refresh', icon: 'refresh', variant: 'primary', act: 'retry' })] });
    if (r.criteria && r.state === 'paused') {
      const chk = st.criteria[r.id] || {};
      const kept = r.criteria.filter((c) => chk[c[0]] !== false).length;
      return M.ActionBar({ buttons: [M.Button({ label: `Confirm ${kept} criteria`, variant: 'primary', act: 'confirm-criteria', arg: r.id, disabled: !kept }), more] });
    }
    if (r.state === 'active' && r.kind === 'goal') return M.ActionBar({ buttons: [M.Button({ label: r.pauseRequested ? 'Pause requested' : 'Pause after subtask', icon: 'pause', act: 'pause', arg: r.id, disabled: r.pauseRequested }), more] });
    if (r.state === 'active') return M.ActionBar({ buttons: [M.Button({ label: 'Cancel ' + (r.phaseName || 'run'), variant: 'danger', act: 'sheet', arg: 'cancel:' + r.id }), more] });
    const harness = (r.runOptions || {}).canLaunch ? 'Starts the installed agent harness on ' + machine(st).name : null;
    if (r.state === 'paused') return M.ActionBar({ note: harness, buttons: [M.Button({ label: (r.runOptions || {}).launchLabel || 'Resume', icon: 'play', variant: 'primary', act: 'sheet', arg: 'resume:' + r.id }), more] });
    if (r.state === 'blocked') return M.ActionBar({ buttons: [M.Button({ label: 'Recover', icon: 'wrench', variant: 'primary', act: 'sheet', arg: 'recover:' + r.id }), more] });
    if (r.state === 'failed') return M.ActionBar({ note: harness, buttons: [M.Button({ label: 'Run again', icon: 'rerun', variant: 'primary', act: 'sheet', arg: 'resume:' + r.id }), more] });
    if (r.state === 'done' && r.kind !== 'goal' && (r.runOptions || {}).canLaunch) return M.ActionBar({ note: harness, buttons: [M.Button({ label: r.runOptions.launchLabel || 'Run again', icon: 'rerun', variant: 'primary', act: 'sheet', arg: 'resume:' + r.id }), more] });
    if (r.state === 'done' && r.kind === 'goal') return M.ActionBar({ buttons: [(r.runOptions || {}).canFinalize ? M.Button({ label: 'Finalize', icon: 'checkCircle', variant: 'primary', act: 'finalize', arg: r.id }) : M.Button({ label: 'Open PR #611', icon: 'external', act: 'toast', arg: 'Opens the pull request in the browser.' }), more] });
    return '';
  }

  /* ---------- Subtask (desktop: selected row in the details panel) ---------- */
  function subtaskScreen(st, arg) {
    const [id, idx] = arg.split(':');
    const r = runOf(st, id);
    const finish = idx === 'finish';
    const s = finish ? D.finish(id) : D.subtask(id, Number(idx));
    const key = id + ':' + idx;
    const ranPhases = s.phases.filter((p) => p[1] !== 'pending');
    const stopped = ranPhases.find((p) => ['active', 'blocked', 'failed'].includes(p[1]));
    const sel = st.peek[key] || (stopped ? stopped[0] : ranPhases.length ? ranPhases[ranPhases.length - 1][0] : null);
    const n = r.subtasks.length;
    const i = finish ? n : Number(idx);
    const go = (j) => (j < 0 || j > n ? null : id + ':' + (j === n ? 'finish' : j));
    const pager = M.Pager({ act: 'subtask-go', index: i, total: n + 1, prev: go(i - 1), next: go(i + 1) });
    const nav = M.NavBar({ back: keyOf(r), title: finish ? 'Goal finish' : `Subtask ${s.n} of ${s.total}`, actions: [pager] });
    const prop = ([k, v]) => {
      if (v == null) return [k, '<span class="mb-prop-none">None</span>'];
      if (v.sha) return [k, `<span class="l-row g-2"><span class="mono">${esc(v.sha)}</span><button type="button" class="mb-prop-link" data-act="copy" data-arg="${esc(v.sha)}">Copy</button></span>`];
      if (v.file) return [k, `<button type="button" class="mb-prop-link mono" data-act="push" data-arg="planfile:${esc(id)}:${esc(v.file)}">${esc(v.file)}</button>`];
      return [k, esc(v)];
    };
    const parts = [
      M.RunHeader({ state: s.state === 'pending' ? 'pending' : s.state, frac: 0.5, key: finish ? id : `${id} · ${s.n}`, title: s.title, meta: finish ? [M.Branch(r.branch)] : [] }),
      s.reason ? M.Hint({ tone: s.state, text: s.reason }) : '',
      M.Card({ title: finish ? 'Goal finish' : 'Subtask', icon: finish ? 'flag' : 'list', body: [`<div class="mb-pad" style="padding-top:2px;padding-bottom:2px">${M.KV(s.props.map(prop))}</div>`] }),
      `<div class="mb-group-h" style="position:static;margin-top:10px">Phases<span class="mb-group-end">${ranPhases.length} of ${s.phases.length} ran</span></div>`,
      M.PhaseTabs({ phases: s.phases, value: sel, act: 'peek', arg: key }),
      sel ? M.PhaseCard(Object.assign({ id: sel }, s.cards[sel])) : `<div class="mb-pk-empty">No phase has run yet.<br>${finish ? 'Goal finish starts after the last subtask commits.' : `Subtask ${s.n} starts ${r.state === 'paused' ? 'when the goal resumes' : 'after the subtasks it depends on'}.`}</div>`,
      '<div class="mb-gap"></div>',
    ];
    return { body: nav + connBanner(st) + M.Scroll({ body: parts }) };
  }

  /* ---------- Changes ---------- */
  function changesScreen(st, id) {
    const r = runOf(st, id);
    const nav = M.NavBar({ back: keyOf(r), title: 'Changes', sub: `${r.changes.files} files · <span class="t-done">+${r.changes.add}</span> <span class="t-failed">−${r.changes.del}</span>` });
    const scopes = r.subtasks ? [['run', 'Whole run'], ['current', 'Subtask ' + (r.current + 1)], ['commit', 'Last commit']] : [['run', 'Whole run']];
    const seg = `<div class="mb-section-pad" style="padding:10px 16px">${M.Segmented({ act: 'scope', value: st.scope, options: scopes })}</div>`;
    const files = D.FILES.filter((f, i) => st.scope === 'run' || (st.scope === 'current' ? i < 2 : i === 3));
    const rows = files.map((f) => M.FileRow({ status: f[0], name: f[1], dir: f[2], add: f[3], del: f[4], act: 'push', arg: 'diff:' + id + ':' + D.FILES.indexOf(f) }));
    const note = M.Group({ rows: [], foot: `${Icon('eye', 'xs')} Read-only. Comment on the desktop, where Add review publishes to the run.` });
    return { body: nav + M.Scroll({ body: [seg, rows, note] }) };
  }
  function diffScreen(st, arg) {
    const [id, idx] = arg.split(':');
    const i = Number(idx);
    const f = D.FILES[i];
    const pager = M.Pager({ act: 'diff-go', index: i, total: D.FILES.length, prev: i > 0 ? id + ':' + (i - 1) : null, next: i < D.FILES.length - 1 ? id + ':' + (i + 1) : null });
    const nav = M.NavBar({ back: 'Changes', title: f[1], sub: `<span class="t-done">+${f[3]}</span> <span class="t-failed">−${f[4]}</span>`, actions: [pager] });
    const head = `<div class="mb-section-pad mono t-faint" style="font-size:11px;padding:10px 16px">${esc(f[2])}/${esc(f[1])}</div>`;
    const lines = i === 0 ? D.DIFF : D.DIFF.slice(0, 11);
    return { body: nav + M.Scroll({ body: [head, M.Diff({ lines, thread: i === 0 ? D.THREAD : null }), M.Group({ rows: [], foot: 'Long lines scroll sideways inside the diff. Line numbers stay put.' })] }) };
  }

  /* ---------- Chats ---------- */
  function chatScreen(st, id) {
    const c = chatOf(st, id);
    const stack = st.stacks[st.tab];
    const prev = stack[stack.length - 2];
    const back = prev && prev.startsWith('detail:') ? keyOf(runOf(st, prev.split(':')[1])) : st.tab === 'chats' ? 'Chats' : 'Back';
    const r = c.run && runOf(st, c.run);
    const actions = [c.changes ? M.NavButton({ icon: 'diff', label: 'Chat changes', act: 'sheet', arg: 'chat-changes:' + id }) : '', r ? M.NavButton({ icon: 'layers', label: 'Open run', act: 'open-run', arg: r.id }) : ''];
    const nav = M.NavBar({ back, title: r ? keyOf(r) : c.title, sub: r ? 'Run chat' : `${esc(c.repo)} · <span class="mono">${esc(c.branch)}</span>`, actions });
    const head = r ? M.RunContext({ act: 'open-run', arg: r.id, state: r.state, frac: r.frac, key: keyOf(r), title: r.title, path: r.path || r.repo, branch: r.branch }) : '';
    const msgs = c.messages.length ? c.messages.map(([role, text]) => M.Message({ role, text, agent: c.agent })) : [M.Message({ role: 'agent', agent: c.agent, text: D.runChatIntro(r, keyOf(r)) })];
    const tail = [];
    if (c.proposal) tail.push(M.ProposalCard(proposalModel(st, c, r)));
    if (c.decision) tail.push(M.DecisionCard({ chat: id, run: r.id, subtask: c.decision.subtask, state: c.decision.state, act: 'decision' }));
    if (c.start) tail.push(M.StartCard(Object.assign({ chat: id, accepted: c.accepted, started: c.started }, c.start)));
    if (c.status === 'replying') tail.push(M.Typing(c.agent));
    if (c.suggestions && c.status !== 'replying') tail.push(M.Suggestions({ items: c.suggestions, act: 'suggest' }));
    const composer = M.Composer({ chat: id, repo: c.repo, branch: c.branch, agent: c.agent, replying: c.status === 'replying', queued: c.queued || 0, draft: c.draft, locked: !!r });
    return { body: nav + connBanner(st) + M.Scroll({ body: [head, msgs, tail] }) + composer };
  }
  /* The parked Verify's proposal: criteria from the run, as unticked or kept on its detail. */
  function proposalModel(st, c, r) {
    const chk = st.criteria[r.id] || {};
    const crit = (D.RUNS.find((x) => x.id === r.id).criteria || []);
    const p = c.proposal;
    const items = crit.map(([ref, text]) => [ref, text, chk[ref] === false]);
    if (p.revised) items.push(['AC-006', 'The export writes a mode per theme (dark only today).', false]);
    const kept = items.filter((x) => !x[2]).length;
    return { chat: c.id, act: 'proposal', title: p.title, state: p.state, revised: p.revised, items, note: p.state === 'open' || p.state === 'adjusting' ? `${kept} criteria will be audited. Mode inline, QA off.` : null };
  }
  function newChatScreen(st) {
    const n = st.newChat;
    const nav = M.NavBar({ back: 'Chats', title: 'New task' });
    const hero = M.Hero({ title: 'What should run on studio?', body: 'Describe the work. The agent asks what it needs, then offers a start card.' });
    const quick = `<div class="mb-group-h" style="position:static">Quick starts</div>` + M.Chips(D.QUICK_STARTS.map(([icon, label]) => M.Chip({ icon, text: label, act: 'quick', arg: label })));
    return { body: nav + M.Scroll({ body: [hero, quick] }) + M.Composer({ chat: 'new', repo: n.repo, branch: n.branch, agent: n.agent, draft: n.draft }) };
  }

  /* ---------- Plans ---------- */
  function planScreen(st, key) {
    const p = D.PLANS['skill-bill'].find((x) => x.key === key);
    const nav = M.NavBar({ back: 'Plans', title: p.key, actions: [M.NavButton({ icon: 'star', label: 'Favorite', act: 'toast', arg: 'Favorites sync with the desktop Plans pane.' })] });
    const head = M.RunHeader({ state: p.status === 'done' ? 'done' : p.status === 'active' ? 'active' : 'pending', frac: p.n / p.m, key: p.key, kind: 'spec', title: p.title, meta: [M.Repo('skill-bill'), `<span>${p.n} of ${p.m} subtasks</span>`] });
    const files = M.Group({ title: 'Bundle', count: D.PLAN_FILES.length, rows: D.PLAN_FILES.map(([icon, name, state]) => M.Row({ lead: state ? StatusIcon(state, 0.5) : Icon(icon), title: `<span class="${state ? 'mono' : ''}" style="${state ? 'font-size:13px' : ''}">${esc(name)}</span>`, chevron: true, act: 'push', arg: 'planfile:' + key + ':' + name })) });
    let bar;
    if (p.status === 'active') bar = M.ActionBar({ buttons: [M.Button({ label: 'Open run', icon: 'arrowRight', act: 'push', arg: 'detail:' + p.run }), M.Button({ icon: 'pause', label: 'Pause', act: 'pause', arg: p.run })] });
    else if (p.status === 'pending') bar = M.ActionBar({ note: `Starts a goal on studio with ${esc(st.defaultAgent)}.`, buttons: [M.Button({ label: 'Start', icon: 'play', variant: 'primary', act: 'sheet', arg: 'start-plan:' + key }), M.Button({ icon: 'more', label: '', act: 'sheet', arg: 'agent:plan' })] });
    else bar = M.ActionBar({ buttons: [M.Button({ label: 'Run again', icon: 'rerun', act: 'sheet', arg: 'start-plan:' + key })] });
    return { body: nav + M.Scroll({ bar: true, body: [head, files] }) + bar };
  }
  function planFileScreen(st, arg) {
    const name = arg.split(':')[1];
    const nav = M.NavBar({ back: arg.split(':')[0], title: name, actions: [M.NavButton({ icon: 'copy', label: 'Copy', act: 'toast', arg: 'Copied the file contents.' })] });
    const blocks = name === 'spec.md' ? D.SPEC_DOC : [['h1', name.replace(/^\d+-|\.md$/g, '').replace(/-/g, ' ')], ['p', 'Subtask spec. Read-only on mobile.'], ['h2', 'Acceptance criteria'], ['li', 'Finalize removes the worktree only when every commit is pushed.'], ['li', 'A kept worktree emits WorktreeKept with its reason.']];
    return { body: nav + M.Scroll({ body: M.Doc(blocks) }) };
  }

  /* ---------- Settings pages ---------- */
  function machineScreen(st, id) {
    const m = D.MACHINES.find((x) => x.id === id);
    const current = id === st.machine;
    const state = current ? st.conn : m.state;
    const nav = M.NavBar({ back: 'Settings', title: m.name });
    const conn = M.Group({ inset: true, title: 'Connection', rows: [M.MachineCard(Object.assign({}, m, { state, current }))] });
    const ssh = M.Group({ inset: true, title: 'SSH', rows: [`<div class="mb-pad">${M.KV([['Host', `<span class="mono">${esc(m.host)}</span>`], ['Port', String(m.port)], ['User', `<span class="mono">${esc(m.user)}</span>`], ['Sona', `<span class="mono">${esc(m.sona)}</span>`]])}</div>`] });
    const fp = M.Group({ inset: true, title: 'Trusted host key', rows: [`<div class="mb-pad">${M.Fingerprint({ value: m.fingerprint })}</div>`], foot: 'Sona refuses to connect if the machine presents a different key.' });
    const key = M.Group({ inset: true, title: "This phone's key", rows: [`<div class="mb-pad">${M.Code({ text: D.PUBLIC_KEY, copy: D.PUBLIC_KEY })}</div>`, M.Row({ lead: Icon('upload'), title: 'Share public key', act: 'toast', arg: 'Opens the share sheet with the public key.' })], foot: 'The private key stays in the phone keychain and never leaves it.' });
    const push = M.Group({ inset: true, title: 'Push', rows: [M.Row({ lead: Icon('bell'), title: m.push === 'on' ? 'Registered' : 'Not registered', sub: m.push === 'on' ? 'Last push 2h ago · Desktop app running' : 'Pushes start once the desktop app on this machine is running', trail: '' })] });
    const acts = M.Group({ inset: true, rows: [current ? M.Row({ lead: Icon('refresh'), title: 'Refresh now', act: 'refresh' }) : M.Row({ lead: Icon('check'), title: 'Make current', act: 'make-current', arg: id }), M.Row({ lead: Icon('trash'), title: 'Remove machine', danger: true, act: 'sheet', arg: 'remove-machine:' + id })] });
    return { body: nav + M.Scroll({ body: [conn, ssh, fp, key, push, acts] }) };
  }
  function notificationsScreen(st) {
    const nav = M.NavBar({ back: 'Settings', title: 'Notifications' });
    const allow = M.Group({ inset: true, rows: [M.Row({ lead: Icon('bell'), title: 'Push notifications', trail: M.Toggle({ on: st.pushOn, act: 'push-on' }) })], foot: 'The Sona desktop app on studio sends these through the Sona push relay and Firebase. They stop while the desktop app is not running.' });
    const types = M.Group({ inset: true, title: 'Notify me when', rows: D.NOTIFICATIONS.map(([id, label]) => M.Row({ title: esc(label), dim: !st.pushOn, trail: M.Toggle({ on: st.notif[id], act: 'notif', arg: id, disabled: !st.pushOn }) })) });
    const privacy = M.Group({ inset: true, title: 'Privacy', rows: [M.Row({ title: 'Show run titles', sub: 'Off sends only the kind of event, such as "A goal needs you".', dim: !st.pushOn, trail: M.Toggle({ on: st.pushTitles, act: 'push-titles', disabled: !st.pushOn }) })], foot: 'Push text passes through Google. Code, diffs and transcripts never do; the app reads them over SSH when you open it.' });
    const preview = `<div class="mb-section-pad" style="margin-top:18px"><div class="mb-group-h" style="position:static;padding:0 4px;height:30px">Preview</div>${M.Push(pushFor(st, 0))}</div>`;
    return { body: nav + M.Scroll({ body: [allow, types, privacy, preview] }) };
  }
  const PUSHES = [
    { full: ['Blocked · INF-7', 'Rotate staging TLS certificates · state lock held on staging.tfstate'], generic: ['A goal needs you', 'Open Sona to see what happened on studio.'], time: 'now' },
    { full: ['Confirm criteria · DSN-31', 'Verify proposed 5 criteria for Token export for Figma'], generic: ['A Verify run is waiting', 'Open Sona to confirm its criteria.'], time: '6m ago' },
    { full: ['Completed · SKILL-498', 'Floating details over terminal · PR #611 opened'], generic: ['A goal completed', 'Open Sona for the result.'], time: '2h ago' },
  ];
  function pushFor(st, i) { const p = PUSHES[i]; const [title, body] = st.pushTitles ? p.full : p.generic; return { title, body, time: p.time }; }

  /* ---------- Pairing ---------- */
  function onboarding(st) {
    const o = st.onboard;
    const m = D.MACHINES[0];
    const scroll = (b) => M.Scroll({ pad: true, body: b });
    if (o === 'welcome') {
      return { body: scroll([M.Hero({ title: 'Sona on your phone', body: 'See what needs you and steer the work running on your computer. Goals never run on the phone.' }),
        M.Group({ inset: true, rows: [M.Row({ lead: Icon('inbox'), title: 'See what needs you', sub: 'Blocked, failed, paused and waiting runs' }), M.Row({ lead: Icon('pause'), title: 'Pause, resume and recover', sub: 'The same controls as the desktop' }), M.Row({ lead: Icon('message'), title: 'Chat and start work', sub: 'Runs start on your computer' })] })])
        + M.ActionBar({ note: 'Connects over SSH. Nothing to install on the phone side.', buttons: [M.Button({ label: 'Add a machine', variant: 'primary', act: 'onboard', arg: 'host' })] }) };
    }
    if (o === 'host') {
      return { body: M.NavBar({ back: 'Back', title: 'Add machine' }) + M.Steps(1, 4) + scroll(`<div class="mb-stack" style="padding-top:12px">${[
        M.Field({ label: 'Name', value: 'studio' }),
        M.Field({ label: 'Host', value: m.host, mono: true, help: 'Hostname, IP address or Tailscale name. The phone must be able to reach it.' }),
        M.Field({ label: 'Port', value: '22', mono: true }),
        M.Field({ label: 'User', value: m.user, mono: true, help: 'The account Sona is installed under.' }),
      ].join('')}</div>`) + M.ActionBar({ buttons: [M.Button({ label: 'Continue', variant: 'primary', act: 'onboard', arg: 'key' })] }) };
    }
    if (o === 'key') {
      return { body: M.NavBar({ back: 'Back', title: 'Phone key' }) + M.Steps(2, 4) + scroll(`<div class="mb-stack" style="padding-top:12px">${[
        `<h2 class="mb-runhead-title">Add this phone's key to studio</h2>`,
        M.Para('Sona made an ed25519 key for this phone. The private key stays in the phone keychain.', { muted: true }),
        M.Code({ text: D.PUBLIC_KEY, copy: D.PUBLIC_KEY }),
        M.Para('On studio, append it to the user\'s authorized keys:', { muted: true }),
        M.Code({ text: "echo '<public key>' >> ~/.ssh/authorized_keys", copy: 'cmd' }),
        M.Button({ label: 'Share public key', icon: 'upload', full: true, act: 'toast', arg: 'Opens the share sheet, for AirDrop, Messages or email to yourself.' }),
        M.Button({ label: 'Use an existing key instead', variant: 'ghost', full: true, act: 'toast', arg: 'Imports an OpenSSH private key from Files.' }),
      ].join('')}</div>`) + M.ActionBar({ buttons: [M.Button({ label: 'I added it', variant: 'primary', act: 'onboard', arg: 'trust' })] }) };
    }
    if (o === 'trust') {
      return { body: M.NavBar({ back: 'Back', title: 'Host key' }) + M.Steps(3, 4) + scroll(`<div class="mb-stack" style="padding-top:12px">${[
        `<h2 class="mb-runhead-title">Is this studio?</h2>`,
        M.Para('First connection. studio presented this host key. Compare it with the machine before you trust it.', { muted: true }),
        M.Fingerprint({ value: m.fingerprint }),
        M.Para('On studio, run:', { muted: true }),
        M.Code({ text: 'ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub', copy: 'cmd' }),
      ].join('')}</div>`) + M.ActionBar({ buttons: [M.Button({ label: 'Trust and connect', variant: 'primary', act: 'onboard', arg: 'check' }), M.Button({ label: 'Cancel', act: 'onboard', arg: 'host' })] }) };
    }
    if (o === 'check' || o === 'check-done' || o === 'check-version') {
      const done = o !== 'check';
      const bad = o === 'check-version';
      const steps = [
        M.CheckStep({ state: 'done', text: 'Connected over SSH', note: `<span class="mono">${esc(m.user)}@studio</span>` }),
        M.CheckStep({ state: 'done', text: 'Found Sona', note: `<span class="mono">${bad ? '0.38.1' : '0.42.0'}</span>` }),
        M.CheckStep({ state: bad ? 'failed' : done ? 'done' : 'active', text: bad ? 'Needs Sona 0.40.0 or newer' : 'Runtime is compatible' }),
        M.CheckStep({ state: bad ? 'pending' : done ? 'done' : 'pending', text: 'Read the board', note: done && !bad ? '6 runs' : '' }),
      ];
      const bar = bad
        ? M.ActionBar({ note: 'Update Sona on studio with ./install.sh, then check again.', buttons: [M.Button({ label: 'Check again', variant: 'primary', act: 'onboard', arg: 'check-done' })] })
        : M.ActionBar({ buttons: [M.Button({ label: done ? 'Continue' : 'Checking…', variant: 'primary', act: 'onboard', arg: 'notify', disabled: !done })] });
      return { body: M.NavBar({ title: 'Connect' }) + M.Steps(4, 4) + scroll([`<h2 class="mb-runhead-title" style="padding:12px 0 6px">${bad ? 'Update Sona on studio' : done ? 'studio is ready' : 'Connecting to studio'}</h2>`, M.Group({ inset: true, rows: steps })]) + bar };
    }
    if (o === 'notify' || o === 'notify-ask') {
      const body = M.Scroll({ pad: true, body: [M.Hero({ icon: 'bell', title: 'Get a push when work needs you', body: 'The desktop app on studio sends a push when a goal blocks, fails, pauses or completes. Open Sona to see the rest.' }), M.Push(pushFor(st, 0))] })
        + `<div class="mb-actionbar"><div class="l-col g-2">${M.Button({ label: 'Turn on notifications', variant: 'primary', full: true, act: 'onboard', arg: 'notify-ask' })}${M.Button({ label: 'Not now', variant: 'ghost', full: true, act: 'onboard-finish', arg: 'off' })}</div></div>`;
      const over = o === 'notify-ask' ? M.SystemAlert({ title: '"Sona" Would Like to Send You Notifications', body: 'Notifications may include alerts, sounds and icon badges.', actions: [{ label: "Don't Allow", act: 'onboard-finish', arg: 'off' }, { label: 'Allow', act: 'onboard-finish', arg: 'on', bold: true }] }) : '';
      return { body, over };
    }
    return { body: '' };
  }

  /* ---------- Connection failures that stop every read ---------- */
  function blocked(st) {
    const m = machine(st);
    const nav = M.NavBar({ title: m.name, sub: m.user + '@' + m.host, left: M.NavButton({ icon: 'monitor', label: 'Machines', act: 'sheet', arg: 'machines' }) });
    const pages = {
      hostkey: [M.Hero({ icon: 'alert', title: "studio's host key changed", body: "This happens after a reinstall, or when someone is intercepting the connection. Sona won't connect until you check it." }),
        `<div class="mb-stack">${M.Fingerprint({ label: 'Trusted', value: m.fingerprint })}${M.Fingerprint({ label: 'Presented now', value: 'SHA256:Vd0kT2hQe8LmZp1sW4xC7nB3yR6uJ9aF5gK0iO2lPtE', tone: 'failed' })}</div>`],
      auth: [M.Hero({ icon: 'shield', title: "studio rejected this phone's key", body: 'The key may have been removed from authorized_keys, or the user name changed.' }), M.Code({ text: D.PUBLIC_KEY, copy: D.PUBLIC_KEY })],
      missing: [M.Hero({ icon: 'warn', title: "Sona isn't installed on studio", body: 'SSH works, but there is no Sona runtime for dev on this machine. Install it from the Sona repository with ./install.sh, then try again.' })],
      version: [M.Hero({ icon: 'refresh', title: 'Update Sona on studio', body: 'studio runs Sona 0.38.1. This app reads boards from 0.40.0 and newer. Run ./install.sh on studio to update.' })],
    };
    const buttons = {
      hostkey: [M.Button({ label: 'Remove and pair again', variant: 'danger-solid', act: 'onboard', arg: 'host' }), M.Button({ label: 'Use another machine', act: 'sheet', arg: 'machines' })],
      auth: [M.Button({ label: 'Try again', variant: 'primary', act: 'retry' }), M.Button({ label: 'Share key', icon: 'upload', act: 'toast', arg: 'Opens the share sheet with the public key.' })],
      missing: [M.Button({ label: 'Try again', variant: 'primary', act: 'retry' })],
      version: [M.Button({ label: 'Try again', variant: 'primary', act: 'retry' })],
    }[st.conn];
    return { body: nav + M.Scroll({ pad: true, body: pages[st.conn] }) + `<div class="mb-actionbar"><div class="l-col g-2">${buttons.join('')}</div></div>` };
  }

  /* ---------- Sheets ---------- */
  function sheet(st) {
    const [type, a, b] = st.sheet.split(':');
    const r = a && runOf(st, a);
    if (type === 'actions') {
      const opts = [];
      if (r.state === 'active' && r.kind === 'goal') {
        opts.push(M.SheetOption({ icon: 'pause', label: 'Pause after subtask', desc: 'Finishes the current subtask, then stops.', act: 'pause', arg: r.id, disabled: r.pauseRequested }));
        opts.push(M.SheetOption({ icon: 'stop', label: 'Pause now', desc: 'Stops the agent mid-subtask. Resume restarts that subtask.', act: 'stop', arg: r.id }));
      }
      if (r.state === 'paused' && !r.criteria) opts.push(M.SheetOption({ icon: 'play', label: 'Resume', desc: 'Continue on studio with an agent you pick.', act: 'sheet', arg: 'resume:' + r.id }));
      if (r.criteria) opts.push(M.SheetOption({ icon: 'message', label: 'Adjust in run chat', desc: 'Change the criteria with the agent before confirming.', act: 'run-chat', arg: r.id }));
      if (r.state === 'blocked') opts.push(M.SheetOption({ icon: 'wrench', label: 'Recover', desc: 'Confirm what changed and resume.', act: 'sheet', arg: 'recover:' + r.id }));
      if (r.changes) opts.push(M.SheetOption({ icon: 'diff', label: 'View changes', desc: `${r.changes.files} files`, act: 'push', arg: 'changes:' + r.id }));
      if ((r.runOptions || {}).canFinalize) opts.push(M.SheetOption({ icon: 'checkCircle', label: 'Finalize', desc: 'Removes the goal worktree unless it holds unpublished work.', act: 'finalize', arg: r.id }));
      if (r.awaitsDecision) opts.push(M.SheetOption({ icon: 'message', label: 'Decide in chat', desc: 'retry_fix, accept_and_advance or abandon_subtask', act: 'run-chat', arg: r.id }));
      if (r.state === 'done' && r.kind === 'goal') opts.push(M.SheetOption({ icon: 'external', label: 'Open PR #611', act: 'toast', arg: 'Opens the pull request in the browser.' }));
      opts.push(M.SheetOption({ icon: 'message', label: 'Run chat', desc: "Ask about this run. Fixes go to its checkout.", act: 'run-chat', arg: r.id }));
      opts.push(M.SheetOption({ icon: 'copy', label: 'Copy key', act: 'toast', arg: 'Copied ' + keyOf(r) }));
      if (['active', 'paused', 'blocked'].includes(r.state)) opts.push(M.SheetOption({ icon: 'x', label: 'Cancel ' + (r.kind === 'goal' ? 'goal' : r.op || r.phaseName), danger: true, act: 'sheet', arg: 'cancel:' + r.id }));
      return M.Sheet({ title: r.title, sub: `<span class="mono">${esc(keyOf(r))}</span> · ${esc(SB.STATES[r.state].label)}`, body: opts });
    }
    if (type === 'resume') {
      const again = r.state === 'failed';
      return M.Sheet({ title: again ? 'Run again with' : 'Resume with', sub: again ? 'Starts a new attempt on studio from the failed subtask.' : `Continues ${esc(r.id)} on studio from subtask ${r.current + 1}.`, body: D.AGENT_CHOICES.map((id) => M.SheetOption({ lead: M.Agent(id), label: id, desc: id === r.agent ? 'Ran this goal' : id === st.defaultAgent ? 'Default agent' : '', on: id === (st.pickAgent || r.agent), act: 'pick-agent', arg: id })),
        foot: [M.Button({ label: (again ? 'Run again' : 'Resume') + ' with ' + (st.pickAgent || r.agent), variant: 'primary', full: true, act: 'resume', arg: r.id })] });
    }
    if (type === 'recover') {
      const i = r.incident;
      const ok = st.recover[r.id];
      return M.Sheet({ title: 'Recover ' + r.id, sub: esc(i.next), body: [M.Group({ inset: true, title: 'Confirm before resuming', rows: i.prereqs.map(([kind, label]) => `<div class="mb-crit">${M.Checkbox({ on: ok, act: 'recover-check', arg: r.id })}<span class="mb-crit-t">${esc(label)}<br><span class="mono t-faint" style="font-size:11px">${esc(kind)}</span></span></div>`) }),
        `<div class="mb-section-pad" style="padding-top:12px">${M.Para('Preview: resumes validate on subtask 2 with codex. Completed subtasks and commits stay as they are.', { muted: true, sm: true })}</div>`],
        foot: [M.Button({ label: 'Apply and resume', variant: 'primary', full: true, act: 'recover', arg: r.id, disabled: !ok })] });
    }
    if (type === 'cancel') {
      return M.Sheet({ title: 'Cancel ' + keyOf(r) + '?', sub: 'The agent stops on studio. Commits already pushed stay. This cannot be resumed.', body: [], foot: [M.Button({ label: 'Cancel ' + (r.kind === 'goal' ? 'goal' : 'run'), variant: 'danger-solid', full: true, act: 'cancel', arg: r.id }), M.Button({ label: 'Keep running', full: true, act: 'close-sheet' })] });
    }
    if (type === 'machines') {
      return M.Sheet({ title: 'Machines', body: [D.MACHINES.map((m) => M.SheetOption({ icon: 'monitor', label: m.name, desc: `${m.user}@${m.host}`, on: m.id === st.machine, act: 'make-current', arg: m.id })), M.SheetOption({ icon: 'plus', label: 'Add machine', act: 'onboard', arg: 'host' })] });
    }
    if (type === 'default-agent' || (type === 'agent' && a === 'plan')) {
      return M.Sheet({ title: type === 'agent' ? 'Start with' : 'Default agent', body: D.AGENT_CHOICES.map((id) => M.SheetOption({ lead: M.Agent(id), label: id, on: id === st.defaultAgent, act: 'default-agent', arg: id })) });
    }
    if (type === 'agent') {
      const c = a === 'new' ? st.newChat : chatOf(st, a);
      return M.Sheet({ title: 'Agent', sub: a === 'new' ? 'The chat starts on this agent.' : 'Switching starts the next turn on this agent.', body: D.AGENT_CHOICES.map((id) => M.SheetOption({ lead: M.Agent(id), label: id, on: id === c.agent, act: 'chat-agent', arg: a + ':' + id })) });
    }
    if (type === 'target') {
      const c = a === 'new' ? st.newChat : chatOf(st, a);
      const repos = Object.values(D.REPOS).map((rp) => M.SheetOption({ lead: SB.RepoIcon(rp), label: rp.name, on: rp.name === c.repo, act: 'target-repo', arg: a + ':' + rp.name }));
      const branches = D.REPOS[c.repo].branches.map((br, i) => M.SheetOption({ icon: 'branch', label: br, desc: i === 0 ? 'Default branch' : 'Local', on: br === c.branch, act: 'target-branch', arg: a + ':' + br }));
      return M.Sheet({ title: 'Repository and branch', sub: 'Repositories added on studio. Add new ones from the desktop.', body: [`<div class="mb-group-h" style="position:static">Repository</div>`, repos, `<div class="mb-group-h" style="position:static">Base branch</div>`, branches] });
    }
    if (type === 'queue') {
      const c = chatOf(st, a);
      return M.Sheet({ title: 'Queued', sub: 'Sends in order after the current reply.', body: c.queued ? [M.SheetOption({ icon: 'message', label: c.queuedText, desc: 'Next', act: 'toast', arg: 'Long-press to edit.' })] : [M.Para('Nothing queued.', { muted: true })], foot: c.queued ? [M.Button({ label: 'Send now', variant: 'primary', full: true, act: 'queue-send', arg: a }), M.Button({ label: 'Remove', variant: 'danger', full: true, act: 'queue-remove', arg: a })] : [] });
    }
    if (type === 'chat-changes') {
      const c = chatOf(st, a);
      return M.Sheet({ title: 'Chat changes', sub: `${c.changes.files} files <span class="t-done">+${c.changes.add}</span> <span class="t-failed">−${c.changes.del}</span> · uncommitted on <span class="mono">${esc(c.branch)}</span>`, body: [M.FileRow({ status: 'M', name: 'ArticleCache.kt', dir: 'app/src/main/…/cache', add: 9, del: 3, act: 'push', arg: 'diff:NEWS-12:0' }), M.FileRow({ status: 'M', name: 'ArticleCacheTest.kt', dir: 'app/src/test/…/cache', add: 5, del: 0, act: 'push', arg: 'diff:NEWS-12:1' })] });
    }
    if (type === 'start-plan') {
      return M.Sheet({ title: 'Start ' + a + '?', sub: `Runs the goal on studio from this spec bundle with ${esc(st.defaultAgent)}.`, body: [], foot: [M.Button({ label: 'Start goal', variant: 'primary', full: true, act: 'start-plan', arg: a }), M.Button({ label: 'Change agent', full: true, act: 'sheet', arg: 'agent:plan' })] });
    }
    if (type === 'plan-repo') return M.Sheet({ title: 'Project', body: Object.values(D.REPOS).map((rp) => M.SheetOption({ lead: SB.RepoIcon(rp), label: rp.name, on: rp.name === 'skill-bill', act: 'close-sheet' })) });
    if (type === 'remove-machine') {
      return M.Sheet({ title: 'Remove ' + a + '?', sub: "Deletes the trusted host key and stops pushes from this machine. Remove the phone's key from authorized_keys on the machine yourself.", body: [], foot: [M.Button({ label: 'Remove machine', variant: 'danger-solid', full: true, act: 'close-sheet' }), M.Button({ label: 'Keep', full: true, act: 'close-sheet' })] });
    }
    return '';
  }

  /* ---------- Root ---------- */
  function render(st) {
    let out;
    if (st.lock) {
      out = { body: M.LockScreen({ pushes: [0, 1, 2].map((i) => pushFor(st, i)) }) };
    } else if (st.onboard) {
      out = onboarding(st);
    } else if (['hostkey', 'auth', 'missing', 'version'].includes(st.conn)) {
      out = blocked(st);
    } else {
      const stack = st.stacks[st.tab];
      const top = stack[stack.length - 1];
      if (!top) out = { needs: needsScreen, work: workScreen, chats: chatsScreen, plans: plansScreen, settings: settingsScreen }[st.tab](st);
      else {
        const [s, ...rest] = top.split(':');
        const arg = rest.join(':');
        out = { detail: detailScreen, subtask: subtaskScreen, changes: changesScreen, diff: diffScreen, chat: chatScreen, newchat: newChatScreen, plan: planScreen, planfile: planFileScreen, machine: machineScreen, notifications: notificationsScreen }[s](st, arg);
      }
    }
    let over = out.over || '';
    if (st.sheet) over += sheet(st);
    if (st.toast) over += M.Toast(st.toast);
    if (st.swipe) over += '';
    return { body: out.body, over };
  }

  /* Swipe actions on a Needs you row, drawn at rest for the States gallery. */
  function swipeExample(st) {
    const r = runOf(st, 'INF-7');
    const p = runOf(st, 'PAY-88');
    const nav = M.NavBar({ large: true, title: 'Needs you', sub: freshness(st) });
    const rows = [
      M.SwipeRow({ row: workRow(st, r, { badge: true }), actions: [{ icon: 'wrench', label: 'Recover', color: 'var(--st-blocked)', act: 'sheet', arg: 'recover:INF-7' }, { icon: 'more', label: 'More', color: 'var(--border-strong)', act: 'sheet', arg: 'actions:INF-7' }] }),
      M.SwipeRow({ row: workRow(st, p, { badge: true }), actions: [{ icon: 'play', label: 'Resume', color: 'var(--st-done)', act: 'sheet', arg: 'resume:PAY-88' }, { icon: 'more', label: 'More', color: 'var(--border-strong)', act: 'sheet', arg: 'actions:PAY-88' }] }),
    ];
    return { body: nav + M.Scroll({ tabs: true, body: [M.Group({ title: 'Waiting on you', count: 4, rows }), M.Group({ rows: [], foot: 'Swipe left on a row for its primary action. Long-press opens the full action sheet. No row multi-select.' })] }) + tabBar(st) };
  }

  window.MS = { render, swipeExample, runOf };
})();
